package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentMessage
import ai.icen.fw.agent.api.AgentRunRequest
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.core.id.Identifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

internal const val MAX_RUNTIME_ITEMS: Int = 1_024
internal const val MAX_RUNTIME_CODE_POINTS: Int = 256
internal const val MAX_RUNTIME_CODE_LENGTH: Int = 128
internal const val MAX_RUNTIME_ARGUMENT_BYTES: Int = 1 * 1_024 * 1_024
internal const val MAX_RUNTIME_ATTEMPTS: Int = 100

private val runtimeCodePattern = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")

internal fun requireRuntimeIdentifier(value: Identifier, message: String): Identifier {
    requireRuntimeToken(value.value, MAX_RUNTIME_CODE_POINTS, message)
    return value
}

internal fun requireRuntimeCode(value: String, message: String): String {
    requireRuntimeToken(value, MAX_RUNTIME_CODE_LENGTH, message)
    require(runtimeCodePattern.matches(value)) { message }
    return value
}

internal fun requireRuntimeToken(value: String, maximumCodePoints: Int, message: String): String {
    require(value.isNotBlank() && value == value.trim()) { message }
    require(value.codePointCount(0, value.length) <= maximumCodePoints) { message }
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        require(!Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT.toInt()) { message }
        require(codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) { message }
        require(codePoint !in 0xFDD0..0xFDEF && (codePoint and 0xFFFF) !in setOf(0xFFFE, 0xFFFF)) { message }
        offset += Character.charCount(codePoint)
    }
    return value
}

internal fun requireRuntimeDigest(value: String, message: String): String {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { message }
    return value
}

internal fun runtimeSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal class AgentRuntimeDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(requireRuntimeCode(domain, "Agent runtime digest domain is invalid."))
    }

    fun add(value: String): AgentRuntimeDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): AgentRuntimeDigest = add(value.toString())
    fun add(value: Int): AgentRuntimeDigest = add(value.toString())
    fun add(value: Boolean): AgentRuntimeDigest = add(if (value) "1" else "0")

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

internal fun runtimeIdempotencyDigest(value: String): String = AgentRuntimeDigest("flowweft.agent.runtime.idempotency.v1")
    .add(requireRuntimeToken(value, MAX_RUNTIME_CODE_POINTS, "Agent runtime idempotency key is invalid."))
    .finish()

/**
 * Stable request identity for an idempotent replay. Transport correlation and admission times are
 * intentionally excluded so a retried HTTP/task request may carry a fresh request ID, while the
 * trusted owner, capability, prompt, limits and absolute deadline must remain exact.
 */
internal fun runtimeIdempotencyReplayDigest(
    request: AgentRunRequest,
    scope: AgentRunIdempotencyScope,
): String {
    request.requireBindingIntact()
    return runtimeIdempotencyReplayDigest(
        scope,
        request.context.locale,
        request.messages,
        request.budget,
        request.deadlineAt,
    )
}

internal fun runtimeIdempotencyReplayDigest(state: AgentDurableRunState): String = state.idempotencyReplayDigest

/**
 * V1 state frames did not persist the original semantic replay digest. A domain-separated marker
 * keeps those runs recoverable while making a new idempotent start fail closed instead of deriving
 * identity from messages that may already contain model or tool output.
 */
internal fun runtimeLegacyIdempotencyReplayDigest(admissionBindingDigest: String): String =
    AgentRuntimeDigest("flowweft.agent.runtime.idempotent-replay-unavailable.v1")
        .add(requireRuntimeDigest(admissionBindingDigest, "Legacy Agent admission binding digest is invalid."))
        .finish()

private fun runtimeIdempotencyReplayDigest(
    scope: AgentRunIdempotencyScope,
    locale: String?,
    messages: Collection<AgentMessage>,
    budget: AgentBudget,
    deadlineAt: Long,
): String {
    val digest = AgentRuntimeDigest("flowweft.agent.runtime.idempotent-replay.v1")
        .add(scope.scopeDigest)
        .add(locale ?: "-")
        .add(messages.size)
    messages.forEach { message ->
        message.requireBindingIntact()
        digest.add(message.bindingDigest)
    }
    return digest
        .add(budget.maximumInputTokens)
        .add(budget.maximumOutputTokens)
        .add(budget.maximumModelCalls)
        .add(budget.maximumToolCalls)
        .add(budget.maximumDurationMillis)
        .add(budget.maximumCostMicros)
        .add(deadlineAt)
        .finish()
}

internal fun <T> runtimeImmutableList(values: Collection<T>, message: String): List<T> {
    require(values.size <= MAX_RUNTIME_ITEMS) { message }
    return Collections.unmodifiableList(ArrayList(values))
}

internal fun <K, V> runtimeImmutableMap(values: Map<K, V>, message: String): Map<K, V> {
    require(values.size <= MAX_RUNTIME_ITEMS) { message }
    return Collections.unmodifiableMap(LinkedHashMap(values))
}

internal fun runtimeUsageEquals(first: AgentUsage, second: AgentUsage): Boolean =
    first.inputTokens == second.inputTokens &&
        first.outputTokens == second.outputTokens &&
        first.modelCalls == second.modelCalls &&
        first.toolCalls == second.toolCalls &&
        first.durationMillis == second.durationMillis &&
        first.costMicros == second.costMicros &&
        first.additionalUnits == second.additionalUnits

internal fun AgentBudget.allowsAnotherModelCall(usage: AgentUsage): Boolean =
    allows(usage) && usage.modelCalls < maximumModelCalls && usage.inputTokens < maximumInputTokens &&
        usage.outputTokens < maximumOutputTokens

internal fun AgentBudget.remainingOutputTokens(usage: AgentUsage): Long = maximumOutputTokens - usage.outputTokens

internal fun AgentBudget.remainingInputTokens(usage: AgentUsage): Long = maximumInputTokens - usage.inputTokens

internal fun AgentBudget.remainingCostMicros(usage: AgentUsage): Long = maximumCostMicros - usage.costMicros

internal fun AgentBudget.remainingDurationMillis(usage: AgentUsage): Long = maximumDurationMillis - usage.durationMillis

internal fun elapsedMillis(startedAt: Long, completedAt: Long): Long {
    require(completedAt >= startedAt) { "Agent runtime completion time precedes dispatch." }
    return completedAt - startedAt
}

internal fun safeAddUsage(first: AgentUsage, second: AgentUsage): AgentUsage = try {
    first.plus(second)
} catch (_: ArithmeticException) {
    throw IllegalArgumentException("Agent runtime usage overflowed its durable counters.")
}

internal fun terminalFailure(category: ai.icen.fw.agent.api.AgentFailureCategory, code: String) =
    ai.icen.fw.agent.api.AgentRunFailure(category, requireRuntimeCode(code, "Agent runtime failure code is invalid."))
