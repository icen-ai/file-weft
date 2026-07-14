package ai.icen.fw.metadata.api

/** Stable, value-free validation categories suitable for APIs and logs. */
enum class MetadataValidationIssueCode(
    val defaultMessage: String,
) {
    INVALID_INPUT("Metadata input is invalid."),
    TOO_MANY_FIELDS("Metadata input contains too many fields."),
    INPUT_TOO_LARGE("Metadata input exceeds the supported size."),
    INVALID_FIELD_NAME("Metadata field name is invalid."),
    MISSING_REQUIRED_FIELD("A required metadata field is missing."),
    UNKNOWN_FIELD("Metadata field is not declared by the schema."),
    INVALID_TYPE("Metadata value does not match the declared type."),
    INVALID_ENUM_VALUE("Metadata value is not allowed by the schema."),
    VALUE_TOO_LONG("Metadata value exceeds the configured limit."),
    FORMAT_MISMATCH("Metadata value does not match the configured format."),
}

class MetadataValidationIssue @JvmOverloads constructor(
    val code: MetadataValidationIssueCode,
    val fieldName: String? = null,
) {
    val message: String = code.defaultMessage

    init {
        if (fieldName != null) {
            requireContractName(
                fieldName,
                MetadataContractLimits.MAX_FIELD_NAME_CODE_POINTS,
                "Metadata validation field name is invalid.",
            )
        }
    }
}

class MetadataValidationResult @JvmOverloads constructor(
    issues: List<MetadataValidationIssue> = emptyList(),
) {
    val issues: List<MetadataValidationIssue>
    val valid: Boolean
        get() = issues.isEmpty()

    init {
        val issuesSnapshot = immutableList(issues)
        require(issuesSnapshot.size <= MetadataContractLimits.MAX_VALIDATION_ISSUES) {
            "Metadata validation result contains too many issues."
        }
        this.issues = issuesSnapshot
    }

    fun isValid(): Boolean = valid

    companion object {
        @JvmStatic
        fun success(): MetadataValidationResult = MetadataValidationResult()
    }
}
