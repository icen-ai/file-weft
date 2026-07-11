package com.fileweft.application.publish

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.catalog.DocumentLifecycleMutationGuard
import com.fileweft.application.catalog.DocumentLifecycleMutationPermit
import com.fileweft.application.delivery.DocumentDeliveryPlanner
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider

class PublishDocumentService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentRepository,
    private val deliveryPlanner: DocumentDeliveryPlanner,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
    private val workflows: WorkflowInstanceRepository,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun publish(documentId: Identifier): Document = execute(documentId, null, null)

    fun publish(documentId: Identifier, deliveryProfileId: String?): Document =
        execute(documentId, deliveryProfileId, null)

    internal fun publish(
        documentId: Identifier,
        deliveryProfileId: String?,
        guard: DocumentLifecycleMutationGuard,
    ): Document = execute(documentId, deliveryProfileId, guard)

    private fun execute(
        documentId: Identifier,
        deliveryProfileId: String?,
        guard: DocumentLifecycleMutationGuard?,
    ): Document {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        authorization.requireDocumentAction(tenant.tenantId, documentId, PUBLISH_ACTION)
        val permit: DocumentLifecycleMutationPermit? = if (guard == null) {
            null
        } else {
            guard.prepareLifecycle(tenant.tenantId, documentId, PUBLISH_ACTION)
        }
        transaction.execute {
            val document = documentRepository.findById(tenant.tenantId, documentId)
                ?: throw DocumentNotFoundException(documentId)
            if (document.tenantId != tenant.tenantId || document.id != documentId) {
                throw DocumentNotFoundException(documentId)
            }
            requireNoActiveWorkflow(tenant.tenantId, documentId)
        }
        val preparation = deliveryPlanner.prepare(tenant.tenantId, deliveryProfileId)
        if (guard != null) {
            guard.revalidateLifecycle(tenant.tenantId, documentId, checkNotNull(permit))
        }
        return transaction.execute {
            val document = documentRepository.findForMutation(tenant.tenantId, documentId)
                ?: throw DocumentNotFoundException(documentId)
            if (document.tenantId != tenant.tenantId || document.id != documentId) {
                throw DocumentNotFoundException(documentId)
            }
            if (guard != null) {
                // Document lock first, then the guard's asset mutation lock.
                guard.verifyLifecycleLocked(tenant.tenantId, document, checkNotNull(permit))
            }
            requireNoActiveWorkflow(tenant.tenantId, documentId)
            document.transition(LifecycleCommand.APPROVE)
            documentRepository.save(document)
            deliveryPlanner.plan(document, preparation)
            auditTrail?.record(
                tenantId = tenant.tenantId,
                resourceType = DOCUMENT_RESOURCE_TYPE,
                resourceId = document.id,
                action = PUBLISH_AUDIT_ACTION,
                operatorId = operator?.id,
                operatorName = operator?.displayName,
            )
            document
        }
    }

    private fun requireNoActiveWorkflow(tenantId: Identifier, documentId: Identifier) {
        if (workflows.findActiveByDocument(tenantId, documentId) != null) {
            throw ActiveDocumentReviewWorkflowException(documentId)
        }
    }

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val PUBLISH_ACTION = "document:publish"
        const val PUBLISH_AUDIT_ACTION = "document:publish:request"
    }
}

class ActiveDocumentReviewWorkflowException(documentId: Identifier) :
    IllegalStateException("Document ${documentId.value} has an active review workflow and cannot be published directly.")
