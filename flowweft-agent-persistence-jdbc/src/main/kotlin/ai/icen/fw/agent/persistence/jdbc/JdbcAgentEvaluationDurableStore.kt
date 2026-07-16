package ai.icen.fw.agent.persistence.jdbc

import ai.icen.fw.agent.runtime.AgentEvaluationCommitResult
import ai.icen.fw.agent.runtime.AgentEvaluationCommitStatus
import ai.icen.fw.agent.runtime.AgentEvaluationCreateResult
import ai.icen.fw.agent.runtime.AgentEvaluationDurableStore
import ai.icen.fw.agent.runtime.AgentEvaluationIdempotencyScope
import ai.icen.fw.agent.runtime.AgentEvaluationLease
import ai.icen.fw.agent.runtime.AgentEvaluationLeaseClaim
import ai.icen.fw.agent.runtime.AgentEvaluationLeaseClaimResult
import ai.icen.fw.agent.runtime.AgentEvaluationLeaseClaimStatus
import ai.icen.fw.agent.runtime.AgentEvaluationRunKey
import ai.icen.fw.agent.runtime.AgentEvaluationRunState
import ai.icen.fw.agent.runtime.AgentEvaluationRunStatus
import ai.icen.fw.agent.runtime.AgentEvaluationStateCommit
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import javax.sql.DataSource

/**
 * Production JDBC implementation of [AgentEvaluationDurableStore]. Every mutation is a single
 * transaction with state-version CAS; worker mutations additionally bind the full fencing lease.
 * Commit exceptions remain outcome-unknown and are never translated into an applied result.
 */
class JdbcAgentEvaluationDurableStore @JvmOverloads constructor(
    dataSource: DataSource,
    dialect: AgentJdbcDialect? = null,
    private val codec: AgentEvaluationStateMementoCodec = AgentEvaluationStateMementoCodec(),
) : AgentEvaluationDurableStore {
    private val transactions = AgentJdbcTransactions(dataSource, dialect)

    override fun create(initialState: AgentEvaluationRunState): AgentEvaluationCreateResult {
        require(initialState.status == AgentEvaluationRunStatus.QUEUED && initialState.stateVersion == 0L &&
            initialState.attempt == 0 && initialState.lease == null
        ) { "Agent evaluation create requires an initial queued state." }
        return transactions.transaction { connection, _ ->
            findIdempotency(connection, initialState.idempotencyScope, true)?.let { evaluationId ->
                return@transaction replay(initialState, requireNotNull(
                    load(connection, AgentEvaluationRunKey(initialState.tenantId, evaluationId), true),
                ) { "Agent evaluation idempotency mapping references a missing run." }.state)
            }

            if (!reserveIdempotency(connection, initialState)) {
                val evaluationId = requireNotNull(findIdempotency(connection, initialState.idempotencyScope, true)) {
                    "Agent evaluation idempotency conflict did not expose its durable mapping."
                }
                return@transaction replay(initialState, requireNotNull(
                    load(connection, AgentEvaluationRunKey(initialState.tenantId, evaluationId), true),
                ) { "Agent evaluation idempotency mapping references a missing run." }.state)
            }

            val memento = codec.encode(initialState)
            connection.prepareStatement(INSERT_RUN_SQL).use { statement ->
                bindInsert(statement, initialState, memento)
                check(statement.executeUpdate() == 1) { "Agent evaluation run insert was not atomic." }
            }
            AgentEvaluationCreateResult(true, initialState)
        }
    }

    private fun replay(
        requested: AgentEvaluationRunState,
        existing: AgentEvaluationRunState,
    ): AgentEvaluationCreateResult {
        require(existing.idempotencyScope == requested.idempotencyScope &&
            existing.requestBindingDigest == requested.requestBindingDigest
        ) {
            "Agent evaluation idempotency key is already bound to a different exact request."
        }
        return AgentEvaluationCreateResult(false, existing)
    }

    override fun load(key: AgentEvaluationRunKey): AgentEvaluationRunState? = transactions.read { connection, _ ->
        load(connection, key, false)?.state
    }

    override fun findByIdempotency(scope: AgentEvaluationIdempotencyScope): AgentEvaluationRunState? =
        transactions.read { connection, _ ->
            val evaluationId = findIdempotency(connection, scope, false) ?: return@read null
            val state = requireNotNull(load(connection, AgentEvaluationRunKey(scope.tenantId, evaluationId), false)) {
                "Agent evaluation idempotency mapping references a missing run."
            }.state
            check(state.idempotencyScope == scope) {
                "Agent evaluation idempotency mapping does not match its durable run."
            }
            state
        }

    override fun claim(claim: AgentEvaluationLeaseClaim): AgentEvaluationLeaseClaimResult =
        transactions.transaction { connection, _ ->
            val persisted = load(connection, claim.key, true)
                ?: return@transaction AgentEvaluationLeaseClaimResult(
                    AgentEvaluationLeaseClaimStatus.MISSING,
                    null,
                )
            val current = persisted.state
            if (current.status.isTerminal()) {
                return@transaction AgentEvaluationLeaseClaimResult(
                    AgentEvaluationLeaseClaimStatus.TERMINAL,
                    current,
                )
            }
            if (claim.requestedAt < current.updatedAt || current.lease?.isCurrent(claim.requestedAt) == true) {
                return@transaction AgentEvaluationLeaseClaimResult(
                    AgentEvaluationLeaseClaimStatus.BUSY,
                    current,
                )
            }
            val fencingToken = Math.addExact(persisted.lastFencingToken, 1L)
            val lease = AgentEvaluationLease(
                claim.leaseId,
                claim.ownerId,
                fencingToken,
                claim.requestedAt,
                Math.addExact(claim.requestedAt, claim.leaseDurationMillis),
            )
            val claimed = current.claimed(lease, claim.requestedAt)
            val memento = codec.encode(claimed)
            connection.prepareStatement(CLAIM_RUN_SQL).use { statement ->
                var index = bindMutable(statement, 1, claimed, fencingToken, memento)
                statement.setString(index++, claim.key.tenantId.value)
                statement.setString(index++, claim.key.evaluationId.value)
                statement.setLong(index++, current.stateVersion)
                statement.setLong(index, persisted.lastFencingToken)
                check(statement.executeUpdate() == 1) { "Agent evaluation lease claim lost its locked CAS row." }
            }
            AgentEvaluationLeaseClaimResult(AgentEvaluationLeaseClaimStatus.ACQUIRED, claimed)
        }

    override fun heartbeat(commit: AgentEvaluationStateCommit): AgentEvaluationCommitResult {
        require(commit.expectedLease != null && commit.nextState.status == AgentEvaluationRunStatus.RUNNING) {
            "Agent evaluation heartbeat requires a running fenced state."
        }
        return mutate(commit, MutationKind.HEARTBEAT)
    }

    override fun complete(commit: AgentEvaluationStateCommit): AgentEvaluationCommitResult {
        require(commit.expectedLease != null && commit.nextState.status == AgentEvaluationRunStatus.COMPLETED) {
            "Agent evaluation completion requires a completed fenced state."
        }
        return mutate(commit, MutationKind.COMPLETE)
    }

    override fun fail(commit: AgentEvaluationStateCommit): AgentEvaluationCommitResult {
        require(commit.nextState.status == AgentEvaluationRunStatus.QUEUED ||
            commit.nextState.status == AgentEvaluationRunStatus.FAILED ||
            commit.nextState.status == AgentEvaluationRunStatus.EXPIRED
        ) { "Agent evaluation failure transition has an unsupported next state." }
        require(commit.expectedLease != null || commit.nextState.status == AgentEvaluationRunStatus.EXPIRED) {
            "Only a pre-claim expiry may fail without a worker lease."
        }
        return mutate(commit, MutationKind.FAIL)
    }

    override fun cancel(commit: AgentEvaluationStateCommit): AgentEvaluationCommitResult {
        require(commit.expectedLease == null && commit.nextState.status == AgentEvaluationRunStatus.CANCELLED) {
            "Agent evaluation cancellation requires trusted CAS without worker authority."
        }
        return mutate(commit, MutationKind.CANCEL)
    }

    override fun recoverable(atTime: Long, limit: Int): List<AgentEvaluationRunState> {
        require(atTime >= 0L && limit in 1..MAX_RECOVERABLE_LIMIT) {
            "Agent evaluation recoverable query bounds are invalid."
        }
        return transactions.read { connection, _ ->
            connection.prepareStatement(RECOVERABLE_SQL).use { statement ->
                statement.setLong(1, atTime)
                statement.setLong(2, atTime)
                statement.setInt(3, limit)
                statement.executeQuery().use { result ->
                    val states = ArrayList<AgentEvaluationRunState>()
                    while (result.next()) states.add(readPersisted(result).state)
                    java.util.Collections.unmodifiableList(states)
                }
            }
        }
    }

    private fun mutate(
        commit: AgentEvaluationStateCommit,
        kind: MutationKind,
    ): AgentEvaluationCommitResult =
        transactions.transaction { connection, _ ->
            val persisted = load(connection, commit.key, true)
                ?: return@transaction AgentEvaluationCommitResult(AgentEvaluationCommitStatus.MISSING, null)
            val current = persisted.state
            if (current.stateVersion != commit.expectedStateVersion) {
                return@transaction AgentEvaluationCommitResult(
                    AgentEvaluationCommitStatus.VERSION_CONFLICT,
                    current,
                )
            }
            val expectedLease = commit.expectedLease
            val requiresCurrentLease = kind != MutationKind.FAIL ||
                commit.nextState.status == AgentEvaluationRunStatus.QUEUED
            val leaseLost = expectedLease != null && (
                current.lease?.matches(expectedLease) != true ||
                    requiresCurrentLease && !expectedLease.isCurrent(commit.committedAt)
            )
            if (leaseLost) {
                return@transaction AgentEvaluationCommitResult(AgentEvaluationCommitStatus.LEASE_LOST, current)
            }
            require(commit.nextState.updatedAt == commit.committedAt) {
                "Agent evaluation commit time does not match its next state."
            }
            requireValidTransition(kind, current, commit)
            requireStableIdentity(current, commit.nextState)
            val memento = codec.encode(commit.nextState)
            val sql = if (expectedLease == null) UPDATE_RUN_SQL else UPDATE_FENCED_RUN_SQL
            connection.prepareStatement(sql).use { statement ->
                var index = bindMutable(statement, 1, commit.nextState, persisted.lastFencingToken, memento)
                statement.setString(index++, commit.key.tenantId.value)
                statement.setString(index++, commit.key.evaluationId.value)
                statement.setLong(index++, commit.expectedStateVersion)
                expectedLease?.let { lease ->
                    statement.setString(index++, lease.leaseId.value)
                    statement.setString(index++, lease.ownerId.value)
                    statement.setLong(index++, lease.fencingToken)
                    statement.setLong(index++, lease.acquiredAt)
                    statement.setLong(index, lease.expiresAt)
                }
                check(statement.executeUpdate() == 1) { "Agent evaluation commit lost its locked CAS row." }
            }
            AgentEvaluationCommitResult(AgentEvaluationCommitStatus.APPLIED, commit.nextState)
        }

    private fun reserveIdempotency(connection: Connection, state: AgentEvaluationRunState): Boolean {
        val savepoint = connection.setSavepoint("fw_agent_evaluation_idempotency")
        return try {
            connection.prepareStatement(INSERT_IDEMPOTENCY_SQL).use { statement ->
                bindIdempotency(statement, state)
                check(statement.executeUpdate() == 1) { "Agent evaluation idempotency reservation was not inserted." }
            }
            connection.releaseSavepoint(savepoint)
            true
        } catch (failure: SQLException) {
            connection.rollback(savepoint)
            if (failure.sqlState?.startsWith("23") != true ||
                findIdempotency(connection, state.idempotencyScope, true) == null
            ) {
                throw failure
            }
            false
        }
    }

    private fun findIdempotency(
        connection: Connection,
        scope: AgentEvaluationIdempotencyScope,
        forUpdate: Boolean,
    ): ai.icen.fw.core.id.Identifier? {
        val sql = SELECT_IDEMPOTENCY_SQL + if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, scope.tenantId.value)
            statement.setString(2, scope.scopeDigest)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    null
                } else {
                    check(result.getString("id") == scope.scopeDigest &&
                        result.getString("tenant_id") == scope.tenantId.value &&
                        result.getString("scope_digest") == scope.scopeDigest &&
                        result.getString("principal_type") == scope.principalType &&
                        result.getString("principal_id") == scope.principalId.value &&
                        result.getString("authorization_revision") == scope.authorizationRevision &&
                        result.getString("suite_id") == scope.suiteId.value &&
                        result.getString("suite_digest") == scope.suiteDigest &&
                        result.getString("provider_snapshot_digest") == scope.providerSnapshotDigest &&
                        result.getString("idempotency_key_digest") == scope.idempotencyKeyDigest
                    ) { "Agent evaluation persisted idempotency scope is inconsistent." }
                    ai.icen.fw.core.id.Identifier(result.getString("evaluation_id"))
                }
            }
        }
    }

    private fun load(
        connection: Connection,
        key: AgentEvaluationRunKey,
        forUpdate: Boolean,
    ): PersistedRun? {
        val sql = SELECT_RUN_SQL + if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, key.tenantId.value)
            statement.setString(2, key.evaluationId.value)
            statement.executeQuery().use { result -> if (result.next()) readPersisted(result) else null }
        }
    }

    private fun readPersisted(result: ResultSet): PersistedRun {
        check(result.getString("memento_schema") == AgentEvaluationStateMementoCodec.SCHEMA) {
            "Persisted Agent evaluation memento schema is unsupported."
        }
        check(result.getInt("memento_format_version") == AgentEvaluationStateMementoCodec.FORMAT_VERSION) {
            "Persisted Agent evaluation memento version is unsupported."
        }
        val state = codec.decode(result.getBytes("memento_payload"), result.getString("memento_digest"))
        val projectedStatus = persistedStatus(result.getString("run_status"))
        check(state.tenantId.value == result.getString("tenant_id") &&
            state.evaluationId.value == result.getString("id") &&
            state.requestId.value == result.getString("request_id") &&
            state.principalType == result.getString("principal_type") &&
            state.principalId.value == result.getString("principal_id") &&
            state.authorizationRevision == result.getString("authorization_revision") &&
            state.suite.suiteId.value == result.getString("suite_id") &&
            state.suite.suiteDigest == result.getString("suite_digest") &&
            state.providerSnapshot.providerId.value == result.getString("provider_id") &&
            state.providerSnapshot.snapshotDigest == result.getString("provider_snapshot_digest") &&
            state.requestBindingDigest == result.getString("request_binding_digest") &&
            state.idempotencyScope.scopeDigest == result.getString("idempotency_scope_digest") &&
            state.status == projectedStatus &&
            state.stateVersion == result.getLong("state_version") &&
            state.attempt == result.getInt("attempt_count") &&
            state.deadlineAt == result.getLong("deadline_time") &&
            state.maximumAttempts == result.getInt("maximum_attempts") &&
            state.createdAt == result.getLong("created_time") &&
            state.updatedAt == result.getLong("updated_time")
        ) { "Persisted Agent evaluation projection does not match its memento." }
        val retainedLease = state.lease
        requireLeaseProjection(retainedLease, result)
        val lastFencingToken = result.getLong("last_fencing_token")
        check(lastFencingToken >= 0L && (retainedLease == null || retainedLease.fencingToken <= lastFencingToken)) {
            "Persisted Agent evaluation fencing sequence is invalid."
        }
        return PersistedRun(state, lastFencingToken)
    }

    private fun requireStableIdentity(current: AgentEvaluationRunState, next: AgentEvaluationRunState) {
        require(current.key() == next.key() && current.requestId == next.requestId &&
            current.idempotencyScope == next.idempotencyScope &&
            current.requestBindingDigest == next.requestBindingDigest &&
            current.suite.suiteDigest == next.suite.suiteDigest &&
            current.providerSnapshot.snapshotDigest == next.providerSnapshot.snapshotDigest &&
            current.createdAt == next.createdAt && current.deadlineAt == next.deadlineAt &&
            current.maximumAttempts == next.maximumAttempts
        ) { "Agent evaluation commit changed immutable durable identity." }
    }

    private fun requireValidTransition(
        kind: MutationKind,
        current: AgentEvaluationRunState,
        commit: AgentEvaluationStateCommit,
    ) {
        val next = commit.nextState
        val expectedLease = commit.expectedLease
        when (kind) {
            MutationKind.HEARTBEAT -> require(
                current.status == AgentEvaluationRunStatus.RUNNING &&
                    next.status == AgentEvaluationRunStatus.RUNNING && expectedLease != null &&
                    next.lease?.leaseId == expectedLease.leaseId &&
                    next.lease?.ownerId == expectedLease.ownerId &&
                    next.lease?.fencingToken == expectedLease.fencingToken &&
                    next.lease?.acquiredAt == expectedLease.acquiredAt &&
                    requireNotNull(next.lease).expiresAt >= expectedLease.expiresAt,
            ) { "Agent evaluation heartbeat transition is invalid." }
            MutationKind.COMPLETE -> require(
                current.status == AgentEvaluationRunStatus.RUNNING &&
                    next.status == AgentEvaluationRunStatus.COMPLETED && expectedLease != null,
            ) { "Agent evaluation completion transition is invalid." }
            MutationKind.FAIL -> require(
                (
                    current.status == AgentEvaluationRunStatus.RUNNING && expectedLease != null &&
                        next.status in setOf(
                            AgentEvaluationRunStatus.QUEUED,
                            AgentEvaluationRunStatus.FAILED,
                        AgentEvaluationRunStatus.EXPIRED,
                    )
                ) || (
                    current.status == AgentEvaluationRunStatus.QUEUED && expectedLease == null &&
                        next.status == AgentEvaluationRunStatus.EXPIRED
                ),
            ) { "Agent evaluation failure transition is invalid." }
            MutationKind.CANCEL -> require(
                !current.status.isTerminal() && expectedLease == null &&
                    next.status == AgentEvaluationRunStatus.CANCELLED,
            ) { "Agent evaluation cancellation transition is invalid." }
        }
    }

    private fun requireLeaseProjection(lease: AgentEvaluationLease?, result: ResultSet) {
        check(lease?.leaseId?.value == result.getString("lease_id") &&
            lease?.ownerId?.value == result.getString("lease_owner_id") &&
            lease?.fencingToken == nullableLong(result, "fencing_token") &&
            lease?.acquiredAt == nullableLong(result, "lease_acquired_time") &&
            lease?.expiresAt == nullableLong(result, "lease_expires_time")
        ) { "Persisted Agent evaluation lease projection does not match its memento." }
    }

    private fun bindInsert(
        statement: PreparedStatement,
        state: AgentEvaluationRunState,
        memento: AgentEvaluationStateMemento,
    ) {
        var index = 1
        statement.setString(index++, state.evaluationId.value)
        statement.setString(index++, state.tenantId.value)
        statement.setString(index++, state.requestId.value)
        statement.setString(index++, state.principalType)
        statement.setString(index++, state.principalId.value)
        statement.setString(index++, state.authorizationRevision)
        statement.setString(index++, state.suite.suiteId.value)
        statement.setString(index++, state.suite.suiteDigest)
        statement.setString(index++, state.providerSnapshot.providerId.value)
        statement.setString(index++, state.providerSnapshot.snapshotDigest)
        statement.setString(index++, state.requestBindingDigest)
        statement.setString(index++, state.idempotencyScope.scopeDigest)
        statement.setString(index++, state.status.name)
        statement.setLong(index++, state.stateVersion)
        statement.setInt(index++, state.attempt)
        statement.setLong(index++, state.deadlineAt)
        statement.setInt(index++, state.maximumAttempts)
        index = bindLease(statement, index, state.lease)
        statement.setLong(index++, 0L)
        statement.setString(index++, AgentEvaluationStateMementoCodec.SCHEMA)
        statement.setInt(index++, memento.formatVersion)
        statement.setString(index++, memento.digest)
        statement.setBytes(index++, memento.payload())
        statement.setLong(index++, state.createdAt)
        statement.setLong(index, state.updatedAt)
    }

    private fun bindMutable(
        statement: PreparedStatement,
        offset: Int,
        state: AgentEvaluationRunState,
        lastFencingToken: Long,
        memento: AgentEvaluationStateMemento,
    ): Int {
        var index = offset
        statement.setString(index++, state.status.name)
        statement.setLong(index++, state.stateVersion)
        statement.setInt(index++, state.attempt)
        index = bindLease(statement, index, state.lease)
        statement.setLong(index++, lastFencingToken)
        statement.setString(index++, AgentEvaluationStateMementoCodec.SCHEMA)
        statement.setInt(index++, memento.formatVersion)
        statement.setString(index++, memento.digest)
        statement.setBytes(index++, memento.payload())
        statement.setLong(index++, state.updatedAt)
        return index
    }

    private fun bindLease(statement: PreparedStatement, offset: Int, lease: AgentEvaluationLease?): Int {
        var index = offset
        setNullableString(statement, index++, lease?.leaseId?.value)
        setNullableString(statement, index++, lease?.ownerId?.value)
        setNullableLong(statement, index++, lease?.fencingToken)
        setNullableLong(statement, index++, lease?.acquiredAt)
        setNullableLong(statement, index++, lease?.expiresAt)
        return index
    }

    private fun bindIdempotency(statement: PreparedStatement, state: AgentEvaluationRunState) {
        val scope = state.idempotencyScope
        statement.setString(1, scope.scopeDigest)
        statement.setString(2, scope.tenantId.value)
        statement.setString(3, scope.scopeDigest)
        statement.setString(4, scope.principalType)
        statement.setString(5, scope.principalId.value)
        statement.setString(6, scope.authorizationRevision)
        statement.setString(7, scope.suiteId.value)
        statement.setString(8, scope.suiteDigest)
        statement.setString(9, scope.providerSnapshotDigest)
        statement.setString(10, scope.idempotencyKeyDigest)
        statement.setString(11, state.evaluationId.value)
        statement.setLong(12, state.createdAt)
        statement.setLong(13, state.createdAt)
    }

    private class PersistedRun(
        val state: AgentEvaluationRunState,
        val lastFencingToken: Long,
    )

    private enum class MutationKind {
        HEARTBEAT,
        COMPLETE,
        FAIL,
        CANCEL,
    }

    private companion object {
        const val MAX_RECOVERABLE_LIMIT = 1_000

        const val INSERT_RUN_SQL = """
            INSERT INTO fw_agent_evaluation_run(
                id, tenant_id, request_id, principal_type, principal_id, authorization_revision,
                suite_id, suite_digest, provider_id, provider_snapshot_digest, request_binding_digest,
                idempotency_scope_digest, run_status, state_version, attempt_count, deadline_time,
                maximum_attempts, lease_id, lease_owner_id, fencing_token, lease_acquired_time,
                lease_expires_time, last_fencing_token, memento_schema, memento_format_version,
                memento_digest, memento_payload, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        const val INSERT_IDEMPOTENCY_SQL = """
            INSERT INTO fw_agent_evaluation_idempotency(
                id, tenant_id, scope_digest, principal_type, principal_id, authorization_revision,
                suite_id, suite_digest, provider_snapshot_digest, idempotency_key_digest,
                evaluation_id, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        const val SELECT_RUN_SQL =
            "SELECT * FROM fw_agent_evaluation_run WHERE tenant_id = ? AND id = ?"

        const val SELECT_IDEMPOTENCY_SQL =
            "SELECT * FROM fw_agent_evaluation_idempotency WHERE tenant_id = ? AND scope_digest = ?"

        const val MUTABLE_SET_SQL = """
            run_status = ?, state_version = ?, attempt_count = ?, lease_id = ?, lease_owner_id = ?,
            fencing_token = ?, lease_acquired_time = ?, lease_expires_time = ?, last_fencing_token = ?,
            memento_schema = ?, memento_format_version = ?, memento_digest = ?, memento_payload = ?,
            updated_time = ?
        """

        const val CLAIM_RUN_SQL = "UPDATE fw_agent_evaluation_run SET $MUTABLE_SET_SQL " +
            "WHERE tenant_id = ? AND id = ? AND state_version = ? AND last_fencing_token = ?"

        const val UPDATE_RUN_SQL = "UPDATE fw_agent_evaluation_run SET $MUTABLE_SET_SQL " +
            "WHERE tenant_id = ? AND id = ? AND state_version = ?"

        const val UPDATE_FENCED_RUN_SQL = UPDATE_RUN_SQL +
            " AND lease_id = ? AND lease_owner_id = ? AND fencing_token = ?" +
            " AND lease_acquired_time = ? AND lease_expires_time = ?"

        const val RECOVERABLE_SQL = """
            SELECT * FROM fw_agent_evaluation_run
            WHERE updated_time <= ? AND (
                run_status = 'QUEUED' OR (run_status = 'RUNNING' AND lease_expires_time <= ?)
            )
            ORDER BY updated_time, tenant_id, id
            LIMIT ?
        """
    }
}

private fun persistedStatus(code: String): AgentEvaluationRunStatus = when (code) {
    "QUEUED" -> AgentEvaluationRunStatus.QUEUED
    "RUNNING" -> AgentEvaluationRunStatus.RUNNING
    "COMPLETED" -> AgentEvaluationRunStatus.COMPLETED
    "FAILED" -> AgentEvaluationRunStatus.FAILED
    "CANCELLED" -> AgentEvaluationRunStatus.CANCELLED
    "EXPIRED" -> AgentEvaluationRunStatus.EXPIRED
    else -> throw IllegalStateException("Persisted Agent evaluation status '$code' is unsupported.")
}

private fun setNullableString(statement: PreparedStatement, index: Int, value: String?) {
    if (value == null) statement.setNull(index, Types.VARCHAR) else statement.setString(index, value)
}

private fun setNullableLong(statement: PreparedStatement, index: Int, value: Long?) {
    if (value == null) statement.setNull(index, Types.BIGINT) else statement.setLong(index, value)
}

private fun nullableLong(result: ResultSet, column: String): Long? =
    result.getLong(column).let { value -> if (result.wasNull()) null else value }
