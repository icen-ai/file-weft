package ai.icen.fw.workflow.domain

/** Stable command discriminator; unknown codes are representable but never executed implicitly. */
class WorkflowCommandCode private constructor(code: String) {
    val code: String = WorkflowDomainSupport.requireCode(code, "Workflow command code is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowCommandCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowCommandCode(<redacted>)"

    companion object {
        @JvmField val START_INSTANCE = WorkflowCommandCode("start-instance")
        @JvmField val ACTIVATE_HUMAN_RULE = WorkflowCommandCode("activate-human-rule")
        @JvmField val DECIDE_HUMAN_TASK = WorkflowCommandCode("decide-human-task")
        @JvmField val COLLABORATE_HUMAN_TASK = WorkflowCommandCode("collaborate-human-task")
        @JvmField val COMPLETE_EFFECT = WorkflowCommandCode("complete-effect")
        @JvmField val CONTINUE_EXECUTION = WorkflowCommandCode("continue-execution")

        @JvmStatic fun of(code: String): WorkflowCommandCode = when (code) {
            START_INSTANCE.code -> START_INSTANCE
            ACTIVATE_HUMAN_RULE.code -> ACTIVATE_HUMAN_RULE
            DECIDE_HUMAN_TASK.code -> DECIDE_HUMAN_TASK
            COLLABORATE_HUMAN_TASK.code -> COLLABORATE_HUMAN_TASK
            COMPLETE_EFFECT.code -> COMPLETE_EFFECT
            CONTINUE_EXECUTION.code -> CONTINUE_EXECUTION
            else -> WorkflowCommandCode(code)
        }
    }
}

/** Stable event discriminator. Event payloads are digest-bound and never arbitrary maps. */
class WorkflowEventCode private constructor(code: String) {
    val code: String = WorkflowDomainSupport.requireCode(code, "Workflow event code is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowEventCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowEventCode(<redacted>)"

    companion object {
        @JvmField val INSTANCE_STARTED = WorkflowEventCode("instance-started")
        @JvmField val NODE_ENTERED = WorkflowEventCode("node-entered")
        @JvmField val NODE_COMPLETED = WorkflowEventCode("node-completed")
        @JvmField val TOKEN_MOVED = WorkflowEventCode("token-moved")
        @JvmField val TOKEN_SPLIT = WorkflowEventCode("token-split")
        @JvmField val TOKEN_JOINED = WorkflowEventCode("token-joined")
        @JvmField val HUMAN_WORK_ITEM_CREATED = WorkflowEventCode("human-work-item-created")
        @JvmField val HUMAN_RULE_ACTIVATED = WorkflowEventCode("human-rule-activated")
        @JvmField val HUMAN_DECISION_RECORDED = WorkflowEventCode("human-decision-recorded")
        @JvmField val HUMAN_TASK_CLAIMED = WorkflowEventCode("human-task-claimed")
        @JvmField val HUMAN_TASK_UNCLAIMED = WorkflowEventCode("human-task-unclaimed")
        @JvmField val HUMAN_TASK_DELEGATED = WorkflowEventCode("human-task-delegated")
        @JvmField val HUMAN_TASK_TRANSFERRED = WorkflowEventCode("human-task-transferred")
        @JvmField val HUMAN_TASK_ADD_SIGNED = WorkflowEventCode("human-task-add-signed")
        @JvmField val HUMAN_TASK_RETURNED = WorkflowEventCode("human-task-returned")
        @JvmField val HUMAN_WORK_ITEM_COMPLETED = WorkflowEventCode("human-work-item-completed")
        @JvmField val EFFECT_REQUESTED = WorkflowEventCode("effect-requested")
        @JvmField val EFFECT_COMPLETED = WorkflowEventCode("effect-completed")
        @JvmField val CONTINUATION_REQUESTED = WorkflowEventCode("continuation-requested")
        @JvmField val INSTANCE_COMPLETED = WorkflowEventCode("instance-completed")
        @JvmField val INCIDENT_RAISED = WorkflowEventCode("incident-raised")

        @JvmStatic fun of(code: String): WorkflowEventCode = builtIns.firstOrNull { it.code == code }
            ?: WorkflowEventCode(code)

        private val builtIns = listOf(
            INSTANCE_STARTED,
            NODE_ENTERED,
            NODE_COMPLETED,
            TOKEN_MOVED,
            TOKEN_SPLIT,
            TOKEN_JOINED,
            HUMAN_WORK_ITEM_CREATED,
            HUMAN_RULE_ACTIVATED,
            HUMAN_DECISION_RECORDED,
            HUMAN_TASK_CLAIMED,
            HUMAN_TASK_UNCLAIMED,
            HUMAN_TASK_DELEGATED,
            HUMAN_TASK_TRANSFERRED,
            HUMAN_TASK_ADD_SIGNED,
            HUMAN_TASK_RETURNED,
            HUMAN_WORK_ITEM_COMPLETED,
            EFFECT_REQUESTED,
            EFFECT_COMPLETED,
            CONTINUATION_REQUESTED,
            INSTANCE_COMPLETED,
            INCIDENT_RAISED,
        )
    }
}

/** Durable intent discriminator. Domain code only emits these values; it never performs them. */
class WorkflowEffectCode private constructor(code: String) {
    val code: String = WorkflowDomainSupport.requireCode(code, "Workflow effect code is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowEffectCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowEffectCode(<redacted>)"

    companion object {
        @JvmField val PARTICIPANT_RESOLUTION = WorkflowEffectCode("participant-resolution")
        @JvmField val EXCLUSIVE_EVALUATION = WorkflowEffectCode("exclusive-evaluation")
        @JvmField val SERVICE_TASK = WorkflowEffectCode("service-task")
        @JvmField val DECISION_TASK = WorkflowEffectCode("decision-task")
        @JvmField val TIMER_WAIT = WorkflowEffectCode("timer-wait")
        @JvmField val SUBPROCESS = WorkflowEffectCode("subprocess")
        @JvmField val EXTENSION = WorkflowEffectCode("extension")
        @JvmField val CONTINUE_EXECUTION = WorkflowEffectCode("continue-execution")

        @JvmStatic fun of(code: String): WorkflowEffectCode = when (code) {
            PARTICIPANT_RESOLUTION.code -> PARTICIPANT_RESOLUTION
            EXCLUSIVE_EVALUATION.code -> EXCLUSIVE_EVALUATION
            SERVICE_TASK.code -> SERVICE_TASK
            DECISION_TASK.code -> DECISION_TASK
            TIMER_WAIT.code -> TIMER_WAIT
            SUBPROCESS.code -> SUBPROCESS
            EXTENSION.code -> EXTENSION
            CONTINUE_EXECUTION.code -> CONTINUE_EXECUTION
            else -> WorkflowEffectCode(code)
        }
    }
}

/** Stable outcome of applying one domain command. */
class WorkflowResultCode private constructor(code: String) {
    val code: String = WorkflowDomainSupport.requireCode(code, "Workflow result code is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowResultCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowResultCode(<redacted>)"

    companion object {
        @JvmField val APPLIED = WorkflowResultCode("applied")
        @JvmField val REPLAYED = WorkflowResultCode("replayed")
        @JvmField val REJECTED = WorkflowResultCode("rejected")
        @JvmField val VERSION_CONFLICT = WorkflowResultCode("version-conflict")
        @JvmField val INCIDENT = WorkflowResultCode("incident")
        @JvmField val BUDGET_EXHAUSTED = WorkflowResultCode("budget-exhausted")
    }
}

class WorkflowInstanceStatus private constructor(code: String) {
    val code: String = WorkflowDomainSupport.requireCode(code, "Workflow instance status is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowInstanceStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowInstanceStatus(<redacted>)"

    companion object {
        @JvmField val RUNNING = WorkflowInstanceStatus("running")
        @JvmField val WAITING = WorkflowInstanceStatus("waiting")
        @JvmField val COMPLETED = WorkflowInstanceStatus("completed")
        @JvmField val INCIDENT = WorkflowInstanceStatus("incident")

        @JvmStatic fun of(code: String): WorkflowInstanceStatus = when (code) {
            RUNNING.code -> RUNNING
            WAITING.code -> WAITING
            COMPLETED.code -> COMPLETED
            INCIDENT.code -> INCIDENT
            else -> WorkflowInstanceStatus(code)
        }
    }
}

class WorkflowTokenStatus private constructor(code: String) {
    val code: String = WorkflowDomainSupport.requireCode(code, "Workflow token status is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowTokenStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowTokenStatus(<redacted>)"

    companion object {
        @JvmField val ACTIVE = WorkflowTokenStatus("active")
        @JvmField val WAITING_EFFECT = WorkflowTokenStatus("waiting-effect")
        @JvmField val WAITING_HUMAN = WorkflowTokenStatus("waiting-human")
        @JvmField val WAITING_JOIN = WorkflowTokenStatus("waiting-join")
        @JvmField val COMPLETED = WorkflowTokenStatus("completed")
        @JvmField val CONSUMED = WorkflowTokenStatus("consumed")

        @JvmStatic fun of(code: String): WorkflowTokenStatus = when (code) {
            ACTIVE.code -> ACTIVE
            WAITING_EFFECT.code -> WAITING_EFFECT
            WAITING_HUMAN.code -> WAITING_HUMAN
            WAITING_JOIN.code -> WAITING_JOIN
            COMPLETED.code -> COMPLETED
            CONSUMED.code -> CONSUMED
            else -> WorkflowTokenStatus(code)
        }
    }
}

class WorkflowNodeExecutionStatus private constructor(code: String) {
    val code: String = WorkflowDomainSupport.requireCode(code, "Workflow node execution status is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowNodeExecutionStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowNodeExecutionStatus(<redacted>)"

    companion object {
        @JvmField val ACTIVE = WorkflowNodeExecutionStatus("active")
        @JvmField val WAITING = WorkflowNodeExecutionStatus("waiting")
        @JvmField val COMPLETED = WorkflowNodeExecutionStatus("completed")
        @JvmField val INCIDENT = WorkflowNodeExecutionStatus("incident")

        @JvmStatic fun of(code: String): WorkflowNodeExecutionStatus = when (code) {
            ACTIVE.code -> ACTIVE
            WAITING.code -> WAITING
            COMPLETED.code -> COMPLETED
            INCIDENT.code -> INCIDENT
            else -> WorkflowNodeExecutionStatus(code)
        }
    }
}

class WorkflowHumanWorkItemStatus private constructor(code: String) {
    val code: String = WorkflowDomainSupport.requireCode(code, "Workflow human work-item status is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowHumanWorkItemStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowHumanWorkItemStatus(<redacted>)"

    companion object {
        @JvmField val WAITING_PARTICIPANTS = WorkflowHumanWorkItemStatus("waiting-participants")
        @JvmField val ACTIVE = WorkflowHumanWorkItemStatus("active")
        @JvmField val APPROVED = WorkflowHumanWorkItemStatus("approved")
        @JvmField val REJECTED = WorkflowHumanWorkItemStatus("rejected")
        @JvmField val INCIDENT = WorkflowHumanWorkItemStatus("incident")

        @JvmStatic fun of(code: String): WorkflowHumanWorkItemStatus = when (code) {
            WAITING_PARTICIPANTS.code -> WAITING_PARTICIPANTS
            ACTIVE.code -> ACTIVE
            APPROVED.code -> APPROVED
            REJECTED.code -> REJECTED
            INCIDENT.code -> INCIDENT
            else -> WorkflowHumanWorkItemStatus(code)
        }
    }
}

class WorkflowHumanDecisionCode private constructor(code: String) {
    val code: String = WorkflowDomainSupport.requireCode(code, "Workflow human decision code is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowHumanDecisionCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowHumanDecisionCode(<redacted>)"

    companion object {
        @JvmField val APPROVE = WorkflowHumanDecisionCode("approve")
        @JvmField val REJECT = WorkflowHumanDecisionCode("reject")

        @JvmStatic fun of(code: String): WorkflowHumanDecisionCode = when (code) {
            APPROVE.code -> APPROVE
            REJECT.code -> REJECT
            else -> WorkflowHumanDecisionCode(code)
        }
    }
}

class WorkflowEffectOutcomeCode private constructor(code: String) {
    val code: String = WorkflowDomainSupport.requireCode(code, "Workflow effect outcome is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowEffectOutcomeCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowEffectOutcomeCode(<redacted>)"

    companion object {
        @JvmField val SUCCESS = WorkflowEffectOutcomeCode("success")
        @JvmField val FAILURE = WorkflowEffectOutcomeCode("failure")
    }
}

class WorkflowIdempotencyStatus private constructor(code: String) {
    val code: String = WorkflowDomainSupport.requireCode(code, "Workflow idempotency status is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowIdempotencyStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowIdempotencyStatus(<redacted>)"

    companion object {
        @JvmField val FRESH = WorkflowIdempotencyStatus("fresh")
        @JvmField val APPLIED = WorkflowIdempotencyStatus("applied")
    }
}

class WorkflowAuthorizationStatus private constructor(code: String) {
    val code: String = WorkflowDomainSupport.requireCode(code, "Workflow authorization status is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowAuthorizationStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowAuthorizationStatus(<redacted>)"

    companion object {
        @JvmField val AUTHORIZED = WorkflowAuthorizationStatus("authorized")
        @JvmField val DENIED = WorkflowAuthorizationStatus("denied")
    }
}
