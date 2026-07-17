package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletionStage

/** The three authorization calls are distinct protocol phases and may never share a request ID. */
enum class AgentAuthorizationPhase {
    POLICY_PREFLIGHT,
    EXECUTION_RECHECK,
    FINAL_EXECUTION_RECHECK,
}

/**
 * Trusted authorization input for exactly one logical tool execution context.
 *
 * Only authenticated runtime code may call [preflight]. Canonical arguments and the logical
 * idempotency key are reduced to domain-bound digests before the request reaches a provider.
 * Execution-time requests can only be derived with [executionRecheck] and [finalExecutionRecheck].
 * Both copy every security binding while creating a distinct request identity and phase.
 */
class AgentAuthorizationRequest private constructor(
    requestId: Identifier,
    parentRequestId: Identifier?,
    val phase: AgentAuthorizationPhase,
    val authorizationProviderId: ProviderId,
    executionContextId: Identifier,
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    runId: Identifier,
    stepId: Identifier,
    val toolProviderId: ProviderId,
    val toolId: ToolId,
    val toolRisk: AgentToolRisk,
    descriptorDigest: String,
    schemaDigest: String,
    argumentsDigest: String,
    idempotencyKeyDigest: String,
    action: String,
    resourceType: String,
    resourceId: Identifier,
    resourceRevision: String,
    purpose: String,
    val requestedAt: Long,
    val expiresAt: Long,
) {
    val requestId: Identifier = requireOpaqueIdentifier(requestId, "Agent authorization request identifier is invalid.")
    val parentRequestId: Identifier? = parentRequestId?.let {
        requireOpaqueIdentifier(it, "Agent authorization parent request identifier is invalid.")
    }
    val executionContextId: Identifier = requireOpaqueIdentifier(
        executionContextId,
        "Agent authorization execution context identifier is invalid.",
    )
    val tenantId: Identifier = requireOpaqueIdentifier(tenantId, "Agent authorization tenant identifier is invalid.")
    val principalId: Identifier = requireOpaqueIdentifier(
        principalId,
        "Agent authorization principal identifier is invalid.",
    )
    val principalType: String = requireAgentCode(principalType, "Agent authorization principal type is invalid.")
    val runId: Identifier = requireOpaqueIdentifier(runId, "Agent authorization run identifier is invalid.")
    val stepId: Identifier = requireOpaqueIdentifier(stepId, "Agent authorization step identifier is invalid.")
    val descriptorDigest: String = requireSha256(
        descriptorDigest,
        "Agent authorization tool descriptor digest is invalid.",
    )
    val schemaDigest: String = requireSha256(schemaDigest, "Agent authorization schema digest is invalid.")
    val argumentsDigest: String = requireSha256(argumentsDigest, "Agent authorization arguments digest is invalid.")
    val idempotencyKeyDigest: String = requireSha256(
        idempotencyKeyDigest,
        "Agent authorization idempotency key digest is invalid.",
    )
    val action: String = requireStableAgentId(action, "Agent authorization action is invalid.")
    val resourceType: String = requireStableAgentId(
        resourceType,
        "Agent authorization resource type is invalid.",
    )
    val resourceId: Identifier = requireOpaqueIdentifier(
        resourceId,
        "Agent authorization resource identifier is invalid.",
    )
    val resourceRevision: String = requireAgentToken(
        resourceRevision,
        AgentContractLimits.MAX_ID_CODE_POINTS,
        "Agent authorization resource revision is invalid.",
    )
    val purpose: String = requireAgentToken(
        purpose,
        AgentContractLimits.MAX_ID_CODE_POINTS,
        "Agent authorization purpose is invalid.",
    )
    val bindingDigest: String

    init {
        requireNonNegativeTime(requestedAt, "Agent authorization request time must not be negative.")
        require(expiresAt > requestedAt) { "Agent authorization request expiry must follow request time." }
        require((phase == AgentAuthorizationPhase.POLICY_PREFLIGHT) == (this.parentRequestId == null)) {
            "Only execution authorization requests may carry a parent request identifier."
        }
        require(this.parentRequestId == null || this.parentRequestId != this.requestId) {
            "Agent authorization recheck must use a distinct request identifier."
        }
        bindingDigest = AgentDigestBuilder("flowweft.agent.authorization-binding.v1")
            .add(this.tenantId.value)
            .add(this.principalType)
            .add(this.principalId.value)
            .add(this.runId.value)
            .add(this.stepId.value)
            .add(this.executionContextId.value)
            .add(authorizationProviderId.value)
            .add(toolProviderId.value)
            .add(toolId.value)
            .add(toolRisk.name)
            .add(this.descriptorDigest)
            .add(this.schemaDigest)
            .add(this.argumentsDigest)
            .add(this.idempotencyKeyDigest)
            .add(this.action)
            .add(this.resourceType)
            .add(this.resourceId.value)
            .add(this.resourceRevision)
            .add(this.purpose)
            .finish()
    }

    fun requireMatches(descriptor: AgentToolDescriptor) {
        require(toolProviderId == descriptor.providerId && toolId == descriptor.toolId) {
            "Agent authorization request tool does not match its descriptor."
        }
        require(
            toolRisk == descriptor.risk &&
                schemaDigest == descriptor.schemaDigest &&
                descriptorDigest == descriptor.descriptorDigest,
        ) {
            "Agent authorization request risk, schema, or descriptor digest does not match its descriptor."
        }
    }

    fun requireArguments(arguments: ByteArray) {
        require(arguments.isNotEmpty() && arguments.size <= AgentContractLimits.MAX_ARGUMENT_BYTES) {
            "Agent authorization request arguments are invalid."
        }
        requireUtf8(arguments, "Agent authorization request arguments must be valid UTF-8 canonical JSON.")
        requireDigestMatches(
            arguments,
            argumentsDigest,
            "Agent authorization request arguments do not match their canonical digest.",
        )
    }

    fun requireIdempotencyKey(idempotencyKey: String) {
        val value = requireAgentToken(
            idempotencyKey,
            AgentContractLimits.MAX_IDEMPOTENCY_KEY_CODE_POINTS,
            "Agent authorization idempotency key is invalid.",
        )
        require(
            sha256Domain("flowweft.agent.idempotency-key.v1", value.toByteArray(StandardCharsets.UTF_8)) ==
                idempotencyKeyDigest,
        ) { "Agent authorization idempotency key does not match its approved digest." }
    }

    fun requireExecutionRecheckOf(preflight: AgentAuthorizationRequest) {
        require(phase == AgentAuthorizationPhase.EXECUTION_RECHECK) {
            "Agent execution authorization must use the execution-recheck phase."
        }
        require(preflight.phase == AgentAuthorizationPhase.POLICY_PREFLIGHT) {
            "Agent execution authorization parent must be a policy preflight request."
        }
        require(parentRequestId == preflight.requestId && requestId != preflight.requestId) {
            "Agent execution authorization does not reference a distinct preflight request."
        }
        require(bindingDigest == preflight.bindingDigest) {
            "Agent execution authorization changed a security binding from preflight."
        }
        require(requestedAt >= preflight.requestedAt && expiresAt <= preflight.expiresAt) {
            "Agent execution authorization lifetime is outside preflight validity."
        }
    }

    /** Verifies the final exact authorization created after the one-time execution context was claimed. */
    fun requireFinalExecutionRecheckOf(executionRecheck: AgentAuthorizationRequest) {
        require(phase == AgentAuthorizationPhase.FINAL_EXECUTION_RECHECK) {
            "Final Agent execution authorization must use the final execution-recheck phase."
        }
        require(executionRecheck.phase == AgentAuthorizationPhase.EXECUTION_RECHECK) {
            "Final Agent execution authorization parent must be an execution-recheck request."
        }
        require(parentRequestId == executionRecheck.requestId && requestId != executionRecheck.requestId) {
            "Final Agent execution authorization does not reference a distinct execution recheck."
        }
        require(bindingDigest == executionRecheck.bindingDigest) {
            "Final Agent execution authorization changed an exact action or resource binding."
        }
        require(requestedAt >= executionRecheck.requestedAt && expiresAt <= executionRecheck.expiresAt) {
            "Final Agent execution authorization lifetime is outside its parent validity."
        }
    }

    override fun toString(): String = "AgentAuthorizationRequest(phase=$phase, toolId=${toolId.value})"

    companion object {
        @JvmStatic
        fun preflight(
            requestId: Identifier,
            executionContextId: Identifier,
            tenantId: Identifier,
            principalId: Identifier,
            principalType: String,
            runId: Identifier,
            stepId: Identifier,
            authorizationProviderId: ProviderId,
            descriptor: AgentToolDescriptor,
            arguments: ByteArray,
            idempotencyKey: String,
            action: String,
            resourceType: String,
            resourceId: Identifier,
            resourceRevision: String,
            purpose: String,
            requestedAt: Long,
            expiresAt: Long,
        ): AgentAuthorizationRequest {
            val argumentsSnapshot = immutableAgentBytes(arguments)
            require(argumentsSnapshot.isNotEmpty() && argumentsSnapshot.size <= AgentContractLimits.MAX_ARGUMENT_BYTES) {
                "Agent authorization request arguments are invalid."
            }
            requireUtf8(argumentsSnapshot, "Agent authorization request arguments must be valid UTF-8 canonical JSON.")
            val safeIdempotencyKey = requireAgentToken(
                idempotencyKey,
                AgentContractLimits.MAX_IDEMPOTENCY_KEY_CODE_POINTS,
                "Agent authorization idempotency key is invalid.",
            )
            return AgentAuthorizationRequest(
                requestId,
                null,
                AgentAuthorizationPhase.POLICY_PREFLIGHT,
                authorizationProviderId,
                executionContextId,
                tenantId,
                principalId,
                principalType,
                runId,
                stepId,
                descriptor.providerId,
                descriptor.toolId,
                descriptor.risk,
                descriptor.descriptorDigest,
                descriptor.schemaDigest,
                sha256(argumentsSnapshot),
                sha256Domain(
                    "flowweft.agent.idempotency-key.v1",
                    safeIdempotencyKey.toByteArray(StandardCharsets.UTF_8),
                ),
                action,
                resourceType,
                resourceId,
                resourceRevision,
                purpose,
                requestedAt,
                expiresAt,
            )
        }

        @JvmStatic
        fun executionRecheck(
            requestId: Identifier,
            preflight: AgentAuthorizationRequest,
            requestedAt: Long,
            expiresAt: Long,
        ): AgentAuthorizationRequest {
            require(preflight.phase == AgentAuthorizationPhase.POLICY_PREFLIGHT) {
                "Agent execution authorization requires a policy preflight request."
            }
            return AgentAuthorizationRequest(
                requestId,
                preflight.requestId,
                AgentAuthorizationPhase.EXECUTION_RECHECK,
                preflight.authorizationProviderId,
                preflight.executionContextId,
                preflight.tenantId,
                preflight.principalId,
                preflight.principalType,
                preflight.runId,
                preflight.stepId,
                preflight.toolProviderId,
                preflight.toolId,
                preflight.toolRisk,
                preflight.descriptorDigest,
                preflight.schemaDigest,
                preflight.argumentsDigest,
                preflight.idempotencyKeyDigest,
                preflight.action,
                preflight.resourceType,
                preflight.resourceId,
                preflight.resourceRevision,
                preflight.purpose,
                requestedAt,
                expiresAt,
            ).also { recheck -> recheck.requireExecutionRecheckOf(preflight) }
        }

        /** Creates the last exact action/resource authorization after durable single-consumption succeeds. */
        @JvmStatic
        fun finalExecutionRecheck(
            requestId: Identifier,
            executionRecheck: AgentAuthorizationRequest,
            requestedAt: Long,
            expiresAt: Long,
        ): AgentAuthorizationRequest {
            require(executionRecheck.phase == AgentAuthorizationPhase.EXECUTION_RECHECK) {
                "Final Agent execution authorization requires an execution-recheck request."
            }
            return AgentAuthorizationRequest(
                requestId,
                executionRecheck.requestId,
                AgentAuthorizationPhase.FINAL_EXECUTION_RECHECK,
                executionRecheck.authorizationProviderId,
                executionRecheck.executionContextId,
                executionRecheck.tenantId,
                executionRecheck.principalId,
                executionRecheck.principalType,
                executionRecheck.runId,
                executionRecheck.stepId,
                executionRecheck.toolProviderId,
                executionRecheck.toolId,
                executionRecheck.toolRisk,
                executionRecheck.descriptorDigest,
                executionRecheck.schemaDigest,
                executionRecheck.argumentsDigest,
                executionRecheck.idempotencyKeyDigest,
                executionRecheck.action,
                executionRecheck.resourceType,
                executionRecheck.resourceId,
                executionRecheck.resourceRevision,
                executionRecheck.purpose,
                requestedAt,
                expiresAt,
            ).also { finalRecheck -> finalRecheck.requireFinalExecutionRecheckOf(executionRecheck) }
        }

        /** Restores payload-free durable authorization evidence and revalidates its exact binding. */
        @JvmStatic
        fun restore(
            requestId: Identifier,
            parentRequestId: Identifier?,
            phase: AgentAuthorizationPhase,
            authorizationProviderId: ProviderId,
            executionContextId: Identifier,
            tenantId: Identifier,
            principalId: Identifier,
            principalType: String,
            runId: Identifier,
            stepId: Identifier,
            toolProviderId: ProviderId,
            toolId: ToolId,
            toolRisk: AgentToolRisk,
            descriptorDigest: String,
            schemaDigest: String,
            argumentsDigest: String,
            idempotencyKeyDigest: String,
            action: String,
            resourceType: String,
            resourceId: Identifier,
            resourceRevision: String,
            purpose: String,
            requestedAt: Long,
            expiresAt: Long,
            bindingDigest: String,
        ): AgentAuthorizationRequest = AgentAuthorizationRequest(
            requestId,
            parentRequestId,
            phase,
            authorizationProviderId,
            executionContextId,
            tenantId,
            principalId,
            principalType,
            runId,
            stepId,
            toolProviderId,
            toolId,
            toolRisk,
            descriptorDigest,
            schemaDigest,
            argumentsDigest,
            idempotencyKeyDigest,
            action,
            resourceType,
            resourceId,
            resourceRevision,
            purpose,
            requestedAt,
            expiresAt,
        ).also { restored ->
            requireSha256(bindingDigest, "Stored Agent authorization binding digest is invalid.")
            require(restored.bindingDigest == bindingDigest) {
                "Stored Agent authorization binding digest does not match its fields."
            }
        }
    }
}

enum class AgentAuthorizationOutcome {
    ALLOW,
    DENY,
}

/** Safe authorization evidence; it carries no ACL expression, token, credential or secret. */
class AgentAuthorizationDecision private constructor(
    decisionId: Identifier,
    val providerId: ProviderId,
    request: AgentAuthorizationRequest,
    val outcome: AgentAuthorizationOutcome,
    authorizationRevision: String,
    val decidedAt: Long,
    val expiresAt: Long,
    reasonCode: String?,
) {
    val decisionId: Identifier = requireOpaqueIdentifier(
        decisionId,
        "Agent authorization decision identifier is invalid.",
    )
    val requestId: Identifier = request.requestId
    val parentRequestId: Identifier? = request.parentRequestId
    val phase: AgentAuthorizationPhase = request.phase
    val bindingDigest: String = request.bindingDigest
    val executionContextId: Identifier = request.executionContextId
    val tenantId: Identifier = request.tenantId
    val principalId: Identifier = request.principalId
    val principalType: String = request.principalType
    val runId: Identifier = request.runId
    val stepId: Identifier = request.stepId
    val toolProviderId: ProviderId = request.toolProviderId
    val toolId: ToolId = request.toolId
    val toolRisk: AgentToolRisk = request.toolRisk
    val descriptorDigest: String = request.descriptorDigest
    val schemaDigest: String = request.schemaDigest
    val argumentsDigest: String = request.argumentsDigest
    val idempotencyKeyDigest: String = request.idempotencyKeyDigest
    val action: String = request.action
    val resourceType: String = request.resourceType
    val resourceId: Identifier = request.resourceId
    val resourceRevision: String = request.resourceRevision
    val purpose: String = request.purpose
    val requestRequestedAt: Long = request.requestedAt
    val requestExpiresAt: Long = request.expiresAt
    val authorizationRevision: String = requireAgentToken(
        authorizationRevision,
        AgentContractLimits.MAX_ID_CODE_POINTS,
        "Agent authorization revision is invalid.",
    )
    val reasonCode: String? = reasonCode?.let {
        requireAgentCode(it, "Agent authorization reason code is invalid.")
    }

    init {
        require(providerId == request.authorizationProviderId) {
            "Agent authorization decision provider does not match the selected provider."
        }
        require(decidedAt >= request.requestedAt && decidedAt < request.expiresAt) {
            "Agent authorization decision time must fall within the request lifetime."
        }
        require(expiresAt > decidedAt && expiresAt <= request.expiresAt) {
            "Agent authorization decision expiry must follow its decision and remain within request validity."
        }
        require(outcome != AgentAuthorizationOutcome.DENY || this.reasonCode != null) {
            "Denied Agent authorization decisions require a reason code."
        }
    }

    fun requireValidFor(request: AgentAuthorizationRequest, atTime: Long) {
        require(
            requestId == request.requestId &&
                parentRequestId == request.parentRequestId &&
                phase == request.phase &&
                bindingDigest == request.bindingDigest,
        ) { "Agent authorization decision request, phase, or binding does not match." }
        require(providerId == request.authorizationProviderId) {
            "Agent authorization decision provider does not match the request."
        }
        require(requestRequestedAt == request.requestedAt && requestExpiresAt == request.expiresAt) {
            "Agent authorization decision request lifetime does not match."
        }
        require(atTime >= decidedAt && atTime < expiresAt) {
            "Agent authorization decision is not valid at the requested time."
        }
    }

    fun requireAllowedFor(request: AgentAuthorizationRequest, atTime: Long) {
        requireValidFor(request, atTime)
        require(outcome == AgentAuthorizationOutcome.ALLOW) { "Agent authorization decision does not allow execution." }
    }

    override fun toString(): String = "AgentAuthorizationDecision(phase=$phase, outcome=$outcome)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun allow(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentAuthorizationRequest,
            authorizationRevision: String,
            decidedAt: Long,
            expiresAt: Long,
            reasonCode: String? = null,
        ): AgentAuthorizationDecision = AgentAuthorizationDecision(
            decisionId,
            providerId,
            request,
            AgentAuthorizationOutcome.ALLOW,
            authorizationRevision,
            decidedAt,
            expiresAt,
            reasonCode,
        )

        @JvmStatic
        fun deny(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentAuthorizationRequest,
            authorizationRevision: String,
            decidedAt: Long,
            expiresAt: Long,
            reasonCode: String,
        ): AgentAuthorizationDecision = AgentAuthorizationDecision(
            decisionId,
            providerId,
            request,
            AgentAuthorizationOutcome.DENY,
            authorizationRevision,
            decidedAt,
            expiresAt,
            reasonCode,
        )
    }
}

interface AgentAuthorizationCall {
    fun completion(): CompletionStage<AgentAuthorizationDecision>

    fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean>
}

interface AgentAuthorizationProvider {
    fun providerId(): ProviderId

    fun start(request: AgentAuthorizationRequest): AgentAuthorizationCall
}

/**
 * Exact, one-time capability presented to the authoritative authorization provider immediately
 * before a tool dispatch. The provider must linearize its current authorization check and the
 * single-consumption record: a later revocation may not invalidate a capability already consumed,
 * while a revocation ordered before consumption must deny it.
 *
 * The request intentionally exposes only trusted authorization coordinates and digests. Tool
 * arguments and credentials never cross this boundary.
 */
class AgentDispatchAuthorizationFenceRequest(
    fenceId: Identifier,
    val consumerId: ProviderId,
    invocation: AuthorizedToolInvocation,
    finalAuthorizationRequest: AgentAuthorizationRequest,
    finalAuthorizationDecision: AgentAuthorizationDecision,
    val requestedAt: Long,
    val expiresAt: Long,
) {
    val fenceId: Identifier = requireOpaqueIdentifier(fenceId, "Agent dispatch fence identifier is invalid.")
    val tenantId: Identifier = invocation.tenantId
    val principalId: Identifier = invocation.principalId
    val principalType: String = invocation.principalType
    val runId: Identifier = invocation.runId
    val stepId: Identifier = invocation.stepId
    val executionContextId: Identifier = invocation.executionContextId
    val invocationId: Identifier = invocation.invocationId
    val logicalInvocationDigest: String = invocation.logicalInvocationDigest
    val toolProviderId: ProviderId = invocation.providerId
    val toolId: ToolId = invocation.toolId
    val action: String = invocation.authorizationAction
    val resourceType: String = invocation.authorizationResourceType
    val resourceId: Identifier = invocation.authorizationResourceId
    val resourceRevision: String = invocation.authorizationResourceRevision
    val purpose: String = invocation.authorizationPurpose
    val finalAuthorizationRequestId: Identifier = finalAuthorizationRequest.requestId
    val finalAuthorizationDecisionId: Identifier = finalAuthorizationDecision.decisionId
    val authorizationRevision: String = finalAuthorizationDecision.authorizationRevision
    val bindingDigest: String

    init {
        finalAuthorizationRequest.requireFinalExecutionRecheckOf(invocation.executionAuthorizationRequest)
        finalAuthorizationDecision.requireAllowedFor(finalAuthorizationRequest, requestedAt)
        require(finalAuthorizationDecision.providerId == invocation.authorizationProviderId) {
            "Agent dispatch fence authorization came from a different provider."
        }
        require(finalAuthorizationDecision.authorizationRevision == invocation.authorizationRevision) {
            "Agent dispatch fence authorization revision changed."
        }
        require(requestedAt >= finalAuthorizationDecision.decidedAt) {
            "Agent dispatch fence must follow the final authorization decision."
        }
        require(expiresAt > requestedAt && expiresAt <= invocation.deadlineAt &&
            expiresAt <= finalAuthorizationRequest.expiresAt && expiresAt <= finalAuthorizationDecision.expiresAt
        ) { "Agent dispatch fence lifetime is outside its authorization chain." }
        bindingDigest = AgentDigestBuilder("flowweft.agent.dispatch-authorization-fence.v1")
            .add(this.fenceId.value)
            .add(consumerId.value)
            .add(this.tenantId.value)
            .add(this.principalType)
            .add(this.principalId.value)
            .add(this.runId.value)
            .add(this.stepId.value)
            .add(this.executionContextId.value)
            .add(this.invocationId.value)
            .add(this.logicalInvocationDigest)
            .add(this.toolProviderId.value)
            .add(this.toolId.value)
            .add(this.action)
            .add(this.resourceType)
            .add(this.resourceId.value)
            .add(this.resourceRevision)
            .add(this.purpose)
            .add(this.finalAuthorizationRequestId.value)
            .add(this.finalAuthorizationDecisionId.value)
            .add(this.authorizationRevision)
            .add(requestedAt)
            .add(expiresAt)
            .finish()
    }

    fun requireCurrent(atTime: Long) {
        require(atTime >= requestedAt && atTime < expiresAt) {
            "Agent dispatch authorization fence is not current."
        }
    }

    override fun toString(): String = "AgentDispatchAuthorizationFenceRequest(toolId=${toolId.value})"
}

enum class AgentDispatchAuthorizationFenceStatus {
    CONSUMED,
    REPLAYED,
}

/** Durable evidence for the authorization provider's atomic check-and-consume operation. */
class AgentDispatchAuthorizationFenceConsumption private constructor(
    receiptId: Identifier,
    request: AgentDispatchAuthorizationFenceRequest,
    val status: AgentDispatchAuthorizationFenceStatus,
    val consumedAt: Long,
    providerRevision: String,
) {
    val receiptId: Identifier = requireOpaqueIdentifier(receiptId, "Agent dispatch fence receipt identifier is invalid.")
    val fenceId: Identifier = request.fenceId
    val consumerId: ProviderId = request.consumerId
    val tenantId: Identifier = request.tenantId
    val executionContextId: Identifier = request.executionContextId
    val invocationId: Identifier = request.invocationId
    val requestBindingDigest: String = request.bindingDigest
    val authorizationRevision: String = request.authorizationRevision
    val providerRevision: String = requireAgentToken(
        providerRevision,
        AgentContractLimits.MAX_ID_CODE_POINTS,
        "Agent dispatch fence provider revision is invalid.",
    )

    init {
        request.requireCurrent(consumedAt)
    }

    fun requireMatches(request: AgentDispatchAuthorizationFenceRequest, atTime: Long) {
        require(
            fenceId == request.fenceId &&
                consumerId == request.consumerId &&
                tenantId == request.tenantId &&
                executionContextId == request.executionContextId &&
                invocationId == request.invocationId &&
                requestBindingDigest == request.bindingDigest &&
                authorizationRevision == request.authorizationRevision,
        ) { "Agent dispatch fence receipt does not match its request." }
        require(atTime >= consumedAt && atTime < request.expiresAt) {
            "Agent dispatch fence receipt is not valid at the requested time."
        }
    }

    override fun toString(): String = "AgentDispatchAuthorizationFenceConsumption(status=$status)"

    companion object {
        @JvmStatic
        fun consumed(
            receiptId: Identifier,
            request: AgentDispatchAuthorizationFenceRequest,
            consumedAt: Long,
            providerRevision: String,
        ): AgentDispatchAuthorizationFenceConsumption = AgentDispatchAuthorizationFenceConsumption(
            receiptId,
            request,
            AgentDispatchAuthorizationFenceStatus.CONSUMED,
            consumedAt,
            providerRevision,
        )

        @JvmStatic
        fun replayed(
            receiptId: Identifier,
            request: AgentDispatchAuthorizationFenceRequest,
            consumedAt: Long,
            providerRevision: String,
        ): AgentDispatchAuthorizationFenceConsumption = AgentDispatchAuthorizationFenceConsumption(
            receiptId,
            request,
            AgentDispatchAuthorizationFenceStatus.REPLAYED,
            consumedAt,
            providerRevision,
        )
    }
}

class AgentDispatchAuthorizationFenceException @JvmOverloads constructor(
    val code: AgentExecutionContextFailureCode,
    safeMessage: String? = null,
    val retryable: Boolean = false,
) : RuntimeException(
    requireOptionalAgentContent(
        safeMessage,
        AgentContractLimits.MAX_DESCRIPTION_CODE_POINTS,
        "Agent dispatch fence failure message is invalid.",
    ) ?: code.value,
) {
    override fun toString(): String = "AgentDispatchAuthorizationFenceException(code=$code, retryable=$retryable)"
}

/**
 * Production authorization providers implement this additive boundary. `consumeDispatchFence`
 * must be a linearizable current-authorization check plus insert-or-read of the fence ID. It may
 * return CONSUMED only to the winning call; an identical replay returns REPLAYED and is reconciled.
 */
interface AgentAtomicDispatchAuthorizationProvider : AgentAuthorizationProvider {
    fun consumeDispatchFence(
        request: AgentDispatchAuthorizationFenceRequest,
    ): CompletionStage<AgentDispatchAuthorizationFenceConsumption>
}
