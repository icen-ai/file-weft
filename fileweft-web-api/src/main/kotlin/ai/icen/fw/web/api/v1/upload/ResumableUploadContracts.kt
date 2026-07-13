package ai.icen.fw.web.api.v1.upload

import ai.icen.fw.web.api.immutableList
import ai.icen.fw.web.api.optionalText
import ai.icen.fw.web.api.requiredFileName
import ai.icen.fw.web.api.requiredText

/**
 * Mutable JSON bean for starting a formal v1 resumable upload.
 *
 * The deliberately small surface contains transfer metadata only. In
 * particular, callers cannot choose an owner, tenant, asset type, storage
 * location, or arbitrary storage metadata.
 */
class StartResumableUploadRequest {
    var fileName: String? = null
    var contentLength: Long? = null
    var contentType: String? = null
    var contentHash: String? = null
}

/** Validated transfer metadata used by the transport-neutral runtime facade. */
class StartResumableUploadCommand @JvmOverloads constructor(
    fileName: String,
    val contentLength: Long,
    contentType: String? = null,
    contentHash: String? = null,
) {
    val fileName: String = requiredFileName(fileName, "Upload file name", 512)
    val contentType: String? = optionalText(contentType, "Upload content type", 128)
    val contentHash: String? = optionalText(contentHash, "Upload content hash", 256)

    init {
        require(contentLength > 0) { "Upload content length must be positive." }
        require(this.contentHash == null || SHA256_CONTENT_HASH.matches(this.contentHash)) {
            "Upload content hash must be a lowercase SHA-256 digest prefixed with sha256:."
        }
    }

    private companion object {
        val SHA256_CONTENT_HASH: Regex = Regex("sha256:[0-9a-f]{64}")
    }
}

/** Public v1 upload states. Internal staging and quarantine states are absent by design. */
class ResumableUploadStatuses private constructor() {
    companion object {
        const val UPLOADING: String = "UPLOADING"
        const val FINALIZING: String = "FINALIZING"
        const val COMPLETED: String = "COMPLETED"
        const val FAILED: String = "FAILED"
        const val ABORTED: String = "ABORTED"
        const val EXPIRED: String = "EXPIRED"

        internal val ALL: Set<String> = setOf(UPLOADING, FINALIZING, COMPLETED, FAILED, ABORTED, EXPIRED)
    }
}

/** Safe acknowledgement of one uploaded part; storage ETags never cross the public boundary. */
class ResumableUploadPartDto(
    uploadId: String,
    val partNumber: Int,
    val contentLength: Long,
    val uploadedTime: Long,
) {
    val uploadId: String = requiredText(uploadId, "Upload id", 128)

    init {
        require(partNumber in 1..MAX_PART_NUMBER) {
            "Upload part number must be between 1 and $MAX_PART_NUMBER."
        }
        require(contentLength > 0) { "Upload part content length must be positive." }
        require(uploadedTime >= 0) { "Upload part time must not be negative." }
    }

    companion object {
        const val MAX_PART_NUMBER: Int = 10_000
    }
}

/** Opaque receipt for a completed upload. It contains no storage implementation details. */
class ResumableUploadCompletionDto @JvmOverloads constructor(
    uploadId: String,
    fileObjectId: String,
    fileAssetId: String,
    val completedAt: Long? = null,
) {
    val uploadId: String = requiredText(uploadId, "Upload id", 128)
    val fileObjectId: String = requiredText(fileObjectId, "Upload file object id", 128)
    val fileAssetId: String = requiredText(fileAssetId, "Upload file asset id", 128)

    init {
        require(completedAt == null || completedAt >= 0) { "Upload completion time must not be negative." }
    }
}

/**
 * Redacted state of one resumable upload owned by the current trusted user.
 *
 * `uploadedParts` is an immutable snapshot. A completion receipt is present
 * only after the application has durably completed the upload.
 */
class ResumableUploadDto @JvmOverloads constructor(
    uploadId: String,
    fileName: String,
    val contentLength: Long,
    status: String,
    val expiresAt: Long,
    val createdTime: Long,
    val updatedTime: Long,
    uploadedParts: List<ResumableUploadPartDto>,
    contentType: String? = null,
    contentHash: String? = null,
    val completion: ResumableUploadCompletionDto? = null,
) {
    val uploadId: String = requiredText(uploadId, "Upload id", 128)
    val fileName: String = requiredFileName(fileName, "Upload file name", 512)
    val contentType: String? = optionalText(contentType, "Upload content type", 128)
    val contentHash: String? = optionalText(contentHash, "Upload content hash", 256)
    val status: String = requiredText(status, "Upload status", 32)
    val uploadedParts: List<ResumableUploadPartDto> = immutableList(uploadedParts)

    init {
        require(contentLength > 0) { "Upload content length must be positive." }
        require(this.status in ResumableUploadStatuses.ALL) { "Upload status is not public." }
        require(expiresAt > 0) { "Upload expiration time must be positive." }
        require(createdTime >= 0) { "Upload creation time must not be negative." }
        require(updatedTime >= createdTime) { "Upload update time must not precede creation time." }
        require(this.uploadedParts.all { part -> part.uploadId == this.uploadId }) {
            "Uploaded parts must belong to the upload."
        }
        require(this.uploadedParts.map { part -> part.partNumber }.distinct().size == this.uploadedParts.size) {
            "Uploaded part numbers must be unique."
        }
        require(completion == null || completion.uploadId == this.uploadId) {
            "Upload completion must belong to the upload."
        }
        require((this.status == ResumableUploadStatuses.COMPLETED) == (completion != null)) {
            "Only a completed upload may contain a completion receipt."
        }
    }
}
