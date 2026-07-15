package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier
import java.util.concurrent.CompletionStage

/** Closed lifecycle states persisted by the Agent runtime. */
enum class AgentRunStatus {
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_TOOL,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED,
    ;

    fun isTerminal(): Boolean = this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED

    fun canTransitionTo(next: AgentRunStatus): Boolean = when (this) {
        QUEUED -> next == RUNNING || next == FAILED || next == CANCELLED || next == EXPIRED
        RUNNING -> next == WAITING_APPROVAL || next == WAITING_TOOL || next.isTerminal()
        WAITING_APPROVAL, WAITING_TOOL ->
            next == RUNNING || next == FAILED || next == CANCELLED || next == EXPIRED
        COMPLETED, FAILED, CANCELLED, EXPIRED -> false
    }
}

/** Stable, extensible failure category. Unknown provider categories remain data, not enum crashes. */
class AgentFailureCategory(value: String) {
    val value: String = requireAgentCode(value, "Agent failure category is invalid.")

    override fun equals(other: Any?): Boolean = other is AgentFailureCategory && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        @JvmField val RETRYABLE = AgentFailureCategory("RETRYABLE")
        @JvmField val PERMANENT = AgentFailureCategory("PERMANENT")
        @JvmField val RATE_LIMITED = AgentFailureCategory("RATE_LIMITED")
        @JvmField val AUTHENTICATION = AgentFailureCategory("AUTHENTICATION")
        @JvmField val AUTHORIZATION = AgentFailureCategory("AUTHORIZATION")
        @JvmField val QUOTA = AgentFailureCategory("QUOTA")
        @JvmField val CANCELLED = AgentFailureCategory("CANCELLED")
        @JvmField val PROTOCOL = AgentFailureCategory("PROTOCOL")
    }
}

/** Failure detail contains a safe code/message only; raw provider payloads are forbidden. */
class AgentRunFailure @JvmOverloads constructor(
    val category: AgentFailureCategory,
    code: String,
    safeMessage: String? = null,
) {
    val code: String = requireAgentCode(code, "Agent failure code is invalid.")
    val safeMessage: String? = requireOptionalAgentContent(
        safeMessage,
        AgentContractLimits.MAX_DESCRIPTION_CODE_POINTS,
        "Agent failure message is invalid.",
    )
}

/** Trusted caller context. Tenant and principal values must be derived before entering Agent code. */
class AgentRunContext @JvmOverloads constructor(
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    requestId: Identifier,
    val initiatedAt: Long,
    locale: String? = null,
) {
    val tenantId: Identifier = requireOpaqueIdentifier(tenantId, "Agent tenant identifier is invalid.")
    val principalId: Identifier = requireOpaqueIdentifier(principalId, "Agent principal identifier is invalid.")
    val principalType: String = requireAgentCode(principalType, "Agent principal type is invalid.")
    val requestId: Identifier = requireOpaqueIdentifier(requestId, "Agent request identifier is invalid.")
    val locale: String? = locale?.let { requireStableAgentId(it, "Agent locale is invalid.") }

    init {
        requireNonNegativeTime(initiatedAt, "Agent initiation time must not be negative.")
    }
}

/** Immutable request to create one durable Agent run. */
class AgentRunRequest(
    val context: AgentRunContext,
    val capabilityId: AgentCapabilityId,
    messages: List<AgentMessage>,
    val budget: AgentBudget,
    idempotencyKey: String,
    val deadlineAt: Long,
    val cancellationToken: AgentCancellationToken,
) {
    val messages: List<AgentMessage>
    val bindingDigest: String
    val idempotencyKey: String = requireAgentToken(
        idempotencyKey,
        AgentContractLimits.MAX_IDEMPOTENCY_KEY_CODE_POINTS,
        "Agent run idempotency key is invalid.",
    )

    init {
        val messageSnapshot = immutableAgentList(messages)
        require(messageSnapshot.isNotEmpty()) { "Agent run requires at least one message." }
        require(messageSnapshot.size <= AgentContractLimits.MAX_MESSAGES) { "Agent run contains too many messages." }
        require(messageSnapshot.map { message -> message.id }.distinct().size == messageSnapshot.size) {
            "Agent run message identifiers must be unique."
        }
        messageSnapshot.forEach(AgentMessage::requireBindingIntact)
        require(deadlineAt > context.initiatedAt) { "Agent run deadline must follow its initiation time." }
        require(
            budget.maximumDurationMillis > Long.MAX_VALUE - context.initiatedAt ||
                deadlineAt <= context.initiatedAt + budget.maximumDurationMillis,
        ) { "Agent run deadline exceeds its duration budget." }
        this.messages = messageSnapshot
        val digest = AgentDigestBuilder("flowweft.agent.run-request.v1")
            .add(context.tenantId.value)
            .add(context.principalType)
            .add(context.principalId.value)
            .add(context.requestId.value)
            .add(context.initiatedAt)
            .add(context.locale ?: "-")
            .add(capabilityId.value)
            .add(messageSnapshot.size)
        messageSnapshot.forEach { message -> digest.add(message.bindingDigest) }
        bindingDigest = digest
            .add(budget.maximumInputTokens)
            .add(budget.maximumOutputTokens)
            .add(budget.maximumModelCalls)
            .add(budget.maximumToolCalls)
            .add(budget.maximumDurationMillis)
            .add(budget.maximumCostMicros)
            .add(idempotencyKey)
            .add(deadlineAt)
            .finish()
    }

    /** Detects mutable open content extensions before admission or provider dispatch. */
    fun requireBindingIntact() {
        messages.forEach(AgentMessage::requireBindingIntact)
        val current = AgentDigestBuilder("flowweft.agent.run-request.v1")
            .add(context.tenantId.value)
            .add(context.principalType)
            .add(context.principalId.value)
            .add(context.requestId.value)
            .add(context.initiatedAt)
            .add(context.locale ?: "-")
            .add(capabilityId.value)
            .add(messages.size)
        messages.forEach { message -> current.add(message.bindingDigest) }
        require(
            current
                .add(budget.maximumInputTokens)
                .add(budget.maximumOutputTokens)
                .add(budget.maximumModelCalls)
                .add(budget.maximumToolCalls)
                .add(budget.maximumDurationMillis)
                .add(budget.maximumCostMicros)
                .add(idempotencyKey)
                .add(deadlineAt)
                .finish() == bindingDigest,
        ) { "Agent run request content binding changed." }
    }
}

/** Durable state projection suitable for polling and recovery. */
class AgentRunSnapshot @JvmOverloads constructor(
    runId: Identifier,
    tenantId: Identifier,
    val capabilityId: AgentCapabilityId,
    val status: AgentRunStatus,
    messages: List<AgentMessage>,
    val budget: AgentBudget,
    val usage: AgentUsage,
    val stateVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
    currentStepId: Identifier? = null,
    val failure: AgentRunFailure? = null,
) {
    val runId: Identifier = requireOpaqueIdentifier(runId, "Agent run identifier is invalid.")
    val tenantId: Identifier = requireOpaqueIdentifier(tenantId, "Agent run tenant identifier is invalid.")
    val messages: List<AgentMessage>
    val currentStepId: Identifier? = currentStepId?.let {
        requireOpaqueIdentifier(it, "Agent run step identifier is invalid.")
    }

    init {
        val messageSnapshot = immutableAgentList(messages)
        require(messageSnapshot.size <= AgentContractLimits.MAX_MESSAGES) {
            "Agent run snapshot contains too many messages."
        }
        require(messageSnapshot.map { message -> message.id }.distinct().size == messageSnapshot.size) {
            "Agent run snapshot message identifiers must be unique."
        }
        messageSnapshot.forEach(AgentMessage::requireBindingIntact)
        require(stateVersion >= 0) { "Agent run state version must not be negative." }
        requireNonNegativeTime(createdAt, "Agent run creation time must not be negative.")
        require(updatedAt >= createdAt) { "Agent run update time must not precede creation time." }
        require(status != AgentRunStatus.FAILED || failure != null) { "Failed Agent runs require failure detail." }
        require(status == AgentRunStatus.FAILED || failure == null) {
            "Only failed Agent runs may contain failure detail."
        }
        require(
            (status != AgentRunStatus.WAITING_APPROVAL && status != AgentRunStatus.WAITING_TOOL) ||
                this.currentStepId != null,
        ) { "Waiting Agent runs require a current step identifier." }
        require(!status.isTerminal() || this.currentStepId == null) {
            "Terminal Agent runs must not retain a current step identifier."
        }
        this.messages = messageSnapshot
    }
}

/** Open event boundary; runtimes may add immutable event implementations without closing the ABI. */
interface AgentRunEvent {
    val runId: Identifier
    val tenantId: Identifier
    val sequence: Long
    val occurredAt: Long
}

class AgentRunStatusChangedEvent @JvmOverloads constructor(
    runId: Identifier,
    tenantId: Identifier,
    override val sequence: Long,
    override val occurredAt: Long,
    val previousStatus: AgentRunStatus?,
    val currentStatus: AgentRunStatus,
    reasonCode: String? = null,
) : AgentRunEvent {
    override val runId: Identifier = requireOpaqueIdentifier(runId, "Agent event run identifier is invalid.")
    override val tenantId: Identifier = requireOpaqueIdentifier(tenantId, "Agent event tenant identifier is invalid.")
    val reasonCode: String? = reasonCode?.let { requireAgentCode(it, "Agent status reason code is invalid.") }

    init {
        requirePositiveSequence(sequence, "Agent event sequence must be positive.")
        requireNonNegativeTime(occurredAt, "Agent event time must not be negative.")
        require(previousStatus != null || currentStatus == AgentRunStatus.QUEUED) {
            "The initial Agent status event must be QUEUED."
        }
        require(previousStatus == null || previousStatus.canTransitionTo(currentStatus)) {
            "Agent status transition is invalid."
        }
    }
}

class AgentRunMessageEvent(
    runId: Identifier,
    tenantId: Identifier,
    override val sequence: Long,
    override val occurredAt: Long,
    val message: AgentMessage,
) : AgentRunEvent {
    override val runId: Identifier = requireOpaqueIdentifier(runId, "Agent event run identifier is invalid.")
    override val tenantId: Identifier = requireOpaqueIdentifier(tenantId, "Agent event tenant identifier is invalid.")

    init {
        requirePositiveSequence(sequence, "Agent event sequence must be positive.")
        requireNonNegativeTime(occurredAt, "Agent event time must not be negative.")
        message.requireBindingIntact()
    }
}

class AgentRunUsageEvent(
    runId: Identifier,
    tenantId: Identifier,
    override val sequence: Long,
    override val occurredAt: Long,
    val cumulativeUsage: AgentUsage,
) : AgentRunEvent {
    override val runId: Identifier = requireOpaqueIdentifier(runId, "Agent event run identifier is invalid.")
    override val tenantId: Identifier = requireOpaqueIdentifier(tenantId, "Agent event tenant identifier is invalid.")

    init {
        requirePositiveSequence(sequence, "Agent event sequence must be positive.")
        requireNonNegativeTime(occurredAt, "Agent event time must not be negative.")
    }
}

class AgentRunApprovalRequiredEvent(
    runId: Identifier,
    tenantId: Identifier,
    override val sequence: Long,
    override val occurredAt: Long,
    val approvalRequest: AgentApprovalRequest,
) : AgentRunEvent {
    override val runId: Identifier = requireOpaqueIdentifier(runId, "Agent event run identifier is invalid.")
    override val tenantId: Identifier = requireOpaqueIdentifier(tenantId, "Agent event tenant identifier is invalid.")

    init {
        requirePositiveSequence(sequence, "Agent event sequence must be positive.")
        requireNonNegativeTime(occurredAt, "Agent event time must not be negative.")
        require(approvalRequest.runId == this.runId && approvalRequest.tenantId == this.tenantId) {
            "Agent approval event must match its run and tenant."
        }
    }
}

interface AgentRunObserver {
    fun onEvent(event: AgentRunEvent)

    companion object {
        @JvmField
        val NOOP: AgentRunObserver = object : AgentRunObserver {
            override fun onEvent(event: AgentRunEvent) = Unit
        }
    }
}

/** Handle for one durable run. Cancellation is explicit and completion is never represented by a null. */
interface AgentRunCall {
    fun runId(): Identifier

    fun completion(): CompletionStage<AgentRunSnapshot>

    fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean>
}

interface AgentRunService {
    fun start(request: AgentRunRequest, observer: AgentRunObserver): AgentRunCall
}
