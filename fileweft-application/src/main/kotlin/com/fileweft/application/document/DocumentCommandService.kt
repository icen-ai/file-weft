package com.fileweft.application.document

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.catalog.DocumentLifecycleMutationGuard
import com.fileweft.application.catalog.DocumentLifecycleMutationPermit
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

    fun submit(documentId: Identifier): Document = execute(documentId, SUBMIT_ACTION, LifecycleCommand.SUBMIT, null)

    internal fun submit(documentId: Identifier, guard: DocumentLifecycleMutationGuard): Document =
        execute(documentId, SUBMIT_ACTION, LifecycleCommand.SUBMIT, guard)

    fun reject(documentId: Identifier): Document = execute(documentId, REJECT_ACTION, LifecycleCommand.REJECT, null)

    internal fun reject(documentId: Identifier, guard: DocumentLifecycleMutationGuard): Document =
        execute(documentId, REJECT_ACTION, LifecycleCommand.REJECT, guard)

    fun revise(documentId: Identifier): Document = execute(documentId, REVISE_ACTION, LifecycleCommand.REVISE, null)

    internal fun revise(documentId: Identifier, guard: DocumentLifecycleMutationGuard): Document =
        execute(documentId, REVISE_ACTION, LifecycleCommand.REVISE, guard)

    private fun execute(
        documentId: Identifier,
        action: String,
        command: LifecycleCommand,
        guard: DocumentLifecycleMutationGuard?,
    ): Document {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        // Base authorization must precede every repository and catalog access.
        authorization.requireDocumentAction(tenant.tenantId, documentId, action)
        val permit: DocumentLifecycleMutationPermit? = if (guard == null) {
            null
        } else {
            guard.prepareLifecycle(tenant.tenantId, documentId, action).also { prepared ->
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
        const val SUBMIT_ACTION = "document:submit"
        const val REJECT_ACTION = "document:reject"
        const val REVISE_ACTION = "document:revise"
    }
}

class DocumentNotFoundException(documentId: Identifier) :
    NoSuchElementException("Document ${documentId.value} was not found in the current tenant.")
