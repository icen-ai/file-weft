package com.fileweft.spi.catalog

import com.fileweft.core.id.Identifier

/**
 * Resolves the business-system folder tree that contains FileWeft documents.
 *
 * FileWeft deliberately does not own folders or use folder names in storage
 * keys. The caller retains its namespace and persists the chosen opaque folder
 * reference with the FileWeft asset using [DocumentCatalogBinding.METADATA_KEY].
 */
interface DocumentCatalogProvider {
    fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder>

    fun findFolder(tenantId: Identifier, folderId: String): DocumentCatalogFolder? =
        listFolders(tenantId).firstOrNull { it.id == folderId }
}

/** A tenant-scoped folder supplied by the host system. Folder IDs are opaque strings. */
class DocumentCatalogFolder(
    val id: String,
    val parentFolderId: String?,
    val displayName: String,
) {
    init {
        require(id.isNotBlank()) { "Document catalog folder id must not be blank." }
        require(displayName.isNotBlank()) { "Document catalog folder display name must not be blank." }
        require(parentFolderId != id) { "Document catalog folder cannot be its own parent." }
    }
}

/** The portable reference persisted with an asset; the folder definition stays in the host system. */
class DocumentCatalogBinding private constructor() {
    companion object {
        const val METADATA_KEY = "catalog.folder-id"
    }
}
