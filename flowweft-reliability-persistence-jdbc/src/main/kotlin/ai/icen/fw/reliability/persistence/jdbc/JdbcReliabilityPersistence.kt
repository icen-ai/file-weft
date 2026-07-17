package ai.icen.fw.reliability.persistence.jdbc

import ai.icen.fw.reliability.runtime.ReliabilityOutboxClaimCode
import ai.icen.fw.reliability.runtime.ReliabilityOutboxClaimResult
import ai.icen.fw.reliability.runtime.ReliabilityOutboxRecord
import ai.icen.fw.reliability.runtime.ReliabilityOutboxRepository
import ai.icen.fw.reliability.runtime.ReliabilityRun
import ai.icen.fw.reliability.runtime.ReliabilityRunRepository
import ai.icen.fw.reliability.runtime.ReliabilitySloSchedule
import ai.icen.fw.reliability.runtime.ReliabilitySloScheduleRepository
import ai.icen.fw.reliability.runtime.ReliabilityStoreCode
import ai.icen.fw.reliability.runtime.ReliabilityStoreResult
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

internal class ReliabilityJdbcStore(
    dataSource: DataSource,
    configuredDialect: ReliabilityJdbcDialect? = null,
) {
    private val transactions = ReliabilityJdbcTransactions(dataSource, configuredDialect)

    fun createOrLoad(run: ReliabilityRun, outbox: ReliabilityOutboxRecord): ReliabilityStoreResult {
        require(run.version == 0L && run.lease == null && !run.isTerminal()) {
            "Reliability JDBC can create only a fresh unclaimed run."
        }
        requireOutboxForRun(outbox, run)
        return try {
            transactions.write { connection, dialect ->
                loadRunByIdempotency(connection, run.tenantId, run.intent.idempotencyDigest, true)?.let {
                    return@write ReliabilityStoreResult.of(ReliabilityStoreCode.REPLAY, it)
                }
                val memento = ReliabilityJdbcCanonicalCodec.encodeRun(run)
                try {
                    insertRun(connection, run, memento)
                } catch (failure: SQLException) {
                    if (!dialect.isUniqueViolation(failure)) throw failure
                    throw ReliabilityJdbcUniqueConflictException(failure)
                }
                insertOutbox(connection, outbox)
                ReliabilityStoreResult.of(ReliabilityStoreCode.STORED, run)
            }
        } catch (_: ReliabilityJdbcUniqueConflictException) {
            reconcileRunInsertConflict(run)
        } catch (_: ReliabilityJdbcCommitOutcomeUnknownException) {
            ReliabilityStoreResult.of(ReliabilityStoreCode.OUTCOME_UNKNOWN, null)
        }
    }

    fun loadRunPort(tenantId: String, runId: String): ReliabilityRun? =
        transactions.read { connection, _ -> loadRun(connection, tenantId, runId, false) }

    fun findByIdempotency(tenantId: String, idempotencyDigest: String): ReliabilityRun? =
        transactions.read { connection, _ ->
            loadRunByIdempotency(connection, tenantId, idempotencyDigest, false)
        }

    fun claim(
        tenantId: String,
        runId: String,
        expectedVersion: Long,
        ownerId: String,
        nowEpochMilli: Long,
        leaseUntilEpochMilli: Long,
    ): ReliabilityStoreResult {
        require(expectedVersion >= 0L && nowEpochMilli >= 0L && leaseUntilEpochMilli > nowEpochMilli) {
            "Reliability JDBC run claim arguments are invalid."
        }
        return try {
            transactions.write { connection, dialect ->
                val row = loadRunRow(connection, tenantId, runId, true)
                    ?: return@write ReliabilityStoreResult.of(ReliabilityStoreCode.NOT_FOUND, null)
                val current = row.run
                if (current.version != expectedVersion || current.isTerminal() ||
                    current.lease?.expiresAtEpochMilli?.let { it > nowEpochMilli } == true
                ) return@write ReliabilityStoreResult.of(ReliabilityStoreCode.CONFLICT, current)
                check(row.nextFencingToken in 1 until Long.MAX_VALUE) {
                    "Reliability run fencing token is exhausted."
                }
                val candidate = ReliabilityRun.claimed(
                    current, ownerId, nowEpochMilli, leaseUntilEpochMilli, row.nextFencingToken,
                )
                val memento = ReliabilityJdbcCanonicalCodec.encodeRun(candidate)
                val updated = connection.prepareStatement(
                    """
                    UPDATE fw_reliability_run SET
                        status = ?, state_version = ?, state_digest = ?, state_memento = ?,
                        state_memento_digest = ?, lease_owner_id = ?, lease_fencing_token = ?,
                        lease_expires_time = ?, next_fencing_token = ?, updated_time = ?
                    WHERE tenant_id = ? AND id = ? AND state_version = ? AND next_fencing_token = ?
                    """.trimIndent(),
                ).use { statement ->
                    var index = 1
                    statement.setString(index++, candidate.status.name)
                    statement.setLong(index++, candidate.version)
                    statement.setString(index++, candidate.stateDigest)
                    statement.setBytes(index++, memento)
                    statement.setString(index++, ReliabilityJdbcDigests.bytes(memento))
                    statement.setString(index++, requireNotNull(candidate.lease).ownerId)
                    statement.setLong(index++, candidate.lease!!.fencingToken)
                    statement.setLong(index++, candidate.lease!!.expiresAtEpochMilli)
                    statement.setLong(index++, row.nextFencingToken + 1L)
                    statement.setLong(index++, candidate.updatedAtEpochMilli)
                    statement.setString(index++, tenantId)
                    statement.setString(index++, runId)
                    statement.setLong(index++, expectedVersion)
                    statement.setLong(index, row.nextFencingToken)
                    statement.executeUpdate()
                }
                if (updated != 1) ReliabilityStoreResult.of(ReliabilityStoreCode.CONFLICT, current)
                else ReliabilityStoreResult.of(ReliabilityStoreCode.STORED, candidate)
            }
        } catch (_: ReliabilityJdbcCommitOutcomeUnknownException) {
            ReliabilityStoreResult.of(ReliabilityStoreCode.OUTCOME_UNKNOWN, null)
        }
    }

    fun compareAndSet(
        tenantId: String,
        runId: String,
        expectedVersion: Long,
        expectedFencingToken: Long,
        candidate: ReliabilityRun,
        outbox: ReliabilityOutboxRecord,
    ): ReliabilityStoreResult {
        require(candidate.tenantId == tenantId && candidate.runId == runId &&
            candidate.version == expectedVersion + 1L && expectedFencingToken > 0L &&
            candidate.lease?.fencingToken == expectedFencingToken
        ) { "Reliability JDBC run candidate is not bound to the exact version and fence." }
        requireOutboxForRun(outbox, candidate)
        return try {
            transactions.write { connection, _ ->
                val row = loadRunRow(connection, tenantId, runId, true)
                    ?: return@write ReliabilityStoreResult.of(ReliabilityStoreCode.NOT_FOUND, null)
                val current = row.run
                if (current.version != expectedVersion || current.lease?.fencingToken != expectedFencingToken) {
                    return@write ReliabilityStoreResult.of(ReliabilityStoreCode.CONFLICT, current)
                }
                if (!hasSameRunBinding(current, candidate)) {
                    return@write ReliabilityStoreResult.of(ReliabilityStoreCode.CONFLICT, current)
                }
                val memento = ReliabilityJdbcCanonicalCodec.encodeRun(candidate)
                val updated = updateRun(connection, current, candidate, memento, expectedFencingToken)
                if (updated != 1) return@write ReliabilityStoreResult.of(ReliabilityStoreCode.CONFLICT, current)
                persistProviderEvidence(connection, current, candidate)
                insertOutbox(connection, outbox)
                ReliabilityStoreResult.of(ReliabilityStoreCode.STORED, candidate)
            }
        } catch (_: ReliabilityJdbcCommitOutcomeUnknownException) {
            ReliabilityStoreResult.of(ReliabilityStoreCode.OUTCOME_UNKNOWN, null)
        }
    }

    /** Creates a host-managed SLO schedule exactly once; schedule updates use the runtime CAS port. */
    fun createSchedule(schedule: ReliabilitySloSchedule): ReliabilityStoreCode {
        require(schedule.version == 0L && schedule.lease == null && schedule.lastRecord == null) {
            "Reliability JDBC can create only a fresh SLO schedule."
        }
        return try {
            transactions.write { connection, dialect ->
                loadSchedule(connection, schedule.tenantId, schedule.scheduleId, true)?.let {
                    return@write if (it.stateDigest == schedule.stateDigest) {
                        ReliabilityStoreCode.REPLAY
                    } else {
                        ReliabilityStoreCode.CONFLICT
                    }
                }
                val memento = ReliabilityJdbcCanonicalCodec.encodeSchedule(schedule)
                try {
                    insertSchedule(connection, schedule, memento)
                } catch (failure: SQLException) {
                    if (!dialect.isUniqueViolation(failure)) throw failure
                    throw ReliabilityJdbcUniqueConflictException(failure)
                }
                ReliabilityStoreCode.STORED
            }
        } catch (_: ReliabilityJdbcUniqueConflictException) {
            reconcileScheduleInsertConflict(schedule)
        } catch (_: ReliabilityJdbcCommitOutcomeUnknownException) {
            ReliabilityStoreCode.OUTCOME_UNKNOWN
        }
    }

    fun loadSchedulePort(tenantId: String, scheduleId: String): ReliabilitySloSchedule? =
        transactions.read { connection, _ -> loadSchedule(connection, tenantId, scheduleId, false) }

    fun claimDue(
        tenantId: String,
        scheduleId: String,
        expectedVersion: Long,
        ownerId: String,
        nowEpochMilli: Long,
        leaseUntilEpochMilli: Long,
    ): ReliabilitySloSchedule? = transactions.write { connection, _ ->
        val row = loadScheduleRow(connection, tenantId, scheduleId, true) ?: return@write null
        val current = row.schedule
        if (current.version != expectedVersion || current.nextEvaluationAtEpochMilli > nowEpochMilli ||
            current.lease?.expiresAtEpochMilli?.let { it > nowEpochMilli } == true ||
            row.nextFencingToken !in 1 until Long.MAX_VALUE
        ) return@write null
        val candidate = ReliabilitySloSchedule.claimed(
            current, ownerId, nowEpochMilli, leaseUntilEpochMilli, row.nextFencingToken,
        )
        val memento = ReliabilityJdbcCanonicalCodec.encodeSchedule(candidate)
        val updated = connection.prepareStatement(
            """
            UPDATE fw_reliability_slo_schedule SET
                state_version = ?, state_digest = ?, state_memento = ?, state_memento_digest = ?,
                lease_owner_id = ?, lease_fencing_token = ?, lease_expires_time = ?,
                next_fencing_token = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND state_version = ? AND next_fencing_token = ?
            """.trimIndent(),
        ).use { statement ->
            var index = 1
            statement.setLong(index++, candidate.version)
            statement.setString(index++, candidate.stateDigest)
            statement.setBytes(index++, memento)
            statement.setString(index++, ReliabilityJdbcDigests.bytes(memento))
            statement.setString(index++, requireNotNull(candidate.lease).ownerId)
            statement.setLong(index++, candidate.lease!!.fencingToken)
            statement.setLong(index++, candidate.lease!!.expiresAtEpochMilli)
            statement.setLong(index++, row.nextFencingToken + 1L)
            statement.setLong(index++, candidate.updatedAtEpochMilli)
            statement.setString(index++, tenantId)
            statement.setString(index++, scheduleId)
            statement.setLong(index++, expectedVersion)
            statement.setLong(index, row.nextFencingToken)
            statement.executeUpdate()
        }
        candidate.takeIf { updated == 1 }
    }

    fun compareAndSet(
        tenantId: String,
        scheduleId: String,
        expectedVersion: Long,
        expectedFencingToken: Long,
        candidate: ReliabilitySloSchedule,
        outbox: ReliabilityOutboxRecord,
    ): ReliabilityStoreCode {
        require(candidate.tenantId == tenantId && candidate.scheduleId == scheduleId &&
            candidate.version == expectedVersion + 1L && expectedFencingToken > 0L &&
            candidate.lease?.fencingToken == expectedFencingToken
        ) { "Reliability JDBC SLO candidate is not bound to the exact version and fence." }
        requireOutboxForSchedule(outbox, candidate)
        return try {
            transactions.write { connection, _ ->
                val current = loadSchedule(connection, tenantId, scheduleId, true)
                    ?: return@write ReliabilityStoreCode.NOT_FOUND
                if (current.version != expectedVersion || current.lease?.fencingToken != expectedFencingToken) {
                    return@write ReliabilityStoreCode.CONFLICT
                }
                if (!hasSameScheduleBinding(current, candidate)) return@write ReliabilityStoreCode.CONFLICT
                val memento = ReliabilityJdbcCanonicalCodec.encodeSchedule(candidate)
                val updated = connection.prepareStatement(
                    """
                    UPDATE fw_reliability_slo_schedule SET
                        next_evaluation_time = ?, state_version = ?, state_digest = ?, state_memento = ?,
                        state_memento_digest = ?, last_evaluation_digest = ?, last_alert_digest = ?,
                        updated_time = ?
                    WHERE tenant_id = ? AND id = ? AND state_version = ? AND lease_fencing_token = ?
                    """.trimIndent(),
                ).use { statement ->
                    var index = 1
                    statement.setLong(index++, candidate.nextEvaluationAtEpochMilli)
                    statement.setLong(index++, candidate.version)
                    statement.setString(index++, candidate.stateDigest)
                    statement.setBytes(index++, memento)
                    statement.setString(index++, ReliabilityJdbcDigests.bytes(memento))
                    statement.setString(index++, candidate.lastRecord?.evaluation?.evaluationDigest)
                    statement.setString(index++, candidate.lastRecord?.alert?.alertDigest)
                    statement.setLong(index++, candidate.updatedAtEpochMilli)
                    statement.setString(index++, tenantId)
                    statement.setString(index++, scheduleId)
                    statement.setLong(index++, expectedVersion)
                    statement.setLong(index, expectedFencingToken)
                    statement.executeUpdate()
                }
                if (updated != 1) return@write ReliabilityStoreCode.CONFLICT
                persistSloEvaluation(connection, candidate)
                insertOutbox(connection, outbox)
                ReliabilityStoreCode.STORED
            }
        } catch (_: ReliabilityJdbcCommitOutcomeUnknownException) {
            ReliabilityStoreCode.OUTCOME_UNKNOWN
        }
    }

    fun claimNext(
        ownerId: String,
        nowEpochMilli: Long,
        leaseUntilEpochMilli: Long,
    ): ReliabilityOutboxClaimResult {
        require(nowEpochMilli >= 0L && leaseUntilEpochMilli > nowEpochMilli) {
            "Reliability JDBC outbox claim window is invalid."
        }
        return try {
            transactions.write { connection, dialect ->
                val row = connection.prepareStatement(
                    """
                    SELECT id, tenant_id, event_type, aggregate_id, aggregate_state_digest,
                           aggregate_version, record_digest, next_fencing_token, created_time
                    FROM fw_reliability_outbox
                    WHERE status = ? OR (status = ? AND lease_expires_time <= ?)
                    ORDER BY created_time, id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, OUTBOX_READY)
                    statement.setString(2, OUTBOX_CLAIMED)
                    statement.setLong(3, nowEpochMilli)
                    statement.executeQuery().use { result -> if (result.next()) outboxRow(result) else null }
                } ?: return@write ReliabilityOutboxClaimResult.of(ReliabilityOutboxClaimCode.EMPTY)
                if (row.nextFencingToken !in 1 until Long.MAX_VALUE) {
                    return@write ReliabilityOutboxClaimResult.of(ReliabilityOutboxClaimCode.CONFLICT)
                }
                val updated = connection.prepareStatement(
                    """
                    UPDATE fw_reliability_outbox SET status = ?, lease_owner_id = ?,
                        lease_fencing_token = ?, lease_expires_time = ?, next_fencing_token = ?, updated_time = ?
                    WHERE tenant_id = ? AND id = ? AND next_fencing_token = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, OUTBOX_CLAIMED)
                    statement.setString(2, ownerId)
                    statement.setLong(3, row.nextFencingToken)
                    statement.setLong(4, leaseUntilEpochMilli)
                    statement.setLong(5, row.nextFencingToken + 1L)
                    statement.setLong(6, nowEpochMilli)
                    statement.setString(7, row.record.tenantId)
                    statement.setString(8, row.record.outboxId)
                    statement.setLong(9, row.nextFencingToken)
                    statement.executeUpdate()
                }
                if (updated != 1) ReliabilityOutboxClaimResult.of(ReliabilityOutboxClaimCode.CONFLICT)
                else ReliabilityOutboxClaimResult.of(
                    ReliabilityOutboxClaimCode.CLAIMED, row.record, row.nextFencingToken,
                )
            }
        } catch (_: ReliabilityJdbcCommitOutcomeUnknownException) {
            ReliabilityOutboxClaimResult.of(ReliabilityOutboxClaimCode.OUTCOME_UNKNOWN)
        }
    }

    fun acknowledge(
        tenantId: String,
        outboxId: String,
        ownerId: String,
        fencingToken: Long,
    ): ReliabilityStoreCode {
        require(fencingToken > 0L) { "Reliability JDBC outbox fence is invalid." }
        return try {
            transactions.write { connection, dialect ->
                val row = connection.prepareStatement(
                    """
                    SELECT tenant_id, status, lease_owner_id, lease_fencing_token
                    FROM fw_reliability_outbox WHERE tenant_id = ? AND id = ? FOR UPDATE
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, tenantId)
                    statement.setString(2, outboxId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) null else AcknowledgementRow(
                            result.getString(1), result.getString(2), result.getString(3),
                            result.getLong(4),
                        )
                    }
                } ?: return@write ReliabilityStoreCode.NOT_FOUND
                require(row.tenantId == tenantId) { "Reliability outbox tenant lookup returned mismatched state." }
                if (row.ownerId != ownerId || row.fencingToken != fencingToken) {
                    return@write ReliabilityStoreCode.CONFLICT
                }
                if (row.status == OUTBOX_PUBLISHED) return@write ReliabilityStoreCode.STORED
                if (row.status != OUTBOX_CLAIMED) return@write ReliabilityStoreCode.CONFLICT
                val nowExpression = dialect.epochMillisExpression()
                val updated = connection.prepareStatement(
                    """
                    UPDATE fw_reliability_outbox SET status = ?, published_time = $nowExpression,
                        updated_time = $nowExpression
                    WHERE tenant_id = ? AND id = ? AND status = ? AND lease_owner_id = ?
                      AND lease_fencing_token = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, OUTBOX_PUBLISHED)
                    statement.setString(2, row.tenantId)
                    statement.setString(3, outboxId)
                    statement.setString(4, OUTBOX_CLAIMED)
                    statement.setString(5, ownerId)
                    statement.setLong(6, fencingToken)
                    statement.executeUpdate()
                }
                if (updated == 1) ReliabilityStoreCode.STORED else ReliabilityStoreCode.CONFLICT
            }
        } catch (_: ReliabilityJdbcCommitOutcomeUnknownException) {
            ReliabilityStoreCode.OUTCOME_UNKNOWN
        }
    }

    private fun insertRun(
        connection: Connection,
        run: ReliabilityRun,
        memento: ByteArray,
    ) {
        val sql = """
            INSERT INTO fw_reliability_run (
                id, tenant_id, idempotency_digest, operation_kind, intent_digest, argument_digest,
                provider_id, provider_revision, provider_descriptor_digest, status, state_version,
                state_digest, state_memento, state_memento_digest, lease_owner_id, lease_fencing_token,
                lease_expires_time, next_fencing_token, provider_operation_id, original_attempt_digest,
                outcome_unknown_digest, outcome_evidence_digest, execution_deadline_time,
                created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setString(index++, run.runId)
            statement.setString(index++, run.tenantId)
            statement.setString(index++, run.intent.idempotencyDigest)
            statement.setString(index++, run.intent.kind.name)
            statement.setString(index++, run.intent.intentDigest)
            statement.setString(index++, run.intent.argumentDigest)
            statement.setString(index++, run.intent.providerId)
            statement.setString(index++, run.intent.providerRevision)
            statement.setString(index++, run.intent.providerDescriptorDigest)
            statement.setString(index++, run.status.name)
            statement.setLong(index++, run.version)
            statement.setString(index++, run.stateDigest)
            statement.setBytes(index++, memento)
            statement.setString(index++, ReliabilityJdbcDigests.bytes(memento))
            statement.setString(index++, run.lease?.ownerId)
            statement.setNullableLong(index++, run.lease?.fencingToken)
            statement.setNullableLong(index++, run.lease?.expiresAtEpochMilli)
            statement.setLong(index++, 1L)
            statement.setString(index++, run.dispatch?.originalAttempt?.providerOperationId)
            statement.setString(index++, run.dispatch?.originalAttempt?.attemptDigest)
            statement.setString(index++, run.outcomeUnknown?.referenceDigest)
            statement.setString(index++, run.outcome?.evidenceDigest)
            statement.setLong(index++, run.intent.executionDeadlineEpochMilli)
            statement.setLong(index++, run.createdAtEpochMilli)
            statement.setLong(index, run.updatedAtEpochMilli)
            check(statement.executeUpdate() == 1) { "Reliability run insert did not affect one row." }
        }
    }

    private fun updateRun(
        connection: Connection,
        current: ReliabilityRun,
        candidate: ReliabilityRun,
        memento: ByteArray,
        expectedFencingToken: Long,
    ): Int = connection.prepareStatement(
        """
        UPDATE fw_reliability_run SET
            status = ?, state_version = ?, state_digest = ?, state_memento = ?, state_memento_digest = ?,
            lease_owner_id = ?, lease_fencing_token = ?, lease_expires_time = ?, provider_operation_id = ?,
            original_attempt_digest = ?, outcome_unknown_digest = ?, outcome_evidence_digest = ?, updated_time = ?
        WHERE tenant_id = ? AND id = ? AND state_version = ? AND lease_fencing_token = ?
        """.trimIndent(),
    ).use { statement ->
        var index = 1
        statement.setString(index++, candidate.status.name)
        statement.setLong(index++, candidate.version)
        statement.setString(index++, candidate.stateDigest)
        statement.setBytes(index++, memento)
        statement.setString(index++, ReliabilityJdbcDigests.bytes(memento))
        statement.setString(index++, candidate.lease?.ownerId)
        statement.setNullableLong(index++, candidate.lease?.fencingToken)
        statement.setNullableLong(index++, candidate.lease?.expiresAtEpochMilli)
        statement.setString(index++, candidate.dispatch?.originalAttempt?.providerOperationId)
        statement.setString(index++, candidate.dispatch?.originalAttempt?.attemptDigest)
        statement.setString(index++, candidate.outcomeUnknown?.referenceDigest)
        statement.setString(index++, candidate.outcome?.evidenceDigest)
        statement.setLong(index++, candidate.updatedAtEpochMilli)
        statement.setString(index++, current.tenantId)
        statement.setString(index++, current.runId)
        statement.setLong(index++, current.version)
        statement.setLong(index, expectedFencingToken)
        statement.executeUpdate()
    }

    private fun loadRun(connection: Connection, tenantId: String, runId: String, forUpdate: Boolean): ReliabilityRun? =
        loadRunRow(connection, tenantId, runId, forUpdate)?.run

    private fun loadRunRow(
        connection: Connection,
        tenantId: String,
        runId: String,
        forUpdate: Boolean,
    ): RunRow? {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT tenant_id, id, idempotency_digest, intent_digest, status, state_version, state_digest,
                   state_memento, state_memento_digest,
                   OCTET_LENGTH(state_memento) AS state_memento_size, next_fencing_token
            FROM fw_reliability_run WHERE tenant_id = ? AND id = ?$suffix
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, runId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else decodeRunRow(result).also { row ->
                    require(row.run.tenantId == tenantId && row.run.runId == runId) {
                        "Reliability run lookup crossed its requested tenant or id boundary."
                    }
                }
            }
        }
    }

    private fun loadRunByIdempotency(
        connection: Connection,
        tenantId: String,
        idempotencyDigest: String,
        forUpdate: Boolean,
    ): ReliabilityRun? {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT tenant_id, id, idempotency_digest, intent_digest, status, state_version, state_digest,
                   state_memento, state_memento_digest,
                   OCTET_LENGTH(state_memento) AS state_memento_size, next_fencing_token
            FROM fw_reliability_run WHERE tenant_id = ? AND idempotency_digest = ?$suffix
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, idempotencyDigest)
            statement.executeQuery().use { result ->
                if (!result.next()) null else decodeRunRow(result).run.also { run ->
                    require(run.tenantId == tenantId && run.intent.idempotencyDigest == idempotencyDigest) {
                        "Reliability idempotency lookup crossed its requested tenant or digest boundary."
                    }
                }
            }
        }
    }

    private fun decodeRunRow(result: ResultSet): RunRow {
        val memento = readBoundedMemento(result, "state_memento", "Reliability run memento")
        require(ReliabilityJdbcDigests.bytes(memento) == result.getString("state_memento_digest")) {
            "Reliability run memento digest is invalid."
        }
        val run = ReliabilityJdbcCanonicalCodec.decodeRun(memento)
        require(run.tenantId == result.getString("tenant_id") && run.runId == result.getString("id") &&
            run.intent.idempotencyDigest == result.getString("idempotency_digest") &&
            run.intent.intentDigest == result.getString("intent_digest") &&
            run.status.name == result.getString("status") && run.version == result.getLong("state_version") &&
            run.stateDigest == result.getString("state_digest")
        ) { "Reliability run columns do not match the canonical memento." }
        return RunRow(run, result.getLong("next_fencing_token"))
    }

    private fun insertSchedule(
        connection: Connection,
        schedule: ReliabilitySloSchedule,
        memento: ByteArray,
    ) {
        val sql = """
            INSERT INTO fw_reliability_slo_schedule (
                id, tenant_id, policy_binding_digest, objective_resource_digest, next_evaluation_time,
                cadence_millis, state_version, state_digest, state_memento, state_memento_digest,
                lease_owner_id, lease_fencing_token, lease_expires_time, next_fencing_token,
                last_evaluation_digest, last_alert_digest, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setString(index++, schedule.scheduleId)
            statement.setString(index++, schedule.tenantId)
            statement.setString(index++, schedule.policyBindingDigest)
            statement.setString(index++, schedule.objectiveResource.referenceDigest)
            statement.setLong(index++, schedule.nextEvaluationAtEpochMilli)
            statement.setLong(index++, schedule.cadenceMillis)
            statement.setLong(index++, schedule.version)
            statement.setString(index++, schedule.stateDigest)
            statement.setBytes(index++, memento)
            statement.setString(index++, ReliabilityJdbcDigests.bytes(memento))
            statement.setString(index++, schedule.lease?.ownerId)
            statement.setNullableLong(index++, schedule.lease?.fencingToken)
            statement.setNullableLong(index++, schedule.lease?.expiresAtEpochMilli)
            statement.setLong(index++, 1L)
            statement.setString(index++, schedule.lastRecord?.evaluation?.evaluationDigest)
            statement.setString(index++, schedule.lastRecord?.alert?.alertDigest)
            statement.setLong(index++, schedule.updatedAtEpochMilli)
            statement.setLong(index, schedule.updatedAtEpochMilli)
            check(statement.executeUpdate() == 1) { "Reliability SLO schedule insert did not affect one row." }
        }
    }

    private fun loadSchedule(
        connection: Connection,
        tenantId: String,
        scheduleId: String,
        forUpdate: Boolean,
    ): ReliabilitySloSchedule? = loadScheduleRow(connection, tenantId, scheduleId, forUpdate)?.schedule

    private fun loadScheduleRow(
        connection: Connection,
        tenantId: String,
        scheduleId: String,
        forUpdate: Boolean,
    ): ScheduleRow? {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT id, tenant_id, policy_binding_digest, objective_resource_digest, next_evaluation_time,
                   cadence_millis, state_version, state_digest, state_memento, state_memento_digest,
                   OCTET_LENGTH(state_memento) AS state_memento_size,
                   next_fencing_token
            FROM fw_reliability_slo_schedule WHERE tenant_id = ? AND id = ?$suffix
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, scheduleId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                val memento = readBoundedMemento(
                    result, "state_memento", "Reliability SLO schedule memento",
                )
                require(ReliabilityJdbcDigests.bytes(memento) == result.getString("state_memento_digest")) {
                    "Reliability SLO schedule memento digest is invalid."
                }
                val schedule = ReliabilityJdbcCanonicalCodec.decodeSchedule(memento)
                require(schedule.tenantId == tenantId && schedule.scheduleId == scheduleId) {
                    "Reliability SLO lookup crossed its requested tenant or id boundary."
                }
                require(schedule.scheduleId == result.getString("id") &&
                    schedule.tenantId == result.getString("tenant_id") &&
                    schedule.policyBindingDigest == result.getString("policy_binding_digest") &&
                    schedule.objectiveResource.referenceDigest == result.getString("objective_resource_digest") &&
                    schedule.nextEvaluationAtEpochMilli == result.getLong("next_evaluation_time") &&
                    schedule.cadenceMillis == result.getLong("cadence_millis") &&
                    schedule.version == result.getLong("state_version") &&
                    schedule.stateDigest == result.getString("state_digest")
                ) { "Reliability SLO schedule columns do not match its canonical memento." }
                ScheduleRow(schedule, result.getLong("next_fencing_token"))
            }
        }
    }

    private fun insertOutbox(connection: Connection, record: ReliabilityOutboxRecord) {
        connection.prepareStatement(
            """
            INSERT INTO fw_reliability_outbox (
                id, tenant_id, event_type, aggregate_id, aggregate_state_digest, aggregate_version,
                record_digest, status, lease_owner_id, lease_fencing_token, lease_expires_time,
                next_fencing_token, published_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            var index = 1
            statement.setString(index++, record.outboxId)
            statement.setString(index++, record.tenantId)
            statement.setString(index++, record.type.name)
            statement.setString(index++, record.aggregateId)
            statement.setString(index++, record.aggregateStateDigest)
            statement.setLong(index++, record.aggregateVersion)
            statement.setString(index++, record.recordDigest)
            statement.setString(index++, OUTBOX_READY)
            statement.setString(index++, null)
            statement.setObject(index++, null)
            statement.setObject(index++, null)
            statement.setLong(index++, 1L)
            statement.setObject(index++, null)
            statement.setLong(index++, record.createdAtEpochMilli)
            statement.setLong(index, record.createdAtEpochMilli)
            check(statement.executeUpdate() == 1) { "Reliability outbox insert did not affect one row." }
        }
    }

    private fun outboxRow(result: ResultSet): OutboxRow {
        val record = ReliabilityOutboxRecord.forAggregate(
            result.getString("id"),
            enumValue(result.getString("event_type")),
            result.getString("tenant_id"),
            result.getString("aggregate_id"),
            result.getString("aggregate_state_digest"),
            result.getLong("aggregate_version"),
            result.getLong("aggregate_version").let { _ ->
                // Created time is not needed to order the selected row but is part of its canonical digest.
                // Reload it in the same statement when this method is called.
                result.getLong("created_time")
            },
        )
        require(record.recordDigest == result.getString("record_digest")) {
            "Reliability outbox columns do not match their canonical digest."
        }
        return OutboxRow(record, result.getLong("next_fencing_token"))
    }

    private fun persistProviderEvidence(connection: Connection, current: ReliabilityRun, candidate: ReliabilityRun) {
        val durableSnapshot = ReliabilityJdbcCanonicalCodec.encodeRun(candidate)
        val dispatch = candidate.dispatch
        if (dispatch != null && current.dispatch == null) {
            val attempt = dispatch.originalAttempt
            connection.prepareStatement(
                """
                INSERT INTO fw_reliability_provider_attempt (
                    id, tenant_id, run_id, operation_kind, provider_id, provider_revision,
                    provider_operation_id, request_digest, version_fence_digest, attempt_digest,
                    attempt_memento, attempt_memento_digest, started_time, deadline_time,
                    created_time, updated_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                val rowId = ReliabilityJdbcDigests.rowId("reliability-attempt", candidate.tenantId, attempt.attemptDigest)
                var index = 1
                statement.setString(index++, rowId)
                statement.setString(index++, candidate.tenantId)
                statement.setString(index++, candidate.runId)
                statement.setString(index++, attempt.kind.name)
                statement.setString(index++, attempt.providerId)
                statement.setString(index++, attempt.providerRevision)
                statement.setString(index++, attempt.providerOperationId)
                statement.setString(index++, attempt.requestDigest)
                statement.setString(index++, attempt.versionFence.fenceDigest)
                statement.setString(index++, attempt.attemptDigest)
                statement.setBytes(index++, durableSnapshot)
                statement.setString(index++, ReliabilityJdbcDigests.bytes(durableSnapshot))
                statement.setLong(index++, attempt.startedAtEpochMilli)
                statement.setLong(index++, attempt.executionDeadlineEpochMilli)
                statement.setLong(index++, candidate.updatedAtEpochMilli)
                statement.setLong(index, candidate.updatedAtEpochMilli)
                check(statement.executeUpdate() == 1) { "Reliability provider attempt insert failed." }
            }
        }
        val evidence = when {
            candidate.outcomeUnknown != null && current.outcomeUnknown == null -> Evidence(
                "OUTCOME_UNKNOWN",
                candidate.outcomeUnknown!!.referenceDigest,
                candidate.outcomeUnknown!!.originalAttempt.attemptDigest,
                candidate.outcomeUnknown!!.referenceDigest,
                candidate.outcomeUnknown!!.recordedAtEpochMilli,
                durableSnapshot,
            )
            candidate.outcome != null && current.outcome == null -> Evidence(
                outcomeKind(candidate),
                candidate.outcome!!.evidenceDigest,
                candidate.dispatch?.originalAttempt?.attemptDigest,
                candidate.outcome!!.evidenceDigest,
                candidate.updatedAtEpochMilli,
                durableSnapshot,
            )
            else -> null
        } ?: return
        connection.prepareStatement(
            """
            INSERT INTO fw_reliability_provider_receipt (
                id, tenant_id, run_id, attempt_digest, evidence_kind, evidence_digest,
                reference_digest, evidence_memento, evidence_memento_digest, recorded_time,
                created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            val rowId = ReliabilityJdbcDigests.rowId(
                "reliability-evidence", candidate.tenantId, evidence.kind, evidence.digest,
            )
            statement.setString(1, rowId)
            statement.setString(2, candidate.tenantId)
            statement.setString(3, candidate.runId)
            statement.setString(4, evidence.attemptDigest)
            statement.setString(5, evidence.kind)
            statement.setString(6, evidence.digest)
            statement.setString(7, evidence.referenceDigest)
            statement.setBytes(8, evidence.memento)
            statement.setString(9, ReliabilityJdbcDigests.bytes(evidence.memento))
            statement.setLong(10, evidence.recordedAt)
            statement.setLong(11, candidate.updatedAtEpochMilli)
            statement.setLong(12, candidate.updatedAtEpochMilli)
            check(statement.executeUpdate() == 1) { "Reliability provider evidence insert failed." }
        }
    }

    private fun persistSloEvaluation(connection: Connection, schedule: ReliabilitySloSchedule) {
        val record = requireNotNull(schedule.lastRecord) {
            "Reliability evaluated SLO schedule must contain its exact record."
        }
        val memento = ReliabilityJdbcCanonicalCodec.encodeEvaluationRecord(record)
        val rowId = ReliabilityJdbcDigests.rowId(
            "reliability-slo-evaluation", schedule.tenantId, schedule.scheduleId, record.recordDigest,
        )
        connection.prepareStatement(
            """
            INSERT INTO fw_reliability_slo_evaluation (
                id, tenant_id, schedule_id, schedule_version, evaluation_digest, alert_digest,
                record_memento, record_memento_digest, evaluated_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, rowId)
            statement.setString(2, schedule.tenantId)
            statement.setString(3, schedule.scheduleId)
            statement.setLong(4, schedule.version)
            statement.setString(5, record.evaluation.evaluationDigest)
            statement.setString(6, record.alert.alertDigest)
            statement.setBytes(7, memento)
            statement.setString(8, ReliabilityJdbcDigests.bytes(memento))
            statement.setLong(9, record.evaluation.evaluatedAtEpochMilli)
            statement.setLong(10, schedule.updatedAtEpochMilli)
            statement.setLong(11, schedule.updatedAtEpochMilli)
            check(statement.executeUpdate() == 1) { "Reliability SLO evaluation insert failed." }
        }
    }

    private fun requireOutboxForRun(outbox: ReliabilityOutboxRecord, run: ReliabilityRun) {
        require(outbox.tenantId == run.tenantId && outbox.aggregateId == run.runId &&
            outbox.aggregateVersion == run.version && outbox.aggregateStateDigest == run.stateDigest
        ) { "Reliability outbox is not bound to the exact run state." }
    }

    private fun requireOutboxForSchedule(outbox: ReliabilityOutboxRecord, schedule: ReliabilitySloSchedule) {
        require(outbox.tenantId == schedule.tenantId && outbox.aggregateId == schedule.scheduleId &&
            outbox.aggregateVersion == schedule.version && outbox.aggregateStateDigest == schedule.stateDigest
        ) { "Reliability outbox is not bound to the exact SLO schedule state." }
    }

    private fun reconcileRunInsertConflict(run: ReliabilityRun): ReliabilityStoreResult {
        findByIdempotency(run.tenantId, run.intent.idempotencyDigest)?.let {
            return ReliabilityStoreResult.of(ReliabilityStoreCode.REPLAY, it)
        }
        loadRunPort(run.tenantId, run.runId)?.let {
            return ReliabilityStoreResult.of(ReliabilityStoreCode.CONFLICT, it)
        }
        throw SQLException("Reliability run uniqueness conflict disappeared before exact reconciliation.")
    }

    private fun reconcileScheduleInsertConflict(schedule: ReliabilitySloSchedule): ReliabilityStoreCode {
        val existing = loadSchedulePort(schedule.tenantId, schedule.scheduleId)
            ?: throw SQLException("Reliability SLO uniqueness conflict disappeared before exact reconciliation.")
        return if (existing.stateDigest == schedule.stateDigest) {
            ReliabilityStoreCode.REPLAY
        } else {
            ReliabilityStoreCode.CONFLICT
        }
    }

    private fun hasSameRunBinding(current: ReliabilityRun, candidate: ReliabilityRun): Boolean =
        current.intent.intentDigest == candidate.intent.intentDigest &&
            current.createdAtEpochMilli == candidate.createdAtEpochMilli &&
            current.lease?.leaseDigest == candidate.lease?.leaseDigest &&
            candidate.updatedAtEpochMilli >= current.updatedAtEpochMilli &&
            !current.isTerminal()

    private fun hasSameScheduleBinding(
        current: ReliabilitySloSchedule,
        candidate: ReliabilitySloSchedule,
    ): Boolean = current.policyBindingDigest == candidate.policyBindingDigest &&
        current.objectiveResource.referenceDigest == candidate.objectiveResource.referenceDigest &&
        current.cadenceMillis == candidate.cadenceMillis &&
        current.lease?.leaseDigest == candidate.lease?.leaseDigest &&
        candidate.updatedAtEpochMilli >= current.updatedAtEpochMilli

    private fun readBoundedMemento(result: ResultSet, column: String, label: String): ByteArray {
        val size = result.getLong("state_memento_size")
        require(size in 1L..ReliabilityJdbcCanonicalCodec.MAX_MEMENTO_BYTES.toLong()) {
            "$label size is invalid."
        }
        val expectedSize = size.toInt()
        val output = ByteArrayOutputStream(expectedSize)
        val buffer = ByteArray(8192)
        result.getBinaryStream(column).use { input ->
            requireNotNull(input) { "$label is missing." }
            var total = 0
            while (total <= ReliabilityJdbcCanonicalCodec.MAX_MEMENTO_BYTES) {
                val read = input.read(buffer, 0, minOf(buffer.size, expectedSize + 1 - total))
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
                require(total <= expectedSize) { "$label changed while it was read." }
            }
            require(total == expectedSize && input.read() == -1) { "$label length is inconsistent." }
        }
        return output.toByteArray()
    }

    private fun outcomeKind(run: ReliabilityRun): String = when {
        run.outcome?.backupReceipt != null -> "BACKUP_RECEIPT"
        run.outcome?.verificationReceipt != null -> "VERIFICATION_RECEIPT"
        run.outcome?.restoreReceipt != null -> "RESTORE_RECEIPT"
        run.outcome?.drillReport != null -> "DRILL_REPORT"
        run.outcome?.reconciliationReceipt != null -> "RECONCILIATION_RECEIPT"
        else -> error("Reliability outcome has no exact receipt.")
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Reliability JDBC enum value is invalid.")

    private data class RunRow(val run: ReliabilityRun, val nextFencingToken: Long)
    private data class ScheduleRow(val schedule: ReliabilitySloSchedule, val nextFencingToken: Long)
    private data class OutboxRow(val record: ReliabilityOutboxRecord, val nextFencingToken: Long)
    private data class AcknowledgementRow(
        val tenantId: String,
        val status: String,
        val ownerId: String?,
        val fencingToken: Long,
    )
    private data class Evidence(
        val kind: String,
        val digest: String,
        val attemptDigest: String?,
        val referenceDigest: String?,
        val recordedAt: Long,
        val memento: ByteArray,
    )

    companion object {
        private const val OUTBOX_READY = "READY"
        private const val OUTBOX_CLAIMED = "CLAIMED"
        private const val OUTBOX_PUBLISHED = "PUBLISHED"
    }
}

/**
 * Java-friendly composition root. All three adapters share one transaction policy and DataSource,
 * while retaining distinct port types (the run and SLO ports intentionally both declare `load`).
 */
class JdbcReliabilityPersistence @JvmOverloads constructor(
    dataSource: DataSource,
    configuredDialect: ReliabilityJdbcDialect? = null,
) {
    private val store = ReliabilityJdbcStore(dataSource, configuredDialect)

    val runRepository: JdbcReliabilityRunRepository = JdbcReliabilityRunRepository(store)
    val outboxRepository: JdbcReliabilityOutboxRepository = JdbcReliabilityOutboxRepository(store)
    val sloRepository: JdbcReliabilitySloRepository = JdbcReliabilitySloRepository(store)

    companion object {
        const val CONTRACT_VERSION: String = "flowweft.reliability.jdbc-persistence.v1"

        @JvmStatic
        @JvmOverloads
        fun create(
            dataSource: DataSource,
            configuredDialect: ReliabilityJdbcDialect? = null,
        ): JdbcReliabilityPersistence = JdbcReliabilityPersistence(dataSource, configuredDialect)
    }
}

class JdbcReliabilityRunRepository internal constructor(
    private val store: ReliabilityJdbcStore,
) : ReliabilityRunRepository {
    @JvmOverloads
    constructor(dataSource: DataSource, configuredDialect: ReliabilityJdbcDialect? = null) :
        this(ReliabilityJdbcStore(dataSource, configuredDialect))

    override fun createOrLoad(run: ReliabilityRun, outbox: ReliabilityOutboxRecord): ReliabilityStoreResult =
        store.createOrLoad(run, outbox)

    override fun load(tenantId: String, runId: String): ReliabilityRun? = store.loadRunPort(tenantId, runId)

    override fun findByIdempotency(tenantId: String, idempotencyDigest: String): ReliabilityRun? =
        store.findByIdempotency(tenantId, idempotencyDigest)

    override fun claim(
        tenantId: String,
        runId: String,
        expectedVersion: Long,
        ownerId: String,
        nowEpochMilli: Long,
        leaseUntilEpochMilli: Long,
    ): ReliabilityStoreResult = store.claim(
        tenantId, runId, expectedVersion, ownerId, nowEpochMilli, leaseUntilEpochMilli,
    )

    override fun compareAndSet(
        tenantId: String,
        runId: String,
        expectedVersion: Long,
        expectedFencingToken: Long,
        candidate: ReliabilityRun,
        outbox: ReliabilityOutboxRecord,
    ): ReliabilityStoreResult = store.compareAndSet(
        tenantId, runId, expectedVersion, expectedFencingToken, candidate, outbox,
    )
}

class JdbcReliabilityOutboxRepository internal constructor(
    private val store: ReliabilityJdbcStore,
) : ReliabilityOutboxRepository {
    @JvmOverloads
    constructor(dataSource: DataSource, configuredDialect: ReliabilityJdbcDialect? = null) :
        this(ReliabilityJdbcStore(dataSource, configuredDialect))

    override fun claimNext(
        ownerId: String,
        nowEpochMilli: Long,
        leaseUntilEpochMilli: Long,
    ): ReliabilityOutboxClaimResult = store.claimNext(ownerId, nowEpochMilli, leaseUntilEpochMilli)

    override fun acknowledge(
        tenantId: String,
        outboxId: String,
        ownerId: String,
        fencingToken: Long,
    ): ReliabilityStoreCode = store.acknowledge(tenantId, outboxId, ownerId, fencingToken)
}

class JdbcReliabilitySloRepository internal constructor(
    private val store: ReliabilityJdbcStore,
) : ReliabilitySloScheduleRepository {
    @JvmOverloads
    constructor(dataSource: DataSource, configuredDialect: ReliabilityJdbcDialect? = null) :
        this(ReliabilityJdbcStore(dataSource, configuredDialect))

    fun createSchedule(schedule: ReliabilitySloSchedule): ReliabilityStoreCode = store.createSchedule(schedule)

    override fun load(tenantId: String, scheduleId: String): ReliabilitySloSchedule? =
        store.loadSchedulePort(tenantId, scheduleId)

    override fun claimDue(
        tenantId: String,
        scheduleId: String,
        expectedVersion: Long,
        ownerId: String,
        nowEpochMilli: Long,
        leaseUntilEpochMilli: Long,
    ): ReliabilitySloSchedule? = store.claimDue(
        tenantId, scheduleId, expectedVersion, ownerId, nowEpochMilli, leaseUntilEpochMilli,
    )

    override fun compareAndSet(
        tenantId: String,
        scheduleId: String,
        expectedVersion: Long,
        expectedFencingToken: Long,
        candidate: ReliabilitySloSchedule,
        outbox: ReliabilityOutboxRecord,
    ): ReliabilityStoreCode = store.compareAndSet(
        tenantId, scheduleId, expectedVersion, expectedFencingToken, candidate, outbox,
    )
}

private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setObject(index, null) else setLong(index, value)
}
