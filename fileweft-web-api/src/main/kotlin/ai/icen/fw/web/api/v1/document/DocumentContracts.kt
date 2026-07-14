package ai.icen.fw.web.api.v1.document

import ai.icen.fw.web.api.immutableList
import ai.icen.fw.web.api.optionalText
import ai.icen.fw.web.api.requiredFileName
import ai.icen.fw.web.api.requiredText
import java.util.Collections
import java.util.LinkedHashMap

/** Immutable public representation of a document; it never exposes a domain aggregate. */
class DocumentDto @JvmOverloads constructor(
    id: String,
    documentNumber: String,
    title: String,
    lifecycleState: String,
    val createdTime: Long,
    val updatedTime: Long,
    currentVersionId: String? = null,
    folderId: String? = null,
) {
    val id: String = requiredText(id, "Document id", 128)
    val documentNumber: String = requiredText(documentNumber, "Document number", 128)
    val title: String = requiredText(title, "Document title", 512)
    val lifecycleState: String = requiredText(lifecycleState, "Document lifecycle state", 64)
    val currentVersionId: String? = optionalText(currentVersionId, "Document current version id", 128)
    val folderId: String? = optionalText(folderId, "Document folder id", 512)

    init {
        require(createdTime >= 0) { "Document creation time must not be negative." }
        require(updatedTime >= createdTime) { "Document update time must not precede creation time." }
    }
}

/** Immutable public representation of one logical document version. */
class DocumentVersionDto @JvmOverloads constructor(
    id: String,
    versionNumber: String,
    fileName: String,
    val contentLength: Long,
    val createdTime: Long,
    val updatedTime: Long,
    contentType: String? = null,
) {
    val id: String = requiredText(id, "Document version id", 128)
    val versionNumber: String = requiredText(versionNumber, "Document version number", 32)
    val fileName: String = requiredText(fileName, "Document version file name", 512)
    val contentType: String? = optionalText(contentType, "Document version content type", 128)

    init {
        require(contentLength >= 0) { "Document version content length must not be negative." }
        require(createdTime >= 0) { "Document version creation time must not be negative." }
        require(updatedTime >= createdTime) { "Document version update time must not precede creation time." }
    }
}

/** Stable, safe detail payload for one readable document and its versions. */
class DocumentDetailDto(document: DocumentDto, versions: List<DocumentVersionDto>) {
    val document: DocumentDto = document
    val versions: List<DocumentVersionDto> = immutableList(versions)

    init {
        require(this.versions.map { it.id }.distinct().size == this.versions.size) {
            "Document detail version identifiers must be unique."
        }
        require(
            this.document.currentVersionId == null || this.versions.any { it.id == this.document.currentVersionId },
        ) {
            "Document current version must be present in its version list."
        }
    }
}

/**
 * Minimal result returned by a successful document mutation.
 *
 * It deliberately avoids a follow-up read after commit: a caller may be
 * allowed to mutate a document without holding the separate read permission,
 * and a failed second query must never make a committed command look failed.
 */
class DocumentCommandResultDto @JvmOverloads constructor(
    val documentId: String,
    val versionId: String? = null,
) {
    // These values originate from a committed domain aggregate rather than an
    // untrusted request. Do not add post-commit validation here: a transport
    // mapping failure must never make a successful mutation look rolled back.
}

/**
 * Stable identifiers returned by a committed lifecycle or review command.
 *
 * These values originate from the committed application receipt. Deliberately
 * do not add post-commit identifier validation here: a transport mapping
 * failure must never make a successful mutation appear to have rolled back.
 */
class DocumentLifecycleCommandResultDto @JvmOverloads constructor(
    val documentId: String,
    val workflowId: String? = null,
    val taskId: String? = null,
)

/** Stable identifiers for an idempotently scheduled delivery recovery. */
class DocumentDeliveryRecoveryResultDto(
    val documentId: String,
    val deliveryId: String,
    val operation: String,
)

/** Optional delivery selection for direct document publication. */
class PublishDocumentCommand @JvmOverloads constructor(deliveryProfileId: String? = null) {
    val deliveryProfileId: String? = optionalText(deliveryProfileId, "Document delivery profile id", 256)
}

/**
 * Zero-argument transport bean usable by both Spring Boot 2 and Spring Boot 3.
 * Controllers must immediately convert it to [PublishDocumentCommand].
 */
class PublishDocumentRequest {
    var deliveryProfileId: String? = null
}

/**
 * Schema-qualified metadata accepted by multipart document mutations.
 *
 * HTTP adapters encode each value as a string and the metadata runtime owns
 * all declared-type validation and canonicalization. This contract only puts
 * defensive bounds around the untrusted transport representation.
 */
class DocumentMetadataCommand @JvmOverloads constructor(
    schemaId: String,
    values: Map<String, String> = emptyMap(),
) {
    val schemaId: String = requiredText(schemaId, "Metadata schema id", 128)
    val values: Map<String, String>

    init {
        require(values.size <= MAX_FIELDS) { "Document metadata contains too many fields." }
        val copy = LinkedHashMap<String, String>(values.size)
        var totalLength = 0L
        values.entries.forEach { entry ->
            val key: String? = entry.key
            val value: String? = entry.value
            require(key != null) { "Document metadata field name must not be null." }
            require(value != null) { "Document metadata field value must not be null." }
            val safeKey = requiredText(key, "Document metadata field name", 128)
            val safeValue = metadataValue(value)
            totalLength += safeKey.length.toLong() + safeValue.length.toLong()
            require(totalLength <= MAX_TOTAL_LENGTH) { "Document metadata input is too large." }
            require(copy.put(safeKey, safeValue) == null) { "Document metadata field names must be unique." }
        }
        this.values = Collections.unmodifiableMap(copy)
    }

    private fun metadataValue(value: String): String {
        require(value.length <= MAX_VALUE_LENGTH) {
            "Document metadata field value must not exceed $MAX_VALUE_LENGTH characters."
        }
        require(value.none { character -> Character.isISOControl(character) }) {
            "Document metadata field value must not contain control characters."
        }
        return value
    }

    private companion object {
        const val MAX_FIELDS: Int = 128
        const val MAX_VALUE_LENGTH: Int = 16_384
        const val MAX_TOTAL_LENGTH: Long = 65_536
    }
}

/**
 * Public metadata for a multipart draft upload. The HTTP adapter owns the
 * binary stream; arbitrary asset/storage metadata is deliberately not a v1
 * client input. Folder binding is validated through the host catalog before
 * the application writes the reserved binding metadata.
 */
class CreateDocumentDraftCommand @JvmOverloads constructor(
    documentNumber: String,
    title: String,
    fileName: String,
    val contentLength: Long,
    contentType: String? = null,
    folderId: String? = null,
) {
    val documentNumber: String = requiredText(documentNumber, "Document number", 128)
    val title: String = requiredText(title, "Document title", 512)
    val fileName: String = requiredFileName(fileName, "Document file name", 512)
    val contentType: String? = optionalText(contentType, "Document content type", 128)
    val folderId: String? = optionalText(folderId, "Document folder id", 512)

    init {
        require(contentLength >= 0) { "Document content length must not be negative." }
    }
}

class RenameDocumentCommand(title: String) {
    val title: String = requiredText(title, "Document title", 512)
}

/**
 * Zero-argument transport bean for Jackson installations without the Kotlin
 * module (notably minimal Spring Boot 2 hosts). Controllers must immediately
 * convert it to [RenameDocumentCommand], which performs the actual validation.
 */
class RenameDocumentRequest {
    var title: String? = null
}

/** Public metadata for a multipart version upload; the HTTP adapter owns the binary stream. */
class AddDocumentVersionCommand @JvmOverloads constructor(
    versionNumber: String,
    fileName: String,
    val contentLength: Long,
    contentType: String? = null,
) {
    val versionNumber: String = requiredText(versionNumber, "Document version number", 32)
    val fileName: String = requiredFileName(fileName, "Document version file name", 512)
    val contentType: String? = optionalText(contentType, "Document version content type", 128)

    init {
        require(contentLength >= 0) { "Document version content length must not be negative." }
    }
}

/** Bounded, tenant-context-derived document list filter with an opaque cursor. */
class DocumentPageQuery @JvmOverloads constructor(
    cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
    lifecycleState: String? = null,
    folderId: String? = null,
) {
    val cursor: String? = optionalText(cursor, "Document page cursor", 512)
    val lifecycleState: String? = optionalText(lifecycleState, "Document lifecycle state", 64)
    val folderId: String? = optionalText(folderId, "Document folder id", 512)

    init {
        require(limit in 1..MAX_LIMIT) { "Document page limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 100
    }
}

/** Redacted status of one current-generation downstream delivery target. */
class DocumentDeliverySyncStatusDto(
    deliveryId: String,
    targetId: String,
    displayName: String,
    requirement: String,
    deliveryStatus: String,
    val deliveryRetryCount: Int,
    removalStatus: String,
    val removalRetryCount: Int,
    val deliveryRetryable: Boolean,
    val removalRetryable: Boolean,
    val updatedTime: Long,
) {
    val deliveryId: String = requiredText(deliveryId, "Delivery id", 128)
    val targetId: String = requiredText(targetId, "Delivery target id", 128)
    val displayName: String = requiredText(displayName, "Delivery target display name", 256)
    val requirement: String = requiredText(requirement, "Delivery requirement", 32)
    val deliveryStatus: String = requiredText(deliveryStatus, "Delivery status", 32)
    val removalStatus: String = requiredText(removalStatus, "Delivery removal status", 32)

    init {
        require(deliveryRetryCount >= 0) { "Delivery retry count must not be negative." }
        require(removalRetryCount >= 0) { "Delivery removal retry count must not be negative." }
        require(updatedTime >= 0) { "Delivery status update time must not be negative." }
        require(!(deliveryRetryable && removalRetryable)) {
            "Delivery and removal cannot both be ready for retry."
        }
    }
}

/** Redacted current synchronization state for one readable document. */
class DocumentSyncStatusDto(
    documentId: String,
    deliveryTargets: List<DocumentDeliverySyncStatusDto>,
) {
    val documentId: String = requiredText(documentId, "Document id", 128)
    val deliveryTargets: List<DocumentDeliverySyncStatusDto> = immutableList(deliveryTargets)

    init {
        require(this.deliveryTargets.map { target -> target.deliveryId }.distinct().size == this.deliveryTargets.size) {
            "Synchronization target identifiers must be unique."
        }
    }
}
