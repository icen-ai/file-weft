package ai.icen.fw.spi.catalog

import ai.icen.fw.core.id.Identifier

/**
 * Resolves the business-system folder tree that contains FlowWeft documents.
 *
 * FlowWeft deliberately does not own folders or use folder names in storage
 * keys. The caller retains its namespace and persists the chosen opaque folder
 * reference with the FlowWeft asset using [DocumentCatalogBinding.METADATA_KEY].
 *
 * Each [listFolders] invocation must return one complete, non-paged forest
 * visible within that invocation's tenant/user/operation scope; the forest may
 * be empty and may contain at most 10,000 nodes. Folder IDs and non-null parent
 * IDs must be non-blank, already trimmed, and at most 256 UTF-16 code units;
 * display names must be non-blank, already trimmed, and at most 512 UTF-16 code
 * units. None may contain ISO control or Unicode FORMAT characters. FlowWeft
 * validates this output and never trims, case-folds, or Unicode-normalizes it.
 *
 * Folder IDs must be unique, every non-null parent ID must identify a node in
 * the same result, and the parent graph must be acyclic. If ACL filtering hides
 * a parent, the provider must re-root the visible child or attach it to another
 * visible parent; it must not expose the hidden parent's opaque ID. A custom
 * [findFolder] implementation may accept a host alias, but the returned folder
 * must be visible for the same request and use a stable canonical ID satisfying
 * the same text bounds.
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

/** Trusted context assembled from FlowWeft's tenant and identity providers. */
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
