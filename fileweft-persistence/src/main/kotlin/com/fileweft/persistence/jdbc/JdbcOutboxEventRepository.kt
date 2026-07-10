package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.core.event.OutboxEvent

class JdbcOutboxEventRepository(
    private val objectMapper: ObjectMapper,
) : OutboxEventRepository {
    override fun append(event: OutboxEvent) {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "INSERT INTO fw_outbox_event(id, tenant_id, event_type, payload_json, event_status, retry_count, created_time, updated_time) VALUES (?, ?, ?, ?::jsonb, 'PENDING', 0, ?, ?)",
        ).use { statement ->
            statement.setString(1, event.id.value)
            statement.setString(2, event.tenantId.value)
            statement.setString(3, event.type)
            statement.setString(4, objectMapper.writeValueAsString(event.payload))
            statement.setLong(5, event.timestamp)
            statement.setLong(6, event.timestamp)
            statement.executeUpdate()
        }
    }
}
