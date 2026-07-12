package ai.icen.fw.application.delivery

import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

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
