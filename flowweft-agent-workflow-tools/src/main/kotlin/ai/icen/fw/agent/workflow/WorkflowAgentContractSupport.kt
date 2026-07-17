package ai.icen.fw.agent.workflow

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

internal const val WORKFLOW_AGENT_MAX_ARGUMENT_BYTES: Int = 1 * 1024 * 1024
internal const val WORKFLOW_AGENT_MAX_RESULT_BYTES: Int = 64 * 1024
internal const val WORKFLOW_AGENT_MAX_TEXT_BYTES: Int = 8 * 1024
internal const val WORKFLOW_AGENT_MAX_ID_BYTES: Int = 512
internal const val WORKFLOW_AGENT_MAX_JSON_DEPTH: Int = 32
internal const val WORKFLOW_AGENT_MAX_JSON_NODES: Int = 8_192

internal fun workflowAgentSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun workflowAgentRequireSha256(value: String, label: String): String {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Workflow Agent $label digest is invalid."
    }
    return value
}

internal fun workflowAgentText(value: String, maximumBytes: Int, label: String): String {
    require(value.isNotBlank() && value == value.trim()) { "Workflow Agent $label is invalid." }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= maximumBytes) {
        "Workflow Agent $label is too large."
    }
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        require(!Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT.toInt()) {
            "Workflow Agent $label contains unsafe Unicode."
        }
        require(codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) {
            "Workflow Agent $label contains unsafe Unicode."
        }
        require(codePoint !in 0xFDD0..0xFDEF && (codePoint and 0xFFFF) != 0xFFFE &&
            (codePoint and 0xFFFF) != 0xFFFF
        ) { "Workflow Agent $label contains unsafe Unicode." }
        offset += Character.charCount(codePoint)
    }
    return value
}

internal fun workflowAgentId(value: String, label: String): String = workflowAgentText(
    value,
    WORKFLOW_AGENT_MAX_ID_BYTES,
    label,
)

internal fun workflowAgentCode(value: String, label: String): String {
    val checked = workflowAgentText(value, 128, label)
    require(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*").matches(checked)) {
        "Workflow Agent $label code is invalid."
    }
    return checked
}

internal class WorkflowAgentDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(domain)
    }

    fun add(value: String): WorkflowAgentDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): WorkflowAgentDigest = add(value.toString())

    fun add(value: Int): WorkflowAgentDigest = add(value.toString())

    fun add(value: Boolean): WorkflowAgentDigest = add(if (value) "1" else "0")

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

internal sealed class WorkflowAgentJsonValue

internal class WorkflowAgentJsonObject(values: Map<String, WorkflowAgentJsonValue>) : WorkflowAgentJsonValue() {
    val values: Map<String, WorkflowAgentJsonValue> = Collections.unmodifiableMap(LinkedHashMap(values))
}

internal class WorkflowAgentJsonArray(values: List<WorkflowAgentJsonValue>) : WorkflowAgentJsonValue() {
    val values: List<WorkflowAgentJsonValue> = Collections.unmodifiableList(ArrayList(values))
}

internal class WorkflowAgentJsonString(val value: String) : WorkflowAgentJsonValue()

internal class WorkflowAgentJsonNumber(val canonicalValue: String) : WorkflowAgentJsonValue()

internal class WorkflowAgentJsonBoolean(val value: Boolean) : WorkflowAgentJsonValue()

internal object WorkflowAgentJsonNull : WorkflowAgentJsonValue()

/**
 * Small dependency-free canonical JSON boundary. It rejects duplicate keys, non-canonical numbers,
 * whitespace variants and excessive nesting before arguments reach an application use case.
 */
internal object WorkflowAgentCanonicalJson {
    fun parseCanonicalObject(bytes: ByteArray): WorkflowAgentJsonObject {
        require(bytes.isNotEmpty() && bytes.size <= WORKFLOW_AGENT_MAX_ARGUMENT_BYTES) {
            "Workflow Agent JSON size is invalid."
        }
        val text = decodeUtf8(bytes)
        val parser = Parser(text)
        val value = parser.parse()
        val root = value as? WorkflowAgentJsonObject
            ?: throw IllegalArgumentException("Workflow Agent JSON root must be an object.")
        val canonical = encode(root)
        require(canonical.contentEquals(bytes)) { "Workflow Agent JSON must use the canonical encoding." }
        return root
    }

    fun requireCanonicalObject(bytes: ByteArray, maximumBytes: Int): ByteArray {
        require(bytes.size <= maximumBytes) { "Workflow Agent JSON result is too large." }
        val root = parseCanonicalObject(bytes)
        return encode(root)
    }

    fun encode(value: WorkflowAgentJsonValue): ByteArray = buildString { appendValue(value) }
        .toByteArray(StandardCharsets.UTF_8)

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("Workflow Agent JSON must be valid UTF-8.")
    }

    private fun StringBuilder.appendValue(value: WorkflowAgentJsonValue) {
        when (value) {
            is WorkflowAgentJsonObject -> {
                append('{')
                value.values.toSortedMap().entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendString(entry.key)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is WorkflowAgentJsonArray -> {
                append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            is WorkflowAgentJsonString -> appendString(value.value)
            is WorkflowAgentJsonNumber -> append(value.canonicalValue)
            is WorkflowAgentJsonBoolean -> append(if (value.value) "true" else "false")
            WorkflowAgentJsonNull -> append("null")
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private class Parser(private val input: String) {
        private var offset = 0
        private var nodes = 0

        fun parse(): WorkflowAgentJsonValue {
            val value = value(0)
            require(offset == input.length) { "Workflow Agent JSON contains trailing data." }
            return value
        }

        private fun value(depth: Int): WorkflowAgentJsonValue {
            require(depth <= WORKFLOW_AGENT_MAX_JSON_DEPTH) { "Workflow Agent JSON nesting is too deep." }
            nodes += 1
            require(nodes <= WORKFLOW_AGENT_MAX_JSON_NODES) { "Workflow Agent JSON contains too many values." }
            require(offset < input.length) { "Workflow Agent JSON ended unexpectedly." }
            return when (input[offset]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> WorkflowAgentJsonString(stringValue())
                't' -> literal("true", WorkflowAgentJsonBoolean(true))
                'f' -> literal("false", WorkflowAgentJsonBoolean(false))
                'n' -> literal("null", WorkflowAgentJsonNull)
                '-', in '0'..'9' -> numberValue()
                else -> throw IllegalArgumentException("Workflow Agent JSON token is invalid.")
            }
        }

        private fun objectValue(depth: Int): WorkflowAgentJsonObject {
            expect('{')
            val values = LinkedHashMap<String, WorkflowAgentJsonValue>()
            if (take('}')) return WorkflowAgentJsonObject(values)
            while (true) {
                require(peek() == '"') { "Workflow Agent JSON object key is invalid." }
                val key = stringValue()
                require(key.toByteArray(StandardCharsets.UTF_8).size <= WORKFLOW_AGENT_MAX_TEXT_BYTES) {
                    "Workflow Agent JSON object key is too large."
                }
                require(!values.containsKey(key)) { "Workflow Agent JSON object contains a duplicate key." }
                expect(':')
                values[key] = value(depth)
                if (take('}')) return WorkflowAgentJsonObject(values)
                expect(',')
            }
        }

        private fun arrayValue(depth: Int): WorkflowAgentJsonArray {
            expect('[')
            val values = ArrayList<WorkflowAgentJsonValue>()
            if (take(']')) return WorkflowAgentJsonArray(values)
            while (true) {
                values += value(depth)
                if (take(']')) return WorkflowAgentJsonArray(values)
                expect(',')
            }
        }

        private fun stringValue(): String {
            expect('"')
            val result = StringBuilder()
            while (offset < input.length) {
                val character = input[offset++]
                when {
                    character == '"' -> {
                        val text = result.toString()
                        require(text.toByteArray(StandardCharsets.UTF_8).size <= WORKFLOW_AGENT_MAX_TEXT_BYTES) {
                            "Workflow Agent JSON string is too large."
                        }
                        validateUnicode(text)
                        return text
                    }
                    character == '\\' -> appendEscape(result)
                    character.code < 0x20 -> throw IllegalArgumentException(
                        "Workflow Agent JSON string contains a control character.",
                    )
                    else -> result.append(character)
                }
            }
            throw IllegalArgumentException("Workflow Agent JSON string is unterminated.")
        }

        private fun appendEscape(result: StringBuilder) {
            require(offset < input.length) { "Workflow Agent JSON escape is incomplete." }
            when (val escaped = input[offset++]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> result.append(readUnicodeEscape())
                else -> throw IllegalArgumentException("Workflow Agent JSON escape is invalid.")
            }
        }

        private fun readUnicodeEscape(): Char {
            require(offset + 4 <= input.length) { "Workflow Agent JSON Unicode escape is incomplete." }
            val digits = input.substring(offset, offset + 4)
            require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "Workflow Agent JSON Unicode escape is invalid."
            }
            offset += 4
            return digits.toInt(16).toChar()
        }

        private fun numberValue(): WorkflowAgentJsonNumber {
            val start = offset
            take('-')
            if (take('0')) {
                require(offset >= input.length || input[offset] !in '0'..'9') {
                    "Workflow Agent JSON number contains a leading zero."
                }
            } else {
                digits(requireOne = true)
            }
            if (take('.')) digits(requireOne = true)
            if (peek() == 'e' || peek() == 'E') {
                offset += 1
                if (peek() == '+' || peek() == '-') offset += 1
                digits(requireOne = true)
            }
            val token = input.substring(start, offset)
            val decimal = try {
                BigDecimal(token)
            } catch (_: NumberFormatException) {
                throw IllegalArgumentException("Workflow Agent JSON number is invalid.")
            }
            val canonical = if (decimal.compareTo(BigDecimal.ZERO) == 0) {
                "0"
            } else {
                decimal.stripTrailingZeros().toPlainString()
            }
            require(token == canonical) { "Workflow Agent JSON number must use canonical decimal form." }
            return WorkflowAgentJsonNumber(canonical)
        }

        private fun digits(requireOne: Boolean) {
            val start = offset
            while (offset < input.length && input[offset] in '0'..'9') offset += 1
            require(!requireOne || offset > start) { "Workflow Agent JSON number is incomplete." }
        }

        private fun <T : WorkflowAgentJsonValue> literal(token: String, value: T): T {
            require(input.regionMatches(offset, token, 0, token.length)) {
                "Workflow Agent JSON literal is invalid."
            }
            offset += token.length
            return value
        }

        private fun expect(character: Char) {
            require(take(character)) { "Workflow Agent JSON structure is invalid." }
        }

        private fun take(character: Char): Boolean = if (offset < input.length && input[offset] == character) {
            offset += 1
            true
        } else {
            false
        }

        private fun peek(): Char? = if (offset < input.length) input[offset] else null

        private fun validateUnicode(value: String) {
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (Character.isHighSurrogate(character)) {
                    require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                        "Workflow Agent JSON contains an unpaired surrogate."
                    }
                    index += 2
                } else {
                    require(!Character.isLowSurrogate(character)) {
                        "Workflow Agent JSON contains an unpaired surrogate."
                    }
                    index += 1
                }
            }
        }
    }
}

internal fun WorkflowAgentJsonObject.requireExactKeys(expected: Set<String>) {
    require(values.keys == expected) { "Workflow Agent command fields do not match the fixed schema." }
}

internal fun WorkflowAgentJsonObject.string(name: String): String =
    (values[name] as? WorkflowAgentJsonString)?.value
        ?: throw IllegalArgumentException("Workflow Agent command field is not a string.")

internal fun WorkflowAgentJsonObject.long(name: String): Long {
    val value = (values[name] as? WorkflowAgentJsonNumber)?.canonicalValue
        ?: throw IllegalArgumentException("Workflow Agent command field is not a number.")
    require(!value.contains('.')) { "Workflow Agent command version field must be an integer." }
    return try {
        value.toLong()
    } catch (_: NumberFormatException) {
        throw IllegalArgumentException("Workflow Agent command version field is out of range.")
    }
}

internal fun WorkflowAgentJsonObject.objectValue(name: String): WorkflowAgentJsonObject =
    values[name] as? WorkflowAgentJsonObject
        ?: throw IllegalArgumentException("Workflow Agent command payload must be an object.")
