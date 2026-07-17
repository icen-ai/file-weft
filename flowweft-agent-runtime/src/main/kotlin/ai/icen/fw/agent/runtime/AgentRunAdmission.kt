package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentRunRequest
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier

/** Durable idempotency namespace. It prevents one principal or capability from replaying another run. */
class AgentRunIdempotencyScope private constructor(
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    val capabilityId: AgentCapabilityId,
    idempotencyKeyDigest: String,
) {
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Agent idempotency tenant is invalid.")
    val principalId: Identifier = requireRuntimeIdentifier(principalId, "Agent idempotency principal is invalid.")
    val principalType: String = requireRuntimeCode(principalType, "Agent idempotency principal type is invalid.")
    val idempotencyKeyDigest: String = requireRuntimeDigest(
        idempotencyKeyDigest,
        "Agent idempotency key digest is invalid.",
    )
    val scopeDigest: String = AgentRuntimeDigest("flowweft.agent.runtime.idempotency-scope.v1")
        .add(this.tenantId.value)
        .add(this.principalType)
        .add(this.principalId.value)
        .add(capabilityId.value)
        .add(this.idempotencyKeyDigest)
        .finish()

    override fun equals(other: Any?): Boolean = this === other ||
        other is AgentRunIdempotencyScope && scopeDigest == other.scopeDigest

    override fun hashCode(): Int = scopeDigest.hashCode()
    override fun toString(): String = "AgentRunIdempotencyScope(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: Identifier,
            principalId: Identifier,
            principalType: String,
            capabilityId: AgentCapabilityId,
            idempotencyKeyDigest: String,
        ): AgentRunIdempotencyScope = AgentRunIdempotencyScope(
            tenantId,
            principalId,
            principalType,
            capabilityId,
            idempotencyKeyDigest,
        )

        @JvmStatic
        fun from(request: AgentRunRequest): AgentRunIdempotencyScope = AgentRunIdempotencyScope(
            request.context.tenantId,
            request.context.principalId,
            request.context.principalType,
            request.capabilityId,
            runtimeIdempotencyDigest(request.idempotencyKey),
        )
    }
}

/** Trusted, payload-free request for the host's current `agent:run` permission. */
class AgentRunAdmissionRequest private constructor(
    requestId: Identifier,
    request: AgentRunRequest,
    val scope: AgentRunIdempotencyScope,
    val requestedAt: Long,
) {
    val requestId: Identifier = requireRuntimeIdentifier(requestId, "Agent admission request identifier is invalid.")
    val tenantId: Identifier = request.context.tenantId
    val principalId: Identifier = request.context.principalId
    val principalType: String = request.context.principalType
    val callerRequestId: Identifier = request.context.requestId
    val initiatedAt: Long = request.context.initiatedAt
    val capabilityId: AgentCapabilityId = request.capabilityId
    val budget: ai.icen.fw.agent.api.AgentBudget = request.budget
    val deadlineAt: Long = request.deadlineAt
    val requestBindingDigest: String = requireRuntimeDigest(
        request.bindingDigest,
        "Agent admission request binding digest is invalid.",
    )
    val bindingDigest: String

    init {
        request.requireBindingIntact()
        require(requestedAt >= request.context.initiatedAt && requestedAt < request.deadlineAt) {
            "Agent admission request is outside the run lifetime."
        }
        require(scope == AgentRunIdempotencyScope.from(request)) {
            "Agent admission idempotency scope does not match the run request."
        }
        bindingDigest = AgentRuntimeDigest("flowweft.agent.runtime.admission-binding.v2")
            .add(scope.scopeDigest)
            .add(this.requestBindingDigest)
            .add(this.callerRequestId.value)
            .add(this.initiatedAt)
            .add(this.capabilityId.value)
            .add(this.budget.maximumInputTokens)
            .add(this.budget.maximumOutputTokens)
            .add(this.budget.maximumModelCalls)
            .add(this.budget.maximumToolCalls)
            .add(this.budget.maximumDurationMillis)
            .add(this.budget.maximumCostMicros)
            .add(this.deadlineAt)
            .finish()
    }

    override fun toString(): String = "AgentRunAdmissionRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun create(
            requestId: Identifier,
            request: AgentRunRequest,
            requestedAt: Long,
        ): AgentRunAdmissionRequest = AgentRunAdmissionRequest(
            requestId,
            request,
            AgentRunIdempotencyScope.from(request),
            requestedAt,
        )
    }
}

enum class AgentRunAdmissionOutcome {
    ALLOW,
    DENY,
}

/** Permission evidence bound to the exact trusted caller, capability, limits and deadline. */
class AgentRunAdmissionDecision private constructor(
    decisionId: Identifier,
    val providerId: ProviderId,
    requestId: Identifier,
    bindingDigest: String,
    scopeDigest: String,
    val outcome: AgentRunAdmissionOutcome,
    authorizationRevision: String,
    val decidedAt: Long,
    val expiresAt: Long,
    reasonCode: String?,
    val requestRequestedAt: Long,
    val requestDeadlineAt: Long,
    expectedDecisionDigest: String?,
) {
    val decisionId: Identifier = requireRuntimeIdentifier(decisionId, "Agent admission decision identifier is invalid.")
    val requestId: Identifier = requireRuntimeIdentifier(requestId, "Agent admission request identifier is invalid.")
    val bindingDigest: String = requireRuntimeDigest(bindingDigest, "Agent admission binding digest is invalid.")
    val scopeDigest: String = requireRuntimeDigest(scopeDigest, "Agent admission scope digest is invalid.")
    val authorizationRevision: String = requireRuntimeToken(
        authorizationRevision,
        MAX_RUNTIME_CODE_POINTS,
        "Agent admission authorization revision is invalid.",
    )
    val reasonCode: String? = reasonCode?.let {
        requireRuntimeCode(it, "Agent admission reason code is invalid.")
    }
    val decisionDigest: String

    init {
        require(requestRequestedAt >= 0L && requestDeadlineAt > requestRequestedAt) {
            "Agent admission request lifetime is invalid."
        }
        require(decidedAt >= requestRequestedAt && decidedAt < requestDeadlineAt) {
            "Agent admission decision time is outside the run lifetime."
        }
        require(expiresAt > decidedAt && expiresAt <= requestDeadlineAt) {
            "Agent admission decision expiry is outside the run lifetime."
        }
        require(outcome != AgentRunAdmissionOutcome.DENY || this.reasonCode != null) {
            "Denied Agent admission requires a reason code."
        }
        decisionDigest = AgentRuntimeDigest("flowweft.agent.runtime.admission-decision.v2")
            .add(this.decisionId.value)
            .add(providerId.value)
            .add(this.requestId.value)
            .add(bindingDigest)
            .add(scopeDigest)
            .add(outcome.name)
            .add(this.authorizationRevision)
            .add(decidedAt)
            .add(expiresAt)
            .add(this.reasonCode ?: "-")
            .add(requestRequestedAt)
            .add(requestDeadlineAt)
            .finish()
        if (expectedDecisionDigest != null) {
            requireRuntimeDigest(expectedDecisionDigest, "Stored Agent admission decision digest is invalid.")
            require(decisionDigest == expectedDecisionDigest) {
                "Stored Agent admission decision digest does not match its fields."
            }
        }
    }

    fun requireAllowedFor(request: AgentRunAdmissionRequest, atTime: Long) {
        require(requestId == request.requestId && bindingDigest == request.bindingDigest &&
            scopeDigest == request.scope.scopeDigest && requestRequestedAt == request.requestedAt &&
            requestDeadlineAt == request.deadlineAt
        ) { "Agent admission decision does not match the request." }
        require(atTime >= decidedAt && atTime < expiresAt) { "Agent admission decision is no longer current." }
        require(outcome == AgentRunAdmissionOutcome.ALLOW) { "Agent admission decision denied the run." }
    }

    override fun toString(): String = "AgentRunAdmissionDecision(outcome=$outcome)"

    companion object {
        @JvmStatic
        fun allow(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentRunAdmissionRequest,
            authorizationRevision: String,
            decidedAt: Long,
            expiresAt: Long,
        ): AgentRunAdmissionDecision = AgentRunAdmissionDecision(
            decisionId,
            providerId,
            request.requestId,
            request.bindingDigest,
            request.scope.scopeDigest,
            AgentRunAdmissionOutcome.ALLOW,
            authorizationRevision,
            decidedAt,
            expiresAt,
            null,
            request.requestedAt,
            request.deadlineAt,
            null,
        )

        @JvmStatic
        fun deny(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentRunAdmissionRequest,
            authorizationRevision: String,
            decidedAt: Long,
            expiresAt: Long,
            reasonCode: String,
        ): AgentRunAdmissionDecision = AgentRunAdmissionDecision(
            decisionId,
            providerId,
            request.requestId,
            request.bindingDigest,
            request.scope.scopeDigest,
            AgentRunAdmissionOutcome.DENY,
            authorizationRevision,
            decidedAt,
            expiresAt,
            reasonCode,
            request.requestedAt,
            request.deadlineAt,
            null,
        )

        /** Restores durable evidence while re-running every field and digest invariant. */
        @JvmStatic
        fun restore(
            decisionId: Identifier,
            providerId: ProviderId,
            requestId: Identifier,
            bindingDigest: String,
            scopeDigest: String,
            outcome: AgentRunAdmissionOutcome,
            authorizationRevision: String,
            decidedAt: Long,
            expiresAt: Long,
            reasonCode: String?,
            requestRequestedAt: Long,
            requestDeadlineAt: Long,
            decisionDigest: String,
        ): AgentRunAdmissionDecision = AgentRunAdmissionDecision(
            decisionId,
            providerId,
            requestId,
            bindingDigest,
            scopeDigest,
            outcome,
            authorizationRevision,
            decidedAt,
            expiresAt,
            reasonCode,
            requestRequestedAt,
            requestDeadlineAt,
            decisionDigest,
        )
    }
}

interface AgentRunAdmissionPort {
    fun providerId(): ProviderId

    /** The host must derive tenant and principal from the trusted request context and check current permission. */
    fun admit(request: AgentRunAdmissionRequest): AgentRunAdmissionDecision
}

class AgentRunAdmissionException(
    val reasonCode: String,
) : RuntimeException(requireRuntimeCode(reasonCode, "Agent admission failure code is invalid.")) {
    override fun toString(): String = "AgentRunAdmissionException(reasonCode=$reasonCode)"
}
