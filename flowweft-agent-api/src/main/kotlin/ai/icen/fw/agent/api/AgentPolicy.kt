package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier
import java.util.concurrent.CompletionStage

/** Closed risk tiers used by deterministic policy before any tool executes. */
enum class AgentToolRisk {
    READ_ONLY,
    REVERSIBLE_WRITE,
    IRREVERSIBLE_OR_EXTERNAL_SIDE_EFFECT,
}

/** Immutable, payload-free policy input for one proposed tool invocation. */
class AgentPolicyProposal private constructor(
    proposalId: Identifier,
    val policyProviderId: ProviderId,
    val authorizationRequest: AgentAuthorizationRequest,
    val authorizationDecision: AgentAuthorizationDecision,
    val risk: AgentToolRisk,
    val budget: AgentBudget,
    val usage: AgentUsage,
    val requestedAt: Long,
    val expiresAt: Long,
) {
    val proposalId: Identifier = requireOpaqueIdentifier(proposalId, "Agent policy proposal identifier is invalid.")
    val tenantId: Identifier = authorizationRequest.tenantId
    val principalId: Identifier = authorizationRequest.principalId
    val principalType: String = authorizationRequest.principalType
    val runId: Identifier = authorizationRequest.runId
    val stepId: Identifier = authorizationRequest.stepId
    val toolProviderId: ProviderId = authorizationRequest.toolProviderId
    val toolId: ToolId = authorizationRequest.toolId
    val descriptorDigest: String = authorizationRequest.descriptorDigest
    val schemaDigest: String = authorizationRequest.schemaDigest
    val argumentsDigest: String = authorizationRequest.argumentsDigest
    val idempotencyKeyDigest: String = authorizationRequest.idempotencyKeyDigest
    val authorizationRequestId: Identifier = authorizationRequest.requestId
    val executionContextId: Identifier = authorizationRequest.executionContextId
    val authorizationProviderId: ProviderId = authorizationDecision.providerId
    val authorizationDecisionId: Identifier = authorizationDecision.decisionId
    val authorizationBindingDigest: String = authorizationRequest.bindingDigest
    val authorizationRevision: String = authorizationDecision.authorizationRevision
    val authorizationExpiresAt: Long = authorizationDecision.expiresAt
    val authorizationAction: String = authorizationRequest.action
    val authorizationResourceType: String = authorizationRequest.resourceType
    val authorizationResourceId: Identifier = authorizationRequest.resourceId
    val authorizationResourceRevision: String = authorizationRequest.resourceRevision
    val authorizationPurpose: String = authorizationRequest.purpose
    val policyInputDigest: String

    init {
        require(authorizationRequest.phase == AgentAuthorizationPhase.POLICY_PREFLIGHT) {
            "Agent policy proposals require a policy-preflight authorization request."
        }
        require(risk == authorizationRequest.toolRisk) {
            "Agent policy proposal risk does not match the authorization-bound tool risk."
        }
        requireNonNegativeTime(requestedAt, "Agent policy proposal time must not be negative.")
        authorizationDecision.requireAllowedFor(authorizationRequest, requestedAt)
        require(expiresAt > requestedAt && expiresAt <= authorizationExpiresAt) {
            "Agent policy proposal expiry must follow request time and remain within authorization validity."
        }
        require(budget.allowsToolInvocation(usage)) {
            "Agent policy proposal has no remaining tool-call budget."
        }
        val hasher = AgentDigestBuilder("flowweft.agent.policy-input.v1")
            .add(this.proposalId.value)
            .add(this.policyProviderId.value)
            .add(this.authorizationRequestId.value)
            .add(this.authorizationDecisionId.value)
            .add(this.authorizationBindingDigest)
            .add(this.authorizationRevision)
            .add(authorizationDecision.decidedAt)
            .add(this.authorizationExpiresAt)
            .add(risk.name)
            .add(budget.maximumInputTokens)
            .add(budget.maximumOutputTokens)
            .add(budget.maximumModelCalls)
            .add(budget.maximumToolCalls)
            .add(budget.maximumDurationMillis)
            .add(budget.maximumCostMicros)
            .add(usage.inputTokens)
            .add(usage.outputTokens)
            .add(usage.modelCalls)
            .add(usage.toolCalls)
            .add(usage.durationMillis)
            .add(usage.costMicros)
        usage.additionalUnits.toSortedMap().forEach { (name, value) ->
            hasher.add(name).add(value)
        }
        policyInputDigest = hasher.add(requestedAt).add(expiresAt).finish()
    }

    fun requireMatches(descriptor: AgentToolDescriptor) {
        authorizationRequest.requireMatches(descriptor)
        require(descriptorDigest == descriptor.descriptorDigest && risk == descriptor.risk) {
            "Agent policy proposal descriptor or risk does not match its descriptor."
        }
    }

    override fun toString(): String = "AgentPolicyProposal(toolId=${toolId.value}, risk=$risk)"

    companion object {
        @JvmStatic
        fun create(
            proposalId: Identifier,
            policyProviderId: ProviderId,
            authorizationRequest: AgentAuthorizationRequest,
            authorizationDecision: AgentAuthorizationDecision,
            risk: AgentToolRisk,
            budget: AgentBudget,
            usage: AgentUsage,
            requestedAt: Long,
            expiresAt: Long,
        ): AgentPolicyProposal = AgentPolicyProposal(
            proposalId,
            policyProviderId,
            authorizationRequest,
            authorizationDecision,
            risk,
            budget,
            usage,
            requestedAt,
            expiresAt,
        )
    }
}

enum class AgentPolicyOutcome {
    ALLOW,
    DENY,
    REQUIRE_APPROVAL,
}

/** Signed intent returned by the selected policy provider. It contains no tool payload or secret. */
class AgentPolicyDecision private constructor(
    decisionId: Identifier,
    val providerId: ProviderId,
    proposal: AgentPolicyProposal,
    val outcome: AgentPolicyOutcome,
    policyRevision: String,
    val decidedAt: Long,
    val expiresAt: Long,
    reasonCode: String?,
) {
    val decisionId: Identifier = requireOpaqueIdentifier(decisionId, "Agent policy decision identifier is invalid.")
    val proposalId: Identifier = proposal.proposalId
    val policyInputDigest: String = proposal.policyInputDigest
    val tenantId: Identifier = proposal.tenantId
    val principalId: Identifier = proposal.principalId
    val principalType: String = proposal.principalType
    val runId: Identifier = proposal.runId
    val stepId: Identifier = proposal.stepId
    val toolProviderId: ProviderId = proposal.toolProviderId
    val toolId: ToolId = proposal.toolId
    val descriptorDigest: String = proposal.descriptorDigest
    val schemaDigest: String = proposal.schemaDigest
    val argumentsDigest: String = proposal.argumentsDigest
    val idempotencyKeyDigest: String = proposal.idempotencyKeyDigest
    val authorizationRequestId: Identifier = proposal.authorizationRequestId
    val executionContextId: Identifier = proposal.executionContextId
    val authorizationProviderId: ProviderId = proposal.authorizationProviderId
    val authorizationDecisionId: Identifier = proposal.authorizationDecisionId
    val authorizationBindingDigest: String = proposal.authorizationBindingDigest
    val authorizationRevision: String = proposal.authorizationRevision
    val authorizationExpiresAt: Long = proposal.authorizationExpiresAt
    val authorizationAction: String = proposal.authorizationAction
    val authorizationResourceType: String = proposal.authorizationResourceType
    val authorizationResourceId: Identifier = proposal.authorizationResourceId
    val authorizationResourceRevision: String = proposal.authorizationResourceRevision
    val authorizationPurpose: String = proposal.authorizationPurpose
    val risk: AgentToolRisk = proposal.risk
    val policyRevision: String = requireAgentToken(
        policyRevision,
        AgentContractLimits.MAX_ID_CODE_POINTS,
        "Agent policy revision is invalid.",
    )
    val reasonCode: String? = reasonCode?.let { requireAgentCode(it, "Agent policy reason code is invalid.") }

    init {
        require(providerId == proposal.policyProviderId) {
            "Agent policy decision provider does not match the selected provider."
        }
        require(decidedAt >= proposal.requestedAt && decidedAt < proposal.expiresAt) {
            "Agent policy decision time must fall within the proposal lifetime."
        }
        require(expiresAt > decidedAt && expiresAt <= proposal.expiresAt) {
            "Agent policy decision expiry must follow its decision and remain within the proposal lifetime."
        }
        require(outcome != AgentPolicyOutcome.DENY || this.reasonCode != null) {
            "Denied Agent policy decisions require a reason code."
        }
    }

    fun requireValidFor(proposal: AgentPolicyProposal, atTime: Long) {
        require(providerId == proposal.policyProviderId) { "Agent policy decision provider does not match." }
        require(proposalId == proposal.proposalId && policyInputDigest == proposal.policyInputDigest) {
            "Agent policy decision proposal or input digest does not match."
        }
        require(
            authorizationBindingDigest == proposal.authorizationBindingDigest &&
                authorizationRequestId == proposal.authorizationRequestId &&
                authorizationDecisionId == proposal.authorizationDecisionId &&
                authorizationProviderId == proposal.authorizationProviderId &&
                authorizationRevision == proposal.authorizationRevision &&
                authorizationExpiresAt == proposal.authorizationExpiresAt,
        ) { "Agent policy decision authorization binding does not match the proposal." }
        require(
            descriptorDigest == proposal.descriptorDigest &&
                schemaDigest == proposal.schemaDigest &&
                argumentsDigest == proposal.argumentsDigest &&
                idempotencyKeyDigest == proposal.idempotencyKeyDigest &&
                risk == proposal.risk,
        ) { "Agent policy decision tool, arguments, idempotency, or risk does not match the proposal." }
        require(
            authorizationResourceRevision == proposal.authorizationResourceRevision &&
                authorizationAction == proposal.authorizationAction &&
                authorizationResourceType == proposal.authorizationResourceType &&
                authorizationResourceId == proposal.authorizationResourceId &&
                authorizationPurpose == proposal.authorizationPurpose,
        ) { "Agent policy decision authorization scope does not match the proposal." }
        require(atTime >= decidedAt && atTime < expiresAt) {
            "Agent policy decision is not valid at the requested time."
        }
    }

    override fun toString(): String = "AgentPolicyDecision(outcome=$outcome)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun allow(
            decisionId: Identifier,
            providerId: ProviderId,
            proposal: AgentPolicyProposal,
            policyRevision: String,
            decidedAt: Long,
            expiresAt: Long,
            reasonCode: String? = null,
        ): AgentPolicyDecision = AgentPolicyDecision(
            decisionId,
            providerId,
            proposal,
            AgentPolicyOutcome.ALLOW,
            policyRevision,
            decidedAt,
            expiresAt,
            reasonCode,
        )

        @JvmStatic
        fun deny(
            decisionId: Identifier,
            providerId: ProviderId,
            proposal: AgentPolicyProposal,
            policyRevision: String,
            decidedAt: Long,
            expiresAt: Long,
            reasonCode: String,
        ): AgentPolicyDecision = AgentPolicyDecision(
            decisionId,
            providerId,
            proposal,
            AgentPolicyOutcome.DENY,
            policyRevision,
            decidedAt,
            expiresAt,
            reasonCode,
        )

        @JvmStatic
        @JvmOverloads
        fun requireApproval(
            decisionId: Identifier,
            providerId: ProviderId,
            proposal: AgentPolicyProposal,
            policyRevision: String,
            decidedAt: Long,
            expiresAt: Long,
            reasonCode: String? = null,
        ): AgentPolicyDecision = AgentPolicyDecision(
            decisionId,
            providerId,
            proposal,
            AgentPolicyOutcome.REQUIRE_APPROVAL,
            policyRevision,
            decidedAt,
            expiresAt,
            reasonCode,
        )
    }
}

interface AgentPolicyCall {
    fun completion(): CompletionStage<AgentPolicyDecision>

    fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean>
}

interface AgentPolicyProvider {
    fun providerId(): ProviderId

    fun start(proposal: AgentPolicyProposal): AgentPolicyCall
}
