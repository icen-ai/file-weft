package com.fileweft.application.delivery

import com.fileweft.application.document.DocumentFolderReadAccess
import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider

/** Security boundary for a document's redacted current synchronization state. */
class DocumentSyncStatusQueryService @JvmOverloads constructor(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val queries: DocumentSyncStatusQueryRepository,
    private val transaction: ApplicationTransaction,
    private val folderReadAccess: DocumentFolderReadAccess? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun status(documentId: Identifier): DocumentSyncStatusView {
        val tenant = tenantProvider.currentTenant()
        authorization.requireDocumentAction(tenant.tenantId, documentId, READ_ACTION)
        val folderScope = readableFolderScope()
        if (folderScope?.isEmpty == true) throw DocumentNotFoundException(documentId)
        val result = transaction.execute {
            queries.findByDocument(tenant.tenantId, documentId, folderScope)
                ?: throw DocumentNotFoundException(documentId)
        }
        check(result.documentId == documentId) {
            "Synchronization query returned a document outside the requested identifier."
        }
        return result
    }

    private fun readableFolderScope(): DocumentFolderReadScope? =
        folderReadAccess?.readableFolderIds()?.let(::DocumentFolderReadScope)

    companion object {
        const val READ_ACTION: String = "document:read"
    }
}
