package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.BackgroundTaskLease
import ai.icen.fw.application.task.BackgroundTaskStatus
import ai.icen.fw.application.task.LeasedTaskProcessingRepository
import ai.icen.fw.application.task.TaskLeaseClaim
import ai.icen.fw.application.task.TaskLeaseLostException
import ai.icen.fw.application.task.TaskMutationRepository
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.task.TaskState
import ai.icen.fw.core.id.Identifier
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Clock
import java.util.UUID

/** PostgreSQL task repository with ownership leases and SKIP LOCKED claiming. */
class JdbcTaskRepository(
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : TaskRepository, LeasedTaskProcessingRepository, TaskMutationRepository {
    override fun enqueue(task: BackgroundTask) {
        val now = clock.millis()
        val dialect = JdbcConnectionContext.requireDialect()
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_task(
                id, tenant_id, task_type, business_id, payload_json, idempotency_key, task_status,
                retry_count, next_attempt_time, lease_owner, lease_expire_time, last_error, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ${dialect.jsonParameterBinding()}, ?, ?, ?, ?, NULL, 0, ?, ?, ?)
            ${dialect.upsertClause(listOf("tenant_id", "idempotency_key"), emptyList())}
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, task.id.value)
            statement.setString(2, task.tenantId.value)
            statement.setString(3, task.type)
            statement.setString(4, task.businessId?.value)
            statement.setString(5, objectMapper.writeValueAsString(task.payload))
            statement.setString(6, task.idempotencyKey)
            statement.setString(7, task.status.name)
            statement.setInt(8, task.retryCount)
            statement.setLong(9, task.nextAttemptTime)
            statement.setString(10, task.lastError)
            statement.setLong(11, now)
            statement.setLong(12, now)
            statement.executeUpdate()
        }
    }

    override fun findById(tenantId: Identifier, taskId: Identifier): BackgroundTask? =
        JdbcConnectionContext.requireCurrent().prepareStatement("$SELECT_COLUMNS FROM fw_task WHERE tenant_id = ? AND id = ?").use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, taskId.value)
            statement.executeQuery().use { result -> if (result.next()) mapTask(result) else null }
        }

    override fun findByBusiness(tenantId: Identifier, businessId: Identifier, limit: Int): List<BackgroundTask> {
        require(limit in 1..1000) { "Task query limit must be between 1 and 1000." }
        return JdbcConnectionContext.requireCurrent().prepareStatement(
            "$SELECT_COLUMNS FROM fw_task WHERE tenant_id = ? AND business_id = ? ORDER BY updated_time DESC, id DESC LIMIT ?",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, businessId.value)
            statement.setInt(3, limit)
            statement.executeQuery().use { result ->
                val tasks = ArrayList<BackgroundTask>()
                while (result.next()) tasks += mapTask(result)
                tasks
            }
        }
    }

    override fun findForMutation(tenantId: Identifier, taskId: Identifier): TaskState? =
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            SELECT id, tenant_id, task_type, business_id, task_status, lease_owner, lease_token
            FROM fw_task
            WHERE tenant_id = ? AND id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, taskId.value)
            statement.executeQuery().use { result -> if (result.next()) mapTaskState(result) else null }
        }

    override fun claimAvailable(
        limit: Int,
        now: Long,
        leaseOwner: String,
        leaseExpiresAt: Long,
    ): List<BackgroundTaskLease> = claimAvailable(
        limit,
        now,
        TaskLeaseClaim(
            leaseOwner,
            UUID.randomUUID().toString(),
            leaseExpiresAt,
            safeSubtract(now, LEGACY_RUNNING_GRACE_MILLIS),
        ),
    )

    override fun claimAvailable(limit: Int, now: Long, claim: TaskLeaseClaim): List<BackgroundTaskLease> {
        require(limit > 0) { "Task claim limit must be positive." }
        require(now >= 0) { "Task claim time must not be negative." }
        require(claim.leaseExpiresAt > now) { "Task lease expiry must be after claim time." }
        require(claim.legacyRunningBefore <= now) { "Task legacy running cutoff must not be after claim time." }
        val dialect = JdbcConnectionContext.requireDialect()
        val connection = JdbcConnectionContext.requireCurrent()

        val candidateIds = connection.prepareStatement(
            "${candidateSelectSql(dialect.claimCandidateTable(TASK_TABLE, CLAIM_ORDER_INDEX))} " +
                "${dialect.limitClause()} ${dialect.forUpdateSkipLocked()}",
        ).use { statement ->
            statement.setLong(1, now)
            statement.setLong(2, now)
            statement.setLong(3, claim.legacyRunningBefore)
            statement.setInt(4, limit)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.getString("id"))
                }
            }
        }
        if (candidateIds.isEmpty()) return emptyList()

        val placeholders = candidateIds.joinToString(",") { "?" }
        val updated = connection.prepareStatement(
            "UPDATE fw_task SET task_status = 'RUNNING', lease_owner = ?, lease_token = ?, lease_expire_time = ?, updated_time = ?, last_error = NULL WHERE id IN ($placeholders)",
        ).use { statement ->
            statement.setString(1, claim.leaseOwner)
            statement.setString(2, claim.leaseToken)
            statement.setLong(3, claim.leaseExpiresAt)
            statement.setLong(4, now)
            candidateIds.forEachIndexed { index, id -> statement.setString(index + 5, id) }
            statement.executeUpdate()
        }
        if (updated == 0) return emptyList()

        return connection.prepareStatement(
            "$SELECT_COLUMNS FROM fw_task WHERE id IN ($placeholders) AND task_status = 'RUNNING'",
        ).use { statement ->
            candidateIds.forEachIndexed { index, id -> statement.setString(index + 1, id) }
            statement.executeQuery().use { result ->
                val leases = ArrayList<BackgroundTaskLease>()
                while (result.next()) leases += mapLease(result)
                leases
            }
        }
    }

    override fun markSucceeded(lease: BackgroundTaskLease, completedAt: Long) {
        require(completedAt >= 0) { "Task completion time must not be negative." }
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "UPDATE fw_task SET task_status = 'SUCCESS', next_attempt_time = 0, lease_owner = NULL, lease_token = NULL, lease_expire_time = 0, last_error = NULL, updated_time = ? WHERE id = ? AND tenant_id = ? AND task_status = 'RUNNING'${leasePredicate(lease)}",
        ).use { statement ->
            statement.setLong(1, completedAt)
            bindLease(statement, lease, 2)
            requireRunningTransition(statement.executeUpdate(), lease)
        }
    }

    override fun markForRetry(lease: BackgroundTaskLease, nextAttemptAt: Long, message: String, updatedAt: Long) {
        require(nextAttemptAt >= 0 && updatedAt >= 0) { "Task retry times must not be negative." }
        require(message.isNotBlank()) { "Task retry message must not be blank." }
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "UPDATE fw_task SET task_status = 'RETRY', retry_count = retry_count + 1, next_attempt_time = ?, lease_owner = NULL, lease_token = NULL, lease_expire_time = 0, last_error = ?, updated_time = ? WHERE id = ? AND tenant_id = ? AND task_status = 'RUNNING'${leasePredicate(lease)}",
        ).use { statement ->
            statement.setLong(1, nextAttemptAt)
            statement.setString(2, message)
            statement.setLong(3, updatedAt)
            bindLease(statement, lease, 4)
            requireRunningTransition(statement.executeUpdate(), lease)
        }
    }

    override fun markFailed(lease: BackgroundTaskLease, message: String, updatedAt: Long) {
        require(updatedAt >= 0) { "Task update time must not be negative." }
        require(message.isNotBlank()) { "Task failure message must not be blank." }
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "UPDATE fw_task SET task_status = 'FAILED', lease_owner = NULL, lease_token = NULL, lease_expire_time = 0, last_error = ?, updated_time = ? WHERE id = ? AND tenant_id = ? AND task_status = 'RUNNING'${leasePredicate(lease)}",
        ).use { statement ->
            statement.setString(1, message)
            statement.setLong(2, updatedAt)
            bindLease(statement, lease, 3)
            requireRunningTransition(statement.executeUpdate(), lease)
        }
    }

    private fun bindLease(statement: PreparedStatement, lease: BackgroundTaskLease, offset: Int) {
        statement.setString(offset, lease.task.id.value)
        statement.setString(offset + 1, lease.task.tenantId.value)
        statement.setString(offset + 2, lease.leaseOwner)
        lease.leaseToken?.let { token ->
            statement.setString(offset + 3, token)
        }
    }

    private fun leasePredicate(lease: BackgroundTaskLease): String =
        if (lease.leaseToken == null) " AND lease_owner = ? AND lease_token IS NULL" else " AND lease_owner = ? AND lease_token = ?"

    private fun requireRunningTransition(updated: Int, lease: BackgroundTaskLease) {
        if (updated != 1) {
            throw TaskLeaseLostException(
                "Task ${lease.task.id.value} is no longer leased by the current worker in the current tenant.",
            )
        }
    }

    private fun mapTask(result: ResultSet): BackgroundTask = BackgroundTask(
        id = Identifier(result.getString("id")),
        tenantId = Identifier(result.getString("tenant_id")),
        type = result.getString("task_type"),
        idempotencyKey = result.getString("idempotency_key"),
        businessId = result.getString("business_id")?.let(::Identifier),
        payload = objectMapper.readValue(result.getString("payload_json"), STRING_MAP_TYPE),
        status = BackgroundTaskStatus.valueOf(result.getString("task_status")),
        retryCount = result.getInt("retry_count"),
        nextAttemptTime = result.getLong("next_attempt_time"),
        lastError = result.getString("last_error"),
    )

    private fun mapLease(result: ResultSet): BackgroundTaskLease = BackgroundTaskLease(
        task = mapTask(result),
        leaseOwner = result.getString("lease_owner") ?: error("Claimed task is missing its lease owner."),
        leaseToken = result.getString("lease_token") ?: error("Claimed task is missing its lease token."),
    )

    private fun mapTaskState(result: ResultSet): TaskState = TaskState(
        id = Identifier(result.getString("id")),
        tenantId = Identifier(result.getString("tenant_id")),
        type = result.getString("task_type"),
        status = BackgroundTaskStatus.valueOf(result.getString("task_status")),
        businessId = result.getString("business_id")?.let(::Identifier),
        leaseOwner = result.getString("lease_owner"),
        leaseToken = result.getString("lease_token"),
    )

    private fun safeSubtract(value: Long, decrement: Long): Long =
        if (value < decrement) 0 else value - decrement

    private companion object {
        val STRING_MAP_TYPE = object : TypeReference<Map<String, String>>() {}
        const val LEGACY_RUNNING_GRACE_MILLIS = 300_000L
        const val SELECT_COLUMNS = "SELECT id, tenant_id, task_type, business_id, payload_json, idempotency_key, task_status, retry_count, next_attempt_time, last_error, lease_owner, lease_token"
        const val TASK_TABLE = "fw_task"
        const val CLAIM_ORDER_INDEX = "idx_fw_task_claim_order"

        fun candidateSelectSql(tableExpression: String): String = """
            SELECT id
            FROM $tableExpression
            WHERE (task_status IN ('PENDING', 'RETRY') AND next_attempt_time <= ?)
               OR (task_status = 'RUNNING' AND (
                    (lease_token IS NOT NULL AND lease_expire_time <= ?)
                    OR (lease_token IS NULL AND updated_time <= ?)
               ))
            ORDER BY created_time, id
        """.trimIndent()
    }
}
