package ai.icen.fw.agent.workflow

import ai.icen.fw.agent.api.AgentToolCallContentBlock
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.runtime.AgentDurableRunState
import ai.icen.fw.agent.runtime.AgentToolExecutionPlan
import ai.icen.fw.agent.runtime.AgentToolPlanResolver
import ai.icen.fw.core.id.Identifier

/**
 * Derives the policy target from canonical arguments, never from model prose. High-risk operations
 * bind the approval operator to the authenticated run principal; Agent execution is not an admin
 * identity and cannot nominate a different confirmer in its arguments.
 */
class WorkflowAgentToolPlanResolver(
    private val catalog: WorkflowAgentToolCatalog,
    private val authorizationProviderId: ProviderId,
    private val policyProviderId: ProviderId,
) : AgentToolPlanResolver {
    override fun resolve(
        state: AgentDurableRunState,
        call: AgentToolCallContentBlock,
        descriptors: List<AgentToolDescriptor>,
        deadlineAt: Long,
    ): AgentToolExecutionPlan {
        require(state.capabilityId == WorkflowAgentToolCatalog.CAPABILITY_ID) {
            "Workflow Agent tool cannot execute under another capability."
        }
        val fixed = catalog.descriptor(call.toolId)
            ?: throw IllegalArgumentException("Workflow Agent tool is unsupported.")
        val offered = descriptors.singleOrNull { descriptor -> descriptor.toolId == call.toolId }
            ?: throw IllegalArgumentException("Workflow Agent tool descriptor is unavailable or ambiguous.")
        require(offered.providerId == WorkflowAgentToolCatalog.PROVIDER_ID &&
            offered.descriptorDigest == fixed.descriptorDigest && offered.schemaDigest == fixed.schemaDigest &&
            offered.risk == fixed.risk && call.schemaDigest == fixed.schemaDigest
        ) { "Workflow Agent tool descriptor drifted after discovery." }
        require(deadlineAt > state.updatedAt && deadlineAt <= state.deadlineAt) {
            "Workflow Agent tool deadline is outside the durable run lifetime."
        }
        val target = WorkflowAgentAuthorizationTarget.decode(call.toolId, call.arguments)
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
