package ai.icen.fw.agent.web.api

import ai.icen.fw.agent.api.AgentApprovalDecision
import ai.icen.fw.agent.api.AgentApprovalOutcome
import ai.icen.fw.agent.api.AgentApprovalRequest
import ai.icen.fw.agent.api.AgentToolRisk
import ai.icen.fw.core.id.Identifier

/** Inbox projection reuses the exact safe Agent approval evidence instead of defining another ABI. */
class AgentWebToolConfirmationSummaryDto(
    val request: AgentApprovalRequest,
    val risk: AgentToolRisk,
    val stateVersion: Long,
) {
    init {
        require(stateVersion >= 0L) { "Agent Web confirmation version is invalid." }
    }

    override fun toString(): String =
        "AgentWebToolConfirmationSummaryDto(toolId=${request.toolId.value}, risk=$risk, <redacted>)"
}

class AgentWebToolConfirmationDetailDto(
    val request: AgentApprovalRequest,
    val risk: AgentToolRisk,
    toolDisplayName: String,
    val stateVersion: Long,
) {
    val toolDisplayName: String = agentWebText(
        toolDisplayName,
        AGENT_WEB_MAX_NAME_BYTES,
        "Agent Web confirmation tool display name",
    )

    init {
        require(stateVersion >= 0L) { "Agent Web confirmation version is invalid." }
    }

    override fun toString(): String =
        "AgentWebToolConfirmationDetailDto(toolId=${request.toolId.value}, risk=$risk, <redacted>)"
}

/**
 * Browser decision intent. Exact proposal, canonical argument digest, approval evidence and state
 * version are all rechecked against authoritative storage immediately before one-time decision.
 */
class AgentWebToolConfirmationDecisionCommand private constructor(
    requestId: Identifier,
    proposalId: Identifier,
    argumentsDigest: String,
    requestEvidenceDigest: String,
    submissionNonce: String,
    val outcome: AgentApprovalOutcome,
    reasonCode: String?,
) {
    val requestId: Identifier = agentWebIdentifier(requestId, "Agent Web confirmation request identifier")
    val proposalId: Identifier = agentWebIdentifier(proposalId, "Agent Web confirmation proposal identifier")
    val argumentsDigest: String = agentWebSha256(argumentsDigest, "Agent Web confirmation arguments")
    val requestEvidenceDigest: String = agentWebSha256(requestEvidenceDigest, "Agent Web confirmation evidence")
    val submissionNonce: String = agentWebCode(submissionNonce, "Agent Web confirmation submission nonce")
    val reasonCode: String? = reasonCode?.let { code -> agentWebCode(code, "Agent Web confirmation reason") }

    init {
        require(outcome != AgentApprovalOutcome.REJECTED || this.reasonCode != null) {
            "Rejected Agent Web confirmations require a reason code."
        }
    }

    fun requireCurrentFor(
        request: AgentApprovalRequest,
        context: AgentWebTrustedContext,
        preconditions: AgentWebWritePreconditions,
        authoritativeStateVersion: Long,
        atTime: Long,
    ) {
        context.requireFresh(atTime)
        require(preconditions.versionTag.expectedVersion == authoritativeStateVersion) {
            "Agent Web confirmation state version changed."
        }
        require(requestId == request.requestId && proposalId == request.proposalId &&
            argumentsDigest == request.argumentsDigest && requestEvidenceDigest == request.evidenceDigest &&
            submissionNonce == request.nonce
        ) { "Agent Web confirmation proposal or arguments changed." }
        require(request.tenantId == context.tenantId && request.principalId == context.principalId &&
            request.principalType == context.principalType && request.operatorId == context.principalId &&
            request.operatorType == context.principalType
        ) { "Agent Web confirmation does not belong to the current principal." }
        require(request.authorizationRevision == context.authorizationRevision &&
            atTime >= request.requestedAt && atTime < request.expiresAt &&
            atTime < request.authorizationExpiresAt
        ) { "Agent Web confirmation authorization is stale or expired." }
    }

    override fun toString(): String = "AgentWebToolConfirmationDecisionCommand(outcome=$outcome, <redacted>)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun approve(
            requestId: Identifier,
            proposalId: Identifier,
            argumentsDigest: String,
            requestEvidenceDigest: String,
            submissionNonce: String,
            reasonCode: String? = null,
        ): AgentWebToolConfirmationDecisionCommand = AgentWebToolConfirmationDecisionCommand(
            requestId,
            proposalId,
            argumentsDigest,
            requestEvidenceDigest,
            submissionNonce,
            AgentApprovalOutcome.APPROVED,
            reasonCode,
        )

        @JvmStatic
        fun reject(
            requestId: Identifier,
            proposalId: Identifier,
            argumentsDigest: String,
            requestEvidenceDigest: String,
            submissionNonce: String,
            reasonCode: String,
        ): AgentWebToolConfirmationDecisionCommand = AgentWebToolConfirmationDecisionCommand(
            requestId,
            proposalId,
            argumentsDigest,
            requestEvidenceDigest,
            submissionNonce,
            AgentApprovalOutcome.REJECTED,
            reasonCode,
        )
    }
}

class AgentWebToolConfirmationDecisionDto(
    val decision: AgentApprovalDecision,
    val stateVersion: Long,
) {
    init {
        require(stateVersion >= 0L) { "Agent Web confirmation decision version is invalid." }
    }

    override fun toString(): String = "AgentWebToolConfirmationDecisionDto(outcome=${decision.outcome}, <redacted>)"
}
