package com.fileweft.application.publish

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.delivery.DocumentDeliveryPlanner
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

class PublishDocumentService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentRepository,
    private val deliveryPlanner: DocumentDeliveryPlanner,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun publish(documentId: Identifier): Document = publish(documentId, null)

    fun publish(documentId: Identifier, deliveryProfileId: String?): Document {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        authorization.requireDocumentAction(tenant.tenantId, documentId, "document:publish")
        return transaction.execute {
            val document = documentRepository.findById(tenant.tenantId, documentId)
                ?: throw DocumentNotFoundException(documentId)
            document.transition(LifecycleCommand.APPROVE)
            documentRepository.save(document)
            deliveryPlanner.plan(document, deliveryProfileId)
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

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val PUBLISH_AUDIT_ACTION = "document:publish:request"
    }
}
