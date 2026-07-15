package ai.icen.fw.retrieval.api

import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

internal object RetrievalContractLimits {
    const val MAX_ID_CODE_POINTS = 256
    const val MAX_QUERY_CODE_POINTS = 8_192
    const val MAX_PURPOSE_CODE_POINTS = 256
    const val MAX_AUTHORIZED_DOCUMENTS = 100_000
    const val MAX_CLAIMS = 128
    const val MAX_CLAIM_VALUES = 1_024
    const val MAX_CANDIDATES = 1_000
    const val MAX_CONTENT_CODE_POINTS = 1_000_000
}

internal fun requireRetrievalIdentifier(identifier: Identifier, message: String) {
    requireRetrievalText(identifier.value, RetrievalContractLimits.MAX_ID_CODE_POINTS, message)
}

internal fun requireRetrievalText(
    value: String,
    maximumCodePoints: Int,
    message: String,
) {
    require(value.isNotEmpty()) { message }
    require(maximumCodePoints > 0) { "Maximum code-point count must be positive." }
    require(value.codePointCount(0, value.length) <= maximumCodePoints) { message }

    var offset = 0
    var first = true
    var lastCodePoint = -1
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        require(codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) { message }
        require(!Character.isISOControl(codePoint)) { message }
        require(Character.getType(codePoint) != Character.FORMAT.toInt()) { message }
        require(!isUnicodeNonCharacter(codePoint)) { message }
        if (first) {
            require(!isRetrievalBoundaryWhitespace(codePoint)) { message }
            first = false
        }
        lastCodePoint = codePoint
        offset += Character.charCount(codePoint)
    }
    require(lastCodePoint >= 0 && !isRetrievalBoundaryWhitespace(lastCodePoint)) { message }
}

/**
 * Validates extracted document text without treating ordinary layout characters as identifiers.
 * Tabs and line endings are valid content; every other C0/C1 control, malformed surrogate and
 * Unicode non-character is rejected. Rendering and prompt boundaries must still treat the value
 * as untrusted data.
 */
internal fun requireRetrievalContent(
    value: String,
    maximumCodePoints: Int,
    message: String,
) {
    require(value.isNotEmpty()) { message }
    require(maximumCodePoints > 0) { "Maximum code-point count must be positive." }
    require(value.codePointCount(0, value.length) <= maximumCodePoints) { message }

    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        require(codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) { message }
        require(!Character.isISOControl(codePoint) || codePoint == '\t'.code || codePoint == '\n'.code ||
            codePoint == '\r'.code) { message }
        require(!isUnicodeNonCharacter(codePoint)) { message }
        offset += Character.charCount(codePoint)
    }
}

private fun isRetrievalBoundaryWhitespace(codePoint: Int): Boolean =
    Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

internal fun isUnicodeNonCharacter(codePoint: Int): Boolean =
    codePoint in 0xfdd0..0xfdef || (codePoint and 0xffff) == 0xfffe || (codePoint and 0xffff) == 0xffff

internal fun requireDigest(value: String, message: String) {
    require(value.length == 64 && value.all { character -> character in '0'..'9' || character in 'a'..'f' }) {
        message
    }
}

internal fun requireStableRetrievalCode(value: String, message: String) {
    require(value.length <= 128 && Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$").matches(value)) {
        message
    }
}

internal class RetrievalDigestWriter {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val numericBuffer = ByteArray(8)
    private var finished = false
    var encodedByteCount: Long = 0L
        private set

    fun text(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        integer(encoded.size)
        update(encoded)
    }

    fun optionalText(value: String?) {
        boolean(value != null)
        value?.let(::text)
    }

    fun integer(value: Int) {
        numericBuffer[0] = (value ushr 24).toByte()
        numericBuffer[1] = (value ushr 16).toByte()
        numericBuffer[2] = (value ushr 8).toByte()
        numericBuffer[3] = value.toByte()
        update(numericBuffer, 4)
    }

    fun long(value: Long) {
        for (index in 0 until 8) {
            numericBuffer[index] = (value ushr (56 - index * 8)).toByte()
        }
        update(numericBuffer, 8)
    }

    fun boolean(value: Boolean) {
        numericBuffer[0] = if (value) 1 else 0
        update(numericBuffer, 1)
    }

    fun finish(): String {
        check(!finished) { "Retrieval digest writer has already been finalized." }
        finished = true
        return digest.digest().toLowerHex()
    }

    private fun update(bytes: ByteArray, length: Int = bytes.size) {
        check(!finished) { "Retrieval digest writer has already been finalized." }
        require(length in 0..bytes.size) { "Digest update length is invalid." }
        digest.update(bytes, 0, length)
        encodedByteCount = Math.addExact(encodedByteCount, length.toLong())
    }
}

internal fun retrievalDigest(block: RetrievalDigestWriter.() -> Unit): String =
    RetrievalDigestWriter().apply(block).finish()

internal fun sha256Hex(payload: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload)
    return digest.toLowerHex()
}

private fun ByteArray.toLowerHex(): String {
    val hexadecimal = "0123456789abcdef"
    val result = CharArray(size * 2)
    forEachIndexed { index, value ->
        val unsigned = value.toInt() and 0xff
        result[index * 2] = hexadecimal[unsigned ushr 4]
        result[index * 2 + 1] = hexadecimal[unsigned and 0x0f]
    }
    return String(result)
}

internal fun <T> immutableRetrievalList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

internal fun <T> immutableRetrievalList(
    values: Collection<T>,
    maximumSize: Int,
    message: String,
): List<T> {
    require(maximumSize >= 0) { "Maximum collection size must not be negative." }
    require(values.size <= maximumSize) { message }
    return immutableRetrievalList(values)
}

internal fun <T> immutableRetrievalSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

internal fun <T> immutableRetrievalSet(
    values: Collection<T>,
    maximumSize: Int,
    message: String,
): Set<T> {
    require(maximumSize >= 0) { "Maximum collection size must not be negative." }
    require(values.size <= maximumSize) { message }
    return immutableRetrievalSet(values)
}

internal fun immutableStringMap(values: Map<String, String>): Map<String, String> =
    Collections.unmodifiableMap(LinkedHashMap(values))

internal fun immutableStringMap(
    values: Map<String, String>,
    maximumSize: Int,
    message: String,
): Map<String, String> {
    require(maximumSize >= 0) { "Maximum map size must not be negative." }
    require(values.size <= maximumSize) { message }
    return immutableStringMap(values)
}
