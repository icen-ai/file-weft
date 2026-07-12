package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.core.event.OutboxEvent

class JdbcOutboxEventRepository(
    private val objectMapper: ObjectMapper,
) : OutboxEventRepository {
    override fun append(event: OutboxEvent) {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "INSERT INTO fw_outbox_event(id, tenant_id, event_type, payload_json, trace_id, event_status, retry_count, created_time, updated_time) VALUES (?, ?, ?, ?::jsonb, ?, 'PENDING', 0, ?, ?)",
        ).use { statement ->
            statement.setString(1, event.id.value)
            statement.setString(2, event.tenantId.value)
            statement.setString(3, event.type)
            statement.setString(4, objectMapper.writeValueAsString(event.payload))
            statement.setString(5, event.traceId?.value)
            statement.setLong(6, event.timestamp)
            statement.setLong(7, event.timestamp)
            statement.executeUpdate()
        }
    }
}
