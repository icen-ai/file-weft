package ai.icen.fw.web.api.v1.catalog

import ai.icen.fw.web.api.optionalText
import ai.icen.fw.web.api.requiredText

/** One host-owned folder visible to the current trusted tenant and user. */
class DocumentCatalogFolderDto @JvmOverloads constructor(
    id: String,
    displayName: String,
    parentFolderId: String? = null,
) {
    val id: String = requiredText(id, "Document catalog folder id", 256)
    val displayName: String = requiredText(displayName, "Document catalog folder display name", 512)
    val parentFolderId: String? = optionalText(parentFolderId, "Document catalog parent folder id", 256)
}

/** Cursor page over a freshly authorized host-catalog snapshot. */
class DocumentCatalogPageQuery @JvmOverloads constructor(
    cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    val cursor: String? = optionalText(cursor, "Document catalog page cursor", 512)

    init {
        require(limit in 1..MAX_LIMIT) {
            "Document catalog page limit must be between 1 and $MAX_LIMIT."
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}

/** Jackson-friendly command; tenant, user and authorization never come from the body. */
class MoveDocumentToFolderRequest {
    var folderId: String? = null
}

/** Validated immutable command created immediately after JSON binding. */
class MoveDocumentToFolderCommand(folderId: String) {
    val folderId: String = requiredText(folderId, "Document catalog folder id", 256)
}
