package ai.icen.fw.spi.storage

/**
 * Optional range capability that binds every read to an object revision.
 *
 * Implementations must translate [StorageRangeRequest.expectedRevision] to a
 * provider-side condition (for example a version id or If-Match) and reject a
 * full-body 200 response, a mismatched Content-Range, or a changed revision.
 * The location is opaque authority loaded from tenant-authorized server state;
 * callers must never construct it from an untrusted path parameter.
 */
interface ConditionalRangedStorageAdapter {
    fun downloadRange(request: StorageRangeRequest): StorageDownload
}

class StorageRangeRequest(
    val location: StorageObjectLocation,
    val offset: Long,
    val length: Long,
    expectedRevision: String,
) {
    val expectedRevision: String = expectedRevision.also { value ->
        require(value.isNotBlank()) { "Expected storage revision must not be blank." }
        require(value.length <= MAX_REVISION_LENGTH) {
            "Expected storage revision must not exceed $MAX_REVISION_LENGTH characters."
        }
        require(value.hasWellFormedRevisionUtf16()) {
            "Expected storage revision must contain well-formed Unicode text."
        }
        require(value.none { character ->
            Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
        }) { "Expected storage revision must not contain unsafe characters." }
    }

    init {
        require(offset >= 0) { "Storage range offset must not be negative." }
        require(length > 0) { "Storage range length must be positive." }
        Math.addExact(offset, length - 1)
    }

    private companion object {
        const val MAX_REVISION_LENGTH = 2_048
    }
}

private fun String.hasWellFormedRevisionUtf16(): Boolean {
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
