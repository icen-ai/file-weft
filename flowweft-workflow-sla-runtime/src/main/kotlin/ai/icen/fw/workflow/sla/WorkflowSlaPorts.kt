package ai.icen.fw.workflow.sla

import ai.icen.fw.workflow.spi.WorkflowBusinessCalendar

/**
 * Trusted exact registry lookup. Implementations compare every profile/calendar/provider digest
 * in the binding and return null on drift; they never return credentials through this contract.
 */
interface WorkflowSlaCalendarRegistry {
    fun resolve(binding: WorkflowSlaCalendarBinding): WorkflowBusinessCalendar?
}

class WorkflowSlaStoreCode private constructor(code: String) {
    val code: String = slaMachineCode(code, "store result code")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowSlaStoreCode && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowSlaStoreCode(<redacted>)"

    companion object {
        @JvmField val APPLIED = WorkflowSlaStoreCode("applied")
        @JvmField val REPLAYED = WorkflowSlaStoreCode("replayed")
        @JvmField val CONFLICT = WorkflowSlaStoreCode("conflict")
        @JvmField val NOT_FOUND = WorkflowSlaStoreCode("not-found")
        @JvmField val NOT_ELIGIBLE = WorkflowSlaStoreCode("not-eligible")
        @JvmField val LEASE_MISMATCH = WorkflowSlaStoreCode("lease-mismatch")
        @JvmField val OUTCOME_UNKNOWN = WorkflowSlaStoreCode("outcome-unknown")

        @JvmStatic
        fun of(code: String): WorkflowSlaStoreCode = builtIns.firstOrNull { it.code == code }
            ?: WorkflowSlaStoreCode(code)

        private val builtIns = listOf(
            APPLIED,
            REPLAYED,
            CONFLICT,
            NOT_FOUND,
            NOT_ELIGIBLE,
            LEASE_MISMATCH,
            OUTCOME_UNKNOWN,
        )
    }
}

class WorkflowSlaStoreResult private constructor(
    val code: WorkflowSlaStoreCode,
    val schedule: WorkflowSlaSchedule?,
) {
    init {
        require((code == WorkflowSlaStoreCode.APPLIED || code == WorkflowSlaStoreCode.REPLAYED) ==
            (schedule != null)
        ) { "Workflow SLA store result shape is inconsistent." }
    }

    override fun toString(): String = "WorkflowSlaStoreResult(code=${code.code})"

    companion object {
        @JvmStatic
        fun applied(schedule: WorkflowSlaSchedule): WorkflowSlaStoreResult =
            WorkflowSlaStoreResult(WorkflowSlaStoreCode.APPLIED, schedule)

        @JvmStatic
        fun replayed(schedule: WorkflowSlaSchedule): WorkflowSlaStoreResult =
            WorkflowSlaStoreResult(WorkflowSlaStoreCode.REPLAYED, schedule)

        @JvmStatic
        fun failed(code: WorkflowSlaStoreCode): WorkflowSlaStoreResult {
            require(code != WorkflowSlaStoreCode.APPLIED && code != WorkflowSlaStoreCode.REPLAYED) {
                "Successful Workflow SLA store results require a schedule."
            }
            return WorkflowSlaStoreResult(code, null)
        }
    }
}

/** Guarded insert. The store must compare the task in the same transaction as the schedule write. */
class WorkflowSlaCreateMutation private constructor(
    val schedule: WorkflowSlaSchedule,
    taskGuardDigest: String,
    val expectedTaskRevision: Long,
) {
    val taskGuardDigest: String = slaDigest(taskGuardDigest, "create task guard")
    val mutationDigest: String

    init {
        require(schedule.task.snapshotDigest == this.taskGuardDigest &&
            schedule.task.revision == expectedTaskRevision &&
            schedule.task.status == WorkflowSlaTaskStatus.ACTIVE
        ) { "Workflow SLA create guard does not match the prepared schedule task." }
        mutationDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-create-mutation-v1")
            .text(schedule.contentDigest)
            .text(this.taskGuardDigest)
            .longValue(expectedTaskRevision)
            .finish()
    }

    override fun toString(): String = "WorkflowSlaCreateMutation(<redacted>)"

    companion object {
        @JvmStatic
        fun of(schedule: WorkflowSlaSchedule): WorkflowSlaCreateMutation = WorkflowSlaCreateMutation(
            schedule,
            schedule.task.snapshotDigest,
            schedule.task.revision,
        )
    }
}

class WorkflowSlaDueRef private constructor(
    tenantId: String,
    scheduleId: String,
    val milestoneKind: WorkflowSlaMilestoneKind,
    val expectedScheduleVersion: Long,
    val eligibleAt: Long,
) {
    val tenantId: String = slaIdentifier(tenantId, "due tenant id")
    val scheduleId: String = slaIdentifier(scheduleId, "due schedule id")

    init {
        require(expectedScheduleVersion >= 0L && eligibleAt >= 0L) { "Workflow SLA due reference is invalid." }
    }

    override fun toString(): String = "WorkflowSlaDueRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            scheduleId: String,
            milestoneKind: WorkflowSlaMilestoneKind,
            expectedScheduleVersion: Long,
            eligibleAt: Long,
        ): WorkflowSlaDueRef = WorkflowSlaDueRef(
            tenantId,
            scheduleId,
            milestoneKind,
            expectedScheduleVersion,
            eligibleAt,
        )
    }
}

class WorkflowSlaClaimMutation private constructor(
    tenantId: String,
    scheduleId: String,
    val milestoneKind: WorkflowSlaMilestoneKind,
    val expectedScheduleVersion: Long,
    workerId: String,
    leaseId: String,
    authorizationEvidenceDigest: String,
    val now: Long,
    val leaseDurationMillis: Long,
) {
    val tenantId: String = slaIdentifier(tenantId, "claim tenant id")
    val scheduleId: String = slaIdentifier(scheduleId, "claim schedule id")
    val workerId: String = slaIdentifier(workerId, "claim worker id")
    val leaseId: String = slaIdentifier(leaseId, "claim lease id")
    val authorizationEvidenceDigest: String = slaDigest(
        authorizationEvidenceDigest,
        "claim authorization evidence",
    )
    val mutationDigest: String

    init {
        require(expectedScheduleVersion >= 0L && now >= 0L && leaseDurationMillis in 1L..MAX_LEASE_MILLIS &&
            now <= Long.MAX_VALUE - leaseDurationMillis
        ) { "Workflow SLA claim timing or version is invalid." }
        mutationDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-claim-mutation-v1")
            .text(this.tenantId)
            .text(this.scheduleId)
            .text(milestoneKind.code)
            .longValue(expectedScheduleVersion)
            .text(this.workerId)
            .text(this.leaseId)
            .text(this.authorizationEvidenceDigest)
            .longValue(now)
            .longValue(leaseDurationMillis)
            .finish()
    }

    override fun toString(): String = "WorkflowSlaClaimMutation(<redacted>)"

    companion object {
        const val MAX_LEASE_MILLIS: Long = 900_000L

        @JvmStatic
        fun of(
            tenantId: String,
            scheduleId: String,
            milestoneKind: WorkflowSlaMilestoneKind,
            expectedScheduleVersion: Long,
            workerId: String,
            leaseId: String,
            authorizationEvidenceDigest: String,
            now: Long,
            leaseDurationMillis: Long,
        ): WorkflowSlaClaimMutation = WorkflowSlaClaimMutation(
            tenantId,
            scheduleId,
            milestoneKind,
            expectedScheduleVersion,
            workerId,
            leaseId,
            authorizationEvidenceDigest,
            now,
            leaseDurationMillis,
        )
    }
}

/** Provider-call checkpoint with a fresh task guard and lease fence. */
class WorkflowSlaActionCheckpoint private constructor(
    tenantId: String,
    scheduleId: String,
    val milestoneKind: WorkflowSlaMilestoneKind,
    val expectedScheduleVersion: Long,
    val lease: WorkflowSlaLease,
    val task: WorkflowSlaTaskSnapshot,
    actionRequestDigest: String,
    authorizationEvidenceDigest: String,
    val checkpointedAt: Long,
) {
    val tenantId: String = slaIdentifier(tenantId, "checkpoint tenant id")
    val scheduleId: String = slaIdentifier(scheduleId, "checkpoint schedule id")
    val actionRequestDigest: String = slaDigest(actionRequestDigest, "checkpoint action request")
    val authorizationEvidenceDigest: String = slaDigest(
        authorizationEvidenceDigest,
        "checkpoint authorization evidence",
    )
    val checkpointDigest: String

    init {
        require(expectedScheduleVersion >= 0L && checkpointedAt >= lease.acquiredAt &&
            checkpointedAt < lease.expiresAt && task.tenantId == this.tenantId &&
            task.status == WorkflowSlaTaskStatus.ACTIVE
        ) { "Workflow SLA action checkpoint binding is invalid." }
        checkpointDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-action-checkpoint-v1")
            .text(this.tenantId)
            .text(this.scheduleId)
            .text(milestoneKind.code)
            .longValue(expectedScheduleVersion)
            .text(lease.leaseId)
            .text(lease.workerId)
            .longValue(lease.fencingToken)
            .text(task.snapshotDigest)
            .text(this.actionRequestDigest)
            .text(this.authorizationEvidenceDigest)
            .longValue(checkpointedAt)
            .finish()
    }

    override fun toString(): String = "WorkflowSlaActionCheckpoint(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            scheduleId: String,
            milestoneKind: WorkflowSlaMilestoneKind,
            expectedScheduleVersion: Long,
            lease: WorkflowSlaLease,
            task: WorkflowSlaTaskSnapshot,
            actionRequestDigest: String,
            authorizationEvidenceDigest: String,
            checkpointedAt: Long,
        ): WorkflowSlaActionCheckpoint = WorkflowSlaActionCheckpoint(
            tenantId,
            scheduleId,
            milestoneKind,
            expectedScheduleVersion,
            lease,
            task,
            actionRequestDigest,
            authorizationEvidenceDigest,
            checkpointedAt,
        )
    }
}

class WorkflowSlaActionCompletion private constructor(
    tenantId: String,
    scheduleId: String,
    val milestoneKind: WorkflowSlaMilestoneKind,
    val expectedScheduleVersion: Long,
    val lease: WorkflowSlaLease,
    val targetStatus: WorkflowSlaMilestoneStatus,
    val actionReceipt: WorkflowSlaActionReceipt?,
    outcomeEvidenceDigest: String,
    val nextAttemptAt: Long?,
    val completedAt: Long,
) {
    val tenantId: String = slaIdentifier(tenantId, "completion tenant id")
    val scheduleId: String = slaIdentifier(scheduleId, "completion schedule id")
    val outcomeEvidenceDigest: String = slaDigest(outcomeEvidenceDigest, "completion outcome evidence")
    val completionDigest: String

    init {
        require(expectedScheduleVersion >= 0L && completedAt >= lease.acquiredAt) {
            "Workflow SLA completion version or time is invalid."
        }
        when (targetStatus) {
            WorkflowSlaMilestoneStatus.SUCCEEDED -> require(
                actionReceipt?.outcome == WorkflowSlaActionOutcome.SUCCEEDED && nextAttemptAt == null,
            ) { "Workflow SLA success completion is invalid." }
            WorkflowSlaMilestoneStatus.SUPPRESSED -> require(
                (actionReceipt == null || actionReceipt.outcome == WorkflowSlaActionOutcome.SUPPRESSED) &&
                    nextAttemptAt == null,
            ) { "Workflow SLA suppression completion is invalid." }
            WorkflowSlaMilestoneStatus.RETRY_WAIT -> require(
                (actionReceipt == null ||
                    actionReceipt.outcome == WorkflowSlaActionOutcome.NOT_APPLIED_RETRYABLE) &&
                    nextAttemptAt != null && nextAttemptAt > completedAt,
            ) { "Workflow SLA retry completion is invalid." }
            WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN -> require(
                (actionReceipt == null || actionReceipt.outcome == WorkflowSlaActionOutcome.OUTCOME_UNKNOWN) &&
                    nextAttemptAt == null,
            ) { "Workflow SLA unknown completion is invalid." }
            WorkflowSlaMilestoneStatus.TERMINAL_FAILURE -> require(
                actionReceipt != null &&
                    (actionReceipt.outcome == WorkflowSlaActionOutcome.PERMANENT_FAILURE ||
                        actionReceipt.outcome == WorkflowSlaActionOutcome.NOT_APPLIED_RETRYABLE) &&
                    nextAttemptAt == null,
            ) { "Workflow SLA terminal completion is invalid." }
            else -> throw IllegalArgumentException("Workflow SLA completion target is not terminal or retryable.")
        }
        completionDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-action-completion-v1")
            .text(this.tenantId)
            .text(this.scheduleId)
            .text(milestoneKind.code)
            .longValue(expectedScheduleVersion)
            .text(lease.leaseId)
            .text(lease.workerId)
            .longValue(lease.fencingToken)
            .text(targetStatus.code)
            .optional(actionReceipt?.receiptDigest)
            .text(this.outcomeEvidenceDigest)
            .optional(nextAttemptAt?.toString())
            .longValue(completedAt)
            .finish()
    }

    override fun toString(): String = "WorkflowSlaActionCompletion(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            scheduleId: String,
            milestoneKind: WorkflowSlaMilestoneKind,
            expectedScheduleVersion: Long,
            lease: WorkflowSlaLease,
            targetStatus: WorkflowSlaMilestoneStatus,
            actionReceipt: WorkflowSlaActionReceipt?,
            outcomeEvidenceDigest: String,
            nextAttemptAt: Long?,
            completedAt: Long,
        ): WorkflowSlaActionCompletion = WorkflowSlaActionCompletion(
            tenantId,
            scheduleId,
            milestoneKind,
            expectedScheduleVersion,
            lease,
            targetStatus,
            actionReceipt,
            outcomeEvidenceDigest,
            nextAttemptAt,
            completedAt,
        )
    }
}

/**
 * Suppresses every still-local milestone. Checkpointed or outcome-unknown milestones are retained
 * unchanged and must finish or reconcile independently; they never justify sending a future SLA
 * action for a now-terminal/revoked task.
 */
class WorkflowSlaSuppression private constructor(
    tenantId: String,
    scheduleId: String,
    val expectedScheduleVersion: Long,
    val lease: WorkflowSlaLease?,
    evidenceDigest: String,
    val suppressedAt: Long,
) {
    val tenantId: String = slaIdentifier(tenantId, "suppression tenant id")
    val scheduleId: String = slaIdentifier(scheduleId, "suppression schedule id")
    val evidenceDigest: String = slaDigest(evidenceDigest, "suppression evidence")

    init {
        require(expectedScheduleVersion >= 0L && suppressedAt >= 0L &&
            (lease == null || suppressedAt >= lease.acquiredAt)
        ) { "Workflow SLA suppression binding is invalid." }
    }

    override fun toString(): String = "WorkflowSlaSuppression(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            scheduleId: String,
            expectedScheduleVersion: Long,
            lease: WorkflowSlaLease?,
            evidenceDigest: String,
            suppressedAt: Long,
        ): WorkflowSlaSuppression = WorkflowSlaSuppression(
            tenantId,
            scheduleId,
            expectedScheduleVersion,
            lease,
            evidenceDigest,
            suppressedAt,
        )
    }
}

class WorkflowSlaReconciliationResolution private constructor(code: String) {
    val code: String = slaMachineCode(code, "reconciliation resolution")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowSlaReconciliationResolution && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowSlaReconciliationResolution(<redacted>)"

    companion object {
        @JvmField val APPLIED = WorkflowSlaReconciliationResolution("applied")
        @JvmField val NOT_APPLIED = WorkflowSlaReconciliationResolution("not-applied")
        @JvmField val FAILED = WorkflowSlaReconciliationResolution("failed")
    }
}

class WorkflowSlaReconciliation private constructor(
    tenantId: String,
    scheduleId: String,
    val milestoneKind: WorkflowSlaMilestoneKind,
    val expectedScheduleVersion: Long,
    val resolution: WorkflowSlaReconciliationResolution,
    val actionReceipt: WorkflowSlaActionReceipt,
    evidenceDigest: String,
    authorizationEvidenceDigest: String,
    val nextAttemptAt: Long?,
    val reconciledAt: Long,
) {
    val tenantId: String = slaIdentifier(tenantId, "reconciliation tenant id")
    val scheduleId: String = slaIdentifier(scheduleId, "reconciliation schedule id")
    val evidenceDigest: String = slaDigest(evidenceDigest, "reconciliation evidence")
    val authorizationEvidenceDigest: String = slaDigest(
        authorizationEvidenceDigest,
        "reconciliation authorization evidence",
    )

    init {
        require(expectedScheduleVersion >= 0L && reconciledAt >= 0L) {
            "Workflow SLA reconciliation version or time is invalid."
        }
        when (resolution) {
            WorkflowSlaReconciliationResolution.APPLIED -> require(
                actionReceipt.outcome == WorkflowSlaActionOutcome.SUCCEEDED && nextAttemptAt == null,
            ) { "Applied Workflow SLA reconciliation requires success evidence." }
            WorkflowSlaReconciliationResolution.NOT_APPLIED -> require(
                actionReceipt.outcome == WorkflowSlaActionOutcome.NOT_APPLIED_RETRYABLE &&
                    nextAttemptAt != null && nextAttemptAt > reconciledAt,
            ) { "Not-applied Workflow SLA reconciliation requires a future retry." }
            WorkflowSlaReconciliationResolution.FAILED -> require(
                actionReceipt.outcome == WorkflowSlaActionOutcome.PERMANENT_FAILURE && nextAttemptAt == null,
            ) { "Failed Workflow SLA reconciliation requires rejection evidence." }
            else -> throw IllegalArgumentException("Unknown Workflow SLA reconciliation resolution.")
        }
    }

    override fun toString(): String = "WorkflowSlaReconciliation(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            scheduleId: String,
            milestoneKind: WorkflowSlaMilestoneKind,
            expectedScheduleVersion: Long,
            resolution: WorkflowSlaReconciliationResolution,
            actionReceipt: WorkflowSlaActionReceipt,
            evidenceDigest: String,
            authorizationEvidenceDigest: String,
            nextAttemptAt: Long?,
            reconciledAt: Long,
        ): WorkflowSlaReconciliation = WorkflowSlaReconciliation(
            tenantId,
            scheduleId,
            milestoneKind,
            expectedScheduleVersion,
            resolution,
            actionReceipt,
            evidenceDigest,
            authorizationEvidenceDigest,
            nextAttemptAt,
            reconciledAt,
        )
    }
}

class WorkflowSlaDiagnosticSnapshot private constructor(
    val activeSchedules: Long,
    val dueMilestones: Long,
    val expiredLeases: Long,
    val outcomeUnknown: Long,
    val terminalFailures: Long,
    val oldestDueAt: Long?,
    val observedAt: Long,
) {
    init {
        require(activeSchedules >= 0L && dueMilestones >= 0L && expiredLeases >= 0L &&
            outcomeUnknown >= 0L && terminalFailures >= 0L && observedAt >= 0L &&
            (oldestDueAt == null || oldestDueAt in 0L..observedAt)
        ) { "Workflow SLA diagnostic counters are invalid." }
    }

    override fun toString(): String = "WorkflowSlaDiagnosticSnapshot(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            activeSchedules: Long,
            dueMilestones: Long,
            expiredLeases: Long,
            outcomeUnknown: Long,
            terminalFailures: Long,
            oldestDueAt: Long?,
            observedAt: Long,
        ): WorkflowSlaDiagnosticSnapshot = WorkflowSlaDiagnosticSnapshot(
            activeSchedules,
            dueMilestones,
            expiredLeases,
            outcomeUnknown,
            terminalFailures,
            oldestDueAt,
            observedAt,
        )
    }
}

/**
 * Durable boundary. Every mutating method is one committed local transaction. Implementations
 * MUST NOT call calendars, action providers, identity systems or notification vendors from these
 * methods. createSchedule and checkpointAction atomically compare the authoritative task guard.
 */
interface WorkflowSlaDurableStore {
    fun loadTask(tenantId: String, instanceId: String, workItemId: String): WorkflowSlaTaskSnapshot?
    fun createSchedule(mutation: WorkflowSlaCreateMutation): WorkflowSlaStoreResult
    fun loadSchedule(tenantId: String, scheduleId: String): WorkflowSlaSchedule?
    fun loadScheduleByIdempotency(tenantId: String, idempotencyKey: String): WorkflowSlaSchedule?
    fun findDue(tenantId: String, eligibleAtOrBefore: Long, limit: Int): List<WorkflowSlaDueRef>
    fun claim(mutation: WorkflowSlaClaimMutation): WorkflowSlaStoreResult
    fun checkpointAction(checkpoint: WorkflowSlaActionCheckpoint): WorkflowSlaStoreResult
    fun completeAction(completion: WorkflowSlaActionCompletion): WorkflowSlaStoreResult
    fun suppressRemaining(suppression: WorkflowSlaSuppression): WorkflowSlaStoreResult
    fun reconcile(reconciliation: WorkflowSlaReconciliation): WorkflowSlaStoreResult
    fun diagnosticSnapshot(tenantId: String, observedAt: Long): WorkflowSlaDiagnosticSnapshot
}
