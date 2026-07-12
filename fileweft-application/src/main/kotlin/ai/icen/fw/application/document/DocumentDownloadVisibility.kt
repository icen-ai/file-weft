package ai.icen.fw.application.document

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document

/**
 * Optional two-phase visibility guard for authorized downloads.
 *
 * [prepare] derives an immutable folder scope from trusted host context before
 * the database transaction. [verify] then applies that scope through the
 * tenant-filtered query port inside the download transaction. No host catalog
 * call is permitted from [verify].
 */
class DocumentDownloadVisibility(
    private val folderReadAccess: DocumentFolderReadAccess,
    private val queries: DocumentQueryRepository,
) {
    @JvmSynthetic
    internal fun prepare(
        tenantId: Identifier,
        documentId: Identifier,
    ): DocumentDownloadVisibilityPermit {
        val folderIds = if (folderReadAccess is DocumentFolderDownloadAccess) {
            folderReadAccess.readableFolderIdsForDocumentDownload(documentId)
        } else {
            folderReadAccess.readableFolderIds()
        }
        val scope = DocumentFolderReadScope(folderIds)
        if (scope.isEmpty) {
            throw DocumentNotFoundException(documentId)
        }
        return DocumentDownloadVisibilityPermit(tenantId, documentId, scope)
    }

    @JvmSynthetic
    internal fun verify(
        tenantId: Identifier,
        document: Document,
        permit: DocumentDownloadVisibilityPermit,
    ) {
        if (
            permit.tenantId != tenantId ||
            permit.documentId != document.id ||
            document.tenantId != tenantId
        ) {
            throw DocumentNotFoundException(permit.documentId)
        }
        val visible = queries.findDetail(tenantId, document.id, permit.folderReadScope)
        if (visible == null || visible.document.id != document.id) {
            throw DocumentNotFoundException(document.id)
        }
    }
}

/** Invocation-local trusted evidence; callers cannot construct or alter it. */
internal class DocumentDownloadVisibilityPermit(
    val tenantId: Identifier,
    val documentId: Identifier,
    val folderReadScope: DocumentFolderReadScope,
)
