package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentApprovalDecision
import ai.icen.fw.agent.api.AgentApprovalRequest
import ai.icen.fw.agent.api.AgentAuthorizationDecision
import ai.icen.fw.agent.api.AgentAuthorizationRequest
import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentCancellationToken
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentExecutionContextConsumption
import ai.icen.fw.agent.api.AgentExecutionContextConsumptionStatus
import ai.icen.fw.agent.api.AgentMessage
import ai.icen.fw.agent.api.AgentPolicyDecision
import ai.icen.fw.agent.api.AgentPolicyProposal
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentRunEvent
import ai.icen.fw.agent.api.AgentRunFailure
import ai.icen.fw.agent.api.AgentRunRequest
import ai.icen.fw.agent.api.AgentRunSnapshot
import ai.icen.fw.agent.api.AgentRunStatus
import ai.icen.fw.agent.api.AgentToolCallContentBlock
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.AgentToolRisk
import ai.icen.fw.agent.api.AuthorizedToolInvocation
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceStatus
import ai.icen.fw.agent.api.LanguageModelDescriptor
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.core.id.Identifier

enum class AgentRuntimeStepKind {
    MODEL,
    TOOL,
}

enum class AgentRuntimeStepStatus {
    CHECKPOINTED,
    CLAIMED,
    WAITING_APPROVAL,
    WAITING_RECONCILIATION,
    COMPLETED,
    FAILED,
}

class AgentRunLease(
    leaseId: Identifier,
    val ownerId: ProviderId,
    val fencingToken: Long,
    val acquiredAt: Long,
    val expiresAt: Long,
) {
    val leaseId: Identifier = requireRuntimeIdentifier(leaseId, "Agent run lease identifier is invalid.")

    init {
        require(fencingToken > 0L) { "Agent run lease fencing token must be positive." }
        require(acquiredAt >= 0L && expiresAt > acquiredAt) { "Agent run lease lifetime is invalid." }
    }

    fun isCurrent(atTime: Long): Boolean = atTime >= acquiredAt && atTime < expiresAt

    fun matches(other: AgentRunLease): Boolean =
        leaseId == other.leaseId && ownerId == other.ownerId && fencingToken == other.fencingToken &&
            acquiredAt == other.acquiredAt && expiresAt == other.expiresAt

    override fun toString(): String = "AgentRunLease(ownerId=$ownerId, fencingToken=$fencingToken)"
}

class AgentRuntimeStep(
    stepId: Identifier,
    val kind: AgentRuntimeStepKind,
    val status: AgentRuntimeStepStatus,
    operationId: Identifier,
    val attempt: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val stepId: Identifier = requireRuntimeIdentifier(stepId, "Agent runtime step identifier is invalid.")
    val operationId: Identifier = requireRuntimeIdentifier(operationId, "Agent runtime operation identifier is invalid.")

    init {
        require(attempt in 1..MAX_RUNTIME_ATTEMPTS) { "Agent runtime step attempt is invalid." }
        require(createdAt >= 0L && updatedAt >= createdAt) { "Agent runtime step timestamps are invalid." }
    }

    fun transition(next: AgentRuntimeStepStatus, attempt: Int, atTime: Long): AgentRuntimeStep {
        require(atTime >= updatedAt) { "Agent runtime step update time moved backwards." }
        require(status != AgentRuntimeStepStatus.COMPLETED && status != AgentRuntimeStepStatus.FAILED) {
            "Terminal Agent runtime steps cannot transition."
        }
        return AgentRuntimeStep(stepId, kind, next, operationId, attempt, createdAt, atTime)
    }
}

class AgentRuntimeCheckpoint(
    checkpointId: Identifier,
    runId: Identifier,
    tenantId: Identifier,
    stepId: Identifier,
    operationId: Identifier,
    checkpointCode: String,
    operationDigest: String,
    val checkpointSequence: Long,
    val createdAt: Long,
) {
    val checkpointId: Identifier = requireRuntimeIdentifier(checkpointId, "Agent checkpoint identifier is invalid.")
    val runId: Identifier = requireRuntimeIdentifier(runId, "Agent checkpoint run identifier is invalid.")
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Agent checkpoint tenant identifier is invalid.")
    val stepId: Identifier = requireRuntimeIdentifier(stepId, "Agent checkpoint step identifier is invalid.")
    val operationId: Identifier = requireRuntimeIdentifier(operationId, "Agent checkpoint operation identifier is invalid.")
    val checkpointCode: String = requireRuntimeCode(checkpointCode, "Agent checkpoint code is invalid.")
    val operationDigest: String = requireRuntimeDigest(operationDigest, "Agent checkpoint operation digest is invalid.")
    val checkpointDigest: String

    init {
        require(checkpointSequence > 0L) { "Agent checkpoint sequence must be positive." }
        require(createdAt >= 0L) { "Agent checkpoint time must not be negative." }
        checkpointDigest = AgentRuntimeDigest("flowweft.agent.runtime.checkpoint.v1")
            .add(this.runId.value)
            .add(this.tenantId.value)
            .add(this.stepId.value)
            .add(this.operationId.value)
            .add(this.checkpointCode)
            .add(this.operationDigest)
            .add(checkpointSequence)
            .add(createdAt)
            .finish()
    }

    override fun toString(): String = "AgentRuntimeCheckpoint(code=$checkpointCode)"
}

enum class AgentRuntimeIncidentStatus {
    OPEN,
    RESOLVED,
}

class AgentRuntimeIncident(
    incidentId: Identifier,
    runId: Identifier,
    tenantId: Identifier,
    stepId: Identifier?,
    code: String,
    val status: AgentRuntimeIncidentStatus,
    val retryable: Boolean,
    val createdAt: Long,
    val resolvedAt: Long? = null,
) {
    val incidentId: Identifier = requireRuntimeIdentifier(incidentId, "Agent incident identifier is invalid.")
    val runId: Identifier = requireRuntimeIdentifier(runId, "Agent incident run identifier is invalid.")
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Agent incident tenant identifier is invalid.")
    val stepId: Identifier? = stepId?.let { requireRuntimeIdentifier(it, "Agent incident step identifier is invalid.") }
    val code: String = requireRuntimeCode(code, "Agent incident code is invalid.")

    init {
        require(createdAt >= 0L) { "Agent incident creation time must not be negative." }
        require((status == AgentRuntimeIncidentStatus.RESOLVED) == (resolvedAt != null)) {
            "Agent incident resolution status and time must agree."
        }
        require(resolvedAt == null || resolvedAt >= createdAt) { "Agent incident resolution time is invalid." }
    }

    override fun toString(): String = "AgentRuntimeIncident(code=$code, status=$status)"
}

enum class AgentPendingModelPhase {
    CHECKPOINTED,
    CLAIMED,
    RECONCILIATION_REQUIRED,
}

interface AgentPendingOperation {
    val operationId: Identifier
    val stepId: Identifier
    val attempt: Int
    val operationDigest: String
    val checkpointId: Identifier
    val claimedLeaseId: Identifier?
}

class AgentPendingModelOperation(
    override val operationId: Identifier,
    override val stepId: Identifier,
    requestId: Identifier,
    val descriptor: LanguageModelDescriptor,
    tools: Collection<AgentToolDescriptor>,
    val maximumInputTokens: Long,
    val maximumOutputTokens: Long,
    val maximumCostMicros: Long,
    val maximumDurationMillis: Long,
    val deadlineAt: Long,
    override val attempt: Int,
    val phase: AgentPendingModelPhase,
    override val checkpointId: Identifier,
    override val claimedLeaseId: Identifier?,
    val createdAt: Long,
    val updatedAt: Long,
) : AgentPendingOperation {
    val requestId: Identifier = requireRuntimeIdentifier(requestId, "Pending model request identifier is invalid.")
    val tools: List<AgentToolDescriptor> = runtimeImmutableList(tools, "Pending model tools exceed the limit.")
    override val operationDigest: String

    init {
        requireRuntimeIdentifier(operationId, "Pending model operation identifier is invalid.")
        requireRuntimeIdentifier(stepId, "Pending model step identifier is invalid.")
        requireRuntimeIdentifier(checkpointId, "Pending model checkpoint identifier is invalid.")
        require(maximumInputTokens > 0L && maximumOutputTokens > 0L) {
            "Pending model token reservations must be positive."
        }
        require(maximumCostMicros >= 0L) { "Pending model cost reservation must not be negative." }
        require(maximumDurationMillis > 0L) { "Pending model duration reservation must be positive." }
        require(deadlineAt > createdAt) { "Pending model deadline must follow creation time." }
        require(maximumInputTokens <= descriptor.maximumInputTokens &&
            maximumOutputTokens <= descriptor.maximumOutputTokens &&
            maximumCostMicros <= descriptor.maximumCostMicros &&
            maximumDurationMillis <= descriptor.maximumDurationMillis &&
            (maximumDurationMillis > Long.MAX_VALUE - createdAt || deadlineAt <= createdAt + maximumDurationMillis)
        ) { "Pending model reservation exceeds its descriptor or lifetime." }
        require(tools.map { tool -> tool.toolId }.toSet().size == tools.size) {
            "Pending model tool identifiers must be unique."
        }
        require(tools.isEmpty() || descriptor.supportsTools) {
            "Pending model tools require model tool support."
        }
        require(attempt in 1..MAX_RUNTIME_ATTEMPTS) { "Pending model attempt is invalid." }
        require(createdAt >= 0L && updatedAt >= createdAt) { "Pending model timestamps are invalid." }
        require((phase == AgentPendingModelPhase.CHECKPOINTED) == (claimedLeaseId == null)) {
            "Only claimed or reconciliation model operations may retain a claim lease."
        }
        operationDigest = AgentRuntimeDigest("flowweft.agent.runtime.pending-model.v1")
            .add(operationId.value)
            .add(stepId.value)
            .add(this.requestId.value)
            .add(descriptor.providerId.value)
            .add(descriptor.modelId.value)
            .add(maximumInputTokens)
            .add(maximumOutputTokens)
            .add(maximumCostMicros)
            .add(maximumDurationMillis)
            .add(deadlineAt)
            .add(attempt)
            .add(this.tools.size)
            .also { hasher -> this.tools.forEach { hasher.add(it.descriptorDigest) } }
            .finish()
    }

    fun claimed(
        lease: AgentRunLease,
        checkpointId: Identifier,
        atTime: Long,
    ): AgentPendingModelOperation = AgentPendingModelOperation(
        operationId,
        stepId,
        requestId,
        descriptor,
        tools,
        maximumInputTokens,
        maximumOutputTokens,
        maximumCostMicros,
        maximumDurationMillis,
        deadlineAt,
        attempt,
        AgentPendingModelPhase.CLAIMED,
        checkpointId,
        lease.leaseId,
        createdAt,
        atTime,
    )

    fun retry(checkpointId: Identifier, attempt: Int, atTime: Long): AgentPendingModelOperation =
        AgentPendingModelOperation(
            operationId,
            stepId,
            requestId,
            descriptor,
            tools,
            maximumInputTokens,
            maximumOutputTokens,
            maximumCostMicros,
            maximumDurationMillis,
            deadlineAt,
            attempt,
            AgentPendingModelPhase.CHECKPOINTED,
            checkpointId,
            null,
            createdAt,
            atTime,
        )

    fun reconciliation(atTime: Long): AgentPendingModelOperation = AgentPendingModelOperation(
        operationId,
        stepId,
        requestId,
        descriptor,
        tools,
        maximumInputTokens,
        maximumOutputTokens,
        maximumCostMicros,
        maximumDurationMillis,
        deadlineAt,
        attempt,
        AgentPendingModelPhase.RECONCILIATION_REQUIRED,
        checkpointId,
        claimedLeaseId,
        createdAt,
        atTime,
    )
}

/** Host-derived, authorization-scoped interpretation of one model tool-call block. */
class AgentToolExecutionPlan(
    val call: AgentToolCallContentBlock,
    val descriptor: AgentToolDescriptor,
    val authorizationProviderId: ProviderId,
    val policyProviderId: ProviderId,
    idempotencyKey: String,
    action: String,
    resourceType: String,
    resourceId: Identifier,
    resourceRevision: String,
    purpose: String,
    operatorId: Identifier?,
    operatorType: String?,
    val deadlineAt: Long,
) {
    val idempotencyKey: String = requireRuntimeToken(
        idempotencyKey,
        MAX_RUNTIME_CODE_POINTS,
        "Agent tool plan idempotency key is invalid.",
    )
    val action: String = requireRuntimeCode(action, "Agent tool plan action is invalid.")
    val resourceType: String = requireRuntimeCode(resourceType, "Agent tool plan resource type is invalid.")
    val resourceId: Identifier = requireRuntimeIdentifier(resourceId, "Agent tool plan resource identifier is invalid.")
    val resourceRevision: String = requireRuntimeToken(
        resourceRevision,
        MAX_RUNTIME_CODE_POINTS,
        "Agent tool plan resource revision is invalid.",
    )
    val purpose: String = requireRuntimeToken(purpose, MAX_RUNTIME_CODE_POINTS, "Agent tool plan purpose is invalid.")
    val operatorId: Identifier? = operatorId?.let {
        requireRuntimeIdentifier(it, "Agent tool plan approval operator identifier is invalid.")
    }
    val operatorType: String? = operatorType?.let {
        requireRuntimeCode(it, "Agent tool plan approval operator type is invalid.")
    }
    val planDigest: String

    init {
        require(call.toolId == descriptor.toolId && call.schemaDigest == descriptor.schemaDigest) {
            "Agent tool plan call does not match its descriptor."
        }
        require(call.arguments.size <= MAX_RUNTIME_ARGUMENT_BYTES) { "Agent tool plan arguments exceed the limit." }
        require((this.operatorId == null) == (this.operatorType == null)) {
            "Agent tool plan approval operator identifier and type must be provided together."
        }
        require(deadlineAt >= 0L) { "Agent tool plan deadline is invalid." }
        planDigest = AgentRuntimeDigest("flowweft.agent.runtime.tool-plan.v1")
            .add(call.callId)
            .add(descriptor.providerId.value)
            .add(descriptor.toolId.value)
            .add(descriptor.descriptorDigest)
            .add(call.argumentsDigest)
            .add(runtimeIdempotencyDigest(this.idempotencyKey))
            .add(this.action)
            .add(this.resourceType)
            .add(this.resourceId.value)
            .add(this.resourceRevision)
            .add(this.purpose)
            .add(this.operatorId?.value ?: "-")
            .add(this.operatorType ?: "-")
            .add(deadlineAt)
            .finish()
    }

    val arguments: ByteArray
        get() = call.arguments

    override fun toString(): String = "AgentToolExecutionPlan(toolId=${descriptor.toolId.value})"
}

enum class AgentPendingToolPhase {
    PREFLIGHT_CHECKPOINTED,
    PREFLIGHT_CLAIMED,
    POLICY_CHECKPOINTED,
    POLICY_CLAIMED,
    WAITING_APPROVAL,
    EXECUTION_RECHECK_CHECKPOINTED,
    EXECUTION_RECHECK_CLAIMED,
    CONSUMPTION_CHECKPOINTED,
    CONSUMPTION_CLAIMED,
    EXECUTION_CLAIMED,
    FINAL_EXECUTION_RECHECK_CHECKPOINTED,
    FINAL_EXECUTION_RECHECK_CLAIMED,
    DISPATCH_FENCE_CHECKPOINTED,
    DISPATCH_FENCE_CLAIMED,
    TOOL_DISPATCHED,
    RECONCILIATION_REQUIRED,
}

class AgentPendingToolOperation(
    override val operationId: Identifier,
    override val stepId: Identifier,
    val plan: AgentToolExecutionPlan,
    override val attempt: Int,
    val phase: AgentPendingToolPhase,
    val preflightRequest: AgentAuthorizationRequest,
    val initialAuthorization: AgentAuthorizationDecision?,
    val proposal: AgentPolicyProposal?,
    val policyDecision: AgentPolicyDecision?,
    val approvalRequest: AgentApprovalRequest?,
    val approvalDecision: AgentApprovalDecision?,
    val executionRecheck: AgentAuthorizationRequest?,
    val executionAuthorization: AgentAuthorizationDecision?,
    val consumption: AgentExecutionContextConsumption?,
    val finalExecutionRecheck: AgentAuthorizationRequest?,
    val finalExecutionAuthorization: AgentAuthorizationDecision?,
    val dispatchFenceRequest: ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceRequest?,
    val dispatchFenceConsumption: ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceConsumption?,
    invocationId: Identifier?,
    val invocationStartedAt: Long?,
    val invocationDeadlineAt: Long?,
    val toolDispatchedAt: Long?,
    val reservedCostMicros: Long?,
    val reservedDurationMillis: Long?,
    override val checkpointId: Identifier,
    override val claimedLeaseId: Identifier?,
    val createdAt: Long,
    val updatedAt: Long,
) : AgentPendingOperation {
    val invocationId: Identifier? = invocationId?.let {
        requireRuntimeIdentifier(it, "Pending tool invocation identifier is invalid.")
    }
    override val operationDigest: String = AgentRuntimeDigest("flowweft.agent.runtime.pending-tool.v1")
        .add(operationId.value)
        .add(stepId.value)
        .add(plan.planDigest)
        .add(attempt)
        .add(phase.name)
        .add(preflightRequest.requestId.value)
        .add(preflightRequest.bindingDigest)
        .add(initialAuthorization?.decisionId?.value ?: "-")
        .add(initialAuthorization?.authorizationRevision ?: "-")
        .add(proposal?.policyInputDigest ?: "-")
        .add(policyDecision?.decisionId?.value ?: "-")
        .add(policyDecision?.policyRevision ?: "-")
        .add(approvalRequest?.requestId?.value ?: "-")
        .add(approvalDecision?.decisionId?.value ?: "-")
        .add(executionRecheck?.requestId?.value ?: "-")
        .add(executionRecheck?.bindingDigest ?: "-")
        .add(executionRecheck?.requestedAt?.toString() ?: "-")
        .add(executionRecheck?.expiresAt?.toString() ?: "-")
        .add(executionAuthorization?.decisionId?.value ?: "-")
        .add(executionAuthorization?.bindingDigest ?: "-")
        .add(executionAuthorization?.authorizationRevision ?: "-")
        .add(executionAuthorization?.outcome?.name ?: "-")
        .add(executionAuthorization?.decidedAt?.toString() ?: "-")
        .add(executionAuthorization?.expiresAt?.toString() ?: "-")
        .add(consumption?.receiptId?.value ?: "-")
        .add(consumption?.consumerId?.value ?: "-")
        .add(consumption?.status?.name ?: "-")
        .add(consumption?.consumedAt?.toString() ?: "-")
        .add(consumption?.consumerRevision ?: "-")
        .add(consumption?.logicalInvocationDigest ?: "-")
        .add(finalExecutionRecheck?.requestId?.value ?: "-")
        .add(finalExecutionRecheck?.parentRequestId?.value ?: "-")
        .add(finalExecutionRecheck?.bindingDigest ?: "-")
        .add(finalExecutionRecheck?.requestedAt?.toString() ?: "-")
        .add(finalExecutionRecheck?.expiresAt?.toString() ?: "-")
        .add(finalExecutionAuthorization?.decisionId?.value ?: "-")
        .add(finalExecutionAuthorization?.providerId?.value ?: "-")
        .add(finalExecutionAuthorization?.bindingDigest ?: "-")
        .add(finalExecutionAuthorization?.authorizationRevision ?: "-")
        .add(finalExecutionAuthorization?.outcome?.name ?: "-")
        .add(finalExecutionAuthorization?.decidedAt?.toString() ?: "-")
        .add(finalExecutionAuthorization?.expiresAt?.toString() ?: "-")
        .add(dispatchFenceRequest?.fenceId?.value ?: "-")
        .add(dispatchFenceRequest?.bindingDigest ?: "-")
        .add(dispatchFenceConsumption?.receiptId?.value ?: "-")
        .add(dispatchFenceConsumption?.status?.name ?: "-")
        .add(dispatchFenceConsumption?.providerRevision ?: "-")
        .add(dispatchFenceConsumption?.consumedAt?.toString() ?: "-")
        .add(this.invocationId?.value ?: "-")
        .add(invocationStartedAt?.toString() ?: "-")
        .add(invocationDeadlineAt?.toString() ?: "-")
        .add(toolDispatchedAt?.toString() ?: "-")
        .add(reservedCostMicros?.toString() ?: "-")
        .add(reservedDurationMillis?.toString() ?: "-")
        .finish()

    init {
        requireRuntimeIdentifier(operationId, "Pending tool operation identifier is invalid.")
        requireRuntimeIdentifier(stepId, "Pending tool step identifier is invalid.")
        requireRuntimeIdentifier(checkpointId, "Pending tool checkpoint identifier is invalid.")
        require(preflightRequest.phase == ai.icen.fw.agent.api.AgentAuthorizationPhase.POLICY_PREFLIGHT &&
            preflightRequest.stepId == stepId &&
            preflightRequest.authorizationProviderId == plan.authorizationProviderId &&
            preflightRequest.action == plan.action && preflightRequest.resourceType == plan.resourceType &&
            preflightRequest.resourceId == plan.resourceId &&
            preflightRequest.resourceRevision == plan.resourceRevision && preflightRequest.purpose == plan.purpose
        ) { "Pending tool preflight does not match its host-derived execution plan." }
        preflightRequest.requireMatches(plan.descriptor)
        preflightRequest.requireArguments(plan.arguments)
        preflightRequest.requireIdempotencyKey(plan.idempotencyKey)
        require(attempt in 1..MAX_RUNTIME_ATTEMPTS) { "Pending tool attempt is invalid." }
        require(createdAt >= 0L && updatedAt >= createdAt) { "Pending tool timestamps are invalid." }
        require((this.invocationId == null) == (invocationStartedAt == null) &&
            (this.invocationId == null) == (invocationDeadlineAt == null)
        ) { "Pending tool invocation identity and lifetime must be provided together." }
        require(invocationStartedAt == null || invocationDeadlineAt!! > invocationStartedAt) {
            "Pending tool invocation lifetime is invalid."
        }
        require((toolDispatchedAt == null) == (reservedCostMicros == null) &&
            (toolDispatchedAt == null) == (reservedDurationMillis == null)
        ) { "Pending tool dispatch time and budget reservations must be provided together." }
        require(toolDispatchedAt == null || toolDispatchedAt >= requireNotNull(invocationStartedAt)) {
            "Pending tool dispatch time is invalid."
        }
        require(reservedCostMicros == null || reservedCostMicros >= 0L) {
            "Pending tool cost reservation is invalid."
        }
        require(reservedDurationMillis == null || reservedDurationMillis > 0L) {
            "Pending tool duration reservation is invalid."
        }
        val claimedPhase = phase == AgentPendingToolPhase.PREFLIGHT_CLAIMED ||
            phase == AgentPendingToolPhase.POLICY_CLAIMED ||
            phase == AgentPendingToolPhase.EXECUTION_RECHECK_CLAIMED ||
            phase == AgentPendingToolPhase.CONSUMPTION_CLAIMED ||
            phase == AgentPendingToolPhase.EXECUTION_CLAIMED ||
            phase == AgentPendingToolPhase.FINAL_EXECUTION_RECHECK_CLAIMED ||
            phase == AgentPendingToolPhase.DISPATCH_FENCE_CLAIMED ||
            phase == AgentPendingToolPhase.TOOL_DISPATCHED
        require(
            phase == AgentPendingToolPhase.RECONCILIATION_REQUIRED ||
                claimedPhase == (claimedLeaseId != null),
        ) { "Pending tool claim phase and lease evidence do not agree." }
        require(phase.ordinal < AgentPendingToolPhase.POLICY_CHECKPOINTED.ordinal ||
            initialAuthorization != null && proposal != null
        ) { "Pending tool policy phase requires initial authorization evidence." }
        require(phase != AgentPendingToolPhase.WAITING_APPROVAL || policyDecision != null) {
            "Pending tool approval phase requires a policy decision."
        }
        require(phase.ordinal < AgentPendingToolPhase.EXECUTION_RECHECK_CHECKPOINTED.ordinal || policyDecision != null) {
            "Pending tool execution phase requires a policy decision."
        }
        if (policyDecision?.outcome == ai.icen.fw.agent.api.AgentPolicyOutcome.REQUIRE_APPROVAL) {
            require(approvalRequest != null) { "Approval policy requires an approval request." }
            require(phase == AgentPendingToolPhase.WAITING_APPROVAL || approvalDecision != null) {
                "Approval policy requires an approval decision before execution recheck."
            }
        }
        require(phase.ordinal < AgentPendingToolPhase.EXECUTION_RECHECK_CHECKPOINTED.ordinal ||
            executionRecheck != null
        ) { "Pending tool execution-recheck phase requires its bound request." }
        require(phase.ordinal < AgentPendingToolPhase.CONSUMPTION_CHECKPOINTED.ordinal ||
            executionRecheck != null && executionAuthorization != null && this.invocationId != null
        ) { "Pending tool consumption requires fresh execution authorization evidence." }
        require(phase.ordinal < AgentPendingToolPhase.EXECUTION_CLAIMED.ordinal ||
            phase == AgentPendingToolPhase.RECONCILIATION_REQUIRED || consumption != null
        ) {
            "Pending tool execution requires a durable execution-context claim."
        }
        require(phase.ordinal < AgentPendingToolPhase.FINAL_EXECUTION_RECHECK_CHECKPOINTED.ordinal ||
            phase == AgentPendingToolPhase.RECONCILIATION_REQUIRED || finalExecutionRecheck != null
        ) { "Pending tool final execution phase requires an exact post-consumption authorization request." }
        require(phase.ordinal < AgentPendingToolPhase.DISPATCH_FENCE_CHECKPOINTED.ordinal ||
            phase == AgentPendingToolPhase.RECONCILIATION_REQUIRED ||
            finalExecutionAuthorization != null && dispatchFenceRequest != null
        ) { "Pending tool dispatch-fence phase requires final authorization and an exact fence request." }
        require(phase.ordinal < AgentPendingToolPhase.TOOL_DISPATCHED.ordinal ||
            phase == AgentPendingToolPhase.RECONCILIATION_REQUIRED ||
            dispatchFenceConsumption != null && toolDispatchedAt != null
        ) { "Pending tool dispatch requires a consumed authorization fence and a durable budget reservation." }
        validateEvidenceChain()
    }

    private fun validateEvidenceChain() {
        initialAuthorization?.requireValidFor(preflightRequest, initialAuthorization.decidedAt)
        proposal?.let { value ->
            require(initialAuthorization != null && value.authorizationRequestId == preflightRequest.requestId &&
                value.authorizationBindingDigest == preflightRequest.bindingDigest &&
                value.authorizationDecisionId == initialAuthorization.decisionId &&
                value.authorizationRevision == initialAuthorization.authorizationRevision
            ) { "Pending tool proposal does not match its initial authorization chain." }
        }
        policyDecision?.let { decision ->
            decision.requireValidFor(
                requireNotNull(proposal) { "Pending tool policy decision lacks its proposal." },
                decision.decidedAt,
            )
            require(decision.outcome != ai.icen.fw.agent.api.AgentPolicyOutcome.DENY) {
                "Denied Agent tool policy decisions cannot remain pending."
            }
        }
        approvalRequest?.let { request ->
            request.requireValidFor(
                requireNotNull(proposal) { "Pending tool approval request lacks its proposal." },
                requireNotNull(policyDecision) { "Pending tool approval request lacks its policy decision." },
                request.requestedAt,
            )
        }
        approvalDecision?.let { decision ->
            decision.requireValidFor(
                requireNotNull(approvalRequest) { "Pending tool approval decision lacks its request." },
                decision.decidedAt,
            )
        }
        executionRecheck?.requireExecutionRecheckOf(preflightRequest)
        executionAuthorization?.let { decision ->
            decision.requireValidFor(
                requireNotNull(executionRecheck) { "Pending tool execution decision lacks its request." },
                decision.decidedAt,
            )
            require(decision.outcome == ai.icen.fw.agent.api.AgentAuthorizationOutcome.ALLOW) {
                "Denied Agent execution authorization cannot remain pending."
            }
        }

        val invocation = invocationId?.let { id ->
            AuthorizedToolInvocation.authorize(
                id,
                requireNotNull(proposal) { "Pending tool invocation lacks its proposal." },
                plan.descriptor,
                requireNotNull(policyDecision) { "Pending tool invocation lacks its policy decision." },
                requireNotNull(executionRecheck) { "Pending tool invocation lacks its execution recheck." },
                requireNotNull(executionAuthorization) { "Pending tool invocation lacks execution authorization." },
                approvalRequest,
                approvalDecision,
                plan.arguments,
                plan.idempotencyKey,
                attempt,
                requireNotNull(invocationStartedAt),
                requireNotNull(invocationDeadlineAt),
                AgentCancellationToken.NONE,
            )
        }
        consumption?.let { receipt ->
            receipt.requireMatches(
                requireNotNull(invocation) { "Pending execution receipt lacks its invocation." },
                receipt.consumedAt,
            )
        }
        finalExecutionRecheck?.requireFinalExecutionRecheckOf(
            requireNotNull(executionRecheck) { "Pending final authorization request lacks its parent." },
        )
        finalExecutionAuthorization?.let { decision ->
            decision.requireValidFor(
                requireNotNull(finalExecutionRecheck) { "Pending final authorization lacks its request." },
                decision.decidedAt,
            )
            require(decision.outcome == ai.icen.fw.agent.api.AgentAuthorizationOutcome.ALLOW) {
                "Denied final Agent authorization cannot remain pending."
            }
        }
        dispatchFenceRequest?.let { request ->
            val authorized = requireNotNull(invocation) { "Pending dispatch fence lacks its invocation." }
            require(request.tenantId == authorized.tenantId && request.runId == authorized.runId &&
                request.stepId == authorized.stepId && request.executionContextId == authorized.executionContextId &&
                request.invocationId == authorized.invocationId &&
                request.logicalInvocationDigest == authorized.logicalInvocationDigest &&
                request.finalAuthorizationRequestId == finalExecutionRecheck?.requestId &&
                request.finalAuthorizationDecisionId == finalExecutionAuthorization?.decisionId
            ) { "Pending dispatch fence does not match its exact invocation and authorization chain." }
        }
        dispatchFenceConsumption?.let { receipt ->
            receipt.requireMatches(
                requireNotNull(dispatchFenceRequest) { "Pending dispatch receipt lacks its fence request." },
                receipt.consumedAt,
            )
        }
        if (phase == AgentPendingToolPhase.EXECUTION_CLAIMED ||
            phase.ordinal >= AgentPendingToolPhase.FINAL_EXECUTION_RECHECK_CHECKPOINTED.ordinal &&
            phase != AgentPendingToolPhase.RECONCILIATION_REQUIRED
        ) {
            require(consumption?.status == AgentExecutionContextConsumptionStatus.CLAIMED) {
                "Executable Agent tool phases require a freshly claimed execution context."
            }
        }
        if (toolDispatchedAt != null) {
            require(dispatchFenceConsumption?.status == AgentDispatchAuthorizationFenceStatus.CONSUMED) {
                "A durable Agent tool dispatch requires a freshly consumed authorization fence."
            }
            require(toolDispatchedAt >= dispatchFenceConsumption.consumedAt &&
                toolDispatchedAt < requireNotNull(invocationDeadlineAt) &&
                toolDispatchedAt < requireNotNull(finalExecutionAuthorization).expiresAt
            ) { "Pending Agent tool dispatch time is outside its authorization chain." }
            require(reservedCostMicros == plan.descriptor.maximumCostMicros &&
                requireNotNull(reservedDurationMillis) <= plan.descriptor.maximumDurationMillis
            ) { "Pending Agent tool budget reservation does not match its descriptor." }
        }
    }

    fun with(
        phase: AgentPendingToolPhase,
        checkpointId: Identifier = this.checkpointId,
        claimedLeaseId: Identifier? = this.claimedLeaseId,
        initialAuthorization: AgentAuthorizationDecision? = this.initialAuthorization,
        proposal: AgentPolicyProposal? = this.proposal,
        policyDecision: AgentPolicyDecision? = this.policyDecision,
        approvalRequest: AgentApprovalRequest? = this.approvalRequest,
        approvalDecision: AgentApprovalDecision? = this.approvalDecision,
        executionRecheck: AgentAuthorizationRequest? = this.executionRecheck,
        executionAuthorization: AgentAuthorizationDecision? = this.executionAuthorization,
        consumption: AgentExecutionContextConsumption? = this.consumption,
        finalExecutionRecheck: AgentAuthorizationRequest? = this.finalExecutionRecheck,
        finalExecutionAuthorization: AgentAuthorizationDecision? = this.finalExecutionAuthorization,
        dispatchFenceRequest: ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceRequest? = this.dispatchFenceRequest,
        dispatchFenceConsumption: ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceConsumption? =
            this.dispatchFenceConsumption,
        invocationId: Identifier? = this.invocationId,
        invocationStartedAt: Long? = this.invocationStartedAt,
        invocationDeadlineAt: Long? = this.invocationDeadlineAt,
        toolDispatchedAt: Long? = this.toolDispatchedAt,
        reservedCostMicros: Long? = this.reservedCostMicros,
        reservedDurationMillis: Long? = this.reservedDurationMillis,
        updatedAt: Long,
    ): AgentPendingToolOperation = AgentPendingToolOperation(
        operationId,
        stepId,
        plan,
        attempt,
        phase,
        preflightRequest,
        initialAuthorization,
        proposal,
        policyDecision,
        approvalRequest,
        approvalDecision,
        executionRecheck,
        executionAuthorization,
        consumption,
        finalExecutionRecheck,
        finalExecutionAuthorization,
        dispatchFenceRequest,
        dispatchFenceConsumption,
        invocationId,
        invocationStartedAt,
        invocationDeadlineAt,
        toolDispatchedAt,
        reservedCostMicros,
        reservedDurationMillis,
        checkpointId,
        claimedLeaseId,
        createdAt,
        updatedAt,
    )

    /**
     * Starts a new, independently authorized attempt while preserving the logical idempotency key.
     * Every attempt receives a new one-time execution context through [preflightRequest].
     */
    fun retry(
        preflightRequest: AgentAuthorizationRequest,
        checkpointId: Identifier,
        atTime: Long,
    ): AgentPendingToolOperation = AgentPendingToolOperation(
        operationId,
        stepId,
        plan,
        attempt + 1,
        AgentPendingToolPhase.PREFLIGHT_CHECKPOINTED,
        preflightRequest,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        checkpointId,
        null,
        createdAt,
        atTime,
    )
}

/** Full durable projection. Persistence adapters map it; they do not run business transitions. */
class AgentDurableRunState private constructor(
    runId: Identifier,
    val context: AgentRunContext,
    val capabilityId: AgentCapabilityId,
    messages: Collection<AgentMessage>,
    val budget: AgentBudget,
    val usage: AgentUsage,
    val status: AgentRunStatus,
    val stateVersion: Long,
    val eventSequence: Long,
    val checkpointSequence: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deadlineAt: Long,
    val idempotencyScope: AgentRunIdempotencyScope,
    idempotencyReplayDigest: String,
    val admission: AgentRunAdmissionDecision,
    steps: Collection<AgentRuntimeStep>,
    checkpoints: Collection<AgentRuntimeCheckpoint>,
    currentStepId: Identifier?,
    val pendingOperation: AgentPendingOperation?,
    val lease: AgentRunLease?,
    val cancellation: AgentCancellation?,
    val failure: AgentRunFailure?,
    incidents: Collection<AgentRuntimeIncident>,
) {
    val runId: Identifier = requireRuntimeIdentifier(runId, "Durable Agent run identifier is invalid.")
    val tenantId: Identifier = context.tenantId
    val messages: List<AgentMessage> = runtimeImmutableList(messages, "Durable Agent messages exceed the limit.")
    val steps: List<AgentRuntimeStep> = runtimeImmutableList(steps, "Durable Agent steps exceed the limit.")
    val checkpoints: List<AgentRuntimeCheckpoint> = runtimeImmutableList(
        checkpoints,
        "Durable Agent checkpoints exceed the limit.",
    )
    val currentStepId: Identifier? = currentStepId?.let {
        requireRuntimeIdentifier(it, "Durable Agent current step identifier is invalid.")
    }
    val idempotencyReplayDigest: String = requireRuntimeDigest(
        idempotencyReplayDigest,
        "Durable Agent idempotency replay digest is invalid.",
    )
    val incidents: List<AgentRuntimeIncident> = runtimeImmutableList(incidents, "Durable Agent incidents exceed the limit.")

    init {
        require(stateVersion >= 0L && eventSequence >= 0L && checkpointSequence >= 0L) {
            "Durable Agent sequences must not be negative."
        }
        require(createdAt >= context.initiatedAt && updatedAt >= createdAt && deadlineAt > createdAt) {
            "Durable Agent run timestamps are invalid."
        }
        require(deadlineAt <= context.initiatedAt + budget.maximumDurationMillis ||
            budget.maximumDurationMillis > Long.MAX_VALUE - context.initiatedAt
        ) { "Durable Agent deadline exceeds its duration budget." }
        require(idempotencyScope.tenantId == tenantId && idempotencyScope.principalId == context.principalId &&
            idempotencyScope.principalType == context.principalType && idempotencyScope.capabilityId == capabilityId
        ) { "Durable Agent idempotency scope does not match its run context." }
        require(admission.scopeDigest == idempotencyScope.scopeDigest) {
            "Durable Agent admission evidence does not match its idempotency scope."
        }
        require(admission.outcome == AgentRunAdmissionOutcome.ALLOW &&
            admission.requestDeadlineAt == deadlineAt &&
            admission.requestRequestedAt >= context.initiatedAt && admission.requestRequestedAt <= createdAt &&
            createdAt >= admission.decidedAt && createdAt < admission.expiresAt
        ) { "Durable Agent admission evidence is not valid for the restored run." }
        require(budget.allows(usage)) { "Durable Agent usage exceeds its assigned budget." }
        require(this.messages.map { it.id }.toSet().size == this.messages.size) {
            "Durable Agent message identifiers must be unique."
        }
        this.messages.forEach(AgentMessage::requireBindingIntact)
        require(this.steps.map { it.stepId }.toSet().size == this.steps.size) {
            "Durable Agent step identifiers must be unique."
        }
        require(this.checkpoints.map { it.checkpointId }.toSet().size == this.checkpoints.size) {
            "Durable Agent checkpoint identifiers must be unique."
        }
        require(this.incidents.map { it.incidentId }.toSet().size == this.incidents.size) {
            "Durable Agent incident identifiers must be unique."
        }
        require(currentStepId == null || this.steps.any { it.stepId == currentStepId }) {
            "Durable Agent current step is missing from its step ledger."
        }
        require(pendingOperation == null || pendingOperation.stepId == currentStepId) {
            "Durable Agent pending operation does not match its current step."
        }
        val stepsById = this.steps.associateBy { step -> step.stepId }
        this.checkpoints.forEach { checkpoint ->
            val step = stepsById[checkpoint.stepId]
            require(checkpoint.runId == runId && checkpoint.tenantId == tenantId && step != null &&
                checkpoint.operationId == step.operationId
            ) { "Durable Agent checkpoint does not match its run or step ledger." }
        }
        require(checkpointSequence == this.checkpoints.size.toLong() &&
            this.checkpoints.withIndex().all { (index, checkpoint) ->
                checkpoint.checkpointSequence == index.toLong() + 1L
            }
        ) { "Durable Agent checkpoint sequence is not contiguous." }
        pendingOperation?.let { pending ->
            val step = requireNotNull(stepsById[pending.stepId]) {
                "Durable Agent pending step is missing from its step ledger."
            }
            require(step.operationId == pending.operationId &&
                (pending is AgentPendingModelOperation) == (step.kind == AgentRuntimeStepKind.MODEL)
            ) { "Durable Agent pending operation does not match its step kind or operation." }
            val checkpoint = this.checkpoints.firstOrNull { it.checkpointId == pending.checkpointId }
            require(checkpoint != null && checkpoint.stepId == pending.stepId &&
                checkpoint.operationId == pending.operationId &&
                (checkpoint.operationDigest == pending.operationDigest || pending.requiresReconciliation())
            ) { "Durable Agent pending operation does not match its checkpoint evidence." }
            if (pending.claimedLeaseId != null &&
                !(pending is AgentPendingModelOperation && pending.phase == AgentPendingModelPhase.RECONCILIATION_REQUIRED) &&
                !(pending is AgentPendingToolOperation && pending.phase == AgentPendingToolPhase.RECONCILIATION_REQUIRED)
            ) {
                require(lease != null) {
                    "Durable Agent pending claim requires retained fencing evidence."
                }
            }
        }
        this.incidents.forEach { incident ->
            require(incident.runId == runId && incident.tenantId == tenantId &&
                (incident.stepId == null || incident.stepId in stepsById)
            ) { "Durable Agent incident does not match its run or step ledger." }
        }
        if (lease != null) {
            require(lease.acquiredAt <= updatedAt && updatedAt < lease.expiresAt) {
                "Durable Agent state is outside its retained lease lifetime."
            }
        }
        require(!status.isTerminal() || currentStepId == null && pendingOperation == null) {
            "Terminal durable Agent runs cannot retain active work."
        }
        require(!status.isTerminal() || lease == null) { "Terminal durable Agent runs cannot retain a lease." }
        require((status == AgentRunStatus.FAILED) == (failure != null)) {
            "Durable Agent failure detail must match FAILED status."
        }
        require(status != AgentRunStatus.WAITING_APPROVAL ||
            pendingOperation is AgentPendingToolOperation &&
            pendingOperation.phase == AgentPendingToolPhase.WAITING_APPROVAL
        ) { "WAITING_APPROVAL requires an approval-bound pending tool operation." }
        require(status != AgentRunStatus.WAITING_TOOL ||
            pendingOperation != null &&
            ((pendingOperation is AgentPendingModelOperation &&
                pendingOperation.phase == AgentPendingModelPhase.RECONCILIATION_REQUIRED) ||
                (pendingOperation is AgentPendingToolOperation &&
                    pendingOperation.phase == AgentPendingToolPhase.RECONCILIATION_REQUIRED)) &&
            this.incidents.any { it.status == AgentRuntimeIncidentStatus.OPEN && it.stepId == pendingOperation.stepId }
        ) { "WAITING_TOOL requires pending reconciliation and an open incident." }
        require((status != AgentRunStatus.WAITING_APPROVAL && status != AgentRunStatus.WAITING_TOOL) || lease == null) {
            "Waiting durable Agent runs cannot retain a worker lease."
        }
    }

    fun snapshot(): AgentRunSnapshot = AgentRunSnapshot(
        runId,
        tenantId,
        capabilityId,
        status,
        messages,
        budget,
        usage,
        stateVersion,
        createdAt,
        updatedAt,
        currentStepId,
        failure,
    )

    /** Store-only lease transition. It increments CAS version without producing a business event. */
    fun withClaimedLease(lease: AgentRunLease, atTime: Long): AgentDurableRunState {
        require(atTime >= updatedAt) { "Agent run lease acquisition time moved backwards." }
        require(stateVersion < Long.MAX_VALUE) { "Agent run state version is exhausted." }
        return AgentDurableRunState(
            runId,
            context,
            capabilityId,
            messages,
            budget,
            usage,
            status,
            stateVersion + 1L,
            eventSequence,
            checkpointSequence,
            createdAt,
            atTime,
            deadlineAt,
            idempotencyScope,
            idempotencyReplayDigest,
            admission,
            steps,
            checkpoints,
            currentStepId,
            pendingOperation,
            lease,
            cancellation,
            failure,
            incidents,
        )
    }

    internal fun evolve(
        messages: Collection<AgentMessage> = this.messages,
        usage: AgentUsage = this.usage,
        status: AgentRunStatus = this.status,
        eventSequence: Long = this.eventSequence,
        checkpointSequence: Long = this.checkpointSequence,
        updatedAt: Long,
        steps: Collection<AgentRuntimeStep> = this.steps,
        checkpoints: Collection<AgentRuntimeCheckpoint> = this.checkpoints,
        currentStepId: Identifier? = this.currentStepId,
        pendingOperation: AgentPendingOperation? = this.pendingOperation,
        lease: AgentRunLease? = this.lease,
        cancellation: AgentCancellation? = this.cancellation,
        failure: AgentRunFailure? = this.failure,
        incidents: Collection<AgentRuntimeIncident> = this.incidents,
    ): AgentDurableRunState {
        require(updatedAt >= this.updatedAt) { "Durable Agent update time moved backwards." }
        require(stateVersion < Long.MAX_VALUE) { "Agent run state version is exhausted." }
        return AgentDurableRunState(
            runId,
            context,
            capabilityId,
            messages,
            budget,
            usage,
            status,
            stateVersion + 1L,
            eventSequence,
            checkpointSequence,
            createdAt,
            updatedAt,
            deadlineAt,
            idempotencyScope,
            idempotencyReplayDigest,
            admission,
            steps,
            checkpoints,
            currentStepId,
            pendingOperation,
            lease,
            cancellation,
            failure,
            incidents,
        )
    }

    override fun toString(): String = "AgentDurableRunState(status=$status, stateVersion=$stateVersion)"

    companion object {
        @JvmStatic
        fun initial(
            runId: Identifier,
            request: AgentRunRequest,
            admissionRequest: AgentRunAdmissionRequest,
            admission: AgentRunAdmissionDecision,
            createdAt: Long,
        ): AgentDurableRunState {
            admission.requireAllowedFor(admissionRequest, createdAt)
            return AgentDurableRunState(
                runId,
                request.context,
                request.capabilityId,
                request.messages,
                request.budget,
                AgentUsage(),
                AgentRunStatus.QUEUED,
                0L,
                1L,
                0L,
                createdAt,
                createdAt,
                request.deadlineAt,
                admissionRequest.scope,
                runtimeIdempotencyReplayDigest(request, admissionRequest.scope),
                admission,
                emptyList(),
                emptyList(),
                null,
                null,
                null,
                null,
                null,
                emptyList(),
            )
        }

        @JvmStatic
        @JvmOverloads
        fun restore(
            runId: Identifier,
            context: AgentRunContext,
            capabilityId: AgentCapabilityId,
            messages: Collection<AgentMessage>,
            budget: AgentBudget,
            usage: AgentUsage,
            status: AgentRunStatus,
            stateVersion: Long,
            eventSequence: Long,
            checkpointSequence: Long,
            createdAt: Long,
            updatedAt: Long,
            deadlineAt: Long,
            idempotencyScope: AgentRunIdempotencyScope,
            admission: AgentRunAdmissionDecision,
            steps: Collection<AgentRuntimeStep>,
            checkpoints: Collection<AgentRuntimeCheckpoint>,
            currentStepId: Identifier?,
            pendingOperation: AgentPendingOperation?,
            lease: AgentRunLease?,
            cancellation: AgentCancellation?,
            failure: AgentRunFailure?,
            incidents: Collection<AgentRuntimeIncident>,
            idempotencyReplayDigest: String = runtimeLegacyIdempotencyReplayDigest(admission.bindingDigest),
        ): AgentDurableRunState = AgentDurableRunState(
            runId,
            context,
            capabilityId,
            messages,
            budget,
            usage,
            status,
            stateVersion,
            eventSequence,
            checkpointSequence,
            createdAt,
            updatedAt,
            deadlineAt,
            idempotencyScope,
            idempotencyReplayDigest,
            admission,
            steps,
            checkpoints,
            currentStepId,
            pendingOperation,
            lease,
            cancellation,
            failure,
            incidents,
        )
    }
}

private fun AgentPendingOperation.requiresReconciliation(): Boolean =
    this is AgentPendingModelOperation && phase == AgentPendingModelPhase.RECONCILIATION_REQUIRED ||
        this is AgentPendingToolOperation && phase == AgentPendingToolPhase.RECONCILIATION_REQUIRED

class AgentRuntimeCheckpointEvent(
    override val runId: Identifier,
    override val tenantId: Identifier,
    override val sequence: Long,
    override val occurredAt: Long,
    val checkpoint: AgentRuntimeCheckpoint,
) : AgentRunEvent {
    init {
        requireRuntimeIdentifier(runId, "Agent checkpoint event run identifier is invalid.")
        requireRuntimeIdentifier(tenantId, "Agent checkpoint event tenant identifier is invalid.")
        require(sequence > 0L && occurredAt >= 0L) { "Agent checkpoint event sequence or time is invalid." }
        require(checkpoint.runId == runId && checkpoint.tenantId == tenantId) {
            "Agent checkpoint event does not match its checkpoint."
        }
    }
}

class AgentRuntimeIncidentEvent(
    override val runId: Identifier,
    override val tenantId: Identifier,
    override val sequence: Long,
    override val occurredAt: Long,
    val incident: AgentRuntimeIncident,
) : AgentRunEvent {
    init {
        requireRuntimeIdentifier(runId, "Agent incident event run identifier is invalid.")
        requireRuntimeIdentifier(tenantId, "Agent incident event tenant identifier is invalid.")
        require(sequence > 0L && occurredAt >= 0L) { "Agent incident event sequence or time is invalid." }
        require(incident.runId == runId && incident.tenantId == tenantId) {
            "Agent incident event does not match its incident."
        }
    }
}
