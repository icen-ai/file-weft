package ai.icen.fw.application.document

import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

/**
 * Security boundary for public document reads.
 *
 * API adapters provide only a document identifier or a bounded page request.
 * This service derives tenant and user context itself, authorizes before any
 * read transaction, and returns immutable redacted projections rather than a
 * mutable domain aggregate.
 */
class DocumentQueryService @JvmOverloads constructor(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val queries: DocumentQueryRepository,
    private val transaction: ApplicationTransaction,
    private val folderReadAccess: DocumentFolderReadAccess? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun detail(documentId: Identifier): DocumentDetailView {
        val tenant = tenantProvider.currentTenant()
        authorization.requireDocumentAction(tenant.tenantId, documentId, DOCUMENT_READ_ACTION)
        val folderReadScope = readableFolderScope()
        if (folderReadScope?.isEmpty == true) {
            throw DocumentNotFoundException(documentId)
        }
        return transaction.execute {
            queries.findDetail(tenant.tenantId, documentId, folderReadScope) ?: throw DocumentNotFoundException(documentId)
        }
    }

    fun page(request: DocumentPageRequest): DocumentPageResult {
        val tenant = tenantProvider.currentTenant()
        authorization.requireAction(
            tenant.tenantId,
            DOCUMENT_PAGE_RESOURCE_ID,
            DOCUMENT_PAGE_RESOURCE_TYPE,
            DOCUMENT_READ_ACTION,
        )
        val normalizedRequest = request.withNormalizedFolderId()
        normalizedRequest.folderId?.let { folderId ->
            val access = folderReadAccess ?: throw DocumentFolderReadAccessUnavailableException()
            access.requireFolderForDocumentRead(folderId)
        }
        val folderReadScope = readableFolderScope()
        if (folderReadScope?.isEmpty == true) {
            return DocumentPageResult(emptyList())
        }
        return transaction.execute { queries.findPage(tenant.tenantId, normalizedRequest, folderReadScope) }
    }

    private fun readableFolderScope(): DocumentFolderReadScope? =
        folderReadAccess?.readableFolderIds()?.let(::DocumentFolderReadScope)

    private fun DocumentPageRequest.withNormalizedFolderId(): DocumentPageRequest {
        val normalizedFolderId = folderId?.trim()
        return if (normalizedFolderId == folderId) {
            this
        } else {
            DocumentPageRequest(cursor, limit, lifecycleState, normalizedFolderId)
        }
    }

    companion object {
        const val DOCUMENT_READ_ACTION = "document:read"
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val DOCUMENT_PAGE_RESOURCE_TYPE = "DOCUMENT_PAGE"
        val DOCUMENT_PAGE_RESOURCE_ID = Identifier("document-page")
    }
}
