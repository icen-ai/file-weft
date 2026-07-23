package ai.icen.fw.metadata.api

/**
 * One transport-neutral metadata entry.
 *
 * Reserved contract not used by the runtime; scheduled for removal in a
 * future major release. Kept only for binary and source compatibility with
 * consumers of the already published metadata API module.
 */
@Deprecated("Reserved contract not used by the runtime; scheduled for removal in a future major release.")
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
