package ai.icen.fw.dev.api.catalog

import ai.icen.fw.application.document.CreateDocumentDraftCommand
import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogDraftService
import ai.icen.fw.domain.document.Document
import java.io.InputStream

data class DevCatalogFolderView(
    val id: String,
    val parentFolderId: String?,
    val displayName: String,
)

/** Applies read authorization before exposing the host system's folder topology. */
class DevCatalogQueryService(
    private val catalogAccess: DocumentCatalogAccessService,
) {
    fun folders(): List<DevCatalogFolderView> =
        catalogAccess.listAccessibleFolders().map { folder ->
            DevCatalogFolderView(folder.id, folder.parentFolderId, folder.displayName)
        }
}

/**
 * Supplies the Dev UI's default inbox while delegating all folder ACL,
 * metadata validation, and atomic binding work to the application service.
 */
class DevCatalogDocumentService(
    private val catalogDrafts: DocumentCatalogDraftService,
) {
    fun create(command: CreateDocumentDraftCommand, folderId: String?, content: InputStream): Document {
        val resolvedFolderId = folderId?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_FOLDER_ID
        return catalogDrafts.createInFolder(command, resolvedFolderId, content)
    }

    private companion object {
        const val DEFAULT_FOLDER_ID = "inbox"
    }
}
