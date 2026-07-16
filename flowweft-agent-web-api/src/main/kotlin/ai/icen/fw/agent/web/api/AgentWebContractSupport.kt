package ai.icen.fw.agent.web.api

import ai.icen.fw.core.id.Identifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

internal const val AGENT_WEB_MAX_ID_BYTES: Int = 512
internal const val AGENT_WEB_MAX_CODE_BYTES: Int = 128
internal const val AGENT_WEB_MAX_NAME_BYTES: Int = 512
internal const val AGENT_WEB_MAX_TEXT_BYTES: Int = 32 * 1024
internal const val AGENT_WEB_MAX_PAGE_SIZE: Int = 200
internal const val AGENT_WEB_MAX_CAPABILITIES: Int = 128
internal const val AGENT_WEB_MAX_CITATIONS: Int = 1_000
internal const val AGENT_WEB_MAX_DOCTOR_CHECKS: Int = 256

private val AGENT_WEB_CODE_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")

internal fun agentWebIdentifier(value: Identifier, label: String): Identifier {
    agentWebText(value.value, AGENT_WEB_MAX_ID_BYTES, label)
    return value
}

internal fun agentWebText(
    value: String,
    maximumBytes: Int,
    label: String,
    allowLineBreaks: Boolean = false,
): String {
    require(value.isNotBlank() && value == value.trim()) { "$label is invalid." }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= maximumBytes) { "$label is too large." }
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        val allowedWhitespace = allowLineBreaks &&
            (codePoint == '\n'.code || codePoint == '\r'.code || codePoint == '\t'.code)
        require(allowedWhitespace || !Character.isISOControl(codePoint)) { "$label contains unsafe Unicode." }
        require(Character.getType(codePoint) != Character.FORMAT.toInt()) { "$label contains unsafe Unicode." }
        require(codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) {
            "$label contains unsafe Unicode."
        }
        require(codePoint !in 0xFDD0..0xFDEF && (codePoint and 0xFFFF) != 0xFFFE &&
            (codePoint and 0xFFFF) != 0xFFFF
        ) { "$label contains unsafe Unicode." }
        offset += Character.charCount(codePoint)
    }
    return value
}

internal fun agentWebOptionalText(
    value: String?,
    maximumBytes: Int,
    label: String,
    allowLineBreaks: Boolean = false,
): String? = value?.let { text -> agentWebText(text, maximumBytes, label, allowLineBreaks) }

internal fun agentWebCode(value: String, label: String): String {
    val checked = agentWebText(value, AGENT_WEB_MAX_CODE_BYTES, label)
    require(AGENT_WEB_CODE_PATTERN.matches(checked)) { "$label is invalid." }
    return checked
}

internal fun agentWebSha256(value: String, label: String): String {
    require(value.length == 64 && value.all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }) { "$label digest is invalid." }
    return value
}

internal fun agentWebTime(value: Long, label: String): Long {
    require(value >= 0L) { "$label is invalid." }
    return value
}

internal class AgentWebDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(domain)
    }

    fun add(value: String): AgentWebDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): AgentWebDigest = add(value.toString())

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

internal fun <T> agentWebList(
    values: Collection<T>,
    maximumSize: Int,
    label: String,
): List<T> {
    require(values.size <= maximumSize) { "$label contains too many values." }
    return Collections.unmodifiableList(ArrayList(values))
}

internal fun <T> agentWebSet(
    values: Collection<T>,
    maximumSize: Int,
    label: String,
): Set<T> {
    require(values.size <= maximumSize) { "$label contains too many values." }
    val snapshot = LinkedHashSet(values)
    require(snapshot.size == values.size) { "$label contains duplicate values." }
    return Collections.unmodifiableSet(snapshot)
}

internal fun <K, V> agentWebMap(
    values: Map<K, V>,
    maximumSize: Int,
    label: String,
): Map<K, V> {
    require(values.size <= maximumSize) { "$label contains too many values." }
    return Collections.unmodifiableMap(LinkedHashMap(values))
}
