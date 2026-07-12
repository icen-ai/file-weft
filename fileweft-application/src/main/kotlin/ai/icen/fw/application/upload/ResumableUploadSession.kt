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
    /*
     * Keep the released 19-parameter primary constructor unchanged. Kotlin callers that omitted
     * defaulted arguments were compiled against its synthetic constructor descriptor, which must
     * remain present at runtime. The owned secondary constructor below is the additive ABI.
     *
     * The backing field is private and written only while a secondary constructor is running;
     * volatile publication keeps the externally read-only property safe across threads.
     */
    @Volatile
    private var ownerIdValue: String? = null

    /**
     * Opaque identity from the trusted [ai.icen.fw.spi.identity.UserRealmProvider].
     * `null` identifies a legacy session that request users must never claim.
     */
    val ownerId: String?
        get() = ownerIdValue

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

    @JvmOverloads
    constructor(
        id: Identifier,
        tenantId: Identifier,
        idempotencyKey: String,
        storageUploadId: Identifier,
        storageLocation: StorageObjectLocation,
        fileObjectId: Identifier,
        fileAssetId: Identifier,
        fileName: String,
        contentLength: Long,
        assetType: String,
        contentType: String? = null,
        expectedContentHash: String? = null,
        metadata: Map<String, String> = emptyMap(),
        status: ResumableUploadSessionStatus = ResumableUploadSessionStatus.ACTIVE,
        expiresAt: Long,
        lastError: String? = null,
        completedAt: Long? = null,
        createdTime: Long,
        updatedTime: Long,
        ownerId: String?,
    ) : this(
        id = id,
        tenantId = tenantId,
        idempotencyKey = idempotencyKey,
        storageUploadId = storageUploadId,
        storageLocation = storageLocation,
        fileObjectId = fileObjectId,
        fileAssetId = fileAssetId,
        fileName = fileName,
        contentLength = contentLength,
        assetType = assetType,
        contentType = contentType,
        expectedContentHash = expectedContentHash,
        metadata = metadata,
        status = status,
        expiresAt = expiresAt,
        lastError = lastError,
        completedAt = completedAt,
        createdTime = createdTime,
        updatedTime = updatedTime,
    ) {
        ownerIdValue = ownerId?.let(::validatedResumableUploadOwnerId)
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
    EXPIRED,
    /** Permanently fenced from every user path after an ownership/persistence contract failure. */
    QUARANTINED;

    fun isTerminal(): Boolean = this == COMPLETED || this == ABORTED || this == EXPIRED || this == QUARANTINED
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

data class StartResumableUploadCommand @JvmOverloads constructor(
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

internal const val MAX_RESUMABLE_UPLOAD_OWNER_ID_LENGTH: Int = 256

internal fun validatedResumableUploadOwnerId(ownerId: String): String = ownerId.also {
    require(it.isNotEmpty()) { "Upload session owner id must not be blank." }
    require(it.length <= MAX_RESUMABLE_UPLOAD_OWNER_ID_LENGTH) {
        "Upload session owner id must not exceed $MAX_RESUMABLE_UPLOAD_OWNER_ID_LENGTH UTF-16 code units."
    }
    require(!it.hasUnpairedSurrogate()) {
        "Upload session owner id must be well-formed Unicode."
    }
    require(!it.firstCodePoint().isOwnerBoundaryWhitespace() && !it.lastCodePoint().isOwnerBoundaryWhitespace()) {
        "Upload session owner id must not have leading or trailing Unicode whitespace."
    }
    require(!it.hasForbiddenOwnerCodePoint()) {
        "Upload session owner id must not contain ISO control or forbidden Unicode format characters."
    }
}

/*
 * Keep this table deliberately explicit. Character.getType() follows the Unicode data bundled
 * with the running JDK, so using it would make the public contract differ between JDK 8 and 25
 * and from the fixed PostgreSQL constraint installed by V024.
 */
private fun String.hasForbiddenOwnerCodePoint(): Boolean {
    var offset = 0
    while (offset < length) {
        val codePoint = Character.codePointAt(this, offset)
        if (codePoint.isIsoControlCodePoint() || codePoint.isForbiddenOwnerFormatCodePoint()) return true
        offset += Character.charCount(codePoint)
    }
    return false
}

private fun String.hasUnpairedSurrogate(): Boolean {
    var offset = 0
    while (offset < length) {
        val current = this[offset]
        when {
            Character.isHighSurrogate(current) -> {
                if (offset + 1 >= length || !Character.isLowSurrogate(this[offset + 1])) return true
                offset += 2
            }
            Character.isLowSurrogate(current) -> return true
            else -> offset++
        }
    }
    return false
}

private fun String.firstCodePoint(): Int = Character.codePointAt(this, 0)

private fun String.lastCodePoint(): Int = Character.codePointBefore(this, length)

private fun Int.isIsoControlCodePoint(): Boolean = this in 0x0000..0x001F || this in 0x007F..0x009F

private fun Int.isOwnerBoundaryWhitespace(): Boolean =
    this == 0x0020 ||
        this == 0x00A0 ||
        this == 0x1680 ||
        this in 0x2000..0x200A ||
        this in 0x2028..0x2029 ||
        this == 0x202F ||
        this == 0x205F ||
        this == 0x3000

private fun Int.isForbiddenOwnerFormatCodePoint(): Boolean =
    this == 0x00AD ||
        this in 0x0600..0x0605 ||
        this == 0x061C ||
        this == 0x06DD ||
        this == 0x070F ||
        this in 0x0890..0x0891 ||
        this == 0x08E2 ||
        this == 0x180E ||
        this in 0x200B..0x200F ||
        this in 0x202A..0x202E ||
        this in 0x2060..0x2064 ||
        this in 0x2066..0x206F ||
        this == 0xFEFF ||
        this in 0xFFF9..0xFFFB ||
        this == 0x110BD ||
        this == 0x110CD ||
        this in 0x13430..0x1343F ||
        this in 0x1BCA0..0x1BCA3 ||
        this in 0x1D173..0x1D17A ||
        this == 0xE0001 ||
        this in 0xE0020..0xE007F
