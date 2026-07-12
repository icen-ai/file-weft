package com.fileweft.application.workflow

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.catalog.DocumentLifecycleMutationGuard
import com.fileweft.application.delivery.DocumentDeliveryPlanner
import com.fileweft.application.delivery.DocumentDeliveryPreparation
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.lifecycle.DocumentLifecycleMutationContext
import com.fileweft.application.lifecycle.DocumentLifecycleMutationTransaction
import com.fileweft.application.lifecycle.ValidatedDocumentLifecycleMutation
import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.security.ApplicationForbiddenException
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.workflow.WorkflowInstance
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.domain.workflow.WorkflowTask
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.spi.workflow.DocumentReviewRouteRequest

/** Local, persistent review workflow that gates document publication. */
class DocumentReviewWorkflowService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentRepository,
    private val workflowRepository: WorkflowInstanceRepository,
    private val deliveryPlanner: DocumentDeliveryPlanner,
    private val identifierGenerator: IdentifierGenerator,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
    private val reviewRoutes: DocumentReviewRouteResolver = DocumentReviewRouteResolver(),
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun submit(documentId: Identifier, reviewerId: Identifier? = null): WorkflowInstance =
        executeSubmit(documentId, reviewerId, null, null)

    fun submit(documentId: Identifier, reviewerId: Identifier?, reviewRouteId: String?): WorkflowInstance =
        executeSubmit(documentId, reviewerId, reviewRouteId, null)

    @JvmSynthetic
    internal fun submit(
        documentId: Identifier,
        reviewerId: Identifier?,
        reviewRouteId: String?,
        guard: DocumentLifecycleMutationGuard,
    ): WorkflowInstance = executeSubmit(documentId, reviewerId, reviewRouteId, guard)

    private fun executeSubmit(
        documentId: Identifier,
        reviewerId: Identifier?,
        reviewRouteId: String?,
        guard: DocumentLifecycleMutationGuard?,
    ): WorkflowInstance {
        val context = prepareSubmitForReview(documentId, guard)
        val preparation = prepareSubmitForReviewRoute(context, reviewerId, reviewRouteId)
        val validated = context.revalidate()
        return transaction.execute {
            DocumentLifecycleMutationTransaction.execute {
                submitForReviewInCurrentTransaction(validated, preparation).workflow
            }
        }
    }

    @JvmSynthetic
    internal fun prepareSubmitForReview(
        documentId: Identifier,
        guard: DocumentLifecycleMutationGuard?,
    ): DocumentLifecycleMutationContext {
        val tenant = tenantProvider.currentTenant()
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, SUBMIT_ACTION)
        return DocumentLifecycleMutationContext.prepare(
            tenantId = tenant.tenantId,
            operator = operator,
            documentId = documentId,
            action = SUBMIT_ACTION,
            guard = guard,
        )
    }

    @JvmSynthetic
    internal fun prepareSubmitForReviewRoute(
        context: DocumentLifecycleMutationContext,
        reviewerId: Identifier?,
        reviewRouteId: String?,
    ): DocumentReviewSubmitPreparation {
        require(context.action == SUBMIT_ACTION) {
            "Lifecycle mutation context belongs to a different action."
        }
        val routeRequest = transaction.execute {
            val document = documentRepository.findById(context.tenantId, context.documentId)
                ?: throw DocumentNotFoundException(context.documentId)
            if (document.tenantId != context.tenantId || document.id != context.documentId) {
                throw DocumentNotFoundException(context.documentId)
            }
            requireNoActiveReview(context.tenantId, context.documentId)
            DocumentReviewRouteRequest(
                tenantId = context.tenantId,
                documentId = document.id,
                documentNumber = document.documentNumber,
                documentTitle = document.title,
                submittedBy = context.operator.id,
                requestedReviewerId = reviewerId,
            )
        }
        // A policy provider may be remote; it must not run while FileWeft owns a database transaction.
        val resolvedRoute = reviewRoutes.resolve(reviewRouteId, routeRequest)
        return DocumentReviewSubmitPreparation(
            lifecycle = context,
            reviewerId = reviewerId,
            routeRequest = routeRequest,
            resolvedRoute = resolvedRoute,
        )
    }

    @JvmSynthetic
    internal fun submitForReviewInCurrentTransaction(
        validated: ValidatedDocumentLifecycleMutation,
        preparation: DocumentReviewSubmitPreparation,
    ): DocumentReviewMutationResult {
        DocumentLifecycleMutationTransaction.requireActive()
        val context = validated.contextFor(SUBMIT_ACTION)
        require(preparation.lifecycle === context) {
            "Review route preparation does not belong to this lifecycle operation."
        }
        require(
            preparation.routeRequest.tenantId == context.tenantId &&
                preparation.routeRequest.documentId == context.documentId &&
                preparation.routeRequest.submittedBy == context.operator.id &&
                preparation.routeRequest.requestedReviewerId == preparation.reviewerId
        ) {
            "Review route preparation does not match the authorized request."
        }
        val document = documentRepository.findForMutation(context.tenantId, context.documentId)
            ?: throw DocumentNotFoundException(context.documentId)
        if (document.tenantId != context.tenantId || document.id != context.documentId) {
            throw DocumentNotFoundException(context.documentId)
        }
        // Preserve the shared document -> asset -> workflow lock order.
        validated.verifyLocked(document, SUBMIT_ACTION)
        requireNoActiveReview(context.tenantId, context.documentId)
        if (
            document.documentNumber != preparation.routeRequest.documentNumber ||
            document.title != preparation.routeRequest.documentTitle
        ) {
            throw DocumentReviewConflictException(
                "Document changed while its review route was resolved; retry submission.",
            )
        }
        document.transition(LifecycleCommand.SUBMIT)
        val workflowId = identifierGenerator.nextId()
        val workflow = WorkflowInstance(
            id = workflowId,
            tenantId = context.tenantId,
            documentId = document.id,
            workflowType = preparation.resolvedRoute.route.workflowType,
            tasks = preparation.resolvedRoute.route.tasks.map { routeTask ->
                WorkflowTask(
                    id = identifierGenerator.nextId(),
                    tenantId = context.tenantId,
                    workflowId = workflowId,
                    assigneeId = routeTask.assigneeId,
                )
            },
        )
        documentRepository.save(document)
        workflowRepository.save(workflow)
        auditTrail?.record(
            tenantId = context.tenantId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = document.id,
            action = SUBMITTED_AUDIT_ACTION,
            operatorId = context.operator.id,
            operatorName = context.operator.displayName,
            details = mapOf(
                "workflowId" to workflow.id.value,
                "reviewerId" to (preparation.reviewerId?.value ?: "UNASSIGNED"),
                "reviewRouteId" to preparation.resolvedRoute.routeId,
                "reviewerIds" to workflow.tasks.joinToString(",") { task -> task.assigneeId?.value ?: "UNASSIGNED" },
            ),
        )
        return DocumentReviewMutationResult(document, workflow)
    }

    fun approve(workflowId: Identifier, taskId: Identifier, comment: String? = null): Document =
        executeDecision(workflowId, taskId, comment, approved = true, deliveryProfileId = null, guard = null)

    fun approve(workflowId: Identifier, taskId: Identifier, comment: String?, deliveryProfileId: String?): Document =
        executeDecision(workflowId, taskId, comment, approved = true, deliveryProfileId = deliveryProfileId, guard = null)

    @JvmSynthetic
    internal fun approve(
        workflowId: Identifier,
        taskId: Identifier,
        comment: String?,
        deliveryProfileId: String?,
        guard: DocumentLifecycleMutationGuard,
    ): Document = executeDecision(
        workflowId,
        taskId,
        comment,
        approved = true,
        deliveryProfileId = deliveryProfileId,
        guard = guard,
    )

    fun reject(workflowId: Identifier, taskId: Identifier, comment: String? = null): Document =
        executeDecision(workflowId, taskId, comment, approved = false, deliveryProfileId = null, guard = null)

    @JvmSynthetic
    internal fun reject(
        workflowId: Identifier,
        taskId: Identifier,
        comment: String?,
        guard: DocumentLifecycleMutationGuard,
    ): Document = executeDecision(
        workflowId,
        taskId,
        comment,
        approved = false,
        deliveryProfileId = null,
        guard = guard,
    )

    private fun executeDecision(
        workflowId: Identifier,
        taskId: Identifier,
        comment: String?,
        approved: Boolean,
        deliveryProfileId: String?,
        guard: DocumentLifecycleMutationGuard?,
    ): Document {
        val decision = prepareReviewDecision(workflowId, taskId, approved, guard)
        var delivery = prepareInitialReviewDelivery(decision, deliveryProfileId)
        while (true) {
            val validated = revalidateReviewDecision(decision)
            try {
                return hideWorkflowDocumentVisibilityFailure(workflowId) {
                    transaction.execute {
                        DocumentLifecycleMutationTransaction.execute {
                            decideInCurrentTransaction(validated, decision, comment, delivery).document
                        }
                    }
                }
            } catch (_: DocumentReviewDeliveryPreparationRequiredException) {
                delivery = prepareCompletingReviewDelivery(decision, deliveryProfileId)
            }
        }
    }

    @JvmSynthetic
    internal fun prepareReviewDecision(
        workflowId: Identifier,
        taskId: Identifier,
        approved: Boolean,
        guard: DocumentLifecycleMutationGuard?,
    ): DocumentReviewDecisionContext {
        val tenant = tenantProvider.currentTenant()
        // Authenticate before the workflow lookup so an anonymous caller
        // cannot probe workflow identifiers through repository behavior.
        val operator = authorization.requireCurrentUser()
        // Existing authorization providers know document resources only. A
        // single tenant-scoped snapshot therefore resolves that resource; a
        // missing workflow and a denied document are both exposed as the same
        // workflow-not-found result below.
        val workflowSnapshot = transaction.execute {
            workflowRepository.findById(tenant.tenantId, workflowId)
                ?.takeIf { workflow -> workflow.tenantId == tenant.tenantId && workflow.id == workflowId }
                ?: throw WorkflowNotFoundException(workflowId)
        }
        val lifecycle = hideWorkflowDocumentVisibilityFailure(workflowId) {
            val authorizedOperator = authorization.requireDocumentActionAs(
                tenant.tenantId,
                workflowSnapshot.documentId,
                AUDIT_ACTION,
                operator,
            )
            // Catalog preparation repeats the document action decision after
            // its local binding snapshot. Revocation remains indistinguishable
            // from a missing workflow.
            DocumentLifecycleMutationContext.prepare(
                tenantId = tenant.tenantId,
                operator = authorizedOperator,
                documentId = workflowSnapshot.documentId,
                action = AUDIT_ACTION,
                guard = guard,
            )
        }
        return DocumentReviewDecisionContext(
            lifecycle = lifecycle,
            workflowId = workflowId,
            taskId = taskId,
            approved = approved,
            workflowSnapshot = workflowSnapshot,
        )
    }

    @JvmSynthetic
    internal fun prepareInitialReviewDelivery(
        decision: DocumentReviewDecisionContext,
        deliveryProfileId: String?,
    ): DocumentReviewDecisionDelivery? {
        if (!decision.approved) return null
        val completes = decision.workflowSnapshot.willCompleteAfterApproval(
            decision.taskId,
            decision.lifecycle.operator.id,
        )
        return if (completes) {
            prepareCompletingReviewDelivery(decision, deliveryProfileId)
        } else {
            null
        }
    }

    @JvmSynthetic
    internal fun prepareCompletingReviewDelivery(
        decision: DocumentReviewDecisionContext,
        deliveryProfileId: String?,
    ): DocumentReviewDecisionDelivery {
        require(decision.approved) { "Only an approval can prepare document delivery." }
        val context = decision.lifecycle
        // Confirm the document before invoking a potentially remote delivery
        // policy. The final transaction repeats identity and lifecycle checks.
        hideWorkflowDocumentVisibilityFailure(decision.workflowId) {
            transaction.execute {
                val document = documentRepository.findById(context.tenantId, context.documentId)
                    ?: throw DocumentNotFoundException(context.documentId)
                if (document.tenantId != context.tenantId || document.id != context.documentId) {
                    throw DocumentNotFoundException(context.documentId)
                }
            }
        }
        return DocumentReviewDecisionDelivery(
            decision = decision,
            preparation = deliveryPlanner.prepare(context.tenantId, deliveryProfileId),
        )
    }

    @JvmSynthetic
    internal fun revalidateReviewDecision(
        decision: DocumentReviewDecisionContext,
    ): ValidatedDocumentLifecycleMutation = hideWorkflowDocumentVisibilityFailure(decision.workflowId) {
        decision.lifecycle.revalidate()
    }

    @JvmSynthetic
    internal fun decideInCurrentTransaction(
        validated: ValidatedDocumentLifecycleMutation,
        decision: DocumentReviewDecisionContext,
        comment: String?,
        delivery: DocumentReviewDecisionDelivery?,
    ): DocumentReviewMutationResult {
        DocumentLifecycleMutationTransaction.requireActive()
        val context = validated.contextFor(AUDIT_ACTION)
        require(decision.lifecycle === context) {
            "Review decision does not belong to this lifecycle operation."
        }
        if (delivery != null) {
            require(decision.approved && delivery.decision === decision) {
                "Review delivery preparation does not belong to this decision."
            }
            require(delivery.preparation.tenantId == context.tenantId) {
                "Review delivery preparation belongs to a different tenant."
            }
        }
        // Keep document -> asset -> workflow lock order everywhere. An
        // idempotent caller has already locked its claim before entering here.
        val document = documentRepository.findForMutation(context.tenantId, context.documentId)
            ?: throw DocumentNotFoundException(context.documentId)
        if (document.tenantId != context.tenantId || document.id != context.documentId) {
            throw DocumentNotFoundException(context.documentId)
        }
        validated.verifyLocked(document, AUDIT_ACTION)
        val workflow = workflowRepository.findForDecision(context.tenantId, decision.workflowId)
            ?: throw WorkflowNotFoundException(decision.workflowId)
        if (
            workflow.tenantId != context.tenantId ||
            workflow.id != decision.workflowId ||
            workflow.documentId != document.id
        ) {
            throw WorkflowNotFoundException(decision.workflowId)
        }
        val completingApproval = decision.approved && workflow.willCompleteAfterApproval(
            decision.taskId,
            context.operator.id,
        )
        if (completingApproval && delivery == null) {
            // Throw before any domain mutation so the claim and every local
            // read/write in this attempt roll back together.
            throw DocumentReviewDeliveryPreparationRequiredException()
        }
        if (decision.approved) {
            workflow.approve(decision.taskId, context.operator.id, comment)
            if (workflow.state == com.fileweft.domain.workflow.WorkflowState.APPROVED) {
                document.transition(LifecycleCommand.APPROVE)
                deliveryPlanner.plan(document, checkNotNull(delivery).preparation)
            }
        } else {
            workflow.reject(decision.taskId, context.operator.id, comment)
            document.transition(LifecycleCommand.REJECT)
        }
        workflowRepository.save(workflow)
        documentRepository.save(document)
        auditTrail?.record(
            tenantId = context.tenantId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = document.id,
            action = if (decision.approved) APPROVED_AUDIT_ACTION else REJECTED_AUDIT_ACTION,
            operatorId = context.operator.id,
            operatorName = context.operator.displayName,
            details = linkedMapOf<String, String>().apply {
                put("workflowId", workflow.id.value)
                put("taskId", decision.taskId.value)
                put("workflowState", workflow.state.name)
                comment?.let { put("comment", it) }
            },
        )
        return DocumentReviewMutationResult(document, workflow)
    }

    /** Keeps a concurrent document visibility change indistinguishable from a missing workflow. */
    @JvmSynthetic
    internal fun decideSafelyInCurrentTransaction(
        validated: ValidatedDocumentLifecycleMutation,
        decision: DocumentReviewDecisionContext,
        comment: String?,
        delivery: DocumentReviewDecisionDelivery?,
    ): DocumentReviewMutationResult = hideWorkflowDocumentVisibilityFailure(decision.workflowId) {
        decideInCurrentTransaction(validated, decision, comment, delivery)
    }

    private fun <T> hideWorkflowDocumentVisibilityFailure(workflowId: Identifier, action: () -> T): T = try {
        action()
    } catch (_: ApplicationForbiddenException) {
        throw WorkflowNotFoundException(workflowId)
    } catch (_: DocumentNotFoundException) {
        throw WorkflowNotFoundException(workflowId)
    }

    private fun requireNoActiveReview(tenantId: Identifier, documentId: Identifier) {
        if (workflowRepository.findActiveByDocument(tenantId, documentId) != null) {
            throw DocumentReviewConflictException("Document already has an active review workflow.")
        }
    }

    companion object {
        const val REVIEW_WORKFLOW_TYPE = "DOCUMENT_REVIEW"
        const val SUBMIT_ACTION = "document:submit"
        const val AUDIT_ACTION = "document:audit"
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val SUBMITTED_AUDIT_ACTION = "document:review:submit"
        const val APPROVED_AUDIT_ACTION = "document:review:approve"
        const val REJECTED_AUDIT_ACTION = "document:review:reject"
    }
}

class WorkflowNotFoundException(workflowId: Identifier) :
    NoSuchElementException("Workflow ${workflowId.value} was not found in the current tenant.")
