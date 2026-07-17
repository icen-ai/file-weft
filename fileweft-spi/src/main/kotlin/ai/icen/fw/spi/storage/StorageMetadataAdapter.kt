package ai.icen.fw.spi.storage

import java.util.Collections
import java.util.LinkedHashMap

/**
 * Optional provider HEAD capability without leaking a vendor SDK type.
 * Its [StorageObjectLocation] argument is opaque authority loaded from
 * tenant-authorized server state, not a path accepted directly from an
 * untrusted caller.
 */
interface StorageMetadataAdapter {
    fun metadata(location: StorageObjectLocation): StorageObjectMetadata
}

/**
 * Authoritative metadata observed for one current provider object revision.
 *
 * [revision] is an opaque conditional-request token such as a version id or
 * ETag. It is identity evidence only and must never be treated as a content
 * hash. [metadata] contains bounded user metadata, never response headers,
 * credentials, request identifiers, or signed URLs.
 */
class StorageObjectMetadata @JvmOverloads constructor(
    val location: StorageObjectLocation,
    val contentLength: Long,
    contentType: String? = null,
    contentHash: String? = null,
    revision: String? = null,
    metadata: Map<String, String> = emptyMap(),
    /** Provider observation time in Unix epoch milliseconds, when available. */
    val lastModifiedTime: Long? = null,
) {
    val contentType: String? = optionalStorageMetadataText(contentType, "Storage content type", 256)
    val contentHash: String? = optionalStorageMetadataText(contentHash, "Storage content hash", 512)
    val revision: String? = optionalStorageMetadataText(revision, "Storage revision", 2_048)
    val metadata: Map<String, String>

    init {
        requiredStorageMetadataText(location.storageType, "Storage metadata location type", 64)
        requiredStorageMetadataText(location.path, "Storage metadata location path", 4_096)
        require(contentLength >= 0) { "Storage content length must not be negative." }
        require(lastModifiedTime == null || lastModifiedTime >= 0) {
            "Storage last-modified time must not be negative."
        }
        require(metadata.size <= MAX_METADATA_ENTRIES) { "Storage user metadata contains too many entries." }
        val copy = LinkedHashMap<String, String>(metadata.size)
        var totalLength = 0L
        metadata.forEach { (rawKey, rawValue) ->
            val key = requiredStorageMetadataText(rawKey, "Storage user metadata key", MAX_METADATA_KEY_LENGTH)
            val value = requiredStorageMetadataText(rawValue, "Storage user metadata value", MAX_METADATA_VALUE_LENGTH)
            totalLength = Math.addExact(totalLength, key.length.toLong() + value.length.toLong())
            require(totalLength <= MAX_METADATA_TOTAL_LENGTH) { "Storage user metadata is too large." }
            require(copy.put(key, value) == null) { "Storage user metadata keys must be unique." }
        }
        this.metadata = Collections.unmodifiableMap(copy)
    }

    private companion object {
        const val MAX_METADATA_ENTRIES = 128
        const val MAX_METADATA_KEY_LENGTH = 256
        const val MAX_METADATA_VALUE_LENGTH = 4_096
        const val MAX_METADATA_TOTAL_LENGTH = 65_536L
    }
}

private fun optionalStorageMetadataText(value: String?, label: String, maxLength: Int): String? =
    value?.let { requiredStorageMetadataText(it, label, maxLength) }

private fun requiredStorageMetadataText(value: String, label: String, maxLength: Int): String {
    require(value.isNotBlank()) { "$label must not be blank." }
    require(value.length <= maxLength) { "$label must not exceed $maxLength characters." }
    require(value.hasWellFormedStorageMetadataUtf16()) { "$label must contain well-formed Unicode text." }
    require(value.none(::isUnsafeStorageMetadataCharacter)) { "$label must not contain unsafe characters." }
    return value
}

private fun String.hasWellFormedStorageMetadataUtf16(): Boolean {
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

private fun isUnsafeStorageMetadataCharacter(character: Char): Boolean =
    Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
