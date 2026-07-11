package com.fileweft.application.document

import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.LifecycleState
import java.util.ArrayList
import java.util.Collections

/**
 * A stable sort position for a document page. It deliberately contains only
 * the two database ordering keys; HTTP adapters own opaque cursor encoding.
 */
class DocumentPageCursor(
    val updatedTime: Long,
    val id: Identifier,
) {
    init {
        require(updatedTime >= 0) { "Document page cursor update time must not be negative." }
    }
}

/**
 * A tenant-context-derived document page request. It never accepts a tenant
 * or user identifier from an API caller.
 */
class DocumentPageRequest @JvmOverloads constructor(
    val cursor: DocumentPageCursor? = null,
    val limit: Int = DEFAULT_LIMIT,
    val lifecycleState: LifecycleState? = null,
    folderId: String? = null,
) {
    val folderId: String? = folderId?.let { publicOptionalText(it, "Document folder id", MAX_FOLDER_ID_LENGTH) }

    init {
        require(limit in 1..MAX_LIMIT) { "Document page limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 100
    }
}

/** Safe document metadata suitable for a public API mapping. */
class DocumentSummaryView @JvmOverloads constructor(
    val id: Identifier,
    documentNumber: String,
    title: String,
    val lifecycleState: LifecycleState,
    val createdTime: Long,
    val updatedTime: Long,
    val currentVersionId: Identifier? = null,
    folderId: String = DEFAULT_FOLDER_ID,
) {
    val documentNumber: String = publicRequiredText(documentNumber, "Document number", MAX_DOCUMENT_NUMBER_LENGTH)
    val title: String = publicRequiredText(title, "Document title", MAX_DOCUMENT_TITLE_LENGTH)
    val folderId: String = publicRequiredText(folderId, "Document folder id", MAX_FOLDER_ID_LENGTH)

    init {
        require(createdTime >= 0) { "Document creation time must not be negative." }
        require(updatedTime >= createdTime) { "Document update time must not precede creation time." }
    }

    companion object {
        const val DEFAULT_FOLDER_ID = "inbox"
    }
}

/** Safe metadata for one version and its file; storage location and object IDs stay internal. */
class DocumentVersionView @JvmOverloads constructor(
    val id: Identifier,
    versionNumber: String,
    fileName: String,
    val contentLength: Long,
    val createdTime: Long,
    val updatedTime: Long,
    contentType: String? = null,
) {
    val versionNumber: String = publicRequiredText(versionNumber, "Document version number", MAX_VERSION_NUMBER_LENGTH)
    val fileName: String = publicRequiredText(fileName, "Document version file name", MAX_FILE_NAME_LENGTH)
    val contentType: String? = contentType?.let { publicOptionalText(it, "Document version content type", MAX_CONTENT_TYPE_LENGTH) }

    init {
        require(contentLength >= 0) { "Document version content length must not be negative." }
        require(createdTime >= 0) { "Document version creation time must not be negative." }
        require(updatedTime >= createdTime) { "Document version update time must not precede creation time." }
    }
}

/** One document detail, reduced to public-safe metadata only. */
class DocumentDetailView @JvmOverloads constructor(
    val document: DocumentSummaryView,
    versions: List<DocumentVersionView> = emptyList(),
) {
    val versions: List<DocumentVersionView> = immutableList(versions)

    init {
        require(this.versions.map { it.id }.distinct().size == this.versions.size) {
            "Document detail versions must have unique identifiers."
        }
        val currentVersionId = document.currentVersionId
        require(currentVersionId == null || this.versions.any { it.id == currentVersionId }) {
            "Document detail current version must be present in its versions."
        }
    }
}

/** A bounded page whose next position is decoded and encoded by the API adapter. */
class DocumentPageResult @JvmOverloads constructor(
    items: List<DocumentSummaryView>,
    val nextCursor: DocumentPageCursor? = null,
) {
    val items: List<DocumentSummaryView> = immutableList(items)

    init {
        require(this.items.size <= DocumentPageRequest.MAX_LIMIT) {
            "Document page must not contain more than ${DocumentPageRequest.MAX_LIMIT} items."
        }
    }
}

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private const val MAX_DOCUMENT_NUMBER_LENGTH = 128
private const val MAX_DOCUMENT_TITLE_LENGTH = 512
private const val MAX_FOLDER_ID_LENGTH = 256
private const val MAX_VERSION_NUMBER_LENGTH = 32
private const val MAX_FILE_NAME_LENGTH = 512
private const val MAX_CONTENT_TYPE_LENGTH = 128

private fun publicRequiredText(value: String, field: String, maxLength: Int): String {
    require(value.isNotBlank()) { "$field must not be blank." }
    return publicText(value, field, maxLength)
}

private fun publicOptionalText(value: String, field: String, maxLength: Int): String {
    require(value.isNotBlank()) { "$field must not be blank when provided." }
    return publicText(value, field, maxLength)
}

private fun publicText(value: String, field: String, maxLength: Int): String {
    require(value.length <= maxLength) { "$field must not exceed $maxLength characters." }
    require(value.none { character -> Character.isISOControl(character) }) { "$field must not contain control characters." }
    return value
}
