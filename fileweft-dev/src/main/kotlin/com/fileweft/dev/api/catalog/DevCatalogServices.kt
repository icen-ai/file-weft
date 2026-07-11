package com.fileweft.dev.api.catalog

import com.fileweft.application.document.CreateDocumentDraftCommand
import com.fileweft.application.catalog.DocumentCatalogAccessService
import com.fileweft.application.catalog.DocumentCatalogDraftService
import com.fileweft.domain.document.Document
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
