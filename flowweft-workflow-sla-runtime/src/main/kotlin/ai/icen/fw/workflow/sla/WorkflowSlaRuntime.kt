package ai.icen.fw.workflow.sla

import ai.icen.fw.workflow.runtime.WorkflowBusinessCalendarCommand
import ai.icen.fw.workflow.runtime.WorkflowBusinessCalendarEvaluation
import ai.icen.fw.workflow.runtime.WorkflowBusinessCalendarResultCode
import ai.icen.fw.workflow.runtime.WorkflowBusinessCalendarRuntime
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAction
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationDecision
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationPort
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationRequest
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationStatus
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext
import ai.icen.fw.workflow.runtime.WorkflowWorkerClock
import ai.icen.fw.workflow.spi.WorkflowBusinessTimeOperation
import java.util.concurrent.TimeUnit

class WorkflowSlaRuntimeCode private constructor(code: String) {
    val code: String = slaMachineCode(code, "runtime result code")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowSlaRuntimeCode && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowSlaRuntimeCode(<redacted>)"

    companion object {
        @JvmField val CREATED = WorkflowSlaRuntimeCode("created")
        @JvmField val REPLAYED = WorkflowSlaRuntimeCode("replayed")
        @JvmField val ACTION_COMMITTED = WorkflowSlaRuntimeCode("action-committed")
        @JvmField val SUPPRESSED = WorkflowSlaRuntimeCode("suppressed")
        @JvmField val RECONCILED = WorkflowSlaRuntimeCode("reconciled")
        @JvmField val AUTHORIZATION_DENIED = WorkflowSlaRuntimeCode("authorization-denied")
        @JvmField val AUTHORIZATION_UNAVAILABLE = WorkflowSlaRuntimeCode("authorization-unavailable")
        @JvmField val TASK_HIDDEN = WorkflowSlaRuntimeCode("task-hidden")
        @JvmField val TASK_NOT_ACTIVE = WorkflowSlaRuntimeCode("task-not-active")
        @JvmField val TASK_DRIFTED = WorkflowSlaRuntimeCode("task-drifted")
        @JvmField val POLICY_MISMATCH = WorkflowSlaRuntimeCode("policy-mismatch")
        @JvmField val CALENDAR_UNAVAILABLE = WorkflowSlaRuntimeCode("calendar-unavailable")
        @JvmField val CALENDAR_REJECTED = WorkflowSlaRuntimeCode("calendar-rejected")
        @JvmField val RECEIPT_INVALID = WorkflowSlaRuntimeCode("receipt-invalid")
        @JvmField val STORE_CONFLICT = WorkflowSlaRuntimeCode("store-conflict")
        @JvmField val STORE_OUTCOME_UNKNOWN = WorkflowSlaRuntimeCode("store-outcome-unknown")
        @JvmField val NOT_ELIGIBLE = WorkflowSlaRuntimeCode("not-eligible")
        @JvmField val ACTION_OUTCOME_UNKNOWN = WorkflowSlaRuntimeCode("action-outcome-unknown")
        @JvmField val INVALID = WorkflowSlaRuntimeCode("invalid")
    }
}

/** Stable, content-free diagnostic. Exception messages, task ids and provider payloads are absent. */
class WorkflowSlaRuntimeDiagnostic private constructor(
    code: String,
    val retryable: Boolean,
) {
    val code: String = slaMachineCode(code, "runtime diagnostic")
    val diagnosticDigest: String = WorkflowSlaSupport.digest("flowweft-workflow-sla-runtime-diagnostic-v1")
        .text(this.code)
        .bool(retryable)
        .finish()

    override fun toString(): String = "WorkflowSlaRuntimeDiagnostic(<redacted>)"

    companion object {
        @JvmStatic
        fun of(code: String, retryable: Boolean): WorkflowSlaRuntimeDiagnostic =
            WorkflowSlaRuntimeDiagnostic(code, retryable)
    }
}

class WorkflowSlaRuntimeResult private constructor(
    val code: WorkflowSlaRuntimeCode,
    val schedule: WorkflowSlaSchedule?,
    val diagnostic: WorkflowSlaRuntimeDiagnostic?,
) {
    init {
        val success = code == WorkflowSlaRuntimeCode.CREATED || code == WorkflowSlaRuntimeCode.REPLAYED ||
            code == WorkflowSlaRuntimeCode.ACTION_COMMITTED || code == WorkflowSlaRuntimeCode.SUPPRESSED ||
            code == WorkflowSlaRuntimeCode.RECONCILED
        require(success == (schedule != null) && success == (diagnostic == null)) {
            "Workflow SLA runtime result shape is inconsistent."
        }
    }

    override fun toString(): String = "WorkflowSlaRuntimeResult(code=${code.code})"

    companion object {
        @JvmStatic
        fun success(code: WorkflowSlaRuntimeCode, schedule: WorkflowSlaSchedule): WorkflowSlaRuntimeResult =
            WorkflowSlaRuntimeResult(code, schedule, null)

        @JvmStatic
        fun failed(
            code: WorkflowSlaRuntimeCode,
            diagnostic: WorkflowSlaRuntimeDiagnostic,
        ): WorkflowSlaRuntimeResult = WorkflowSlaRuntimeResult(code, null, diagnostic)
    }
}

class WorkflowSlaCreateCommand private constructor(
    val callContext: WorkflowTrustedCallContext,
    scheduleId: String,
    idempotencyKey: String,
    instanceId: String,
    workItemId: String,
    val policy: WorkflowSlaPolicy,
) {
    val scheduleId: String = slaIdentifier(scheduleId, "create schedule id")
    val idempotencyKey: String = slaIdentifier(idempotencyKey, "create idempotency key")
    val instanceId: String = slaIdentifier(instanceId, "create instance id")
    val workItemId: String = slaIdentifier(workItemId, "create work-item id")
    val idempotencyBindingDigest: String = WorkflowSlaSupport.digest(
        "flowweft-workflow-sla-idempotency-binding-v1",
    )
        .text(callContext.tenantId)
        .text(callContext.actor.type)
        .text(callContext.actor.id)
        .text(this.scheduleId)
        .text(this.idempotencyKey)
        .text(this.instanceId)
        .text(this.workItemId)
        .text(policy.policyDigest)
        .finish()
    val requestDigest: String = WorkflowSlaSupport.digest("flowweft-workflow-sla-create-command-v1")
        .text(callContext.contextDigest)
        .text(this.scheduleId)
        .text(this.idempotencyKey)
        .text(this.instanceId)
        .text(this.workItemId)
        .text(policy.policyDigest)
        .finish()

    override fun toString(): String = "WorkflowSlaCreateCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            callContext: WorkflowTrustedCallContext,
            scheduleId: String,
            idempotencyKey: String,
            instanceId: String,
            workItemId: String,
            policy: WorkflowSlaPolicy,
        ): WorkflowSlaCreateCommand = WorkflowSlaCreateCommand(
            callContext,
            scheduleId,
            idempotencyKey,
            instanceId,
            workItemId,
            policy,
        )
    }
}

class WorkflowSlaDispatchCommand private constructor(
    val callContext: WorkflowTrustedCallContext,
    scheduleId: String,
    val milestoneKind: WorkflowSlaMilestoneKind,
    val expectedScheduleVersion: Long,
    workerId: String,
    leaseId: String,
    val leaseDurationMillis: Long,
) {
    val scheduleId: String = slaIdentifier(scheduleId, "dispatch schedule id")
    val workerId: String = slaIdentifier(workerId, "dispatch worker id")
    val leaseId: String = slaIdentifier(leaseId, "dispatch lease id")
    val commandDigest: String

    init {
        require(expectedScheduleVersion >= 0L && leaseDurationMillis in 1L..WorkflowSlaClaimMutation.MAX_LEASE_MILLIS) {
            "Workflow SLA dispatch version or lease duration is invalid."
        }
        commandDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-dispatch-command-v1")
            .text(callContext.contextDigest)
            .text(this.scheduleId)
            .text(milestoneKind.code)
            .longValue(expectedScheduleVersion)
            .text(this.workerId)
            .text(this.leaseId)
            .longValue(leaseDurationMillis)
            .finish()
    }

    override fun toString(): String = "WorkflowSlaDispatchCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            callContext: WorkflowTrustedCallContext,
            scheduleId: String,
            milestoneKind: WorkflowSlaMilestoneKind,
            expectedScheduleVersion: Long,
            workerId: String,
            leaseId: String,
            leaseDurationMillis: Long,
        ): WorkflowSlaDispatchCommand = WorkflowSlaDispatchCommand(
            callContext,
            scheduleId,
            milestoneKind,
            expectedScheduleVersion,
            workerId,
            leaseId,
            leaseDurationMillis,
        )
    }
}

class WorkflowSlaReconcileCommand private constructor(
    val callContext: WorkflowTrustedCallContext,
    scheduleId: String,
    val milestoneKind: WorkflowSlaMilestoneKind,
    val expectedScheduleVersion: Long,
    val resolution: WorkflowSlaReconciliationResolution,
    val actionReceipt: WorkflowSlaActionReceipt,
    evidenceDigest: String,
    val nextAttemptAt: Long?,
) {
    val scheduleId: String = slaIdentifier(scheduleId, "reconcile schedule id")
    val evidenceDigest: String = slaDigest(evidenceDigest, "reconcile evidence")
    val commandDigest: String

    init {
        require(expectedScheduleVersion >= 0L) { "Workflow SLA reconcile version is invalid." }
        commandDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-reconcile-command-v1")
            .text(callContext.contextDigest)
            .text(this.scheduleId)
            .text(milestoneKind.code)
            .longValue(expectedScheduleVersion)
            .text(resolution.code)
            .text(actionReceipt.receiptDigest)
            .text(this.evidenceDigest)
            .optional(nextAttemptAt?.toString())
            .finish()
    }

    override fun toString(): String = "WorkflowSlaReconcileCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            callContext: WorkflowTrustedCallContext,
            scheduleId: String,
            milestoneKind: WorkflowSlaMilestoneKind,
            expectedScheduleVersion: Long,
            resolution: WorkflowSlaReconciliationResolution,
            actionReceipt: WorkflowSlaActionReceipt,
            evidenceDigest: String,
            nextAttemptAt: Long?,
        ): WorkflowSlaReconcileCommand = WorkflowSlaReconcileCommand(
            callContext,
            scheduleId,
            milestoneKind,
            expectedScheduleVersion,
            resolution,
            actionReceipt,
            evidenceDigest,
            nextAttemptAt,
        )
    }
}

/**
 * Typed SLA orchestrator. Store calls are committed transaction boundaries; every calendar or
 * action invocation occurs after the preceding store method has returned.
 */
class WorkflowSlaRuntime(
    private val authorizationPort: WorkflowRuntimeAuthorizationPort,
    private val store: WorkflowSlaDurableStore,
    private val calendarRegistry: WorkflowSlaCalendarRegistry,
    private val actionPort: WorkflowSlaActionPort,
    private val clock: WorkflowWorkerClock,
) {
    fun createSchedule(command: WorkflowSlaCreateCommand): WorkflowSlaRuntimeResult {
        val startedAt = currentTime()
            ?: return failed(WorkflowSlaRuntimeCode.INVALID, "clock-unavailable", true)
        val replay = loadByIdempotency(command.callContext.tenantId, command.idempotencyKey)
        if (replay != null) {
            if (replay.scheduleId != command.scheduleId || replay.task.instanceId != command.instanceId ||
                replay.task.workItemId != command.workItemId || replay.policy.policyDigest != command.policy.policyDigest ||
                replay.idempotencyBindingDigest != command.idempotencyBindingDigest
            ) return failed(WorkflowSlaRuntimeCode.STORE_CONFLICT, "idempotency-conflict", false)
            val replayDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-replay-request-v1")
                .text(command.requestDigest)
                .text(replay.contentDigest)
                .longValue(startedAt)
                .finish()
            val replayRequest = authorizationRequest(
                command.callContext,
                ACTION_PREPARE_SCHEDULE,
                replay.task,
                replayDigest,
                startedAt,
            ) ?: return failed(WorkflowSlaRuntimeCode.INVALID, "replay-authorization-request-invalid", false)
            val replayAuthorization = authorize(replayRequest, startedAt)
                ?: return failed(
                    WorkflowSlaRuntimeCode.AUTHORIZATION_UNAVAILABLE,
                    "replay-authorization-unavailable",
                    true,
                )
            if (replayAuthorization.status != WorkflowRuntimeAuthorizationStatus.AUTHORIZED) {
                return failed(WorkflowSlaRuntimeCode.AUTHORIZATION_DENIED, "replay-authorization-denied", false)
            }
            return WorkflowSlaRuntimeResult.success(WorkflowSlaRuntimeCode.REPLAYED, replay)
        }
        val before = loadTask(command.callContext.tenantId, command.instanceId, command.workItemId)
            ?: return failed(WorkflowSlaRuntimeCode.TASK_HIDDEN, "task-hidden", false)
        val bindingFailure = validateCreationTask(command, before)
        if (bindingFailure != null) return bindingFailure

        val prepareDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-prepare-request-v1")
            .text(command.requestDigest)
            .text(before.snapshotDigest)
            .longValue(startedAt)
            .finish()
        val prepareRequest = authorizationRequest(
            command.callContext,
            ACTION_PREPARE_SCHEDULE,
            before,
            prepareDigest,
            startedAt,
        ) ?: return failed(WorkflowSlaRuntimeCode.INVALID, "prepare-authorization-request-invalid", false)
        val prepare = authorize(prepareRequest, startedAt)
            ?: return failed(WorkflowSlaRuntimeCode.AUTHORIZATION_UNAVAILABLE, "prepare-authorization-unavailable", true)
        if (prepare.status != WorkflowRuntimeAuthorizationStatus.AUTHORIZED) {
            return failed(WorkflowSlaRuntimeCode.AUTHORIZATION_DENIED, "prepare-authorization-denied", false)
        }

        val provider = try {
            calendarRegistry.resolve(command.policy.calendarBinding)
        } catch (_: RuntimeException) {
            null
        } ?: return failed(WorkflowSlaRuntimeCode.CALENDAR_UNAVAILABLE, "calendar-provider-unavailable", true)
        val calendarRuntime = WorkflowBusinessCalendarRuntime(
            authorizationPort,
            provider,
            command.policy.calendarBinding.providerProfile,
            clock,
        )
        val evaluations = ArrayList<WorkflowBusinessCalendarEvaluation>(command.policy.milestones.size)
        for (milestone in command.policy.milestones) {
            val requestId = WorkflowSlaSupport.digest("flowweft-workflow-sla-calendar-request-id-v1")
                .text(command.scheduleId)
                .text(milestone.kind.code)
                .text(command.policy.policyDigest)
                .finish()
            val calendarCommand = try {
                WorkflowBusinessCalendarCommand.addWorkingDuration(
                    command.callContext,
                    requestId,
                    command.instanceId,
                    before.subject,
                    command.policy.calendarBinding.calendar,
                    before.activatedAt,
                    milestone.workingDurationMillis,
                )
            } catch (_: RuntimeException) {
                return failed(WorkflowSlaRuntimeCode.INVALID, "calendar-command-invalid", false)
            }
            val calendarResult = calendarRuntime.evaluate(calendarCommand)
            if (calendarResult.code != WorkflowBusinessCalendarResultCode.SUCCEEDED) {
                return mapCalendarFailure(calendarResult.code, calendarResult.diagnostic?.retryable == true)
            }
            val evaluation = calendarResult.evaluation
                ?: return failed(WorkflowSlaRuntimeCode.RECEIPT_INVALID, "calendar-evaluation-missing", false)
            if (evaluation.calendar != command.policy.calendarBinding.calendar ||
                evaluation.operation != WorkflowBusinessTimeOperation.ADD_WORKING_DURATION ||
                evaluation.value.resultingEpochMilli == null
            ) return failed(WorkflowSlaRuntimeCode.RECEIPT_INVALID, "calendar-evaluation-binding-invalid", false)
            evaluations += evaluation
        }
        if (evaluations.zipWithNext().any { pair ->
                pair.first.value.resultingEpochMilli!! > pair.second.value.resultingEpochMilli!!
            }
        ) return failed(WorkflowSlaRuntimeCode.RECEIPT_INVALID, "calendar-results-unordered", false)

        val afterCalendar = currentTime()
            ?: return failed(WorkflowSlaRuntimeCode.INVALID, "clock-unavailable", true)
        val after = loadTask(command.callContext.tenantId, command.instanceId, command.workItemId)
            ?: return failed(WorkflowSlaRuntimeCode.TASK_HIDDEN, "task-hidden-after-calendar", false)
        if (!before.sameTaskVersion(after)) {
            return failed(WorkflowSlaRuntimeCode.TASK_DRIFTED, "task-drifted-during-calendar", true)
        }
        val secondBindingFailure = validateCreationTask(command, after)
        if (secondBindingFailure != null) return secondBindingFailure

        val commitDigestWriter = WorkflowSlaSupport.digest("flowweft-workflow-sla-commit-request-v1")
            .text(command.requestDigest)
            .text(after.snapshotDigest)
            .text(prepareEvidence(prepare))
            .integer(evaluations.size)
        evaluations.forEach { commitDigestWriter.text(it.evaluationDigest) }
        val commitDigest = commitDigestWriter.longValue(afterCalendar).finish()
        val commitRequest = authorizationRequest(
            command.callContext,
            ACTION_COMMIT_SCHEDULE,
            after,
            commitDigest,
            afterCalendar,
        ) ?: return failed(WorkflowSlaRuntimeCode.INVALID, "commit-authorization-request-invalid", false)
        val commit = authorize(commitRequest, afterCalendar)
            ?: return failed(WorkflowSlaRuntimeCode.AUTHORIZATION_UNAVAILABLE, "commit-authorization-unavailable", true)
        if (commit.status != WorkflowRuntimeAuthorizationStatus.AUTHORIZED) {
            return failed(WorkflowSlaRuntimeCode.AUTHORIZATION_DENIED, "commit-authorization-denied", false)
        }
        val milestones = command.policy.milestones.mapIndexed { index, policy ->
            WorkflowSlaMilestoneRecord.scheduled(
                policy,
                requireNotNull(evaluations[index].value.resultingEpochMilli),
                evaluations[index].evaluationDigest,
                afterCalendar,
            )
        }
        val schedule = try {
            WorkflowSlaSchedule.create(
                command.callContext.tenantId,
                command.scheduleId,
                command.idempotencyKey,
                command.idempotencyBindingDigest,
                command.policy,
                after,
                milestones,
                prepareEvidence(prepare),
                prepareEvidence(commit),
                afterCalendar,
            )
        } catch (_: RuntimeException) {
            return failed(WorkflowSlaRuntimeCode.INVALID, "schedule-invalid", false)
        }
        val stored = storeMutation { store.createSchedule(WorkflowSlaCreateMutation.of(schedule)) }
            ?: return failed(WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN, "create-store-outcome-unknown", true)
        return mapStoreResult(stored, WorkflowSlaRuntimeCode.CREATED)
    }

    fun dispatch(command: WorkflowSlaDispatchCommand): WorkflowSlaRuntimeResult {
        val now = currentTime()
            ?: return failed(WorkflowSlaRuntimeCode.INVALID, "clock-unavailable", true)
        val schedule = loadSchedule(command.callContext.tenantId, command.scheduleId)
            ?: return failed(WorkflowSlaRuntimeCode.AUTHORIZATION_DENIED, "schedule-hidden", false)
        if (schedule.version != command.expectedScheduleVersion ||
            (schedule.status != WorkflowSlaScheduleStatus.ACTIVE &&
                schedule.status != WorkflowSlaScheduleStatus.ATTENTION_REQUIRED)
        ) return failed(WorkflowSlaRuntimeCode.NOT_ELIGIBLE, "schedule-not-eligible", false)
        val milestone = try {
            schedule.milestone(command.milestoneKind)
        } catch (_: RuntimeException) {
            return failed(WorkflowSlaRuntimeCode.NOT_ELIGIBLE, "milestone-not-eligible", false)
        }
        val claimDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-claim-request-v1")
            .text(command.commandDigest)
            .text(schedule.contentDigest)
            .text(milestone.contentDigest)
            .longValue(now)
            .finish()
        val claimRequest = authorizationRequest(
            command.callContext,
            ACTION_CLAIM_MILESTONE,
            schedule.task,
            claimDigest,
            now,
        ) ?: return failed(WorkflowSlaRuntimeCode.INVALID, "claim-authorization-request-invalid", false)
        val claimAuthorization = authorize(claimRequest, now)
            ?: return failed(WorkflowSlaRuntimeCode.AUTHORIZATION_UNAVAILABLE, "claim-authorization-unavailable", true)
        if (claimAuthorization.status != WorkflowRuntimeAuthorizationStatus.AUTHORIZED) {
            return failed(WorkflowSlaRuntimeCode.AUTHORIZATION_DENIED, "claim-authorization-denied", false)
        }
        val claim = try {
            WorkflowSlaClaimMutation.of(
                command.callContext.tenantId,
                command.scheduleId,
                command.milestoneKind,
                command.expectedScheduleVersion,
                command.workerId,
                command.leaseId,
                prepareEvidence(claimAuthorization),
                now,
                command.leaseDurationMillis,
            )
        } catch (_: RuntimeException) {
            return failed(WorkflowSlaRuntimeCode.INVALID, "claim-mutation-invalid", false)
        }
        val claimedResult = storeMutation { store.claim(claim) }
            ?: return failed(WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN, "claim-store-outcome-unknown", true)
        if (claimedResult.code != WorkflowSlaStoreCode.APPLIED && claimedResult.code != WorkflowSlaStoreCode.REPLAYED) {
            return mapStoreResult(claimedResult, WorkflowSlaRuntimeCode.ACTION_COMMITTED)
        }
        val claimed = requireNotNull(claimedResult.schedule)
        val leasedMilestone = try {
            claimed.milestone(command.milestoneKind)
        } catch (_: RuntimeException) {
            return failed(WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN, "claimed-milestone-missing", true)
        }
        val lease = leasedMilestone.lease
        if (leasedMilestone.status != WorkflowSlaMilestoneStatus.LEASED || lease == null ||
            lease.leaseId != command.leaseId || lease.workerId != command.workerId ||
            lease.expiresAt <= now
        ) return failed(WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN, "claimed-lease-invalid", true)

        val task = loadTask(claimed.tenantId, claimed.task.instanceId, claimed.task.workItemId)
        if (task == null || task.status != WorkflowSlaTaskStatus.ACTIVE || !task.matchesScheduleBinding(claimed)) {
            return suppressBeforeCheckpoint(claimed, lease, "task-terminal-or-hidden", now, task?.snapshotDigest)
        }
        val actionAuthorizationDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-action-authorize-v1")
            .text(command.commandDigest)
            .text(claimed.contentDigest)
            .text(task.snapshotDigest)
            .text(leasedMilestone.contentDigest)
            .longValue(now)
            .finish()
        val actionAuthorizationRequest = authorizationRequest(
            command.callContext,
            ACTION_EXECUTE,
            task,
            actionAuthorizationDigest,
            now,
        ) ?: return failed(WorkflowSlaRuntimeCode.INVALID, "action-authorization-request-invalid", false)
        val actionAuthorization = authorize(actionAuthorizationRequest, now)
            ?: return failed(WorkflowSlaRuntimeCode.AUTHORIZATION_UNAVAILABLE, "action-authorization-unavailable", true)
        if (actionAuthorization.status != WorkflowRuntimeAuthorizationStatus.AUTHORIZED) {
            return suppressBeforeCheckpoint(
                claimed,
                lease,
                "action-authority-revoked",
                now,
                prepareEvidence(actionAuthorization),
            )
        }
        val deadline = minimumDeadline(now, lease.expiresAt, claimed.policy.actionProfile.callWindowMillis)
            ?: return failed(WorkflowSlaRuntimeCode.NOT_ELIGIBLE, "action-window-expired", true)
        val actionRequest = try {
            WorkflowSlaActionRequest.of(
                command.callContext,
                claimed.scheduleId,
                claimed.policy.definitionRef,
                task.instanceId,
                task.workItemId,
                task.nodeId,
                task.subject,
                task.revision,
                task.taskDigest,
                claimed.policy.policyDigest,
                leasedMilestone.policy.kind,
                leasedMilestone.policy.action,
                claimed.policy.actionProfile,
                leasedMilestone.attempt,
                prepareEvidence(actionAuthorization),
                now,
                deadline,
            )
        } catch (_: RuntimeException) {
            return failed(WorkflowSlaRuntimeCode.INVALID, "action-request-invalid", false)
        }
        val checkpoint = try {
            WorkflowSlaActionCheckpoint.of(
                claimed.tenantId,
                claimed.scheduleId,
                command.milestoneKind,
                claimed.version,
                lease,
                task,
                actionRequest.requestDigest,
                prepareEvidence(actionAuthorization),
                now,
            )
        } catch (_: RuntimeException) {
            return failed(WorkflowSlaRuntimeCode.INVALID, "action-checkpoint-invalid", false)
        }
        val checkpointResult = storeMutation { store.checkpointAction(checkpoint) }
            ?: return failed(WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN, "checkpoint-store-outcome-unknown", true)
        if (checkpointResult.code != WorkflowSlaStoreCode.APPLIED &&
            checkpointResult.code != WorkflowSlaStoreCode.REPLAYED
        ) return mapStoreResult(checkpointResult, WorkflowSlaRuntimeCode.ACTION_COMMITTED)
        val checkpointed = requireNotNull(checkpointResult.schedule)
        val checkpointedMilestone = checkpointed.milestone(command.milestoneKind)
        if (checkpointedMilestone.status != WorkflowSlaMilestoneStatus.ACTION_CALL_STARTED ||
            checkpointedMilestone.actionRequestDigest != actionRequest.requestDigest ||
            checkpointedMilestone.lease?.fencingToken != lease.fencingToken
        ) return failed(WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN, "checkpoint-binding-invalid", true)

        // A second post-checkpoint reread closes the DB-to-provider race as far as a generic host
        // can. The ActionPort contract performs the final authoritative check at side-effect time.
        val beforeCallAt = currentTime()
            ?: return localRetry(checkpointed, lease, command.milestoneKind, "clock-unavailable-before-call", now)
        val beforeCallTask = loadTask(checkpointed.tenantId, task.instanceId, task.workItemId)
        if (beforeCallTask == null || beforeCallTask.status != WorkflowSlaTaskStatus.ACTIVE ||
            !beforeCallTask.matchesScheduleBinding(checkpointed)
        ) return suppressAfterCheckpoint(
            checkpointed,
            lease,
            command.milestoneKind,
            "task-terminal-before-call",
            beforeCallAt,
            beforeCallTask?.snapshotDigest,
        )
        if (!task.sameTaskVersion(beforeCallTask)) {
            return localRetry(checkpointed, lease, command.milestoneKind, "task-drifted-before-call", beforeCallAt)
        }
        val finalAuthorizationDigest = WorkflowSlaSupport.digest(
            "flowweft-workflow-sla-final-action-authorize-v1",
        )
            .text(actionRequest.requestDigest)
            .text(checkpointed.contentDigest)
            .text(beforeCallTask.snapshotDigest)
            .longValue(beforeCallAt)
            .finish()
        val finalActionAuthorizationRequest = authorizationRequest(
            command.callContext,
            ACTION_EXECUTE,
            beforeCallTask,
            finalAuthorizationDigest,
            beforeCallAt,
        ) ?: return localRetry(
            checkpointed,
            lease,
            command.milestoneKind,
            "final-action-authorization-request-invalid",
            beforeCallAt,
        )
        val finalActionAuthorization = authorize(finalActionAuthorizationRequest, beforeCallAt)
            ?: return localRetry(
                checkpointed,
                lease,
                command.milestoneKind,
                "action-authorization-unavailable-before-call",
                beforeCallAt,
            )
        if (finalActionAuthorization.status != WorkflowRuntimeAuthorizationStatus.AUTHORIZED) {
            return suppressAfterCheckpoint(
                checkpointed,
                lease,
                command.milestoneKind,
                "action-authority-revoked-before-call",
                beforeCallAt,
                prepareEvidence(finalActionAuthorization),
            )
        }

        val receipt = try {
            val remaining = deadline - (currentTime() ?: deadline)
            require(remaining > 0L) { "Workflow SLA action deadline expired." }
            actionPort.execute(actionRequest).toCompletableFuture().get(remaining, TimeUnit.MILLISECONDS)
        } catch (failure: Exception) {
            restoreInterrupt(failure)
            return outcomeUnknown(checkpointed, lease, command.milestoneKind, "action-call-outcome-unknown")
        }
        val completedAt = currentTime()
            ?: return outcomeUnknown(checkpointed, lease, command.milestoneKind, "clock-unavailable-after-action")
        if (!receipt.matches(actionRequest, completedAt)) {
            return outcomeUnknown(checkpointed, lease, command.milestoneKind, "action-receipt-invalid")
        }
        val recordDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-record-outcome-request-v1")
            .text(actionRequest.requestDigest)
            .text(receipt.receiptDigest)
            .text(checkpointed.contentDigest)
            .longValue(completedAt)
            .finish()
        val recordRequest = authorizationRequest(
            command.callContext,
            ACTION_RECORD_OUTCOME,
            beforeCallTask,
            recordDigest,
            completedAt,
        ) ?: return outcomeUnknown(checkpointed, lease, command.milestoneKind, "record-authorization-request-invalid")
        val recordAuthorization = authorize(recordRequest, completedAt)
            ?: return outcomeUnknown(checkpointed, lease, command.milestoneKind, "record-authorization-unavailable")
        if (recordAuthorization.status != WorkflowRuntimeAuthorizationStatus.AUTHORIZED) {
            return outcomeUnknown(checkpointed, lease, command.milestoneKind, "record-authorization-denied")
        }
        val target = when (receipt.outcome) {
            WorkflowSlaActionOutcome.SUCCEEDED -> WorkflowSlaMilestoneStatus.SUCCEEDED
            WorkflowSlaActionOutcome.SUPPRESSED -> WorkflowSlaMilestoneStatus.SUPPRESSED
            WorkflowSlaActionOutcome.OUTCOME_UNKNOWN -> WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN
            WorkflowSlaActionOutcome.PERMANENT_FAILURE -> WorkflowSlaMilestoneStatus.TERMINAL_FAILURE
            WorkflowSlaActionOutcome.NOT_APPLIED_RETRYABLE -> {
                if (checkpointedMilestone.attempt < checkpointed.policy.actionProfile.maximumAttempts) {
                    WorkflowSlaMilestoneStatus.RETRY_WAIT
                } else {
                    WorkflowSlaMilestoneStatus.TERMINAL_FAILURE
                }
            }
            else -> return outcomeUnknown(checkpointed, lease, command.milestoneKind, "action-outcome-unsupported")
        }
        val nextAttemptAt = if (target == WorkflowSlaMilestoneStatus.RETRY_WAIT) {
            safeAdd(completedAt, checkpointed.policy.actionProfile.retryDelayMillis)
        } else null
        val evidence = WorkflowSlaSupport.digest("flowweft-workflow-sla-recorded-action-outcome-v1")
            .text(receipt.receiptDigest)
            .text(prepareEvidence(recordAuthorization))
            .text(target.code)
            .longValue(completedAt)
            .finish()
        val completion = try {
            WorkflowSlaActionCompletion.of(
                checkpointed.tenantId,
                checkpointed.scheduleId,
                command.milestoneKind,
                checkpointed.version,
                lease,
                target,
                receipt,
                evidence,
                nextAttemptAt,
                completedAt,
            )
        } catch (_: RuntimeException) {
            return outcomeUnknown(checkpointed, lease, command.milestoneKind, "action-completion-invalid")
        }
        val completed = storeMutation { store.completeAction(completion) }
            ?: return failed(WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN, "completion-store-outcome-unknown", true)
        if (target == WorkflowSlaMilestoneStatus.SUPPRESSED &&
            (completed.code == WorkflowSlaStoreCode.APPLIED || completed.code == WorkflowSlaStoreCode.REPLAYED)
        ) {
            val updated = requireNotNull(completed.schedule)
            if (updated.status == WorkflowSlaScheduleStatus.ACTIVE ||
                updated.status == WorkflowSlaScheduleStatus.ATTENTION_REQUIRED
            ) {
                val suppression = WorkflowSlaSuppression.of(
                    updated.tenantId,
                    updated.scheduleId,
                    updated.version,
                    null,
                    evidence,
                    completedAt,
                )
                val remaining = storeMutation { store.suppressRemaining(suppression) }
                    ?: return failed(
                        WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN,
                        "provider-suppression-store-outcome-unknown",
                        true,
                    )
                return mapStoreResult(remaining, WorkflowSlaRuntimeCode.SUPPRESSED)
            }
        }
        return mapStoreResult(
            completed,
            if (target == WorkflowSlaMilestoneStatus.SUPPRESSED) WorkflowSlaRuntimeCode.SUPPRESSED
            else WorkflowSlaRuntimeCode.ACTION_COMMITTED,
        )
    }

    fun reconcile(command: WorkflowSlaReconcileCommand): WorkflowSlaRuntimeResult {
        val now = currentTime()
            ?: return failed(WorkflowSlaRuntimeCode.INVALID, "clock-unavailable", true)
        val schedule = loadSchedule(command.callContext.tenantId, command.scheduleId)
            ?: return failed(WorkflowSlaRuntimeCode.AUTHORIZATION_DENIED, "schedule-hidden", false)
        if (schedule.version != command.expectedScheduleVersion) {
            return failed(WorkflowSlaRuntimeCode.NOT_ELIGIBLE, "reconcile-version-conflict", false)
        }
        val milestone = try {
            schedule.milestone(command.milestoneKind)
        } catch (_: RuntimeException) {
            return failed(WorkflowSlaRuntimeCode.NOT_ELIGIBLE, "reconcile-milestone-missing", false)
        }
        if (milestone.status != WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN ||
            milestone.actionRequestDigest == null ||
            command.actionReceipt.scheduleId != schedule.scheduleId ||
            command.actionReceipt.milestoneKind != command.milestoneKind ||
            command.actionReceipt.providerId != schedule.policy.actionProfile.providerId ||
            command.actionReceipt.providerRevision != schedule.policy.actionProfile.providerRevision ||
            command.actionReceipt.actionProfileDigest != schedule.policy.actionProfile.bindingDigest ||
            command.actionReceipt.requestDigest != milestone.actionRequestDigest ||
            command.actionReceipt.completedAt !in command.actionReceipt.requestedAt..now ||
            now > command.actionReceipt.expiresAt || command.actionReceipt.expiresAt > command.actionReceipt.deadline
        ) return failed(WorkflowSlaRuntimeCode.RECEIPT_INVALID, "reconcile-evidence-invalid", false)
        val authRequest = authorizationRequest(
            command.callContext,
            ACTION_RECONCILE,
            schedule.task,
            command.commandDigest,
            now,
        ) ?: return failed(WorkflowSlaRuntimeCode.INVALID, "reconcile-authorization-request-invalid", false)
        val authorization = authorize(authRequest, now)
            ?: return failed(WorkflowSlaRuntimeCode.AUTHORIZATION_UNAVAILABLE, "reconcile-authorization-unavailable", true)
        if (authorization.status != WorkflowRuntimeAuthorizationStatus.AUTHORIZED) {
            return failed(WorkflowSlaRuntimeCode.AUTHORIZATION_DENIED, "reconcile-authorization-denied", false)
        }
        val mutation = try {
            WorkflowSlaReconciliation.of(
                schedule.tenantId,
                schedule.scheduleId,
                command.milestoneKind,
                command.expectedScheduleVersion,
                command.resolution,
                command.actionReceipt,
                command.evidenceDigest,
                prepareEvidence(authorization),
                command.nextAttemptAt,
                now,
            )
        } catch (_: RuntimeException) {
            return failed(WorkflowSlaRuntimeCode.INVALID, "reconcile-mutation-invalid", false)
        }
        val result = storeMutation { store.reconcile(mutation) }
            ?: return failed(WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN, "reconcile-store-outcome-unknown", true)
        return mapStoreResult(result, WorkflowSlaRuntimeCode.RECONCILED)
    }

    private fun validateCreationTask(
        command: WorkflowSlaCreateCommand,
        task: WorkflowSlaTaskSnapshot,
    ): WorkflowSlaRuntimeResult? {
        if (task.tenantId != command.callContext.tenantId || task.instanceId != command.instanceId ||
            task.workItemId != command.workItemId
        ) return failed(WorkflowSlaRuntimeCode.TASK_HIDDEN, "task-binding-hidden", false)
        if (task.status != WorkflowSlaTaskStatus.ACTIVE) {
            return failed(WorkflowSlaRuntimeCode.TASK_NOT_ACTIVE, "task-not-active", false)
        }
        if (task.definitionRef != command.policy.definitionRef || task.nodeId != command.policy.nodeId) {
            return failed(WorkflowSlaRuntimeCode.POLICY_MISMATCH, "policy-task-mismatch", false)
        }
        return null
    }

    private fun suppressBeforeCheckpoint(
        schedule: WorkflowSlaSchedule,
        lease: WorkflowSlaLease,
        reason: String,
        now: Long,
        additionalEvidenceDigest: String? = null,
    ): WorkflowSlaRuntimeResult {
        val evidence = suppressionEvidence(schedule, reason, now, additionalEvidenceDigest)
        val mutation = WorkflowSlaSuppression.of(
            schedule.tenantId,
            schedule.scheduleId,
            schedule.version,
            lease,
            evidence,
            now,
        )
        val result = storeMutation { store.suppressRemaining(mutation) }
            ?: return failed(WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN, "suppression-store-outcome-unknown", true)
        return mapStoreResult(result, WorkflowSlaRuntimeCode.SUPPRESSED)
    }

    private fun suppressAfterCheckpoint(
        schedule: WorkflowSlaSchedule,
        lease: WorkflowSlaLease,
        milestoneKind: WorkflowSlaMilestoneKind,
        reason: String,
        now: Long,
        additionalEvidenceDigest: String? = null,
    ): WorkflowSlaRuntimeResult {
        val evidence = suppressionEvidence(schedule, reason, now, additionalEvidenceDigest)
        val completion = WorkflowSlaActionCompletion.of(
            schedule.tenantId,
            schedule.scheduleId,
            milestoneKind,
            schedule.version,
            lease,
            WorkflowSlaMilestoneStatus.SUPPRESSED,
            null,
            evidence,
            null,
            now,
        )
        val current = storeMutation { store.completeAction(completion) }
            ?: return failed(WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN, "suppression-store-outcome-unknown", true)
        if (current.code != WorkflowSlaStoreCode.APPLIED && current.code != WorkflowSlaStoreCode.REPLAYED) {
            return mapStoreResult(current, WorkflowSlaRuntimeCode.SUPPRESSED)
        }
        val updated = requireNotNull(current.schedule)
        if (updated.status != WorkflowSlaScheduleStatus.ACTIVE &&
            updated.status != WorkflowSlaScheduleStatus.ATTENTION_REQUIRED
        ) {
            return WorkflowSlaRuntimeResult.success(WorkflowSlaRuntimeCode.SUPPRESSED, updated)
        }
        val remaining = WorkflowSlaSuppression.of(
            updated.tenantId,
            updated.scheduleId,
            updated.version,
            null,
            evidence,
            now,
        )
        val result = storeMutation { store.suppressRemaining(remaining) }
            ?: return failed(WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN, "remaining-suppression-outcome-unknown", true)
        return mapStoreResult(result, WorkflowSlaRuntimeCode.SUPPRESSED)
    }

    private fun localRetry(
        schedule: WorkflowSlaSchedule,
        lease: WorkflowSlaLease,
        milestoneKind: WorkflowSlaMilestoneKind,
        reason: String,
        now: Long,
    ): WorkflowSlaRuntimeResult {
        val evidence = WorkflowSlaSupport.digest("flowweft-workflow-sla-local-deferral-v1")
            .text(schedule.contentDigest)
            .text(reason)
            .longValue(now)
            .finish()
        val completion = WorkflowSlaActionCompletion.of(
            schedule.tenantId,
            schedule.scheduleId,
            milestoneKind,
            schedule.version,
            lease,
            WorkflowSlaMilestoneStatus.RETRY_WAIT,
            null,
            evidence,
            safeAdd(now, schedule.policy.actionProfile.retryDelayMillis),
            now,
        )
        val result = storeMutation { store.completeAction(completion) }
            ?: return failed(WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN, "deferral-store-outcome-unknown", true)
        return mapStoreResult(result, WorkflowSlaRuntimeCode.ACTION_COMMITTED)
    }

    private fun outcomeUnknown(
        schedule: WorkflowSlaSchedule,
        lease: WorkflowSlaLease,
        milestoneKind: WorkflowSlaMilestoneKind,
        reason: String,
    ): WorkflowSlaRuntimeResult {
        val now = currentTime() ?: lease.acquiredAt
        val evidence = WorkflowSlaSupport.digest("flowweft-workflow-sla-action-outcome-unknown-v1")
            .text(schedule.contentDigest)
            .text(reason)
            .longValue(now)
            .finish()
        val completion = try {
            WorkflowSlaActionCompletion.of(
                schedule.tenantId,
                schedule.scheduleId,
                milestoneKind,
                schedule.version,
                lease,
                WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN,
                null,
                evidence,
                null,
                now,
            )
        } catch (_: RuntimeException) {
            return failed(WorkflowSlaRuntimeCode.ACTION_OUTCOME_UNKNOWN, reason, false)
        }
        val result = storeMutation { store.completeAction(completion) }
            ?: return failed(WorkflowSlaRuntimeCode.ACTION_OUTCOME_UNKNOWN, reason, false)
        return when (result.code) {
            WorkflowSlaStoreCode.APPLIED,
            WorkflowSlaStoreCode.REPLAYED -> WorkflowSlaRuntimeResult.success(
                WorkflowSlaRuntimeCode.ACTION_COMMITTED,
                requireNotNull(result.schedule),
            )
            else -> failed(WorkflowSlaRuntimeCode.ACTION_OUTCOME_UNKNOWN, reason, false)
        }
    }

    private fun authorizationRequest(
        context: WorkflowTrustedCallContext,
        action: WorkflowRuntimeAction,
        task: WorkflowSlaTaskSnapshot,
        requestDigest: String,
        now: Long,
    ): WorkflowRuntimeAuthorizationRequest? = try {
        WorkflowRuntimeAuthorizationRequest.of(
            context,
            action,
            task.instanceId,
            task.definitionId,
            task.definitionRef,
            task.subject,
            requestDigest,
            now,
        )
    } catch (_: RuntimeException) {
        null
    }

    private fun authorize(
        request: WorkflowRuntimeAuthorizationRequest,
        now: Long,
    ): WorkflowRuntimeAuthorizationDecision? {
        val decision = try {
            authorizationPort.authorize(request)
        } catch (_: RuntimeException) {
            return null
        }
        return decision.takeIf { it.matches(request, now) }
    }

    private fun prepareEvidence(decision: WorkflowRuntimeAuthorizationDecision): String =
        WorkflowSlaSupport.digest("flowweft-workflow-sla-authorization-evidence-v1")
            .text(decision.authorizationId)
            .text(decision.tenantId)
            .text(decision.actor.type)
            .text(decision.actor.id)
            .text(decision.action.code)
            .text(decision.instanceId)
            .text(decision.requestDigest)
            .text(decision.status.code)
            .text(decision.authorityRevision)
            .text(decision.authorityDigest)
            .longValue(decision.evaluatedAt)
            .longValue(decision.validUntil)
            .finish()

    private fun mapCalendarFailure(
        code: WorkflowBusinessCalendarResultCode,
        retryable: Boolean,
    ): WorkflowSlaRuntimeResult = when (code) {
        WorkflowBusinessCalendarResultCode.AUTHORIZATION_DENIED -> failed(
            WorkflowSlaRuntimeCode.AUTHORIZATION_DENIED,
            "calendar-authorization-denied",
            false,
        )
        WorkflowBusinessCalendarResultCode.PROVIDER_UNAVAILABLE -> failed(
            WorkflowSlaRuntimeCode.CALENDAR_UNAVAILABLE,
            "calendar-provider-unavailable",
            true,
        )
        WorkflowBusinessCalendarResultCode.RECEIPT_INVALID -> failed(
            WorkflowSlaRuntimeCode.RECEIPT_INVALID,
            "calendar-receipt-invalid",
            false,
        )
        else -> failed(WorkflowSlaRuntimeCode.CALENDAR_REJECTED, "calendar-rejected", retryable)
    }

    private fun mapStoreResult(
        result: WorkflowSlaStoreResult,
        appliedCode: WorkflowSlaRuntimeCode,
    ): WorkflowSlaRuntimeResult = when (result.code) {
        WorkflowSlaStoreCode.APPLIED -> WorkflowSlaRuntimeResult.success(appliedCode, requireNotNull(result.schedule))
        WorkflowSlaStoreCode.REPLAYED -> WorkflowSlaRuntimeResult.success(
            if (appliedCode == WorkflowSlaRuntimeCode.CREATED) WorkflowSlaRuntimeCode.REPLAYED else appliedCode,
            requireNotNull(result.schedule),
        )
        WorkflowSlaStoreCode.CONFLICT -> failed(
            WorkflowSlaRuntimeCode.STORE_CONFLICT,
            "store-conflict",
            false,
        )
        WorkflowSlaStoreCode.NOT_FOUND,
        WorkflowSlaStoreCode.NOT_ELIGIBLE,
        WorkflowSlaStoreCode.LEASE_MISMATCH -> failed(
            WorkflowSlaRuntimeCode.NOT_ELIGIBLE,
            "store-not-eligible",
            false,
        )
        else -> failed(WorkflowSlaRuntimeCode.STORE_OUTCOME_UNKNOWN, "store-outcome-unknown", true)
    }

    private fun suppressionEvidence(
        schedule: WorkflowSlaSchedule,
        reason: String,
        now: Long,
        additionalEvidenceDigest: String?,
    ): String =
        WorkflowSlaSupport.digest("flowweft-workflow-sla-suppression-evidence-v1")
            .text(schedule.contentDigest)
            .text(reason)
            .optional(additionalEvidenceDigest)
            .longValue(now)
            .finish()

    private fun loadTask(tenantId: String, instanceId: String, workItemId: String): WorkflowSlaTaskSnapshot? = try {
        store.loadTask(tenantId, instanceId, workItemId)
    } catch (_: RuntimeException) {
        null
    }

    private fun loadSchedule(tenantId: String, scheduleId: String): WorkflowSlaSchedule? = try {
        store.loadSchedule(tenantId, scheduleId)
    } catch (_: RuntimeException) {
        null
    }

    private fun loadByIdempotency(tenantId: String, idempotencyKey: String): WorkflowSlaSchedule? = try {
        store.loadScheduleByIdempotency(tenantId, idempotencyKey)
    } catch (_: RuntimeException) {
        null
    }

    private fun storeMutation(operation: () -> WorkflowSlaStoreResult): WorkflowSlaStoreResult? = try {
        operation()
    } catch (_: RuntimeException) {
        null
    }

    private fun currentTime(): Long? = try {
        clock.currentTimeMillis().takeIf { it >= 0L }
    } catch (_: RuntimeException) {
        null
    }

    private fun minimumDeadline(now: Long, leaseExpiry: Long, callWindow: Long): Long? {
        if (now > Long.MAX_VALUE - callWindow) return null
        val deadline = minOf(leaseExpiry, now + callWindow)
        return deadline.takeIf { it > now }
    }

    private fun safeAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

    private fun restoreInterrupt(failure: Exception) {
        if (failure is InterruptedException || failure.cause is InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun failed(code: WorkflowSlaRuntimeCode, diagnostic: String, retryable: Boolean): WorkflowSlaRuntimeResult =
        WorkflowSlaRuntimeResult.failed(code, WorkflowSlaRuntimeDiagnostic.of(diagnostic, retryable))

    companion object {
        @JvmField val ACTION_PREPARE_SCHEDULE = WorkflowRuntimeAction.of("prepare-sla-schedule")
        @JvmField val ACTION_COMMIT_SCHEDULE = WorkflowRuntimeAction.of("commit-sla-schedule")
        @JvmField val ACTION_CLAIM_MILESTONE = WorkflowRuntimeAction.of("claim-sla-milestone")
        @JvmField val ACTION_EXECUTE = WorkflowRuntimeAction.of("execute-sla-action")
        @JvmField val ACTION_RECORD_OUTCOME = WorkflowRuntimeAction.of("record-sla-action-outcome")
        @JvmField val ACTION_RECONCILE = WorkflowRuntimeAction.of("reconcile-sla-action")
    }
}
