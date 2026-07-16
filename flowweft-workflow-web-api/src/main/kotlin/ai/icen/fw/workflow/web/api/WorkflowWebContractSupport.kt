package ai.icen.fw.workflow.web.api

import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collections

internal fun requiredText(
    value: String,
    field: String,
    maximumUtf8Bytes: Int = 512,
    multiline: Boolean = false,
): String = value.also {
    require(maximumUtf8Bytes > 0) { "$field maximum size must be positive." }
    require(it.isNotBlank()) { "$field must not be blank." }
    require(it.toByteArray(StandardCharsets.UTF_8).size <= maximumUtf8Bytes) {
        "$field exceeds its UTF-8 size limit."
    }
    requireSafeText(it, field, multiline)
}

internal fun optionalText(
    value: String?,
    field: String,
    maximumUtf8Bytes: Int = 512,
    multiline: Boolean = false,
): String? = value?.let { requiredText(it, field, maximumUtf8Bytes, multiline) }

internal fun requiredCode(value: String, field: String, maximumLength: Int = 96): String =
    requiredText(value, field, maximumLength).also {
        require(CODE_PATTERN.matches(it)) { "$field must use the stable public code alphabet." }
    }

internal fun sha256(value: String, field: String): String = value.also {
    require(SHA256_PATTERN.matches(it)) { "$field must be a lowercase SHA-256 digest." }
}

internal fun <T> immutableList(
    values: Collection<T>,
    field: String,
    maximumSize: Int,
): List<T> {
    require(maximumSize >= 0) { "$field maximum size must not be negative." }
    require(values.size <= maximumSize) { "$field exceeds its item limit." }
    return Collections.unmodifiableList(ArrayList(values))
}

private fun requireSafeText(value: String, field: String, multiline: Boolean) {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        require(!Character.isSurrogate(character) ||
            (Character.isHighSurrogate(character) && index + 1 < value.length &&
                Character.isLowSurrogate(value[index + 1]))) {
            "$field contains an invalid Unicode sequence."
        }
        if (Character.isHighSurrogate(character)) {
            index += 2
            continue
        }
        require(Character.getType(character) != Character.FORMAT.toInt()) {
            "$field contains a prohibited Unicode format character."
        }
        val allowedMultilineControl = multiline && (character == '\n' || character == '\t')
        require(allowedMultilineControl || !Character.isISOControl(character)) {
            "$field contains a prohibited control character."
        }
        index += 1
    }
}

private val CODE_PATTERN = Regex("[A-Z][A-Z0-9_.:-]{0,95}")
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
