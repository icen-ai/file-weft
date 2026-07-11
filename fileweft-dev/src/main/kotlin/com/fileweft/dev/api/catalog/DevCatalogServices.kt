package com.fileweft.dev.api.catalog

import com.fileweft.application.document.CreateDocumentDraftCommand
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.catalog.DocumentCatalogAccessService
import com.fileweft.domain.document.Document
import com.fileweft.spi.catalog.DocumentCatalogBinding
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
 * Binds an uploaded FileWeft document to an externally owned folder reference.
 * Only the opaque folder ID is stored on the asset; folder contents remain in
 * the host-owned [DocumentCatalogAccessService].
 */
class DevCatalogDocumentService(
    private val drafts: DocumentDraftService,
    private val catalogAccess: DocumentCatalogAccessService,
) {
    fun create(command: CreateDocumentDraftCommand, folderId: String?, content: InputStream): Document {
        val resolvedFolderId = folderId?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_FOLDER_ID
        catalogAccess.requireFolderForDocumentCreation(resolvedFolderId)
        return drafts.create(
            command.copy(metadata = command.metadata + (DocumentCatalogBinding.METADATA_KEY to resolvedFolderId)),
            content,
        )
    }

    private companion object {
        const val DEFAULT_FOLDER_ID = "inbox"
    }
}
