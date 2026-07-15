package ai.icen.fw.workflow.persistence.jdbc

import ai.icen.fw.workflow.domain.WorkflowEffectCode
import ai.icen.fw.workflow.runtime.WorkflowClaimedEffectJob
import ai.icen.fw.workflow.runtime.WorkflowEffectDeliveryStatus
import ai.icen.fw.workflow.runtime.WorkflowEffectExecutionPhase
import ai.icen.fw.workflow.runtime.WorkflowEffectJobExecutionMode
import ai.icen.fw.workflow.runtime.WorkflowEffectJobResultCheckpoint
import ai.icen.fw.workflow.runtime.WorkflowEffectJobStoreCode
import ai.icen.fw.workflow.runtime.WorkflowEffectJobStoreResult
import ai.icen.fw.workflow.runtime.WorkflowEffectJobStoredResult
import ai.icen.fw.workflow.runtime.WorkflowEffectLease
import ai.icen.fw.workflow.runtime.WorkflowEffectObservedOutcome
import ai.icen.fw.workflow.runtime.WorkflowReadyEffectJobClaimRequest
import ai.icen.fw.workflow.runtime.WorkflowReadyEffectJobPort
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

/**
 * JDBC implementation of the narrow worker queue. Every method is a bounded local transaction or
 * read. Resolver/provider calls are structurally impossible because this class has no such
 * dependency.
 */
class JdbcWorkflowReadyEffectJobQueue @JvmOverloads constructor(
    dataSource: DataSource,
    dialect: WorkflowJdbcDialect? = null,
) : WorkflowReadyEffectJobPort {
    private val transactions = WorkflowJdbcTransactions(dataSource, dialect)

    override fun claimReady(request: WorkflowReadyEffectJobClaimRequest): List<WorkflowClaimedEffectJob> =
        transactions.transaction { connection, _ ->
            recoverExpiredProviderCalls(connection, request)
            val rows = selectReadyRows(connection, request)
            rows.map { row -> claimRow(connection, request, row) }
        }

    override fun storeResult(checkpoint: WorkflowEffectJobResultCheckpoint): WorkflowEffectJobStoreResult =
        transactions.transaction { connection, _ ->
            val claim = checkpoint.claim
            val current = selectClaimRow(connection, claim.tenantId, claim.jobId, forUpdate = true)
                ?: return@transaction WorkflowEffectJobStoreResult.failed(WorkflowEffectJobStoreCode.LEASE_MISMATCH)
            if (!currentLeaseMatches(current, claim, checkpoint.storedAt) ||
                current.effectVersion != checkpoint.expectedEffectVersion ||
                current.effectStatus != WorkflowEffectDeliveryStatus.LEASED.code ||
                current.effectPhase != WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED.code ||
                current.effectCheckpointDigest.isNullOrBlank() ||
                current.effectLeaseId != claim.lease.leaseId ||
                current.effectFencingToken != claim.lease.fencingToken ||
                current.effectLeaseExpiresAt == null || current.effectLeaseExpiresAt <= checkpoint.storedAt
            ) {
                return@transaction WorkflowEffectJobStoreResult.failed(WorkflowEffectJobStoreCode.LEASE_MISMATCH)
            }
            val existing = selectStoredResult(connection, claim.tenantId, claim.effectId, forUpdate = true)
            if (existing != null) {
                return@transaction if (existing == checkpoint.result) {
                    WorkflowEffectJobStoreResult.replayed(existing)
                } else {
                    WorkflowEffectJobStoreResult.failed(WorkflowEffectJobStoreCode.CONFLICT)
                }
            }
            connection.prepareStatement(INSERT_RESULT_SQL).use { statement ->
                statement.setString(1, stableId("workflow-effect-result", claim.effectId))
                statement.setString(2, claim.tenantId)
                statement.setString(3, claim.instanceId)
                statement.setString(4, claim.effectId)
                statement.setString(5, checkpoint.result.resultType)
                statement.setString(6, checkpoint.result.outcome.code)
                statement.setString(7, checkpoint.result.resultDigest)
                statement.setBytes(8, checkpoint.result.bytes())
                setNullableLong(statement, 9, checkpoint.result.retryAt)
                statement.setLong(10, checkpoint.result.completedAt)
                statement.setLong(11, 1L)
                statement.setLong(12, checkpoint.storedAt)
                statement.setLong(13, checkpoint.storedAt)
                check(statement.executeUpdate() == 1) { "Workflow effect result insert did not affect one row." }
            }
            WorkflowEffectJobStoreResult.stored(checkpoint.result)
        }

    override fun loadClaims(
        request: WorkflowReadyEffectJobClaimRequest,
        readAt: Long,
    ): List<WorkflowClaimedEffectJob> = transactions.read { connection, _ ->
        connection.prepareStatement(SELECT_CLAIMS_BY_REQUEST_SQL).use { statement ->
            statement.setString(1, request.tenantId)
            statement.setString(2, request.requestDigest)
            statement.setString(3, request.workerId)
            statement.setLong(4, readAt)
            statement.executeQuery().use { result ->
                val claims = ArrayList<WorkflowClaimedEffectJob>()
                while (result.next()) claims.add(mapClaimedRow(result))
                claims
            }
        }
    }

    override fun loadClaim(tenantId: String, jobId: String, readAt: Long): WorkflowClaimedEffectJob? =
        transactions.read { connection, _ ->
            val row = selectClaimRow(connection, tenantId, jobId, forUpdate = false) ?: return@read null
            if (row.jobLeaseExpiresAt == null || row.jobLeaseExpiresAt <= readAt || row.executionMode == null) {
                null
            } else {
                row.toClaim()
            }
        }

    private fun selectReadyRows(
        connection: Connection,
        request: WorkflowReadyEffectJobClaimRequest,
    ): List<JobRow> = connection.prepareStatement(SELECT_READY_SQL).use { statement ->
        statement.setString(1, request.tenantId)
        statement.setString(2, request.effectCode.code)
        statement.setLong(3, request.now)
        statement.setLong(4, request.now)
        statement.setString(5, WorkflowEffectDeliveryStatus.PENDING.code)
        statement.setString(6, WorkflowEffectDeliveryStatus.RETRY_WAIT.code)
        statement.setLong(7, request.now)
        statement.setString(8, WorkflowEffectDeliveryStatus.LEASED.code)
        statement.setString(9, WorkflowEffectExecutionPhase.PREPARED.code)
        statement.setLong(10, request.now)
        statement.setString(11, WorkflowEffectDeliveryStatus.SUCCEEDED.code)
        statement.setString(12, WorkflowEffectDeliveryStatus.RETRYABLE_FAILURE.code)
        statement.setInt(13, request.maximumJobs)
        statement.executeQuery().use { result ->
            val rows = ArrayList<JobRow>()
            while (result.next()) rows.add(mapJobRow(result))
            rows
        }
    }

    private fun claimRow(
        connection: Connection,
        request: WorkflowReadyEffectJobClaimRequest,
        row: JobRow,
    ): WorkflowClaimedEffectJob {
        val mode = when (row.effectStatus) {
            WorkflowEffectDeliveryStatus.SUCCEEDED.code -> WorkflowEffectJobExecutionMode.APPLY_SUCCEEDED_RESULT
            WorkflowEffectDeliveryStatus.RETRYABLE_FAILURE.code -> WorkflowEffectJobExecutionMode.SCHEDULE_RETRY
            else -> WorkflowEffectJobExecutionMode.EXECUTE_PROVIDER
        }
        val nextFence = maxOf(row.jobFencingToken, row.effectFencingToken ?: 0L) + 1L
        check(nextFence > 0L) { "Workflow effect job fencing token overflowed." }
        val leaseId = "wf-job-lease-${stableId(request.requestDigest, row.jobId, nextFence.toString())}"
        val nextVersion = row.jobVersion + 1L
        connection.prepareStatement(CLAIM_JOB_SQL).use { statement ->
            statement.setString(1, jobStatus(mode))
            statement.setLong(2, nextVersion)
            statement.setString(3, leaseId)
            statement.setString(4, request.workerId)
            statement.setLong(5, nextFence)
            statement.setLong(6, request.now)
            statement.setLong(7, request.leaseExpiresAt)
            statement.setString(8, mode.code)
            statement.setString(9, request.requestDigest)
            statement.setLong(10, request.now)
            statement.setString(11, request.tenantId)
            statement.setString(12, row.jobId)
            statement.setLong(13, row.jobVersion)
            check(statement.executeUpdate() == 1) { "Workflow effect job claim CAS changed while locked." }
        }
        return WorkflowClaimedEffectJob.of(
            row.jobId,
            row.tenantId,
            row.instanceId,
            row.effectId,
            WorkflowEffectCode.of(row.effectCode),
            mode,
            nextVersion,
            row.effectVersion,
            request.requestDigest,
            WorkflowEffectLease.of(leaseId, request.workerId, nextFence, request.now, request.leaseExpiresAt),
            row.storedResult,
            request.now,
        )
    }

    /**
     * A PREPARED lease is safe to reclaim. A started provider call is recovered from a result that
     * was durably checkpointed after the call; without one its outcome is explicitly unknown and
     * never replayed blindly.
     */
    private fun recoverExpiredProviderCalls(
        connection: Connection,
        request: WorkflowReadyEffectJobClaimRequest,
    ) {
        val expired = connection.prepareStatement(SELECT_EXPIRED_PROVIDER_SQL).use { statement ->
            statement.setString(1, request.tenantId)
            statement.setString(2, request.effectCode.code)
            statement.setString(3, WorkflowEffectDeliveryStatus.LEASED.code)
            statement.setString(4, WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED.code)
            statement.setLong(5, request.now)
            statement.setInt(6, request.maximumJobs)
            statement.executeQuery().use { result ->
                val rows = ArrayList<JobRow>()
                while (result.next()) rows.add(mapJobRow(result))
                rows
            }
        }
        expired.forEach { row ->
            val stored = row.storedResult
            val status = stored?.let { result -> deliveryStatus(result.outcome) }
                ?: WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN.code
            val digest = stored?.resultDigest ?: stableId(
                "workflow-provider-outcome-unknown",
                row.tenantId,
                row.effectId,
                row.effectVersion.toString(),
                request.now.toString(),
            )
            connection.prepareStatement(RECOVER_EFFECT_SQL).use { statement ->
                statement.setString(1, status)
                statement.setString(2, digest)
                statement.setLong(3, request.now)
                statement.setString(4, row.tenantId)
                statement.setString(5, row.effectId)
                statement.setLong(6, row.effectVersion)
                check(statement.executeUpdate() == 1) { "Workflow expired provider recovery CAS changed while locked." }
            }
            connection.prepareStatement(RELEASE_RECOVERED_JOB_SQL).use { statement ->
                statement.setString(1, status)
                statement.setString(2, if (stored == null) digest else null)
                statement.setLong(3, request.now)
                statement.setString(4, row.tenantId)
                statement.setString(5, row.jobId)
                statement.setLong(6, row.jobVersion)
                check(statement.executeUpdate() == 1) { "Workflow recovered job CAS changed while locked." }
            }
        }
    }

    private fun selectClaimRow(
        connection: Connection,
        tenantId: String,
        jobId: String,
        forUpdate: Boolean,
    ): JobRow? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(SELECT_JOB_BY_ID_SQL + lock).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, jobId)
            statement.executeQuery().use { result -> if (result.next()) mapJobRow(result) else null }
        }
    }

    private fun selectStoredResult(
        connection: Connection,
        tenantId: String,
        effectId: String,
        forUpdate: Boolean,
    ): WorkflowEffectJobStoredResult? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(SELECT_RESULT_SQL + lock).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, effectId)
            statement.executeQuery().use { result -> if (result.next()) mapStoredResult(result) else null }
        }
    }

    private fun currentLeaseMatches(row: JobRow, claim: WorkflowClaimedEffectJob, now: Long): Boolean =
        row.jobVersion == claim.jobVersion && row.claimRequestDigest == claim.claimRequestDigest &&
            row.jobLeaseId == claim.lease.leaseId && row.jobWorkerId == claim.lease.workerId &&
            row.jobFencingToken == claim.lease.fencingToken &&
            row.jobLeaseExpiresAt != null && row.jobLeaseExpiresAt > now

    private fun mapClaimedRow(result: ResultSet): WorkflowClaimedEffectJob = mapJobRow(result).toClaim()

    private fun mapJobRow(result: ResultSet): JobRow = JobRow(
        result.getString("job_id"),
        result.getString("tenant_id"),
        result.getString("instance_id"),
        result.getString("effect_id"),
        result.getString("effect_code"),
        result.getString("effect_status"),
        result.getString("effect_phase"),
        result.getString("effect_checkpoint_digest"),
        result.getLong("effect_version"),
        nullableLong(result, "effect_fencing_token"),
        result.getString("effect_lease_id"),
        nullableLong(result, "effect_lease_expires_time"),
        result.getLong("job_version"),
        result.getLong("job_fencing_token"),
        result.getString("job_lease_id"),
        result.getString("job_worker_id"),
        nullableLong(result, "job_lease_acquired_time"),
        nullableLong(result, "job_lease_expires_time"),
        result.getString("execution_mode"),
        result.getString("claim_request_digest"),
        if (result.getString("result_digest") == null) null else mapStoredResult(result),
    )

    private fun mapStoredResult(result: ResultSet): WorkflowEffectJobStoredResult = WorkflowEffectJobStoredResult.of(
        observedOutcome(result.getString("outcome_code")),
        result.getString("result_type"),
        result.getString("result_digest"),
        result.getBytes("result_payload"),
        nullableLong(result, "retry_time"),
        result.getLong("completed_time"),
    )

    private fun JobRow.toClaim(): WorkflowClaimedEffectJob = WorkflowClaimedEffectJob.of(
        jobId,
        tenantId,
        instanceId,
        effectId,
        WorkflowEffectCode.of(effectCode),
        WorkflowEffectJobExecutionMode.of(requireNotNull(executionMode)),
        jobVersion,
        effectVersion,
        requireNotNull(claimRequestDigest),
        WorkflowEffectLease.of(
            requireNotNull(jobLeaseId),
            requireNotNull(jobWorkerId),
            jobFencingToken,
            requireNotNull(jobLeaseAcquiredAt),
            requireNotNull(jobLeaseExpiresAt),
        ),
        storedResult,
        jobLeaseAcquiredAt,
    )

    private fun observedOutcome(code: String): WorkflowEffectObservedOutcome = when (code) {
        WorkflowEffectObservedOutcome.SUCCEEDED.code -> WorkflowEffectObservedOutcome.SUCCEEDED
        WorkflowEffectObservedOutcome.RETRYABLE_FAILURE.code -> WorkflowEffectObservedOutcome.RETRYABLE_FAILURE
        WorkflowEffectObservedOutcome.TERMINAL_FAILURE.code -> WorkflowEffectObservedOutcome.TERMINAL_FAILURE
        WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN.code -> WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN
        else -> throw IllegalArgumentException("Persisted workflow effect outcome is unsupported.")
    }

    private fun deliveryStatus(outcome: WorkflowEffectObservedOutcome): String = when (outcome) {
        WorkflowEffectObservedOutcome.SUCCEEDED -> WorkflowEffectDeliveryStatus.SUCCEEDED.code
        WorkflowEffectObservedOutcome.RETRYABLE_FAILURE -> WorkflowEffectDeliveryStatus.RETRYABLE_FAILURE.code
        WorkflowEffectObservedOutcome.TERMINAL_FAILURE -> WorkflowEffectDeliveryStatus.TERMINAL_FAILURE.code
        WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN -> WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN.code
        else -> throw IllegalArgumentException("Workflow effect recovery outcome is unsupported.")
    }

    private fun jobStatus(mode: WorkflowEffectJobExecutionMode): String = when (mode) {
        WorkflowEffectJobExecutionMode.EXECUTE_PROVIDER -> "claimed"
        WorkflowEffectJobExecutionMode.APPLY_SUCCEEDED_RESULT -> "applying"
        WorkflowEffectJobExecutionMode.SCHEDULE_RETRY -> "scheduling-retry"
        else -> throw IllegalArgumentException("Workflow effect job mode is unsupported.")
    }

    private data class JobRow(
        val jobId: String,
        val tenantId: String,
        val instanceId: String,
        val effectId: String,
        val effectCode: String,
        val effectStatus: String,
        val effectPhase: String?,
        val effectCheckpointDigest: String?,
        val effectVersion: Long,
        val effectFencingToken: Long?,
        val effectLeaseId: String?,
        val effectLeaseExpiresAt: Long?,
        val jobVersion: Long,
        val jobFencingToken: Long,
        val jobLeaseId: String?,
        val jobWorkerId: String?,
        val jobLeaseAcquiredAt: Long?,
        val jobLeaseExpiresAt: Long?,
        val executionMode: String?,
        val claimRequestDigest: String?,
        val storedResult: WorkflowEffectJobStoredResult?,
    )

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT j.id AS job_id, j.tenant_id, j.instance_id, j.effect_id,
                e.effect_code, e.delivery_status AS effect_status, e.execution_phase AS effect_phase,
                e.checkpoint_digest AS effect_checkpoint_digest, e.record_version AS effect_version,
                e.fencing_token AS effect_fencing_token, e.lease_id AS effect_lease_id,
                e.lease_expires_time AS effect_lease_expires_time,
                j.record_version AS job_version, j.fencing_token AS job_fencing_token,
                j.lease_id AS job_lease_id, j.worker_id AS job_worker_id,
                j.lease_acquired_time AS job_lease_acquired_time,
                j.lease_expires_time AS job_lease_expires_time,
                j.execution_mode, j.claim_request_digest,
                (SELECT r.result_type FROM fw_wf_effect_result r
                    WHERE r.tenant_id = j.tenant_id AND r.effect_id = j.effect_id) AS result_type,
                (SELECT r.outcome_code FROM fw_wf_effect_result r
                    WHERE r.tenant_id = j.tenant_id AND r.effect_id = j.effect_id) AS outcome_code,
                (SELECT r.result_digest FROM fw_wf_effect_result r
                    WHERE r.tenant_id = j.tenant_id AND r.effect_id = j.effect_id) AS result_digest,
                (SELECT r.result_payload FROM fw_wf_effect_result r
                    WHERE r.tenant_id = j.tenant_id AND r.effect_id = j.effect_id) AS result_payload,
                (SELECT r.retry_time FROM fw_wf_effect_result r
                    WHERE r.tenant_id = j.tenant_id AND r.effect_id = j.effect_id) AS retry_time,
                (SELECT r.completed_time FROM fw_wf_effect_result r
                    WHERE r.tenant_id = j.tenant_id AND r.effect_id = j.effect_id) AS completed_time
            FROM fw_wf_job j
            JOIN fw_wf_effect e ON e.tenant_id = j.tenant_id AND e.id = j.effect_id
        """
        const val SELECT_READY_SQL = SELECT_COLUMNS + """
            WHERE j.tenant_id = ? AND j.job_type = ? AND j.available_time <= ?
                AND (j.lease_expires_time IS NULL OR j.lease_expires_time <= ?)
                AND (
                    e.delivery_status = ? OR
                    (e.delivery_status = ? AND e.next_attempt_time <= ?) OR
                    (e.delivery_status = ? AND e.execution_phase = ? AND e.lease_expires_time <= ?) OR
                    (e.delivery_status = ? AND EXISTS (
                        SELECT 1 FROM fw_wf_effect_result r
                        WHERE r.tenant_id = j.tenant_id AND r.effect_id = j.effect_id
                    )) OR
                    (e.delivery_status = ? AND EXISTS (
                        SELECT 1 FROM fw_wf_effect_result r
                        WHERE r.tenant_id = j.tenant_id AND r.effect_id = j.effect_id
                    ))
                )
            ORDER BY j.available_time, j.created_time, j.id
            LIMIT ? FOR UPDATE SKIP LOCKED
        """
        const val SELECT_EXPIRED_PROVIDER_SQL = SELECT_COLUMNS + """
            WHERE j.tenant_id = ? AND j.job_type = ? AND e.delivery_status = ?
                AND e.execution_phase = ? AND e.lease_expires_time <= ?
            ORDER BY e.lease_expires_time, j.created_time, j.id
            LIMIT ? FOR UPDATE SKIP LOCKED
        """
        const val SELECT_JOB_BY_ID_SQL = SELECT_COLUMNS + " WHERE j.tenant_id = ? AND j.id = ?"
        const val SELECT_CLAIMS_BY_REQUEST_SQL = SELECT_COLUMNS + """
            WHERE j.tenant_id = ? AND j.claim_request_digest = ? AND j.worker_id = ?
                AND j.lease_expires_time > ?
            ORDER BY j.created_time, j.id
        """
        const val SELECT_RESULT_SQL = """
            SELECT result_type, outcome_code, result_digest, result_payload, retry_time, completed_time
            FROM fw_wf_effect_result WHERE tenant_id = ? AND effect_id = ?
        """
        const val CLAIM_JOB_SQL = """
            UPDATE fw_wf_job SET job_status = ?, record_version = ?, lease_id = ?, worker_id = ?,
                fencing_token = ?, lease_acquired_time = ?, lease_expires_time = ?, execution_mode = ?,
                claim_request_digest = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ?
        """
        const val INSERT_RESULT_SQL = """
            INSERT INTO fw_wf_effect_result(
                id, tenant_id, instance_id, effect_id, result_type, outcome_code, result_digest,
                result_payload, retry_time, completed_time, result_version, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val RECOVER_EFFECT_SQL = """
            UPDATE fw_wf_effect SET delivery_status = ?, outcome_digest = ?,
                record_version = record_version + 1, lease_id = NULL, worker_id = NULL,
                lease_acquired_time = NULL, lease_expires_time = NULL, execution_phase = NULL,
                next_attempt_time = NULL, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ?
        """
        const val RELEASE_RECOVERED_JOB_SQL = """
            UPDATE fw_wf_job SET job_status = ?, failure_digest = ?, record_version = record_version + 1,
                lease_id = NULL, worker_id = NULL, lease_acquired_time = NULL,
                lease_expires_time = NULL, execution_mode = NULL, claim_request_digest = NULL,
                updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ?
        """
    }
}

private fun stableId(vararg values: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(byteArrayOf(
            (bytes.size ushr 24).toByte(),
            (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(),
            bytes.size.toByte(),
        ))
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun setNullableLong(statement: java.sql.PreparedStatement, index: Int, value: Long?) {
    if (value == null) statement.setNull(index, Types.BIGINT) else statement.setLong(index, value)
}

private fun nullableLong(result: ResultSet, column: String): Long? =
    result.getLong(column).let { value -> if (result.wasNull()) null else value }
