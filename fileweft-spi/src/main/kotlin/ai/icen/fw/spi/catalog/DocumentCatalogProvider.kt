package ai.icen.fw.spi.catalog

import ai.icen.fw.core.id.Identifier

/**
 * Resolves the business-system folder tree that contains FileWeft documents.
 *
 * FileWeft deliberately does not own folders or use folder names in storage
 * keys. The caller retains its namespace and persists the chosen opaque folder
 * reference with the FileWeft asset using [DocumentCatalogBinding.METADATA_KEY].
 */
interface DocumentCatalogProvider {
    fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder>

    /**
     * User-aware variant for hosts whose folder visibility is narrower than a
     * tenant. Existing providers remain compatible through the tenant-only
     * fallback; new providers should use this request to enforce their own
     * folder ACLs without trusting an HTTP request parameter.
     */
    fun listFolders(request: DocumentCatalogAccessRequest): List<DocumentCatalogFolder> = listFolders(request.tenantId)

    fun findFolder(tenantId: Identifier, folderId: String): DocumentCatalogFolder? =
        listFolders(tenantId).firstOrNull { it.id == folderId }

    fun findFolder(request: DocumentCatalogAccessRequest, folderId: String): DocumentCatalogFolder? =
        listFolders(request).firstOrNull { it.id == folderId }
}

/** Trusted context assembled from FileWeft's tenant and identity providers. */
class DocumentCatalogAccessRequest(
    val tenantId: Identifier,
    val userId: Identifier,
    val operation: DocumentCatalogOperation,
)

enum class DocumentCatalogOperation {
    BROWSE,
    BIND_DOCUMENT,
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
