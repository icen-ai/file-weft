package ai.icen.fw.metadata.api

import java.util.ArrayList
import java.util.Collections

internal object MetadataContractLimits {
    const val MAX_FIELD_NAME_CODE_POINTS = 128
    const val MAX_SCHEMA_ID_CODE_POINTS = 128
    const val MAX_SCHEMA_VERSION_CODE_POINTS = 64
    const val MAX_CONTEXT_VALUE_CODE_POINTS = 256
    const val MAX_FIELDS_PER_SCHEMA = 128
    const val MAX_ENUM_VALUES = 256
    const val MAX_VALUE_UTF16_LENGTH = 16_384
    const val MAX_FORMAT_UTF16_LENGTH = 256
    const val MAX_VALIDATION_ISSUES = 128
}

internal fun requireContractName(
    value: String,
    maximumCodePoints: Int,
    message: String,
) {
    require(value.isNotBlank()) { message }
    require(value == value.trim()) { message }
    require(value.codePointCount(0, value.length) <= maximumCodePoints) { message }

    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        require(!Character.isISOControl(codePoint)) { message }
        require(Character.getType(codePoint) != Character.FORMAT.toInt()) { message }
        require(codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) { message }
        offset += Character.charCount(codePoint)
    }
}

internal fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
