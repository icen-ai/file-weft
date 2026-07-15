package ai.icen.fw.spi.storage

import ai.icen.fw.core.id.Identifier
import java.net.URI
import java.time.Duration
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Optional direct-upload capability for storage providers that can sign a
 * constrained HTTP PUT and later attest the exact stored object.
 *
 * [StorageAdapter] remains unchanged for binary compatibility. A caller must
 * persist the opaque staging [PresignedUploadGrant.location] and every
 * immutable request field before returning the URL to an untrusted client.
 * Finalization must be built from that authoritative state; a browser-supplied
 * location is never an acceptable substitute. A provider whose staging key is
 * mutable or versioned must return an immutable revision-bound final location.
 * Tenant and binding identifiers are part of the provider-side attestation:
 * using them only to choose a key without signing and verifying their binding
 * is not a conforming implementation.
 */
interface PresignedUploadStorageAdapter {
    /**
     * Allocates and signs a new staging authority without performing a remote
     * object write. Repeating this method is not an idempotency mechanism;
     * callers persist the first result and use [reissueUploadGrant] thereafter.
     */
    fun createUploadGrant(request: PresignedUploadGrantRequest): PresignedUploadGrant

    /**
     * Re-signs the exact durable staging authority of an existing grant.
     *
     * The absolute deadline is authoritative: implementations must not extend
     * it, allocate another staging object, or change the required headers.
     */
    fun reissueUploadGrant(request: PresignedUploadReissueRequest): PresignedUploadGrant

    /**
     * Verifies, but does not mutate, the exact uploaded revision. The same
     * request must be safe to retry and return equivalent authority evidence.
     * Missing, mutable, ambiguous, or declaration-mismatched evidence fails
     * explicitly and must never be accepted as a best-effort finalization.
     */
    fun finalizeUpload(request: PresignedUploadFinalizeRequest): PresignedUploadFinalization

    /**
     * Idempotently removes an expired, unfinalized staging authority.
     * Implementations must reject immutable final locations.
     */
    fun cleanupUpload(request: PresignedUploadCleanupRequest)
}

/** Provider-neutral checksum that a storage service can validate on upload. */
class StorageContentChecksum(
    algorithm: String,
    value: String,
) {
    val algorithm: String = requiredPresignedText(algorithm, "Storage checksum algorithm", 64)
        .lowercase(Locale.ROOT)
    val value: String = requiredPresignedText(value, "Storage checksum value", 1_024)

    init {
        require(CHECKSUM_ALGORITHM_PATTERN.matches(this.algorithm)) {
            "Storage checksum algorithm must be a canonical provider-neutral token."
        }
        require(this.value.none { character -> Character.isWhitespace(character) }) {
            "Storage checksum value must not contain whitespace."
        }
    }

    override fun equals(other: Any?): Boolean =
        other is StorageContentChecksum && algorithm == other.algorithm && value == other.value

    override fun hashCode(): Int = 31 * algorithm.hashCode() + value.hashCode()

    override fun toString(): String = "StorageContentChecksum(algorithm=$algorithm, value=redacted)"
}

/** Exact immutable input to one provider PUT grant. */
class PresignedUploadGrantRequest @JvmOverloads constructor(
    val bindingId: Identifier,
    val tenantId: Identifier,
    objectName: String,
    val contentLength: Long,
    contentType: String,
    contentHash: String,
    val checksum: StorageContentChecksum,
    metadata: Map<String, String> = emptyMap(),
    val expiresIn: Duration = Duration.ofMinutes(15),
) {
    val objectName: String = requiredPresignedText(objectName, "Presigned upload object name", 1_024)
    val contentType: String = requiredPresignedText(contentType, "Presigned upload content type", 256)
    val contentHash: String = requiredPresignedText(contentHash, "Presigned upload content hash", 512)
    val metadata: Map<String, String> = immutablePresignedMap(metadata, "Presigned upload metadata")

    init {
        require(contentLength >= 0) { "Presigned upload content length must not be negative." }
        require(!expiresIn.isNegative && !expiresIn.isZero && expiresIn.toMillis() > 0) {
            "Presigned upload expiration must be at least one millisecond."
        }
    }
}

/**
 * Signed HTTP PUT capability. [requiredHeaders] is an exact client contract:
 * every entry must be sent unchanged and callers must not add an Authorization
 * header. The location is application-internal authority, not a client field.
 */
class PresignedUploadGrant(
    val location: StorageObjectLocation,
    val uploadUri: URI,
    requiredHeaders: Map<String, String>,
    /** Unix epoch milliseconds; the grant is unusable at or after this instant. */
    val expiresAt: Long,
) {
    val httpMethod: String = "PUT"
    val requiredHeaders: Map<String, String> = immutablePresignedHeaders(requiredHeaders)

    init {
        validatePresignedLocation(location, "Presigned upload staging location")
        require(uploadUri.isAbsolute && uploadUri.scheme.equals("https", ignoreCase = true)) {
            "Presigned upload URI must be an absolute HTTPS URI."
        }
        require(!uploadUri.host.isNullOrBlank() && uploadUri.userInfo == null && uploadUri.fragment == null) {
            "Presigned upload URI is unsafe."
        }
        require(expiresAt > 0) { "Presigned upload expiration must be positive." }
    }
}

/**
 * Durable, location-bound authority used to re-sign an exact unexpired PUT.
 * It deliberately contains no caller idempotency key and no previous URI.
 */
class PresignedUploadReissueRequest @JvmOverloads constructor(
    val bindingId: Identifier,
    val tenantId: Identifier,
    val location: StorageObjectLocation,
    val contentLength: Long,
    contentType: String,
    contentHash: String,
    val checksum: StorageContentChecksum,
    metadata: Map<String, String> = emptyMap(),
    requiredHeaders: Map<String, String>,
    /** Original absolute deadline in Unix epoch milliseconds; reissue must preserve it exactly. */
    val expiresAt: Long,
) {
    val contentType: String = requiredPresignedText(contentType, "Presigned upload content type", 256)
    val contentHash: String = requiredPresignedText(contentHash, "Presigned upload content hash", 512)
    val metadata: Map<String, String> = immutablePresignedMap(metadata, "Presigned upload metadata")
    val requiredHeaders: Map<String, String> = immutablePresignedHeaders(requiredHeaders)

    init {
        validatePresignedLocation(location, "Presigned upload staging location")
        require(contentLength >= 0) { "Presigned upload content length must not be negative." }
        require(expiresAt > 0) { "Presigned upload expiration must be positive." }
    }
}

/** Exact unfinalized staging authority eligible for idempotent cleanup. */
class PresignedUploadCleanupRequest(
    val bindingId: Identifier,
    val tenantId: Identifier,
    val location: StorageObjectLocation,
) {
    init {
        validatePresignedLocation(location, "Presigned upload cleanup location")
    }
}

/** Exact authority loaded from a durable grant session for provider verification. */
class PresignedUploadFinalizeRequest @JvmOverloads constructor(
    val bindingId: Identifier,
    val tenantId: Identifier,
    val location: StorageObjectLocation,
    val contentLength: Long,
    contentType: String,
    contentHash: String,
    val checksum: StorageContentChecksum,
    metadata: Map<String, String> = emptyMap(),
) {
    val contentType: String = requiredPresignedText(contentType, "Presigned upload content type", 256)
    val contentHash: String = requiredPresignedText(contentHash, "Presigned upload content hash", 512)
    val metadata: Map<String, String> = immutablePresignedMap(metadata, "Presigned upload metadata")

    init {
        validatePresignedLocation(location, "Presigned upload staging location")
        require(contentLength >= 0) { "Presigned upload content length must not be negative." }
    }
}

/**
 * Authoritative provider evidence for one finalized direct upload.
 *
 * [sourceLocation] is the durable staging authority that received the signed
 * PUT. [storedObject] must use a distinct opaque location that binds the
 * attested immutable provider revision. Application code must verify the
 * tenant, binding and source location against its durable session before it
 * accepts the final location.
 */
class PresignedUploadFinalization(
    val tenantId: Identifier,
    val bindingId: Identifier,
    val sourceLocation: StorageObjectLocation,
    val storedObject: StoredObject,
    revision: String,
    val checksum: StorageContentChecksum,
    metadata: Map<String, String>,
) {
    val revision: String = requiredPresignedText(revision, "Presigned upload revision", 2_048)
    val metadata: Map<String, String> = immutablePresignedMap(metadata, "Finalized upload metadata")

    init {
        validatePresignedLocation(sourceLocation, "Presigned upload source location")
        validatePresignedLocation(storedObject.location, "Presigned upload immutable location")
        require(storedObject.location != sourceLocation) {
            "Presigned upload finalization must bind a distinct immutable location."
        }
        require(storedObject.location.storageType == sourceLocation.storageType) {
            "Presigned upload finalization must remain within the source storage adapter."
        }
        val storedContentType = requireNotNull(storedObject.contentType) {
            "Presigned upload finalization must include content type evidence."
        }
        val storedContentHash = requireNotNull(storedObject.contentHash) {
            "Presigned upload finalization must include content hash evidence."
        }
        requiredPresignedText(storedContentType, "Finalized upload content type", 256)
        requiredPresignedText(storedContentHash, "Finalized upload content hash", 512)
    }
}

private fun validatePresignedLocation(location: StorageObjectLocation, label: String) {
    requiredPresignedText(location.storageType, "$label storage type", 64)
    requiredPresignedText(location.path, "$label path", 4_096)
}

private fun immutablePresignedMap(source: Map<String, String>, label: String): Map<String, String> {
    require(source.size <= 128) { "$label contains too many entries." }
    val copy = LinkedHashMap<String, String>(source.size)
    var total = 0L
    source.forEach { (rawKey, rawValue) ->
        val key = requiredPresignedText(rawKey, "$label key", 256)
        val value = requiredPresignedText(rawValue, "$label value", 4_096)
        total = Math.addExact(total, key.length.toLong() + value.length.toLong())
        require(total <= 65_536L) { "$label is too large." }
        require(copy.put(key, value) == null) { "$label keys must be unique." }
    }
    return Collections.unmodifiableMap(copy)
}

private fun immutablePresignedHeaders(source: Map<String, String>): Map<String, String> {
    val headers = immutablePresignedMap(source, "Presigned upload required headers")
    val normalizedHeaders = HashSet<String>(headers.size)
    headers.keys.forEach { header ->
        require(HTTP_HEADER_NAME_PATTERN.matches(header)) {
            "Presigned upload header name is invalid."
        }
        val normalized = header.lowercase(Locale.ROOT)
        require(normalized !in CREDENTIAL_HEADER_NAMES) {
            "Presigned upload headers must not contain credentials."
        }
        require(normalizedHeaders.add(normalized)) {
            "Presigned upload headers must be case-insensitively unique."
        }
    }
    return headers
}

private fun requiredPresignedText(value: String, label: String, maxLength: Int): String = value.also {
    require(it.isNotBlank()) { "$label must not be blank." }
    require(it.length <= maxLength) { "$label must not exceed $maxLength characters." }
    require(it.hasWellFormedPresignedUtf16()) { "$label must contain well-formed Unicode text." }
    require(it.none(::isUnsafePresignedCharacter)) { "$label must not contain unsafe characters." }
}

private fun String.hasWellFormedPresignedUtf16(): Boolean {
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

private fun isUnsafePresignedCharacter(character: Char): Boolean =
    Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()

private val HTTP_HEADER_NAME_PATTERN = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+")
private val CHECKSUM_ALGORITHM_PATTERN = Regex("[a-z0-9][a-z0-9._+-]{0,63}")
private val CREDENTIAL_HEADER_NAMES = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "cookie2",
    "x-oss-security-token",
    "x-amz-security-token",
)
