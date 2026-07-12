package ai.icen.fw.spi.storage

data class MultipartPart(
    val partNumber: Int,
    val eTag: String,
) {
    init {
        require(partNumber > 0) { "Part number must be positive." }
        require(eTag.isNotBlank()) { "Part eTag must not be blank." }
    }
}
