package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.outbox.OutboxEventLease
import com.fileweft.application.outbox.OutboxProcessingRepository
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import java.sql.ResultSet

/** PostgreSQL outbox persistence using SKIP LOCKED for multi-worker leasing. */
class JdbcOutboxProcessingRepository(
    private val objectMapper: ObjectMapper,
) : OutboxProcessingRepository {
    override fun claimAvailable(limit: Int, now: Long): List<OutboxEventLease> {
        require(limit > 0) { "Outbox claim limit must be positive." }
        require(now >= 0) { "Outbox claim time must not be negative." }
        return JdbcConnectionContext.requireCurrent().prepareStatement(CLAIM_SQL).use { statement ->
            statement.setLong(1, now)
            statement.setInt(2, limit)
            statement.setLong(3, now)
            statement.executeQuery().use { result ->
                val leases = ArrayList<OutboxEventLease>()
                while (result.next()) leases += mapLease(result)
                leases
            }
        }
    }

    override fun markSucceeded(lease: OutboxEventLease, completedAt: Long) {
        require(completedAt >= 0) { "Outbox completion time must not be negative." }
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "UPDATE fw_outbox_event SET event_status = 'SUCCESS', next_attempt_time = 0, last_error = NULL, updated_time = ? WHERE id = ? AND tenant_id = ? AND event_status = 'RUNNING'",
        ).use { statement ->
            statement.setLong(1, completedAt)
            bindLease(statement, lease, 2)
            requireRunningTransition(statement.executeUpdate(), lease)
        }
    }

    override fun markForRetry(lease: OutboxEventLease, nextAttemptAt: Long, message: String, updatedAt: Long) {
        require(nextAttemptAt >= 0) { "Outbox next attempt time must not be negative." }
        require(updatedAt >= 0) { "Outbox update time must not be negative." }
        require(message.isNotBlank()) { "Outbox retry message must not be blank." }
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "UPDATE fw_outbox_event SET event_status = 'RETRY', retry_count = retry_count + 1, next_attempt_time = ?, last_error = ?, updated_time = ? WHERE id = ? AND tenant_id = ? AND event_status = 'RUNNING'",
        ).use { statement ->
            statement.setLong(1, nextAttemptAt)
            statement.setString(2, message)
            statement.setLong(3, updatedAt)
            bindLease(statement, lease, 4)
            requireRunningTransition(statement.executeUpdate(), lease)
        }
    }

    override fun markFailed(lease: OutboxEventLease, message: String, updatedAt: Long) {
        require(updatedAt >= 0) { "Outbox update time must not be negative." }
        require(message.isNotBlank()) { "Outbox failure message must not be blank." }
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "UPDATE fw_outbox_event SET event_status = 'FAILED', last_error = ?, updated_time = ? WHERE id = ? AND tenant_id = ? AND event_status = 'RUNNING'",
        ).use { statement ->
            statement.setString(1, message)
            statement.setLong(2, updatedAt)
            bindLease(statement, lease, 3)
            requireRunningTransition(statement.executeUpdate(), lease)
        }
    }

    private fun bindLease(statement: java.sql.PreparedStatement, lease: OutboxEventLease, offset: Int) {
        statement.setString(offset, lease.event.id.value)
        statement.setString(offset + 1, lease.event.tenantId.value)
    }

    private fun requireRunningTransition(updated: Int, lease: OutboxEventLease) {
        require(updated == 1) {
            "Outbox event ${lease.event.id.value} is not leased by a running worker in the current tenant."
        }
    }

    private fun mapLease(result: ResultSet): OutboxEventLease = OutboxEventLease(
        event = OutboxEvent(
            id = Identifier(result.getString("id")),
            tenantId = Identifier(result.getString("tenant_id")),
            type = result.getString("event_type"),
            payload = objectMapper.readValue(result.getString("payload_json"), STRING_MAP_TYPE),
            timestamp = result.getLong("created_time"),
            traceId = result.getString("trace_id")?.let(::Identifier),
        ),
        retryCount = result.getInt("retry_count"),
    )

    private companion object {
        val STRING_MAP_TYPE = object : TypeReference<Map<String, String>>() {}

        const val CLAIM_SQL = """
            WITH candidates AS (
                SELECT id
                FROM fw_outbox_event
                WHERE event_status IN ('PENDING', 'RETRY')
                  AND next_attempt_time <= ?
                ORDER BY created_time, id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE fw_outbox_event AS event
            SET event_status = 'RUNNING', updated_time = ?, last_error = NULL
            FROM candidates
            WHERE event.id = candidates.id
            RETURNING event.id, event.tenant_id, event.event_type, event.payload_json, event.trace_id, event.retry_count, event.created_time
        """
    }
}
