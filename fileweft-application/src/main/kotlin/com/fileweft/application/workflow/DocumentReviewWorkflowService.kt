package com.fileweft.application.workflow

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.catalog.DocumentLifecycleMutationGuard
import com.fileweft.application.catalog.DocumentLifecycleMutationPermit
import com.fileweft.application.delivery.DocumentDeliveryPlanner
import com.fileweft.application.delivery.DocumentDeliveryPreparation
import com.fileweft.application.document.DocumentNotFoundException
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
        val tenant = tenantProvider.currentTenant()
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, SUBMIT_ACTION)
        val permit: DocumentLifecycleMutationPermit? = if (guard == null) {
            null
        } else {
            guard.prepareLifecycle(tenant.tenantId, operator, documentId, SUBMIT_ACTION)
        }
        val routeRequest = transaction.execute {
            val document = documentRepository.findById(tenant.tenantId, documentId)
                ?: throw DocumentNotFoundException(documentId)
            if (document.tenantId != tenant.tenantId || document.id != documentId) {
                throw DocumentNotFoundException(documentId)
            }
            requireNoActiveReview(tenant.tenantId, documentId)
            DocumentReviewRouteRequest(
                tenantId = tenant.tenantId,
                documentId = document.id,
                documentNumber = document.documentNumber,
                documentTitle = document.title,
                submittedBy = operator.id,
                requestedReviewerId = reviewerId,
            )
        }
        // A policy provider may be remote; it must not run while FileWeft owns a database transaction.
        val resolvedRoute = reviewRoutes.resolve(reviewRouteId, routeRequest)
        if (guard != null) {
            guard.revalidateLifecycle(tenant.tenantId, operator, documentId, checkNotNull(permit))
        }
        return transaction.execute {
            val document = documentRepository.findForMutation(tenant.tenantId, documentId)
                ?: throw DocumentNotFoundException(documentId)
            if (document.tenantId != tenant.tenantId || document.id != documentId) {
                throw DocumentNotFoundException(documentId)
            }
            if (guard != null) {
                // Preserve the shared document -> asset -> workflow lock order.
                guard.verifyLifecycleLocked(tenant.tenantId, document, checkNotNull(permit))
            }
            requireNoActiveReview(tenant.tenantId, documentId)
            if (document.documentNumber != routeRequest.documentNumber || document.title != routeRequest.documentTitle) {
                throw DocumentReviewConflictException(
                    "Document changed while its review route was resolved; retry submission.",
                )
            }
            document.transition(LifecycleCommand.SUBMIT)
            val workflowId = identifierGenerator.nextId()
            val workflow = WorkflowInstance(
                id = workflowId,
                tenantId = tenant.tenantId,
                documentId = document.id,
                workflowType = resolvedRoute.route.workflowType,
                tasks = resolvedRoute.route.tasks.map { routeTask ->
                    WorkflowTask(
                        id = identifierGenerator.nextId(),
                        tenantId = tenant.tenantId,
                        workflowId = workflowId,
                        assigneeId = routeTask.assigneeId,
                    )
                },
            )
            documentRepository.save(document)
            workflowRepository.save(workflow)
            auditTrail?.record(
                tenantId = tenant.tenantId,
                resourceType = DOCUMENT_RESOURCE_TYPE,
                resourceId = document.id,
                action = SUBMITTED_AUDIT_ACTION,
                operatorId = operator.id,
                operatorName = operator.displayName,
                details = mapOf(
                    "workflowId" to workflow.id.value,
                    "reviewerId" to (reviewerId?.value ?: "UNASSIGNED"),
                    "reviewRouteId" to resolvedRoute.routeId,
                    "reviewerIds" to workflow.tasks.joinToString(",") { task -> task.assigneeId?.value ?: "UNASSIGNED" },
                ),
            )
            workflow
        }
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
        val permit: DocumentLifecycleMutationPermit? = hideWorkflowDocumentVisibilityFailure(workflowId) {
            authorization.requireDocumentActionAs(
                tenant.tenantId,
                workflowSnapshot.documentId,
                AUDIT_ACTION,
                operator,
            )
            if (guard == null) {
                null
            } else {
                // Catalog preparation repeats the document action decision
                // after its local binding snapshot. A concurrent revocation
                // must remain indistinguishable from a missing workflow.
                guard.prepareLifecycle(tenant.tenantId, operator, workflowSnapshot.documentId, AUDIT_ACTION)
            }
        }
        val snapshotCompletesApproval = approved && workflowSnapshot.willCompleteAfterApproval(taskId, operator.id)
        if (snapshotCompletesApproval && guard == null) {
            // Flat mode has no catalog guard snapshot. Confirm the referenced
            // document before invoking a potentially remote delivery policy;
            // the final mutation transaction still repeats this validation.
            hideWorkflowDocumentVisibilityFailure(workflowId) {
                transaction.execute {
                    val document = documentRepository.findById(tenant.tenantId, workflowSnapshot.documentId)
                        ?: throw DocumentNotFoundException(workflowSnapshot.documentId)
                    if (
                        document.tenantId != tenant.tenantId ||
                        document.id != workflowSnapshot.documentId
                    ) {
                        throw DocumentNotFoundException(workflowSnapshot.documentId)
                    }
                }
            }
        }
        var preparation: DocumentDeliveryPreparation? = if (snapshotCompletesApproval) {
            deliveryPlanner.prepare(tenant.tenantId, deliveryProfileId)
        } else {
            null
        }
        if (guard != null) {
            hideWorkflowDocumentVisibilityFailure(workflowId) {
                guard.revalidateLifecycle(
                    tenant.tenantId,
                    operator,
                    workflowSnapshot.documentId,
                    checkNotNull(permit),
                )
            }
        }
        while (true) {
            val completed = hideWorkflowDocumentVisibilityFailure(workflowId) {
                transaction.execute<Document?> {
                    // Keep the document before workflow lock order everywhere so a
                    // direct publish, submission, and review decision serialize on
                    // one document aggregate without lock-order deadlocks.
                    val document = documentRepository.findForMutation(tenant.tenantId, workflowSnapshot.documentId)
                        ?: throw DocumentNotFoundException(workflowSnapshot.documentId)
                    if (
                        document.tenantId != tenant.tenantId ||
                        document.id != workflowSnapshot.documentId
                    ) {
                        throw DocumentNotFoundException(workflowSnapshot.documentId)
                    }
                    if (guard != null) {
                        // The catalog asset lock precedes the workflow decision lock.
                        guard.verifyLifecycleLocked(tenant.tenantId, document, checkNotNull(permit))
                    }
                    val workflow = workflowRepository.findForDecision(tenant.tenantId, workflowId)
                        ?: throw WorkflowNotFoundException(workflowId)
                    if (
                        workflow.tenantId != tenant.tenantId ||
                        workflow.id != workflowId ||
                        workflow.documentId != document.id
                    ) {
                        throw WorkflowNotFoundException(workflowId)
                    }
                    val completingApproval = approved && workflow.willCompleteAfterApproval(taskId, operator.id)
                    if (completingApproval && preparation == null) {
                        // A parallel reviewer completed another task after the first
                        // snapshot. Leave state untouched, resolve the remote policy
                        // outside the transaction, then retry under the row lock.
                        return@execute null
                    }
                    if (approved) {
                        workflow.approve(taskId, operator.id, comment)
                        if (workflow.state == com.fileweft.domain.workflow.WorkflowState.APPROVED) {
                            document.transition(LifecycleCommand.APPROVE)
                            deliveryPlanner.plan(document, requireNotNull(preparation))
                        }
                    } else {
                        workflow.reject(taskId, operator.id, comment)
                        document.transition(LifecycleCommand.REJECT)
                    }
                    workflowRepository.save(workflow)
                    documentRepository.save(document)
                    auditTrail?.record(
                        tenantId = tenant.tenantId,
                        resourceType = DOCUMENT_RESOURCE_TYPE,
                        resourceId = document.id,
                        action = if (approved) APPROVED_AUDIT_ACTION else REJECTED_AUDIT_ACTION,
                        operatorId = operator.id,
                        operatorName = operator.displayName,
                        details = linkedMapOf<String, String>().apply {
                            put("workflowId", workflow.id.value)
                            put("taskId", taskId.value)
                            put("workflowState", workflow.state.name)
                            comment?.let { put("comment", it) }
                        },
                    )
                    document
                }
            }
            if (completed != null) return completed
            preparation = deliveryPlanner.prepare(tenant.tenantId, deliveryProfileId)
            if (guard != null) {
                hideWorkflowDocumentVisibilityFailure(workflowId) {
                    guard.revalidateLifecycle(
                        tenant.tenantId,
                        operator,
                        workflowSnapshot.documentId,
                        checkNotNull(permit),
                    )
                }
            }
        }
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
