package ai.icen.fw.application.upload

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.StorageObjectLocation

/** Durable application-level state for a resumable multipart upload. */
class ResumableUploadSession @JvmOverloads constructor(
    val id: Identifier,
    val tenantId: Identifier,
    val idempotencyKey: String,
    val storageUploadId: Identifier,
    val storageLocation: StorageObjectLocation,
    val fileObjectId: Identifier,
    val fileAssetId: Identifier,
    val fileName: String,
    val contentLength: Long,
    val assetType: String,
    val contentType: String? = null,
    val expectedContentHash: String? = null,
    metadata: Map<String, String> = emptyMap(),
    val status: ResumableUploadSessionStatus = ResumableUploadSessionStatus.ACTIVE,
    val expiresAt: Long,
    val lastError: String? = null,
    val completedAt: Long? = null,
    val createdTime: Long,
    val updatedTime: Long,
) {
    val metadata: Map<String, String> = LinkedHashMap(metadata)

    init {
        require(idempotencyKey.isNotBlank()) { "Upload session idempotency key must not be blank." }
        require(fileName.isNotBlank()) { "Upload session file name must not be blank." }
        require(contentLength > 0) { "Resumable upload content length must be positive." }
        require(assetType.isNotBlank()) { "Upload session asset type must not be blank." }
        require(expectedContentHash == null || expectedContentHash.isNotBlank()) {
            "Expected content hash must not be blank when provided."
        }
        require(metadata.all { (key, value) -> key.isNotBlank() && value.isNotBlank() }) {
            "Upload session metadata keys and values must not be blank."
        }
        require(expiresAt > 0) { "Upload session expiration must be positive." }
        require(createdTime >= 0 && updatedTime >= createdTime) { "Upload session timestamps are invalid." }
        require(lastError == null || lastError.isNotBlank()) { "Upload session error must not be blank when provided." }
        require(completedAt == null || completedAt >= createdTime) { "Upload session completion time is invalid." }
        require(
            if (status == ResumableUploadSessionStatus.COMPLETED) completedAt != null else completedAt == null,
        ) { "Only a completed upload session may have a completion time." }
    }

    fun isExpired(now: Long): Boolean = now >= expiresAt && !status.isTerminal()
}

enum class ResumableUploadSessionStatus {
    ACTIVE,
    COMPLETING,
    COMPLETED,
    ABORTING,
    ABORTED,
    FAILED,
    EXPIRED;

    fun isTerminal(): Boolean = this == COMPLETED || this == ABORTED || this == EXPIRED
}

/** One immutable acknowledgement returned by the object storage multipart protocol. */
class ResumableUploadPart(
    val id: Identifier,
    val tenantId: Identifier,
    val sessionId: Identifier,
    val partNumber: Int,
    val eTag: String,
    val contentLength: Long,
    val createdTime: Long,
    val updatedTime: Long,
) {
    init {
        require(partNumber in 1..MAX_PART_NUMBER) { "Multipart part number must be between 1 and $MAX_PART_NUMBER." }
        require(eTag.isNotBlank()) { "Multipart part eTag must not be blank." }
        require(contentLength >= 0) { "Multipart part length must not be negative." }
        require(createdTime >= 0 && updatedTime >= createdTime) { "Multipart part timestamps are invalid." }
    }

    companion object {
        const val MAX_PART_NUMBER = 10_000
    }
}

data class StartResumableUploadCommand(
    val fileName: String,
    val contentLength: Long,
    val assetType: String,
    val idempotencyKey: String,
    val contentType: String? = null,
    val contentHash: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(fileName.isNotBlank()) { "File name must not be blank." }
        require(contentLength > 0) { "Resumable upload content length must be positive." }
        require(assetType.isNotBlank()) { "Asset type must not be blank." }
        require(idempotencyKey.isNotBlank()) { "Resumable upload idempotency key must not be blank." }
        require(contentHash == null || contentHash.isNotBlank()) { "Content hash must not be blank when provided." }
        require(metadata.all { (key, value) -> key.isNotBlank() && value.isNotBlank() }) {
            "Upload metadata keys and values must not be blank."
        }
    }
}

data class ResumableUploadSessionView(
    val session: ResumableUploadSession,
    val parts: List<ResumableUploadPart>,
)

data class ExpiredResumableUploadCleanupResult(
    val inspected: Int,
    val expired: Int,
    val failed: Int,
)

/** Safe operational summary for a completion whose remote outcome must be reconciled manually. */
class StalledResumableUploadSession(
    val id: Identifier,
    val tenantId: Identifier,
    val fileName: String,
    val contentLength: Long,
    val expiresAt: Long,
    val updatedTime: Long,
    val lastError: String? = null,
) {
    init {
        require(fileName.isNotBlank()) { "Stalled upload file name must not be blank." }
        require(contentLength > 0) { "Stalled upload content length must be positive." }
        require(expiresAt > 0 && updatedTime >= 0) { "Stalled upload timestamps are invalid." }
        require(lastError == null || lastError.isNotBlank()) { "Stalled upload error must not be blank when provided." }
    }
}

class ResumableUploadNotFoundException(sessionId: Identifier) : NoSuchElementException(
    "Upload session ${sessionId.value} was not found in the current tenant.",
)

class ResumableUploadStateException(message: String) : IllegalStateException(message)
