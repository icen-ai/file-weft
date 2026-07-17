package ai.icen.fw.workflow.domain

/**
 * Atomic command result. Runtime persists a successful state, ordered events, effect intents and
 * applied idempotency evidence in one transaction before dispatching effects.
 */
class WorkflowDomainResult private constructor(
    val code: WorkflowResultCode,
    val commandCode: WorkflowCommandCode,
    commandDigest: String,
    idempotencyKey: String,
    val state: WorkflowInstanceState?,
    events: Collection<WorkflowDomainEvent>,
    effects: Collection<WorkflowEffectIntent>,
    failureCode: String?,
) {
    val commandDigest: String = WorkflowDomainSupport.requireSha256(
        commandDigest,
        "Workflow result command digest is invalid.",
    )
    val idempotencyKey: String = WorkflowDomainSupport.requireText(
        idempotencyKey,
        WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
        "Workflow result idempotency key is invalid.",
    )
    val events: List<WorkflowDomainEvent> = WorkflowDomainSupport.immutableList(
        events,
        WorkflowDomainSupport.MAX_STATE_ITEMS,
        "Workflow result events are invalid or exceed the limit.",
    )
    val effects: List<WorkflowEffectIntent> = WorkflowDomainSupport.immutableList(
        effects,
        WorkflowDomainSupport.MAX_STATE_ITEMS,
        "Workflow result effects are invalid or exceed the limit.",
    )
    val failureCode: String? = failureCode?.let { value ->
        WorkflowDomainSupport.requireCode(value, "Workflow result failure code is invalid.")
    }

    init {
        when (code) {
            WorkflowResultCode.APPLIED -> require(state != null && this.failureCode == null) {
                "Applied workflow results require state and no failure."
            }

            WorkflowResultCode.BUDGET_EXHAUSTED -> require(
                state != null && this.failureCode == null &&
                    this.effects.any { effect -> effect.code == WorkflowEffectCode.CONTINUE_EXECUTION },
            ) { "Budget-exhausted workflow results require a continuation intent." }

            WorkflowResultCode.INCIDENT -> require(
                state?.status == WorkflowInstanceStatus.INCIDENT && this.failureCode != null,
            ) { "Incident workflow results require incident state and a failure code." }

            WorkflowResultCode.REPLAYED,
            WorkflowResultCode.VERSION_CONFLICT -> require(
                state != null && this.events.isEmpty() && this.effects.isEmpty() && this.failureCode != null,
            ) { "Replay and version-conflict results cannot emit new effects or events." }

            WorkflowResultCode.REJECTED -> require(
                this.events.isEmpty() && this.effects.isEmpty() && this.failureCode != null,
            ) { "Rejected workflow results cannot emit effects or events." }

            else -> throw IllegalArgumentException("Unknown workflow result code is unsupported.")
        }
    }

    override fun toString(): String = "WorkflowDomainResult(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            code: WorkflowResultCode,
            commandCode: WorkflowCommandCode,
            commandDigest: String,
            idempotencyKey: String,
            state: WorkflowInstanceState?,
            events: Collection<WorkflowDomainEvent>,
            effects: Collection<WorkflowEffectIntent>,
            failureCode: String?,
        ): WorkflowDomainResult = WorkflowDomainResult(
            code,
            commandCode,
            commandDigest,
            idempotencyKey,
            state,
            events,
            effects,
            failureCode,
        )
    }
}
