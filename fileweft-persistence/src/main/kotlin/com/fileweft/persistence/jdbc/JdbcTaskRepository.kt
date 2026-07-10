package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.task.BackgroundTask
import com.fileweft.application.task.BackgroundTaskLease
import com.fileweft.application.task.BackgroundTaskStatus
import com.fileweft.application.task.TaskProcessingRepository
import com.fileweft.application.task.TaskRepository
import com.fileweft.core.id.Identifier
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Clock

/** PostgreSQL task repository with ownership leases and SKIP LOCKED claiming. */
class JdbcTaskRepository(
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : TaskRepository, TaskProcessingRepository {
    override fun enqueue(task: BackgroundTask) {
        val now = clock.millis()
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_task(
                id, tenant_id, task_type, business_id, payload_json, idempotency_key, task_status,
                retry_count, next_attempt_time, lease_owner, lease_expire_time, last_error, created_time, updated_time
            ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, NULL, 0, ?, ?, ?)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
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

    override fun claimAvailable(
        limit: Int,
        now: Long,
        leaseOwner: String,
        leaseExpiresAt: Long,
    ): List<BackgroundTaskLease> {
        require(limit > 0) { "Task claim limit must be positive." }
        require(now >= 0 && leaseExpiresAt > now) { "Task lease time must be after claim time." }
        require(leaseOwner.isNotBlank()) { "Task lease owner must not be blank." }
        return JdbcConnectionContext.requireCurrent().prepareStatement(CLAIM_SQL).use { statement ->
            statement.setLong(1, now)
            statement.setLong(2, now)
            statement.setInt(3, limit)
            statement.setString(4, leaseOwner)
            statement.setLong(5, leaseExpiresAt)
            statement.setLong(6, now)
            statement.executeQuery().use { result ->
                val leases = ArrayList<BackgroundTaskLease>()
                while (result.next()) leases += BackgroundTaskLease(mapTask(result), leaseOwner)
                leases
            }
        }
    }

    override fun markSucceeded(lease: BackgroundTaskLease, completedAt: Long) {
        require(completedAt >= 0) { "Task completion time must not be negative." }
        transition(
            lease,
            "UPDATE fw_task SET task_status = 'SUCCESS', next_attempt_time = 0, lease_owner = NULL, lease_expire_time = 0, last_error = NULL, updated_time = ? WHERE id = ? AND tenant_id = ? AND task_status = 'RUNNING' AND lease_owner = ?",
            completedAt,
        )
    }

    override fun markForRetry(lease: BackgroundTaskLease, nextAttemptAt: Long, message: String, updatedAt: Long) {
        require(nextAttemptAt >= 0 && updatedAt >= 0) { "Task retry times must not be negative." }
        require(message.isNotBlank()) { "Task retry message must not be blank." }
        transition(
            lease,
            "UPDATE fw_task SET task_status = 'RETRY', retry_count = retry_count + 1, next_attempt_time = ?, lease_owner = NULL, lease_expire_time = 0, last_error = ?, updated_time = ? WHERE id = ? AND tenant_id = ? AND task_status = 'RUNNING' AND lease_owner = ?",
            nextAttemptAt,
            message,
            updatedAt,
        )
    }

    override fun markFailed(lease: BackgroundTaskLease, message: String, updatedAt: Long) {
        require(updatedAt >= 0) { "Task update time must not be negative." }
        require(message.isNotBlank()) { "Task failure message must not be blank." }
        transition(
            lease,
            "UPDATE fw_task SET task_status = 'FAILED', lease_owner = NULL, lease_expire_time = 0, last_error = ?, updated_time = ? WHERE id = ? AND tenant_id = ? AND task_status = 'RUNNING' AND lease_owner = ?",
            message,
            updatedAt,
        )
    }

    private fun transition(lease: BackgroundTaskLease, sql: String, vararg values: Any) {
        JdbcConnectionContext.requireCurrent().prepareStatement(sql).use { statement ->
            var index = 1
            values.forEach { value ->
                when (value) {
                    is Long -> statement.setLong(index++, value)
                    is String -> statement.setString(index++, value)
                    else -> throw IllegalArgumentException("Unsupported task SQL value ${value.javaClass.name}.")
                }
            }
            statement.setString(index++, lease.task.id.value)
            statement.setString(index++, lease.task.tenantId.value)
            statement.setString(index, lease.leaseOwner)
            require(statement.executeUpdate() == 1) {
                "Task ${lease.task.id.value} is not leased by worker ${lease.leaseOwner} in the current tenant."
            }
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

    private companion object {
        val STRING_MAP_TYPE = object : TypeReference<Map<String, String>>() {}
        const val SELECT_COLUMNS = "SELECT id, tenant_id, task_type, business_id, payload_json, idempotency_key, task_status, retry_count, next_attempt_time, last_error"
        const val CLAIM_SQL = """
            WITH candidates AS (
                SELECT id
                FROM fw_task
                WHERE (task_status IN ('PENDING', 'RETRY') AND next_attempt_time <= ?)
                   OR (task_status = 'RUNNING' AND lease_expire_time <= ?)
                ORDER BY created_time, id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE fw_task AS task
            SET task_status = 'RUNNING', lease_owner = ?, lease_expire_time = ?, updated_time = ?
            FROM candidates
            WHERE task.id = candidates.id
            RETURNING task.id, task.tenant_id, task.task_type, task.business_id, task.payload_json,
                      task.idempotency_key, task.task_status, task.retry_count, task.next_attempt_time, task.last_error
        """
    }
}
