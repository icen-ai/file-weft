package ai.icen.fw.agent.workflow

import ai.icen.fw.agent.api.AgentToolCallContentBlock
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.runtime.AgentDurableRunState
import ai.icen.fw.agent.runtime.AgentToolExecutionPlan
import ai.icen.fw.agent.runtime.AgentToolPlanResolver
import ai.icen.fw.core.id.Identifier

/** Policy planning for the public-use-case directory, derived only from its canonical envelope. */
class WorkflowAgentPublicToolPlanResolver(
    private val directory: WorkflowAgentPublicToolDirectory,
    private val authorizationProviderId: ProviderId,
    private val policyProviderId: ProviderId,
) : AgentToolPlanResolver {
    override fun resolve(
        state: AgentDurableRunState,
        call: AgentToolCallContentBlock,
        descriptors: List<AgentToolDescriptor>,
        deadlineAt: Long,
    ): AgentToolExecutionPlan {
        require(state.capabilityId == WorkflowAgentPublicToolDirectory.CAPABILITY_ID) {
            "Workflow Agent public tool cannot execute under another capability."
        }
        val useCase = directory.entry(call.toolId)
            ?: throw IllegalArgumentException("Workflow Agent public use case is unsupported.")
        val fixed = useCase.toolDescriptor
        val offered = descriptors.singleOrNull { descriptor -> descriptor.toolId == call.toolId }
            ?: throw IllegalArgumentException("Workflow Agent public descriptor is unavailable or ambiguous.")
        require(offered.providerId == WorkflowAgentPublicToolDirectory.PROVIDER_ID &&
            offered.descriptorDigest == fixed.descriptorDigest && offered.schemaDigest == fixed.schemaDigest &&
            offered.risk == fixed.risk && call.schemaDigest == fixed.schemaDigest
        ) { "Workflow Agent public descriptor drifted after discovery." }
        require(deadlineAt > state.updatedAt && deadlineAt <= state.deadlineAt) {
            "Workflow Agent public deadline is outside the durable run lifetime."
        }
        val target = WorkflowAgentPublicAuthorizationTarget.decode(directory, call.toolId, call.arguments)
        return AgentToolExecutionPlan(
            call,
            fixed,
            authorizationProviderId,
            policyProviderId,
            target.idempotencyKey,
            target.action,
            target.resourceType,
            Identifier(target.resourceId),
            target.resourceRevision,
            target.purpose,
            if (target.confirmationRequired) state.context.principalId else null,
            if (target.confirmationRequired) state.context.principalType else null,
            deadlineAt,
        )
    }
}
