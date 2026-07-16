package ai.icen.fw.workflow.templates

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

internal object ReferenceTemplateContractSupport {
    const val MAX_ID_BYTES = 256
    const val MAX_VERSION_BYTES = 128
    const val MAX_BINDINGS = 128
    const val MAX_SIMULATION_STEPS = 1_024
    const val MAX_CYCLE_ITERATIONS = 10_000

    fun text(value: String, maximumBytes: Int, message: String): String {
        require(value.isNotEmpty() && value.toByteArray(StandardCharsets.UTF_8).size <= maximumBytes) { message }
        var offset = 0
        var firstCodePoint = -1
        var lastCodePoint = -1
        while (offset < value.length) {
            val first = value[offset]
            val codePoint = if (first in '\uD800'..'\uDBFF') {
                require(offset + 1 < value.length && value[offset + 1] in '\uDC00'..'\uDFFF') { message }
                val second = value[offset + 1]
                offset += 2
                0x10000 + ((first.code - 0xD800) shl 10) + (second.code - 0xDC00)
            } else {
                require(first !in '\uDC00'..'\uDFFF') { message }
                offset += 1
                first.code
            }
            require(!isRejectedControlOrFormat(codePoint) && !isUnicodeNoncharacter(codePoint)) { message }
            if (firstCodePoint < 0) firstCodePoint = codePoint
            lastCodePoint = codePoint
        }
        require(!isBoundaryWhitespace(firstCodePoint) && !isBoundaryWhitespace(lastCodePoint)) { message }
        return value
    }

    fun code(value: String, message: String): String {
        text(value, MAX_ID_BYTES, message)
        require(isAsciiLetterOrDigit(value.first())) { message }
        require(value.all { character ->
            isAsciiLetterOrDigit(character) || character == '.' || character == '_' ||
                character == ':' || character == '/' || character == '-'
        }) { message }
        return value
    }

    fun sha256(value: String, message: String): String {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { message }
        return value
    }

    fun <T> immutableList(values: Collection<T>, maximum: Int, message: String): List<T> {
        require(values.size <= maximum && values.none { it == null }) { message }
        return Collections.unmodifiableList(ArrayList(values))
    }

    fun <K, V> immutableMap(values: Map<K, V>, maximum: Int, message: String): Map<K, V> {
        require(values.size <= maximum && values.keys.none { it == null } && values.values.none { it == null }) {
            message
        }
        return Collections.unmodifiableMap(LinkedHashMap(values))
    }

    fun digest(domain: String, vararg values: String): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        add(messageDigest, domain)
        values.forEach { add(messageDigest, it) }
        return lowerHex(messageDigest.digest())
    }

    fun digest(domain: String, values: Collection<String>): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        add(messageDigest, domain)
        values.forEach { add(messageDigest, it) }
        return lowerHex(messageDigest.digest())
    }

    private fun add(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun lowerHex(bytes: ByteArray): String {
        val alphabet = "0123456789abcdef"
        val result = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            result[index * 2] = alphabet[value ushr 4]
            result[index * 2 + 1] = alphabet[value and 0x0f]
        }
        return String(result)
    }

    private fun isAsciiLetterOrDigit(character: Char): Boolean =
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9'

    private fun isBoundaryWhitespace(codePoint: Int): Boolean =
        codePoint in 0x0009..0x000D || codePoint == 0x0020 || codePoint == 0x0085 ||
            codePoint == 0x00A0 || codePoint == 0x1680 || codePoint in 0x2000..0x200A ||
            codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F ||
            codePoint == 0x205F || codePoint == 0x3000

    private fun isRejectedControlOrFormat(codePoint: Int): Boolean =
        codePoint in 0x0000..0x001F || codePoint in 0x007F..0x009F ||
            codePoint == 0x00AD || codePoint in 0x0600..0x0605 || codePoint == 0x061C ||
            codePoint == 0x06DD || codePoint == 0x070F || codePoint in 0x0890..0x0891 ||
            codePoint == 0x08E2 || codePoint == 0x180E || codePoint in 0x200B..0x200F ||
            codePoint in 0x2028..0x202E || codePoint in 0x2060..0x206F || codePoint == 0xFEFF ||
            codePoint in 0xFFF9..0xFFFB || codePoint == 0x110BD || codePoint == 0x110CD ||
            codePoint in 0x13430..0x1345F || codePoint in 0x1BCA0..0x1BCAF ||
            codePoint in 0x1D173..0x1D17A || codePoint in 0xE0000..0xE007F

    private fun isUnicodeNoncharacter(codePoint: Int): Boolean =
        codePoint in 0xFDD0..0xFDEF || codePoint and 0xFFFE == 0xFFFE
}
