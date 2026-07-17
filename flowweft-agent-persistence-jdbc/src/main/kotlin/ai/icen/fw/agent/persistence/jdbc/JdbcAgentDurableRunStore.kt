package ai.icen.fw.agent.persistence.jdbc

import ai.icen.fw.agent.api.AgentRunApprovalRequiredEvent
import ai.icen.fw.agent.api.AgentRunEvent
import ai.icen.fw.agent.api.AgentRunMessageEvent
import ai.icen.fw.agent.api.AgentRunStatusChangedEvent
import ai.icen.fw.agent.api.AgentRunUsageEvent
import ai.icen.fw.agent.runtime.AgentDurableMementoCodec
import ai.icen.fw.agent.runtime.AgentDurableRunState
import ai.icen.fw.agent.runtime.AgentDurableStateMemento
import ai.icen.fw.agent.runtime.AgentPendingModelOperation
import ai.icen.fw.agent.runtime.AgentPendingModelPhase
import ai.icen.fw.agent.runtime.AgentPendingOperation
import ai.icen.fw.agent.runtime.AgentPendingOperationMemento
import ai.icen.fw.agent.runtime.AgentPendingToolOperation
import ai.icen.fw.agent.runtime.AgentPendingToolPhase
import ai.icen.fw.agent.runtime.AgentRunCreateCommit
import ai.icen.fw.agent.runtime.AgentRunCreateResult
import ai.icen.fw.agent.runtime.AgentRunCreateStatus
import ai.icen.fw.agent.runtime.AgentRunIdempotencyScope
import ai.icen.fw.agent.runtime.AgentRunKey
import ai.icen.fw.agent.runtime.AgentRunLease
import ai.icen.fw.agent.runtime.AgentRunLeaseClaim
import ai.icen.fw.agent.runtime.AgentRunLeaseClaimResult
import ai.icen.fw.agent.runtime.AgentRunLeaseClaimStatus
import ai.icen.fw.agent.runtime.AgentRunEventMemento
import ai.icen.fw.agent.runtime.AgentRuntimeCheckpointEvent
import ai.icen.fw.agent.runtime.AgentRuntimeIncidentEvent
import ai.icen.fw.agent.runtime.AgentRuntimeStepStatus
import ai.icen.fw.agent.runtime.AgentStoreCommit
import ai.icen.fw.agent.runtime.AgentStoreCommitAuthority
import ai.icen.fw.agent.runtime.AgentStoreCommitResult
import ai.icen.fw.agent.runtime.AgentStoreCommitStatus
import ai.icen.fw.agent.runtime.AgentDurableRunStore
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import javax.sql.DataSource

/**
 * JDBC persistence for the provider-neutral Agent runtime. State, ordered events, owner-scoped
 * idempotency and operation evidence are committed in one transaction. The adapter never invokes
 * a model, tool, authorization provider or reconciliation callback while holding a connection.
 */
class JdbcAgentDurableRunStore @JvmOverloads constructor(
    dataSource: DataSource,
    dialect: AgentJdbcDialect? = null,
    private val codec: AgentDurableMementoCodec = AgentDurableMementoCodec(),
) : AgentDurableRunStore {
    private val transactions = AgentJdbcTransactions(dataSource, dialect)

    override fun create(commit: AgentRunCreateCommit): AgentRunCreateResult {
        val stateMemento = codec.encodeState(commit.state)
        val eventMemento = codec.encodeEvent(commit.initialEvent)
        return transactions.transaction { connection, _ ->
            findIdempotency(connection, commit.idempotencyScope, true)?.let { mapping ->
                return@transaction replay(connection, commit.state, mapping)
            }
            if (!reserveIdempotency(connection, commit.state)) {
                val mapping = requireNotNull(findIdempotency(connection, commit.idempotencyScope, true)) {
                    "Agent idempotency conflict did not expose its durable mapping."
                }
                return@transaction replay(connection, commit.state, mapping)
            }
            insertRun(connection, commit.state, stateMemento, 0L)
            insertEvent(connection, commit.initialEvent, eventMemento)
            AgentRunCreateResult(AgentRunCreateStatus.CREATED, commit.state)
        }
    }

    override fun load(key: AgentRunKey): AgentDurableRunState? = transactions.read { connection, _ ->
        load(connection, key, false)?.also { persisted ->
            validateRunLedgers(connection, persisted.state)
        }?.state
    }

    override fun findByIdempotency(scope: AgentRunIdempotencyScope): AgentDurableRunState? =
        transactions.read { connection, _ ->
            val mapping = findIdempotency(connection, scope, false) ?: return@read null
            val persisted = requireNotNull(load(connection, AgentRunKey(scope.tenantId, mapping.runId), false)) {
                "Agent idempotency mapping references a missing durable run."
            }
            requireMappingMatchesState(mapping, persisted.state)
            validateRunLedgers(connection, persisted.state)
            persisted.state
        }

    override fun claimLease(claim: AgentRunLeaseClaim): AgentRunLeaseClaimResult =
        transactions.transaction { connection, _ ->
            val persisted = load(connection, claim.key, true)
                ?: return@transaction AgentRunLeaseClaimResult(AgentRunLeaseClaimStatus.MISSING, null)
            val current = persisted.state
            validateRunLedgers(connection, current)
            if (current.status.isTerminal()) {
                return@transaction AgentRunLeaseClaimResult(AgentRunLeaseClaimStatus.TERMINAL, current)
            }
            val requestedExpiry = Math.addExact(claim.requestedAt, claim.leaseDurationMillis)
            if (claim.requestedAt < current.updatedAt || claim.requestedAt >= current.deadlineAt ||
                requestedExpiry > current.deadlineAt || current.lease?.isCurrent(claim.requestedAt) == true
            ) {
                return@transaction AgentRunLeaseClaimResult(AgentRunLeaseClaimStatus.BUSY, current)
            }
            val nextFence = Math.addExact(persisted.lastFencingToken, 1L)
            val lease = AgentRunLease(
                claim.leaseId,
                claim.ownerId,
                nextFence,
                claim.requestedAt,
                requestedExpiry,
            )
            val next = current.withClaimedLease(lease, claim.requestedAt)
            val memento = codec.encodeState(next)
            val updated = connection.prepareStatement(claimSql(current.lease)).use { statement ->
                var index = bindMutableRun(statement, 1, next, nextFence, memento)
                index = bindRunIdentity(statement, index, claim.key)
                statement.setLong(index++, current.stateVersion)
                statement.setLong(index++, current.eventSequence)
                statement.setLong(index++, current.checkpointSequence)
                statement.setLong(index++, persisted.lastFencingToken)
                bindLeasePredicate(statement, index, current.lease)
                statement.executeUpdate()
            }
            check(updated == 1) { "Agent lease claim lost its locked state/event/checkpoint CAS row." }
            AgentRunLeaseClaimResult(AgentRunLeaseClaimStatus.ACQUIRED, next)
        }

    override fun commit(commit: AgentStoreCommit): AgentStoreCommitResult {
        val stateMemento = codec.encodeState(commit.nextState)
        val eventMementos = commit.events.map { event -> event to codec.encodeEvent(event) }
        val operationMemento = commit.nextState.pendingOperation?.let(codec::encodeOperation)
        return transactions.transaction { connection, _ ->
            val persisted = load(connection, commit.key, true)
                ?: return@transaction AgentStoreCommitResult(AgentStoreCommitStatus.MISSING, null)
            val current = persisted.state
            validateRunLedgers(connection, current)
            if (current.stateVersion != commit.expectedStateVersion ||
                current.eventSequence != commit.expectedEventSequence
            ) {
                return@transaction AgentStoreCommitResult(AgentStoreCommitStatus.VERSION_CONFLICT, current)
            }
            if (commit.authority == AgentStoreCommitAuthority.WORKER) {
                val expected = requireNotNull(commit.expectedLease)
                if (current.lease?.matches(expected) != true || !expected.isCurrent(commit.committedAt)) {
                    return@transaction AgentStoreCommitResult(AgentStoreCommitStatus.LEASE_LOST, current)
                }
            }
            require(commit.nextState.updatedAt == commit.committedAt) {
                "Agent commit time does not match the next durable state."
            }
            requireValidTransition(current, commit.nextState)

            eventMementos.forEach { (event, memento) -> insertEvent(connection, event, memento) }
            persistOperationTransition(connection, current, commit.nextState, operationMemento)

            val updated = connection.prepareStatement(updateRunSql(current.lease)).use { statement ->
                var index = bindMutableRun(statement, 1, commit.nextState, persisted.lastFencingToken, stateMemento)
                index = bindRunIdentity(statement, index, commit.key)
                statement.setLong(index++, commit.expectedStateVersion)
                statement.setLong(index++, commit.expectedEventSequence)
                statement.setLong(index++, current.checkpointSequence)
                statement.setLong(index++, persisted.lastFencingToken)
                bindLeasePredicate(statement, index, current.lease)
                statement.executeUpdate()
            }
            check(updated == 1) { "Agent commit lost its locked state/event/checkpoint/lease CAS row." }
            AgentStoreCommitResult(AgentStoreCommitStatus.APPLIED, commit.nextState)
        }
    }

    override fun recoverable(atTime: Long, limit: Int): List<AgentDurableRunState> {
        require(atTime >= 0L && limit in 1..MAX_QUERY_LIMIT) { "Agent recoverable query bounds are invalid." }
        return transactions.read { connection, _ ->
            val persisted = connection.prepareStatement(RECOVERABLE_SQL).use { statement ->
                statement.setLong(1, atTime)
                statement.setLong(2, atTime)
                statement.setInt(3, limit)
                statement.executeQuery().use { result ->
                    ArrayList<PersistedRun>().also { rows ->
                        while (result.next()) rows += readRun(result)
                    }
                }
            }
            persisted.forEach { row -> validateRunLedgers(connection, row.state) }
            java.util.Collections.unmodifiableList(persisted.map(PersistedRun::state))
        }
    }

    override fun events(key: AgentRunKey, afterSequence: Long, limit: Int): List<AgentRunEvent> {
        require(afterSequence >= 0L && limit in 1..MAX_QUERY_LIMIT) { "Agent event query bounds are invalid." }
        return transactions.read { connection, _ ->
            val persisted = load(connection, key, false) ?: return@read emptyList()
            validateRunLedgers(connection, persisted.state)
            connection.prepareStatement(EVENTS_SQL).use { statement ->
                statement.setString(1, tenantDigest(key.tenantId.value))
                statement.setString(2, runRecordId(key))
                statement.setString(3, key.tenantId.value)
                statement.setString(4, key.runId.value)
                statement.setLong(5, afterSequence)
                statement.setInt(6, limit)
                statement.executeQuery().use { result ->
                    val events = ArrayList<AgentRunEvent>()
                    var expected = Math.addExact(afterSequence, 1L)
                    while (result.next()) {
                        val event = readEvent(result)
                        check(event.sequence == expected) { "Persisted Agent event sequence contains a gap." }
                        events += event
                        expected = Math.addExact(expected, 1L)
                    }
                    java.util.Collections.unmodifiableList(events)
                }
            }
        }
    }

    private fun replay(
        connection: Connection,
        requested: AgentDurableRunState,
        mapping: IdempotencyMapping,
    ): AgentRunCreateResult {
        require(mapping.idempotencyReplayDigest == requested.idempotencyReplayDigest) {
            "Agent idempotency key is already bound to a different exact request."
        }
        val existing = requireNotNull(load(connection, AgentRunKey(requested.tenantId, mapping.runId), true)) {
            "Agent idempotency mapping references a missing durable run."
        }.state
        requireMappingMatchesState(mapping, existing)
        require(existing.idempotencyReplayDigest == requested.idempotencyReplayDigest) {
            "Agent idempotency replay digest differs from the durable run."
        }
        validateRunLedgers(connection, existing)
        return AgentRunCreateResult(AgentRunCreateStatus.REPLAYED, existing)
    }

    private fun reserveIdempotency(connection: Connection, state: AgentDurableRunState): Boolean {
        val savepoint = connection.setSavepoint("fw_agent_idempotency")
        return try {
            connection.prepareStatement(INSERT_IDEMPOTENCY_SQL).use { statement ->
                bindIdempotency(statement, state)
                check(statement.executeUpdate() == 1) { "Agent idempotency reservation was not inserted." }
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
        scope: AgentRunIdempotencyScope,
        forUpdate: Boolean,
    ): IdempotencyMapping? {
        val sql = SELECT_IDEMPOTENCY_SQL + if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, scope.scopeDigest)
            statement.setString(2, tenantDigest(scope.tenantId.value))
            statement.setString(3, scope.tenantId.value)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    null
                } else {
                    check(result.getString("id") == scope.scopeDigest &&
                        result.getString("tenant_id") == scope.tenantId.value &&
                        result.getString("tenant_key_digest") == tenantDigest(scope.tenantId.value) &&
                        result.getString("principal_type") == scope.principalType &&
                        result.getString("principal_id") == scope.principalId.value &&
                        result.getString("capability_id") == scope.capabilityId.value &&
                        result.getString("idempotency_key_digest") == scope.idempotencyKeyDigest
                    ) { "Persisted Agent idempotency scope projection is inconsistent." }
                    IdempotencyMapping(
                        scope,
                        result.getString("run_record_id"),
                        ai.icen.fw.core.id.Identifier(result.getString("run_id")),
                        result.getString("idempotency_replay_digest"),
                        result.getString("admission_binding_digest"),
                        result.getString("admission_decision_digest"),
                    )
                }
            }
        }
    }

    private fun load(connection: Connection, key: AgentRunKey, forUpdate: Boolean): PersistedRun? {
        val sql = SELECT_RUN_SQL + if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(sql).use { statement ->
            bindRunIdentity(statement, 1, key)
            statement.executeQuery().use { result -> if (result.next()) readRun(result) else null }
        }
    }

    private fun readRun(result: ResultSet): PersistedRun {
        check(result.getString("state_memento_schema") == STATE_SCHEMA) {
            "Persisted Agent state memento schema is unsupported."
        }
        val payload = result.getBytes("state_memento_payload")
        val format = mementoFormat(payload)
        check(result.getInt("state_memento_format_version") == format) {
            "Persisted Agent state memento format projection is inconsistent."
        }
        val state = codec.decodeState(
            AgentDurableStateMemento.restore(payload, result.getString("state_memento_digest")),
        )
        val expectedRecord = runRecordId(AgentRunKey(state.tenantId, state.runId))
        check(result.getString("id") == expectedRecord &&
            result.getString("tenant_id") == state.tenantId.value &&
            result.getString("tenant_key_digest") == tenantDigest(state.tenantId.value) &&
            result.getString("run_id") == state.runId.value &&
            result.getString("principal_type") == state.context.principalType &&
            result.getString("principal_id") == state.context.principalId.value &&
            result.getString("capability_id") == state.capabilityId.value &&
            result.getString("idempotency_scope_digest") == state.idempotencyScope.scopeDigest &&
            result.getString("idempotency_replay_digest") == state.idempotencyReplayDigest &&
            result.getString("admission_binding_digest") == state.admission.bindingDigest &&
            result.getString("admission_decision_digest") == state.admission.decisionDigest &&
            result.getString("run_status") == state.status.name &&
            result.getLong("state_version") == state.stateVersion &&
            result.getLong("event_sequence") == state.eventSequence &&
            result.getLong("checkpoint_sequence") == state.checkpointSequence &&
            result.getLong("deadline_time") == state.deadlineAt &&
            result.getLong("created_time") == state.createdAt &&
            result.getLong("updated_time") == state.updatedAt
        ) { "Persisted Agent run projection does not match its validated memento." }
        requireBudgetProjection(state, result)
        requireLeaseProjection(state.lease, result)
        requireCurrentOperationProjection(state.pendingOperation, result)
        val lastFence = result.getLong("last_fencing_token")
        check(lastFence >= 0L && (state.lease == null || state.lease!!.fencingToken <= lastFence)) {
            "Persisted Agent fencing sequence is invalid."
        }
        return PersistedRun(state, lastFence)
    }

    private fun insertRun(
        connection: Connection,
        state: AgentDurableRunState,
        memento: AgentDurableStateMemento,
        lastFence: Long,
    ) {
        connection.prepareStatement(INSERT_RUN_SQL).use { statement ->
            var index = 1
            statement.setString(index++, runRecordId(AgentRunKey(state.tenantId, state.runId)))
            statement.setString(index++, state.tenantId.value)
            statement.setString(index++, tenantDigest(state.tenantId.value))
            statement.setString(index++, state.runId.value)
            statement.setString(index++, state.context.principalType)
            statement.setString(index++, state.context.principalId.value)
            statement.setString(index++, state.capabilityId.value)
            statement.setString(index++, state.idempotencyScope.scopeDigest)
            statement.setString(index++, state.idempotencyReplayDigest)
            statement.setString(index++, state.admission.bindingDigest)
            statement.setString(index++, state.admission.decisionDigest)
            index = bindMutableRun(statement, index, state, lastFence, memento)
            statement.setLong(index, state.createdAt)
            check(statement.executeUpdate() == 1) { "Agent durable run insert was not atomic." }
        }
    }

    private fun bindMutableRun(
        statement: PreparedStatement,
        offset: Int,
        state: AgentDurableRunState,
        lastFence: Long,
        memento: AgentDurableStateMemento,
    ): Int {
        var index = offset
        statement.setString(index++, state.status.name)
        statement.setLong(index++, state.stateVersion)
        statement.setLong(index++, state.eventSequence)
        statement.setLong(index++, state.checkpointSequence)
        statement.setLong(index++, state.deadlineAt)
        statement.setLong(index++, state.budget.maximumInputTokens)
        statement.setLong(index++, state.budget.maximumOutputTokens)
        statement.setInt(index++, state.budget.maximumModelCalls)
        statement.setInt(index++, state.budget.maximumToolCalls)
        statement.setLong(index++, state.budget.maximumDurationMillis)
        statement.setLong(index++, state.budget.maximumCostMicros)
        statement.setLong(index++, state.usage.inputTokens)
        statement.setLong(index++, state.usage.outputTokens)
        statement.setInt(index++, state.usage.modelCalls)
        statement.setInt(index++, state.usage.toolCalls)
        statement.setLong(index++, state.usage.durationMillis)
        statement.setLong(index++, state.usage.costMicros)
        index = bindLease(statement, index, state.lease)
        statement.setLong(index++, lastFence)
        index = bindCurrentOperation(statement, index, state.pendingOperation)
        statement.setString(index++, STATE_SCHEMA)
        statement.setInt(index++, mementoFormat(memento.payload))
        statement.setString(index++, memento.digest)
        statement.setBytes(index++, memento.payload)
        statement.setLong(index++, state.updatedAt)
        return index
    }

    private fun bindIdempotency(statement: PreparedStatement, state: AgentDurableRunState) {
        val scope = state.idempotencyScope
        val key = AgentRunKey(state.tenantId, state.runId)
        statement.setString(1, scope.scopeDigest)
        statement.setString(2, scope.tenantId.value)
        statement.setString(3, tenantDigest(scope.tenantId.value))
        statement.setString(4, scope.principalType)
        statement.setString(5, scope.principalId.value)
        statement.setString(6, scope.capabilityId.value)
        statement.setString(7, scope.idempotencyKeyDigest)
        statement.setString(8, state.idempotencyReplayDigest)
        statement.setString(9, runRecordId(key))
        statement.setString(10, state.runId.value)
        statement.setString(11, state.admission.bindingDigest)
        statement.setString(12, state.admission.decisionDigest)
        statement.setLong(13, state.createdAt)
        statement.setLong(14, state.createdAt)
    }

    private fun insertEvent(connection: Connection, event: AgentRunEvent, memento: AgentRunEventMemento) {
        val key = AgentRunKey(event.tenantId, event.runId)
        connection.prepareStatement(INSERT_EVENT_SQL).use { statement ->
            statement.setString(1, eventRecordId(key, event.sequence))
            statement.setString(2, event.tenantId.value)
            statement.setString(3, tenantDigest(event.tenantId.value))
            statement.setString(4, runRecordId(key))
            statement.setString(5, event.runId.value)
            statement.setLong(6, event.sequence)
            statement.setString(7, eventType(event))
            statement.setLong(8, event.occurredAt)
            statement.setString(9, EVENT_SCHEMA)
            statement.setInt(10, mementoFormat(memento.payload))
            statement.setString(11, memento.digest)
            statement.setBytes(12, memento.payload)
            statement.setLong(13, event.occurredAt)
            statement.setLong(14, event.occurredAt)
            check(statement.executeUpdate() == 1) { "Agent ordered event insert was not atomic." }
        }
    }

    private fun readEvent(result: ResultSet): AgentRunEvent {
        check(result.getString("event_memento_schema") == EVENT_SCHEMA) {
            "Persisted Agent event memento schema is unsupported."
        }
        val payload = result.getBytes("event_memento_payload")
        check(result.getInt("event_memento_format_version") == mementoFormat(payload)) {
            "Persisted Agent event memento format projection is inconsistent."
        }
        val event = codec.decodeEvent(AgentRunEventMemento.restore(payload, result.getString("event_memento_digest")))
        val key = AgentRunKey(event.tenantId, event.runId)
        check(result.getString("id") == eventRecordId(key, event.sequence) &&
            result.getString("tenant_id") == event.tenantId.value &&
            result.getString("tenant_key_digest") == tenantDigest(event.tenantId.value) &&
            result.getString("run_record_id") == runRecordId(key) &&
            result.getString("run_id") == event.runId.value &&
            result.getLong("event_sequence") == event.sequence &&
            result.getString("event_type") == eventType(event) &&
            result.getLong("occurred_time") == event.occurredAt
        ) { "Persisted Agent event projection does not match its validated memento." }
        return event
    }

    private fun persistOperationTransition(
        connection: Connection,
        current: AgentDurableRunState,
        next: AgentDurableRunState,
        nextMemento: AgentPendingOperationMemento?,
    ) {
        val previous = current.pendingOperation
        val pending = next.pendingOperation
        if (previous != null && (pending == null || previous.operationId != pending.operationId ||
                previous.attempt != pending.attempt)
        ) {
            val outcome = terminalOperationOutcome(previous, next)
            val reconciliation = reconciliationDigest(next, previous.stepId)
            val recordId = operationRecordId(current, previous)
            val evidenceDigest = codec.encodeOperation(previous).digest
            val outcomeBinding = operationOutcomeBinding(
                recordId,
                evidenceDigest,
                outcome,
                reconciliation,
                next.updatedAt,
            )
            val updated = connection.prepareStatement(FINALIZE_OPERATION_SQL).use { statement ->
                statement.setString(1, outcome)
                setNullableString(statement, 2, reconciliation)
                statement.setLong(3, next.updatedAt)
                statement.setString(4, outcomeBinding)
                statement.setLong(5, next.updatedAt)
                statement.setString(6, recordId)
                statement.setString(7, tenantDigest(current.tenantId.value))
                statement.setString(8, runRecordId(AgentRunKey(current.tenantId, current.runId)))
                statement.setString(9, current.tenantId.value)
                statement.setString(10, current.runId.value)
                statement.executeUpdate()
            }
            check(updated == 1) { "Agent operation ledger lost pending evidence before finalization." }
        }
        if (pending != null) {
            upsertOperation(connection, next, pending, requireNotNull(nextMemento))
        }
    }

    private fun upsertOperation(
        connection: Connection,
        state: AgentDurableRunState,
        operation: AgentPendingOperation,
        memento: AgentPendingOperationMemento,
    ) {
        val recordId = operationRecordId(state, operation)
        val key = AgentRunKey(state.tenantId, state.runId)
        val existing = connection.prepareStatement(SELECT_OPERATION_SQL + " FOR UPDATE").use { statement ->
            statement.setString(1, recordId)
            statement.setString(2, tenantDigest(state.tenantId.value))
            statement.setString(3, runRecordId(key))
            statement.setString(4, state.tenantId.value)
            statement.setString(5, state.runId.value)
            statement.executeQuery().use { result -> if (result.next()) readOperation(result, state) else null }
        }
        val projection = operationProjection(operation)
        if (existing == null) {
            connection.prepareStatement(INSERT_OPERATION_SQL).use { statement ->
                bindOperationInsert(statement, state, projection, memento)
                check(statement.executeUpdate() == 1) { "Agent operation ledger insert was not atomic." }
            }
        } else {
            val restored = existing.projection
            check(existing.outcome in ACTIVE_OPERATION_OUTCOMES && projection.outcome in ACTIVE_OPERATION_OUTCOMES &&
                restored.operationId == operation.operationId && restored.attempt == operation.attempt &&
                restored.stepId == operation.stepId && restored.kind == projection.kind &&
                restored.logicalDigest == projection.logicalDigest && restored.providerId == projection.providerId &&
                restored.targetId == projection.targetId && restored.createdAt == projection.createdAt
            ) { "Agent operation update changed immutable operation identity." }
            connection.prepareStatement(UPDATE_OPERATION_SQL).use { statement ->
                var index = bindOperationMutable(statement, 1, projection, memento)
                statement.setString(index++, recordId)
                statement.setString(index++, tenantDigest(state.tenantId.value))
                statement.setString(index++, runRecordId(key))
                statement.setString(index++, state.tenantId.value)
                statement.setString(index, state.runId.value)
                check(statement.executeUpdate() == 1) { "Agent operation ledger update lost its locked row." }
            }
        }
    }

    private fun validateRunLedgers(connection: Connection, state: AgentDurableRunState) {
        val mapping = requireNotNull(findIdempotency(connection, state.idempotencyScope, false)) {
            "Agent durable run has no owner-scoped idempotency mapping."
        }
        requireMappingMatchesState(mapping, state)
        validateEventLedgerHead(connection, state)
        validateOperationLedger(connection, state)
    }

    private fun validateEventLedgerHead(connection: Connection, state: AgentDurableRunState) {
        connection.prepareStatement(EVENT_LEDGER_HEAD_SQL).use { statement ->
            val key = AgentRunKey(state.tenantId, state.runId)
            statement.setString(1, tenantDigest(state.tenantId.value))
            statement.setString(2, runRecordId(key))
            statement.setString(3, state.tenantId.value)
            statement.setString(4, state.runId.value)
            statement.executeQuery().use { result ->
                check(result.next()) { "Agent event-ledger aggregate did not return a result." }
                val count = result.getLong("event_count")
                val first = nullableLong(result, "first_sequence")
                val last = nullableLong(result, "last_sequence")
                check(count == state.eventSequence &&
                    if (state.eventSequence == 0L) {
                        first == null && last == null
                    } else {
                        first == 1L && last == state.eventSequence
                    }
                ) { "Agent event ledger is missing, duplicated, or non-contiguous." }
                check(!result.next()) { "Agent event-ledger aggregate returned multiple results." }
            }
        }
    }

    private fun validateOperationLedger(connection: Connection, state: AgentDurableRunState) {
        val runRecordId = runRecordId(AgentRunKey(state.tenantId, state.runId))
        val records = connection.prepareStatement(SELECT_OPERATIONS_FOR_RUN_SQL).use { statement ->
            statement.setString(1, tenantDigest(state.tenantId.value))
            statement.setString(2, runRecordId)
            statement.setString(3, state.tenantId.value)
            statement.setString(4, state.runId.value)
            statement.setInt(5, MAX_OPERATION_LEDGER_ITEMS + 1)
            statement.executeQuery().use { result ->
                ArrayList<PersistedOperation>().also { values ->
                    while (result.next()) values += readOperation(result, state)
                }
            }
        }
        check(records.size <= MAX_OPERATION_LEDGER_ITEMS) { "Agent operation ledger exceeds its durable bound." }
        records.forEach { record ->
            val step = state.steps.firstOrNull { candidate -> candidate.stepId == record.projection.stepId }
            check(step != null && step.operationId == record.projection.operationId &&
                record.projection.attempt <= step.attempt
            ) { "Agent operation ledger references an unknown run step or future attempt." }
        }
        val pending = state.pendingOperation
        val active = records.filter { record -> record.outcome in ACTIVE_OPERATION_OUTCOMES }
        if (pending != null) {
            val recordId = operationRecordId(state, pending)
            val current = active.singleOrNull()
            check(current != null && current.recordId == recordId &&
                current.projection.operationDigest == pending.operationDigest &&
                current.projection.operationId == pending.operationId && current.projection.attempt == pending.attempt
            ) { "Agent current operation differs from its durable ledger evidence." }
        } else {
            check(active.isEmpty()) {
                "Agent run cleared pending work while its operation ledger remains active."
            }
        }
    }

    private fun readOperation(result: ResultSet, state: AgentDurableRunState): PersistedOperation {
        check(result.getString("operation_memento_schema") == OPERATION_SCHEMA) {
            "Persisted Agent operation memento schema is unsupported."
        }
        val payload = result.getBytes("operation_memento_payload")
        check(result.getInt("operation_memento_format_version") == mementoFormat(payload)) {
            "Persisted Agent operation memento format projection is inconsistent."
        }
        val operation = codec.decodeOperation(
            AgentPendingOperationMemento.restore(payload, result.getString("operation_memento_digest")),
        )
        val projection = operationProjection(operation)
        val key = AgentRunKey(state.tenantId, state.runId)
        val recordId = operationRecordId(state, operation)
        val outcome = result.getString("operation_outcome")
        check(result.getString("id") == recordId &&
            result.getString("tenant_id") == state.tenantId.value &&
            result.getString("tenant_key_digest") == tenantDigest(state.tenantId.value) &&
            result.getString("run_record_id") == runRecordId(key) &&
            result.getString("run_id") == state.runId.value &&
            result.getString("operation_id") == projection.operationId.value &&
            result.getString("step_id") == projection.stepId.value &&
            result.getInt("attempt_count") == projection.attempt &&
            result.getString("operation_kind") == projection.kind &&
            result.getString("operation_phase") == projection.phase &&
            result.getString("logical_operation_digest") == projection.logicalDigest &&
            result.getString("operation_digest") == projection.operationDigest &&
            result.getString("checkpoint_id") == projection.checkpointId.value &&
            result.getString("claimed_lease_id") == projection.claimedLeaseId?.value &&
            result.getString("provider_id") == projection.providerId &&
            result.getString("target_id") == projection.targetId &&
            result.getString("request_id") == projection.requestId &&
            result.getString("invocation_id") == projection.invocationId &&
            result.getString("execution_context_id") == projection.executionContextId &&
            result.getString("execution_context_receipt_id") == projection.executionReceiptId &&
            result.getString("execution_context_receipt_status") == projection.executionReceiptStatus &&
            result.getString("dispatch_fence_id") == projection.dispatchFenceId &&
            result.getString("dispatch_fence_binding_digest") == projection.dispatchFenceDigest &&
            result.getString("dispatch_receipt_id") == projection.dispatchReceiptId &&
            result.getString("dispatch_receipt_status") == projection.dispatchReceiptStatus &&
            result.getString("dispatch_provider_revision") == projection.dispatchProviderRevision &&
            nullableLong(result, "dispatch_consumed_time") == projection.dispatchConsumedAt &&
            nullableLong(result, "dispatched_time") == projection.dispatchedAt &&
            nullableLong(result, "reserved_cost_micros") == projection.reservedCostMicros &&
            nullableLong(result, "reserved_duration_millis") == projection.reservedDurationMillis &&
            result.getLong("created_time") == projection.createdAt &&
            result.getLong("evidence_updated_time") == projection.updatedAt &&
            result.getLong("updated_time") >= projection.updatedAt
        ) { "Persisted Agent operation projection does not match its validated memento." }
        val reconciliation = result.getString("reconciliation_evidence_digest")
        val outcomeTime = nullableLong(result, "outcome_time")
        val outcomeBinding = result.getString("outcome_binding_digest")
        if (outcome in ACTIVE_OPERATION_OUTCOMES) {
            check(outcome == projection.outcome && reconciliation == projection.reconciliationDigest &&
                outcomeTime == null && outcomeBinding == null && result.getLong("updated_time") == projection.updatedAt
            ) { "Active Agent operation outcome projection is inconsistent." }
        } else {
            check(outcome in TERMINAL_OPERATION_OUTCOMES && outcomeTime != null && outcomeBinding != null &&
                result.getLong("updated_time") == outcomeTime &&
                outcomeBinding == operationOutcomeBinding(
                    recordId,
                    result.getString("operation_memento_digest"),
                    outcome,
                    reconciliation,
                    outcomeTime,
                )
            ) { "Terminal Agent operation outcome evidence is inconsistent." }
        }
        return PersistedOperation(recordId, projection, outcome)
    }

    private fun bindOperationInsert(
        statement: PreparedStatement,
        state: AgentDurableRunState,
        projection: OperationProjection,
        memento: AgentPendingOperationMemento,
    ) {
        val key = AgentRunKey(state.tenantId, state.runId)
        var index = 1
        statement.setString(index++, operationRecordId(state, projection.operation))
        statement.setString(index++, state.tenantId.value)
        statement.setString(index++, tenantDigest(state.tenantId.value))
        statement.setString(index++, runRecordId(key))
        statement.setString(index++, state.runId.value)
        statement.setString(index++, projection.operationId.value)
        statement.setString(index++, projection.stepId.value)
        statement.setInt(index++, projection.attempt)
        statement.setString(index++, projection.kind)
        index = bindOperationMutable(statement, index, projection, memento)
        statement.setLong(index, projection.createdAt)
    }

    private fun bindOperationMutable(
        statement: PreparedStatement,
        offset: Int,
        projection: OperationProjection,
        memento: AgentPendingOperationMemento,
    ): Int {
        var index = offset
        statement.setString(index++, projection.phase)
        statement.setString(index++, projection.outcome)
        statement.setString(index++, projection.logicalDigest)
        statement.setString(index++, projection.operationDigest)
        statement.setString(index++, projection.checkpointId.value)
        setNullableString(statement, index++, projection.claimedLeaseId?.value)
        statement.setString(index++, projection.providerId)
        statement.setString(index++, projection.targetId)
        setNullableString(statement, index++, projection.requestId)
        setNullableString(statement, index++, projection.invocationId)
        setNullableString(statement, index++, projection.executionContextId)
        setNullableString(statement, index++, projection.executionReceiptId)
        setNullableString(statement, index++, projection.executionReceiptStatus)
        setNullableString(statement, index++, projection.dispatchFenceId)
        setNullableString(statement, index++, projection.dispatchFenceDigest)
        setNullableString(statement, index++, projection.dispatchReceiptId)
        setNullableString(statement, index++, projection.dispatchReceiptStatus)
        setNullableString(statement, index++, projection.dispatchProviderRevision)
        setNullableLong(statement, index++, projection.dispatchConsumedAt)
        setNullableLong(statement, index++, projection.dispatchedAt)
        setNullableLong(statement, index++, projection.reservedCostMicros)
        setNullableLong(statement, index++, projection.reservedDurationMillis)
        setNullableString(statement, index++, projection.reconciliationDigest)
        statement.setNull(index++, Types.BIGINT)
        statement.setNull(index++, Types.CHAR)
        statement.setString(index++, OPERATION_SCHEMA)
        statement.setInt(index++, mementoFormat(memento.payload))
        statement.setString(index++, memento.digest)
        statement.setBytes(index++, memento.payload)
        statement.setLong(index++, projection.updatedAt)
        statement.setLong(index++, projection.updatedAt)
        return index
    }

    private fun operationProjection(operation: AgentPendingOperation): OperationProjection = when (operation) {
        is AgentPendingModelOperation -> OperationProjection(
            operation,
            operation.operationId,
            operation.stepId,
            operation.attempt,
            "MODEL",
            operation.phase.name,
            if (operation.phase == AgentPendingModelPhase.RECONCILIATION_REQUIRED) {
                "RECONCILIATION_REQUIRED"
            } else {
                "PENDING"
            },
            operation.operationDigest,
            operation.operationDigest,
            operation.checkpointId,
            operation.claimedLeaseId,
            operation.descriptor.providerId.value,
            operation.descriptor.modelId.value,
            operation.requestId.value,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            if (operation.phase == AgentPendingModelPhase.RECONCILIATION_REQUIRED) {
                jdbcDigest(
                    "flowweft.agent.jdbc.reconciliation-operation.v1",
                    operation.operationDigest,
                )
            } else {
                null
            },
            operation.createdAt,
            operation.updatedAt,
        )
        is AgentPendingToolOperation -> OperationProjection(
            operation,
            operation.operationId,
            operation.stepId,
            operation.attempt,
            "TOOL",
            operation.phase.name,
            if (operation.phase == AgentPendingToolPhase.RECONCILIATION_REQUIRED) {
                "RECONCILIATION_REQUIRED"
            } else {
                "PENDING"
            },
            operation.plan.planDigest,
            operation.operationDigest,
            operation.checkpointId,
            operation.claimedLeaseId,
            operation.plan.descriptor.providerId.value,
            operation.plan.descriptor.toolId.value,
            operation.preflightRequest.requestId.value,
            operation.invocationId?.value,
            operation.preflightRequest.executionContextId.value,
            operation.consumption?.receiptId?.value,
            operation.consumption?.status?.name,
            operation.dispatchFenceRequest?.fenceId?.value,
            operation.dispatchFenceRequest?.bindingDigest,
            operation.dispatchFenceConsumption?.receiptId?.value,
            operation.dispatchFenceConsumption?.status?.name,
            operation.dispatchFenceConsumption?.providerRevision,
            operation.dispatchFenceConsumption?.consumedAt,
            operation.toolDispatchedAt,
            operation.reservedCostMicros,
            operation.reservedDurationMillis,
            if (operation.phase == AgentPendingToolPhase.RECONCILIATION_REQUIRED) {
                jdbcDigest(
                    "flowweft.agent.jdbc.reconciliation-operation.v1",
                    operation.operationDigest,
                )
            } else {
                null
            },
            operation.createdAt,
            operation.updatedAt,
        )
        else -> throw IllegalArgumentException(
            "Agent pending operation type '${operation.javaClass.name}' is not persistable.",
        )
    }

    private fun terminalOperationOutcome(operation: AgentPendingOperation, next: AgentDurableRunState): String {
        val step = next.steps.firstOrNull { candidate -> candidate.stepId == operation.stepId }
        return when (step?.status) {
            AgentRuntimeStepStatus.COMPLETED -> "COMPLETED"
            AgentRuntimeStepStatus.FAILED -> "FAILED"
            else -> "SUPERSEDED"
        }
    }

    private fun reconciliationDigest(state: AgentDurableRunState, stepId: ai.icen.fw.core.id.Identifier): String? {
        val incidents = state.incidents.filter { incident -> incident.stepId == stepId }
            .sortedBy { incident -> incident.incidentId.value }
        if (incidents.isEmpty()) return null
        val values = ArrayList<String>()
        incidents.forEach { incident ->
            values += incident.incidentId.value
            values += incident.code
            values += incident.status.name
            values += incident.retryable.toString()
            values += incident.createdAt.toString()
            values += incident.resolvedAt?.toString() ?: "-"
        }
        return jdbcDigest("flowweft.agent.jdbc.reconciliation-evidence.v1", *values.toTypedArray())
    }

    private fun requireValidTransition(current: AgentDurableRunState, next: AgentDurableRunState) {
        require(current.runId == next.runId && current.tenantId == next.tenantId &&
            current.context.principalId == next.context.principalId &&
            current.context.principalType == next.context.principalType &&
            current.context.requestId == next.context.requestId &&
            current.context.initiatedAt == next.context.initiatedAt &&
            current.context.locale == next.context.locale && current.capabilityId == next.capabilityId &&
            current.idempotencyScope.scopeDigest == next.idempotencyScope.scopeDigest &&
            current.idempotencyReplayDigest == next.idempotencyReplayDigest &&
            current.admission.decisionDigest == next.admission.decisionDigest &&
            current.createdAt == next.createdAt && current.deadlineAt == next.deadlineAt
        ) { "Agent commit changed immutable durable identity or admission evidence." }
        require(sameBudget(current, next)) { "Agent commit changed its admitted budget." }
        require(next.stateVersion == current.stateVersion + 1L &&
            next.eventSequence >= current.eventSequence &&
            next.checkpointSequence >= current.checkpointSequence && next.updatedAt >= current.updatedAt
        ) { "Agent commit regressed a durable sequence or timestamp." }
        require(current.status == next.status || current.status.canTransitionTo(next.status)) {
            "Agent commit contains an invalid run-status transition."
        }
        require(next.messages.size >= current.messages.size && current.messages.indices.all { index ->
            current.messages[index].id == next.messages[index].id &&
                current.messages[index].bindingDigest == next.messages[index].bindingDigest
        }) { "Agent commit removed or rewrote durable messages." }
        require(next.steps.size >= current.steps.size && current.steps.indices.all { index ->
            val before = current.steps[index]
            val after = next.steps[index]
            before.stepId == after.stepId && before.operationId == after.operationId &&
                before.kind == after.kind && before.createdAt == after.createdAt
        }) { "Agent commit removed or rewrote durable step identity." }
        require(next.checkpoints.size >= current.checkpoints.size && current.checkpoints.indices.all { index ->
            current.checkpoints[index].checkpointId == next.checkpoints[index].checkpointId &&
                current.checkpoints[index].checkpointDigest == next.checkpoints[index].checkpointDigest
        }) { "Agent commit removed or rewrote durable checkpoint evidence." }
        requireUsageMonotonic(current, next)
    }

    private fun sameBudget(current: AgentDurableRunState, next: AgentDurableRunState): Boolean =
        current.budget.maximumInputTokens == next.budget.maximumInputTokens &&
            current.budget.maximumOutputTokens == next.budget.maximumOutputTokens &&
            current.budget.maximumModelCalls == next.budget.maximumModelCalls &&
            current.budget.maximumToolCalls == next.budget.maximumToolCalls &&
            current.budget.maximumDurationMillis == next.budget.maximumDurationMillis &&
            current.budget.maximumCostMicros == next.budget.maximumCostMicros

    private fun requireUsageMonotonic(current: AgentDurableRunState, next: AgentDurableRunState) {
        require(next.usage.inputTokens >= current.usage.inputTokens &&
            next.usage.outputTokens >= current.usage.outputTokens &&
            next.usage.modelCalls >= current.usage.modelCalls &&
            next.usage.toolCalls >= current.usage.toolCalls &&
            next.usage.durationMillis >= current.usage.durationMillis &&
            next.usage.costMicros >= current.usage.costMicros &&
            current.usage.additionalUnits.all { (name, value) ->
                (next.usage.additionalUnits[name] ?: -1L) >= value
            }
        ) { "Agent commit regressed cumulative usage." }
    }

    private fun requireBudgetProjection(state: AgentDurableRunState, result: ResultSet) {
        check(result.getLong("budget_input_tokens") == state.budget.maximumInputTokens &&
            result.getLong("budget_output_tokens") == state.budget.maximumOutputTokens &&
            result.getInt("budget_model_calls") == state.budget.maximumModelCalls &&
            result.getInt("budget_tool_calls") == state.budget.maximumToolCalls &&
            result.getLong("budget_duration_millis") == state.budget.maximumDurationMillis &&
            result.getLong("budget_cost_micros") == state.budget.maximumCostMicros &&
            result.getLong("usage_input_tokens") == state.usage.inputTokens &&
            result.getLong("usage_output_tokens") == state.usage.outputTokens &&
            result.getInt("usage_model_calls") == state.usage.modelCalls &&
            result.getInt("usage_tool_calls") == state.usage.toolCalls &&
            result.getLong("usage_duration_millis") == state.usage.durationMillis &&
            result.getLong("usage_cost_micros") == state.usage.costMicros
        ) { "Persisted Agent budget or usage projection does not match its memento." }
    }

    private fun requireLeaseProjection(lease: AgentRunLease?, result: ResultSet) {
        check(result.getString("lease_id") == lease?.leaseId?.value &&
            result.getString("lease_owner_id") == lease?.ownerId?.value &&
            nullableLong(result, "fencing_token") == lease?.fencingToken &&
            nullableLong(result, "lease_acquired_time") == lease?.acquiredAt &&
            nullableLong(result, "lease_expires_time") == lease?.expiresAt
        ) { "Persisted Agent lease projection does not match its memento." }
    }

    private fun requireCurrentOperationProjection(operation: AgentPendingOperation?, result: ResultSet) {
        check(result.getString("current_operation_id") == operation?.operationId?.value &&
            nullableInt(result, "current_operation_attempt") == operation?.attempt &&
            result.getString("current_operation_kind") == operation?.let(::operationKind) &&
            result.getString("current_operation_phase") == operation?.let(::operationPhase) &&
            result.getString("current_operation_digest") == operation?.operationDigest &&
            result.getString("current_checkpoint_id") == operation?.checkpointId?.value
        ) { "Persisted Agent current-operation projection does not match its memento." }
    }

    private fun requireMappingMatchesState(mapping: IdempotencyMapping, state: AgentDurableRunState) {
        check(mapping.scope.scopeDigest == state.idempotencyScope.scopeDigest &&
            mapping.runRecordId == runRecordId(AgentRunKey(state.tenantId, state.runId)) &&
            mapping.runId == state.runId && mapping.idempotencyReplayDigest == state.idempotencyReplayDigest &&
            mapping.admissionBindingDigest == state.admission.bindingDigest &&
            mapping.admissionDecisionDigest == state.admission.decisionDigest
        ) { "Agent idempotency mapping does not match its durable run." }
    }

    private fun bindRunIdentity(statement: PreparedStatement, offset: Int, key: AgentRunKey): Int {
        var index = offset
        statement.setString(index++, runRecordId(key))
        statement.setString(index++, tenantDigest(key.tenantId.value))
        statement.setString(index++, key.tenantId.value)
        statement.setString(index++, key.runId.value)
        return index
    }

    private fun bindLease(statement: PreparedStatement, offset: Int, lease: AgentRunLease?): Int {
        var index = offset
        setNullableString(statement, index++, lease?.leaseId?.value)
        setNullableString(statement, index++, lease?.ownerId?.value)
        setNullableLong(statement, index++, lease?.fencingToken)
        setNullableLong(statement, index++, lease?.acquiredAt)
        setNullableLong(statement, index++, lease?.expiresAt)
        return index
    }

    private fun bindCurrentOperation(
        statement: PreparedStatement,
        offset: Int,
        operation: AgentPendingOperation?,
    ): Int {
        var index = offset
        setNullableString(statement, index++, operation?.operationId?.value)
        setNullableInt(statement, index++, operation?.attempt)
        setNullableString(statement, index++, operation?.let(::operationKind))
        setNullableString(statement, index++, operation?.let(::operationPhase))
        setNullableString(statement, index++, operation?.operationDigest)
        setNullableString(statement, index++, operation?.checkpointId?.value)
        return index
    }

    private fun bindLeasePredicate(statement: PreparedStatement, offset: Int, lease: AgentRunLease?): Int {
        if (lease == null) return offset
        var index = offset
        statement.setString(index++, lease.leaseId.value)
        statement.setString(index++, lease.ownerId.value)
        statement.setLong(index++, lease.fencingToken)
        statement.setLong(index++, lease.acquiredAt)
        statement.setLong(index++, lease.expiresAt)
        return index
    }

    private fun operationKind(operation: AgentPendingOperation): String = when (operation) {
        is AgentPendingModelOperation -> "MODEL"
        is AgentPendingToolOperation -> "TOOL"
        else -> throw IllegalArgumentException("Agent pending operation type is unsupported.")
    }

    private fun operationPhase(operation: AgentPendingOperation): String = when (operation) {
        is AgentPendingModelOperation -> operation.phase.name
        is AgentPendingToolOperation -> operation.phase.name
        else -> throw IllegalArgumentException("Agent pending operation type is unsupported.")
    }

    private fun eventType(event: AgentRunEvent): String = when (event) {
        is AgentRunStatusChangedEvent -> "STATUS_CHANGED"
        is AgentRunMessageEvent -> "MESSAGE"
        is AgentRunUsageEvent -> "USAGE"
        is AgentRunApprovalRequiredEvent -> "APPROVAL_REQUIRED"
        is AgentRuntimeCheckpointEvent -> "CHECKPOINT"
        is AgentRuntimeIncidentEvent -> "INCIDENT"
        else -> throw IllegalArgumentException("Agent event type '${event.javaClass.name}' is not persistable.")
    }

    private fun runRecordId(key: AgentRunKey): String =
        jdbcDigest("flowweft.agent.jdbc.run-record.v1", key.tenantId.value, key.runId.value)

    private fun eventRecordId(key: AgentRunKey, sequence: Long): String =
        jdbcDigest("flowweft.agent.jdbc.event-record.v1", key.tenantId.value, key.runId.value, sequence.toString())

    private fun operationRecordId(state: AgentDurableRunState, operation: AgentPendingOperation): String =
        jdbcDigest(
            "flowweft.agent.jdbc.operation-record.v1",
            state.tenantId.value,
            state.runId.value,
            operation.operationId.value,
            operation.attempt.toString(),
        )

    private fun tenantDigest(tenantId: String): String = jdbcDigest("flowweft.agent.jdbc.tenant.v1", tenantId)

    private fun operationOutcomeBinding(
        recordId: String,
        evidenceDigest: String,
        outcome: String,
        reconciliationDigest: String?,
        outcomeTime: Long,
    ): String = jdbcDigest(
        "flowweft.agent.jdbc.operation-outcome.v1",
        recordId,
        evidenceDigest,
        outcome,
        reconciliationDigest ?: "-",
        outcomeTime.toString(),
    )

    private fun claimSql(lease: AgentRunLease?): String =
        "UPDATE fw_agent_run SET $MUTABLE_RUN_SET_SQL " + RUN_CAS_SQL + leasePredicateSql(lease)

    private fun updateRunSql(lease: AgentRunLease?): String = claimSql(lease)

    private fun leasePredicateSql(lease: AgentRunLease?): String = if (lease == null) {
        " AND lease_id IS NULL AND lease_owner_id IS NULL AND fencing_token IS NULL" +
            " AND lease_acquired_time IS NULL AND lease_expires_time IS NULL"
    } else {
        " AND lease_id = ? AND lease_owner_id = ? AND fencing_token = ?" +
            " AND lease_acquired_time = ? AND lease_expires_time = ?"
    }

    private class PersistedRun(val state: AgentDurableRunState, val lastFencingToken: Long)

    private class IdempotencyMapping(
        val scope: AgentRunIdempotencyScope,
        val runRecordId: String,
        val runId: ai.icen.fw.core.id.Identifier,
        val idempotencyReplayDigest: String,
        val admissionBindingDigest: String,
        val admissionDecisionDigest: String,
    )

    private class PersistedOperation(
        val recordId: String,
        val projection: OperationProjection,
        val outcome: String,
    )

    private data class OperationProjection(
        val operation: AgentPendingOperation,
        val operationId: ai.icen.fw.core.id.Identifier,
        val stepId: ai.icen.fw.core.id.Identifier,
        val attempt: Int,
        val kind: String,
        val phase: String,
        val outcome: String,
        val logicalDigest: String,
        val operationDigest: String,
        val checkpointId: ai.icen.fw.core.id.Identifier,
        val claimedLeaseId: ai.icen.fw.core.id.Identifier?,
        val providerId: String,
        val targetId: String,
        val requestId: String?,
        val invocationId: String?,
        val executionContextId: String?,
        val executionReceiptId: String?,
        val executionReceiptStatus: String?,
        val dispatchFenceId: String?,
        val dispatchFenceDigest: String?,
        val dispatchReceiptId: String?,
        val dispatchReceiptStatus: String?,
        val dispatchProviderRevision: String?,
        val dispatchConsumedAt: Long?,
        val dispatchedAt: Long?,
        val reservedCostMicros: Long?,
        val reservedDurationMillis: Long?,
        val reconciliationDigest: String?,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private companion object {
        const val STATE_SCHEMA = "agent-durable-run"
        const val EVENT_SCHEMA = "agent-run-event"
        const val OPERATION_SCHEMA = "agent-pending-operation"
        const val MAX_QUERY_LIMIT = 1_000
        const val MAX_OPERATION_LEDGER_ITEMS = 1_024
        val ACTIVE_OPERATION_OUTCOMES = setOf("PENDING", "RECONCILIATION_REQUIRED")
        val TERMINAL_OPERATION_OUTCOMES = setOf("COMPLETED", "FAILED", "SUPERSEDED")

        const val INSERT_RUN_SQL = """
            INSERT INTO fw_agent_run(
                id, tenant_id, tenant_key_digest, run_id, principal_type, principal_id, capability_id,
                idempotency_scope_digest, idempotency_replay_digest, admission_binding_digest,
                admission_decision_digest, run_status, state_version, event_sequence, checkpoint_sequence,
                deadline_time, budget_input_tokens, budget_output_tokens, budget_model_calls,
                budget_tool_calls, budget_duration_millis, budget_cost_micros, usage_input_tokens,
                usage_output_tokens, usage_model_calls, usage_tool_calls, usage_duration_millis,
                usage_cost_micros, lease_id, lease_owner_id, fencing_token, lease_acquired_time,
                lease_expires_time, last_fencing_token, current_operation_id, current_operation_attempt,
                current_operation_kind, current_operation_phase, current_operation_digest,
                current_checkpoint_id, state_memento_schema, state_memento_format_version,
                state_memento_digest, state_memento_payload, updated_time, created_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        const val INSERT_IDEMPOTENCY_SQL = """
            INSERT INTO fw_agent_idempotency(
                id, tenant_id, tenant_key_digest, principal_type, principal_id, capability_id,
                idempotency_key_digest, idempotency_replay_digest, run_record_id, run_id,
                admission_binding_digest, admission_decision_digest, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        const val SELECT_IDEMPOTENCY_SQL = """
            SELECT * FROM fw_agent_idempotency
            WHERE id = ? AND tenant_key_digest = ? AND tenant_id = ?
        """

        const val SELECT_RUN_SQL = """
            SELECT * FROM fw_agent_run
            WHERE id = ? AND tenant_key_digest = ? AND tenant_id = ? AND run_id = ?
        """

        const val MUTABLE_RUN_SET_SQL = """
            run_status = ?, state_version = ?, event_sequence = ?, checkpoint_sequence = ?, deadline_time = ?,
            budget_input_tokens = ?, budget_output_tokens = ?, budget_model_calls = ?, budget_tool_calls = ?,
            budget_duration_millis = ?, budget_cost_micros = ?, usage_input_tokens = ?, usage_output_tokens = ?,
            usage_model_calls = ?, usage_tool_calls = ?, usage_duration_millis = ?, usage_cost_micros = ?,
            lease_id = ?, lease_owner_id = ?, fencing_token = ?, lease_acquired_time = ?, lease_expires_time = ?,
            last_fencing_token = ?, current_operation_id = ?, current_operation_attempt = ?,
            current_operation_kind = ?, current_operation_phase = ?, current_operation_digest = ?,
            current_checkpoint_id = ?, state_memento_schema = ?, state_memento_format_version = ?,
            state_memento_digest = ?, state_memento_payload = ?, updated_time = ?
        """

        const val RUN_CAS_SQL = """
            WHERE id = ? AND tenant_key_digest = ? AND tenant_id = ? AND run_id = ?
              AND state_version = ? AND event_sequence = ? AND checkpoint_sequence = ?
              AND last_fencing_token = ?
        """

        const val INSERT_EVENT_SQL = """
            INSERT INTO fw_agent_event(
                id, tenant_id, tenant_key_digest, run_record_id, run_id, event_sequence, event_type,
                occurred_time, event_memento_schema, event_memento_format_version, event_memento_digest,
                event_memento_payload, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        const val EVENTS_SQL = """
            SELECT * FROM fw_agent_event
            WHERE tenant_key_digest = ? AND run_record_id = ? AND tenant_id = ? AND run_id = ?
              AND event_sequence > ?
            ORDER BY event_sequence
            LIMIT ?
        """

        const val EVENT_LEDGER_HEAD_SQL = """
            SELECT COUNT(*) AS event_count, MIN(event_sequence) AS first_sequence,
                   MAX(event_sequence) AS last_sequence
            FROM fw_agent_event
            WHERE tenant_key_digest = ? AND run_record_id = ? AND tenant_id = ? AND run_id = ?
        """

        const val RECOVERABLE_SQL = """
            SELECT * FROM fw_agent_run
            WHERE updated_time <= ?
              AND run_status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')
              AND (lease_expires_time IS NULL OR lease_expires_time <= ?)
            ORDER BY updated_time, tenant_key_digest, id
            LIMIT ?
        """

        const val SELECT_OPERATION_SQL =
            "SELECT * FROM fw_agent_operation WHERE id = ? AND tenant_key_digest = ?" +
                " AND run_record_id = ? AND tenant_id = ? AND run_id = ?"

        const val INSERT_OPERATION_SQL = """
            INSERT INTO fw_agent_operation(
                id, tenant_id, tenant_key_digest, run_record_id, run_id, operation_id, step_id,
                attempt_count, operation_kind, operation_phase, operation_outcome, logical_operation_digest,
                operation_digest, checkpoint_id, claimed_lease_id, provider_id, target_id, request_id,
                invocation_id, execution_context_id, execution_context_receipt_id,
                execution_context_receipt_status, dispatch_fence_id, dispatch_fence_binding_digest,
                dispatch_receipt_id, dispatch_receipt_status, dispatch_provider_revision,
                dispatch_consumed_time, dispatched_time, reserved_cost_micros, reserved_duration_millis,
                reconciliation_evidence_digest, outcome_time, outcome_binding_digest,
                operation_memento_schema, operation_memento_format_version, operation_memento_digest,
                operation_memento_payload, evidence_updated_time,
                updated_time, created_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        const val UPDATE_OPERATION_SQL = """
            UPDATE fw_agent_operation SET
                operation_phase = ?, operation_outcome = ?, logical_operation_digest = ?, operation_digest = ?,
                checkpoint_id = ?, claimed_lease_id = ?, provider_id = ?, target_id = ?, request_id = ?,
                invocation_id = ?, execution_context_id = ?, execution_context_receipt_id = ?,
                execution_context_receipt_status = ?, dispatch_fence_id = ?, dispatch_fence_binding_digest = ?,
                dispatch_receipt_id = ?, dispatch_receipt_status = ?, dispatch_provider_revision = ?,
                dispatch_consumed_time = ?, dispatched_time = ?, reserved_cost_micros = ?,
                reserved_duration_millis = ?, reconciliation_evidence_digest = ?, outcome_time = ?,
                outcome_binding_digest = ?, operation_memento_schema = ?, operation_memento_format_version = ?,
                operation_memento_digest = ?, operation_memento_payload = ?, evidence_updated_time = ?,
                updated_time = ?
            WHERE id = ? AND tenant_key_digest = ? AND run_record_id = ? AND tenant_id = ? AND run_id = ?
        """

        const val FINALIZE_OPERATION_SQL = """
            UPDATE fw_agent_operation
            SET operation_outcome = ?, reconciliation_evidence_digest = ?, outcome_time = ?,
                outcome_binding_digest = ?, updated_time = ?
            WHERE id = ? AND tenant_key_digest = ? AND run_record_id = ? AND tenant_id = ? AND run_id = ?
              AND operation_outcome IN ('PENDING', 'RECONCILIATION_REQUIRED')
        """

        const val SELECT_OPERATIONS_FOR_RUN_SQL = """
            SELECT * FROM fw_agent_operation
            WHERE tenant_key_digest = ? AND run_record_id = ? AND tenant_id = ? AND run_id = ?
            ORDER BY created_time, attempt_count, id
            LIMIT ?
        """
    }
}

private fun mementoFormat(payload: ByteArray): Int {
    require(payload.size >= 12) { "Agent memento frame is truncated." }
    val version = ByteBuffer.wrap(payload, 8, 4).int
    require(version == 1 || version == 2) { "Agent memento format version is unsupported." }
    return version
}

private fun jdbcDigest(domain: String, vararg values: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    (arrayOf(domain) + values).forEach { value ->
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
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
