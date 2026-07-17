package ai.icen.fw.workflow.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.domain.WorkflowCommandCode
import ai.icen.fw.workflow.domain.WorkflowEffectCode
import ai.icen.fw.workflow.domain.WorkflowEffectIntent
import ai.icen.fw.workflow.domain.WorkflowHumanDecision
import ai.icen.fw.workflow.domain.WorkflowHumanRuleSnapshot
import ai.icen.fw.workflow.domain.WorkflowInstanceState
import ai.icen.fw.workflow.domain.WorkflowResultCode
import ai.icen.fw.workflow.runtime.WorkflowEffectCheckpoint
import ai.icen.fw.workflow.runtime.WorkflowEffectClaim
import ai.icen.fw.workflow.runtime.WorkflowEffectDeliveryStatus
import ai.icen.fw.workflow.runtime.WorkflowEffectExecutionPhase
import ai.icen.fw.workflow.runtime.WorkflowEffectIncidentResolution
import ai.icen.fw.workflow.runtime.WorkflowEffectIncidentSnapshot
import ai.icen.fw.workflow.runtime.WorkflowEffectJobStoredResult
import ai.icen.fw.workflow.runtime.WorkflowEffectLease
import ai.icen.fw.workflow.runtime.WorkflowEffectObservedOutcome
import ai.icen.fw.workflow.runtime.WorkflowEffectOperationCode
import ai.icen.fw.workflow.runtime.WorkflowEffectOperationResult
import ai.icen.fw.workflow.runtime.WorkflowEffectOutcome
import ai.icen.fw.workflow.runtime.WorkflowEffectReconciliationIncident
import ai.icen.fw.workflow.runtime.WorkflowEffectRecord
import ai.icen.fw.workflow.runtime.WorkflowEffectRetry
import ai.icen.fw.workflow.runtime.WorkflowIncidentOperationCode
import ai.icen.fw.workflow.runtime.WorkflowIncidentOperationResult
import ai.icen.fw.workflow.runtime.WorkflowIncidentPersistencePort
import ai.icen.fw.workflow.runtime.WorkflowIncidentStatus
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAtomicCommit
import ai.icen.fw.workflow.runtime.WorkflowRuntimeCommandSnapshot
import ai.icen.fw.workflow.runtime.WorkflowRuntimeCommitCode
import ai.icen.fw.workflow.runtime.WorkflowRuntimeCommitResult
import ai.icen.fw.workflow.runtime.WorkflowRuntimeDefinitionRecord
import ai.icen.fw.workflow.runtime.WorkflowRuntimeIdempotencyRecord
import ai.icen.fw.workflow.runtime.WorkflowRuntimePersistencePort
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

/**
 * Tenant-scoped JDBC implementation of the Workflow durable runtime port.
 * Every mutation is a short local transaction; this class contains no provider, dispatcher or
 * authorization callback and therefore cannot perform an external call while holding locks.
 */
class JdbcWorkflowRuntimePersistence @JvmOverloads constructor(
    dataSource: DataSource,
    dialect: WorkflowJdbcDialect? = null,
) : WorkflowRuntimePersistencePort, WorkflowIncidentPersistencePort {
    private val transactions = WorkflowJdbcTransactions(dataSource, dialect)
    private val definitions = JdbcWorkflowDefinitionStore(dataSource, dialect)

    override fun loadDefinition(
        tenantId: String,
        definitionId: String,
        definitionRef: WorkflowDefinitionRef,
    ): WorkflowRuntimeDefinitionRecord? = definitions.load(tenantId, definitionId, definitionRef)

    override fun loadCommandSnapshot(
        tenantId: String,
        instanceId: String,
        idempotencyKey: String,
        readAt: Long,
    ): WorkflowRuntimeCommandSnapshot = transactions.transaction { connection, _ ->
        val state = loadState(connection, tenantId, instanceId, forUpdate = false)
        val idempotency = loadIdempotency(connection, tenantId, instanceId, idempotencyKey, forUpdate = false)
        WorkflowRuntimeCommandSnapshot.of(tenantId, instanceId, idempotencyKey, state, idempotency, readAt)
    }

    override fun commit(request: WorkflowRuntimeAtomicCommit): WorkflowRuntimeCommitResult =
        transactions.transaction { connection, dialect ->
            val current = loadState(connection, request.tenantId, request.instanceId, forUpdate = true)
            if (!matchesExpectedState(current, request)) {
                return@transaction conflict(WorkflowRuntimeCommitCode.VERSION_CONFLICT)
            }
            if (loadIdempotency(
                    connection,
                    request.tenantId,
                    request.instanceId,
                    request.idempotency.idempotencyKey,
                    forUpdate = true,
                ) != null
            ) {
                return@transaction conflict(WorkflowRuntimeCommitCode.IDEMPOTENCY_CONFLICT)
            }
            if (request.effectAcknowledgement != null &&
                !acknowledgeEffect(connection, request)
            ) {
                return@transaction conflict(WorkflowRuntimeCommitCode.EFFECT_CONFLICT)
            }
            if (!writeState(connection, dialect, request)) {
                return@transaction conflict(WorkflowRuntimeCommitCode.VERSION_CONFLICT)
            }
            writeStateProjections(connection, request.state)
            insertEvents(connection, request)
            insertEffects(connection, request)
            insertIdempotency(connection, request.idempotency)
            WorkflowRuntimeCommitResult.committed(request.state.version)
        }

    override fun loadEffect(tenantId: String, effectId: String, readAt: Long): WorkflowEffectRecord? =
        transactions.read { connection, _ -> loadEffectRow(connection, tenantId, effectId, false)?.record }

    override fun claimEffect(request: WorkflowEffectClaim): WorkflowEffectOperationResult =
        transactions.transaction { connection, _ ->
            val row = loadEffectRow(connection, request.tenantId, request.effectId, true)
                ?: return@transaction failed(WorkflowEffectOperationCode.NOT_FOUND)
            if (row.record.version != request.expectedRecordVersion) {
                return@transaction failed(WorkflowEffectOperationCode.VERSION_CONFLICT)
            }
            if (!jobLeaseMatches(
                    connection,
                    request.tenantId,
                    request.effectId,
                    request.lease.leaseId,
                    request.lease.fencingToken,
                    request.lease.acquiredAt,
                )
            ) {
                return@transaction failed(WorkflowEffectOperationCode.LEASE_MISMATCH)
            }
            if (row.lastFencingToken != null && request.lease.fencingToken <= row.lastFencingToken) {
                return@transaction failed(WorkflowEffectOperationCode.LEASE_MISMATCH)
            }
            if (!claimEligible(row.record, request.lease.acquiredAt)) {
                return@transaction failed(
                    if (requiresReconciliation(row.record, request.lease.acquiredAt)) {
                        WorkflowEffectOperationCode.RECONCILIATION_REQUIRED
                    } else {
                        WorkflowEffectOperationCode.NOT_ELIGIBLE
                    },
                )
            }
            connection.prepareStatement(CLAIM_EFFECT_SQL).use { statement ->
                statement.setString(1, WorkflowEffectDeliveryStatus.LEASED.code)
                statement.setInt(2, row.record.attempt + 1)
                statement.setString(3, request.lease.leaseId)
                statement.setString(4, request.lease.workerId)
                statement.setLong(5, request.lease.fencingToken)
                statement.setLong(6, request.lease.acquiredAt)
                statement.setLong(7, request.lease.expiresAt)
                statement.setString(8, WorkflowEffectExecutionPhase.PREPARED.code)
                statement.setLong(9, request.lease.acquiredAt)
                statement.setString(10, request.tenantId)
                statement.setString(11, request.effectId)
                statement.setLong(12, request.expectedRecordVersion)
                check(statement.executeUpdate() == 1) { "Workflow effect claim CAS changed while locked." }
            }
            updateJob(connection, request.tenantId, request.effectId, "leased", request.lease.acquiredAt, null)
            applied(requireNotNull(loadEffectRow(connection, request.tenantId, request.effectId, false)).record)
        }

    override fun checkpointEffect(request: WorkflowEffectCheckpoint): WorkflowEffectOperationResult =
        transactions.transaction { connection, _ ->
            val row = loadEffectRow(connection, request.tenantId, request.effectId, true)
                ?: return@transaction failed(WorkflowEffectOperationCode.NOT_FOUND)
            effectLeaseMutationFailure(
                row,
                request.expectedRecordVersion,
                request.leaseId,
                request.fencingToken,
                request.checkpointedAt,
            )?.let { return@transaction failed(it) }
            if (!jobLeaseMatches(
                    connection,
                    request.tenantId,
                    request.effectId,
                    request.leaseId,
                    request.fencingToken,
                    request.checkpointedAt,
                )
            ) return@transaction failed(WorkflowEffectOperationCode.LEASE_MISMATCH)
            if (request.sequence <= row.record.checkpointSequence ||
                row.record.phase == WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED &&
                request.phase == WorkflowEffectExecutionPhase.PREPARED
            ) {
                return@transaction failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
            }
            connection.prepareStatement(CHECKPOINT_EFFECT_SQL).use { statement ->
                statement.setString(1, request.phase.code)
                statement.setLong(2, request.sequence)
                statement.setString(3, request.checkpointDigest)
                statement.setLong(4, request.checkpointedAt)
                bindLeaseCas(statement, 5, request)
                check(statement.executeUpdate() == 1) { "Workflow effect checkpoint CAS changed while locked." }
            }
            updateJob(connection, request.tenantId, request.effectId, "running", request.checkpointedAt, null)
            applied(requireNotNull(loadEffectRow(connection, request.tenantId, request.effectId, false)).record)
        }

    override fun recordEffectOutcome(request: WorkflowEffectOutcome): WorkflowEffectOperationResult =
        transactions.transaction { connection, _ ->
            val row = loadEffectRow(connection, request.tenantId, request.effectId, true)
                ?: return@transaction failed(WorkflowEffectOperationCode.NOT_FOUND)
            effectLeaseMutationFailure(
                row,
                request.expectedRecordVersion,
                request.leaseId,
                request.fencingToken,
                request.completedAt,
            )?.let { return@transaction failed(it) }
            if (!jobLeaseMatches(
                    connection,
                    request.tenantId,
                    request.effectId,
                    request.leaseId,
                    request.fencingToken,
                    request.completedAt,
                )
            ) return@transaction failed(WorkflowEffectOperationCode.LEASE_MISMATCH)
            if (row.record.phase != WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED) {
                return@transaction failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
            }
            val deliveryStatus = outcomeStatus(request.outcome)
            connection.prepareStatement(RECORD_EFFECT_OUTCOME_SQL).use { statement ->
                statement.setString(1, deliveryStatus.code)
                statement.setString(2, request.outcomeDigest)
                statement.setLong(3, request.completedAt)
                bindLeaseCas(statement, 4, request)
                check(statement.executeUpdate() == 1) { "Workflow effect outcome CAS changed while locked." }
            }
            if (deliveryStatus == WorkflowEffectDeliveryStatus.TERMINAL_FAILURE ||
                deliveryStatus == WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN
            ) {
                releaseJob(
                    connection,
                    request.tenantId,
                    request.effectId,
                    deliveryStatus.code,
                    request.completedAt,
                    null,
                    request.outcomeDigest,
                )
            } else {
                updateJob(
                    connection,
                    request.tenantId,
                    request.effectId,
                    deliveryStatus.code,
                    request.completedAt,
                    request.outcomeDigest,
                )
            }
            applied(requireNotNull(loadEffectRow(connection, request.tenantId, request.effectId, false)).record)
        }

    override fun scheduleEffectRetry(request: WorkflowEffectRetry): WorkflowEffectOperationResult =
        transactions.transaction { connection, _ ->
            val row = loadEffectRow(connection, request.tenantId, request.effectId, true)
                ?: return@transaction failed(WorkflowEffectOperationCode.NOT_FOUND)
            if (row.record.version != request.expectedRecordVersion) {
                return@transaction failed(WorkflowEffectOperationCode.VERSION_CONFLICT)
            }
            if (row.record.status != WorkflowEffectDeliveryStatus.RETRYABLE_FAILURE) {
                return@transaction failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
            }
            val retryLeaseId = request.leaseId
            if (retryLeaseId != null && !jobLeaseMatches(
                    connection,
                    request.tenantId,
                    request.effectId,
                    retryLeaseId,
                    requireNotNull(request.fencingToken),
                    request.scheduledAt,
                )
            ) return@transaction failed(WorkflowEffectOperationCode.LEASE_MISMATCH)
            connection.prepareStatement(SCHEDULE_EFFECT_RETRY_SQL).use { statement ->
                statement.setString(1, WorkflowEffectDeliveryStatus.RETRY_WAIT.code)
                statement.setLong(2, request.nextAttemptAt)
                statement.setString(3, request.retryReasonDigest)
                statement.setLong(4, request.scheduledAt)
                statement.setString(5, request.tenantId)
                statement.setString(6, request.effectId)
                statement.setLong(7, request.expectedRecordVersion)
                check(statement.executeUpdate() == 1) { "Workflow effect retry CAS changed while locked." }
            }
            releaseJob(
                connection,
                request.tenantId,
                request.effectId,
                "retry-wait",
                request.scheduledAt,
                request.nextAttemptAt,
                null,
            )
            applied(requireNotNull(loadEffectRow(connection, request.tenantId, request.effectId, false)).record)
        }

    override fun raiseEffectReconciliationIncident(
        request: WorkflowEffectReconciliationIncident,
    ): WorkflowEffectOperationResult = transactions.transaction { connection, _ ->
        val row = loadEffectRow(connection, request.tenantId, request.effectId, true)
            ?: return@transaction failed(WorkflowEffectOperationCode.NOT_FOUND)
        if (row.record.version != request.expectedRecordVersion) {
            return@transaction failed(WorkflowEffectOperationCode.VERSION_CONFLICT)
        }
        if (row.record.status != WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN) {
            return@transaction failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
        }
        connection.prepareStatement(RAISE_RECONCILIATION_SQL).use { statement ->
            statement.setString(1, WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT.code)
            statement.setString(2, request.evidenceDigest)
            statement.setLong(3, request.raisedAt)
            statement.setString(4, request.tenantId)
            statement.setString(5, request.effectId)
            statement.setLong(6, request.expectedRecordVersion)
            check(statement.executeUpdate() == 1) { "Workflow effect reconciliation CAS changed while locked." }
        }
        insertIncident(connection, row.record.intent, request)
        releaseJob(
            connection,
            request.tenantId,
            request.effectId,
            "incident",
            request.raisedAt,
            null,
            request.evidenceDigest,
        )
        applied(requireNotNull(loadEffectRow(connection, request.tenantId, request.effectId, false)).record)
    }

    override fun loadEffectIncident(
        tenantId: String,
        incidentId: String,
        readAt: Long,
    ): WorkflowEffectIncidentSnapshot? = transactions.read { connection, _ ->
        require(readAt >= 0L) { "Workflow incident read time is invalid." }
        val incident = loadIncidentRow(connection, tenantId, incidentId, forUpdate = false)
            ?: return@read null
        incident.snapshot(
            requireNotNull(loadEffectRow(connection, tenantId, incident.effectId, false)) {
                "Workflow incident references a missing effect."
            }.record,
        )
    }

    override fun resolveEffectIncident(
        request: WorkflowEffectIncidentResolution,
    ): WorkflowIncidentOperationResult = transactions.transaction { connection, _ ->
        val incident = loadIncidentRow(connection, request.tenantId, request.incidentId, forUpdate = true)
            ?: return@transaction incidentFailed(WorkflowIncidentOperationCode.NOT_FOUND)
        if (incident.effectId != request.effectId) {
            return@transaction incidentFailed(WorkflowIncidentOperationCode.NOT_ELIGIBLE)
        }
        val effect = loadEffectRow(connection, request.tenantId, request.effectId, true)
            ?: return@transaction incidentFailed(WorkflowIncidentOperationCode.NOT_FOUND)
        if (effect.record.intent.instanceId != incident.instanceId) {
            throw IllegalStateException("Workflow incident and effect instance bindings are inconsistent.")
        }
        val storedResult = loadIncidentStoredResult(connection, request.tenantId, request.effectId, true)
        if (incident.status == WorkflowIncidentStatus.RESOLVED) {
            return@transaction if (incident.repairDigest == request.repairDigest && storedResult == request.result) {
                WorkflowIncidentOperationResult.replayed(incident.snapshot(effect.record))
            } else {
                incidentFailed(WorkflowIncidentOperationCode.NOT_ELIGIBLE)
            }
        }
        if (incident.status != WorkflowIncidentStatus.OPEN ||
            effect.record.status != WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT
        ) {
            return@transaction incidentFailed(WorkflowIncidentOperationCode.NOT_ELIGIBLE)
        }
        if (effect.record.version != request.expectedEffectVersion) {
            return@transaction incidentFailed(WorkflowIncidentOperationCode.VERSION_CONFLICT)
        }
        if (storedResult != null && storedResult != request.result) {
            return@transaction incidentFailed(WorkflowIncidentOperationCode.NOT_ELIGIBLE)
        }
        if (storedResult == null) {
            insertIncidentStoredResult(connection, incident, request)
        }

        val targetStatus = when (request.result.outcome) {
            WorkflowEffectObservedOutcome.SUCCEEDED -> WorkflowEffectDeliveryStatus.SUCCEEDED
            WorkflowEffectObservedOutcome.RETRYABLE_FAILURE -> WorkflowEffectDeliveryStatus.RETRY_WAIT
            WorkflowEffectObservedOutcome.TERMINAL_FAILURE -> WorkflowEffectDeliveryStatus.TERMINAL_FAILURE
            else -> return@transaction incidentFailed(WorkflowIncidentOperationCode.NOT_ELIGIBLE)
        }
        connection.prepareStatement(RESOLVE_INCIDENT_EFFECT_SQL).use { statement ->
            statement.setString(1, targetStatus.code)
            statement.setString(2, request.result.resultDigest)
            setNullableLong(statement, 3, request.result.retryAt)
            setNullableString(
                statement,
                4,
                if (request.result.outcome == WorkflowEffectObservedOutcome.RETRYABLE_FAILURE) {
                    request.repairDigest
                } else {
                    null
                },
            )
            statement.setLong(5, request.resolvedAt)
            statement.setString(6, request.tenantId)
            statement.setString(7, request.effectId)
            statement.setLong(8, request.expectedEffectVersion)
            statement.setString(9, WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT.code)
            check(statement.executeUpdate() == 1) { "Workflow incident effect CAS changed while locked." }
        }
        connection.prepareStatement(RESOLVE_INCIDENT_SQL).use { statement ->
            statement.setString(1, WorkflowIncidentStatus.RESOLVED.code)
            statement.setString(2, request.repairDigest)
            statement.setLong(3, request.resolvedAt)
            statement.setLong(4, request.resolvedAt)
            statement.setString(5, request.tenantId)
            statement.setString(6, request.incidentId)
            statement.setString(7, WorkflowIncidentStatus.OPEN.code)
            check(statement.executeUpdate() == 1) { "Workflow incident resolution CAS changed while locked." }
        }
        val jobStatus: String
        val availableAt: Long
        val failureDigest: String?
        when (request.result.outcome) {
            WorkflowEffectObservedOutcome.SUCCEEDED -> {
                jobStatus = WorkflowEffectDeliveryStatus.SUCCEEDED.code
                availableAt = request.resolvedAt
                failureDigest = null
            }
            WorkflowEffectObservedOutcome.RETRYABLE_FAILURE -> {
                jobStatus = WorkflowEffectDeliveryStatus.RETRY_WAIT.code
                availableAt = requireNotNull(request.result.retryAt)
                failureDigest = request.repairDigest
            }
            WorkflowEffectObservedOutcome.TERMINAL_FAILURE -> {
                jobStatus = WorkflowEffectDeliveryStatus.TERMINAL_FAILURE.code
                availableAt = request.resolvedAt
                failureDigest = request.repairDigest
            }
            else -> throw IllegalStateException("Unknown workflow incident resolution outcome.")
        }
        releaseJob(
            connection,
            request.tenantId,
            request.effectId,
            jobStatus,
            request.resolvedAt,
            availableAt,
            failureDigest,
        )
        val resolvedIncident = requireNotNull(
            loadIncidentRow(connection, request.tenantId, request.incidentId, forUpdate = false),
        )
        val resolvedEffect = requireNotNull(
            loadEffectRow(connection, request.tenantId, request.effectId, false),
        ).record
        WorkflowIncidentOperationResult.resolved(resolvedIncident.snapshot(resolvedEffect))
    }

    private fun loadState(
        connection: Connection,
        tenantId: String,
        instanceId: String,
        forUpdate: Boolean,
    ): WorkflowInstanceState? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        connection.prepareStatement(
            "SELECT state_payload, state_digest, instance_version FROM fw_wf_instance " +
                "WHERE tenant_id = ? AND id = ?$lock",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, instanceId)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                val state = WorkflowJdbcBinaryCodec.decodeState(result.getBytes("state_payload"))
                check(state.tenantId == tenantId && state.instanceId == instanceId &&
                    state.version == result.getLong("instance_version") &&
                    state.stateDigest == result.getString("state_digest")
                ) { "Persisted workflow state payload does not match its projection." }
                return state
            }
        }
    }

    private fun loadIdempotency(
        connection: Connection,
        tenantId: String,
        instanceId: String,
        idempotencyKey: String,
        forUpdate: Boolean,
    ): WorkflowRuntimeIdempotencyRecord? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        connection.prepareStatement(
            """
            SELECT logical_request_digest, command_code, domain_command_digest, result_version,
                   effect_count, domain_result_code, committed_time
            FROM fw_wf_idempotency
            WHERE tenant_id = ? AND instance_id = ? AND idempotency_key = ?$lock
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, instanceId)
            statement.setString(3, idempotencyKey)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                return WorkflowRuntimeIdempotencyRecord.of(
                    tenantId,
                    instanceId,
                    idempotencyKey,
                    result.getString("logical_request_digest"),
                    WorkflowCommandCode.of(result.getString("command_code")),
                    result.getString("domain_command_digest"),
                    result.getLong("result_version"),
                    result.getInt("effect_count"),
                    resultCode(result.getString("domain_result_code")),
                    result.getLong("committed_time"),
                )
            }
        }
    }

    private fun matchesExpectedState(current: WorkflowInstanceState?, request: WorkflowRuntimeAtomicCommit): Boolean =
        if (request.expectedInstanceVersion == 0L) {
            current == null && request.expectedStateDigest == null
        } else {
            current != null && current.version == request.expectedInstanceVersion &&
                current.stateDigest == request.expectedStateDigest
        }

    private fun writeState(
        connection: Connection,
        dialect: WorkflowJdbcDialect,
        request: WorkflowRuntimeAtomicCommit,
    ): Boolean {
        val state = request.state
        val payload = WorkflowJdbcBinaryCodec.encodeState(state)
        if (request.expectedInstanceVersion == 0L) {
            connection.prepareStatement(dialect.insertInstanceSql()).use { statement ->
                bindInstance(statement, state, payload)
                return statement.executeUpdate() == 1
            }
        }
        connection.prepareStatement(UPDATE_INSTANCE_SQL).use { statement ->
            statement.setString(1, state.status.code)
            statement.setLong(2, state.version)
            statement.setString(3, state.stateDigest)
            statement.setBytes(4, payload)
            statement.setLong(5, state.updatedAt)
            statement.setString(6, state.tenantId)
            statement.setString(7, state.instanceId)
            statement.setLong(8, request.expectedInstanceVersion)
            statement.setString(9, request.expectedStateDigest)
            return statement.executeUpdate() == 1
        }
    }

    private fun bindInstance(statement: PreparedStatement, state: WorkflowInstanceState, payload: ByteArray) {
        statement.setString(1, state.instanceId)
        statement.setString(2, state.tenantId)
        statement.setString(3, state.definitionId)
        statement.setString(4, state.definitionRef.key)
        statement.setString(5, state.definitionRef.version)
        statement.setString(6, state.definitionRef.digest)
        statement.setString(7, state.subject.ref.type)
        statement.setString(8, state.subject.ref.id)
        statement.setString(9, state.subject.revision)
        statement.setString(10, state.subject.digest)
        statement.setString(11, state.initiator.type)
        statement.setString(12, state.initiator.id)
        statement.setString(13, state.status.code)
        statement.setLong(14, state.version)
        statement.setString(15, state.stateDigest)
        statement.setBytes(16, payload)
        statement.setLong(17, state.createdAt)
        statement.setLong(18, state.updatedAt)
    }

    private fun acknowledgeEffect(connection: Connection, request: WorkflowRuntimeAtomicCommit): Boolean {
        val acknowledgement = requireNotNull(request.effectAcknowledgement)
        val row = loadEffectRow(connection, request.tenantId, acknowledgement.effectId, true) ?: return false
        if (row.record.intent.instanceId != request.instanceId ||
            row.record.intent.requestDigest != acknowledgement.requestDigest ||
            row.record.status != WorkflowEffectDeliveryStatus.SUCCEEDED &&
            row.record.status != WorkflowEffectDeliveryStatus.TERMINAL_FAILURE
        ) return false
        val acknowledgementLeaseId = acknowledgement.leaseId
        if (acknowledgementLeaseId != null && !jobLeaseMatches(
                connection,
                request.tenantId,
                acknowledgement.effectId,
                acknowledgementLeaseId,
                requireNotNull(acknowledgement.fencingToken),
                request.committedAt,
            )
        ) return false
        connection.prepareStatement(ACKNOWLEDGE_EFFECT_SQL).use { statement ->
            statement.setString(1, WorkflowEffectDeliveryStatus.DOMAIN_APPLIED.code)
            statement.setString(2, acknowledgement.kind.code)
            statement.setString(3, acknowledgement.receiptDigest)
            statement.setLong(4, request.committedAt)
            statement.setString(5, request.tenantId)
            statement.setString(6, acknowledgement.effectId)
            statement.setLong(7, row.record.version)
            if (statement.executeUpdate() != 1) return false
        }
        releaseJob(
            connection,
            request.tenantId,
            acknowledgement.effectId,
            "domain-applied",
            request.committedAt,
            null,
            null,
        )
        return true
    }

    private fun insertEvents(connection: Connection, request: WorkflowRuntimeAtomicCommit) {
        connection.prepareStatement(INSERT_EVENT_SQL).use { statement ->
            request.events.forEach { event ->
                statement.setString(1, event.eventId)
                statement.setString(2, event.tenantId)
                statement.setString(3, event.instanceId)
                statement.setString(4, event.definitionId)
                statement.setString(5, event.definitionRef.key)
                statement.setString(6, event.definitionRef.version)
                statement.setString(7, event.definitionRef.digest)
                statement.setString(8, event.code.code)
                setNullableString(statement, 9, event.tokenId)
                setNullableString(statement, 10, event.nodeExecutionId)
                setNullableString(statement, 11, event.workItemId)
                setNullableString(statement, 12, event.nodeId)
                statement.setString(13, event.subject.ref.type)
                statement.setString(14, event.subject.ref.id)
                statement.setString(15, event.subject.revision)
                statement.setString(16, event.subject.digest)
                statement.setString(17, event.payloadDigest)
                statement.setLong(18, event.instanceVersion)
                statement.setString(19, event.eventDigest)
                statement.setLong(20, event.occurredAt)
                statement.setLong(21, event.occurredAt)
                statement.setLong(22, event.occurredAt)
                statement.addBatch()
            }
            if (request.events.isNotEmpty()) statement.executeBatch()
        }
    }

    private fun insertEffects(connection: Connection, request: WorkflowRuntimeAtomicCommit) {
        connection.prepareStatement(INSERT_EFFECT_SQL).use { effectStatement ->
            connection.prepareStatement(INSERT_JOB_SQL).use { jobStatement ->
                request.effects.forEach { effect ->
                    bindEffect(effectStatement, effect)
                    effectStatement.addBatch()
                    jobStatement.setString(1, jobId(effect.effectId))
                    jobStatement.setString(2, effect.tenantId)
                    jobStatement.setString(3, effect.instanceId)
                    jobStatement.setString(4, effect.effectId)
                    jobStatement.setString(5, effect.code.code)
                    jobStatement.setString(6, "pending")
                    jobStatement.setLong(7, effect.createdAt)
                    jobStatement.setLong(8, effect.createdAt)
                    jobStatement.setLong(9, effect.createdAt)
                    jobStatement.addBatch()
                }
                if (request.effects.isNotEmpty()) {
                    effectStatement.executeBatch()
                    jobStatement.executeBatch()
                }
            }
        }
    }

    private fun bindEffect(statement: PreparedStatement, effect: WorkflowEffectIntent) {
        statement.setString(1, effect.effectId)
        statement.setString(2, effect.tenantId)
        statement.setString(3, effect.instanceId)
        statement.setString(4, effect.definitionId)
        statement.setString(5, effect.definitionRef.key)
        statement.setString(6, effect.definitionRef.version)
        statement.setString(7, effect.definitionRef.digest)
        statement.setString(8, effect.subject.ref.type)
        statement.setString(9, effect.subject.ref.id)
        statement.setString(10, effect.subject.revision)
        statement.setString(11, effect.subject.digest)
        setNullableString(statement, 12, effect.tokenId)
        setNullableString(statement, 13, effect.nodeExecutionId)
        setNullableString(statement, 14, effect.workItemId)
        setNullableString(statement, 15, effect.nodeId)
        setNullableInt(statement, 16, effect.ruleIndex)
        statement.setString(17, effect.code.code)
        statement.setString(18, effect.payloadDigest)
        statement.setString(19, effect.requestDigest)
        statement.setString(20, WorkflowEffectDeliveryStatus.PENDING.code)
        statement.setLong(21, 0L)
        statement.setInt(22, 0)
        statement.setLong(23, 0L)
        statement.setLong(24, effect.createdAt)
        statement.setLong(25, effect.createdAt)
    }

    private fun insertIdempotency(connection: Connection, record: WorkflowRuntimeIdempotencyRecord) {
        connection.prepareStatement(INSERT_IDEMPOTENCY_SQL).use { statement ->
            statement.setString(1, stableRowId(record.tenantId, record.instanceId, record.idempotencyKey))
            statement.setString(2, record.tenantId)
            statement.setString(3, record.instanceId)
            statement.setString(4, record.idempotencyKey)
            statement.setString(5, record.logicalRequestDigest)
            statement.setString(6, record.commandCode.code)
            statement.setString(7, record.domainCommandDigest)
            statement.setLong(8, record.resultVersion)
            statement.setInt(9, record.effectCount)
            statement.setString(10, record.domainResultCode.code)
            statement.setLong(11, record.committedAt)
            statement.setLong(12, record.committedAt)
            statement.setLong(13, record.committedAt)
            check(statement.executeUpdate() == 1) { "Workflow idempotency record was not inserted." }
        }
    }

    private fun writeStateProjections(connection: Connection, state: WorkflowInstanceState) {
        connection.prepareStatement("DELETE FROM fw_wf_token WHERE tenant_id = ? AND instance_id = ?").use { statement ->
            statement.setString(1, state.tenantId)
            statement.setString(2, state.instanceId)
            statement.executeUpdate()
        }
        connection.prepareStatement(INSERT_TOKEN_SQL).use { statement ->
            state.tokens.forEach { token ->
                statement.setString(1, token.tokenId)
                statement.setString(2, state.tenantId)
                statement.setString(3, state.instanceId)
                statement.setString(4, token.nodeId)
                statement.setString(5, token.status.code)
                statement.setLong(6, token.revision)
                setNullableString(statement, 7, token.waitingExecutionId)
                statement.setString(8, token.contentDigest)
                statement.setLong(9, state.createdAt)
                statement.setLong(10, state.updatedAt)
                statement.addBatch()
            }
            statement.executeBatch()
        }

        connection.prepareStatement("DELETE FROM fw_wf_node_execution WHERE tenant_id = ? AND instance_id = ?").use { statement ->
            statement.setString(1, state.tenantId)
            statement.setString(2, state.instanceId)
            statement.executeUpdate()
        }
        connection.prepareStatement(INSERT_EXECUTION_SQL).use { statement ->
            state.nodeExecutions.forEach { execution ->
                statement.setString(1, execution.executionId)
                statement.setString(2, state.tenantId)
                statement.setString(3, state.instanceId)
                statement.setString(4, execution.tokenId)
                statement.setString(5, execution.nodeId)
                statement.setString(6, execution.status.code)
                statement.setLong(7, execution.revision)
                statement.setLong(8, execution.startedAt)
                setNullableLong(statement, 9, execution.completedAt)
                setNullableString(statement, 10, execution.pendingEffectId)
                setNullableString(statement, 11, execution.pendingEffectCode?.code)
                setNullableString(statement, 12, execution.effectRequestDigest)
                statement.setString(13, execution.contentDigest)
                statement.setLong(14, state.createdAt)
                statement.setLong(15, state.updatedAt)
                statement.addBatch()
            }
            if (state.nodeExecutions.isNotEmpty()) statement.executeBatch()
        }

        state.humanWorkItems.forEach { workItem ->
            upsertHumanTask(connection, state, workItem)
            workItem.ruleSnapshots.forEach { snapshot -> insertCandidates(connection, state, workItem.workItemId, snapshot) }
            workItem.decisions.forEach { decision -> insertDecision(connection, state, workItem.workItemId, decision) }
            workItem.collaboration.records.forEach { record ->
                insertCollaborationRecord(connection, state, workItem.workItemId, record)
            }
        }
    }

    private fun upsertHumanTask(
        connection: Connection,
        state: WorkflowInstanceState,
        workItem: ai.icen.fw.workflow.domain.WorkflowHumanWorkItemState,
    ) {
        val updated = connection.prepareStatement(UPDATE_HUMAN_TASK_SQL).use { statement ->
            statement.setString(1, workItem.status.code)
            statement.setInt(2, workItem.activeRuleIndex)
            statement.setLong(3, workItem.revision)
            statement.setString(4, workItem.contentDigest)
            setNullableString(statement, 5, workItem.collaboration.claimOwner?.type)
            setNullableString(statement, 6, workItem.collaboration.claimOwner?.id)
            setNullableString(statement, 7, workItem.collaboration.activeDelegate?.type)
            setNullableString(statement, 8, workItem.collaboration.activeDelegate?.id)
            statement.setInt(9, workItem.collaboration.assignmentPath.size)
            statement.setLong(10, workItem.updatedAt)
            statement.setString(11, state.tenantId)
            statement.setString(12, state.instanceId)
            statement.setString(13, workItem.workItemId)
            statement.executeUpdate()
        }
        if (updated == 0) {
            connection.prepareStatement(INSERT_HUMAN_TASK_SQL).use { statement ->
                statement.setString(1, workItem.workItemId)
                statement.setString(2, state.tenantId)
                statement.setString(3, state.instanceId)
                statement.setString(4, workItem.nodeExecutionId)
                statement.setString(5, workItem.tokenId)
                statement.setString(6, workItem.nodeId)
                statement.setString(7, workItem.policyDigest)
                statement.setString(8, workItem.status.code)
                statement.setInt(9, workItem.activeRuleIndex)
                statement.setLong(10, workItem.revision)
                statement.setString(11, workItem.contentDigest)
                setNullableString(statement, 12, workItem.collaboration.claimOwner?.type)
                setNullableString(statement, 13, workItem.collaboration.claimOwner?.id)
                setNullableString(statement, 14, workItem.collaboration.activeDelegate?.type)
                setNullableString(statement, 15, workItem.collaboration.activeDelegate?.id)
                statement.setInt(16, workItem.collaboration.assignmentPath.size)
                statement.setLong(17, workItem.createdAt)
                statement.setLong(18, workItem.updatedAt)
                statement.executeUpdate()
            }
        }
    }

    private fun insertCandidates(
        connection: Connection,
        state: WorkflowInstanceState,
        workItemId: String,
        snapshot: WorkflowHumanRuleSnapshot,
    ) {
        snapshot.candidates.forEachIndexed { ordinal, candidate ->
            val id = stableRowId(state.tenantId, workItemId, snapshot.ruleIndex.toString(), ordinal.toString())
            val existing = connection.prepareStatement(
                "SELECT principal_type, principal_id, activation_receipt_digest, " +
                    "organization_authority, organization_snapshot_revision, resolution_request_digest, " +
                    "organization_provider_revision, organization_snapshot_digest, " +
                    "organization_snapshot_receipt_digest, organization_confirmation_revision, " +
                    "organization_confirmation_snapshot_digest, organization_confirmation_request_digest, " +
                    "organization_confirmation_receipt_digest " +
                    "FROM fw_wf_human_candidate " +
                    "WHERE tenant_id = ? AND id = ?",
            ).use { statement ->
                statement.setString(1, state.tenantId)
                statement.setString(2, id)
                statement.executeQuery().use { result ->
                    if (result.next()) {
                        CandidateEvidence(
                            result.getString(1),
                            result.requiredIdentifier(2),
                            result.getString(3),
                            result.nullableIdentifier(4),
                            result.nullableIdentifier(5),
                            result.nullableIdentifier(6),
                            result.nullableIdentifier(7),
                            result.nullableIdentifier(8),
                            result.nullableIdentifier(9),
                            result.nullableIdentifier(10),
                            result.nullableIdentifier(11),
                            result.nullableIdentifier(12),
                            result.nullableIdentifier(13),
                        )
                    } else null
                }
            }
            if (existing == null) {
                connection.prepareStatement(INSERT_CANDIDATE_SQL).use { statement ->
                    statement.setString(1, id)
                    statement.setString(2, state.tenantId)
                    statement.setString(3, state.instanceId)
                    statement.setString(4, workItemId)
                    statement.setInt(5, snapshot.ruleIndex)
                    statement.setInt(6, ordinal)
                    statement.setString(7, candidate.type)
                    statement.setString(8, candidate.id)
                    statement.setString(9, snapshot.selectorDigest)
                    statement.setString(10, snapshot.resolutionDigest)
                    statement.setString(11, snapshot.activationReceiptDigest)
                    setNullableString(statement, 12, snapshot.organizationAuthority)
                    setNullableString(statement, 13, snapshot.organizationSnapshotRevision)
                    setNullableString(statement, 14, snapshot.resolutionRequestDigest)
                    setNullableString(statement, 15, snapshot.organizationProviderRevision)
                    setNullableString(statement, 16, snapshot.organizationSnapshotDigest)
                    setNullableString(statement, 17, snapshot.organizationSnapshotReceiptDigest)
                    setNullableString(statement, 18, snapshot.organizationConfirmationRevision)
                    setNullableString(statement, 19, snapshot.organizationConfirmationSnapshotDigest)
                    setNullableString(statement, 20, snapshot.organizationConfirmationRequestDigest)
                    setNullableString(statement, 21, snapshot.organizationConfirmationReceiptDigest)
                    statement.setLong(22, snapshot.activatedAt)
                    statement.setLong(23, snapshot.activatedAt)
                    statement.executeUpdate()
                }
            } else {
                check(existing == CandidateEvidence(
                    candidate.type,
                    candidate.id,
                    snapshot.activationReceiptDigest,
                    snapshot.organizationAuthority,
                    snapshot.organizationSnapshotRevision,
                    snapshot.resolutionRequestDigest,
                    snapshot.organizationProviderRevision,
                    snapshot.organizationSnapshotDigest,
                    snapshot.organizationSnapshotReceiptDigest,
                    snapshot.organizationConfirmationRevision,
                    snapshot.organizationConfirmationSnapshotDigest,
                    snapshot.organizationConfirmationRequestDigest,
                    snapshot.organizationConfirmationReceiptDigest,
                )) {
                    "Workflow candidate activation evidence is immutable."
                }
            }
        }
    }

    private fun insertDecision(
        connection: Connection,
        state: WorkflowInstanceState,
        workItemId: String,
        decision: WorkflowHumanDecision,
    ) {
        val existingDigest = connection.prepareStatement(
            "SELECT decision_digest FROM fw_wf_human_decision WHERE tenant_id = ? AND id = ?",
        ).use { statement ->
            statement.setString(1, state.tenantId)
            statement.setString(2, decision.decisionId)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
        if (existingDigest == null) {
            connection.prepareStatement(INSERT_DECISION_SQL).use { statement ->
                statement.setString(1, decision.decisionId)
                statement.setString(2, state.tenantId)
                statement.setString(3, state.instanceId)
                statement.setString(4, workItemId)
                statement.setInt(5, decision.ruleIndex)
                statement.setString(6, decision.actor.type)
                statement.setString(7, decision.actor.id)
                statement.setString(8, decision.decision.code)
                statement.setString(9, decision.authorizationReceiptDigest)
                statement.setString(10, decision.contentDigest)
                statement.setLong(11, decision.decidedAt)
                statement.setLong(12, decision.decidedAt)
                statement.executeUpdate()
            }
        } else {
            check(existingDigest == decision.contentDigest) { "Workflow human decisions are immutable." }
        }
    }

    private fun insertCollaborationRecord(
        connection: Connection,
        state: WorkflowInstanceState,
        workItemId: String,
        record: ai.icen.fw.workflow.domain.WorkflowHumanCollaborationRecord,
    ) {
        val existingBinding = connection.prepareStatement(
            """SELECT instance_id, work_item_id, execution_nonce, record_digest
                FROM fw_wf_human_collaboration_event WHERE tenant_id = ? AND id = ?""",
        ).use { statement ->
            statement.setString(1, state.tenantId)
            statement.setString(2, record.recordId)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    listOf(
                        result.requiredIdentifier(1),
                        result.requiredIdentifier(2),
                        result.getString(3),
                        result.getString(4),
                    )
                } else {
                    null
                }
            }
        }
        if (existingBinding == null) {
            connection.prepareStatement(INSERT_COLLABORATION_SQL).use { statement ->
                statement.setString(1, record.recordId)
                statement.setString(2, state.tenantId)
                statement.setString(3, state.instanceId)
                statement.setString(4, workItemId)
                statement.setString(5, record.action.code)
                statement.setString(6, record.actor.type)
                statement.setString(7, record.actor.id)
                setNullableString(statement, 8, record.target?.type)
                setNullableString(statement, 9, record.target?.id)
                setNullableString(statement, 10, record.ownerBefore?.type)
                setNullableString(statement, 11, record.ownerBefore?.id)
                setNullableString(statement, 12, record.ownerAfter?.type)
                setNullableString(statement, 13, record.ownerAfter?.id)
                setNullableString(statement, 14, record.delegateBefore?.type)
                setNullableString(statement, 15, record.delegateBefore?.id)
                setNullableString(statement, 16, record.delegateAfter?.type)
                setNullableString(statement, 17, record.delegateAfter?.id)
                statement.setString(18, record.authorizationReceiptDigest)
                statement.setString(19, record.executionNonce)
                statement.setString(20, record.contentDigest)
                statement.setLong(21, record.occurredAt)
                statement.setLong(22, record.occurredAt)
                statement.setLong(23, record.occurredAt)
                statement.executeUpdate()
            }
        } else {
            check(existingBinding == listOf(
                state.instanceId,
                workItemId,
                record.executionNonce,
                record.contentDigest,
            )) { "Workflow human collaboration records are immutable and task-bound." }
        }
    }

    private fun loadEffectRow(
        connection: Connection,
        tenantId: String,
        effectId: String,
        forUpdate: Boolean,
    ): EffectRow? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        connection.prepareStatement(SELECT_EFFECT_SQL + lock).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, effectId)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                val intent = WorkflowEffectIntent.of(
                    result.requiredIdentifier("id"),
                    WorkflowEffectCode.of(result.getString("effect_code")),
                    result.requiredIdentifier("tenant_id"),
                    result.requiredIdentifier("instance_id"),
                    result.requiredIdentifier("definition_id"),
                    WorkflowDefinitionRef.of(
                        result.getString("definition_key"),
                        result.getString("definition_version"),
                        result.getString("definition_digest"),
                    ),
                    WorkflowSubjectSnapshot.of(
                        WorkflowSubjectRef.of(result.getString("subject_type"), result.requiredIdentifier("subject_id")),
                        result.getString("subject_revision"),
                        result.getString("subject_digest"),
                    ),
                    result.nullableIdentifier("token_id"),
                    result.nullableIdentifier("node_execution_id"),
                    result.nullableIdentifier("work_item_id"),
                    result.getString("node_id"),
                    nullableInt(result, "rule_index"),
                    result.getString("payload_digest"),
                    result.getLong("created_time"),
                )
                check(intent.requestDigest == result.getString("request_digest")) {
                    "Persisted workflow effect request digest is inconsistent."
                }
                val status = deliveryStatus(result.getString("delivery_status"))
                val lease = if (status == WorkflowEffectDeliveryStatus.LEASED) {
                    WorkflowEffectLease.of(
                        requireNotNull(result.nullableIdentifier("lease_id")),
                        requireNotNull(result.nullableIdentifier("worker_id")),
                        result.getLong("fencing_token"),
                        result.getLong("lease_acquired_time"),
                        result.getLong("lease_expires_time"),
                    )
                } else null
                val record = WorkflowEffectRecord.restore(
                    intent,
                    status,
                    result.getLong("record_version"),
                    result.getInt("attempt_count"),
                    nullableLong(result, "next_attempt_time"),
                    lease,
                    result.getString("execution_phase")?.let(::executionPhase),
                    result.getLong("checkpoint_sequence"),
                    result.getString("checkpoint_digest"),
                    result.getString("outcome_digest"),
                    result.getLong("updated_time"),
                )
                return EffectRow(record, nullableLong(result, "fencing_token"))
            }
        }
    }

    private fun claimEligible(record: WorkflowEffectRecord, now: Long): Boolean =
        record.status == WorkflowEffectDeliveryStatus.PENDING ||
            record.status == WorkflowEffectDeliveryStatus.RETRY_WAIT && requireNotNull(record.nextAttemptAt) <= now ||
            record.status == WorkflowEffectDeliveryStatus.LEASED && requireNotNull(record.lease).expiresAt <= now &&
            record.phase == WorkflowEffectExecutionPhase.PREPARED

    private fun requiresReconciliation(record: WorkflowEffectRecord, now: Long): Boolean =
        record.status == WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN ||
            record.status == WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT ||
            record.status == WorkflowEffectDeliveryStatus.LEASED && requireNotNull(record.lease).expiresAt <= now &&
            record.phase == WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED

    private fun effectLeaseMutationFailure(
        row: EffectRow,
        expectedVersion: Long,
        leaseId: String,
        fencingToken: Long,
        operationTime: Long,
    ): WorkflowEffectOperationCode? {
        if (row.record.version != expectedVersion) return WorkflowEffectOperationCode.VERSION_CONFLICT
        if (row.record.status != WorkflowEffectDeliveryStatus.LEASED) return WorkflowEffectOperationCode.NOT_ELIGIBLE
        val lease = requireNotNull(row.record.lease)
        if (lease.leaseId != leaseId || lease.fencingToken != fencingToken || lease.expiresAt <= operationTime) {
            return WorkflowEffectOperationCode.LEASE_MISMATCH
        }
        return null
    }

    private fun bindLeaseCas(statement: PreparedStatement, offset: Int, request: WorkflowEffectCheckpoint) {
        statement.setString(offset, request.tenantId)
        statement.setString(offset + 1, request.effectId)
        statement.setLong(offset + 2, request.expectedRecordVersion)
        statement.setString(offset + 3, request.leaseId)
        statement.setLong(offset + 4, request.fencingToken)
    }

    private fun bindLeaseCas(statement: PreparedStatement, offset: Int, request: WorkflowEffectOutcome) {
        statement.setString(offset, request.tenantId)
        statement.setString(offset + 1, request.effectId)
        statement.setLong(offset + 2, request.expectedRecordVersion)
        statement.setString(offset + 3, request.leaseId)
        statement.setLong(offset + 4, request.fencingToken)
    }

    private fun updateJob(
        connection: Connection,
        tenantId: String,
        effectId: String,
        status: String,
        now: Long,
        failureDigest: String?,
    ) {
        connection.prepareStatement(
            "UPDATE fw_wf_job SET job_status = ?, failure_digest = ?, updated_time = ? " +
                "WHERE tenant_id = ? AND effect_id = ?",
        ).use { statement ->
            statement.setString(1, status)
            setNullableString(statement, 2, failureDigest)
            statement.setLong(3, now)
            statement.setString(4, tenantId)
            statement.setString(5, effectId)
            check(statement.executeUpdate() == 1) { "Workflow effect job projection is missing." }
        }
    }

    private fun releaseJob(
        connection: Connection,
        tenantId: String,
        effectId: String,
        status: String,
        now: Long,
        availableAt: Long?,
        failureDigest: String?,
    ) {
        connection.prepareStatement(
            "UPDATE fw_wf_job SET job_status = ?, available_time = COALESCE(?, available_time), " +
                "failure_digest = ?, record_version = record_version + 1, lease_id = NULL, worker_id = NULL, " +
                "lease_acquired_time = NULL, lease_expires_time = NULL, execution_mode = NULL, " +
                "claim_request_digest = NULL, updated_time = ? WHERE tenant_id = ? AND effect_id = ?",
        ).use { statement ->
            statement.setString(1, status)
            setNullableLong(statement, 2, availableAt)
            setNullableString(statement, 3, failureDigest)
            statement.setLong(4, now)
            statement.setString(5, tenantId)
            statement.setString(6, effectId)
            check(statement.executeUpdate() == 1) { "Workflow effect job projection is missing." }
        }
    }

    private fun jobLeaseMatches(
        connection: Connection,
        tenantId: String,
        effectId: String,
        leaseId: String,
        fencingToken: Long,
        at: Long,
    ): Boolean = connection.prepareStatement(
        "SELECT lease_id, fencing_token, lease_expires_time FROM fw_wf_job " +
            "WHERE tenant_id = ? AND effect_id = ? FOR UPDATE",
    ).use { statement ->
        statement.setString(1, tenantId)
        statement.setString(2, effectId)
        statement.executeQuery().use { result ->
            result.next() && result.requiredIdentifier(1) == leaseId && result.getLong(2) == fencingToken &&
                result.getLong(3).let { expiry -> !result.wasNull() && expiry > at }
        }
    }

    private fun insertIncident(
        connection: Connection,
        intent: WorkflowEffectIntent,
        request: WorkflowEffectReconciliationIncident,
    ) {
        connection.prepareStatement(INSERT_INCIDENT_SQL).use { statement ->
            statement.setString(1, request.incidentId)
            statement.setString(2, request.tenantId)
            statement.setString(3, intent.instanceId)
            statement.setString(4, request.effectId)
            statement.setString(5, "effect-outcome-unknown")
            statement.setString(6, "open")
            statement.setString(7, request.evidenceDigest)
            statement.setLong(8, request.raisedAt)
            statement.setLong(9, request.raisedAt)
            statement.setLong(10, request.raisedAt)
            statement.executeUpdate()
        }
    }

    private fun loadIncidentRow(
        connection: Connection,
        tenantId: String,
        incidentId: String,
        forUpdate: Boolean,
    ): IncidentRow? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(SELECT_INCIDENT_SQL + lock).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, incidentId)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    null
                } else {
                    IncidentRow(
                        incidentId = result.requiredIdentifier("id"),
                        tenantId = result.requiredIdentifier("tenant_id"),
                        instanceId = result.requiredIdentifier("instance_id"),
                        effectId = requireNotNull(result.nullableIdentifier("effect_id")) {
                            "Workflow effect incident is missing its effect binding."
                        },
                        incidentCode = result.getString("incident_code"),
                        status = WorkflowIncidentStatus.of(result.getString("incident_status")),
                        evidenceDigest = result.getString("evidence_digest"),
                        repairDigest = result.getString("repair_digest"),
                        occurredAt = result.getLong("occurred_time"),
                        resolvedAt = nullableLong(result, "resolved_time"),
                    ).also { check(!result.next()) { "Workflow incident identity is not unique." } }
                }
            }
        }
    }

    private fun loadIncidentStoredResult(
        connection: Connection,
        tenantId: String,
        effectId: String,
        forUpdate: Boolean,
    ): WorkflowEffectJobStoredResult? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(SELECT_INCIDENT_RESULT_SQL + lock).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, effectId)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    null
                } else {
                    WorkflowEffectJobStoredResult.of(
                        incidentOutcome(result.getString("outcome_code")),
                        result.getString("result_type"),
                        result.requiredIdentifier("result_digest"),
                        result.getBytes("result_payload"),
                        nullableLong(result, "retry_time"),
                        result.getLong("completed_time"),
                    ).also { check(!result.next()) { "Workflow effect result identity is not unique." } }
                }
            }
        }
    }

    private fun insertIncidentStoredResult(
        connection: Connection,
        incident: IncidentRow,
        request: WorkflowEffectIncidentResolution,
    ) {
        connection.prepareStatement(INSERT_INCIDENT_RESULT_SQL).use { statement ->
            statement.setString(1, stableRowId("workflow-effect-result", request.effectId))
            statement.setString(2, request.tenantId)
            statement.setString(3, incident.instanceId)
            statement.setString(4, request.effectId)
            statement.setString(5, request.result.resultType)
            statement.setString(6, request.result.outcome.code)
            statement.setString(7, request.result.resultDigest)
            statement.setBytes(8, request.result.bytes())
            setNullableLong(statement, 9, request.result.retryAt)
            statement.setLong(10, request.result.completedAt)
            statement.setLong(11, 1L)
            statement.setLong(12, request.resolvedAt)
            statement.setLong(13, request.resolvedAt)
            check(statement.executeUpdate() == 1) { "Workflow incident result insert did not affect one row." }
        }
    }

    private fun outcomeStatus(outcome: WorkflowEffectObservedOutcome): WorkflowEffectDeliveryStatus = when (outcome) {
        WorkflowEffectObservedOutcome.SUCCEEDED -> WorkflowEffectDeliveryStatus.SUCCEEDED
        WorkflowEffectObservedOutcome.RETRYABLE_FAILURE -> WorkflowEffectDeliveryStatus.RETRYABLE_FAILURE
        WorkflowEffectObservedOutcome.TERMINAL_FAILURE -> WorkflowEffectDeliveryStatus.TERMINAL_FAILURE
        WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN -> WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN
        else -> throw IllegalArgumentException("Unknown workflow effect outcome is unsupported.")
    }

    private fun incidentOutcome(code: String): WorkflowEffectObservedOutcome = when (code) {
        WorkflowEffectObservedOutcome.SUCCEEDED.code -> WorkflowEffectObservedOutcome.SUCCEEDED
        WorkflowEffectObservedOutcome.RETRYABLE_FAILURE.code -> WorkflowEffectObservedOutcome.RETRYABLE_FAILURE
        WorkflowEffectObservedOutcome.TERMINAL_FAILURE.code -> WorkflowEffectObservedOutcome.TERMINAL_FAILURE
        WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN.code -> WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN
        else -> throw IllegalStateException("Persisted workflow incident outcome '$code' is unsupported.")
    }

    private fun deliveryStatus(code: String): WorkflowEffectDeliveryStatus = when (code) {
        WorkflowEffectDeliveryStatus.PENDING.code -> WorkflowEffectDeliveryStatus.PENDING
        WorkflowEffectDeliveryStatus.LEASED.code -> WorkflowEffectDeliveryStatus.LEASED
        WorkflowEffectDeliveryStatus.RETRYABLE_FAILURE.code -> WorkflowEffectDeliveryStatus.RETRYABLE_FAILURE
        WorkflowEffectDeliveryStatus.RETRY_WAIT.code -> WorkflowEffectDeliveryStatus.RETRY_WAIT
        WorkflowEffectDeliveryStatus.SUCCEEDED.code -> WorkflowEffectDeliveryStatus.SUCCEEDED
        WorkflowEffectDeliveryStatus.TERMINAL_FAILURE.code -> WorkflowEffectDeliveryStatus.TERMINAL_FAILURE
        WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN.code -> WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN
        WorkflowEffectDeliveryStatus.DOMAIN_APPLIED.code -> WorkflowEffectDeliveryStatus.DOMAIN_APPLIED
        WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT.code -> WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT
        else -> throw IllegalStateException("Persisted workflow effect delivery status '$code' is unsupported.")
    }

    private fun executionPhase(code: String): WorkflowEffectExecutionPhase = when (code) {
        WorkflowEffectExecutionPhase.PREPARED.code -> WorkflowEffectExecutionPhase.PREPARED
        WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED.code -> WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED
        else -> throw IllegalStateException("Persisted workflow effect execution phase '$code' is unsupported.")
    }

    private fun resultCode(code: String): WorkflowResultCode = when (code) {
        WorkflowResultCode.APPLIED.code -> WorkflowResultCode.APPLIED
        WorkflowResultCode.BUDGET_EXHAUSTED.code -> WorkflowResultCode.BUDGET_EXHAUSTED
        WorkflowResultCode.INCIDENT.code -> WorkflowResultCode.INCIDENT
        else -> throw IllegalStateException("Persisted durable workflow result '$code' is unsupported.")
    }

    private fun applied(record: WorkflowEffectRecord) = WorkflowEffectOperationResult.applied(record)
    private fun failed(code: WorkflowEffectOperationCode) = WorkflowEffectOperationResult.failed(code)
    private fun incidentFailed(code: WorkflowIncidentOperationCode) = WorkflowIncidentOperationResult.failed(code)
    private fun conflict(code: WorkflowRuntimeCommitCode) = WorkflowRuntimeCommitResult.conflict(code)

    private fun stableRowId(vararg components: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        components.forEach { component ->
            val bytes = component.toByteArray(Charsets.UTF_8)
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

    private fun jobId(effectId: String): String = stableRowId("workflow-effect-job", effectId)

    private data class EffectRow(
        val record: WorkflowEffectRecord,
        val lastFencingToken: Long?,
    )

    private data class IncidentRow(
        val incidentId: String,
        val tenantId: String,
        val instanceId: String,
        val effectId: String,
        val incidentCode: String,
        val status: WorkflowIncidentStatus,
        val evidenceDigest: String,
        val repairDigest: String?,
        val occurredAt: Long,
        val resolvedAt: Long?,
    ) {
        fun snapshot(effect: WorkflowEffectRecord): WorkflowEffectIncidentSnapshot =
            WorkflowEffectIncidentSnapshot.restore(
                incidentId,
                tenantId,
                instanceId,
                incidentCode,
                status,
                evidenceDigest,
                repairDigest,
                occurredAt,
                resolvedAt,
                effect,
            )
    }

    private data class CandidateEvidence(
        val principalType: String,
        val principalId: String,
        val activationReceiptDigest: String,
        val organizationAuthority: String?,
        val organizationSnapshotRevision: String?,
        val resolutionRequestDigest: String?,
        val organizationProviderRevision: String?,
        val organizationSnapshotDigest: String?,
        val organizationSnapshotReceiptDigest: String?,
        val organizationConfirmationRevision: String?,
        val organizationConfirmationSnapshotDigest: String?,
        val organizationConfirmationRequestDigest: String?,
        val organizationConfirmationReceiptDigest: String?,
    )

    private companion object {
        const val UPDATE_INSTANCE_SQL = """
            UPDATE fw_wf_instance
            SET status = ?, instance_version = ?, state_digest = ?, state_payload = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND instance_version = ? AND state_digest = ?
        """
        const val INSERT_EVENT_SQL = """
            INSERT INTO fw_wf_event(
                id, tenant_id, instance_id, definition_id, definition_key, definition_version,
                definition_digest, event_code, token_id, node_execution_id, work_item_id, node_id,
                subject_type, subject_id, subject_revision, subject_digest, payload_digest,
                instance_version, event_digest, occurred_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val INSERT_EFFECT_SQL = """
            INSERT INTO fw_wf_effect(
                id, tenant_id, instance_id, definition_id, definition_key, definition_version,
                definition_digest, subject_type, subject_id, subject_revision, subject_digest,
                token_id, node_execution_id, work_item_id, node_id, rule_index, effect_code,
                payload_digest, request_digest, delivery_status, record_version, attempt_count,
                checkpoint_sequence, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val INSERT_JOB_SQL = """
            INSERT INTO fw_wf_job(
                id, tenant_id, instance_id, effect_id, job_type, job_status,
                available_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val INSERT_IDEMPOTENCY_SQL = """
            INSERT INTO fw_wf_idempotency(
                id, tenant_id, instance_id, idempotency_key, logical_request_digest,
                command_code, domain_command_digest, result_version, effect_count,
                domain_result_code, committed_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val INSERT_TOKEN_SQL = """
            INSERT INTO fw_wf_token(
                id, tenant_id, instance_id, node_id, token_status, token_revision,
                waiting_execution_id, content_digest, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val INSERT_EXECUTION_SQL = """
            INSERT INTO fw_wf_node_execution(
                id, tenant_id, instance_id, token_id, node_id, execution_status,
                execution_revision, started_time, completed_time, pending_effect_id,
                pending_effect_code, effect_request_digest, content_digest, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val UPDATE_HUMAN_TASK_SQL = """
            UPDATE fw_wf_human_task
            SET task_status = ?, active_rule_index = ?, task_revision = ?, content_digest = ?,
                claimed_by_type = ?, claimed_by_id = ?, active_delegate_type = ?,
                active_delegate_id = ?, assignment_depth = ?, updated_time = ?
            WHERE tenant_id = ? AND instance_id = ? AND id = ?
        """
        const val INSERT_HUMAN_TASK_SQL = """
            INSERT INTO fw_wf_human_task(
                id, tenant_id, instance_id, node_execution_id, token_id, node_id,
                policy_digest, task_status, active_rule_index, task_revision,
                content_digest, claimed_by_type, claimed_by_id, active_delegate_type,
                active_delegate_id, assignment_depth, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val INSERT_CANDIDATE_SQL = """
            INSERT INTO fw_wf_human_candidate(
                id, tenant_id, instance_id, work_item_id, rule_index, candidate_ordinal,
                principal_type, principal_id, selector_digest, resolution_digest,
                activation_receipt_digest, organization_authority,
                organization_snapshot_revision, resolution_request_digest,
                organization_provider_revision, organization_snapshot_digest,
                organization_snapshot_receipt_digest, organization_confirmation_revision,
                organization_confirmation_snapshot_digest, organization_confirmation_request_digest,
                organization_confirmation_receipt_digest,
                created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val INSERT_DECISION_SQL = """
            INSERT INTO fw_wf_human_decision(
                id, tenant_id, instance_id, work_item_id, rule_index, actor_type,
                actor_id, decision_code, authorization_receipt_digest, decision_digest,
                occurred_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val INSERT_COLLABORATION_SQL = """
            INSERT INTO fw_wf_human_collaboration_event(
                id, tenant_id, instance_id, work_item_id, action_code, actor_type, actor_id,
                target_type, target_id, owner_before_type, owner_before_id, owner_after_type,
                owner_after_id, delegate_before_type, delegate_before_id, delegate_after_type,
                delegate_after_id, authorization_receipt_digest, execution_nonce, record_digest,
                occurred_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val SELECT_EFFECT_SQL = """
            SELECT * FROM fw_wf_effect WHERE tenant_id = ? AND id = ?
        """
        const val CLAIM_EFFECT_SQL = """
            UPDATE fw_wf_effect
            SET delivery_status = ?, record_version = record_version + 1, attempt_count = ?,
                next_attempt_time = NULL, lease_id = ?, worker_id = ?, fencing_token = ?,
                lease_acquired_time = ?, lease_expires_time = ?, execution_phase = ?,
                checkpoint_sequence = 0, checkpoint_digest = NULL, outcome_digest = NULL,
                retry_reason_digest = NULL, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ?
        """
        const val CHECKPOINT_EFFECT_SQL = """
            UPDATE fw_wf_effect
            SET execution_phase = ?, checkpoint_sequence = ?, checkpoint_digest = ?,
                record_version = record_version + 1, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ? AND lease_id = ? AND fencing_token = ?
        """
        const val RECORD_EFFECT_OUTCOME_SQL = """
            UPDATE fw_wf_effect
            SET delivery_status = ?, outcome_digest = ?, record_version = record_version + 1,
                lease_id = NULL, worker_id = NULL, lease_acquired_time = NULL,
                lease_expires_time = NULL, execution_phase = NULL, next_attempt_time = NULL,
                updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ? AND lease_id = ? AND fencing_token = ?
        """
        const val SCHEDULE_EFFECT_RETRY_SQL = """
            UPDATE fw_wf_effect
            SET delivery_status = ?, next_attempt_time = ?, retry_reason_digest = ?,
                record_version = record_version + 1, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ?
        """
        const val RAISE_RECONCILIATION_SQL = """
            UPDATE fw_wf_effect
            SET delivery_status = ?, reconciliation_evidence_digest = ?,
                record_version = record_version + 1, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ?
        """
        const val ACKNOWLEDGE_EFFECT_SQL = """
            UPDATE fw_wf_effect
            SET delivery_status = ?, acknowledgement_kind = ?, acknowledgement_receipt_digest = ?,
                record_version = record_version + 1, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ?
        """
        const val INSERT_INCIDENT_SQL = """
            INSERT INTO fw_wf_incident(
                id, tenant_id, instance_id, effect_id, incident_code, incident_status,
                evidence_digest, occurred_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val SELECT_INCIDENT_SQL = """
            SELECT id, tenant_id, instance_id, effect_id, incident_code, incident_status,
                   evidence_digest, repair_digest, occurred_time, resolved_time
            FROM fw_wf_incident WHERE tenant_id = ? AND id = ?
        """
        const val SELECT_INCIDENT_RESULT_SQL = """
            SELECT result_type, outcome_code, result_digest, result_payload, retry_time, completed_time
            FROM fw_wf_effect_result WHERE tenant_id = ? AND effect_id = ?
        """
        const val INSERT_INCIDENT_RESULT_SQL = """
            INSERT INTO fw_wf_effect_result(
                id, tenant_id, instance_id, effect_id, result_type, outcome_code, result_digest,
                result_payload, retry_time, completed_time, result_version, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val RESOLVE_INCIDENT_EFFECT_SQL = """
            UPDATE fw_wf_effect
            SET delivery_status = ?, outcome_digest = ?, next_attempt_time = ?, retry_reason_digest = ?,
                record_version = record_version + 1, lease_id = NULL, worker_id = NULL,
                lease_acquired_time = NULL, lease_expires_time = NULL, execution_phase = NULL,
                updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ? AND delivery_status = ?
        """
        const val RESOLVE_INCIDENT_SQL = """
            UPDATE fw_wf_incident
            SET incident_status = ?, repair_digest = ?, resolved_time = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND incident_status = ?
        """
    }
}

private fun setNullableString(statement: PreparedStatement, index: Int, value: String?) {
    if (value == null) statement.setNull(index, Types.VARCHAR) else statement.setString(index, value)
}

private fun setNullableLong(statement: PreparedStatement, index: Int, value: Long?) {
    if (value == null) statement.setNull(index, Types.BIGINT) else statement.setLong(index, value)
}

private fun setNullableInt(statement: PreparedStatement, index: Int, value: Int?) {
    if (value == null) statement.setNull(index, Types.INTEGER) else statement.setInt(index, value)
}

private fun nullableLong(result: ResultSet, column: String): Long? =
    result.getLong(column).let { value -> if (result.wasNull()) null else value }

private fun nullableInt(result: ResultSet, column: String): Int? =
    result.getInt(column).let { value -> if (result.wasNull()) null else value }

private fun ResultSet.requiredIdentifier(column: String): String =
    requireNotNull(getBytes(column)) { "Persisted workflow binary value is missing: $column." }
        .toString(StandardCharsets.UTF_8)

private fun ResultSet.requiredIdentifier(index: Int): String =
    requireNotNull(getBytes(index)) { "Persisted workflow binary value is missing: $index." }
        .toString(StandardCharsets.UTF_8)

private fun ResultSet.nullableIdentifier(column: String): String? =
    getBytes(column)?.toString(StandardCharsets.UTF_8)

private fun ResultSet.nullableIdentifier(index: Int): String? =
    getBytes(index)?.toString(StandardCharsets.UTF_8)
