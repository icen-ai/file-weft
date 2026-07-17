package ai.icen.fw.governance.persistence.jdbc

import ai.icen.fw.governance.api.GovernanceDoctorFinding
import ai.icen.fw.governance.api.GovernanceDoctorMode
import ai.icen.fw.governance.api.GovernanceDoctorSeverity
import ai.icen.fw.governance.runtime.GovernanceClaimedOutboxRecord
import ai.icen.fw.governance.runtime.GovernanceDeletionRepository
import ai.icen.fw.governance.runtime.GovernanceDeletionRun
import ai.icen.fw.governance.runtime.GovernanceMetric
import ai.icen.fw.governance.runtime.GovernanceMetricCode
import ai.icen.fw.governance.runtime.GovernanceMetricsPort
import ai.icen.fw.governance.runtime.GovernanceOutboxClaimRequest
import ai.icen.fw.governance.runtime.GovernanceOutboxRecord
import ai.icen.fw.governance.runtime.GovernanceOutboxRepository
import ai.icen.fw.governance.runtime.GovernanceOutboxType
import ai.icen.fw.governance.runtime.GovernanceRuntimeDiagnosticSource
import ai.icen.fw.governance.runtime.GovernanceStoreCode
import ai.icen.fw.governance.runtime.GovernanceStoreResult
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Production JDBC repository and fenced outbox for the provider-neutral governance runtime.
 * No method calls authorization, a deletion provider, reconciliation, or a worker.
 */
class JdbcGovernancePersistence @JvmOverloads constructor(
    dataSource: DataSource,
    configuredDialect: GovernanceJdbcDialect? = null,
    private val metrics: GovernanceMetricsPort? = null,
) : GovernanceDeletionRepository, GovernanceOutboxRepository, GovernanceRuntimeDiagnosticSource {
    private val transactions = GovernanceJdbcTransactions(dataSource, configuredDialect)

    override fun findByIdempotency(tenantId: String, idempotencyKey: String): GovernanceDeletionRun? {
        val boundedTenantId = GovernanceJdbcValues.id(tenantId)
        val boundedIdempotencyKey = GovernanceJdbcValues.id(idempotencyKey)
        val digest = idempotencyDigest(boundedTenantId, boundedIdempotencyKey)
        return jdbcBoundary {
            transactions.read { connection, _ ->
                loadByIdempotencyDigest(connection, boundedTenantId, digest, false)?.run?.also { run ->
                    require(run.idempotencyKey == boundedIdempotencyKey) {
                        "Governance JDBC idempotency digest collision is fail-closed."
                    }
                }
            }
        }
    }

    override fun load(tenantId: String, planId: String): GovernanceDeletionRun? {
        val boundedTenantId = GovernanceJdbcValues.id(tenantId)
        val boundedPlanId = GovernanceJdbcValues.id(planId)
        return jdbcBoundary {
            transactions.read { connection, _ ->
                loadByPlan(connection, boundedTenantId, boundedPlanId, false)?.run
            }
        }
    }

    override fun compareAndSet(
        tenantId: String,
        planId: String,
        expectedVersion: Long?,
        candidate: GovernanceDeletionRun,
        outbox: GovernanceOutboxRecord,
    ): GovernanceStoreResult {
        if (!validTransitionArguments(tenantId, planId, expectedVersion, candidate, outbox)) {
            return conflict()
        }
        val memento = GovernanceJdbcCanonicalCodec.encodeRun(candidate)
        val mementoDigest = GovernanceJdbcDigests.bytes(memento)
        return try {
            val result = transactions.transaction { connection, _ ->
                val candidateIdempotencyDigest = idempotencyDigest(tenantId, candidate.idempotencyKey)
                if (expectedVersion == null) {
                    val priorIdempotency = loadByIdempotencyDigest(
                        connection, tenantId, candidateIdempotencyDigest, true,
                    )
                    if (priorIdempotency != null) {
                        return@transaction if (exact(priorIdempotency.run, candidate)) {
                            GovernanceStoreResult.replayed(priorIdempotency.run)
                        } else {
                            GovernanceStoreResult.failed(GovernanceStoreCode.CONFLICT)
                        }
                    }
                }

                val current = loadByPlan(connection, tenantId, planId, true)
                if (current != null && exact(current.run, candidate)) {
                    return@transaction GovernanceStoreResult.replayed(current.run)
                }
                val matches = if (expectedVersion == null) {
                    current == null
                } else {
                    current?.run?.version == expectedVersion
                }
                if (!matches) return@transaction GovernanceStoreResult.failed(GovernanceStoreCode.CONFLICT)

                if (current == null) {
                    insertRun(connection, candidate, candidateIdempotencyDigest, memento, mementoDigest)
                } else {
                    if (current.idempotencyDigest != candidateIdempotencyDigest ||
                        !sameImmutableBinding(current.run, candidate)
                    ) return@transaction GovernanceStoreResult.failed(GovernanceStoreCode.CONFLICT)
                    val changed = updateRun(
                        connection,
                        current,
                        candidate,
                        requireNotNull(expectedVersion),
                        memento,
                        mementoDigest,
                    )
                    if (!changed) return@transaction GovernanceStoreResult.failed(GovernanceStoreCode.CONFLICT)
                }
                insertOutbox(connection, outbox, memento, mementoDigest)
                GovernanceStoreResult.stored(candidate)
            }
            observeStoreResult(result)
            result
        } catch (_: GovernanceJdbcCommitOutcomeUnknownException) {
            metric(GovernanceMetricCode.STORE_OUTCOME_UNKNOWN)
            GovernanceStoreResult.failed(GovernanceStoreCode.OUTCOME_UNKNOWN)
        } catch (failure: SQLException) {
            if (!failure.isGovernanceUniqueViolation()) throw GovernanceJdbcPersistenceException(failure)
            reconcileUniqueConflict(candidate).also(::observeStoreResult)
        }
    }

    override fun claimReady(request: GovernanceOutboxClaimRequest): List<GovernanceClaimedOutboxRecord> =
        jdbcBoundary { transactions.transaction { connection, dialect ->
            val rows = connection.prepareStatement(dialect.claimReadySql()).use { statement ->
                statement.setString(1, request.tenantId)
                statement.setLong(2, request.nowEpochMilli)
                statement.setLong(3, request.nowEpochMilli)
                statement.setInt(4, request.maximumRecords)
                statement.executeQuery().use { result ->
                    val claimed = mutableListOf<OutboxRow>()
                    while (result.next()) claimed += readOutboxRow(request.tenantId, result)
                    claimed
                }
            }
            val claimDigest = claimDigest(request.tenantId, request.claimId)
            val workerDigest = workerDigest(request.tenantId, request.workerId)
            rows.map { row ->
                val fencingToken = Math.addExact(row.fencingToken, 1L)
                val changed = connection.prepareStatement(
                    """UPDATE fw_governance_deletion_outbox
                       SET claim_digest = ?, worker_digest = ?, fencing_token = ?,
                           lease_expires_time = ?, updated_time = ?
                       WHERE tenant_id = ? AND id = ? AND acknowledged_time IS NULL
                         AND fencing_token = ?
                         AND (lease_expires_time IS NULL OR lease_expires_time <= ?)""".trimIndent(),
                ).use { statement ->
                    statement.setString(1, claimDigest)
                    statement.setString(2, workerDigest)
                    statement.setLong(3, fencingToken)
                    statement.setLong(4, request.leaseExpiresAtEpochMilli)
                    statement.setLong(5, request.nowEpochMilli)
                    statement.setString(6, request.tenantId)
                    statement.setString(7, row.id)
                    statement.setLong(8, row.fencingToken)
                    statement.setLong(9, request.nowEpochMilli)
                    statement.executeUpdate()
                }
                check(changed == 1) { "Governance JDBC outbox claim lost its fencing CAS." }
                GovernanceClaimedOutboxRecord.of(
                    row.record,
                    request.claimId,
                    request.workerId,
                    fencingToken,
                    request.leaseExpiresAtEpochMilli,
                )
            }
        } }

    override fun acknowledge(
        claim: GovernanceClaimedOutboxRecord,
        acknowledgedAtEpochMilli: Long,
    ): Boolean {
        val tenantId = claim.record.tenantId
        if (acknowledgedAtEpochMilli < claim.record.createdAtEpochMilli ||
            acknowledgedAtEpochMilli >= claim.leaseExpiresAtEpochMilli) {
            return false
        }
        val id = outboxRowId(tenantId, claim.record.recordId)
        return jdbcBoundary {
            transactions.transaction { connection, _ -> connection.prepareStatement(
                """UPDATE fw_governance_deletion_outbox
                   SET acknowledged_time = ?, updated_time = ?
                   WHERE tenant_id = ? AND id = ? AND record_id_digest = ? AND record_id = ?
                     AND acknowledged_time IS NULL AND claim_digest = ? AND worker_digest = ?
                     AND fencing_token = ? AND lease_expires_time = ? AND lease_expires_time > ?
                     AND record_digest = ? AND state_digest = ? AND run_version = ?""".trimIndent(),
            ).use { statement ->
                statement.setLong(1, acknowledgedAtEpochMilli)
                statement.setLong(2, acknowledgedAtEpochMilli)
                statement.setString(3, tenantId)
                statement.setString(4, id)
                statement.setString(5, recordIdDigest(tenantId, claim.record.recordId))
                statement.setString(6, claim.record.recordId)
                statement.setString(7, claimDigest(tenantId, claim.claimId))
                statement.setString(8, workerDigest(tenantId, claim.workerId))
                statement.setLong(9, claim.fencingToken)
                statement.setLong(10, claim.leaseExpiresAtEpochMilli)
                statement.setLong(11, acknowledgedAtEpochMilli)
                statement.setString(12, claim.record.recordDigest)
                statement.setString(13, claim.record.stateDigest)
                statement.setLong(14, claim.record.runVersion)
                statement.executeUpdate() == 1
            } }
        }
    }

    /** Schema/connection-only Doctor evidence. It never scans another tenant's business rows. */
    override fun findings(
        mode: GovernanceDoctorMode,
        observedAtEpochMilli: Long,
    ): Collection<GovernanceDoctorFinding> = try {
        require(observedAtEpochMilli >= 0L) { "Governance JDBC Doctor observation time is invalid." }
        transactions.read { connection, _ ->
            verifySchema(connection, "fw_governance_deletion_run", "run_version")
            verifySchema(connection, "fw_governance_deletion_outbox", "fencing_token")
        }
        listOf(GovernanceDoctorFinding.of("governance-jdbc-ready", GovernanceDoctorSeverity.INFO, 1L))
    } catch (_: RuntimeException) {
        listOf(GovernanceDoctorFinding.of("governance-jdbc-unavailable", GovernanceDoctorSeverity.ERROR, 1L))
    } catch (_: SQLException) {
        listOf(GovernanceDoctorFinding.of("governance-jdbc-unavailable", GovernanceDoctorSeverity.ERROR, 1L))
    }

    override fun toString(): String = "JdbcGovernancePersistence(<redacted>)"

    private fun verifySchema(connection: Connection, table: String, column: String) {
        require(table in SCHEMA_TABLES && column in SCHEMA_COLUMNS) { "Governance JDBC schema probe is invalid." }
        connection.prepareStatement(
            "SELECT $column FROM $table WHERE tenant_id = ? AND 1 = 0",
        ).use { statement ->
            statement.setString(1, "flowweft-doctor-probe")
            statement.executeQuery().use { result -> check(!result.next()) }
        }
    }

    private fun validTransitionArguments(
        tenantId: String,
        planId: String,
        expectedVersion: Long?,
        candidate: GovernanceDeletionRun,
        outbox: GovernanceOutboxRecord,
    ): Boolean {
        val versionMatches = if (expectedVersion == null) {
            candidate.version == 1L
        } else {
            expectedVersion > 0L && expectedVersion < Long.MAX_VALUE && candidate.version == expectedVersion + 1L
        }
        return versionMatches && candidate.tenantId == tenantId && candidate.planId == planId &&
            outbox.tenantId == tenantId && outbox.planId == planId && outbox.runVersion == candidate.version &&
            outbox.stateDigest == candidate.stateDigest && outbox.run.stateDigest == candidate.stateDigest
    }

    private fun insertRun(
        connection: Connection,
        run: GovernanceDeletionRun,
        idempotencyDigest: String,
        memento: ByteArray,
        mementoDigest: String,
    ) {
        connection.prepareStatement(
            """INSERT INTO fw_governance_deletion_run
               (id, tenant_id, plan_id, plan_id_digest, idempotency_digest, run_version, status_code,
                state_digest, memento_version, run_memento, run_memento_digest,
                created_time, updated_time)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
        ).use { statement ->
            statement.setString(1, runRowId(run.tenantId, run.planId))
            statement.setString(2, run.tenantId)
            statement.setString(3, run.planId)
            statement.setString(4, planIdDigest(run.tenantId, run.planId))
            statement.setString(5, idempotencyDigest)
            statement.setLong(6, run.version)
            statement.setString(7, run.status.code)
            statement.setString(8, run.stateDigest)
            statement.setInt(9, GovernanceJdbcCanonicalCodec.VERSION)
            statement.setBytes(10, memento)
            statement.setString(11, mementoDigest)
            statement.setLong(12, run.updatedAtEpochMilli)
            statement.setLong(13, run.updatedAtEpochMilli)
            check(statement.executeUpdate() == 1) { "Governance JDBC initial run insert failed." }
        }
    }

    private fun updateRun(
        connection: Connection,
        current: RunRow,
        run: GovernanceDeletionRun,
        expectedVersion: Long,
        memento: ByteArray,
        mementoDigest: String,
    ): Boolean = connection.prepareStatement(
        """UPDATE fw_governance_deletion_run
           SET run_version = ?, status_code = ?, state_digest = ?, memento_version = ?,
               run_memento = ?, run_memento_digest = ?, updated_time = ?
           WHERE tenant_id = ? AND plan_id_digest = ? AND plan_id = ? AND id = ? AND idempotency_digest = ?
             AND run_version = ? AND state_digest = ?""".trimIndent(),
    ).use { statement ->
        statement.setLong(1, run.version)
        statement.setString(2, run.status.code)
        statement.setString(3, run.stateDigest)
        statement.setInt(4, GovernanceJdbcCanonicalCodec.VERSION)
        statement.setBytes(5, memento)
        statement.setString(6, mementoDigest)
        statement.setLong(7, run.updatedAtEpochMilli)
        statement.setString(8, run.tenantId)
        statement.setString(9, current.planIdDigest)
        statement.setString(10, run.planId)
        statement.setString(11, current.id)
        statement.setString(12, current.idempotencyDigest)
        statement.setLong(13, expectedVersion)
        statement.setString(14, current.run.stateDigest)
        statement.executeUpdate() == 1
    }

    private fun insertOutbox(
        connection: Connection,
        record: GovernanceOutboxRecord,
        memento: ByteArray,
        mementoDigest: String,
    ) {
        connection.prepareStatement(
            """INSERT INTO fw_governance_deletion_outbox
               (id, tenant_id, record_id, record_id_digest, plan_id, plan_id_digest,
                event_type, run_version, state_digest,
                record_digest, memento_version, run_memento, run_memento_digest,
                available_time, fencing_token, created_time, updated_time)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
        ).use { statement ->
            statement.setString(1, outboxRowId(record.tenantId, record.recordId))
            statement.setString(2, record.tenantId)
            statement.setString(3, record.recordId)
            statement.setString(4, recordIdDigest(record.tenantId, record.recordId))
            statement.setString(5, record.planId)
            statement.setString(6, planIdDigest(record.tenantId, record.planId))
            statement.setString(7, record.type.code)
            statement.setLong(8, record.runVersion)
            statement.setString(9, record.stateDigest)
            statement.setString(10, record.recordDigest)
            statement.setInt(11, GovernanceJdbcCanonicalCodec.VERSION)
            statement.setBytes(12, memento)
            statement.setString(13, mementoDigest)
            statement.setLong(14, record.createdAtEpochMilli)
            statement.setLong(15, 0L)
            statement.setLong(16, record.createdAtEpochMilli)
            statement.setLong(17, record.createdAtEpochMilli)
            check(statement.executeUpdate() == 1) { "Governance JDBC outbox insert failed." }
        }
    }

    private fun loadByPlan(
        connection: Connection,
        tenantId: String,
        planId: String,
        forUpdate: Boolean,
    ): RunRow? {
        val row = loadRun(
            connection,
            "tenant_id = ? AND plan_id_digest = ?",
            listOf(tenantId, planIdDigest(tenantId, planId)),
            forUpdate,
        )
        require(row == null || row.run.planId == planId) {
            "Governance JDBC plan digest collision is fail-closed."
        }
        return row
    }

    private fun loadByIdempotencyDigest(
        connection: Connection,
        tenantId: String,
        digest: String,
        forUpdate: Boolean,
    ): RunRow? = loadRun(
        connection,
        "tenant_id = ? AND idempotency_digest = ?",
        listOf(tenantId, digest),
        forUpdate,
    )

    private fun loadRun(
        connection: Connection,
        predicate: String,
        arguments: List<String>,
        forUpdate: Boolean,
    ): RunRow? {
        require(predicate in RUN_PREDICATES && arguments.size == 2) {
            "Governance JDBC run lookup predicate is invalid."
        }
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """SELECT id, tenant_id, plan_id, plan_id_digest, idempotency_digest, run_version, status_code,
                      state_digest, memento_version, run_memento, run_memento_digest,
                      OCTET_LENGTH(run_memento) AS run_memento_size,
                      created_time, updated_time
               FROM fw_governance_deletion_run WHERE $predicate$suffix""".trimIndent(),
        ).use { statement ->
            statement.setString(1, arguments[0])
            statement.setString(2, arguments[1])
            statement.executeQuery().use { result ->
                if (!result.next()) null else readRunRow(result).also {
                    require(!result.next()) { "Governance JDBC run uniqueness invariant is violated." }
                }
            }
        }
    }

    private fun readRunRow(result: ResultSet): RunRow {
        val id = result.getString("id")
        val tenantId = result.getString("tenant_id")
        val planId = result.getString("plan_id")
        val storedPlanIdDigest = result.getString("plan_id_digest")
        val storedIdempotencyDigest = result.getString("idempotency_digest")
        val stateDigest = result.getString("state_digest")
        val memento = readBoundedMemento(result)
        require(result.getInt("memento_version") == GovernanceJdbcCanonicalCodec.VERSION &&
            GovernanceJdbcDigests.bytes(memento) == result.getString("run_memento_digest")
        ) { "Governance JDBC run memento is invalid." }
        val run = GovernanceJdbcCanonicalCodec.decodeRun(memento, stateDigest)
        require(id == runRowId(tenantId, planId) && storedPlanIdDigest == planIdDigest(tenantId, planId) &&
            run.tenantId == tenantId && run.planId == planId &&
            run.version == result.getLong("run_version") && run.status.code == result.getString("status_code") &&
            run.stateDigest == stateDigest &&
            storedIdempotencyDigest == idempotencyDigest(tenantId, run.idempotencyKey) &&
            result.getLong("created_time") <= result.getLong("updated_time") &&
            run.updatedAtEpochMilli == result.getLong("updated_time")
        ) { "Governance JDBC run row does not match its canonical memento." }
        return RunRow(id, storedPlanIdDigest, storedIdempotencyDigest, run)
    }

    private fun readOutboxRow(tenantId: String, result: ResultSet): OutboxRow {
        val id = result.getString("id")
        val recordId = result.getString("record_id")
        val storedRecordIdDigest = result.getString("record_id_digest")
        val planId = result.getString("plan_id")
        val storedPlanIdDigest = result.getString("plan_id_digest")
        val stateDigest = result.getString("state_digest")
        require(result.getString("tenant_id") == tenantId) {
            "Governance JDBC outbox lookup crossed its requested tenant boundary."
        }
        val memento = readBoundedMemento(result)
        require(result.getInt("memento_version") == GovernanceJdbcCanonicalCodec.VERSION &&
            GovernanceJdbcDigests.bytes(memento) == result.getString("run_memento_digest")
        ) {
            "Governance JDBC outbox memento is invalid."
        }
        val run = GovernanceJdbcCanonicalCodec.decodeRun(memento, stateDigest)
        val record = GovernanceOutboxRecord.of(
            recordId,
            outboxType(result.getString("event_type")),
            run,
            result.getLong("available_time"),
        )
        require(id == outboxRowId(tenantId, recordId) &&
            storedRecordIdDigest == recordIdDigest(tenantId, recordId) &&
            storedPlanIdDigest == planIdDigest(tenantId, planId) && record.planId == planId &&
            record.tenantId == tenantId &&
            record.runVersion == result.getLong("run_version") && record.stateDigest == stateDigest &&
            record.recordDigest == result.getString("record_digest")
        ) { "Governance JDBC outbox row does not match its canonical memento." }
        return OutboxRow(id, record, result.getLong("fencing_token"))
    }

    private fun outboxType(code: String): GovernanceOutboxType = when (code) {
        GovernanceOutboxType.RUN_READY.code -> GovernanceOutboxType.RUN_READY
        GovernanceOutboxType.STEP_READY.code -> GovernanceOutboxType.STEP_READY
        GovernanceOutboxType.RETRY_READY.code -> GovernanceOutboxType.RETRY_READY
        GovernanceOutboxType.RECONCILIATION_REQUIRED.code -> GovernanceOutboxType.RECONCILIATION_REQUIRED
        GovernanceOutboxType.RUN_BLOCKED.code -> GovernanceOutboxType.RUN_BLOCKED
        GovernanceOutboxType.RUN_COMPLETED.code -> GovernanceOutboxType.RUN_COMPLETED
        GovernanceOutboxType.RUN_FAILED.code -> GovernanceOutboxType.RUN_FAILED
        GovernanceOutboxType.STATE_CHECKPOINTED.code -> GovernanceOutboxType.STATE_CHECKPOINTED
        else -> throw IllegalArgumentException("Governance JDBC outbox type is unsupported.")
    }

    private fun exact(first: GovernanceDeletionRun, second: GovernanceDeletionRun): Boolean =
        first.tenantId == second.tenantId && first.planId == second.planId &&
            first.version == second.version && first.stateDigest == second.stateDigest

    private fun sameImmutableBinding(current: GovernanceDeletionRun, candidate: GovernanceDeletionRun): Boolean =
        current.plan.planDigest == candidate.plan.planDigest &&
            current.commandDigest == candidate.commandDigest &&
            current.idempotencyKey == candidate.idempotencyKey &&
            candidate.updatedAtEpochMilli >= current.updatedAtEpochMilli

    private fun reconcileUniqueConflict(candidate: GovernanceDeletionRun): GovernanceStoreResult {
        load(candidate.tenantId, candidate.planId)?.let { current ->
            if (exact(current, candidate)) return GovernanceStoreResult.replayed(current)
        }
        findByIdempotency(candidate.tenantId, candidate.idempotencyKey)?.let { current ->
            if (exact(current, candidate)) return GovernanceStoreResult.replayed(current)
        }
        return GovernanceStoreResult.failed(GovernanceStoreCode.CONFLICT)
    }

    private inline fun <T> jdbcBoundary(action: () -> T): T = try {
        action()
    } catch (failure: GovernanceJdbcPersistenceException) {
        throw failure
    } catch (failure: SQLException) {
        throw GovernanceJdbcPersistenceException(failure)
    }

    private fun readBoundedMemento(result: ResultSet): ByteArray {
        val size = result.getLong("run_memento_size")
        require(size in 1L..GovernanceJdbcCanonicalCodec.MAX_MEMENTO_BYTES.toLong()) {
            "Governance JDBC memento size is invalid."
        }
        val expectedSize = size.toInt()
        val output = ByteArrayOutputStream(expectedSize)
        val buffer = ByteArray(8192)
        result.getBinaryStream("run_memento").use { input ->
            requireNotNull(input) { "Governance JDBC memento is missing." }
            var total = 0
            while (total <= GovernanceJdbcCanonicalCodec.MAX_MEMENTO_BYTES) {
                val read = input.read(buffer, 0, minOf(buffer.size, expectedSize + 1 - total))
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
                require(total <= expectedSize) { "Governance JDBC memento changed while it was read." }
            }
            require(total == expectedSize && input.read() == -1) {
                "Governance JDBC memento length is inconsistent."
            }
        }
        return output.toByteArray()
    }

    private fun conflict(): GovernanceStoreResult {
        metric(GovernanceMetricCode.CAS_CONFLICT)
        return GovernanceStoreResult.failed(GovernanceStoreCode.CONFLICT)
    }

    private fun observeStoreResult(result: GovernanceStoreResult) {
        when (result.code) {
            GovernanceStoreCode.REPLAYED -> metric(GovernanceMetricCode.IDEMPOTENT_REPLAY)
            GovernanceStoreCode.CONFLICT -> metric(GovernanceMetricCode.CAS_CONFLICT)
            GovernanceStoreCode.OUTCOME_UNKNOWN -> metric(GovernanceMetricCode.STORE_OUTCOME_UNKNOWN)
        }
    }

    private fun metric(code: GovernanceMetricCode) {
        try {
            metrics?.record(GovernanceMetric.of(code))
        } catch (_: RuntimeException) {
            // Value-free observability cannot change a governance persistence decision.
        }
    }

    private fun runRowId(tenantId: String, planId: String): String =
        GovernanceJdbcDigests.rowId("flowweft-governance-run-row-v1", tenantId, planId)

    private fun outboxRowId(tenantId: String, recordId: String): String =
        GovernanceJdbcDigests.rowId("flowweft-governance-outbox-row-v1", tenantId, recordId)

    private fun idempotencyDigest(tenantId: String, idempotencyKey: String): String =
        GovernanceJdbcDigests.digest("flowweft-governance-idempotency-v1", tenantId, idempotencyKey)

    private fun planIdDigest(tenantId: String, planId: String): String =
        GovernanceJdbcDigests.digest("flowweft-governance-plan-id-v1", tenantId, planId)

    private fun recordIdDigest(tenantId: String, recordId: String): String =
        GovernanceJdbcDigests.digest("flowweft-governance-record-id-v1", tenantId, recordId)

    private fun claimDigest(tenantId: String, claimId: String): String =
        GovernanceJdbcDigests.digest("flowweft-governance-outbox-claim-v1", tenantId, claimId)

    private fun workerDigest(tenantId: String, workerId: String): String =
        GovernanceJdbcDigests.digest("flowweft-governance-outbox-worker-v1", tenantId, workerId)

    private class RunRow(
        val id: String,
        val planIdDigest: String,
        val idempotencyDigest: String,
        val run: GovernanceDeletionRun,
    )

    private class OutboxRow(
        val id: String,
        val record: GovernanceOutboxRecord,
        val fencingToken: Long,
    )

    companion object {
        const val CONTRACT_VERSION: String = "flowweft.governance.jdbc-persistence.v1"

        private val RUN_PREDICATES = setOf(
            "tenant_id = ? AND plan_id_digest = ?",
            "tenant_id = ? AND idempotency_digest = ?",
        )
        private val SCHEMA_TABLES = setOf(
            "fw_governance_deletion_run",
            "fw_governance_deletion_outbox",
        )
        private val SCHEMA_COLUMNS = setOf("run_version", "fencing_token")
    }
}

internal class GovernanceJdbcPersistenceException(cause: SQLException) :
    IllegalStateException("Governance JDBC persistence operation failed.", cause)
