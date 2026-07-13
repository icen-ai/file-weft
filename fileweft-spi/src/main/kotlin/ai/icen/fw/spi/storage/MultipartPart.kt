package ai.icen.fw.spi.storage

/**
 * Authoritative acknowledgement for the current bytes of one multipart part.
 * A completion implementation must reject an acknowledgement superseded by a
 * later upload of the same [partNumber]; it must never silently assemble the
 * newer bytes for an older eTag. When that rejection is known not to have
 * published an object, it must use [MultipartCompletionRejectedException].
 */
data class MultipartPart(
    val partNumber: Int,
    val eTag: String,
) {
    init {
        require(partNumber > 0) { "Part number must be positive." }
        require(eTag.isNotBlank()) { "Part eTag must not be blank." }
    }
}
