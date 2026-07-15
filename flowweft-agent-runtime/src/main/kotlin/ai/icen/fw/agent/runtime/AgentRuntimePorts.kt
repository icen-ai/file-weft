package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentToolCallContentBlock
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.AgentToolExecutor
import ai.icen.fw.agent.api.AgentAuthorizationProvider
import ai.icen.fw.agent.api.AgentPolicyProvider
import ai.icen.fw.agent.api.LanguageModelDescriptor
import ai.icen.fw.agent.api.LanguageModelProvider
import ai.icen.fw.agent.api.ModelId
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.core.id.Identifier

fun interface AgentRuntimeClock {
    fun currentTimeMillis(): Long
}

fun interface AgentRuntimeIdGenerator {
    fun nextId(purpose: String): Identifier
}

class AgentModelSelection(
    val descriptor: LanguageModelDescriptor,
    tools: Collection<AgentToolDescriptor>,
) {
    val tools: List<AgentToolDescriptor> = runtimeImmutableList(tools, "Agent model selection tools exceed the limit.")

    init {
        require(this.tools.map { it.toolId }.toSet().size == this.tools.size) {
            "Agent model selection tool identifiers must be unique."
        }
        require(this.tools.isEmpty() || descriptor.supportsTools) {
            "Agent model selection cannot provide tools to a model without tool support."
        }
    }

    fun requireCapability(capabilityId: AgentCapabilityId) {
        require(capabilityId in descriptor.capabilities) { "Selected Agent model does not support the run capability." }
        require(tools.all { capabilityId in it.capabilities }) {
            "Selected Agent tool does not support the run capability."
        }
    }
}

/** Pure selection from configured, already-health-checked providers. It must not perform remote I/O. */
fun interface AgentModelSelectionPort {
    fun select(state: AgentDurableRunState): AgentModelSelection
}

/**
 * Converts an untrusted model tool-call into a host-owned action/resource scope. It must fail closed
 * when no exact descriptor and authorization target can be derived.
 */
fun interface AgentToolPlanResolver {
    fun resolve(
        state: AgentDurableRunState,
        call: AgentToolCallContentBlock,
        descriptors: List<AgentToolDescriptor>,
        deadlineAt: Long,
    ): AgentToolExecutionPlan
}

fun interface AgentToolExecutorRegistry {
    fun find(providerId: ProviderId, toolId: ToolId): AgentToolExecutor?
}

fun interface AgentLanguageModelProviderRegistry {
    fun find(providerId: ProviderId, modelId: ModelId): LanguageModelProvider?
}

fun interface AgentAuthorizationProviderRegistry {
    fun find(providerId: ProviderId): AgentAuthorizationProvider?
}

fun interface AgentPolicyProviderRegistry {
    fun find(providerId: ProviderId): AgentPolicyProvider?
}

enum class AgentReconciliationOutcome {
    SUCCEEDED,
    FAILED,
    STILL_UNKNOWN,
}

/** Safe operator/provider evidence for an outcome that was previously unknown. */
class AgentReconciliationDecision(
    decisionId: Identifier,
    runId: Identifier,
    tenantId: Identifier,
    stepId: Identifier,
    operationDigest: String,
    val outcome: AgentReconciliationOutcome,
    evidenceDigest: String,
    val decidedAt: Long,
) {
    val decisionId: Identifier = requireRuntimeIdentifier(decisionId, "Agent reconciliation decision identifier is invalid.")
    val runId: Identifier = requireRuntimeIdentifier(runId, "Agent reconciliation run identifier is invalid.")
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Agent reconciliation tenant identifier is invalid.")
    val stepId: Identifier = requireRuntimeIdentifier(stepId, "Agent reconciliation step identifier is invalid.")
    val operationDigest: String = requireRuntimeDigest(operationDigest, "Agent reconciliation operation digest is invalid.")
    val evidenceDigest: String = requireRuntimeDigest(evidenceDigest, "Agent reconciliation evidence digest is invalid.")

    init {
        require(decidedAt >= 0L) { "Agent reconciliation decision time must not be negative." }
    }

    override fun toString(): String = "AgentReconciliationDecision(outcome=$outcome)"
}

class AgentRuntimeConfiguration @JvmOverloads constructor(
    val leaseDurationMillis: Long = 30_000L,
    val maximumProviderAttempts: Int = 3,
) {
    init {
        require(leaseDurationMillis in 1L..300_000L) { "Agent runtime lease duration is invalid." }
        require(maximumProviderAttempts in 1..MAX_RUNTIME_ATTEMPTS) {
            "Agent runtime provider attempt limit is invalid."
        }
    }
}
