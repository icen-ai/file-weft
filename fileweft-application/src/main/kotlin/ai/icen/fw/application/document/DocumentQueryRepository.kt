package ai.icen.fw.application.document

import ai.icen.fw.core.id.Identifier

/**
 * Read-model port for public-safe document projections.
 *
 * The tenant argument is supplied only by [DocumentQueryService] from the
 * trusted tenant context. Implementations must always filter by it and must
 * never return persistence-only fields in these views.
 */
interface DocumentQueryRepository {
    fun findDetail(tenantId: Identifier, documentId: Identifier): DocumentDetailView? =
        findDetail(tenantId, documentId, folderReadScope = null)

    fun findDetail(
        tenantId: Identifier,
        documentId: Identifier,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentDetailView?

    fun findPage(tenantId: Identifier, request: DocumentPageRequest): DocumentPageResult =
        findPage(tenantId, request, folderReadScope = null)

    fun findPage(
        tenantId: Identifier,
        request: DocumentPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentPageResult
}
