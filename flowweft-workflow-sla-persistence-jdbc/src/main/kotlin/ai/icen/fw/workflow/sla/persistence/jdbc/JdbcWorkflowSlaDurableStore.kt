package ai.icen.fw.workflow.sla.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.runtime.WorkflowBusinessCalendarProfile
import ai.icen.fw.workflow.sla.WorkflowSlaActionCheckpoint
import ai.icen.fw.workflow.sla.WorkflowSlaActionCompletion
import ai.icen.fw.workflow.sla.WorkflowSlaActionKind
import ai.icen.fw.workflow.sla.WorkflowSlaActionOutcome
import ai.icen.fw.workflow.sla.WorkflowSlaActionProfile
import ai.icen.fw.workflow.sla.WorkflowSlaActionReceipt
import ai.icen.fw.workflow.sla.WorkflowSlaCalendarBinding
import ai.icen.fw.workflow.sla.WorkflowSlaClaimMutation
import ai.icen.fw.workflow.sla.WorkflowSlaCreateMutation
import ai.icen.fw.workflow.sla.WorkflowSlaDiagnosticSnapshot
import ai.icen.fw.workflow.sla.WorkflowSlaDueRef
import ai.icen.fw.workflow.sla.WorkflowSlaDurableStore
import ai.icen.fw.workflow.sla.WorkflowSlaLease
import ai.icen.fw.workflow.sla.WorkflowSlaMilestoneKind
import ai.icen.fw.workflow.sla.WorkflowSlaMilestonePolicy
import ai.icen.fw.workflow.sla.WorkflowSlaMilestoneRecord
import ai.icen.fw.workflow.sla.WorkflowSlaMilestoneStatus
import ai.icen.fw.workflow.sla.WorkflowSlaPolicy
import ai.icen.fw.workflow.sla.WorkflowSlaReconciliation
import ai.icen.fw.workflow.sla.WorkflowSlaReconciliationResolution
import ai.icen.fw.workflow.sla.WorkflowSlaSchedule
import ai.icen.fw.workflow.sla.WorkflowSlaScheduleStatus
import ai.icen.fw.workflow.sla.WorkflowSlaStoreCode
import ai.icen.fw.workflow.sla.WorkflowSlaStoreResult
import ai.icen.fw.workflow.sla.WorkflowSlaSuppression
import ai.icen.fw.workflow.sla.WorkflowSlaTaskSnapshot
import ai.icen.fw.workflow.sla.WorkflowSlaTaskStatus
import ai.icen.fw.workflow.spi.WorkflowBusinessCalendarRef
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Production JDBC implementation of the Workflow SLA durable boundary.
 *
 * Every mutation locks the tenant-scoped schedule row, checks the expected version, persists an
 * exact operation digest and advances the version in one short local transaction. Provider,
 * calendar, identity and authorization calls never occur in this adapter. Commit exceptions are
 * reconciled only from the exact operation digest; an unprovable outcome remains unknown.
 */
class JdbcWorkflowSlaDurableStore @JvmOverloads constructor(
    dataSource: DataSource,
    configuredDialect: WorkflowSlaJdbcDialect? = null,
) : WorkflowSlaDurableStore {
    private val jdbc = WorkflowSlaJdbcTransactions(dataSource, configuredDialect)

    override fun loadTask(
        tenantId: String,
        instanceId: String,
        workItemId: String,
    ): WorkflowSlaTaskSnapshot? = try {
        jdbc.read { connection, _ -> selectTask(connection, tenantId, instanceId, workItemId, false) }
    } catch (failure: SQLException) {
        throw IllegalStateException("Workflow SLA task projection is unavailable.", failure)
    }

    override fun createSchedule(mutation: WorkflowSlaCreateMutation): WorkflowSlaStoreResult = try {
        jdbc.transaction { connection, _ -> createInTransaction(connection, mutation) }
    } catch (_: SQLException) {
        resolveCreateAfterFailure(mutation)
    }

    override fun loadSchedule(tenantId: String, scheduleId: String): WorkflowSlaSchedule? = try {
        jdbc.read { connection, _ ->
            selectStoredSchedule(connection, tenantId, "schedule_id", scheduleId, false)?.schedule
        }
    } catch (failure: SQLException) {
        throw IllegalStateException("Workflow SLA schedule persistence is unavailable.", failure)
    }

    override fun loadScheduleByIdempotency(
        tenantId: String,
        idempotencyKey: String,
    ): WorkflowSlaSchedule? = try {
        jdbc.read { connection, _ ->
            selectStoredSchedule(connection, tenantId, "idempotency_key", idempotencyKey, false)?.schedule
        }
    } catch (failure: SQLException) {
        throw IllegalStateException("Workflow SLA idempotency persistence is unavailable.", failure)
    }

    override fun findDue(
        tenantId: String,
        eligibleAtOrBefore: Long,
        limit: Int,
    ): List<WorkflowSlaDueRef> {
        require(eligibleAtOrBefore >= 0L && limit in 1..MAX_QUERY_LIMIT) {
            "Workflow SLA due query is outside the supported range."
        }
        return try {
            jdbc.read { connection, _ ->
                connection.prepareStatement(FIND_DUE_SQL).use { statement ->
                    statement.setString(1, tenantId)
                    statement.setString(2, WorkflowSlaScheduleStatus.ACTIVE.code)
                    statement.setString(3, WorkflowSlaScheduleStatus.ATTENTION_REQUIRED.code)
                    statement.setString(4, WorkflowSlaMilestoneStatus.SCHEDULED.code)
                    statement.setLong(5, eligibleAtOrBefore)
                    statement.setString(6, WorkflowSlaMilestoneStatus.RETRY_WAIT.code)
                    statement.setLong(7, eligibleAtOrBefore)
                    statement.setString(8, WorkflowSlaMilestoneStatus.LEASED.code)
                    statement.setLong(9, eligibleAtOrBefore)
                    statement.setInt(10, limit)
                    statement.executeQuery().use { result ->
                        val due = ArrayList<WorkflowSlaDueRef>()
                        while (result.next()) {
                            due += WorkflowSlaDueRef.of(
                                result.getString("tenant_id"),
                                result.getString("schedule_id"),
                                WorkflowSlaMilestoneKind.of(result.getString("milestone_kind")),
                                result.getLong("schedule_version"),
                                result.getLong("eligible_time"),
                            )
                        }
                        due
                    }
                }
            }
        } catch (failure: SQLException) {
            throw IllegalStateException("Workflow SLA due query is unavailable.", failure)
        }
    }

    override fun claim(mutation: WorkflowSlaClaimMutation): WorkflowSlaStoreResult = try {
        jdbc.transaction { connection, _ -> claimInTransaction(connection, mutation) }
    } catch (_: SQLException) {
        resolveAfterFailure(
            mutation.tenantId,
            mutation.scheduleId,
            mutation.expectedScheduleVersion,
            OPERATION_CLAIM,
            mutation.mutationDigest,
        )
    }

    override fun checkpointAction(checkpoint: WorkflowSlaActionCheckpoint): WorkflowSlaStoreResult = try {
        jdbc.transaction { connection, _ -> checkpointInTransaction(connection, checkpoint) }
    } catch (_: SQLException) {
        resolveAfterFailure(
            checkpoint.tenantId,
            checkpoint.scheduleId,
            checkpoint.expectedScheduleVersion,
            OPERATION_CHECKPOINT,
            checkpoint.checkpointDigest,
        )
    }

    override fun completeAction(completion: WorkflowSlaActionCompletion): WorkflowSlaStoreResult = try {
        jdbc.transaction { connection, _ -> completeInTransaction(connection, completion) }
    } catch (_: SQLException) {
        resolveAfterFailure(
            completion.tenantId,
            completion.scheduleId,
            completion.expectedScheduleVersion,
            OPERATION_COMPLETE,
            completion.completionDigest,
        )
    }

    override fun suppressRemaining(suppression: WorkflowSlaSuppression): WorkflowSlaStoreResult {
        val digest = suppressionDigest(suppression)
        return try {
            jdbc.transaction { connection, _ -> suppressInTransaction(connection, suppression, digest) }
        } catch (_: SQLException) {
            resolveAfterFailure(
                suppression.tenantId,
                suppression.scheduleId,
                suppression.expectedScheduleVersion,
                OPERATION_SUPPRESS,
                digest,
            )
        }
    }

    override fun reconcile(reconciliation: WorkflowSlaReconciliation): WorkflowSlaStoreResult {
        val digest = reconciliationDigest(reconciliation)
        return try {
            jdbc.transaction { connection, _ -> reconcileInTransaction(connection, reconciliation, digest) }
        } catch (_: SQLException) {
            resolveAfterFailure(
                reconciliation.tenantId,
                reconciliation.scheduleId,
                reconciliation.expectedScheduleVersion,
                OPERATION_RECONCILE,
                digest,
            )
        }
    }

    override fun diagnosticSnapshot(tenantId: String, observedAt: Long): WorkflowSlaDiagnosticSnapshot {
        require(observedAt >= 0L) { "Workflow SLA diagnostic time is invalid." }
        return try {
            jdbc.read { connection, _ -> diagnosticSnapshot(connection, tenantId, observedAt) }
        } catch (failure: SQLException) {
            throw IllegalStateException("Workflow SLA diagnostics are unavailable.", failure)
        }
    }

    private fun createInTransaction(
        connection: Connection,
        mutation: WorkflowSlaCreateMutation,
    ): WorkflowSlaStoreResult {
        val candidate = mutation.schedule
        val byIdempotency = selectStoredSchedule(
            connection,
            candidate.tenantId,
            "idempotency_key",
            candidate.idempotencyKey,
            true,
        )
        if (byIdempotency != null) return classifyCreateReplay(byIdempotency.schedule, candidate)
        val byId = selectStoredSchedule(connection, candidate.tenantId, "schedule_id", candidate.scheduleId, true)
        if (byId != null) return classifyCreateReplay(byId.schedule, candidate)
        val byTask = selectStoredScheduleByTask(
            connection,
            candidate.tenantId,
            candidate.task.instanceId,
            candidate.task.workItemId,
            true,
        )
        if (byTask != null) return classifyCreateReplay(byTask.schedule, candidate)

        val currentTask = selectTask(
            connection,
            candidate.tenantId,
            candidate.task.instanceId,
            candidate.task.workItemId,
            true,
        ) ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        if (currentTask.status != WorkflowSlaTaskStatus.ACTIVE ||
            !currentTask.sameTaskVersion(candidate.task) ||
            currentTask.snapshotDigest != mutation.taskGuardDigest ||
            currentTask.revision != mutation.expectedTaskRevision
        ) return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)

        insertSchedule(connection, candidate, mutation.mutationDigest)
        candidate.milestones.forEachIndexed { ordinal, milestone ->
            insertMilestone(connection, candidate, milestone, ordinal, 0L)
        }
        return WorkflowSlaStoreResult.applied(candidate)
    }

    private fun claimInTransaction(
        connection: Connection,
        mutation: WorkflowSlaClaimMutation,
    ): WorkflowSlaStoreResult {
        val stored = selectStoredSchedule(connection, mutation.tenantId, "schedule_id", mutation.scheduleId, true)
            ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_FOUND)
        exactReplay(stored, mutation.expectedScheduleVersion, OPERATION_CLAIM, mutation.mutationDigest)?.let {
            return it
        }
        if (stored.schedule.version != mutation.expectedScheduleVersion) {
            return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.CONFLICT)
        }
        val milestone = stored.milestone(mutation.milestoneKind)
            ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        if (!isClaimable(milestone.record, mutation.now)) {
            return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        }
        val reclaimedBeforeCheckpoint = milestone.record.status == WorkflowSlaMilestoneStatus.LEASED
        if (milestone.fenceSequence == Long.MAX_VALUE ||
            (!reclaimedBeforeCheckpoint && milestone.record.attempt == Int.MAX_VALUE)
        ) {
            return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        }
        val nextAttempt = if (reclaimedBeforeCheckpoint) {
            milestone.record.attempt
        } else {
            milestone.record.attempt + 1
        }
        if (nextAttempt > stored.schedule.policy.actionProfile.maximumAttempts) {
            return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        }
        val fence = milestone.fenceSequence + 1L
        val lease = WorkflowSlaLease.of(
            mutation.leaseId,
            mutation.workerId,
            fence,
            mutation.now,
            mutation.now + mutation.leaseDurationMillis,
        )
        val claimed = WorkflowSlaMilestoneRecord.restore(
            milestone.record.policy,
            milestone.record.scheduledFor,
            milestone.record.calendarEvaluationDigest,
            WorkflowSlaMilestoneStatus.LEASED,
            nextAttempt,
            lease,
            null,
            null,
            null,
            null,
            null,
            mutation.now,
        )
        return persistMutation(
            connection,
            stored,
            replace(stored.schedule, claimed),
            mapOf(mutation.milestoneKind to fence),
            mutation.now,
            OPERATION_CLAIM,
            mutation.mutationDigest,
        )
    }

    private fun checkpointInTransaction(
        connection: Connection,
        checkpoint: WorkflowSlaActionCheckpoint,
    ): WorkflowSlaStoreResult {
        val stored = selectStoredSchedule(
            connection,
            checkpoint.tenantId,
            "schedule_id",
            checkpoint.scheduleId,
            true,
        )
            ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_FOUND)
        exactReplay(
            stored,
            checkpoint.expectedScheduleVersion,
            OPERATION_CHECKPOINT,
            checkpoint.checkpointDigest,
        )?.let { return it }
        if (stored.schedule.version != checkpoint.expectedScheduleVersion) {
            return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.CONFLICT)
        }
        val task = selectTask(
            connection,
            checkpoint.tenantId,
            checkpoint.task.instanceId,
            checkpoint.task.workItemId,
            true,
        ) ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        if (task.status != WorkflowSlaTaskStatus.ACTIVE || !task.sameTaskVersion(checkpoint.task) ||
            task.snapshotDigest != checkpoint.task.snapshotDigest ||
            !task.matchesScheduleBinding(stored.schedule)
        ) return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)

        val milestone = stored.milestone(checkpoint.milestoneKind)
            ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        if (milestone.record.status != WorkflowSlaMilestoneStatus.LEASED ||
            !leaseMatches(milestone.record.lease, checkpoint.lease) ||
            milestone.fenceSequence != checkpoint.lease.fencingToken
        ) return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.LEASE_MISMATCH)
        val started = WorkflowSlaMilestoneRecord.restore(
            milestone.record.policy,
            milestone.record.scheduledFor,
            milestone.record.calendarEvaluationDigest,
            WorkflowSlaMilestoneStatus.ACTION_CALL_STARTED,
            milestone.record.attempt,
            checkpoint.lease,
            null,
            checkpoint.actionRequestDigest,
            checkpoint.authorizationEvidenceDigest,
            null,
            null,
            checkpoint.checkpointedAt,
        )
        return persistMutation(
            connection,
            stored,
            replace(stored.schedule, started),
            emptyMap(),
            checkpoint.checkpointedAt,
            OPERATION_CHECKPOINT,
            checkpoint.checkpointDigest,
        )
    }

    private fun completeInTransaction(
        connection: Connection,
        completion: WorkflowSlaActionCompletion,
    ): WorkflowSlaStoreResult {
        val stored = selectStoredSchedule(connection, completion.tenantId, "schedule_id", completion.scheduleId, true)
            ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_FOUND)
        exactReplay(stored, completion.expectedScheduleVersion, OPERATION_COMPLETE, completion.completionDigest)?.let {
            return it
        }
        if (stored.schedule.version != completion.expectedScheduleVersion) {
            return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.CONFLICT)
        }
        val milestone = stored.milestone(completion.milestoneKind)
            ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        if ((milestone.record.status != WorkflowSlaMilestoneStatus.LEASED &&
                milestone.record.status != WorkflowSlaMilestoneStatus.ACTION_CALL_STARTED) ||
            !leaseMatches(milestone.record.lease, completion.lease) ||
            milestone.fenceSequence != completion.lease.fencingToken
        ) return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.LEASE_MISMATCH)
        val actionReceipt = completion.actionReceipt
        if (actionReceipt != null &&
            !receiptMatchesBinding(
                stored.schedule,
                milestone.record,
                actionReceipt,
                completion.completedAt,
            )
        ) return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        val completed = WorkflowSlaMilestoneRecord.restore(
            milestone.record.policy,
            milestone.record.scheduledFor,
            milestone.record.calendarEvaluationDigest,
            completion.targetStatus,
            milestone.record.attempt,
            null,
            completion.nextAttemptAt,
            milestone.record.actionRequestDigest,
            milestone.record.authorizationEvidenceDigest,
            completion.actionReceipt,
            completion.outcomeEvidenceDigest,
            completion.completedAt,
        )
        return persistMutation(
            connection,
            stored,
            replace(stored.schedule, completed),
            emptyMap(),
            completion.completedAt,
            OPERATION_COMPLETE,
            completion.completionDigest,
        )
    }

    private fun suppressInTransaction(
        connection: Connection,
        suppression: WorkflowSlaSuppression,
        operationDigest: String,
    ): WorkflowSlaStoreResult {
        val stored = selectStoredSchedule(connection, suppression.tenantId, "schedule_id", suppression.scheduleId, true)
            ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_FOUND)
        exactReplay(stored, suppression.expectedScheduleVersion, OPERATION_SUPPRESS, operationDigest)?.let {
            return it
        }
        if (stored.schedule.version != suppression.expectedScheduleVersion) {
            return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.CONFLICT)
        }
        val suppressionLease = suppression.lease
        if (suppressionLease != null && stored.milestones.none {
                leaseMatches(it.record.lease, suppressionLease) &&
                    it.fenceSequence == suppressionLease.fencingToken
            }
        ) return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.LEASE_MISMATCH)

        val milestones = stored.schedule.milestones.map { milestone ->
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
        return persistMutation(
            connection,
            stored,
            milestones,
            emptyMap(),
            suppression.suppressedAt,
            OPERATION_SUPPRESS,
            operationDigest,
        )
    }

    private fun reconcileInTransaction(
        connection: Connection,
        reconciliation: WorkflowSlaReconciliation,
        operationDigest: String,
    ): WorkflowSlaStoreResult {
        val stored = selectStoredSchedule(
            connection,
            reconciliation.tenantId,
            "schedule_id",
            reconciliation.scheduleId,
            true,
        ) ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_FOUND)
        exactReplay(stored, reconciliation.expectedScheduleVersion, OPERATION_RECONCILE, operationDigest)?.let {
            return it
        }
        if (stored.schedule.version != reconciliation.expectedScheduleVersion) {
            return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.CONFLICT)
        }
        val milestone = stored.milestone(reconciliation.milestoneKind)
            ?: return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        if (milestone.record.status != WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN) {
            return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        }
        if (!receiptMatchesBinding(
                stored.schedule,
                milestone.record,
                reconciliation.actionReceipt,
                reconciliation.reconciledAt,
            )) {
            return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        }
        val target = when (reconciliation.resolution) {
            WorkflowSlaReconciliationResolution.APPLIED -> WorkflowSlaMilestoneStatus.SUCCEEDED
            WorkflowSlaReconciliationResolution.NOT_APPLIED -> WorkflowSlaMilestoneStatus.RETRY_WAIT
            WorkflowSlaReconciliationResolution.FAILED -> WorkflowSlaMilestoneStatus.TERMINAL_FAILURE
            else -> return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        }
        val reconciled = WorkflowSlaMilestoneRecord.restore(
            milestone.record.policy,
            milestone.record.scheduledFor,
            milestone.record.calendarEvaluationDigest,
            target,
            milestone.record.attempt,
            null,
            reconciliation.nextAttemptAt,
            milestone.record.actionRequestDigest,
            reconciliation.authorizationEvidenceDigest,
            reconciliation.actionReceipt,
            reconciliation.evidenceDigest,
            reconciliation.reconciledAt,
        )
        return persistMutation(
            connection,
            stored,
            replace(stored.schedule, reconciled),
            emptyMap(),
            reconciliation.reconciledAt,
            OPERATION_RECONCILE,
            operationDigest,
        )
    }

    private fun persistMutation(
        connection: Connection,
        stored: StoredSchedule,
        milestones: List<WorkflowSlaMilestoneRecord>,
        fenceOverrides: Map<WorkflowSlaMilestoneKind, Long>,
        updatedAt: Long,
        operationCode: String,
        operationDigest: String,
    ): WorkflowSlaStoreResult {
        if (stored.schedule.version == Long.MAX_VALUE || updatedAt < stored.schedule.updatedAt) {
            return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.NOT_ELIGIBLE)
        }
        val next = nextSchedule(stored.schedule, milestones, updatedAt)
        val updated = connection.prepareStatement(UPDATE_SCHEDULE_SQL).use { statement ->
            statement.setString(1, next.status.code)
            statement.setLong(2, next.version)
            statement.setString(3, next.contentDigest)
            statement.setString(4, operationCode)
            statement.setString(5, operationDigest)
            statement.setLong(6, stored.schedule.version)
            statement.setLong(7, updatedAt)
            statement.setString(8, stored.schedule.tenantId)
            statement.setString(9, stored.schedule.scheduleId)
            statement.setLong(10, stored.schedule.version)
            statement.executeUpdate()
        }
        if (updated != 1) return WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.CONFLICT)

        next.milestones.forEach { milestone ->
            val previous = stored.milestone(milestone.policy.kind)
                ?: throw IllegalStateException("Workflow SLA milestone disappeared while locked.")
            val fenceSequence = fenceOverrides[milestone.policy.kind] ?: previous.fenceSequence
            if (updateMilestone(connection, next.tenantId, next.scheduleId, milestone, fenceSequence) != 1) {
                throw IllegalStateException("Workflow SLA milestone update was not applied.")
            }
        }
        return WorkflowSlaStoreResult.applied(next)
    }

    private fun nextSchedule(
        current: WorkflowSlaSchedule,
        milestones: List<WorkflowSlaMilestoneRecord>,
        updatedAt: Long,
    ): WorkflowSlaSchedule = WorkflowSlaSchedule.restore(
        current.tenantId,
        current.scheduleId,
        current.idempotencyKey,
        current.idempotencyBindingDigest,
        current.policy,
        current.task,
        milestones,
        scheduleStatus(milestones),
        current.version + 1L,
        current.prepareAuthorizationEvidenceDigest,
        current.commitAuthorizationEvidenceDigest,
        current.createdAt,
        updatedAt,
    )

    private fun scheduleStatus(milestones: List<WorkflowSlaMilestoneRecord>): WorkflowSlaScheduleStatus {
        val hasAttention = milestones.any {
            it.status == WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN ||
                it.status == WorkflowSlaMilestoneStatus.TERMINAL_FAILURE
        }
        val allSuppressed = milestones.all { it.status == WorkflowSlaMilestoneStatus.SUPPRESSED }
        val allClosed = milestones.all {
            it.status == WorkflowSlaMilestoneStatus.SUCCEEDED ||
                it.status == WorkflowSlaMilestoneStatus.SUPPRESSED
        }
        return when {
            hasAttention -> WorkflowSlaScheduleStatus.ATTENTION_REQUIRED
            allSuppressed -> WorkflowSlaScheduleStatus.SUPPRESSED
            allClosed -> WorkflowSlaScheduleStatus.COMPLETED
            else -> WorkflowSlaScheduleStatus.ACTIVE
        }
    }

    private fun replace(
        schedule: WorkflowSlaSchedule,
        replacement: WorkflowSlaMilestoneRecord,
    ): List<WorkflowSlaMilestoneRecord> = schedule.milestones.map { milestone ->
        if (milestone.policy.kind == replacement.policy.kind) replacement else milestone
    }

    private fun isClaimable(milestone: WorkflowSlaMilestoneRecord, now: Long): Boolean =
        milestone.status == WorkflowSlaMilestoneStatus.SCHEDULED && milestone.scheduledFor <= now ||
            milestone.status == WorkflowSlaMilestoneStatus.RETRY_WAIT &&
            requireNotNull(milestone.nextAttemptAt) <= now ||
            milestone.status == WorkflowSlaMilestoneStatus.LEASED &&
            requireNotNull(milestone.lease).expiresAt <= now

    private fun leaseMatches(left: WorkflowSlaLease?, right: WorkflowSlaLease?): Boolean =
        left != null && right != null && left.leaseId == right.leaseId &&
            left.workerId == right.workerId && left.fencingToken == right.fencingToken &&
            left.acquiredAt == right.acquiredAt && left.expiresAt == right.expiresAt

    private fun receiptMatchesBinding(
        schedule: WorkflowSlaSchedule,
        milestone: WorkflowSlaMilestoneRecord,
        receipt: WorkflowSlaActionReceipt,
        observedAt: Long,
    ): Boolean = receipt.scheduleId == schedule.scheduleId &&
        receipt.milestoneKind == milestone.policy.kind &&
        receipt.providerId == schedule.policy.actionProfile.providerId &&
        receipt.providerRevision == schedule.policy.actionProfile.providerRevision &&
        receipt.actionProfileDigest == schedule.policy.actionProfile.bindingDigest &&
        milestone.actionRequestDigest != null && receipt.requestDigest == milestone.actionRequestDigest &&
        receipt.completedAt <= observedAt && observedAt <= receipt.expiresAt

    private fun exactReplay(
        stored: StoredSchedule,
        expectedVersion: Long,
        operationCode: String,
        operationDigest: String,
    ): WorkflowSlaStoreResult? = if (
        expectedVersion < Long.MAX_VALUE &&
        stored.schedule.version == expectedVersion + 1L &&
        stored.lastOperationBaseVersion == expectedVersion &&
        stored.lastOperationCode == operationCode &&
        stored.lastOperationDigest == operationDigest
    ) WorkflowSlaStoreResult.replayed(stored.schedule) else null

    private fun resolveAfterFailure(
        tenantId: String,
        scheduleId: String,
        expectedVersion: Long,
        operationCode: String,
        operationDigest: String,
    ): WorkflowSlaStoreResult = try {
        jdbc.read { connection, _ ->
            val stored = selectStoredSchedule(connection, tenantId, "schedule_id", scheduleId, false)
                ?: return@read WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.OUTCOME_UNKNOWN)
            exactReplay(stored, expectedVersion, operationCode, operationDigest)
                ?: if (stored.schedule.version > expectedVersion) {
                    WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.CONFLICT)
                } else {
                    WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.OUTCOME_UNKNOWN)
                }
        }
    } catch (_: RuntimeException) {
        WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.OUTCOME_UNKNOWN)
    } catch (_: SQLException) {
        WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.OUTCOME_UNKNOWN)
    }

    private fun resolveCreateAfterFailure(mutation: WorkflowSlaCreateMutation): WorkflowSlaStoreResult = try {
        jdbc.read { connection, _ ->
            val candidate = mutation.schedule
            val byId = selectStoredSchedule(connection, candidate.tenantId, "schedule_id", candidate.scheduleId, false)
            val byKey = selectStoredSchedule(
                connection,
                candidate.tenantId,
                "idempotency_key",
                candidate.idempotencyKey,
                false,
            )
            val byTask = selectStoredScheduleByTask(
                connection,
                candidate.tenantId,
                candidate.task.instanceId,
                candidate.task.workItemId,
                false,
            )
            val existing = byId ?: byKey ?: byTask
                ?: return@read WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.OUTCOME_UNKNOWN)
            classifyCreateReplay(existing.schedule, candidate)
        }
    } catch (_: RuntimeException) {
        WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.OUTCOME_UNKNOWN)
    } catch (_: SQLException) {
        WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.OUTCOME_UNKNOWN)
    }

    private fun classifyCreateReplay(
        existing: WorkflowSlaSchedule,
        candidate: WorkflowSlaSchedule,
    ): WorkflowSlaStoreResult = if (
        existing.tenantId == candidate.tenantId &&
        existing.scheduleId == candidate.scheduleId &&
        existing.idempotencyKey == candidate.idempotencyKey &&
        existing.idempotencyBindingDigest == candidate.idempotencyBindingDigest &&
        existing.contentDigest == candidate.contentDigest
    ) WorkflowSlaStoreResult.replayed(existing)
    else WorkflowSlaStoreResult.failed(WorkflowSlaStoreCode.CONFLICT)

    private fun suppressionDigest(suppression: WorkflowSlaSuppression): String = WorkflowSlaJdbcDigests.operation(
        "flowweft-workflow-sla-jdbc-suppression-v1",
        suppression.tenantId,
        suppression.scheduleId,
        suppression.expectedScheduleVersion,
        suppression.lease?.leaseId,
        suppression.lease?.workerId,
        suppression.lease?.fencingToken,
        suppression.lease?.acquiredAt,
        suppression.lease?.expiresAt,
        suppression.evidenceDigest,
        suppression.suppressedAt,
    )

    private fun reconciliationDigest(reconciliation: WorkflowSlaReconciliation): String =
        WorkflowSlaJdbcDigests.operation(
            "flowweft-workflow-sla-jdbc-reconciliation-v1",
            reconciliation.tenantId,
            reconciliation.scheduleId,
            reconciliation.milestoneKind.code,
            reconciliation.expectedScheduleVersion,
            reconciliation.resolution.code,
            reconciliation.actionReceipt.receiptDigest,
            reconciliation.evidenceDigest,
            reconciliation.authorizationEvidenceDigest,
            reconciliation.nextAttemptAt,
            reconciliation.reconciledAt,
        )

    private fun diagnosticSnapshot(
        connection: Connection,
        tenantId: String,
        observedAt: Long,
    ): WorkflowSlaDiagnosticSnapshot {
        val activeSchedules = connection.prepareStatement(COUNT_ACTIVE_SCHEDULES_SQL).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, WorkflowSlaScheduleStatus.ACTIVE.code)
            statement.executeQuery().use { result ->
                check(result.next()) { "Workflow SLA schedule diagnostic row is absent." }
                result.getLong(1)
            }
        }
        return connection.prepareStatement(MILESTONE_DIAGNOSTIC_SQL).use { statement ->
            statement.setString(1, WorkflowSlaMilestoneStatus.SCHEDULED.code)
            statement.setLong(2, observedAt)
            statement.setString(3, WorkflowSlaMilestoneStatus.RETRY_WAIT.code)
            statement.setLong(4, observedAt)
            statement.setString(5, WorkflowSlaMilestoneStatus.LEASED.code)
            statement.setString(6, WorkflowSlaMilestoneStatus.ACTION_CALL_STARTED.code)
            statement.setLong(7, observedAt)
            statement.setString(8, WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN.code)
            statement.setString(9, WorkflowSlaMilestoneStatus.TERMINAL_FAILURE.code)
            statement.setString(10, WorkflowSlaMilestoneStatus.SCHEDULED.code)
            statement.setLong(11, observedAt)
            statement.setString(12, WorkflowSlaMilestoneStatus.RETRY_WAIT.code)
            statement.setLong(13, observedAt)
            statement.setString(14, tenantId)
            statement.executeQuery().use { result ->
                check(result.next()) { "Workflow SLA milestone diagnostic row is absent." }
                WorkflowSlaDiagnosticSnapshot.of(
                    activeSchedules,
                    result.getLong("due_count"),
                    result.getLong("expired_lease_count"),
                    result.getLong("unknown_count"),
                    result.getLong("failure_count"),
                    nullableLong(result, "oldest_due_time"),
                    observedAt,
                )
            }
        }
    }

    private fun selectTask(
        connection: Connection,
        tenantId: String,
        instanceId: String,
        workItemId: String,
        forUpdate: Boolean,
    ): WorkflowSlaTaskSnapshot? {
        val sql = SELECT_TASK_SQL + if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, instanceId)
            statement.setString(3, workItemId)
            statement.executeQuery().use { result ->
                if (result.next()) mapAuthoritativeTask(result) else null
            }
        }
    }

    private fun mapAuthoritativeTask(result: ResultSet): WorkflowSlaTaskSnapshot {
        val instanceStatus = result.getString("instance_status")
        val humanTaskStatus = result.getString("human_task_status")
        val status = when (instanceStatus) {
            "running", "waiting" -> when (humanTaskStatus) {
                "active" -> WorkflowSlaTaskStatus.ACTIVE
                "approved", "rejected" -> WorkflowSlaTaskStatus.COMPLETED
                "incident" -> WorkflowSlaTaskStatus.INCIDENT
                "waiting-participants" -> WorkflowSlaTaskStatus.SUSPENDED
                else -> WorkflowSlaTaskStatus.SUSPENDED
            }
            "completed" -> WorkflowSlaTaskStatus.COMPLETED
            "cancelled", "terminated" -> WorkflowSlaTaskStatus.CANCELLED
            "incident" -> WorkflowSlaTaskStatus.INCIDENT
            else -> WorkflowSlaTaskStatus.SUSPENDED
        }
        val activatedAt = result.getLong("task_created_time")
        val observedAt = maxOf(activatedAt, result.getLong("task_updated_time"))
        return WorkflowSlaTaskSnapshot.of(
            result.getString("tenant_id"),
            result.getString("instance_id"),
            result.getString("work_item_id"),
            result.getString("definition_id"),
            WorkflowDefinitionRef.of(
                result.getString("definition_key"),
                result.getString("definition_version"),
                result.getString("definition_digest"),
            ),
            result.getString("node_id"),
            WorkflowSubjectSnapshot.of(
                WorkflowSubjectRef.of(result.getString("subject_type"), result.getString("subject_id")),
                result.getString("subject_revision"),
                result.getString("subject_digest"),
            ),
            status,
            result.getLong("task_revision"),
            result.getString("task_digest"),
            activatedAt,
            observedAt,
        )
    }

    private fun selectStoredSchedule(
        connection: Connection,
        tenantId: String,
        lookupColumn: String,
        lookupValue: String,
        forUpdate: Boolean,
    ): StoredSchedule? {
        require(lookupColumn == "schedule_id" || lookupColumn == "idempotency_key") {
            "Workflow SLA schedule lookup column is unsupported."
        }
        val sql = "SELECT * FROM fw_wf_sla_schedule WHERE tenant_id = ? AND $lookupColumn = ?" +
            if (forUpdate) " FOR UPDATE" else ""
        val seed = connection.prepareStatement(sql).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, lookupValue)
            statement.executeQuery().use { result ->
                if (result.next()) mapScheduleSeed(result) else null
            }
        } ?: return null

        val policies = connection.prepareStatement(SELECT_MILESTONE_POLICIES_SQL).use { statement ->
            statement.setString(1, seed.tenantId)
            statement.setString(2, seed.scheduleId)
            statement.executeQuery().use { result ->
                val values = ArrayList<WorkflowSlaMilestonePolicy>()
                while (result.next()) {
                    values += WorkflowSlaMilestonePolicy.of(
                        WorkflowSlaMilestoneKind.of(result.getString("milestone_kind")),
                        WorkflowSlaActionKind.of(result.getString("action_kind")),
                        result.getLong("working_duration"),
                    )
                }
                values
            }
        }
        val policy = WorkflowSlaPolicy.of(
            seed.policyId,
            seed.policyVersion,
            seed.sourcePolicyDigest,
            seed.definitionRef,
            seed.nodeId,
            seed.calendarBinding,
            seed.actionProfile,
            policies,
        )
        check(policy.policyDigest == seed.policyDigest) { "Workflow SLA policy evidence is corrupt." }

        val storedMilestones = connection.prepareStatement(
            SELECT_MILESTONES_SQL + if (forUpdate) " FOR UPDATE" else "",
        ).use { statement ->
            statement.setString(1, seed.tenantId)
            statement.setString(2, seed.scheduleId)
            statement.executeQuery().use { result ->
                val values = ArrayList<StoredMilestone>()
                while (result.next()) {
                    val kind = WorkflowSlaMilestoneKind.of(result.getString("milestone_kind"))
                    val milestonePolicy = policy.milestones.firstOrNull { it.kind == kind }
                        ?: throw IllegalStateException("Workflow SLA milestone policy is missing.")
                    values += StoredMilestone(
                        mapMilestone(result, seed.scheduleId, milestonePolicy),
                        result.getLong("fence_sequence"),
                    )
                }
                values
            }
        }
        val schedule = WorkflowSlaSchedule.restore(
            seed.tenantId,
            seed.scheduleId,
            seed.idempotencyKey,
            seed.idempotencyBindingDigest,
            policy,
            seed.task,
            storedMilestones.map { it.record },
            seed.status,
            seed.version,
            seed.prepareAuthorizationDigest,
            seed.commitAuthorizationDigest,
            seed.createdAt,
            seed.updatedAt,
        )
        check(schedule.contentDigest == seed.scheduleContentDigest) {
            "Workflow SLA schedule evidence is corrupt."
        }
        return StoredSchedule(
            schedule,
            storedMilestones,
            seed.lastOperationCode,
            seed.lastOperationDigest,
            seed.lastOperationBaseVersion,
        )
    }

    private fun selectStoredScheduleByTask(
        connection: Connection,
        tenantId: String,
        instanceId: String,
        workItemId: String,
        forUpdate: Boolean,
    ): StoredSchedule? {
        val sql = "SELECT schedule_id FROM fw_wf_sla_schedule " +
            "WHERE tenant_id = ? AND instance_id = ? AND work_item_id = ?" +
            if (forUpdate) " FOR UPDATE" else ""
        val scheduleId = connection.prepareStatement(sql).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, instanceId)
            statement.setString(3, workItemId)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        } ?: return null
        return selectStoredSchedule(connection, tenantId, "schedule_id", scheduleId, forUpdate)
    }

    private fun mapScheduleSeed(result: ResultSet): ScheduleSeed {
        val definitionRef = WorkflowDefinitionRef.of(
            result.getString("definition_key"),
            result.getString("definition_version"),
            result.getString("definition_digest"),
        )
        val calendarBinding = WorkflowSlaCalendarBinding.of(
            result.getString("calendar_profile_id"),
            result.getString("calendar_profile_version"),
            result.getString("calendar_profile_digest"),
            result.getString("calendar_profile_binding_digest"),
            WorkflowBusinessCalendarRef.of(
                result.getString("calendar_provider_id"),
                result.getString("calendar_id"),
                result.getString("calendar_version"),
                result.getString("calendar_digest"),
            ),
            WorkflowBusinessCalendarProfile.of(
                result.getString("calendar_provider_id"),
                result.getString("calendar_runtime_revision"),
                result.getLong("calendar_call_window"),
                result.getInt("calendar_maximum_input"),
                result.getInt("calendar_maximum_output"),
            ),
        )
        check(calendarBinding.bindingDigest == result.getString("calendar_binding_digest")) {
            "Workflow SLA calendar binding evidence is corrupt."
        }
        val actionProfile = WorkflowSlaActionProfile.of(
            result.getString("action_profile_id"),
            result.getString("action_profile_version"),
            result.getString("action_profile_digest"),
            result.getString("action_provider_id"),
            result.getString("action_provider_revision"),
            result.getLong("action_call_window"),
            result.getInt("action_maximum_input"),
            result.getInt("action_maximum_output"),
            result.getInt("action_maximum_attempts"),
            result.getLong("action_retry_delay"),
        )
        check(actionProfile.bindingDigest == result.getString("action_binding_digest")) {
            "Workflow SLA action binding evidence is corrupt."
        }
        val task = WorkflowSlaTaskSnapshot.of(
            result.getString("tenant_id"),
            result.getString("instance_id"),
            result.getString("work_item_id"),
            result.getString("definition_id"),
            definitionRef,
            result.getString("node_id"),
            WorkflowSubjectSnapshot.of(
                WorkflowSubjectRef.of(result.getString("subject_type"), result.getString("subject_id")),
                result.getString("subject_revision"),
                result.getString("subject_digest"),
            ),
            WorkflowSlaTaskStatus.of(result.getString("task_status")),
            result.getLong("task_revision"),
            result.getString("task_digest"),
            result.getLong("task_activated_time"),
            result.getLong("task_observed_time"),
        )
        check(task.snapshotDigest == result.getString("task_snapshot_digest")) {
            "Workflow SLA task snapshot evidence is corrupt."
        }
        return ScheduleSeed(
            result.getString("tenant_id"),
            result.getString("schedule_id"),
            result.getString("idempotency_key"),
            result.getString("idempotency_binding_digest"),
            result.getString("policy_id"),
            result.getString("policy_version"),
            result.getString("source_policy_digest"),
            result.getString("definition_id"),
            definitionRef,
            result.getString("node_id"),
            calendarBinding,
            actionProfile,
            result.getString("schedule_content_digest"),
            result.getString("policy_digest"),
            task,
            WorkflowSlaScheduleStatus.of(result.getString("schedule_status")),
            result.getLong("schedule_version"),
            result.getString("prepare_authorization_digest"),
            result.getString("commit_authorization_digest"),
            result.getString("last_operation_code"),
            result.getString("last_operation_digest"),
            result.getLong("last_operation_base_version"),
            result.getLong("created_time"),
            result.getLong("updated_time"),
        )
    }

    private fun mapMilestone(
        result: ResultSet,
        scheduleId: String,
        policy: WorkflowSlaMilestonePolicy,
    ): WorkflowSlaMilestoneRecord {
        val leaseId = result.getString("lease_id")
        val lease = if (leaseId == null) {
            check(result.getObject("worker_id") == null && result.getObject("lease_fencing_token") == null &&
                result.getObject("lease_acquired_time") == null && result.getObject("lease_expires_time") == null
            ) { "Workflow SLA lease evidence is partially populated." }
            null
        } else {
            WorkflowSlaLease.of(
                leaseId,
                result.getString("worker_id"),
                result.getLong("lease_fencing_token"),
                result.getLong("lease_acquired_time"),
                result.getLong("lease_expires_time"),
            )
        }
        val receipt = mapReceipt(result, scheduleId, policy.kind)
        val milestone = WorkflowSlaMilestoneRecord.restore(
            policy,
            result.getLong("scheduled_for"),
            result.getString("calendar_evaluation_digest"),
            WorkflowSlaMilestoneStatus.of(result.getString("status_code")),
            result.getInt("attempt_count"),
            lease,
            nullableLong(result, "next_attempt_time"),
            result.getString("action_request_digest"),
            result.getString("authorization_evidence_digest"),
            receipt,
            result.getString("outcome_evidence_digest"),
            result.getLong("updated_time"),
        )
        check(milestone.contentDigest == result.getString("milestone_content_digest")) {
            "Workflow SLA milestone evidence is corrupt."
        }
        check(result.getLong("fence_sequence") >= (lease?.fencingToken ?: 0L)) {
            "Workflow SLA lease fence sequence is corrupt."
        }
        return milestone
    }

    private fun mapReceipt(
        result: ResultSet,
        scheduleId: String,
        milestoneKind: WorkflowSlaMilestoneKind,
    ): WorkflowSlaActionReceipt? {
        val receiptDigest = result.getString("receipt_digest")
        if (receiptDigest == null) {
            val populated = RECEIPT_COLUMNS.any { column -> result.getObject(column) != null }
            check(!populated) { "Workflow SLA receipt evidence is partially populated." }
            return null
        }
        val receipt = WorkflowSlaActionReceipt.restore(
            scheduleId,
            milestoneKind,
            result.getString("receipt_provider_id"),
            result.getString("receipt_provider_revision"),
            result.getString("receipt_action_profile_digest"),
            result.getString("receipt_request_digest"),
            WorkflowSlaActionOutcome.of(result.getString("receipt_outcome")),
            result.getString("receipt_result_evidence_digest"),
            result.getString("receipt_failure_code"),
            result.getLong("receipt_requested_time"),
            result.getLong("receipt_deadline_time"),
            result.getLong("receipt_completed_time"),
            result.getLong("receipt_expires_time"),
        )
        check(receipt.receiptDigest == receiptDigest) { "Workflow SLA action receipt evidence is corrupt." }
        return receipt
    }

    private fun nullableLong(result: ResultSet, column: String): Long? {
        val value = result.getLong(column)
        return if (result.wasNull()) null else value
    }

    private fun insertSchedule(
        connection: Connection,
        schedule: WorkflowSlaSchedule,
        operationDigest: String,
    ) {
        val policy = schedule.policy
        val calendar = policy.calendarBinding
        val action = policy.actionProfile
        val task = schedule.task
        connection.prepareStatement(INSERT_SCHEDULE_SQL).use { statement ->
            var index = 1
            statement.setString(index++, scheduleRowId(schedule.tenantId, schedule.scheduleId))
            statement.setString(index++, schedule.tenantId)
            statement.setString(index++, schedule.scheduleId)
            statement.setString(index++, schedule.idempotencyKey)
            statement.setString(index++, schedule.idempotencyBindingDigest)
            statement.setString(index++, policy.policyId)
            statement.setString(index++, policy.policyVersion)
            statement.setString(index++, policy.sourcePolicyDigest)
            statement.setString(index++, policy.policyDigest)
            statement.setString(index++, task.definitionId)
            statement.setString(index++, policy.definitionRef.key)
            statement.setString(index++, policy.definitionRef.version)
            statement.setString(index++, policy.definitionRef.digest)
            statement.setString(index++, policy.nodeId)
            statement.setString(index++, calendar.profileId)
            statement.setString(index++, calendar.profileVersion)
            statement.setString(index++, calendar.profileDigest)
            statement.setString(index++, calendar.profileBindingDigest)
            statement.setString(index++, calendar.calendar.providerId)
            statement.setString(index++, calendar.calendar.calendarId)
            statement.setString(index++, calendar.calendar.version)
            statement.setString(index++, calendar.calendar.digest)
            statement.setString(index++, calendar.providerProfile.providerRevision)
            statement.setLong(index++, calendar.providerProfile.callWindowMillis)
            statement.setInt(index++, calendar.providerProfile.maximumInputBytes)
            statement.setInt(index++, calendar.providerProfile.maximumOutputBytes)
            statement.setString(index++, calendar.bindingDigest)
            statement.setString(index++, action.profileId)
            statement.setString(index++, action.profileVersion)
            statement.setString(index++, action.profileDigest)
            statement.setString(index++, action.providerId)
            statement.setString(index++, action.providerRevision)
            statement.setLong(index++, action.callWindowMillis)
            statement.setInt(index++, action.maximumInputBytes)
            statement.setInt(index++, action.maximumOutputBytes)
            statement.setInt(index++, action.maximumAttempts)
            statement.setLong(index++, action.retryDelayMillis)
            statement.setString(index++, action.bindingDigest)
            statement.setString(index++, task.instanceId)
            statement.setString(index++, task.workItemId)
            statement.setString(index++, task.status.code)
            statement.setLong(index++, task.revision)
            statement.setString(index++, task.taskDigest)
            statement.setLong(index++, task.activatedAt)
            statement.setLong(index++, task.observedAt)
            statement.setString(index++, task.subject.ref.type)
            statement.setString(index++, task.subject.ref.id)
            statement.setString(index++, task.subject.revision)
            statement.setString(index++, task.subject.digest)
            statement.setString(index++, task.snapshotDigest)
            statement.setString(index++, schedule.status.code)
            statement.setLong(index++, schedule.version)
            statement.setString(index++, schedule.prepareAuthorizationEvidenceDigest)
            statement.setString(index++, schedule.commitAuthorizationEvidenceDigest)
            statement.setString(index++, schedule.contentDigest)
            statement.setString(index++, OPERATION_CREATE)
            statement.setString(index++, operationDigest)
            statement.setLong(index++, -1L)
            statement.setLong(index++, schedule.createdAt)
            statement.setLong(index, schedule.updatedAt)
            check(statement.executeUpdate() == 1) { "Workflow SLA schedule insert was not applied." }
        }
    }

    private fun insertMilestone(
        connection: Connection,
        schedule: WorkflowSlaSchedule,
        milestone: WorkflowSlaMilestoneRecord,
        ordinal: Int,
        fenceSequence: Long,
    ) {
        connection.prepareStatement(INSERT_MILESTONE_SQL).use { statement ->
            var index = 1
            statement.setString(
                index++,
                milestoneRowId(schedule.tenantId, schedule.scheduleId, milestone.policy.kind.code),
            )
            statement.setString(index++, schedule.tenantId)
            statement.setString(index++, schedule.scheduleId)
            statement.setInt(index++, ordinal)
            statement.setString(index++, milestone.policy.kind.code)
            statement.setString(index++, milestone.policy.action.code)
            statement.setLong(index++, milestone.policy.workingDurationMillis)
            statement.setLong(index++, milestone.scheduledFor)
            statement.setString(index++, milestone.calendarEvaluationDigest)
            statement.setString(index++, milestone.status.code)
            statement.setInt(index++, milestone.attempt)
            statement.setLong(index++, fenceSequence)
            index = bindLease(statement, index, milestone.lease)
            setNullableLong(statement, index++, milestone.nextAttemptAt)
            statement.setString(index++, milestone.actionRequestDigest)
            statement.setString(index++, milestone.authorizationEvidenceDigest)
            index = bindReceipt(statement, index, milestone.actionReceipt)
            statement.setString(index++, milestone.outcomeEvidenceDigest)
            statement.setString(index++, milestone.contentDigest)
            statement.setLong(index++, milestone.updatedAt)
            statement.setLong(index, milestone.updatedAt)
            check(statement.executeUpdate() == 1) { "Workflow SLA milestone insert was not applied." }
        }
    }

    private fun updateMilestone(
        connection: Connection,
        tenantId: String,
        scheduleId: String,
        milestone: WorkflowSlaMilestoneRecord,
        fenceSequence: Long,
    ): Int = connection.prepareStatement(UPDATE_MILESTONE_SQL).use { statement ->
        var index = 1
        statement.setString(index++, milestone.status.code)
        statement.setInt(index++, milestone.attempt)
        statement.setLong(index++, fenceSequence)
        index = bindLease(statement, index, milestone.lease)
        setNullableLong(statement, index++, milestone.nextAttemptAt)
        statement.setString(index++, milestone.actionRequestDigest)
        statement.setString(index++, milestone.authorizationEvidenceDigest)
        index = bindReceipt(statement, index, milestone.actionReceipt)
        statement.setString(index++, milestone.outcomeEvidenceDigest)
        statement.setString(index++, milestone.contentDigest)
        statement.setLong(index++, milestone.updatedAt)
        statement.setString(index++, tenantId)
        statement.setString(index++, scheduleId)
        statement.setString(index, milestone.policy.kind.code)
        statement.executeUpdate()
    }

    private fun bindLease(
        statement: java.sql.PreparedStatement,
        startIndex: Int,
        lease: WorkflowSlaLease?,
    ): Int {
        var index = startIndex
        statement.setString(index++, lease?.leaseId)
        statement.setString(index++, lease?.workerId)
        setNullableLong(statement, index++, lease?.fencingToken)
        setNullableLong(statement, index++, lease?.acquiredAt)
        setNullableLong(statement, index++, lease?.expiresAt)
        return index
    }

    private fun bindReceipt(
        statement: java.sql.PreparedStatement,
        startIndex: Int,
        receipt: WorkflowSlaActionReceipt?,
    ): Int {
        var index = startIndex
        statement.setString(index++, receipt?.providerId)
        statement.setString(index++, receipt?.providerRevision)
        statement.setString(index++, receipt?.actionProfileDigest)
        statement.setString(index++, receipt?.requestDigest)
        statement.setString(index++, receipt?.outcome?.code)
        statement.setString(index++, receipt?.resultEvidenceDigest)
        statement.setString(index++, receipt?.failureCode)
        setNullableLong(statement, index++, receipt?.requestedAt)
        setNullableLong(statement, index++, receipt?.deadline)
        setNullableLong(statement, index++, receipt?.completedAt)
        setNullableLong(statement, index++, receipt?.expiresAt)
        statement.setString(index++, receipt?.receiptDigest)
        return index
    }

    private fun setNullableLong(statement: java.sql.PreparedStatement, index: Int, value: Long?) {
        if (value == null) statement.setNull(index, java.sql.Types.BIGINT) else statement.setLong(index, value)
    }

    private fun scheduleRowId(tenantId: String, scheduleId: String): String = WorkflowSlaJdbcDigests.rowId(
        "flowweft-workflow-sla-jdbc-schedule-row-v1",
        tenantId,
        scheduleId,
    )

    private fun milestoneRowId(tenantId: String, scheduleId: String, kind: String): String =
        WorkflowSlaJdbcDigests.rowId(
            "flowweft-workflow-sla-jdbc-milestone-row-v1",
            tenantId,
            scheduleId,
            kind,
        )

    private class StoredMilestone(
        val record: WorkflowSlaMilestoneRecord,
        val fenceSequence: Long,
    )

    private class StoredSchedule(
        val schedule: WorkflowSlaSchedule,
        val milestones: List<StoredMilestone>,
        val lastOperationCode: String,
        val lastOperationDigest: String,
        val lastOperationBaseVersion: Long,
    ) {
        fun milestone(kind: WorkflowSlaMilestoneKind): StoredMilestone? = milestones.firstOrNull {
            it.record.policy.kind == kind
        }
    }

    private class ScheduleSeed(
        val tenantId: String,
        val scheduleId: String,
        val idempotencyKey: String,
        val idempotencyBindingDigest: String,
        val policyId: String,
        val policyVersion: String,
        val sourcePolicyDigest: String,
        val definitionId: String,
        val definitionRef: WorkflowDefinitionRef,
        val nodeId: String,
        val calendarBinding: WorkflowSlaCalendarBinding,
        val actionProfile: WorkflowSlaActionProfile,
        val scheduleContentDigest: String,
        val policyDigest: String,
        val task: WorkflowSlaTaskSnapshot,
        val status: WorkflowSlaScheduleStatus,
        val version: Long,
        val prepareAuthorizationDigest: String,
        val commitAuthorizationDigest: String,
        val lastOperationCode: String,
        val lastOperationDigest: String,
        val lastOperationBaseVersion: Long,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private companion object {
        const val MAX_QUERY_LIMIT = 1_000

        const val OPERATION_CREATE = "create"
        const val OPERATION_CLAIM = "claim"
        const val OPERATION_CHECKPOINT = "checkpoint"
        const val OPERATION_COMPLETE = "complete"
        const val OPERATION_SUPPRESS = "suppress"
        const val OPERATION_RECONCILE = "reconcile"

        val RECEIPT_COLUMNS = listOf(
            "receipt_provider_id",
            "receipt_provider_revision",
            "receipt_action_profile_digest",
            "receipt_request_digest",
            "receipt_outcome",
            "receipt_result_evidence_digest",
            "receipt_failure_code",
            "receipt_requested_time",
            "receipt_deadline_time",
            "receipt_completed_time",
            "receipt_expires_time",
        )

        val SCHEDULE_COLUMNS = listOf(
            "id",
            "tenant_id",
            "schedule_id",
            "idempotency_key",
            "idempotency_binding_digest",
            "policy_id",
            "policy_version",
            "source_policy_digest",
            "policy_digest",
            "definition_id",
            "definition_key",
            "definition_version",
            "definition_digest",
            "node_id",
            "calendar_profile_id",
            "calendar_profile_version",
            "calendar_profile_digest",
            "calendar_profile_binding_digest",
            "calendar_provider_id",
            "calendar_id",
            "calendar_version",
            "calendar_digest",
            "calendar_runtime_revision",
            "calendar_call_window",
            "calendar_maximum_input",
            "calendar_maximum_output",
            "calendar_binding_digest",
            "action_profile_id",
            "action_profile_version",
            "action_profile_digest",
            "action_provider_id",
            "action_provider_revision",
            "action_call_window",
            "action_maximum_input",
            "action_maximum_output",
            "action_maximum_attempts",
            "action_retry_delay",
            "action_binding_digest",
            "instance_id",
            "work_item_id",
            "task_status",
            "task_revision",
            "task_digest",
            "task_activated_time",
            "task_observed_time",
            "subject_type",
            "subject_id",
            "subject_revision",
            "subject_digest",
            "task_snapshot_digest",
            "schedule_status",
            "schedule_version",
            "prepare_authorization_digest",
            "commit_authorization_digest",
            "schedule_content_digest",
            "last_operation_code",
            "last_operation_digest",
            "last_operation_base_version",
            "created_time",
            "updated_time",
        )

        val MILESTONE_COLUMNS = listOf(
            "id",
            "tenant_id",
            "schedule_id",
            "milestone_ordinal",
            "milestone_kind",
            "action_kind",
            "working_duration",
            "scheduled_for",
            "calendar_evaluation_digest",
            "status_code",
            "attempt_count",
            "fence_sequence",
            "lease_id",
            "worker_id",
            "lease_fencing_token",
            "lease_acquired_time",
            "lease_expires_time",
            "next_attempt_time",
            "action_request_digest",
            "authorization_evidence_digest",
            "receipt_provider_id",
            "receipt_provider_revision",
            "receipt_action_profile_digest",
            "receipt_request_digest",
            "receipt_outcome",
            "receipt_result_evidence_digest",
            "receipt_failure_code",
            "receipt_requested_time",
            "receipt_deadline_time",
            "receipt_completed_time",
            "receipt_expires_time",
            "receipt_digest",
            "outcome_evidence_digest",
            "milestone_content_digest",
            "created_time",
            "updated_time",
        )

        val INSERT_SCHEDULE_SQL = "INSERT INTO fw_wf_sla_schedule (" +
            SCHEDULE_COLUMNS.joinToString(", ") + ") VALUES (" +
            SCHEDULE_COLUMNS.joinToString(", ") { "?" } + ")"

        val INSERT_MILESTONE_SQL = "INSERT INTO fw_wf_sla_milestone (" +
            MILESTONE_COLUMNS.joinToString(", ") + ") VALUES (" +
            MILESTONE_COLUMNS.joinToString(", ") { "?" } + ")"

        const val UPDATE_SCHEDULE_SQL = """
            UPDATE fw_wf_sla_schedule SET
                schedule_status = ?, schedule_version = ?, schedule_content_digest = ?,
                last_operation_code = ?, last_operation_digest = ?, last_operation_base_version = ?,
                updated_time = ?
            WHERE tenant_id = ? AND schedule_id = ? AND schedule_version = ?
        """

        const val UPDATE_MILESTONE_SQL = """
            UPDATE fw_wf_sla_milestone SET
                status_code = ?, attempt_count = ?, fence_sequence = ?, lease_id = ?, worker_id = ?,
                lease_fencing_token = ?, lease_acquired_time = ?, lease_expires_time = ?,
                next_attempt_time = ?, action_request_digest = ?, authorization_evidence_digest = ?,
                receipt_provider_id = ?, receipt_provider_revision = ?,
                receipt_action_profile_digest = ?, receipt_request_digest = ?, receipt_outcome = ?,
                receipt_result_evidence_digest = ?, receipt_failure_code = ?, receipt_requested_time = ?,
                receipt_deadline_time = ?, receipt_completed_time = ?, receipt_expires_time = ?,
                receipt_digest = ?, outcome_evidence_digest = ?, milestone_content_digest = ?,
                updated_time = ?
            WHERE tenant_id = ? AND schedule_id = ? AND milestone_kind = ?
        """

        const val SELECT_TASK_SQL = """
            SELECT
                ht.tenant_id AS tenant_id,
                ht.instance_id AS instance_id,
                ht.id AS work_item_id,
                ht.node_id AS node_id,
                ht.task_status AS human_task_status,
                ht.task_revision AS task_revision,
                ht.content_digest AS task_digest,
                ht.created_time AS task_created_time,
                ht.updated_time AS task_updated_time,
                i.definition_id AS definition_id,
                i.definition_key AS definition_key,
                i.definition_version AS definition_version,
                i.definition_digest AS definition_digest,
                i.subject_type AS subject_type,
                i.subject_id AS subject_id,
                i.subject_revision AS subject_revision,
                i.subject_digest AS subject_digest,
                i.status AS instance_status
            FROM fw_wf_human_task ht
            JOIN fw_wf_instance i ON i.tenant_id = ht.tenant_id AND i.id = ht.instance_id
            WHERE ht.tenant_id = ? AND ht.instance_id = ? AND ht.id = ?
        """

        const val SELECT_MILESTONE_POLICIES_SQL = """
            SELECT milestone_kind, action_kind, working_duration
            FROM fw_wf_sla_milestone
            WHERE tenant_id = ? AND schedule_id = ?
            ORDER BY milestone_ordinal
        """

        const val SELECT_MILESTONES_SQL = """
            SELECT * FROM fw_wf_sla_milestone
            WHERE tenant_id = ? AND schedule_id = ?
            ORDER BY milestone_ordinal
        """

        const val FIND_DUE_SQL = """
            SELECT m.tenant_id, m.schedule_id, m.milestone_kind, s.schedule_version,
                CASE
                    WHEN m.status_code = 'retry-wait' THEN m.next_attempt_time
                    WHEN m.status_code = 'leased' THEN m.lease_expires_time
                    ELSE m.scheduled_for
                END AS eligible_time
            FROM fw_wf_sla_milestone m
            JOIN fw_wf_sla_schedule s
              ON s.tenant_id = m.tenant_id AND s.schedule_id = m.schedule_id
            WHERE m.tenant_id = ?
              AND s.schedule_status IN (?, ?)
              AND ((m.status_code = ? AND m.scheduled_for <= ?)
                OR (m.status_code = ? AND m.next_attempt_time <= ?)
                OR (m.status_code = ? AND m.lease_expires_time <= ?))
            ORDER BY eligible_time, m.schedule_id, m.milestone_ordinal
            LIMIT ?
        """

        const val COUNT_ACTIVE_SCHEDULES_SQL = """
            SELECT COUNT(*) FROM fw_wf_sla_schedule
            WHERE tenant_id = ? AND schedule_status = ?
        """

        const val MILESTONE_DIAGNOSTIC_SQL = """
            SELECT
                COALESCE(SUM(CASE
                    WHEN (status_code = ? AND scheduled_for <= ?)
                      OR (status_code = ? AND next_attempt_time <= ?)
                    THEN 1 ELSE 0 END), 0) AS due_count,
                COALESCE(SUM(CASE
                    WHEN status_code IN (?, ?) AND lease_expires_time <= ?
                    THEN 1 ELSE 0 END), 0) AS expired_lease_count,
                COALESCE(SUM(CASE WHEN status_code = ? THEN 1 ELSE 0 END), 0) AS unknown_count,
                COALESCE(SUM(CASE WHEN status_code = ? THEN 1 ELSE 0 END), 0) AS failure_count,
                MIN(CASE
                    WHEN status_code = ? AND scheduled_for <= ? THEN scheduled_for
                    WHEN status_code = ? AND next_attempt_time <= ? THEN next_attempt_time
                    ELSE NULL END) AS oldest_due_time
            FROM fw_wf_sla_milestone
            WHERE tenant_id = ?
        """
    }
}
