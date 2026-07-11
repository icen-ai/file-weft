package com.fileweft.application.document

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider

class DocumentCommandService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentRepository,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun submit(documentId: Identifier): Document = execute(documentId, "document:submit", LifecycleCommand.SUBMIT)

    fun reject(documentId: Identifier): Document = execute(documentId, "document:reject", LifecycleCommand.REJECT)

    fun revise(documentId: Identifier): Document = execute(documentId, "document:revise", LifecycleCommand.REVISE)

    private fun execute(documentId: Identifier, action: String, command: LifecycleCommand): Document {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        authorization.requireDocumentAction(tenant.tenantId, documentId, action)
        return transaction.execute {
            val document = documentRepository.findForMutation(tenant.tenantId, documentId)
                ?: throw DocumentNotFoundException(documentId)
            document.transition(command)
            documentRepository.save(document)
            auditTrail?.record(
                tenantId = tenant.tenantId,
                resourceType = DOCUMENT_RESOURCE_TYPE,
                resourceId = document.id,
                action = action,
                operatorId = operator?.id,
                operatorName = operator?.displayName,
            )
            document
        }
    }

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
    }
}

class DocumentNotFoundException(documentId: Identifier) :
    NoSuchElementException("Document ${documentId.value} was not found in the current tenant.")
