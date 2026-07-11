package com.fileweft.application.offline

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.catalog.DocumentLifecycleMutationGuard
import com.fileweft.application.catalog.DocumentLifecycleMutationPermit
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.delivery.DocumentDeliveryRemovalPlanner
import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider

class OfflineDocumentService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentRepository,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
    private val removalPlanner: DocumentDeliveryRemovalPlanner? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun offline(documentId: Identifier): Document = execute(documentId, null)

    internal fun offline(documentId: Identifier, guard: DocumentLifecycleMutationGuard): Document =
        execute(documentId, guard)

    private fun execute(documentId: Identifier, guard: DocumentLifecycleMutationGuard?): Document {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        authorization.requireDocumentAction(tenant.tenantId, documentId, OFFLINE_ACTION)
        val permit: DocumentLifecycleMutationPermit? = if (guard == null) {
            null
        } else {
            guard.prepareLifecycle(tenant.tenantId, documentId, OFFLINE_ACTION).also { prepared ->
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
            document.transition(LifecycleCommand.OFFLINE)
            val removalPlan = removalPlanner?.plan(document)
            documentRepository.save(document)
            auditTrail?.record(
                tenantId = tenant.tenantId,
                resourceType = DOCUMENT_RESOURCE_TYPE,
                resourceId = document.id,
                action = OFFLINE_AUDIT_ACTION,
                operatorId = operator?.id,
                operatorName = operator?.displayName,
                details = removalPlan?.let { plan -> mapOf("downstreamRemovalCount" to plan.deliveries.size.toString()) } ?: emptyMap(),
            )
            document
        }
    }

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val OFFLINE_ACTION = "document:offline"
        const val OFFLINE_AUDIT_ACTION = "document:offline"
    }
}
