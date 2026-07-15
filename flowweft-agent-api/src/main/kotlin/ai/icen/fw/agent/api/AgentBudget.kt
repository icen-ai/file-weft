package ai.icen.fw.agent.api

import java.util.concurrent.CancellationException

/** Hard limits assigned before an Agent run begins. A zero cost budget permits only zero reported cost. */
class AgentBudget @JvmOverloads constructor(
    val maximumInputTokens: Long,
    val maximumOutputTokens: Long,
    val maximumModelCalls: Int,
    val maximumToolCalls: Int,
    val maximumDurationMillis: Long,
    val maximumCostMicros: Long = 0,
) {
    init {
        require(maximumInputTokens > 0) { "Agent input token budget must be positive." }
        require(maximumOutputTokens > 0) { "Agent output token budget must be positive." }
        require(maximumModelCalls > 0) { "Agent model call budget must be positive." }
        require(maximumToolCalls >= 0) { "Agent tool call budget must not be negative." }
        require(maximumDurationMillis > 0) { "Agent duration budget must be positive." }
        require(maximumCostMicros >= 0) { "Agent cost budget must not be negative." }
    }

    fun allows(usage: AgentUsage): Boolean =
        usage.inputTokens <= maximumInputTokens &&
            usage.outputTokens <= maximumOutputTokens &&
            usage.modelCalls <= maximumModelCalls &&
            usage.toolCalls <= maximumToolCalls &&
            usage.durationMillis <= maximumDurationMillis &&
            usage.costMicros <= maximumCostMicros

    /** A proposal consumes one additional tool-call slot; equality is already exhausted. */
    fun allowsToolInvocation(usage: AgentUsage): Boolean =
        allows(usage) && usage.toolCalls < maximumToolCalls
}

/** Non-sensitive aggregate usage. Provider-specific units must be counters, never labels or payloads. */
class AgentUsage @JvmOverloads constructor(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val modelCalls: Int = 0,
    val toolCalls: Int = 0,
    val durationMillis: Long = 0,
    val costMicros: Long = 0,
    additionalUnits: Map<String, Long> = emptyMap(),
) {
    val additionalUnits: Map<String, Long>

    init {
        val unitSnapshot = immutableAgentMap(additionalUnits)
        require(inputTokens >= 0) { "Agent input token usage must not be negative." }
        require(outputTokens >= 0) { "Agent output token usage must not be negative." }
        require(modelCalls >= 0) { "Agent model call usage must not be negative." }
        require(toolCalls >= 0) { "Agent tool call usage must not be negative." }
        require(durationMillis >= 0) { "Agent duration usage must not be negative." }
        require(costMicros >= 0) { "Agent cost usage must not be negative." }
        require(unitSnapshot.size <= AgentContractLimits.MAX_USAGE_DIMENSIONS) {
            "Agent usage contains too many additional dimensions."
        }
        unitSnapshot.forEach { (name, value) ->
            requireAgentCode(name, "Agent usage dimension is invalid.")
            require(value >= 0) { "Agent usage dimension value must not be negative." }
        }
        this.additionalUnits = unitSnapshot
    }

    fun plus(other: AgentUsage): AgentUsage {
        val combinedUnits = LinkedHashMap(additionalUnits)
        other.additionalUnits.forEach { (name, value) ->
            combinedUnits[name] = Math.addExact(combinedUnits[name] ?: 0L, value)
        }
        return AgentUsage(
            inputTokens = Math.addExact(inputTokens, other.inputTokens),
            outputTokens = Math.addExact(outputTokens, other.outputTokens),
            modelCalls = Math.addExact(modelCalls, other.modelCalls),
            toolCalls = Math.addExact(toolCalls, other.toolCalls),
            durationMillis = Math.addExact(durationMillis, other.durationMillis),
            costMicros = Math.addExact(costMicros, other.costMicros),
            additionalUnits = combinedUnits,
        )
    }
}

/** Value recorded when cancellation is requested; [reasonCode] must be safe for audit logs. */
class AgentCancellation(
    reasonCode: String,
    val requestedAt: Long,
) {
    val reasonCode: String = requireAgentCode(reasonCode, "Agent cancellation reason code is invalid.")

    init {
        requireNonNegativeTime(requestedAt, "Agent cancellation time must not be negative.")
    }
}

/** Cooperative cancellation view supplied by the runtime. */
interface AgentCancellationToken {
    fun cancellation(): AgentCancellation?

    companion object {
        @JvmField
        val NONE: AgentCancellationToken = object : AgentCancellationToken {
            override fun cancellation(): AgentCancellation? = null
        }
    }
}

class AgentCancellationException(
    val cancellation: AgentCancellation,
) : CancellationException("Agent operation was cancelled: ${cancellation.reasonCode}")
