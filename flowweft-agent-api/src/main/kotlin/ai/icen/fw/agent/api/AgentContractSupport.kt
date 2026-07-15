package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

internal object AgentContractLimits {
    const val MAX_ID_CODE_POINTS = 256
    const val MAX_STABLE_ID_CODE_POINTS = 128
    const val MAX_CODE_CODE_POINTS = 128
    const val MAX_NAME_CODE_POINTS = 256
    const val MAX_DESCRIPTION_CODE_POINTS = 4_096
    const val MAX_CONTENT_CODE_POINTS = 131_072
    const val MAX_IDEMPOTENCY_KEY_CODE_POINTS = 256
    const val MAX_MESSAGES = 512
    const val MAX_BLOCKS_PER_MESSAGE = 128
    const val MAX_CAPABILITIES = 128
    const val MAX_TOOLS = 128
    const val MAX_CITATIONS = 1_000
    const val MAX_USAGE_DIMENSIONS = 64
    const val MAX_BINARY_BYTES = 8 * 1_024 * 1_024
    const val MAX_SCHEMA_BYTES = 1 * 1_024 * 1_024
    const val MAX_ARGUMENT_BYTES = 1 * 1_024 * 1_024
    const val MAX_ATTEMPTS = 100
}

private val stableIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")
private val mediaTypePattern = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")

internal fun requireOpaqueIdentifier(identifier: Identifier, message: String): Identifier {
    requireAgentToken(identifier.value, AgentContractLimits.MAX_ID_CODE_POINTS, message)
    return identifier
}

internal fun requireStableAgentId(value: String, message: String): String {
    requireAgentToken(value, AgentContractLimits.MAX_STABLE_ID_CODE_POINTS, message)
    require(stableIdPattern.matches(value)) { message }
    return value
}

internal fun requireAgentCode(value: String, message: String): String =
    requireStableAgentId(value, message)

internal fun requireAgentToken(value: String, maximumCodePoints: Int, message: String): String {
    require(value.isNotBlank()) { message }
    require(value == value.trim()) { message }
    require(!Character.isWhitespace(value.codePointAt(0)) && !Character.isWhitespace(value.codePointBefore(value.length))) {
        message
    }
    require(value.codePointCount(0, value.length) <= maximumCodePoints) { message }
    requireSafeUnicode(value, allowWhitespaceControls = false, message = message)
    return value
}

internal fun requireAgentContent(value: String, maximumCodePoints: Int, message: String): String {
    require(value.isNotBlank()) { message }
    require(value.codePointCount(0, value.length) <= maximumCodePoints) { message }
    requireSafeUnicode(value, allowWhitespaceControls = true, message = message)
    return value
}

internal fun requireOptionalAgentContent(value: String?, maximumCodePoints: Int, message: String): String? {
    if (value != null) requireAgentContent(value, maximumCodePoints, message)
    return value
}

internal fun requireMediaType(value: String, message: String): String {
    requireAgentToken(value, AgentContractLimits.MAX_CODE_CODE_POINTS, message)
    require(mediaTypePattern.matches(value)) { message }
    return value.lowercase()
}

private fun requireSafeUnicode(value: String, allowWhitespaceControls: Boolean, message: String) {
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        val allowedWhitespace = allowWhitespaceControls &&
            (codePoint == '\n'.code || codePoint == '\r'.code || codePoint == '\t'.code)
        require(allowedWhitespace || !Character.isISOControl(codePoint)) { message }
        require(Character.getType(codePoint) != Character.FORMAT.toInt()) { message }
        require(codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) { message }
        require(!isUnicodeNoncharacter(codePoint)) { message }
        offset += Character.charCount(codePoint)
    }
}

private fun isUnicodeNoncharacter(codePoint: Int): Boolean =
    codePoint in 0xFDD0..0xFDEF || (codePoint and 0xFFFF) == 0xFFFE || (codePoint and 0xFFFF) == 0xFFFF

internal fun requireSha256(value: String, message: String): String {
    require(value.length == 64 && value.all { character -> character in '0'..'9' || character in 'a'..'f' }) {
        message
    }
    return value
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

/** Length-prefixed digest input avoids delimiter ambiguity between independently controlled fields. */
internal class AgentDigestBuilder(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(domain)
    }

    fun add(value: String): AgentDigestBuilder {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): AgentDigestBuilder = add(value.toString())

    fun add(value: Int): AgentDigestBuilder = add(value.toString())

    fun add(value: Boolean): AgentDigestBuilder = add(if (value) "1" else "0")

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

internal fun sha256Domain(domain: String, bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val domainBytes = domain.toByteArray(StandardCharsets.UTF_8)
    digest.update(ByteBuffer.allocate(4).putInt(domainBytes.size).array())
    digest.update(domainBytes)
    digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
    digest.update(bytes)
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun requireDigestMatches(bytes: ByteArray, digest: String, message: String) {
    requireSha256(digest, message)
    require(sha256(bytes) == digest) { message }
}

internal fun requireUtf8(bytes: ByteArray, message: String) {
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException(message)
    }
}

internal fun immutableAgentBytes(values: ByteArray): ByteArray = values.copyOf()

internal fun <T> immutableAgentList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

internal fun <T> immutableAgentSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

internal fun <K, V> immutableAgentMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

internal fun requireNonNegativeTime(value: Long, message: String): Long {
    require(value >= 0) { message }
    return value
}

internal fun requirePositiveSequence(value: Long, message: String): Long {
    require(value > 0) { message }
    return value
}
