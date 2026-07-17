package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier

/** Host-authenticated principal for a command against an existing Agent run. */
class AgentRunCommandContext(
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    requestId: Identifier,
    val authenticatedAt: Long,
) {
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Agent command tenant identifier is invalid.")
    val principalId: Identifier = requireRuntimeIdentifier(principalId, "Agent command principal identifier is invalid.")
    val principalType: String = requireRuntimeCode(principalType, "Agent command principal type is invalid.")
    val requestId: Identifier = requireRuntimeIdentifier(requestId, "Agent command request identifier is invalid.")

    init {
        require(authenticatedAt >= 0L) { "Agent command authentication time must not be negative." }
    }
}

enum class AgentRunCommandAction {
    REPLAY,
    APPROVE,
    RECONCILE,
    CANCEL,
}

/** Payload-free authorization input; evidence is represented only by a domain-bound digest. */
class AgentRunCommandAuthorizationRequest(
    requestId: Identifier,
    val context: AgentRunCommandContext,
    runId: Identifier,
    val action: AgentRunCommandAction,
    val expectedStateVersion: Long?,
    evidenceDigest: String?,
    val requestedAt: Long,
    val expiresAt: Long,
) {
    val requestId: Identifier = requireRuntimeIdentifier(requestId, "Agent command authorization request is invalid.")
    val runId: Identifier = requireRuntimeIdentifier(runId, "Agent command run identifier is invalid.")
    val evidenceDigest: String? = evidenceDigest?.let {
        requireRuntimeDigest(it, "Agent command evidence digest is invalid.")
    }
    val bindingDigest: String

    init {
        require(expectedStateVersion == null || expectedStateVersion >= 0L) {
            "Agent command expected state version is invalid."
        }
        require(requestedAt >= context.authenticatedAt && expiresAt > requestedAt) {
            "Agent command authorization lifetime is invalid."
        }
        require((action == AgentRunCommandAction.REPLAY || action == AgentRunCommandAction.CANCEL) ==
            (expectedStateVersion == null)
        ) {
            "Replay and cancellation commands omit an expected state version."
        }
        require((action == AgentRunCommandAction.REPLAY) == (this.evidenceDigest == null)) {
            "Mutating Agent commands require bound evidence and replay commands do not accept it."
        }
        bindingDigest = AgentRuntimeDigest("flowweft.agent.runtime.command-authorization.v1")
            .add(context.tenantId.value)
            .add(context.principalType)
            .add(context.principalId.value)
            .add(context.requestId.value)
            .add(this.runId.value)
            .add(action.name)
            .add(expectedStateVersion?.toString() ?: "-")
            .add(this.evidenceDigest ?: "-")
            .add(requestedAt)
            .add(expiresAt)
            .finish()
    }

    override fun toString(): String = "AgentRunCommandAuthorizationRequest(action=$action)"
}

enum class AgentRunCommandAuthorizationOutcome {
    ALLOW,
    DENY,
}

class AgentRunCommandAuthorizationDecision private constructor(
    decisionId: Identifier,
    val providerId: ProviderId,
    request: AgentRunCommandAuthorizationRequest,
    val outcome: AgentRunCommandAuthorizationOutcome,
    authorizationRevision: String,
    val decidedAt: Long,
    val expiresAt: Long,
    reasonCode: String?,
) {
    val decisionId: Identifier = requireRuntimeIdentifier(decisionId, "Agent command decision identifier is invalid.")
    val requestId: Identifier = request.requestId
    val bindingDigest: String = request.bindingDigest
    val authorizationRevision: String = requireRuntimeToken(
        authorizationRevision,
        MAX_RUNTIME_CODE_POINTS,
        "Agent command authorization revision is invalid.",
    )
    val reasonCode: String? = reasonCode?.let { requireRuntimeCode(it, "Agent command denial code is invalid.") }

    init {
        require(decidedAt >= request.requestedAt && decidedAt < request.expiresAt &&
            expiresAt > decidedAt && expiresAt <= request.expiresAt
        ) { "Agent command authorization decision lifetime is invalid." }
        require(outcome != AgentRunCommandAuthorizationOutcome.DENY || this.reasonCode != null) {
            "Denied Agent commands require a reason code."
        }
    }

    fun requireAllowedFor(request: AgentRunCommandAuthorizationRequest, atTime: Long) {
        require(requestId == request.requestId && bindingDigest == request.bindingDigest) {
            "Agent command authorization decision binding does not match."
        }
        require(atTime >= decidedAt && atTime < expiresAt) {
            "Agent command authorization decision is no longer current."
        }
        require(outcome == AgentRunCommandAuthorizationOutcome.ALLOW) {
            "Agent command authorization was denied."
        }
    }

    companion object {
        @JvmStatic
        fun allow(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentRunCommandAuthorizationRequest,
            authorizationRevision: String,
            decidedAt: Long,
            expiresAt: Long,
        ): AgentRunCommandAuthorizationDecision = AgentRunCommandAuthorizationDecision(
            decisionId, providerId, request, AgentRunCommandAuthorizationOutcome.ALLOW,
            authorizationRevision, decidedAt, expiresAt, null,
        )

        @JvmStatic
        fun deny(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentRunCommandAuthorizationRequest,
            authorizationRevision: String,
            decidedAt: Long,
            expiresAt: Long,
            reasonCode: String,
        ): AgentRunCommandAuthorizationDecision = AgentRunCommandAuthorizationDecision(
            decisionId, providerId, request, AgentRunCommandAuthorizationOutcome.DENY,
            authorizationRevision, decidedAt, expiresAt, reasonCode,
        )
    }
}

interface AgentRunCommandAuthorizationPort {
    fun providerId(): ProviderId

    /** Must authorize from [AgentRunCommandContext]; it must not use run existence as authority. */
    fun authorize(request: AgentRunCommandAuthorizationRequest): AgentRunCommandAuthorizationDecision
}

class AgentRunCommandAuthorizationException(
    val reasonCode: String,
) : RuntimeException(requireRuntimeCode(reasonCode, "Agent command authorization failure code is invalid."))
