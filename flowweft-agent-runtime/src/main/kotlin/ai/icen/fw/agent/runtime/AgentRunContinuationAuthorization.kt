package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentRunStatus
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier

/**
 * Current host authorization question asked immediately before a leased run continues.
 * It contains no prompt or tool payload, but is bound to the exact durable state digest.
 */
class AgentRunContinuationAuthorizationRequest(
    requestId: Identifier,
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    runId: Identifier,
    val capabilityId: AgentCapabilityId,
    val status: AgentRunStatus,
    val expectedStateVersion: Long,
    stateDigest: String,
    initialAuthorizationRevision: String,
    val requestedAt: Long,
    val expiresAt: Long,
) {
    val requestId: Identifier = requireRuntimeIdentifier(requestId, "Agent continuation request identifier is invalid.")
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Agent continuation tenant identifier is invalid.")
    val principalId: Identifier = requireRuntimeIdentifier(principalId, "Agent continuation principal identifier is invalid.")
    val principalType: String = requireRuntimeCode(principalType, "Agent continuation principal type is invalid.")
    val runId: Identifier = requireRuntimeIdentifier(runId, "Agent continuation run identifier is invalid.")
    val stateDigest: String = requireRuntimeDigest(stateDigest, "Agent continuation state digest is invalid.")
    val initialAuthorizationRevision: String = requireRuntimeToken(
        initialAuthorizationRevision,
        MAX_RUNTIME_CODE_POINTS,
        "Agent continuation initial authorization revision is invalid.",
    )
    val bindingDigest: String

    init {
        require(!status.isTerminal()) { "Terminal Agent runs cannot request continuation authorization." }
        require(expectedStateVersion >= 0L) { "Agent continuation state version is invalid." }
        require(requestedAt >= 0L && expiresAt > requestedAt) {
            "Agent continuation authorization lifetime is invalid."
        }
        bindingDigest = AgentRuntimeDigest("flowweft.agent.runtime.continuation-authorization.v1")
            .add(this.tenantId.value)
            .add(this.principalType)
            .add(this.principalId.value)
            .add(this.runId.value)
            .add(capabilityId.value)
            .add(status.name)
            .add(expectedStateVersion)
            .add(this.stateDigest)
            .add(this.initialAuthorizationRevision)
            .add(requestedAt)
            .add(expiresAt)
            .finish()
    }

    override fun toString(): String = "AgentRunContinuationAuthorizationRequest(status=$status)"
}

enum class AgentRunContinuationAuthorizationOutcome {
    ALLOW,
    DENY,
}

/** Short-lived, exact-state decision. It is never persisted as authority for a later state. */
class AgentRunContinuationAuthorizationDecision private constructor(
    decisionId: Identifier,
    val providerId: ProviderId,
    request: AgentRunContinuationAuthorizationRequest,
    val outcome: AgentRunContinuationAuthorizationOutcome,
    authorizationRevision: String,
    val decidedAt: Long,
    val expiresAt: Long,
    reasonCode: String?,
) {
    val decisionId: Identifier = requireRuntimeIdentifier(decisionId, "Agent continuation decision identifier is invalid.")
    val requestId: Identifier = request.requestId
    val bindingDigest: String = request.bindingDigest
    val authorizationRevision: String = requireRuntimeToken(
        authorizationRevision,
        MAX_RUNTIME_CODE_POINTS,
        "Agent continuation authorization revision is invalid.",
    )
    val reasonCode: String? = reasonCode?.let {
        requireRuntimeCode(it, "Agent continuation denial code is invalid.")
    }

    init {
        require(decidedAt >= request.requestedAt && decidedAt < request.expiresAt &&
            expiresAt > decidedAt && expiresAt <= request.expiresAt
        ) { "Agent continuation decision lifetime is invalid." }
        require(outcome != AgentRunContinuationAuthorizationOutcome.DENY || this.reasonCode != null) {
            "Denied Agent continuation requires a reason code."
        }
    }

    fun requireAllowedFor(request: AgentRunContinuationAuthorizationRequest, atTime: Long) {
        require(requestId == request.requestId && bindingDigest == request.bindingDigest) {
            "Agent continuation decision binding does not match."
        }
        require(atTime >= decidedAt && atTime < expiresAt) {
            "Agent continuation decision is no longer current."
        }
        require(outcome == AgentRunContinuationAuthorizationOutcome.ALLOW) {
            "Agent continuation authorization was denied."
        }
    }

    companion object {
        @JvmStatic
        fun allow(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentRunContinuationAuthorizationRequest,
            authorizationRevision: String,
            decidedAt: Long,
            expiresAt: Long,
        ): AgentRunContinuationAuthorizationDecision = AgentRunContinuationAuthorizationDecision(
            decisionId,
            providerId,
            request,
            AgentRunContinuationAuthorizationOutcome.ALLOW,
            authorizationRevision,
            decidedAt,
            expiresAt,
            null,
        )

        @JvmStatic
        fun deny(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentRunContinuationAuthorizationRequest,
            authorizationRevision: String,
            decidedAt: Long,
            expiresAt: Long,
            reasonCode: String,
        ): AgentRunContinuationAuthorizationDecision = AgentRunContinuationAuthorizationDecision(
            decisionId,
            providerId,
            request,
            AgentRunContinuationAuthorizationOutcome.DENY,
            authorizationRevision,
            decidedAt,
            expiresAt,
            reasonCode,
        )
    }
}

/** Host bridge that evaluates the principal's current permission and policy revision. */
interface AgentRunContinuationAuthorizationPort {
    fun providerId(): ProviderId

    fun authorize(request: AgentRunContinuationAuthorizationRequest): AgentRunContinuationAuthorizationDecision
}

class AgentRunContinuationAuthorizationException(
    val reasonCode: String,
) : RuntimeException(requireRuntimeCode(reasonCode, "Agent continuation authorization failure code is invalid."))
