package ai.icen.fw.workflow.sla

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionAuthorizationReceipt
import ai.icen.fw.workflow.runtime.WorkflowBusinessCalendarProfile
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationDecision
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationPort
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationRequest
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationStatus
import ai.icen.fw.workflow.runtime.WorkflowRuntimeHumanDecisionReceiptRequest
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext
import ai.icen.fw.workflow.runtime.WorkflowWorkerClock
import ai.icen.fw.workflow.spi.WorkflowBusinessCalendar
import ai.icen.fw.workflow.spi.WorkflowBusinessCalendarRef
import ai.icen.fw.workflow.spi.WorkflowBusinessTimeResult
import ai.icen.fw.workflow.spi.WorkflowBusinessTimeValue
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkflowSlaRuntimeTest {
    @Test
    fun `SLA action vocabulary cannot express automatic approval`() {
        assertFailsWith<IllegalArgumentException> { WorkflowSlaActionKind.of("approve") }
    }

    @Test
    fun `creation binds exact task policy and three external calendar receipts`() {
        val fixture = Fixture()
        val result = fixture.create()

        assertEquals(WorkflowSlaRuntimeCode.CREATED, result.code)
        val schedule = assertNotNull(result.schedule)
        assertEquals(2, fixture.store.taskReads)
        assertEquals(3, fixture.calendarCalls)
        assertEquals(listOf(200L, 300L, 400L), schedule.milestones.map { it.scheduledFor })
        assertEquals(fixture.policy.policyDigest, schedule.policy.policyDigest)
        assertEquals(fixture.store.currentTask.snapshotDigest, schedule.task.snapshotDigest)
        assertTrue(fixture.authorization.actions.contains("prepare-sla-schedule"))
        assertTrue(fixture.authorization.actions.contains("commit-sla-schedule"))

        val replay = fixture.create()
        assertEquals(WorkflowSlaRuntimeCode.REPLAYED, replay.code)
        assertEquals(3, fixture.calendarCalls)
        assertEquals(schedule.contentDigest, replay.schedule?.contentDigest)
    }

    @Test
    fun `task drift after calendar evaluation prevents durable creation`() {
        val fixture = Fixture(driftOnSecondTaskRead = true)
        val result = fixture.create()

        assertEquals(WorkflowSlaRuntimeCode.TASK_DRIFTED, result.code)
        assertEquals(null, fixture.store.schedule)
        assertEquals(3, fixture.calendarCalls)
        assertTrue(fixture.authorization.actions.none { it == "commit-sla-schedule" })
    }

    @Test
    fun `completed task and revoked authority suppress without action invocation`() {
        val completed = Fixture()
        val schedule = assertNotNull(completed.create().schedule)
        completed.clock.now = 2_000L
        completed.store.currentTask = completed.task(WorkflowSlaTaskStatus.COMPLETED, 2L, '9')
        val terminal = completed.dispatch(schedule)
        assertEquals(WorkflowSlaRuntimeCode.SUPPRESSED, terminal.code)
        assertEquals(0, completed.actionCalls)
        assertEquals(WorkflowSlaScheduleStatus.SUPPRESSED, terminal.schedule?.status)

        val revoked = Fixture(denyExecute = true)
        val revokedSchedule = assertNotNull(revoked.create().schedule)
        revoked.clock.now = 2_000L
        val denied = revoked.dispatch(revokedSchedule)
        assertEquals(WorkflowSlaRuntimeCode.SUPPRESSED, denied.code)
        assertEquals(0, revoked.actionCalls)
        assertTrue(revoked.authorization.actions.contains("execute-sla-action"))
    }

    @Test
    fun `exception after durable checkpoint becomes outcome unknown and requires reconciliation`() {
        val fixture = Fixture(actionThrows = true)
        val schedule = assertNotNull(fixture.create().schedule)
        fixture.clock.now = 2_000L
        val unknown = fixture.dispatch(schedule)

        assertEquals(WorkflowSlaRuntimeCode.ACTION_COMMITTED, unknown.code)
        val unknownSchedule = assertNotNull(unknown.schedule)
        assertEquals(
            WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN,
            unknownSchedule.milestone(WorkflowSlaMilestoneKind.REMINDER).status,
        )
        assertEquals(1, fixture.actionCalls)

        val request = assertNotNull(fixture.lastActionRequest)
        fixture.clock.now = 2_100L
        val receipt = WorkflowSlaActionReceipt.success(request, sha('8'), 2_050L, request.deadline)
        val reconciled = fixture.runtime.reconcile(
            WorkflowSlaReconcileCommand.of(
                context(),
                unknownSchedule.scheduleId,
                WorkflowSlaMilestoneKind.REMINDER,
                unknownSchedule.version,
                WorkflowSlaReconciliationResolution.APPLIED,
                receipt,
                sha('7'),
                null,
            ),
        )
        assertEquals(WorkflowSlaRuntimeCode.RECONCILED, reconciled.code)
        assertEquals(
            WorkflowSlaMilestoneStatus.SUCCEEDED,
            reconciled.schedule?.milestone(WorkflowSlaMilestoneKind.REMINDER)?.status,
        )
    }

    @Test
    fun `doctor reports only aggregate buckets and repair codes`() {
        val fixture = Fixture(actionThrows = true)
        val schedule = assertNotNull(fixture.create().schedule)
        fixture.clock.now = 2_000L
        fixture.dispatch(schedule)

        val report = WorkflowSlaDoctor(fixture.store, fixture.authorization).inspect(context(), 2_100L)
        assertEquals(WorkflowSlaDoctorStatus.CRITICAL, report.status)
        assertTrue(report.findings.any { it.code == "sla-outcome-unknown" })
        assertTrue(report.toString().contains("critical"))
        assertTrue(report.findings.all { !it.toString().contains("schedule-1") })
    }

    private class Fixture(
        driftOnSecondTaskRead: Boolean = false,
        denyExecute: Boolean = false,
        private val actionThrows: Boolean = false,
    ) {
        val clock = MutableClock(1_000L)
        val authorization = TestAuthorization(denyExecute)
        val store = MemorySlaStore(task(), driftOnSecondTaskRead)
        var calendarCalls: Int = 0
        var actionCalls: Int = 0
        var lastActionRequest: WorkflowSlaActionRequest? = null
        private val calendarRef = WorkflowBusinessCalendarRef.of(
            "calendar-provider",
            "calendar-cn",
            "calendar-r1",
            sha('c'),
        )
        private val calendarProfile = WorkflowBusinessCalendarProfile.of(
            "calendar-provider",
            "provider-r1",
            10_000L,
            1_024,
            1_024,
        )
        private val actionProfile = WorkflowSlaActionProfile.of(
            "sla-actions",
            "1",
            sha('e'),
            "action-provider",
            "provider-r1",
            10_000L,
            1_024,
            1_024,
            3,
            100L,
        )
        val policy = WorkflowSlaPolicy.standard(
            "standard-sla",
            "1",
            sha('d'),
            definitionRef(),
            "approve",
            WorkflowSlaCalendarBinding.of(
                "calendar-profile",
                "1",
                sha('a'),
                sha('b'),
                calendarRef,
                calendarProfile,
            ),
            actionProfile,
            100L,
            200L,
            300L,
        )
        private val calendar = WorkflowBusinessCalendar { request ->
            calendarCalls += 1
            clock.now += 1L
            CompletableFuture.completedFuture(
                WorkflowBusinessTimeResult.success(
                    request,
                    WorkflowBusinessTimeValue.instant(
                        request.instantEpochMilli + requireNotNull(request.workingDurationMillis),
                        request.calendar.version,
                    ),
                    clock.now,
                    request.context.deadlineEpochMilli,
                ),
            )
        }
        private val action = object : WorkflowSlaActionPort {
            override fun execute(request: WorkflowSlaActionRequest): CompletableFuture<WorkflowSlaActionReceipt> {
                actionCalls += 1
                lastActionRequest = request
                clock.now += 1L
                if (actionThrows) {
                    return CompletableFuture<WorkflowSlaActionReceipt>().also {
                        it.completeExceptionally(IllegalStateException("provider secret must not escape"))
                    }
                }
                return CompletableFuture.completedFuture(
                    WorkflowSlaActionReceipt.success(request, sha('6'), clock.now, request.deadline),
                )
            }
        }
        val runtime = WorkflowSlaRuntime(
            authorization,
            store,
            object : WorkflowSlaCalendarRegistry {
                override fun resolve(binding: WorkflowSlaCalendarBinding): WorkflowBusinessCalendar? =
                    calendar.takeIf { binding.bindingDigest == policy.calendarBinding.bindingDigest }
            },
            action,
            clock,
        )

        fun create(): WorkflowSlaRuntimeResult = runtime.createSchedule(
            WorkflowSlaCreateCommand.of(
                context(),
                "schedule-1",
                "idempotency-1",
                "instance-1",
                "task-1",
                policy,
            ),
        )

        fun dispatch(schedule: WorkflowSlaSchedule): WorkflowSlaRuntimeResult = runtime.dispatch(
            WorkflowSlaDispatchCommand.of(
                context(),
                schedule.scheduleId,
                WorkflowSlaMilestoneKind.REMINDER,
                schedule.version,
                "worker-1",
                "lease-1",
                1_000L,
            ),
        )

        fun task(
            status: WorkflowSlaTaskStatus = WorkflowSlaTaskStatus.ACTIVE,
            revision: Long = 1L,
            digestChar: Char = '1',
        ): WorkflowSlaTaskSnapshot = taskSnapshot(status, revision, digestChar, clock.now)
    }

    private class TestAuthorization(private val denyExecute: Boolean) : WorkflowRuntimeAuthorizationPort {
        val actions = ArrayList<String>()
        private var sequence = 0

        override fun authorize(request: WorkflowRuntimeAuthorizationRequest): WorkflowRuntimeAuthorizationDecision {
            sequence += 1
            actions += request.action.code
            val denied = denyExecute && request.action == WorkflowSlaRuntime.ACTION_EXECUTE
            return WorkflowRuntimeAuthorizationDecision.of(
                "authorization-$sequence",
                request.callContext.tenantId,
                request.callContext.actor,
                request.action,
                request.instanceId,
                request.requestDigest,
                if (denied) WorkflowRuntimeAuthorizationStatus.DENIED
                else WorkflowRuntimeAuthorizationStatus.AUTHORIZED,
                "authority-r$sequence",
                sha(if (denied) 'f' else 'a'),
                request.evaluatedAt,
                request.evaluatedAt + 20_000L,
            )
        }

        override fun issueHumanDecisionReceipt(
            request: WorkflowRuntimeHumanDecisionReceiptRequest,
        ): WorkflowHumanDecisionAuthorizationReceipt = throw UnsupportedOperationException("Not used by SLA runtime.")
    }

    private class MutableClock(var now: Long) : WorkflowWorkerClock {
        override fun currentTimeMillis(): Long = now
    }

    private class MemorySlaStore(
        task: WorkflowSlaTaskSnapshot,
        private val driftOnSecondTaskRead: Boolean,
    ) : WorkflowSlaDurableStore {
        var currentTask: WorkflowSlaTaskSnapshot = task
        var taskReads: Int = 0
        var schedule: WorkflowSlaSchedule? = null
        private var fence: Long = 0L

        override fun loadTask(tenantId: String, instanceId: String, workItemId: String): WorkflowSlaTaskSnapshot? {
            taskReads += 1
            if (currentTask.tenantId != tenantId || currentTask.instanceId != instanceId ||
                currentTask.workItemId != workItemId
            ) return null
            if (driftOnSecondTaskRead && taskReads == 2) {
                currentTask = taskSnapshot(WorkflowSlaTaskStatus.ACTIVE, 2L, '2', currentTask.observedAt + 1L)
            }
            return currentTask
        }

        override fun createSchedule(mutation: WorkflowSlaCreateMutation): WorkflowSlaStoreResult {
            val existing = schedule
            if (existing != null) {
                return if (existing.idempotencyKey == mutation.schedule.idempotencyKey &&
                    existing.contentDigest == mutation.schedule.contentDigest
                ) WorkflowSlaStoreResult.replayed(existing)
                else WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.CONFLICT)
            }
            if (!currentTask.sameTaskVersion(mutation.schedule.task) ||
                currentTask.snapshotDigest != mutation.taskGuardDigest ||
                currentTask.status != WorkflowSlaTaskStatus.ACTIVE
            ) return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
            schedule = mutation.schedule
            return WorkflowSlaStoreResult.applied(mutation.schedule)
        }

        override fun loadSchedule(tenantId: String, scheduleId: String): WorkflowSlaSchedule? = schedule?.takeIf {
            it.tenantId == tenantId && it.scheduleId == scheduleId
        }

        override fun loadScheduleByIdempotency(
            tenantId: String,
            idempotencyKey: String,
        ): WorkflowSlaSchedule? = schedule?.takeIf {
            it.tenantId == tenantId && it.idempotencyKey == idempotencyKey
        }

        override fun findDue(tenantId: String, eligibleAtOrBefore: Long, limit: Int): List<WorkflowSlaDueRef> {
            val record = schedule ?: return emptyList()
            if (record.tenantId != tenantId || limit <= 0) return emptyList()
            return record.milestones.filter { milestone ->
                (milestone.status == WorkflowSlaMilestoneStatus.SCHEDULED &&
                    milestone.scheduledFor <= eligibleAtOrBefore) ||
                    (milestone.status == WorkflowSlaMilestoneStatus.RETRY_WAIT &&
                        requireNotNull(milestone.nextAttemptAt) <= eligibleAtOrBefore)
            }.take(limit).map { milestone ->
                WorkflowSlaDueRef.of(
                    tenantId,
                    record.scheduleId,
                    milestone.policy.kind,
                    record.version,
                    milestone.nextAttemptAt ?: milestone.scheduledFor,
                )
            }
        }

        override fun claim(mutation: WorkflowSlaClaimMutation): WorkflowSlaStoreResult {
            val current = schedule ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_FOUND)
            if (current.version != mutation.expectedScheduleVersion) {
                return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.CONFLICT)
            }
            val milestone = current.milestone(mutation.milestoneKind)
            val eligible = milestone.status == WorkflowSlaMilestoneStatus.SCHEDULED &&
                milestone.scheduledFor <= mutation.now ||
                milestone.status == WorkflowSlaMilestoneStatus.RETRY_WAIT &&
                requireNotNull(milestone.nextAttemptAt) <= mutation.now
            if (!eligible) return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
            fence += 1L
            val lease = WorkflowSlaLease.of(
                mutation.leaseId,
                mutation.workerId,
                fence,
                mutation.now,
                mutation.now + mutation.leaseDurationMillis,
            )
            val leased = WorkflowSlaMilestoneRecord.restore(
                milestone.policy,
                milestone.scheduledFor,
                milestone.calendarEvaluationDigest,
                WorkflowSlaMilestoneStatus.LEASED,
                milestone.attempt + 1,
                lease,
                null,
                null,
                null,
                null,
                null,
                mutation.now,
            )
            val updated = update(current, replace(current, leased), mutation.now)
            schedule = updated
            return WorkflowSlaStoreResult.applied(updated)
        }

        override fun checkpointAction(checkpoint: WorkflowSlaActionCheckpoint): WorkflowSlaStoreResult {
            val current = schedule ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_FOUND)
            if (current.version != checkpoint.expectedScheduleVersion ||
                !currentTask.sameTaskVersion(checkpoint.task) ||
                currentTask.status != WorkflowSlaTaskStatus.ACTIVE
            ) return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
            val milestone = current.milestone(checkpoint.milestoneKind)
            if (milestone.status != WorkflowSlaMilestoneStatus.LEASED ||
                !leaseMatches(milestone.lease, checkpoint.lease)
            ) return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.LEASE_MISMATCH)
            val started = WorkflowSlaMilestoneRecord.restore(
                milestone.policy,
                milestone.scheduledFor,
                milestone.calendarEvaluationDigest,
                WorkflowSlaMilestoneStatus.ACTION_CALL_STARTED,
                milestone.attempt,
                checkpoint.lease,
                null,
                checkpoint.actionRequestDigest,
                checkpoint.authorizationEvidenceDigest,
                null,
                null,
                checkpoint.checkpointedAt,
            )
            val updated = update(current, replace(current, started), checkpoint.checkpointedAt)
            schedule = updated
            return WorkflowSlaStoreResult.applied(updated)
        }

        override fun completeAction(completion: WorkflowSlaActionCompletion): WorkflowSlaStoreResult {
            val current = schedule ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_FOUND)
            if (current.version != completion.expectedScheduleVersion) {
                return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.CONFLICT)
            }
            val milestone = current.milestone(completion.milestoneKind)
            if ((milestone.status != WorkflowSlaMilestoneStatus.LEASED &&
                    milestone.status != WorkflowSlaMilestoneStatus.ACTION_CALL_STARTED) ||
                !leaseMatches(milestone.lease, completion.lease)
            ) return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.LEASE_MISMATCH)
            val finished = WorkflowSlaMilestoneRecord.restore(
                milestone.policy,
                milestone.scheduledFor,
                milestone.calendarEvaluationDigest,
                completion.targetStatus,
                milestone.attempt,
                null,
                completion.nextAttemptAt,
                milestone.actionRequestDigest,
                milestone.authorizationEvidenceDigest,
                completion.actionReceipt,
                completion.outcomeEvidenceDigest,
                completion.completedAt,
            )
            val updated = update(current, replace(current, finished), completion.completedAt)
            schedule = updated
            return WorkflowSlaStoreResult.applied(updated)
        }

        override fun suppressRemaining(suppression: WorkflowSlaSuppression): WorkflowSlaStoreResult {
            val current = schedule ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_FOUND)
            if (current.version != suppression.expectedScheduleVersion) {
                return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.CONFLICT)
            }
            if (suppression.lease != null && current.milestones.none { leaseMatches(it.lease, suppression.lease) }) {
                return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.LEASE_MISMATCH)
            }
            val suppressed = current.milestones.map { milestone ->
                if (milestone.status == WorkflowSlaMilestoneStatus.SUCCEEDED ||
                    milestone.status == WorkflowSlaMilestoneStatus.SUPPRESSED ||
                    milestone.status == WorkflowSlaMilestoneStatus.TERMINAL_FAILURE ||
                    milestone.status == WorkflowSlaMilestoneStatus.ACTION_CALL_STARTED ||
                    milestone.status == WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN
                ) milestone else WorkflowSlaMilestoneRecord.restore(
                    milestone.policy,
                    milestone.scheduledFor,
                    milestone.calendarEvaluationDigest,
                    WorkflowSlaMilestoneStatus.SUPPRESSED,
                    milestone.attempt,
                    null,
                    null,
                    milestone.actionRequestDigest,
                    milestone.authorizationEvidenceDigest,
                    null,
                    suppression.evidenceDigest,
                    suppression.suppressedAt,
                )
            }
            val updated = update(current, suppressed, suppression.suppressedAt)
            schedule = updated
            return WorkflowSlaStoreResult.applied(updated)
        }

        override fun reconcile(reconciliation: WorkflowSlaReconciliation): WorkflowSlaStoreResult {
            val current = schedule ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_FOUND)
            if (current.version != reconciliation.expectedScheduleVersion) {
                return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.CONFLICT)
            }
            val milestone = current.milestone(reconciliation.milestoneKind)
            if (milestone.status != WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN) {
                return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
            }
            val target = when (reconciliation.resolution) {
                WorkflowSlaReconciliationResolution.APPLIED -> WorkflowSlaMilestoneStatus.SUCCEEDED
                WorkflowSlaReconciliationResolution.NOT_APPLIED -> WorkflowSlaMilestoneStatus.RETRY_WAIT
                WorkflowSlaReconciliationResolution.FAILED -> WorkflowSlaMilestoneStatus.TERMINAL_FAILURE
                else -> return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
            }
            val reconciled = WorkflowSlaMilestoneRecord.restore(
                milestone.policy,
                milestone.scheduledFor,
                milestone.calendarEvaluationDigest,
                target,
                milestone.attempt,
                null,
                reconciliation.nextAttemptAt,
                milestone.actionRequestDigest,
                reconciliation.authorizationEvidenceDigest,
                reconciliation.actionReceipt,
                reconciliation.evidenceDigest,
                reconciliation.reconciledAt,
            )
            val updated = update(current, replace(current, reconciled), reconciliation.reconciledAt)
            schedule = updated
            return WorkflowSlaStoreResult.applied(updated)
        }

        override fun diagnosticSnapshot(tenantId: String, observedAt: Long): WorkflowSlaDiagnosticSnapshot {
            val record = schedule
            val milestones = record?.milestones ?: emptyList()
            val due = milestones.filter { it.status == WorkflowSlaMilestoneStatus.SCHEDULED && it.scheduledFor <= observedAt }
            return WorkflowSlaDiagnosticSnapshot.of(
                if (record?.status == WorkflowSlaScheduleStatus.ACTIVE) 1L else 0L,
                due.size.toLong(),
                milestones.count { milestone ->
                    val lease = milestone.lease
                    lease != null && lease.expiresAt <= observedAt
                }.toLong(),
                milestones.count { it.status == WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN }.toLong(),
                milestones.count { it.status == WorkflowSlaMilestoneStatus.TERMINAL_FAILURE }.toLong(),
                due.minOfOrNull { it.scheduledFor },
                observedAt,
            )
        }

        private fun replace(
            current: WorkflowSlaSchedule,
            replacement: WorkflowSlaMilestoneRecord,
        ): List<WorkflowSlaMilestoneRecord> = current.milestones.map { milestone ->
            if (milestone.policy.kind == replacement.policy.kind) replacement else milestone
        }

        private fun update(
            current: WorkflowSlaSchedule,
            milestones: List<WorkflowSlaMilestoneRecord>,
            updatedAt: Long,
        ): WorkflowSlaSchedule {
            val attention = milestones.any {
                it.status == WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN ||
                    it.status == WorkflowSlaMilestoneStatus.TERMINAL_FAILURE
            }
            val allSuppressed = milestones.all { it.status == WorkflowSlaMilestoneStatus.SUPPRESSED }
            val allClosed = milestones.all {
                it.status == WorkflowSlaMilestoneStatus.SUCCEEDED ||
                    it.status == WorkflowSlaMilestoneStatus.SUPPRESSED
            }
            val status = when {
                attention -> WorkflowSlaScheduleStatus.ATTENTION_REQUIRED
                allSuppressed -> WorkflowSlaScheduleStatus.SUPPRESSED
                allClosed -> WorkflowSlaScheduleStatus.COMPLETED
                else -> WorkflowSlaScheduleStatus.ACTIVE
            }
            return WorkflowSlaSchedule.restore(
                current.tenantId,
                current.scheduleId,
                current.idempotencyKey,
                current.idempotencyBindingDigest,
                current.policy,
                current.task,
                milestones,
                status,
                current.version + 1L,
                current.prepareAuthorizationEvidenceDigest,
                current.commitAuthorizationEvidenceDigest,
                current.createdAt,
                updatedAt,
            )
        }

        private fun leaseMatches(left: WorkflowSlaLease?, right: WorkflowSlaLease?): Boolean =
            left != null && right != null && left.leaseId == right.leaseId && left.workerId == right.workerId &&
                left.fencingToken == right.fencingToken && left.expiresAt == right.expiresAt
    }

    companion object {
        private fun context(): WorkflowTrustedCallContext = WorkflowTrustedCallContext.of(
            "tenant-1",
            WorkflowPrincipalRef.of("user", "alice"),
            "authentication-1",
            sha('f'),
        )

        private fun definitionRef(): WorkflowDefinitionRef = WorkflowDefinitionRef.of(
            "approval",
            "1",
            sha('3'),
        )

        private fun taskSnapshot(
            status: WorkflowSlaTaskStatus,
            revision: Long,
            digestChar: Char,
            observedAt: Long,
        ): WorkflowSlaTaskSnapshot = WorkflowSlaTaskSnapshot.of(
            "tenant-1",
            "instance-1",
            "task-1",
            "definition-1",
            definitionRef(),
            "approve",
            WorkflowSubjectSnapshot.of(
                WorkflowSubjectRef.of("case", "case-1"),
                "subject-r1",
                sha('4'),
            ),
            status,
            revision,
            sha(digestChar),
            100L,
            observedAt,
        )

        private fun sha(character: Char): String = character.toString().repeat(64)
    }
}
