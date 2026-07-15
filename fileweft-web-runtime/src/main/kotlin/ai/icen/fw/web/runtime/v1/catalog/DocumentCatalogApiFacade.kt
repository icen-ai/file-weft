package ai.icen.fw.web.runtime.v1.catalog

import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogBindingCommand
import ai.icen.fw.web.api.ApiPage
import ai.icen.fw.web.api.v1.catalog.DocumentCatalogFolderDto
import ai.icen.fw.web.api.v1.catalog.DocumentCatalogPageQuery
import ai.icen.fw.web.api.v1.catalog.MoveDocumentToFolderCommand
import ai.icen.fw.web.api.v1.document.DocumentCommandResultDto
import ai.icen.fw.web.runtime.v1.V1FeatureUnavailableException
import ai.icen.fw.web.runtime.v1.document.DocumentApiInputs

/** Formal v1 read-only host catalog plus the single controlled document-move command. */
class DocumentCatalogApiFacade @JvmOverloads constructor(
    private val catalog: DocumentCatalogAccessService,
    private val bindings: DocumentCatalogBindingCommand? = null,
) {
    private val cursorCodec = DocumentCatalogCursorCodec()

    fun page(query: DocumentCatalogPageQuery): ApiPage<DocumentCatalogFolderDto> {
        val visible = catalog.listAccessibleFolders().sortedBy { folder -> folder.id }
        val cursor = query.cursor?.let(cursorCodec::decode)
        val startIndex = cursor?.let { decoded ->
            require(decoded.position < visible.size && cursorCodec.matches(decoded, visible[decoded.position].id)) {
                "Document catalog page cursor no longer matches the visible catalog."
            }
            decoded.position + 1
        } ?: 0
        val candidates = visible.drop(startIndex)
        val selected = candidates.take(query.limit)
        val nextCursor = selected.lastOrNull()
            ?.takeIf { candidates.size > selected.size }
            ?.let { folder -> cursorCodec.encode(startIndex + selected.lastIndex, folder.id) }
        return ApiPage(
            items = selected.map { folder ->
                DocumentCatalogFolderDto(folder.id, folder.displayName, folder.parentFolderId)
            },
            nextCursor = nextCursor,
        )
    }

    fun move(documentId: String, rawFolderId: String?): DocumentCommandResultDto {
        val service = bindings ?: throw V1FeatureUnavailableException()
        val id = DocumentApiInputs.documentId(documentId)
        val command = MoveDocumentToFolderCommand(
            rawFolderId ?: throw IllegalArgumentException("Document catalog folder id is required."),
        )
        val moved = service.move(id, command.folderId)
        return DocumentCommandResultDto(moved.id.value, moved.currentVersionId?.value)
    }
}
