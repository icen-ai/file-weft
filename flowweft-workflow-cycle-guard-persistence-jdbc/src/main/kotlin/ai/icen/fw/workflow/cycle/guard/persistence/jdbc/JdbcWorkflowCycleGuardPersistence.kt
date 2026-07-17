package ai.icen.fw.workflow.cycle.guard.persistence.jdbc

import ai.icen.fw.workflow.cycle.guard.WorkflowCycleBudgetPolicy
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardCommand
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardConsumeRequest
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardLookupCode
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardLookupResult
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardPersistencePort
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardReceiptLookup
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardRecord
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardScope
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardStoreCode
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardStoreResult
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Standard-JDBC durable cycle guard store.
 *
 * A fresh consume locks the owning workflow instance first, then the cross-cycle aggregate and the
 * exact cycle row. Instance fencing, both counters, CAS, the immutable idempotency receipt and all
 * audit evidence commit in one transaction. Exact receipts are checked before a fresh instance
 * fence so a replay remains recoverable after the guarded workflow command advances the instance.
 */
class JdbcWorkflowCycleGuardPersistence(dataSource: DataSource) : WorkflowCycleGuardPersistencePort {
    private val transactions = WorkflowCycleGuardJdbcTransactions(dataSource)

    override fun consume(request: WorkflowCycleGuardConsumeRequest): WorkflowCycleGuardStoreResult = try {
        transactions.transaction { connection -> consumeInTransaction(connection, request) }
    } catch (_: SQLException) {
        recoverAfterFailure(request)
    } catch (_: RuntimeException) {
        failure(WorkflowCycleGuardStoreCode.OUTCOME_UNKNOWN)
    }

    override fun findReceipt(request: WorkflowCycleGuardReceiptLookup): WorkflowCycleGuardLookupResult = try {
        transactions.read { connection ->
            val row = selectReceipt(
                connection,
                request.command.scope.tenantId,
                request.command.idempotencyKey,
            ) ?: return@read WorkflowCycleGuardLookupResult.absent(WorkflowCycleGuardLookupCode.NOT_FOUND)
            if (!row.matches(request.command)) {
                return@read WorkflowCycleGuardLookupResult.absent(WorkflowCycleGuardLookupCode.CONFLICT)
            }
            WorkflowCycleGuardLookupResult.found(row.toRecord(request.command.scope))
        }
    } catch (_: SQLException) {
        WorkflowCycleGuardLookupResult.absent(WorkflowCycleGuardLookupCode.OUTCOME_UNKNOWN)
    } catch (_: RuntimeException) {
        WorkflowCycleGuardLookupResult.absent(WorkflowCycleGuardLookupCode.OUTCOME_UNKNOWN)
    }

    override fun load(scope: WorkflowCycleGuardScope): WorkflowCycleGuardLookupResult {
        if (scope.subject == null) {
            return WorkflowCycleGuardLookupResult.absent(WorkflowCycleGuardLookupCode.NOT_FOUND)
        }
        return try {
            transactions.read { connection ->
                connection.prepareStatement(LOAD_SCOPE_SQL).use { statement ->
                    statement.setString(1, scope.tenantId)
                    statement.setString(2, scope.scopeDigest)
                    statement.executeQuery().use { result ->
                        if (!result.next()) {
                            return@read WorkflowCycleGuardLookupResult.absent(WorkflowCycleGuardLookupCode.NOT_FOUND)
                        }
                        WorkflowCycleGuardLookupResult.found(mapCurrentRecord(result, scope))
                    }
                }
            }
        } catch (_: SQLException) {
            WorkflowCycleGuardLookupResult.absent(WorkflowCycleGuardLookupCode.OUTCOME_UNKNOWN)
        } catch (_: RuntimeException) {
            WorkflowCycleGuardLookupResult.absent(WorkflowCycleGuardLookupCode.OUTCOME_UNKNOWN)
        }
    }

    private fun consumeInTransaction(
        connection: Connection,
        request: WorkflowCycleGuardConsumeRequest,
    ): WorkflowCycleGuardStoreResult {
        val command = request.command
        val scope = command.scope
        if (scope.subject == null || request.policy.scopeDigest != scope.scopeDigest) {
            return failure(WorkflowCycleGuardStoreCode.POLICY_CONFLICT)
        }

        selectReceipt(connection, scope.tenantId, command.idempotencyKey, false)?.let { receipt ->
            return classifyReceipt(receipt, request)
        }

        val instance = lockInstance(connection, scope.tenantId, scope.instanceId)
            ?: return failure(WorkflowCycleGuardStoreCode.VERSION_CONFLICT)

        // A competing transaction can commit the receipt while this call waits on the instance lock.
        selectReceipt(connection, scope.tenantId, command.idempotencyKey, true)?.let { receipt ->
            return classifyReceipt(receipt, request)
        }
        if (!instance.matches(command)) {
            return failure(WorkflowCycleGuardStoreCode.VERSION_CONFLICT)
        }

        val aggregateDigest = WorkflowCycleGuardJdbcIds.aggregate(scope)
        val aggregate = selectAggregate(connection, scope, aggregateDigest, true)
        val cycle = selectCycle(connection, scope, aggregateDigest, true)
        if (cycle != null && aggregate == null) {
            throw IllegalStateException("Cycle guard aggregate is missing for an existing cycle.")
        }
        if (aggregate != null && !aggregate.matches(request.policy)) {
            return failure(WorkflowCycleGuardStoreCode.POLICY_CONFLICT)
        }
        if (cycle != null && !cycle.matches(request.policy)) {
            return failure(WorkflowCycleGuardStoreCode.POLICY_CONFLICT)
        }

        val currentGuardRevision = cycle?.guardRevision ?: 0L
        if (currentGuardRevision != command.expectedGuardRevision ||
            currentGuardRevision == Long.MAX_VALUE ||
            aggregate?.aggregateRevision == Long.MAX_VALUE
        ) return failure(WorkflowCycleGuardStoreCode.VERSION_CONFLICT)

        val nextCycleCount = (cycle?.perCycleCount ?: 0) + 1
        val nextInstanceCount = (aggregate?.operationCount ?: 0) + 1
        if (nextCycleCount > request.policy.maximumPerCycle ||
            nextInstanceCount > request.policy.maximumPerInstance
        ) return failure(WorkflowCycleGuardStoreCode.LIMIT_REACHED)

        val updatedAt = maxOf(
            command.requestedAtEpochMilli,
            aggregate?.updatedAtEpochMilli ?: command.requestedAtEpochMilli,
            cycle?.updatedAtEpochMilli ?: command.requestedAtEpochMilli,
        )
        val nextGuardRevision = currentGuardRevision + 1L
        val record = WorkflowCycleGuardRecord.of(
            scope,
            request.policy.policyId,
            request.policy.policyVersion,
            request.policy.policyDigest,
            request.policy.contentDigest,
            request.policy.authorityRevision,
            request.policy.maximumPerCycle,
            request.policy.maximumPerInstance,
            nextCycleCount,
            nextInstanceCount,
            nextGuardRevision,
            command.idempotencyKey,
            command.requestDigest,
            request.authorizationDecisionDigest,
            updatedAt,
        )

        if (aggregate == null) {
            insertAggregate(connection, aggregateDigest, request, nextInstanceCount, updatedAt)
        } else if (updateAggregate(
                connection,
                aggregate,
                scope.tenantId,
                nextInstanceCount,
                updatedAt,
            ) != 1
        ) {
            throw SQLException("Cycle guard aggregate CAS failed.", "40001")
        }
        if (cycle == null) {
            insertCycle(connection, aggregateDigest, request, record, updatedAt)
        } else if (updateCycle(connection, cycle, request, record, updatedAt) != 1) {
            throw SQLException("Cycle guard cycle CAS failed.", "40001")
        }
        insertReceipt(connection, aggregateDigest, request, record, updatedAt)
        return WorkflowCycleGuardStoreResult.success(WorkflowCycleGuardStoreCode.APPLIED, record)
    }

    private fun classifyReceipt(
        receipt: StoredReceipt,
        request: WorkflowCycleGuardConsumeRequest,
    ): WorkflowCycleGuardStoreResult {
        if (!receipt.matches(request.command)) {
            return failure(WorkflowCycleGuardStoreCode.IDEMPOTENCY_CONFLICT)
        }
        val record = receipt.toRecord(request.command.scope)
        if (!record.matchesPolicy(request.policy)) {
            return failure(WorkflowCycleGuardStoreCode.POLICY_CONFLICT)
        }
        return WorkflowCycleGuardStoreResult.success(WorkflowCycleGuardStoreCode.REPLAYED, record)
    }

    private fun recoverAfterFailure(request: WorkflowCycleGuardConsumeRequest): WorkflowCycleGuardStoreResult {
        val lookup = findReceipt(WorkflowCycleGuardReceiptLookup.of(request.command))
        return when (lookup.code) {
            WorkflowCycleGuardLookupCode.FOUND -> {
                val record = lookup.record
                if (record == null || !record.matchesCommand(request.command)) {
                    failure(WorkflowCycleGuardStoreCode.OUTCOME_UNKNOWN)
                } else if (!record.matchesPolicy(request.policy)) {
                    failure(WorkflowCycleGuardStoreCode.POLICY_CONFLICT)
                } else {
                    WorkflowCycleGuardStoreResult.success(WorkflowCycleGuardStoreCode.REPLAYED, record)
                }
            }
            WorkflowCycleGuardLookupCode.CONFLICT -> failure(WorkflowCycleGuardStoreCode.IDEMPOTENCY_CONFLICT)
            else -> failure(WorkflowCycleGuardStoreCode.OUTCOME_UNKNOWN)
        }
    }

    private fun lockInstance(connection: Connection, tenantId: String, instanceId: String): StoredInstance? =
        connection.prepareStatement(LOCK_INSTANCE_SQL).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, instanceId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else StoredInstance(
                    result.getString("definition_id"),
                    result.getString("definition_key"),
                    result.getString("definition_version"),
                    result.getString("definition_digest"),
                    result.getString("subject_type"),
                    result.getString("subject_id"),
                    result.getString("subject_revision"),
                    result.getString("subject_digest"),
                    result.getLong("instance_version"),
                )
            }
        }

    private fun selectAggregate(
        connection: Connection,
        scope: WorkflowCycleGuardScope,
        aggregateDigest: String,
        lock: Boolean,
    ): StoredAggregate? = connection.prepareStatement(
        SELECT_AGGREGATE_SQL + if (lock) " FOR UPDATE" else "",
    ).use { statement ->
        statement.setString(1, scope.tenantId)
        statement.setString(2, aggregateDigest)
        statement.executeQuery().use { result ->
            if (!result.next()) null else {
                checkAggregateScope(result, scope, aggregateDigest)
                StoredAggregate(
                    result.getString("id"),
                    result.getString("policy_id"),
                    result.getString("policy_version"),
                    result.getString("policy_digest"),
                    result.getString("policy_binding_digest"),
                    result.getString("policy_authority_revision"),
                    result.getInt("maximum_per_cycle"),
                    result.getInt("maximum_per_instance"),
                    result.getInt("operation_count"),
                    result.getLong("aggregate_revision"),
                    result.getLong("created_time"),
                    result.getLong("updated_time"),
                )
            }
        }
    }

    private fun selectCycle(
        connection: Connection,
        scope: WorkflowCycleGuardScope,
        aggregateDigest: String,
        lock: Boolean,
    ): StoredCycle? = connection.prepareStatement(
        SELECT_CYCLE_SQL + if (lock) " FOR UPDATE" else "",
    ).use { statement ->
        statement.setString(1, scope.tenantId)
        statement.setString(2, scope.scopeDigest)
        statement.executeQuery().use { result ->
            if (!result.next()) null else {
                checkCycleScope(result, scope, aggregateDigest)
                StoredCycle(
                    result.getString("id"),
                    result.getString("policy_id"),
                    result.getString("policy_version"),
                    result.getString("policy_digest"),
                    result.getString("policy_content_digest"),
                    result.getString("policy_authority_revision"),
                    result.getInt("maximum_per_cycle"),
                    result.getInt("maximum_per_instance"),
                    result.getInt("per_cycle_count"),
                    result.getLong("guard_revision"),
                    result.getLong("created_time"),
                    result.getLong("updated_time"),
                )
            }
        }
    }

    private fun selectReceipt(
        connection: Connection,
        tenantId: String,
        idempotencyKey: String,
        lock: Boolean = false,
    ): StoredReceipt? = connection.prepareStatement(
        SELECT_RECEIPT_SQL + if (lock) " FOR UPDATE" else "",
    ).use { statement ->
        statement.setString(1, tenantId)
        statement.setString(2, idempotencyKey)
        statement.executeQuery().use { result ->
            if (!result.next()) null else StoredReceipt(
                result.getString("scope_digest"),
                result.getString("aggregate_digest"),
                result.getString("policy_id"),
                result.getString("policy_version"),
                result.getString("policy_digest"),
                result.getString("policy_content_digest"),
                result.getString("policy_authority_revision"),
                result.getInt("maximum_per_cycle"),
                result.getInt("maximum_per_instance"),
                result.getInt("per_cycle_count"),
                result.getInt("instance_operation_count"),
                result.getLong("guard_revision"),
                result.getString("idempotency_key"),
                result.getString("command_request_digest"),
                result.getString("authorization_decision_digest"),
                result.getString("record_digest"),
                result.getLong("updated_time"),
            )
        }
    }

    private fun insertAggregate(
        connection: Connection,
        aggregateDigest: String,
        request: WorkflowCycleGuardConsumeRequest,
        count: Int,
        now: Long,
    ) {
        val scope = request.command.scope
        val subject = checkNotNull(scope.subject)
        connection.prepareStatement(INSERT_AGGREGATE_SQL).use { statement ->
            var index = 1
            statement.setString(index++, aggregateDigest)
            statement.setString(index++, scope.tenantId)
            statement.setString(index++, aggregateDigest)
            statement.setString(index++, scope.instanceId)
            statement.setString(index++, scope.definitionId)
            statement.setString(index++, scope.definitionRef.key)
            statement.setString(index++, scope.definitionRef.version)
            statement.setString(index++, scope.definitionRef.digest)
            statement.setString(index++, scope.operation.code)
            statement.setString(index++, subject.ref.type)
            statement.setString(index++, subject.ref.id)
            statement.setString(index++, request.policy.policyId)
            statement.setString(index++, request.policy.policyVersion)
            statement.setString(index++, request.policy.policyDigest)
            statement.setString(index++, WorkflowCycleGuardJdbcIds.policyBinding(request.policy))
            statement.setString(index++, request.policy.authorityRevision)
            statement.setInt(index++, request.policy.maximumPerCycle)
            statement.setInt(index++, request.policy.maximumPerInstance)
            statement.setInt(index++, count)
            statement.setLong(index++, 1L)
            statement.setLong(index++, now)
            statement.setLong(index, now)
            statement.executeUpdate().also { check(it == 1) { "Cycle guard aggregate insert failed." } }
        }
    }

    private fun updateAggregate(
        connection: Connection,
        stored: StoredAggregate,
        tenantId: String,
        count: Int,
        now: Long,
    ): Int = connection.prepareStatement(UPDATE_AGGREGATE_SQL).use { statement ->
        statement.setInt(1, count)
        statement.setLong(2, stored.aggregateRevision + 1L)
        statement.setLong(3, now)
        statement.setString(4, tenantId)
        statement.setString(5, stored.id)
        statement.setLong(6, stored.aggregateRevision)
        statement.setString(7, stored.policyBindingDigest)
        statement.executeUpdate()
    }

    private fun insertCycle(
        connection: Connection,
        aggregateDigest: String,
        request: WorkflowCycleGuardConsumeRequest,
        record: WorkflowCycleGuardRecord,
        now: Long,
    ) {
        val scope = request.command.scope
        val subject = checkNotNull(scope.subject)
        connection.prepareStatement(INSERT_CYCLE_SQL).use { statement ->
            var index = 1
            statement.setString(index++, scope.scopeDigest)
            statement.setString(index++, scope.tenantId)
            statement.setString(index++, scope.scopeDigest)
            statement.setString(index++, aggregateDigest)
            statement.setString(index++, scope.instanceId)
            statement.setString(index++, scope.definitionId)
            statement.setString(index++, scope.definitionRef.key)
            statement.setString(index++, scope.definitionRef.version)
            statement.setString(index++, scope.definitionRef.digest)
            statement.setString(index++, scope.nodeId)
            statement.setString(index++, scope.operation.code)
            statement.setLong(index++, scope.cycleNumber)
            statement.setString(index++, subject.ref.type)
            statement.setString(index++, subject.ref.id)
            statement.setString(index++, subject.revision)
            statement.setString(index++, subject.digest)
            index = setPolicy(statement, index, request.policy)
            statement.setInt(index++, record.perCycleCount)
            statement.setInt(index++, record.instanceOperationCount)
            statement.setLong(index++, record.guardRevision)
            statement.setString(index++, record.lastIdempotencyKey)
            statement.setString(index++, record.lastRequestDigest)
            statement.setString(index++, record.lastAuthorizationDecisionDigest)
            statement.setString(index++, record.recordDigest)
            statement.setLong(index++, now)
            statement.setLong(index, now)
            statement.executeUpdate().also { check(it == 1) { "Cycle guard cycle insert failed." } }
        }
    }

    private fun updateCycle(
        connection: Connection,
        stored: StoredCycle,
        request: WorkflowCycleGuardConsumeRequest,
        record: WorkflowCycleGuardRecord,
        now: Long,
    ): Int = connection.prepareStatement(UPDATE_CYCLE_SQL).use { statement ->
        statement.setInt(1, record.perCycleCount)
        statement.setInt(2, record.instanceOperationCount)
        statement.setLong(3, record.guardRevision)
        statement.setString(4, record.lastIdempotencyKey)
        statement.setString(5, record.lastRequestDigest)
        statement.setString(6, record.lastAuthorizationDecisionDigest)
        statement.setString(7, record.recordDigest)
        statement.setLong(8, now)
        statement.setString(9, request.command.scope.tenantId)
        statement.setString(10, stored.id)
        statement.setLong(11, stored.guardRevision)
        statement.setString(12, stored.policyContentDigest)
        statement.executeUpdate()
    }

    private fun insertReceipt(
        connection: Connection,
        aggregateDigest: String,
        request: WorkflowCycleGuardConsumeRequest,
        record: WorkflowCycleGuardRecord,
        now: Long,
    ) {
        val command = request.command
        connection.prepareStatement(INSERT_RECEIPT_SQL).use { statement ->
            var index = 1
            statement.setString(index++, WorkflowCycleGuardJdbcIds.receipt(command.scope.tenantId, command.idempotencyKey))
            statement.setString(index++, command.scope.tenantId)
            statement.setString(index++, command.idempotencyKey)
            statement.setString(index++, command.scope.scopeDigest)
            statement.setString(index++, aggregateDigest)
            index = setPolicy(statement, index, request.policy)
            statement.setInt(index++, record.perCycleCount)
            statement.setInt(index++, record.instanceOperationCount)
            statement.setLong(index++, record.guardRevision)
            statement.setString(index++, command.requestDigest)
            statement.setString(index++, request.requestDigest)
            statement.setString(index++, request.authorizationDecisionDigest)
            statement.setString(index++, record.recordDigest)
            statement.setLong(index++, now)
            statement.setLong(index, now)
            statement.executeUpdate().also { check(it == 1) { "Cycle guard receipt insert failed." } }
        }
    }

    private fun setPolicy(
        statement: java.sql.PreparedStatement,
        start: Int,
        policy: WorkflowCycleBudgetPolicy,
    ): Int {
        var index = start
        statement.setString(index++, policy.policyId)
        statement.setString(index++, policy.policyVersion)
        statement.setString(index++, policy.policyDigest)
        statement.setString(index++, policy.contentDigest)
        statement.setString(index++, policy.authorityRevision)
        statement.setInt(index++, policy.maximumPerCycle)
        statement.setInt(index++, policy.maximumPerInstance)
        return index
    }

    private fun checkAggregateScope(result: ResultSet, scope: WorkflowCycleGuardScope, digest: String) {
        val subject = checkNotNull(scope.subject)
        check(result.getString("aggregate_digest") == digest &&
            result.getString("tenant_id") == scope.tenantId &&
            result.getString("instance_id") == scope.instanceId &&
            result.getString("definition_id") == scope.definitionId &&
            result.getString("definition_key") == scope.definitionRef.key &&
            result.getString("definition_version") == scope.definitionRef.version &&
            result.getString("definition_digest") == scope.definitionRef.digest &&
            result.getString("operation_code") == scope.operation.code &&
            result.getString("subject_type") == subject.ref.type &&
            result.getString("subject_id") == subject.ref.id
        ) { "Cycle guard aggregate scope evidence is corrupt." }
    }

    private fun checkCycleScope(result: ResultSet, scope: WorkflowCycleGuardScope, aggregateDigest: String) {
        val subject = checkNotNull(scope.subject)
        check(result.getString("scope_digest") == scope.scopeDigest &&
            result.getString("aggregate_digest") == aggregateDigest &&
            result.getString("tenant_id") == scope.tenantId &&
            result.getString("instance_id") == scope.instanceId &&
            result.getString("definition_id") == scope.definitionId &&
            result.getString("definition_key") == scope.definitionRef.key &&
            result.getString("definition_version") == scope.definitionRef.version &&
            result.getString("definition_digest") == scope.definitionRef.digest &&
            result.getString("node_id") == scope.nodeId &&
            result.getString("operation_code") == scope.operation.code &&
            result.getLong("cycle_number") == scope.cycleNumber &&
            result.getString("subject_type") == subject.ref.type &&
            result.getString("subject_id") == subject.ref.id &&
            result.getString("subject_revision") == subject.revision &&
            result.getString("subject_digest") == subject.digest
        ) { "Cycle guard cycle scope evidence is corrupt." }
    }

    private fun mapCurrentRecord(result: ResultSet, scope: WorkflowCycleGuardScope): WorkflowCycleGuardRecord {
        val aggregateDigest = WorkflowCycleGuardJdbcIds.aggregate(scope)
        checkCycleScope(result, scope, aggregateDigest)
        val expectedAggregatePolicyBinding = WorkflowCycleGuardJdbcIds.policyBinding(
            result.getString("policy_id"),
            result.getString("policy_version"),
            result.getString("policy_digest"),
            result.getString("policy_authority_revision"),
            result.getInt("maximum_per_cycle"),
            result.getInt("maximum_per_instance"),
        )
        check(result.getString("aggregate_policy_binding_digest") == expectedAggregatePolicyBinding &&
            result.getInt("aggregate_maximum_per_cycle") == result.getInt("maximum_per_cycle") &&
            result.getInt("aggregate_maximum_per_instance") == result.getInt("maximum_per_instance")
        ) { "Cycle guard aggregate policy evidence is corrupt." }

        val historical = recordFromCycle(
            result,
            scope,
            result.getInt("last_instance_operation_count"),
            result.getLong("updated_time"),
        )
        check(historical.recordDigest == result.getString("last_record_digest") &&
            result.getString("receipt_record_digest") == historical.recordDigest &&
            result.getString("receipt_scope_digest") == scope.scopeDigest &&
            result.getString("receipt_request_digest") == historical.lastRequestDigest
        ) { "Cycle guard latest receipt evidence is corrupt." }

        val currentInstanceCount = result.getInt("aggregate_operation_count")
        check(currentInstanceCount >= historical.instanceOperationCount) {
            "Cycle guard aggregate counter regressed."
        }
        val observedAt = maxOf(result.getLong("updated_time"), result.getLong("aggregate_updated_time"))
        return recordFromCycle(result, scope, currentInstanceCount, observedAt)
    }

    private fun recordFromCycle(
        result: ResultSet,
        scope: WorkflowCycleGuardScope,
        instanceCount: Int,
        updatedAt: Long,
    ): WorkflowCycleGuardRecord = WorkflowCycleGuardRecord.of(
        scope,
        result.getString("policy_id"),
        result.getString("policy_version"),
        result.getString("policy_digest"),
        result.getString("policy_content_digest"),
        result.getString("policy_authority_revision"),
        result.getInt("maximum_per_cycle"),
        result.getInt("maximum_per_instance"),
        result.getInt("per_cycle_count"),
        instanceCount,
        result.getLong("guard_revision"),
        result.getString("last_idempotency_key"),
        result.getString("last_request_digest"),
        result.getString("last_authorization_decision_digest"),
        updatedAt,
    )

    private fun failure(code: WorkflowCycleGuardStoreCode): WorkflowCycleGuardStoreResult =
        WorkflowCycleGuardStoreResult.failure(code)

    private class StoredInstance(
        private val definitionId: String,
        private val definitionKey: String,
        private val definitionVersion: String,
        private val definitionDigest: String,
        private val subjectType: String,
        private val subjectId: String,
        private val subjectRevision: String,
        private val subjectDigest: String,
        private val instanceVersion: Long,
    ) {
        fun matches(command: WorkflowCycleGuardCommand): Boolean {
            val scope = command.scope
            val subject = scope.subject ?: return false
            return instanceVersion == command.expectedInstanceVersion &&
                definitionId == scope.definitionId &&
                definitionKey == scope.definitionRef.key &&
                definitionVersion == scope.definitionRef.version &&
                definitionDigest == scope.definitionRef.digest &&
                subjectType == subject.ref.type &&
                subjectId == subject.ref.id &&
                subjectRevision == subject.revision &&
                subjectDigest == subject.digest
        }
    }

    private class StoredAggregate(
        val id: String,
        private val policyId: String,
        private val policyVersion: String,
        private val policyDigest: String,
        val policyBindingDigest: String,
        private val policyAuthorityRevision: String,
        private val maximumPerCycle: Int,
        private val maximumPerInstance: Int,
        val operationCount: Int,
        val aggregateRevision: Long,
        val createdAtEpochMilli: Long,
        val updatedAtEpochMilli: Long,
    ) {
        init {
            check(maximumPerCycle > 0 && maximumPerInstance >= maximumPerCycle &&
                operationCount in 1..maximumPerInstance && aggregateRevision > 0L &&
                updatedAtEpochMilli >= createdAtEpochMilli
            ) { "Cycle guard aggregate counters are corrupt." }
        }

        fun matches(policy: WorkflowCycleBudgetPolicy): Boolean =
            policyId == policy.policyId && policyVersion == policy.policyVersion &&
                policyDigest == policy.policyDigest &&
                policyBindingDigest == WorkflowCycleGuardJdbcIds.policyBinding(policy) &&
                policyAuthorityRevision == policy.authorityRevision &&
                maximumPerCycle == policy.maximumPerCycle && maximumPerInstance == policy.maximumPerInstance
    }

    private class StoredCycle(
        val id: String,
        private val policyId: String,
        private val policyVersion: String,
        private val policyDigest: String,
        val policyContentDigest: String,
        private val policyAuthorityRevision: String,
        private val maximumPerCycle: Int,
        private val maximumPerInstance: Int,
        val perCycleCount: Int,
        val guardRevision: Long,
        val createdAtEpochMilli: Long,
        val updatedAtEpochMilli: Long,
    ) {
        init {
            check(maximumPerCycle > 0 && maximumPerInstance >= maximumPerCycle &&
                perCycleCount in 1..maximumPerCycle && guardRevision > 0L &&
                updatedAtEpochMilli >= createdAtEpochMilli
            ) { "Cycle guard cycle counters are corrupt." }
        }

        fun matches(policy: WorkflowCycleBudgetPolicy): Boolean =
            policyId == policy.policyId && policyVersion == policy.policyVersion &&
                policyDigest == policy.policyDigest && policyContentDigest == policy.contentDigest &&
                policyAuthorityRevision == policy.authorityRevision &&
                maximumPerCycle == policy.maximumPerCycle && maximumPerInstance == policy.maximumPerInstance
    }

    private class StoredReceipt(
        private val scopeDigest: String,
        private val aggregateDigest: String,
        private val policyId: String,
        private val policyVersion: String,
        private val policyDigest: String,
        private val policyContentDigest: String,
        private val policyAuthorityRevision: String,
        private val maximumPerCycle: Int,
        private val maximumPerInstance: Int,
        private val perCycleCount: Int,
        private val instanceOperationCount: Int,
        private val guardRevision: Long,
        private val idempotencyKey: String,
        private val commandRequestDigest: String,
        private val authorizationDecisionDigest: String,
        private val recordDigest: String,
        private val updatedAtEpochMilli: Long,
    ) {
        fun matches(command: WorkflowCycleGuardCommand): Boolean =
            scopeDigest == command.scope.scopeDigest && idempotencyKey == command.idempotencyKey &&
                commandRequestDigest == command.requestDigest &&
                aggregateDigest == WorkflowCycleGuardJdbcIds.aggregate(command.scope)

        fun toRecord(scope: WorkflowCycleGuardScope): WorkflowCycleGuardRecord {
            check(scopeDigest == scope.scopeDigest && aggregateDigest == WorkflowCycleGuardJdbcIds.aggregate(scope)) {
                "Cycle guard receipt scope evidence is corrupt."
            }
            val record = WorkflowCycleGuardRecord.of(
                scope,
                policyId,
                policyVersion,
                policyDigest,
                policyContentDigest,
                policyAuthorityRevision,
                maximumPerCycle,
                maximumPerInstance,
                perCycleCount,
                instanceOperationCount,
                guardRevision,
                idempotencyKey,
                commandRequestDigest,
                authorizationDecisionDigest,
                updatedAtEpochMilli,
            )
            check(record.recordDigest == recordDigest) { "Cycle guard receipt digest is corrupt." }
            return record
        }
    }

    private companion object {
        const val LOCK_INSTANCE_SQL = """
            SELECT definition_id, definition_key, definition_version, definition_digest,
                   subject_type, subject_id, subject_revision, subject_digest, instance_version
            FROM fw_wf_instance WHERE tenant_id = ? AND id = ? FOR UPDATE
        """
        const val SELECT_AGGREGATE_SQL = """
            SELECT * FROM fw_wf_cycle_guard_total WHERE tenant_id = ? AND aggregate_digest = ?
        """
        const val SELECT_CYCLE_SQL = """
            SELECT * FROM fw_wf_cycle_guard_cycle WHERE tenant_id = ? AND scope_digest = ?
        """
        const val SELECT_RECEIPT_SQL = """
            SELECT * FROM fw_wf_cycle_guard_receipt WHERE tenant_id = ? AND idempotency_key = ?
        """
        const val LOAD_SCOPE_SQL = """
            SELECT c.*,
                   a.operation_count AS aggregate_operation_count,
                   a.updated_time AS aggregate_updated_time,
                   a.policy_binding_digest AS aggregate_policy_binding_digest,
                   a.maximum_per_cycle AS aggregate_maximum_per_cycle,
                   a.maximum_per_instance AS aggregate_maximum_per_instance,
                   r.record_digest AS receipt_record_digest,
                   r.scope_digest AS receipt_scope_digest,
                   r.command_request_digest AS receipt_request_digest
            FROM fw_wf_cycle_guard_cycle c
            JOIN fw_wf_cycle_guard_total a
              ON a.tenant_id = c.tenant_id AND a.aggregate_digest = c.aggregate_digest
            LEFT JOIN fw_wf_cycle_guard_receipt r
              ON r.tenant_id = c.tenant_id AND r.idempotency_key = c.last_idempotency_key
            WHERE c.tenant_id = ? AND c.scope_digest = ?
        """
        const val INSERT_AGGREGATE_SQL = """
            INSERT INTO fw_wf_cycle_guard_total (
                id, tenant_id, aggregate_digest, instance_id, definition_id, definition_key,
                definition_version, definition_digest, operation_code, subject_type,
                subject_id, policy_id, policy_version, policy_digest, policy_binding_digest,
                policy_authority_revision, maximum_per_cycle, maximum_per_instance,
                operation_count, aggregate_revision, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val UPDATE_AGGREGATE_SQL = """
            UPDATE fw_wf_cycle_guard_total SET operation_count = ?, aggregate_revision = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND aggregate_revision = ? AND policy_binding_digest = ?
        """
        const val INSERT_CYCLE_SQL = """
            INSERT INTO fw_wf_cycle_guard_cycle (
                id, tenant_id, scope_digest, aggregate_digest, instance_id, definition_id,
                definition_key, definition_version, definition_digest, node_id, operation_code,
                cycle_number, subject_type, subject_id, subject_revision, subject_digest,
                policy_id, policy_version, policy_digest, policy_content_digest,
                policy_authority_revision, maximum_per_cycle, maximum_per_instance, per_cycle_count,
                last_instance_operation_count, guard_revision, last_idempotency_key,
                last_request_digest, last_authorization_decision_digest, last_record_digest,
                created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val UPDATE_CYCLE_SQL = """
            UPDATE fw_wf_cycle_guard_cycle SET
                per_cycle_count = ?, last_instance_operation_count = ?, guard_revision = ?,
                last_idempotency_key = ?, last_request_digest = ?,
                last_authorization_decision_digest = ?, last_record_digest = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND guard_revision = ? AND policy_content_digest = ?
        """
        const val INSERT_RECEIPT_SQL = """
            INSERT INTO fw_wf_cycle_guard_receipt (
                id, tenant_id, idempotency_key, scope_digest, aggregate_digest, policy_id,
                policy_version, policy_digest, policy_content_digest, policy_authority_revision,
                maximum_per_cycle, maximum_per_instance, per_cycle_count,
                instance_operation_count, guard_revision, command_request_digest,
                consume_request_digest, authorization_decision_digest, record_digest,
                created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    }
}
