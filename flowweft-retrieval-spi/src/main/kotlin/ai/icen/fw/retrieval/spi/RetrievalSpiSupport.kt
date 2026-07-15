package ai.icen.fw.retrieval.spi

import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet

internal object RetrievalSpiLimits {
    const val MAX_ID_CODE_POINTS = 256
    const val MAX_TEXT_CODE_POINTS = 1_000_000
    const val MAX_SEGMENTS = 10_000
    const val MAX_CHUNKS = 100_000
    const val MAX_EMBEDDING_BATCH = 2_048
    const val MAX_VECTOR_DIMENSIONS = 65_536
    const val MAX_RERANK_ITEMS = 1_000
}

internal fun requireSpiIdentifier(identifier: Identifier, message: String) {
    requireSpiText(identifier.value, RetrievalSpiLimits.MAX_ID_CODE_POINTS, message)
}

internal fun requireSpiText(value: String, maximumCodePoints: Int, message: String): String {
    require(value.isNotEmpty() && maximumCodePoints > 0) { message }
    require(value.codePointCount(0, value.length) <= maximumCodePoints) { message }
    var offset = 0
    var first = true
    var last = -1
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        require(codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) { message }
        require(!Character.isISOControl(codePoint)) { message }
        require(Character.getType(codePoint) != Character.FORMAT.toInt()) { message }
        require(!isNonCharacter(codePoint)) { message }
        if (first) {
            require(!isBoundaryWhitespace(codePoint)) { message }
            first = false
        }
        last = codePoint
        offset += Character.charCount(codePoint)
    }
    require(last >= 0 && !isBoundaryWhitespace(last)) { message }
    return value
}

internal fun requireSpiContent(value: String, maximumCodePoints: Int, message: String): String {
    require(value.isNotEmpty() && maximumCodePoints > 0) { message }
    require(value.codePointCount(0, value.length) <= maximumCodePoints) { message }
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        require(codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) { message }
        require(
            !Character.isISOControl(codePoint) ||
                codePoint == '\t'.code || codePoint == '\n'.code || codePoint == '\r'.code,
        ) { message }
        require(!isNonCharacter(codePoint)) { message }
        offset += Character.charCount(codePoint)
    }
    return value
}

internal fun requireSpiSha256(value: String, message: String): String {
    require(value.length == 64 && value.all { character -> character in '0'..'9' || character in 'a'..'f' }) {
        message
    }
    return value
}

internal fun requireSpiMediaType(value: String): String {
    requireSpiText(value, RetrievalSpiLimits.MAX_ID_CODE_POINTS, "Media type is invalid.")
    val token = "[A-Za-z0-9][A-Za-z0-9!#$&^_.+\\-]{0,126}"
    require(Regex("^$token/$token\$").matches(value)) {
        "Media type must be an RFC token type/subtype without parameters."
    }
    return value
}

internal fun requireSpiTime(value: Long, message: String): Long {
    require(value >= 0L) { message }
    return value
}

internal fun <T> immutableSpiList(values: Collection<T>, maximumSize: Int, message: String): List<T> {
    require(maximumSize >= 0) { message }
    val snapshot = ArrayList(values)
    require(snapshot.size <= maximumSize && snapshot.none { value -> value == null }) { message }
    return Collections.unmodifiableList(snapshot)
}

internal fun immutableSpiStrings(values: Collection<String>, maximumSize: Int, message: String): List<String> {
    val snapshot = immutableSpiList(values, maximumSize, message)
    snapshot.forEach { value ->
        requireSpiText(value, RetrievalSpiLimits.MAX_ID_CODE_POINTS, message)
    }
    require(LinkedHashSet(snapshot).size == snapshot.size) { message }
    return snapshot
}

internal fun sha256Spi(value: String): String = sha256Spi(value.toByteArray(StandardCharsets.UTF_8))

internal fun sha256Spi(value: ByteArray): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value)
    val hex = "0123456789abcdef"
    val result = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
        val unsigned = byte.toInt() and 0xff
        result[index * 2] = hex[unsigned ushr 4]
        result[index * 2 + 1] = hex[unsigned and 0x0f]
    }
    return String(result)
}

internal class RetrievalSpiDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val number = ByteArray(8)
    private var finished = false

    init {
        text(domain)
    }

    fun text(value: String): RetrievalSpiDigest = apply {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        integer(encoded.size)
        update(encoded)
    }

    fun optionalText(value: String?): RetrievalSpiDigest = apply {
        boolean(value != null)
        if (value != null) text(value)
    }

    fun integer(value: Int): RetrievalSpiDigest = apply {
        number[0] = (value ushr 24).toByte()
        number[1] = (value ushr 16).toByte()
        number[2] = (value ushr 8).toByte()
        number[3] = value.toByte()
        update(number, 4)
    }

    fun long(value: Long): RetrievalSpiDigest = apply {
        for (index in 0 until 8) {
            number[index] = (value ushr (56 - index * 8)).toByte()
        }
        update(number)
    }

    fun boolean(value: Boolean): RetrievalSpiDigest = apply {
        number[0] = if (value) 1 else 0
        update(number, 1)
    }

    fun floating(value: Double): RetrievalSpiDigest = long(java.lang.Double.doubleToLongBits(value))

    fun finish(): String {
        check(!finished) { "Retrieval SPI digest has already been finalized." }
        finished = true
        return digest.digest().let { bytes ->
            val hex = "0123456789abcdef"
            val result = CharArray(bytes.size * 2)
            bytes.forEachIndexed { index, byte ->
                val unsigned = byte.toInt() and 0xff
                result[index * 2] = hex[unsigned ushr 4]
                result[index * 2 + 1] = hex[unsigned and 0x0f]
            }
            String(result)
        }
    }

    private fun update(value: ByteArray, length: Int = value.size) {
        check(!finished) { "Retrieval SPI digest has already been finalized." }
        digest.update(value, 0, length)
    }
}

internal fun codePointSlice(value: String, startCodePoint: Int, endCodePoint: Int): String {
    val start = value.offsetByCodePoints(0, startCodePoint)
    val end = value.offsetByCodePoints(0, endCodePoint)
    return value.substring(start, end)
}

private fun isBoundaryWhitespace(codePoint: Int): Boolean =
    Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

private fun isNonCharacter(codePoint: Int): Boolean =
    codePoint in 0xfdd0..0xfdef || (codePoint and 0xffff) == 0xfffe || (codePoint and 0xffff) == 0xffff
