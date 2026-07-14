package ai.icen.fw.metadata.api

/** One transport-neutral metadata entry. */
class MetadataValue(
    val fieldName: String,
    val value: String,
) {
    init {
        requireContractName(
            fieldName,
            MetadataContractLimits.MAX_FIELD_NAME_CODE_POINTS,
            "Metadata value field name is invalid.",
        )
        require(value.length <= MetadataContractLimits.MAX_VALUE_UTF16_LENGTH) {
            "Metadata value exceeds the supported limit."
        }
    }
}
