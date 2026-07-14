package ai.icen.fw.metadata.api

/**
 * Definition of one metadata field.
 *
 * [maxLength] is interpreted by the runtime after canonicalization. For
 * [MetadataFieldType.STRING_LIST], it applies to every decoded list item, not
 * to the serialized JSON array as a whole.
 *
 * [format] uses the RE2 regular-expression dialect so runtime matching remains
 * linear in the input length. Backreferences, look-around and other
 * backtracking-only Java regular-expression constructs are not supported.
 */
class MetadataField @JvmOverloads constructor(
    val name: String,
    val type: MetadataFieldType,
    val required: Boolean = false,
    allowedValues: List<String> = emptyList(),
    val maxLength: Int? = null,
    val format: String? = null,
) {
    val allowedValues: List<String>

    init {
        val allowedValuesSnapshot = immutableList(allowedValues)
        requireContractName(
            name,
            MetadataContractLimits.MAX_FIELD_NAME_CODE_POINTS,
            "Metadata field name is invalid.",
        )
        require(maxLength == null || maxLength in 1..MetadataContractLimits.MAX_VALUE_UTF16_LENGTH) {
            "Metadata field maximum length is invalid."
        }
        require(format == null || format.isNotBlank()) { "Metadata field format is invalid." }
        require(format == null || format.length <= MetadataContractLimits.MAX_FORMAT_UTF16_LENGTH) {
            "Metadata field format is invalid."
        }
        require(format == null || format.none { Character.isISOControl(it) }) {
            "Metadata field format is invalid."
        }
        require(allowedValuesSnapshot.size <= MetadataContractLimits.MAX_ENUM_VALUES) {
            "Metadata field allowed values exceed the supported limit."
        }
        require(allowedValuesSnapshot.none { it.length > MetadataContractLimits.MAX_VALUE_UTF16_LENGTH }) {
            "Metadata field allowed value is too long."
        }
        require(allowedValuesSnapshot.none { it.isBlank() }) { "Metadata field allowed value is invalid." }
        require(allowedValuesSnapshot.toSet().size == allowedValuesSnapshot.size) {
            "Metadata field allowed values must be unique."
        }
        require(type == MetadataFieldType.ENUM || allowedValuesSnapshot.isEmpty()) {
            "Allowed values are only valid for ENUM fields."
        }
        require(type != MetadataFieldType.ENUM || allowedValuesSnapshot.isNotEmpty()) {
            "ENUM fields require at least one allowed value."
        }
        require(maxLength == null || allowedValuesSnapshot.all { it.length <= maxLength }) {
            "Metadata field allowed value exceeds the configured maximum length."
        }

        this.allowedValues = allowedValuesSnapshot
    }
}
