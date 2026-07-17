package ai.icen.fw.workflow.sla

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot

class WorkflowSlaTaskStatus private constructor(code: String) {
    val code: String = slaMachineCode(code, "task status")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowSlaTaskStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowSlaTaskStatus(<redacted>)"

    companion object {
        @JvmField val ACTIVE = WorkflowSlaTaskStatus("active")
        @JvmField val COMPLETED = WorkflowSlaTaskStatus("completed")
        @JvmField val CANCELLED = WorkflowSlaTaskStatus("cancelled")
        @JvmField val SUSPENDED = WorkflowSlaTaskStatus("suspended")
        @JvmField val INCIDENT = WorkflowSlaTaskStatus("incident")

        @JvmStatic
        fun of(code: String): WorkflowSlaTaskStatus = builtIns.firstOrNull { it.code == code }
            ?: WorkflowSlaTaskStatus(code)

        private val builtIns = listOf(ACTIVE, COMPLETED, CANCELLED, SUSPENDED, INCIDENT)
    }
}

/** Authoritative host projection read before and after calendar work and again before an action. */
class WorkflowSlaTaskSnapshot private constructor(
    tenantId: String,
    instanceId: String,
    workItemId: String,
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    nodeId: String,
    val subject: WorkflowSubjectSnapshot,
    val status: WorkflowSlaTaskStatus,
    revision: Long,
    taskDigest: String,
    val activatedAt: Long,
    val observedAt: Long,
) {
    val tenantId: String = slaIdentifier(tenantId, "task tenant id")
    val instanceId: String = slaIdentifier(instanceId, "task instance id")
    val workItemId: String = slaIdentifier(workItemId, "task work-item id")
    val definitionId: String = slaIdentifier(definitionId, "task definition id")
    val nodeId: String = slaMachineCode(nodeId, "task node id")
    val revision: Long = WorkflowSlaSupport.nonNegative(revision, "Workflow SLA task revision is invalid.")
    val taskDigest: String = slaDigest(taskDigest, "task content")
    val snapshotDigest: String

    init {
        require(activatedAt >= 0L && observedAt >= activatedAt) {
            "Workflow SLA task time binding is invalid."
        }
        snapshotDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-task-snapshot-v1")
            .text(this.tenantId)
            .text(this.instanceId)
            .text(this.workItemId)
            .text(this.definitionId)
            .text(definitionRef.key)
            .text(definitionRef.version)
            .text(definitionRef.digest)
            .text(this.nodeId)
            .text(subject.ref.type)
            .text(subject.ref.id)
            .text(subject.revision)
            .text(subject.digest)
            .text(status.code)
            .longValue(this.revision)
            .text(this.taskDigest)
            .longValue(activatedAt)
            .longValue(observedAt)
            .finish()
    }

    fun sameTaskVersion(other: WorkflowSlaTaskSnapshot): Boolean =
        tenantId == other.tenantId && instanceId == other.instanceId && workItemId == other.workItemId &&
            definitionId == other.definitionId && definitionRef == other.definitionRef &&
            nodeId == other.nodeId && subject == other.subject &&
            status == other.status && revision == other.revision && taskDigest == other.taskDigest &&
            activatedAt == other.activatedAt

    fun matchesScheduleBinding(schedule: WorkflowSlaSchedule): Boolean =
        tenantId == schedule.tenantId && instanceId == schedule.task.instanceId &&
            workItemId == schedule.task.workItemId && definitionRef == schedule.policy.definitionRef &&
            nodeId == schedule.policy.nodeId && subject == schedule.task.subject && activatedAt == schedule.task.activatedAt

    override fun toString(): String = "WorkflowSlaTaskSnapshot(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            instanceId: String,
            workItemId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            nodeId: String,
            subject: WorkflowSubjectSnapshot,
            status: WorkflowSlaTaskStatus,
            revision: Long,
            taskDigest: String,
            activatedAt: Long,
            observedAt: Long,
        ): WorkflowSlaTaskSnapshot = WorkflowSlaTaskSnapshot(
            tenantId,
            instanceId,
            workItemId,
            definitionId,
            definitionRef,
            nodeId,
            subject,
            status,
            revision,
            taskDigest,
            activatedAt,
            observedAt,
        )
    }
}

class WorkflowSlaLease private constructor(
    leaseId: String,
    workerId: String,
    val fencingToken: Long,
    val acquiredAt: Long,
    val expiresAt: Long,
) {
    val leaseId: String = slaIdentifier(leaseId, "lease id")
    val workerId: String = slaIdentifier(workerId, "lease worker id")

    init {
        require(fencingToken > 0L && acquiredAt >= 0L && expiresAt > acquiredAt) {
            "Workflow SLA lease is invalid."
        }
    }

    override fun toString(): String = "WorkflowSlaLease(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            leaseId: String,
            workerId: String,
            fencingToken: Long,
            acquiredAt: Long,
            expiresAt: Long,
        ): WorkflowSlaLease = WorkflowSlaLease(leaseId, workerId, fencingToken, acquiredAt, expiresAt)
    }
}

class WorkflowSlaMilestoneStatus private constructor(code: String) {
    val code: String = slaMachineCode(code, "milestone status")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowSlaMilestoneStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowSlaMilestoneStatus(<redacted>)"

    companion object {
        @JvmField val SCHEDULED = WorkflowSlaMilestoneStatus("scheduled")
        @JvmField val LEASED = WorkflowSlaMilestoneStatus("leased")
        @JvmField val ACTION_CALL_STARTED = WorkflowSlaMilestoneStatus("action-call-started")
        @JvmField val RETRY_WAIT = WorkflowSlaMilestoneStatus("retry-wait")
        @JvmField val SUCCEEDED = WorkflowSlaMilestoneStatus("succeeded")
        @JvmField val SUPPRESSED = WorkflowSlaMilestoneStatus("suppressed")
        @JvmField val OUTCOME_UNKNOWN = WorkflowSlaMilestoneStatus("outcome-unknown")
        @JvmField val TERMINAL_FAILURE = WorkflowSlaMilestoneStatus("terminal-failure")

        @JvmStatic
        fun of(code: String): WorkflowSlaMilestoneStatus = builtIns.firstOrNull { it.code == code }
            ?: WorkflowSlaMilestoneStatus(code)

        private val builtIns = listOf(
            SCHEDULED,
            LEASED,
            ACTION_CALL_STARTED,
            RETRY_WAIT,
            SUCCEEDED,
            SUPPRESSED,
            OUTCOME_UNKNOWN,
            TERMINAL_FAILURE,
        )
    }
}

/** One independently fenced durable milestone. */
class WorkflowSlaMilestoneRecord private constructor(
    val policy: WorkflowSlaMilestonePolicy,
    val scheduledFor: Long,
    calendarEvaluationDigest: String,
    val status: WorkflowSlaMilestoneStatus,
    val attempt: Int,
    val lease: WorkflowSlaLease?,
    val nextAttemptAt: Long?,
    actionRequestDigest: String?,
    authorizationEvidenceDigest: String?,
    val actionReceipt: WorkflowSlaActionReceipt?,
    outcomeEvidenceDigest: String?,
    val updatedAt: Long,
) {
    val calendarEvaluationDigest: String = slaDigest(calendarEvaluationDigest, "calendar evaluation")
    val actionRequestDigest: String? = actionRequestDigest?.let { slaDigest(it, "action request") }
    val authorizationEvidenceDigest: String? = authorizationEvidenceDigest?.let {
        slaDigest(it, "milestone authorization evidence")
    }
    val outcomeEvidenceDigest: String? = outcomeEvidenceDigest?.let { slaDigest(it, "milestone outcome evidence") }
    val contentDigest: String

    init {
        require(scheduledFor >= 0L && attempt >= 0 && updatedAt >= 0L) {
            "Workflow SLA milestone counters or time are invalid."
        }
        when (status) {
            WorkflowSlaMilestoneStatus.SCHEDULED -> require(
                attempt == 0 && lease == null && nextAttemptAt == null && this.actionRequestDigest == null &&
                    this.authorizationEvidenceDigest == null && actionReceipt == null &&
                    this.outcomeEvidenceDigest == null,
            ) { "Scheduled Workflow SLA milestone carries execution state." }
            WorkflowSlaMilestoneStatus.LEASED -> require(
                attempt > 0 && lease != null && nextAttemptAt == null && this.actionRequestDigest == null &&
                    this.authorizationEvidenceDigest == null && actionReceipt == null,
            ) { "Leased Workflow SLA milestone is inconsistent." }
            WorkflowSlaMilestoneStatus.ACTION_CALL_STARTED -> require(
                attempt > 0 && lease != null && nextAttemptAt == null && this.actionRequestDigest != null &&
                    this.authorizationEvidenceDigest != null && actionReceipt == null,
            ) { "Checkpointed Workflow SLA milestone is incomplete." }
            WorkflowSlaMilestoneStatus.RETRY_WAIT -> require(
                attempt > 0 && lease == null && nextAttemptAt != null && nextAttemptAt > updatedAt &&
                    this.actionRequestDigest != null &&
                    (actionReceipt == null ||
                        actionReceipt.outcome == WorkflowSlaActionOutcome.NOT_APPLIED_RETRYABLE) &&
                    this.outcomeEvidenceDigest != null,
            ) { "Workflow SLA retry state is incomplete or unsafe." }
            WorkflowSlaMilestoneStatus.SUCCEEDED -> require(
                attempt > 0 && lease == null && nextAttemptAt == null && actionReceipt != null &&
                    actionReceipt.outcome == WorkflowSlaActionOutcome.SUCCEEDED &&
                    this.outcomeEvidenceDigest != null,
            ) { "Succeeded Workflow SLA milestone is incomplete." }
            WorkflowSlaMilestoneStatus.SUPPRESSED -> require(
                lease == null && nextAttemptAt == null && this.outcomeEvidenceDigest != null &&
                    (actionReceipt == null || actionReceipt.outcome == WorkflowSlaActionOutcome.SUPPRESSED),
            ) { "Suppressed Workflow SLA milestone is incomplete." }
            WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN -> require(
                attempt > 0 && lease == null && nextAttemptAt == null && this.actionRequestDigest != null &&
                    this.outcomeEvidenceDigest != null &&
                    (actionReceipt == null || actionReceipt.outcome == WorkflowSlaActionOutcome.OUTCOME_UNKNOWN),
            ) { "Unknown Workflow SLA outcome lacks checkpoint evidence." }
            WorkflowSlaMilestoneStatus.TERMINAL_FAILURE -> require(
                attempt > 0 && lease == null && nextAttemptAt == null && actionReceipt != null &&
                    (actionReceipt.outcome == WorkflowSlaActionOutcome.PERMANENT_FAILURE ||
                        actionReceipt.outcome == WorkflowSlaActionOutcome.NOT_APPLIED_RETRYABLE) &&
                    this.outcomeEvidenceDigest != null,
            ) { "Terminal Workflow SLA failure is incomplete." }
            else -> throw IllegalArgumentException("Unknown Workflow SLA milestone statuses require typed support.")
        }
        contentDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-milestone-record-v1")
            .text(policy.contentDigest)
            .longValue(scheduledFor)
            .text(this.calendarEvaluationDigest)
            .text(status.code)
            .integer(attempt)
            .optional(lease?.leaseId)
            .optional(lease?.workerId)
            .longValue(lease?.fencingToken ?: 0L)
            .longValue(lease?.acquiredAt ?: 0L)
            .longValue(lease?.expiresAt ?: 0L)
            .optional(nextAttemptAt?.toString())
            .optional(this.actionRequestDigest)
            .optional(this.authorizationEvidenceDigest)
            .optional(actionReceipt?.receiptDigest)
            .optional(this.outcomeEvidenceDigest)
            .longValue(updatedAt)
            .finish()
    }

    override fun toString(): String = "WorkflowSlaMilestoneRecord(<redacted>)"

    companion object {
        @JvmStatic
        fun scheduled(
            policy: WorkflowSlaMilestonePolicy,
            scheduledFor: Long,
            calendarEvaluationDigest: String,
            createdAt: Long,
        ): WorkflowSlaMilestoneRecord = WorkflowSlaMilestoneRecord(
            policy,
            scheduledFor,
            calendarEvaluationDigest,
            WorkflowSlaMilestoneStatus.SCHEDULED,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            createdAt,
        )

        @JvmStatic
        fun restore(
            policy: WorkflowSlaMilestonePolicy,
            scheduledFor: Long,
            calendarEvaluationDigest: String,
            status: WorkflowSlaMilestoneStatus,
            attempt: Int,
            lease: WorkflowSlaLease?,
            nextAttemptAt: Long?,
            actionRequestDigest: String?,
            authorizationEvidenceDigest: String?,
            actionReceipt: WorkflowSlaActionReceipt?,
            outcomeEvidenceDigest: String?,
            updatedAt: Long,
        ): WorkflowSlaMilestoneRecord = WorkflowSlaMilestoneRecord(
            policy,
            scheduledFor,
            calendarEvaluationDigest,
            status,
            attempt,
            lease,
            nextAttemptAt,
            actionRequestDigest,
            authorizationEvidenceDigest,
            actionReceipt,
            outcomeEvidenceDigest,
            updatedAt,
        )
    }
}

class WorkflowSlaScheduleStatus private constructor(code: String) {
    val code: String = slaMachineCode(code, "schedule status")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowSlaScheduleStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowSlaScheduleStatus(<redacted>)"

    companion object {
        @JvmField val ACTIVE = WorkflowSlaScheduleStatus("active")
        @JvmField val COMPLETED = WorkflowSlaScheduleStatus("completed")
        @JvmField val SUPPRESSED = WorkflowSlaScheduleStatus("suppressed")
        @JvmField val ATTENTION_REQUIRED = WorkflowSlaScheduleStatus("attention-required")

        @JvmStatic
        fun of(code: String): WorkflowSlaScheduleStatus = builtIns.firstOrNull { it.code == code }
            ?: WorkflowSlaScheduleStatus(code)

        private val builtIns = listOf(ACTIVE, COMPLETED, SUPPRESSED, ATTENTION_REQUIRED)
    }
}

class WorkflowSlaSchedule private constructor(
    tenantId: String,
    scheduleId: String,
    idempotencyKey: String,
    idempotencyBindingDigest: String,
    val policy: WorkflowSlaPolicy,
    val task: WorkflowSlaTaskSnapshot,
    milestones: Collection<WorkflowSlaMilestoneRecord>,
    val status: WorkflowSlaScheduleStatus,
    version: Long,
    prepareAuthorizationEvidenceDigest: String,
    commitAuthorizationEvidenceDigest: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val tenantId: String = slaIdentifier(tenantId, "schedule tenant id")
    val scheduleId: String = slaIdentifier(scheduleId, "schedule id")
    val idempotencyKey: String = slaIdentifier(idempotencyKey, "schedule idempotency key")
    val idempotencyBindingDigest: String = slaDigest(idempotencyBindingDigest, "schedule idempotency binding")
    val milestones: List<WorkflowSlaMilestoneRecord> = WorkflowSlaSupport.immutable(
        milestones,
        8,
        "Workflow SLA milestones are invalid or exceed the limit.",
    )
    val version: Long = WorkflowSlaSupport.nonNegative(version, "Workflow SLA schedule version is invalid.")
    val prepareAuthorizationEvidenceDigest: String = slaDigest(
        prepareAuthorizationEvidenceDigest,
        "prepare authorization evidence",
    )
    val commitAuthorizationEvidenceDigest: String = slaDigest(
        commitAuthorizationEvidenceDigest,
        "commit authorization evidence",
    )
    val contentDigest: String

    init {
        require(this.tenantId == task.tenantId && policy.definitionRef == task.definitionRef &&
            policy.nodeId == task.nodeId && task.status == WorkflowSlaTaskStatus.ACTIVE
        ) { "Workflow SLA schedule task and policy bindings are inconsistent." }
        require(createdAt >= task.activatedAt && updatedAt >= createdAt) {
            "Workflow SLA schedule time binding is invalid."
        }
        require(this.milestones.size == policy.milestones.size &&
            this.milestones.map { it.policy.contentDigest } == policy.milestones.map { it.contentDigest }
        ) { "Workflow SLA schedule milestones do not match the exact policy." }
        require(this.milestones.zipWithNext().all { pair -> pair.first.scheduledFor <= pair.second.scheduledFor }) {
            "Workflow SLA schedule milestone times are not ordered."
        }
        val hasAttention = this.milestones.any {
            it.status == WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN ||
                it.status == WorkflowSlaMilestoneStatus.TERMINAL_FAILURE
        }
        val allSuppressed = this.milestones.all { it.status == WorkflowSlaMilestoneStatus.SUPPRESSED }
        val allClosed = this.milestones.all {
            it.status == WorkflowSlaMilestoneStatus.SUCCEEDED || it.status == WorkflowSlaMilestoneStatus.SUPPRESSED
        }
        when (status) {
            WorkflowSlaScheduleStatus.ACTIVE -> require(!hasAttention && !allClosed) {
                "Active Workflow SLA schedules require processable milestones."
            }
            WorkflowSlaScheduleStatus.ATTENTION_REQUIRED -> require(hasAttention) {
                "Workflow SLA attention state requires a failed or unknown milestone."
            }
            WorkflowSlaScheduleStatus.SUPPRESSED -> require(allSuppressed) {
                "Suppressed Workflow SLA schedules require all milestones suppressed."
            }
            WorkflowSlaScheduleStatus.COMPLETED -> require(allClosed && !allSuppressed) {
                "Completed Workflow SLA schedules require successful or suppressed milestones."
            }
            else -> throw IllegalArgumentException("Unknown Workflow SLA schedule statuses require typed support.")
        }
        val writer = WorkflowSlaSupport.digest("flowweft-workflow-sla-schedule-v1")
            .text(this.tenantId)
            .text(this.scheduleId)
            .text(this.idempotencyKey)
            .text(this.idempotencyBindingDigest)
            .text(policy.policyDigest)
            .text(task.snapshotDigest)
            .integer(this.milestones.size)
        this.milestones.forEach { writer.text(it.contentDigest) }
        contentDigest = writer.text(status.code)
            .longValue(this.version)
            .text(this.prepareAuthorizationEvidenceDigest)
            .text(this.commitAuthorizationEvidenceDigest)
            .longValue(createdAt)
            .longValue(updatedAt)
            .finish()
    }

    fun milestone(kind: WorkflowSlaMilestoneKind): WorkflowSlaMilestoneRecord = milestones.firstOrNull {
        it.policy.kind == kind
    } ?: throw IllegalArgumentException("Workflow SLA schedule milestone is absent.")

    override fun toString(): String = "WorkflowSlaSchedule(<redacted>)"

    companion object {
        @JvmStatic
        fun create(
            tenantId: String,
            scheduleId: String,
            idempotencyKey: String,
            idempotencyBindingDigest: String,
            policy: WorkflowSlaPolicy,
            task: WorkflowSlaTaskSnapshot,
            milestones: Collection<WorkflowSlaMilestoneRecord>,
            prepareAuthorizationEvidenceDigest: String,
            commitAuthorizationEvidenceDigest: String,
            createdAt: Long,
        ): WorkflowSlaSchedule = WorkflowSlaSchedule(
            tenantId,
            scheduleId,
            idempotencyKey,
            idempotencyBindingDigest,
            policy,
            task,
            milestones,
            WorkflowSlaScheduleStatus.ACTIVE,
            0L,
            prepareAuthorizationEvidenceDigest,
            commitAuthorizationEvidenceDigest,
            createdAt,
            createdAt,
        )

        @JvmStatic
        fun restore(
            tenantId: String,
            scheduleId: String,
            idempotencyKey: String,
            idempotencyBindingDigest: String,
            policy: WorkflowSlaPolicy,
            task: WorkflowSlaTaskSnapshot,
            milestones: Collection<WorkflowSlaMilestoneRecord>,
            status: WorkflowSlaScheduleStatus,
            version: Long,
            prepareAuthorizationEvidenceDigest: String,
            commitAuthorizationEvidenceDigest: String,
            createdAt: Long,
            updatedAt: Long,
        ): WorkflowSlaSchedule = WorkflowSlaSchedule(
            tenantId,
            scheduleId,
            idempotencyKey,
            idempotencyBindingDigest,
            policy,
            task,
            milestones,
            status,
            version,
            prepareAuthorizationEvidenceDigest,
            commitAuthorizationEvidenceDigest,
            createdAt,
            updatedAt,
        )
    }
}
