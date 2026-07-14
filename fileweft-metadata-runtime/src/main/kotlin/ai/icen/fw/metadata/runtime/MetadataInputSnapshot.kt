package ai.icen.fw.metadata.runtime

import ai.icen.fw.metadata.api.MetadataValidationIssue
import ai.icen.fw.metadata.api.MetadataValidationIssueCode
import java.util.LinkedHashMap

internal class MetadataInputSnapshot(
    val values: Map<String, String>,
    val issues: List<MetadataValidationIssue>,
    val usable: Boolean,
)

/** Copies caller-owned maps once and bounds them before any schema work. */
internal object MetadataInputSnapshots {
    fun capture(metadata: Map<String, String>): MetadataInputSnapshot {
        val declaredSize = try {
            metadata.size
        } catch (_: RuntimeException) {
            return invalidInput()
        }
        if (declaredSize > MetadataRuntimeLimits.MAX_FIELDS) {
            return fatal(MetadataValidationIssueCode.TOO_MANY_FIELDS)
        }

        val values = LinkedHashMap<String, String>(declaredSize)
        val issues = ArrayList<MetadataValidationIssue>()
        var totalLength = 0L
        var observedEntries = 0

        try {
            for (entry in metadata.entries) {
                observedEntries++
                if (observedEntries > MetadataRuntimeLimits.MAX_FIELDS) {
                    return fatal(MetadataValidationIssueCode.TOO_MANY_FIELDS)
                }
                val keyCandidate: Any? = entry.key
                val valueCandidate: Any? = entry.value
                if (keyCandidate !is String || valueCandidate !is String) {
                    return invalidInput()
                }
                if (!isSafeFieldName(keyCandidate)) {
                    addIssue(issues, MetadataValidationIssue(MetadataValidationIssueCode.INVALID_FIELD_NAME))
                    continue
                }
                if (valueCandidate.length > MetadataRuntimeLimits.MAX_VALUE_UTF16_LENGTH) {
                    return fatal(MetadataValidationIssueCode.INPUT_TOO_LARGE, keyCandidate)
                }

                totalLength += keyCandidate.length.toLong() + valueCandidate.length.toLong()
                if (totalLength > MetadataRuntimeLimits.MAX_TOTAL_INPUT_UTF16_LENGTH) {
                    return fatal(MetadataValidationIssueCode.INPUT_TOO_LARGE, keyCandidate)
                }
                values[keyCandidate] = valueCandidate
            }
        } catch (_: RuntimeException) {
            return invalidInput()
        }

        return MetadataInputSnapshot(values, issues, true)
    }

    private fun isSafeFieldName(value: String): Boolean {
        if (value.isBlank() || value != value.trim()) {
            return false
        }
        if (value.codePointCount(0, value.length) > MetadataRuntimeLimits.MAX_FIELD_NAME_CODE_POINTS) {
            return false
        }

        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (
                Character.isISOControl(codePoint) ||
                Character.getType(codePoint) == Character.FORMAT.toInt() ||
                codePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
            ) {
                return false
            }
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private fun invalidInput(): MetadataInputSnapshot =
        fatal(MetadataValidationIssueCode.INVALID_INPUT)

    private fun fatal(
        code: MetadataValidationIssueCode,
        fieldName: String? = null,
    ): MetadataInputSnapshot = MetadataInputSnapshot(
        emptyMap(),
        listOf(MetadataValidationIssue(code, fieldName)),
        false,
    )

    private fun addIssue(
        issues: MutableList<MetadataValidationIssue>,
        issue: MetadataValidationIssue,
    ) {
        if (issues.size < MetadataRuntimeLimits.MAX_ISSUES) {
            issues += issue
        }
    }
}
