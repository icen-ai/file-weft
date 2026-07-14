package ai.icen.fw.metadata.runtime

import ai.icen.fw.metadata.api.MetadataValidationIssueCode

internal class StringListDecodingException(
    val issueCode: MetadataValidationIssueCode,
) : IllegalArgumentException("Metadata string list is invalid.")

/** Minimal strict JSON codec: only an array whose every element is a string. */
internal object StrictJsonStringListCodec {
    fun decode(source: String): List<String> = Parser(source).parse()

    fun encode(values: List<String>): String {
        val result = StringBuilder()
        result.append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) {
                result.append(',')
            }
            appendJsonString(result, value)
        }
        result.append(']')
        return result.toString()
    }

    private fun appendJsonString(target: StringBuilder, value: String) {
        target.append('"')
        value.forEach { character ->
            when (character) {
                '"' -> target.append("\\\"")
                '\\' -> target.append("\\\\")
                '\b' -> target.append("\\b")
                '\u000C' -> target.append("\\f")
                '\n' -> target.append("\\n")
                '\r' -> target.append("\\r")
                '\t' -> target.append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        target.append("\\u00")
                        target.append(HEX[(character.code ushr 4) and 0x0F])
                        target.append(HEX[character.code and 0x0F])
                    } else {
                        target.append(character)
                    }
                }
            }
        }
        target.append('"')
    }

    private class Parser(
        private val source: String,
    ) {
        private var offset = 0

        fun parse(): List<String> {
            skipWhitespace()
            requireCharacter('[')
            skipWhitespace()
            if (consume(']')) {
                skipWhitespace()
                requireEnd()
                return emptyList()
            }

            val values = ArrayList<String>()
            while (true) {
                if (values.size >= MetadataRuntimeLimits.MAX_STRING_LIST_ITEMS) {
                    fail(MetadataValidationIssueCode.VALUE_TOO_LONG)
                }
                values += parseString()
                skipWhitespace()
                if (consume(']')) {
                    skipWhitespace()
                    requireEnd()
                    return values
                }
                requireCharacter(',')
                skipWhitespace()
            }
        }

        private fun parseString(): String {
            requireCharacter('"')
            val value = StringBuilder()
            while (offset < source.length) {
                val character = source[offset++]
                when {
                    character == '"' -> return value.toString()
                    character == '\\' -> appendEscape(value)
                    character.code < 0x20 -> fail(MetadataValidationIssueCode.INVALID_TYPE)
                    Character.isHighSurrogate(character) -> {
                        if (offset >= source.length || !Character.isLowSurrogate(source[offset])) {
                            fail(MetadataValidationIssueCode.INVALID_TYPE)
                        }
                        value.append(character)
                        value.append(source[offset++])
                    }
                    Character.isLowSurrogate(character) ->
                        fail(MetadataValidationIssueCode.INVALID_TYPE)
                    else -> value.append(character)
                }
                if (value.length > MetadataRuntimeLimits.MAX_STRING_LIST_ITEM_UTF16_LENGTH) {
                    fail(MetadataValidationIssueCode.VALUE_TOO_LONG)
                }
            }
            fail(MetadataValidationIssueCode.INVALID_TYPE)
        }

        private fun appendEscape(target: StringBuilder) {
            if (offset >= source.length) {
                fail(MetadataValidationIssueCode.INVALID_TYPE)
            }
            when (source[offset++]) {
                '"' -> target.append('"')
                '\\' -> target.append('\\')
                '/' -> target.append('/')
                'b' -> target.append('\b')
                'f' -> target.append('\u000C')
                'n' -> target.append('\n')
                'r' -> target.append('\r')
                't' -> target.append('\t')
                'u' -> appendUnicodeEscape(target)
                else -> fail(MetadataValidationIssueCode.INVALID_TYPE)
            }
        }

        private fun appendUnicodeEscape(target: StringBuilder) {
            val first = readHexCharacter()
            when {
                Character.isHighSurrogate(first) -> {
                    if (
                        offset + 2 > source.length ||
                        source[offset] != '\\' ||
                        source[offset + 1] != 'u'
                    ) {
                        fail(MetadataValidationIssueCode.INVALID_TYPE)
                    }
                    offset += 2
                    val second = readHexCharacter()
                    if (!Character.isLowSurrogate(second)) {
                        fail(MetadataValidationIssueCode.INVALID_TYPE)
                    }
                    target.append(first)
                    target.append(second)
                }
                Character.isLowSurrogate(first) ->
                    fail(MetadataValidationIssueCode.INVALID_TYPE)
                else -> target.append(first)
            }
        }

        private fun readHexCharacter(): Char {
            if (offset + 4 > source.length) {
                fail(MetadataValidationIssueCode.INVALID_TYPE)
            }
            var value = 0
            repeat(4) {
                val digit = Character.digit(source[offset++], 16)
                if (digit < 0) {
                    fail(MetadataValidationIssueCode.INVALID_TYPE)
                }
                value = (value shl 4) or digit
            }
            return value.toChar()
        }

        private fun skipWhitespace() {
            while (offset < source.length) {
                when (source[offset]) {
                    ' ', '\t', '\n', '\r' -> offset++
                    else -> return
                }
            }
        }

        private fun consume(expected: Char): Boolean {
            if (offset < source.length && source[offset] == expected) {
                offset++
                return true
            }
            return false
        }

        private fun requireCharacter(expected: Char) {
            if (!consume(expected)) {
                fail(MetadataValidationIssueCode.INVALID_TYPE)
            }
        }

        private fun requireEnd() {
            if (offset != source.length) {
                fail(MetadataValidationIssueCode.INVALID_TYPE)
            }
        }
    }

    private fun fail(code: MetadataValidationIssueCode): Nothing =
        throw StringListDecodingException(code)

    private const val HEX = "0123456789abcdef"
}
