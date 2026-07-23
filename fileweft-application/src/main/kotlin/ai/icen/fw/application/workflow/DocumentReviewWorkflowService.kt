package ai.icen.fw.application.workflow

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentLifecycleMutationGuard
import ai.icen.fw.application.delivery.DocumentDeliveryPlanner
import ai.icen.fw.application.delivery.DocumentDeliveryPreparation
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.lifecycle.DocumentLifecycleMutationContext
import ai.icen.fw.application.lifecycle.DocumentLifecycleMutationTransaction
import ai.icen.fw.application.lifecycle.ValidatedDocumentLifecycleMutation
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.security.validatedTrustedUserId
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentMutationRepository
import ai.icen.fw.domain.document.LifecycleCommand
import ai.icen.fw.domain.workflow.WorkflowInstance
import ai.icen.fw.domain.workflow.WorkflowInstanceRepository
import ai.icen.fw.domain.workflow.WorkflowState
import ai.icen.fw.domain.workflow.WorkflowTask
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest

/** Local, persistent review workflow that gates document publication. */
class DocumentReviewWorkflowService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentMutationRepository,
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
        val snapshot = transaction.execute {
            val document = documentRepository.findById(context.tenantId, context.documentId)
                ?: throw DocumentNotFoundException(context.documentId)
            if (document.tenantId != context.tenantId || document.id != context.documentId) {
                throw DocumentNotFoundException(context.documentId)
            }
            val routeRequest = DocumentReviewRouteRequest(
                tenantId = context.tenantId,
                documentId = document.id,
                documentNumber = document.documentNumber,
                documentTitle = document.title,
                submittedBy = context.operator.id,
                requestedReviewerId = reviewerId,
            )
            // An already active review makes the submission return the existing
            // workflow, so resolving a new route for it would be wasted work.
            routeRequest to workflowRepository.findActiveByDocument(context.tenantId, context.documentId)
        }
        val routeRequest = snapshot.first
        if (snapshot.second != null) {
            return DocumentReviewSubmitPreparation(
                lifecycle = context,
                reviewerId = reviewerId,
                routeRequest = routeRequest,
                resolvedRoute = null,
            )
        }
        // A policy provider may be remote; it must not run while FileWeft owns a database transaction.
        val resolvedRoute = reviewRoutes.resolve(reviewRouteId, routeRequest)
        validateRouteAssignees(resolvedRoute)
        return DocumentReviewSubmitPreparation(
            lifecycle = context,
            reviewerId = reviewerId,
            routeRequest = routeRequest,
            resolvedRoute = resolvedRoute,
        )
    }

    private fun validateRouteAssignees(resolvedRoute: ResolvedDocumentReviewRoute) {
        resolvedRoute.route.tasks.forEachIndexed { taskIndex, routeTask ->
            val assigneeId = routeTask.assigneeId?.value ?: return@forEachIndexed
            try {
                validatedTrustedUserId(assigneeId, "Review route assignee id")
            } catch (failure: IllegalArgumentException) {
                throw DocumentReviewRouteConfigurationException(
                    "Document review route ${resolvedRoute.routeId} returned an invalid assignee id at task index $taskIndex.",
                    failure,
                )
            }
        }
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
        val activeWorkflow = workflowRepository.findActiveByDocument(context.tenantId, context.documentId)
        if (activeWorkflow != null) {
            // Submission is naturally idempotent per document: the unique
            // active-workflow index already guarantees a single active review,
            // so a repeated submission returns it instead of conflicting. This
            // branch mutates nothing and must not append a second submission
            // audit record.
            if (
                activeWorkflow.tenantId != context.tenantId ||
                activeWorkflow.documentId != document.id ||
                activeWorkflow.state != WorkflowState.PENDING
            ) {
                throw DocumentReviewConflictException("Document already has an active review workflow.")
            }
            return DocumentReviewMutationResult(document, activeWorkflow)
        }
        val resolvedRoute = preparation.resolvedRoute
            ?: throw DocumentReviewConflictException(
                "Document review workflow changed while its review route was resolved; retry submission.",
            )
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
            workflowType = resolvedRoute.route.workflowType,
            state = WorkflowState.PENDING,
            tasks = resolvedRoute.route.tasks.map { routeTask ->
                WorkflowTask(
                    id = identifierGenerator.nextId(),
                    tenantId = context.tenantId,
                    workflowId = workflowId,
                    assigneeId = routeTask.assigneeId,
                )
            },
            submittedBy = context.operator.id,
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
                "reviewRouteId" to resolvedRoute.routeId,
                "reviewerIds" to workflow.tasks.joinToString(",") { task -> task.assigneeId?.value ?: "UNASSIGNED" },
            ),
        )
        return DocumentReviewMutationResult(document, workflow)
    }

    fun withdraw(workflowId: Identifier): Document = executeWithdrawal(workflowId, null)

    @JvmSynthetic
    internal fun withdraw(
        workflowId: Identifier,
        guard: DocumentLifecycleMutationGuard,
    ): Document = executeWithdrawal(workflowId, guard)

    private fun executeWithdrawal(
        workflowId: Identifier,
        guard: DocumentLifecycleMutationGuard?,
    ): Document {
        val withdrawal = prepareReviewWithdrawal(workflowId, guard)
        val validated = revalidateReviewWithdrawal(withdrawal)
        return hideWorkflowDocumentVisibilityFailure(workflowId) {
            transaction.execute {
                DocumentLifecycleMutationTransaction.execute {
                    withdrawInCurrentTransaction(validated, withdrawal).document
                }
            }
        }
    }

    @JvmSynthetic
    internal fun prepareReviewWithdrawal(
        workflowId: Identifier,
        guard: DocumentLifecycleMutationGuard?,
    ): DocumentReviewWithdrawalContext {
        requireNotNull(auditTrail) {
            "Review withdrawal requires an audit trail."
        }
        val tenant = tenantProvider.currentTenant()
        // Authenticate before the tenant-scoped lookup so an anonymous caller
        // cannot use withdrawal to probe workflow identifiers.
        val operator = authorization.requireCurrentUser()
        val workflowSnapshot = transaction.execute {
            workflowRepository.findById(tenant.tenantId, workflowId)
                ?.takeIf { workflow -> workflow.tenantId == tenant.tenantId && workflow.id == workflowId }
                ?: throw WorkflowNotFoundException(workflowId)
        }
        val authorizedAsSubmitter = workflowSnapshot.submittedBy == operator.id
        val lifecycle = hideWorkflowDocumentVisibilityFailure(workflowId) {
            val authorizedOperator = if (authorizedAsSubmitter) {
                operator
            } else {
                authorization.requireDocumentActionAs(
                    tenant.tenantId,
                    workflowSnapshot.documentId,
                    WITHDRAW_ACTION,
                    operator,
                )
            }
            DocumentLifecycleMutationContext.prepare(
                tenantId = tenant.tenantId,
                operator = authorizedOperator,
                documentId = workflowSnapshot.documentId,
                action = WITHDRAW_ACTION,
                guard = guard,
                // A trusted submitter owns the withdrawal decision, but a
                // catalog host must still recheck current document visibility.
                guardAction = if (authorizedAsSubmitter) SUBMITTER_VISIBILITY_ACTION else WITHDRAW_ACTION,
            )
        }
        return DocumentReviewWithdrawalContext(
            lifecycle = lifecycle,
            workflowId = workflowId,
            submittedBySnapshot = workflowSnapshot.submittedBy,
            authorizedAsSubmitter = authorizedAsSubmitter,
        )
    }

    @JvmSynthetic
    internal fun revalidateReviewWithdrawal(
        withdrawal: DocumentReviewWithdrawalContext,
    ): ValidatedDocumentLifecycleMutation = hideWorkflowDocumentVisibilityFailure(withdrawal.workflowId) {
        withdrawal.lifecycle.revalidate()
    }

    @JvmSynthetic
    internal fun withdrawInCurrentTransaction(
        validated: ValidatedDocumentLifecycleMutation,
        withdrawal: DocumentReviewWithdrawalContext,
    ): DocumentReviewMutationResult {
        DocumentLifecycleMutationTransaction.requireActive()
        val context = validated.contextFor(WITHDRAW_ACTION)
        require(withdrawal.lifecycle === context) {
            "Review withdrawal does not belong to this lifecycle operation."
        }
        // Preserve the same document -> asset -> workflow lock order as review
        // decisions so withdrawal and the final vote have one serial outcome.
        val document = documentRepository.findForMutation(context.tenantId, context.documentId)
            ?: throw DocumentNotFoundException(context.documentId)
        if (document.tenantId != context.tenantId || document.id != context.documentId) {
            throw DocumentNotFoundException(context.documentId)
        }
        validated.verifyLocked(document, WITHDRAW_ACTION)
        val workflow = workflowRepository.findForDecision(context.tenantId, withdrawal.workflowId)
            ?: throw WorkflowNotFoundException(withdrawal.workflowId)
        if (
            workflow.tenantId != context.tenantId ||
            workflow.id != withdrawal.workflowId ||
            workflow.documentId != document.id ||
            workflow.submittedBy != withdrawal.submittedBySnapshot ||
            (withdrawal.authorizedAsSubmitter && workflow.submittedBy != context.operator.id)
        ) {
            throw WorkflowNotFoundException(withdrawal.workflowId)
        }
        workflow.withdraw()
        document.transition(LifecycleCommand.WITHDRAW_REVIEW)
        workflowRepository.save(workflow)
        documentRepository.save(document)
        checkNotNull(auditTrail).record(
            tenantId = context.tenantId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = document.id,
            action = WITHDRAWN_AUDIT_ACTION,
            operatorId = context.operator.id,
            operatorName = context.operator.displayName,
            details = mapOf(
                "workflowId" to workflow.id.value,
                "workflowState" to workflow.state.name,
                "authorizationBasis" to if (withdrawal.authorizedAsSubmitter) "SUBMITTER" else "POLICY",
            ),
        )
        return DocumentReviewMutationResult(document, workflow)
    }

    /** Keeps catalog revocation and a concurrent document disappearance indistinguishable from a missing workflow. */
    @JvmSynthetic
    internal fun withdrawSafelyInCurrentTransaction(
        validated: ValidatedDocumentLifecycleMutation,
        withdrawal: DocumentReviewWithdrawalContext,
    ): DocumentReviewMutationResult = hideWorkflowDocumentVisibilityFailure(withdrawal.workflowId) {
        withdrawInCurrentTransaction(validated, withdrawal)
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
            workflow.approve(decision.taskId, context.operator.id, context.operator.displayName, comment)
            if (workflow.state == ai.icen.fw.domain.workflow.WorkflowState.APPROVED) {
                document.transition(LifecycleCommand.APPROVE)
                deliveryPlanner.plan(document, checkNotNull(delivery).preparation)
            }
        } else {
            workflow.reject(decision.taskId, context.operator.id, context.operator.displayName, comment)
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

    companion object {
        const val REVIEW_WORKFLOW_TYPE = "DOCUMENT_REVIEW"
        const val SUBMIT_ACTION = "document:submit"
        const val AUDIT_ACTION = "document:audit"
        const val WITHDRAW_ACTION = "document:review:withdraw"
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val SUBMITTED_AUDIT_ACTION = "document:review:submit"
        const val APPROVED_AUDIT_ACTION = "document:review:approve"
        const val REJECTED_AUDIT_ACTION = "document:review:reject"
        const val WITHDRAWN_AUDIT_ACTION = "document:review:withdraw"
        private const val SUBMITTER_VISIBILITY_ACTION = "document:read"
    }
}

class WorkflowNotFoundException(workflowId: Identifier) :
    NoSuchElementException("Workflow ${workflowId.value} was not found in the current tenant.")
