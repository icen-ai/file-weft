package ai.icen.fw.application.audit

import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

/** Security boundary for one document's redacted, cursor-paged audit history. */
class DocumentAuditLogQueryService @JvmOverloads constructor(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val queries: DocumentAuditLogQueryRepository,
    private val transaction: ApplicationTransaction,
    private val folderReadAccess: DocumentFolderReadAccess? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun page(documentId: Identifier, request: DocumentAuditLogPageRequest): DocumentAuditLogPageResult {
        val tenant = tenantProvider.currentTenant()
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, AUDIT_ACTION)
        authorization.requireDocumentActionAs(tenant.tenantId, documentId, READ_ACTION, operator)

        val folderScope = readableFolderScope()
        if (folderScope?.isEmpty == true) throw DocumentNotFoundException(documentId)

        val result = transaction.execute {
            queries.findPage(tenant.tenantId, documentId, request, folderScope)
                ?: throw DocumentNotFoundException(documentId)
        }
        check(result.documentId == documentId) {
            "Document audit-log query returned a page outside the requested document."
        }
        return result
    }

    private fun readableFolderScope(): DocumentFolderReadScope? =
        folderReadAccess?.readableFolderIds()?.let(::DocumentFolderReadScope)

    companion object {
        const val AUDIT_ACTION: String = "document:audit"
        const val READ_ACTION: String = "document:read"
    }
}
