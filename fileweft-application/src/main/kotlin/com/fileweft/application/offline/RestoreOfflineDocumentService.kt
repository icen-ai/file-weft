package com.fileweft.application.offline

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.catalog.DocumentLifecycleMutationGuard
import com.fileweft.application.catalog.DocumentLifecycleMutationPermit
import com.fileweft.application.delivery.DocumentDeliveryRemovalStatus
import com.fileweft.application.delivery.DocumentDeliveryStatus
import com.fileweft.application.delivery.DocumentDeliveryTargetRepository
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider

/**
 * Starts a controlled replacement only after the current publication generation
 * has settled: successfully delivered targets must be withdrawn and no target
 * may still be waiting for a downstream synchronization result.
 */
class RestoreOfflineDocumentService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentRepository,
    private val deliveries: DocumentDeliveryTargetRepository,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun restore(documentId: Identifier): Document = execute(documentId, null)

    internal fun restore(documentId: Identifier, guard: DocumentLifecycleMutationGuard): Document =
        execute(documentId, guard)

    private fun execute(documentId: Identifier, guard: DocumentLifecycleMutationGuard?): Document {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        authorization.requireDocumentAction(tenant.tenantId, documentId, RESTORE_ACTION)
        val permit: DocumentLifecycleMutationPermit? = if (guard == null) {
            null
        } else {
            guard.prepareLifecycle(tenant.tenantId, documentId, RESTORE_ACTION).also { prepared ->
                guard.revalidateLifecycle(tenant.tenantId, documentId, prepared)
            }
        }
        return transaction.execute {
            val document = documentRepository.findForMutation(tenant.tenantId, documentId)
                ?: throw DocumentNotFoundException(documentId)
            if (document.tenantId != tenant.tenantId || document.id != documentId) {
                throw DocumentNotFoundException(documentId)
            }
            if (guard != null) {
                guard.verifyLifecycleLocked(tenant.tenantId, document, checkNotNull(permit))
            }
            val currentDeliveries = deliveries.findByDocumentGeneration(
                tenant.tenantId,
                document.id,
                document.deliveryGeneration,
            )
            require(currentDeliveries.none { it.status in ACTIVE_DELIVERY_STATES }) {
                "Document still has downstream synchronization in progress; wait for the current publication generation to settle."
            }
            require(currentDeliveries.none {
                it.status == DocumentDeliveryStatus.SUCCEEDED && it.removalStatus != DocumentDeliveryRemovalStatus.SUCCEEDED
            }) {
                "Document still has delivered downstream targets awaiting withdrawal."
            }
            document.transition(LifecycleCommand.RESTORE_DRAFT)
            documentRepository.save(document)
            auditTrail?.record(
                tenantId = tenant.tenantId,
                resourceType = DOCUMENT_RESOURCE_TYPE,
                resourceId = document.id,
                action = RESTORE_ACTION,
                operatorId = operator?.id,
                operatorName = operator?.displayName,
                details = mapOf("completedDeliveryGeneration" to document.deliveryGeneration.toString()),
            )
            document
        }
    }

    private companion object {
        const val RESTORE_ACTION = "document:restore"
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        val ACTIVE_DELIVERY_STATES = setOf(DocumentDeliveryStatus.PENDING, DocumentDeliveryStatus.RETRYING)
    }
}
