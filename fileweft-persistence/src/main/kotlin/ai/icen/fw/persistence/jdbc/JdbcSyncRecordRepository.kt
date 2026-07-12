package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.sync.SyncRecord
import ai.icen.fw.application.sync.SyncRecordRepository
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import java.time.Clock

class JdbcSyncRecordRepository(
    private val clock: Clock,
) : SyncRecordRepository {
    override fun findBySourceEvent(tenantId: Identifier, sourceEventId: Identifier, connectorName: String): SyncRecord? {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "SELECT id, tenant_id, document_id, source_event_id, connector_name, external_id, sync_status, error_message, retry_count FROM fw_sync_record WHERE tenant_id = ? AND source_event_id = ? AND connector_name = ?",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, sourceEventId.value)
            statement.setString(3, connectorName)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                return SyncRecord(
                    id = Identifier(result.getString("id")),
                    tenantId = Identifier(result.getString("tenant_id")),
                    documentId = Identifier(result.getString("document_id")),
                    sourceEventId = Identifier(result.getString("source_event_id")),
                    connectorName = result.getString("connector_name"),
                    status = ConnectorSyncStatus.valueOf(result.getString("sync_status")),
                    externalId = result.getString("external_id"),
                    errorMessage = result.getString("error_message"),
                    retryCount = result.getInt("retry_count"),
                )
            }
        }
    }

    override fun save(record: SyncRecord) {
        val now = clock.millis()
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_sync_record(id, tenant_id, document_id, source_event_id, connector_name, external_id, sync_status, error_message, retry_count, created_time, updated_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, source_event_id, connector_name) DO UPDATE
            SET document_id = EXCLUDED.document_id,
                external_id = EXCLUDED.external_id,
                sync_status = EXCLUDED.sync_status,
                error_message = EXCLUDED.error_message,
                retry_count = EXCLUDED.retry_count,
                updated_time = EXCLUDED.updated_time
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, record.id.value)
            statement.setString(2, record.tenantId.value)
            statement.setString(3, record.documentId.value)
            statement.setString(4, record.sourceEventId.value)
            statement.setString(5, record.connectorName)
            statement.setString(6, record.externalId)
            statement.setString(7, record.status.name)
            statement.setString(8, record.errorMessage)
            statement.setInt(9, record.retryCount)
            statement.setLong(10, now)
            statement.setLong(11, now)
            statement.executeUpdate()
        }
    }
}
