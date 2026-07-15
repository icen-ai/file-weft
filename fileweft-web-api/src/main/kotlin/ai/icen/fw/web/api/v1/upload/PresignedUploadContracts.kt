package ai.icen.fw.web.api.v1.upload

import ai.icen.fw.web.api.requiredFileName
import ai.icen.fw.web.api.requiredText
import java.net.URI
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale

/** Transfer declarations only; tenant, owner, storage authority and credentials are trusted server context. */
class StartPresignedUploadRequest {
    var fileName: String? = null
    var contentLength: Long? = null
    var contentType: String? = null
    var contentHash: String? = null
    var checksumAlgorithm: String? = null
    var checksumValue: String? = null
}

class StartPresignedUploadCommand(
    fileName: String,
    val contentLength: Long,
    contentType: String,
    contentHash: String,
    checksumAlgorithm: String,
    checksumValue: String,
) {
    val fileName: String = requiredFileName(fileName, "Presigned upload file name", 512)
    val contentType: String = requiredText(contentType, "Presigned upload content type", 256)
    val contentHash: String = requiredText(contentHash, "Presigned upload content hash", 512)
    val checksumAlgorithm: String = requiredText(
        checksumAlgorithm,
        "Presigned upload checksum algorithm",
        64,
    ).lowercase(Locale.ROOT)
    val checksumValue: String = requiredText(checksumValue, "Presigned upload checksum value", 1_024)

    init {
        require(contentLength >= 0) { "Presigned upload content length must not be negative." }
        require(SHA256_CONTENT_HASH.matches(this.contentHash)) {
            "Presigned upload content hash must be a lowercase SHA-256 digest prefixed with sha256:."
        }
        require(CHECKSUM_ALGORITHM.matches(this.checksumAlgorithm)) {
            "Presigned upload checksum algorithm is invalid."
        }
    }

    private companion object {
        val SHA256_CONTENT_HASH: Regex = Regex("sha256:[0-9a-f]{64}")
        val CHECKSUM_ALGORITHM: Regex = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}

/** A short-lived PUT capability. Internal storage type/path and raw authorization are deliberately absent. */
class PresignedUploadGrantDto(
    uploadId: String,
    val uploadUrl: URI,
    requiredHeaders: Map<String, String>,
    val expiresAt: Long,
    val created: Boolean,
) {
    val uploadId: String = requiredText(uploadId, "Presigned upload id", 128)
    val method: String = "PUT"
    val requiredHeaders: Map<String, String> = immutableSafeHeaders(requiredHeaders)

    init {
        require(uploadUrl.isAbsolute && uploadUrl.scheme.equals("https", ignoreCase = true)) {
            "Presigned upload URL must use HTTPS."
        }
        require(!uploadUrl.host.isNullOrBlank() && uploadUrl.userInfo == null && uploadUrl.fragment == null) {
            "Presigned upload URL is unsafe."
        }
        require(expiresAt > 0) { "Presigned upload expiration must be positive." }
    }
}

class PresignedUploadStatuses private constructor() {
    companion object {
        const val READY: String = "READY"
        const val FINALIZING: String = "FINALIZING"
        const val COMPLETED: String = "COMPLETED"
        const val CANCELLED: String = "CANCELLED"
        const val EXPIRED: String = "EXPIRED"

        internal val ALL: Set<String> = setOf(READY, FINALIZING, COMPLETED, CANCELLED, EXPIRED)
    }
}

/** Redacted owner-scoped state; provider locations, headers, revisions and failures never cross this boundary. */
class PresignedUploadDto(
    uploadId: String,
    fileName: String,
    val contentLength: Long,
    contentType: String,
    contentHash: String,
    status: String,
    val grantExpiresAt: Long,
    val sessionExpiresAt: Long,
    val createdTime: Long,
    val updatedTime: Long,
    val completedTime: Long?,
    val cancelledTime: Long?,
    val cleanupTime: Long?,
) {
    val uploadId: String = requiredText(uploadId, "Presigned upload id", 128)
    val fileName: String = requiredFileName(fileName, "Presigned upload file name", 512)
    val contentType: String = requiredText(contentType, "Presigned upload content type", 256)
    val contentHash: String = requiredText(contentHash, "Presigned upload content hash", 512)
    val status: String = requiredText(status, "Presigned upload status", 32)

    init {
        require(contentLength >= 0) { "Presigned upload content length must not be negative." }
        require(this.status in PresignedUploadStatuses.ALL) { "Presigned upload status is not public." }
        require(grantExpiresAt > createdTime && sessionExpiresAt >= grantExpiresAt) {
            "Presigned upload expiration is invalid."
        }
        require(createdTime >= 0 && updatedTime >= createdTime) { "Presigned upload timestamps are invalid." }
    }
}

/** Stable result of an atomic provider-evidence to FileObject/FileAsset claim. */
class PresignedUploadFinalizationDto(
    uploadId: String,
    fileObjectId: String,
    fileAssetId: String,
    val replayed: Boolean,
) {
    val uploadId: String = requiredText(uploadId, "Presigned upload id", 128)
    val fileObjectId: String = requiredText(fileObjectId, "Presigned upload file object id", 128)
    val fileAssetId: String = requiredText(fileAssetId, "Presigned upload file asset id", 128)
    val status: String = PresignedUploadStatuses.COMPLETED
}

private fun immutableSafeHeaders(source: Map<String, String>): Map<String, String> {
    require(source.size <= 128) { "Presigned upload contains too many required headers." }
    val result = LinkedHashMap<String, String>(source.size)
    val normalized = HashSet<String>(source.size)
    source.forEach { (rawName, rawValue) ->
        val name = requiredText(rawName, "Presigned upload header name", 256)
        val value = requiredText(rawValue, "Presigned upload header value", 4_096)
        require(HEADER_NAME.matches(name)) { "Presigned upload header name is invalid." }
        val lower = name.lowercase(Locale.ROOT)
        require(lower !in CREDENTIAL_HEADERS) { "Presigned upload headers must not contain credentials." }
        require(normalized.add(lower)) { "Presigned upload headers must be case-insensitively unique." }
        result[name] = value
    }
    return Collections.unmodifiableMap(result)
}

private val HEADER_NAME = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+")
private val CREDENTIAL_HEADERS = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "cookie2",
    "x-oss-security-token",
    "x-amz-security-token",
)
