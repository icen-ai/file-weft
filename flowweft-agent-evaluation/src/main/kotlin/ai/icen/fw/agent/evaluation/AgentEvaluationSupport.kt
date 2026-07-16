package ai.icen.fw.agent.evaluation

import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

internal object AgentEvaluationLimits {
    const val MAX_CODE_POINTS: Int = 256
    const val MAX_CASES: Int = 1_000
    const val MAX_OBSERVATIONS: Int = 6
    const val SCORE_SCALE: Int = 10_000
}

internal fun requireEvaluationIdentifier(identifier: Identifier, message: String): Identifier = identifier.also {
    require(it.value.isNotBlank() && it.value.codePointCount(0, it.value.length) <= AgentEvaluationLimits.MAX_CODE_POINTS) {
        message
    }
    require(it.value.none(Char::isISOControl)) { message }
}

internal fun requireEvaluationToken(value: String, message: String): String = value.also {
    require(it.isNotBlank() && it.codePointCount(0, it.length) <= AgentEvaluationLimits.MAX_CODE_POINTS) { message }
    require(it.none(Char::isISOControl)) { message }
}

internal fun requireEvaluationCode(value: String, message: String): String = value.also {
    require(it.isNotBlank() && it.codePointCount(0, it.length) <= AgentEvaluationLimits.MAX_CODE_POINTS) { message }
    require(it.matches(Regex("[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*"))) { message }
}

internal fun requireEvaluationDigest(value: String, message: String): String = value.lowercase().also {
    require(it.matches(Regex("[0-9a-f]{64}"))) { message }
}

internal fun <T> immutableEvaluationList(values: Collection<T>, limit: Int, message: String): List<T> {
    val snapshot = ArrayList(values)
    require(snapshot.size <= limit) { message }
    return Collections.unmodifiableList(snapshot)
}

internal fun <T> immutableEvaluationSet(values: Collection<T>, limit: Int, message: String): Set<T> {
    val snapshot = LinkedHashSet(values)
    require(snapshot.size == values.size) { "Agent evaluation values must be unique." }
    require(snapshot.size <= limit) { message }
    return Collections.unmodifiableSet(snapshot)
}

internal class AgentEvaluationDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(domain)
    }

    fun add(value: String): AgentEvaluationDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
        return this
    }

    fun add(value: Int): AgentEvaluationDigest = add(value.toString())
    fun add(value: Long): AgentEvaluationDigest = add(value.toString())
    fun add(value: Boolean): AgentEvaluationDigest = add(value.toString())

    fun finish(): String = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun evaluationDigestOf(domain: String, value: String): String = AgentEvaluationDigest(domain).add(value).finish()
