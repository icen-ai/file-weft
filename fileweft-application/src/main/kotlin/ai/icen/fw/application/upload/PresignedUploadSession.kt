package ai.icen.fw.application.upload

import ai.icen.fw.application.idempotency.RequestFingerprint
import ai.icen.fw.application.security.validatedTrustedUserId
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.PresignedUploadFinalization
import ai.icen.fw.spi.storage.StorageContentChecksum
import ai.icen.fw.spi.storage.StorageObjectLocation
import java.net.URI
import java.time.Duration
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale

/** Durable application authority for one constrained direct-to-storage PUT. */
class PresignedUploadSession @JvmOverloads constructor(
    val id: Identifier,
    val tenantId: Identifier,
    ownerId: String,
    fileName: String,
    val contentLength: Long,
    contentType: String,
    contentHash: String,
    val checksum: StorageContentChecksum,
    metadata: Map<String, String>,
    storageLocation: StorageObjectLocation,
    val grantExpiresAt: Long,
    val sessionExpiresAt: Long,
    val status: PresignedUploadSessionStatus = PresignedUploadSessionStatus.READY,
    val version: Long = 0,
    val claimTime: Long? = null,
    val finalization: PresignedUploadFinalization? = null,
    lastError: String? = null,
    val createdTime: Long,
    val updatedTime: Long,
    idempotencyKeyDigest: String = legacySessionDigest("idempotency", tenantId, ownerId, id),
    declarationDigest: String = legacySessionDigest("declaration", tenantId, ownerId, id),
    val grantDurationMillis: Long = defaultGrantDurationMillis(createdTime, grantExpiresAt),
    requiredHeaders: Map<String, String> = emptyMap(),
    claimToken: String? = claimTime?.let { legacyClaimToken(id, version) },
    val claimExpiresAt: Long? = claimTime?.let { defaultClaimExpiresAt(it, sessionExpiresAt) },
    val completedTime: Long? = if (status == PresignedUploadSessionStatus.COMPLETED) updatedTime else null,
    val cancelledTime: Long? = if (status == PresignedUploadSessionStatus.CANCELLED) updatedTime else null,
    val cleanupTime: Long? = null,
) {
    /** Unbound staging authority that received the constrained client PUT. */
    val stagingLocation: StorageObjectLocation = storageLocation

    /** Compatibility name for the durable staging authority. */
    val storageLocation: StorageObjectLocation = stagingLocation

    /** Immutable provider-bound location, present only after completion. */
    val finalLocation: StorageObjectLocation?
        get() = finalization?.storedObject?.location

    val ownerId: String = validatedTrustedUserId(ownerId, "Presigned upload owner id")
    val fileName: String = requiredSessionText(fileName, "Presigned upload file name", 1_024)
    val contentType: String = requiredSessionText(contentType, "Presigned upload content type", 256)
    val contentHash: String = requiredSessionText(contentHash, "Presigned upload content hash", 512)
    val metadata: Map<String, String> = immutableSessionMetadata(metadata)
    val requiredHeaders: Map<String, String> = immutableSessionHeaders(requiredHeaders)
    val idempotencyKeyDigest: String = validatedSessionDigest(
        idempotencyKeyDigest,
        "Presigned upload idempotency key digest",
    )
    val declarationDigest: String = validatedSessionDigest(
        declarationDigest,
        "Presigned upload declaration digest",
    )
    val claimToken: String? = claimToken?.let { validatedSessionDigest(it, "Presigned upload claim token") }
    val lastError: String? = lastError?.let {
        requiredSessionText(it, "Presigned upload error", 2_048)
    }

    init {
        requiredSessionText(id.value, "Presigned upload session id", 64)
        requiredSessionText(tenantId.value, "Presigned upload tenant id", 64)
        require(contentLength >= 0) { "Presigned upload content length must not be negative." }
        require(grantExpiresAt > 0 && sessionExpiresAt >= grantExpiresAt) {
            "Presigned upload expiration is invalid."
        }
        require(grantExpiresAt > createdTime) {
            "Presigned upload grant must expire after durable session creation."
        }
        require(grantDurationMillis > 0) { "Presigned upload grant duration must be positive." }
        require(version >= 0) { "Presigned upload session version must not be negative." }
        require(createdTime >= 0 && updatedTime >= createdTime) {
            "Presigned upload session timestamps are invalid."
        }
        val hasCompleteClaim = claimTime != null && this.claimToken != null && claimExpiresAt != null
        val hasAnyClaim = claimTime != null || this.claimToken != null || claimExpiresAt != null
        require((status == PresignedUploadSessionStatus.FINALIZING) == hasCompleteClaim && hasAnyClaim == hasCompleteClaim) {
            "Only a finalizing presigned upload may have a complete claim lease."
        }
        val claimDeadline = claimExpiresAt
        require(
            claimTime == null ||
                (
                    claimDeadline != null &&
                        claimTime >= createdTime &&
                        claimTime == updatedTime &&
                        claimDeadline > claimTime &&
                        claimDeadline <= sessionExpiresAt
                    ),
        ) {
            "Presigned upload claim lease timestamps are invalid."
        }
        require((status == PresignedUploadSessionStatus.COMPLETED) == (finalization != null)) {
            "Only a completed presigned upload may have finalization evidence."
        }
        require((status == PresignedUploadSessionStatus.COMPLETED) == (completedTime != null)) {
            "Only a completed presigned upload may have a completion time."
        }
        require((status == PresignedUploadSessionStatus.CANCELLED) == (cancelledTime != null)) {
            "Only a cancelled presigned upload may have a cancellation time."
        }
        require(completedTime == null || (completedTime >= createdTime && completedTime == updatedTime)) {
            "Presigned upload completion time is invalid."
        }
        require(cancelledTime == null || cancelledTime in createdTime..updatedTime) {
            "Presigned upload cancellation time is invalid."
        }
        require(
            cleanupTime == null ||
                (
                    status in TERMINAL_CLEANUP_STATUSES &&
                        cleanupTime >= grantExpiresAt &&
                        cleanupTime == updatedTime
                    ),
        ) {
            "Presigned upload cleanup time is invalid."
        }
        validateSessionLocation(stagingLocation, "Presigned upload staging location")
        finalization?.storedObject?.location?.let {
            validateSessionLocation(it, "Presigned upload final location")
        }
        require(
            finalization == null ||
                (
                    finalization.tenantId == tenantId &&
                        finalization.bindingId == id &&
                        finalization.sourceLocation == stagingLocation &&
                        finalization.storedObject.location != stagingLocation
                    ),
        ) {
            "Presigned upload finalization authority must bind a distinct immutable location."
        }
        require(
            finalization == null ||
                (
                    finalization.storedObject.contentLength == contentLength &&
                        finalization.storedObject.contentType == contentType &&
                        finalization.storedObject.contentHash == contentHash &&
                        finalization.checksum == checksum &&
                        finalization.metadata == metadata
                    ),
        ) {
            "Presigned upload finalization evidence does not match its durable declaration."
        }
    }
}

enum class PresignedUploadSessionStatus {
    READY,
    FINALIZING,
    COMPLETED,
    CANCELLED,
    EXPIRED,
}

/**
 * Persistence port whose mutation is an exact optimistic CAS.
 *
 * Implementations must scope every operation by tenant, enforce unique IDs,
 * and atomically accept [replacement] only when the stored version equals
 * [expectedVersion]. The replacement version must equal expectedVersion + 1.
 */
interface PresignedUploadSessionRepository {
    fun create(session: PresignedUploadSession): Boolean

    fun findById(tenantId: Identifier, sessionId: Identifier): PresignedUploadSession?

    fun findById(
        tenantId: Identifier,
        ownerId: String,
        sessionId: Identifier,
    ): PresignedUploadSession?

    fun findByIdempotencyKey(
        tenantId: Identifier,
        ownerId: String,
        idempotencyKeyDigest: String,
    ): PresignedUploadSession?

    fun findRecoveryCandidates(now: Long, limit: Int): List<PresignedUploadSession>

    fun findCleanupCandidates(now: Long, limit: Int): List<PresignedUploadSession>

    fun compareAndSet(
        tenantId: Identifier,
        sessionId: Identifier,
        expectedVersion: Long,
        replacement: PresignedUploadSession,
    ): Boolean

    /**
     * Strong claim-aware CAS used by finalize, cancellation and maintenance.
     * Implementations backed by durable storage should override this overload
     * and compare the nullable token with null-safe equality in SQL.
     */
    fun compareAndSet(
        tenantId: Identifier,
        sessionId: Identifier,
        expectedVersion: Long,
        expectedClaimToken: String?,
        replacement: PresignedUploadSession,
    ): Boolean = throw UnsupportedOperationException(
        "This presigned upload repository does not implement claim-token-aware CAS.",
    )
}

/** Request authority for a new direct upload. */
class StartPresignedUploadCommand @JvmOverloads constructor(
    fileName: String,
    val contentLength: Long,
    contentType: String,
    contentHash: String,
    val checksum: StorageContentChecksum,
    metadata: Map<String, String> = emptyMap(),
    val grantDuration: Duration = Duration.ofMinutes(15),
) {
    @JvmOverloads
    constructor(
        fileName: String,
        contentLength: Long,
        contentType: String,
        contentHash: String,
        checksumAlgorithm: String,
        checksumValue: String,
        metadata: Map<String, String> = emptyMap(),
        grantDuration: Duration = Duration.ofMinutes(15),
    ) : this(
        fileName = fileName,
        contentLength = contentLength,
        contentType = contentType,
        contentHash = contentHash,
        checksum = StorageContentChecksum(checksumAlgorithm, checksumValue),
        metadata = metadata,
        grantDuration = grantDuration,
    )

    val fileName: String = requiredSessionText(fileName, "Presigned upload file name", 1_024)
    val contentType: String = requiredSessionText(contentType, "Presigned upload content type", 256)
    val contentHash: String = requiredSessionText(contentHash, "Presigned upload content hash", 512)
    val metadata: Map<String, String> = immutableSessionMetadata(metadata)

    init {
        require(contentLength >= 0) { "Presigned upload content length must not be negative." }
        require(!grantDuration.isNegative && !grantDuration.isZero && grantDuration.toMillis() > 0) {
            "Presigned upload duration must be at least one millisecond."
        }
    }
}

/** Safe result for an HTTP client. Deliberately contains no storage location. */
class PresignedUploadGrantResult @JvmOverloads constructor(
    val sessionId: Identifier,
    val uploadUri: URI,
    requiredHeaders: Map<String, String>,
    val expiresAt: Long,
    val created: Boolean = true,
) {
    val httpMethod: String = "PUT"
    val requiredHeaders: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(requiredHeaders))
}

/** Client command for finalize. Location and object declarations are intentionally absent. */
class CompletePresignedUploadCommand(val sessionId: Identifier)

class ReissuePresignedUploadCommand(val sessionId: Identifier)

class InspectPresignedUploadCommand(val sessionId: Identifier)

class CancelPresignedUploadCommand(val sessionId: Identifier)

/** Application completion including authority for a later FileObject transaction. */
class PresignedUploadCompletionResult(
    val sessionId: Identifier,
    val finalization: PresignedUploadFinalization,
)

/** Location-, header-, revision-, error-, tenant- and owner-free status view. */
class PresignedUploadStatusResult internal constructor(
    val sessionId: Identifier,
    val fileName: String,
    val contentLength: Long,
    val contentType: String,
    val contentHash: String,
    val status: PresignedUploadSessionStatus,
    val grantExpiresAt: Long,
    val sessionExpiresAt: Long,
    val createdTime: Long,
    val updatedTime: Long,
    val completedTime: Long?,
    val cancelledTime: Long?,
    val cleanupTime: Long?,
)

class PresignedUploadMaintenanceResult(
    val discovered: Int,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
)

class PresignedUploadNotFoundException(sessionId: Identifier) : NoSuchElementException(
    "Presigned upload session ${sessionId.value} was not found in the current tenant.",
)

class PresignedUploadStateException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private fun immutableSessionMetadata(source: Map<String, String>): Map<String, String> {
    require(source.size <= 128) { "Presigned upload metadata contains too many entries." }
    val copy = LinkedHashMap<String, String>(source.size)
    var total = 0L
    source.forEach { (rawKey, rawValue) ->
        val key = requiredSessionText(rawKey, "Presigned upload metadata key", 256)
        val value = requiredSessionText(rawValue, "Presigned upload metadata value", 4_096)
        total = Math.addExact(total, key.length.toLong() + value.length.toLong())
        require(total <= 65_536L) { "Presigned upload metadata is too large." }
        copy[key] = value
    }
    return Collections.unmodifiableMap(copy)
}

private fun immutableSessionHeaders(source: Map<String, String>): Map<String, String> {
    val copy = LinkedHashMap<String, String>(source.size)
    val normalized = HashSet<String>(source.size)
    source.forEach { (rawKey, rawValue) ->
        val key = requiredSessionText(rawKey, "Presigned upload header name", 256)
        val value = requiredSessionText(rawValue, "Presigned upload header value", 4_096)
        require(SESSION_HEADER_NAME_PATTERN.matches(key)) { "Presigned upload header name is invalid." }
        val lower = key.lowercase(Locale.ROOT)
        require(lower !in SESSION_CREDENTIAL_HEADER_NAMES) {
            "Presigned upload headers must not contain credentials."
        }
        require(normalized.add(lower)) {
            "Presigned upload headers must be case-insensitively unique."
        }
        copy[key] = value
    }
    require(copy.size <= 128) { "Presigned upload headers contain too many entries." }
    var total = 0L
    copy.forEach { (key, value) ->
        total = Math.addExact(total, key.length.toLong() + value.length.toLong())
    }
    require(total <= 65_536L) {
        "Presigned upload headers are too large."
    }
    return Collections.unmodifiableMap(copy)
}

private fun validatedSessionDigest(value: String, label: String): String = value.also {
    require(SESSION_DIGEST_PATTERN.matches(it)) { "$label must be a versioned SHA-256 digest." }
}

private fun legacySessionDigest(
    purpose: String,
    tenantId: Identifier,
    ownerId: String,
    id: Identifier,
): String = RequestFingerprint.sha256(
    "flowweft-presigned-upload-legacy-$purpose-v1",
    tenantId.value,
    ownerId,
    id.value,
)

private fun legacyClaimToken(id: Identifier, version: Long): String = RequestFingerprint.sha256(
    "flowweft-presigned-upload-legacy-claim-v1",
    id.value,
    version.toString(),
)

private fun defaultGrantDurationMillis(createdTime: Long, grantExpiresAt: Long): Long =
    Math.subtractExact(grantExpiresAt, createdTime)

private fun defaultClaimExpiresAt(claimTime: Long, sessionExpiresAt: Long): Long =
    minOf(Math.addExact(claimTime, DEFAULT_LEGACY_CLAIM_LEASE_MILLIS), sessionExpiresAt)

private fun validateSessionLocation(location: StorageObjectLocation, label: String) {
    requiredSessionText(location.storageType, "$label storage type", 64)
    requiredSessionText(location.path, "$label path", 4_096)
}

private fun requiredSessionText(value: String, label: String, maxLength: Int): String = value.also {
    require(it.isNotBlank()) { "$label must not be blank." }
    require(it.length <= maxLength) { "$label must not exceed $maxLength characters." }
    require(it.hasWellFormedSessionUtf16()) { "$label must contain well-formed Unicode text." }
    require(it.none(::isUnsafeSessionCharacter)) { "$label must not contain unsafe characters." }
}

private fun String.hasWellFormedSessionUtf16(): Boolean {
    var index = 0
    while (index < length) {
        when {
            Character.isHighSurrogate(this[index]) -> {
                if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                index += 2
            }
            Character.isLowSurrogate(this[index]) -> return false
            else -> index += 1
        }
    }
    return true
}

private fun isUnsafeSessionCharacter(character: Char): Boolean =
    Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()

private const val DEFAULT_LEGACY_CLAIM_LEASE_MILLIS = 120_000L
private val TERMINAL_CLEANUP_STATUSES = setOf(
    PresignedUploadSessionStatus.CANCELLED,
    PresignedUploadSessionStatus.EXPIRED,
)
private val SESSION_DIGEST_PATTERN = Regex("sha256:[0-9a-f]{64}")
private val SESSION_HEADER_NAME_PATTERN = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+")
private val SESSION_CREDENTIAL_HEADER_NAMES = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "cookie2",
    "x-oss-security-token",
    "x-amz-security-token",
)
