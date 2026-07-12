package ai.icen.fw.application.sync

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.connector.ConnectorSyncStatus

/** Persisted operational result for one connector delivery of an outbox event. */
class SyncRecord(
    val id: Identifier,
    val tenantId: Identifier,
    val documentId: Identifier,
    val sourceEventId: Identifier,
    val connectorName: String,
    val status: ConnectorSyncStatus,
    val externalId: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
) {
    init {
        require(connectorName.isNotBlank()) { "Connector name must not be blank." }
        require(errorMessage == null || errorMessage.isNotBlank()) { "Sync error message must not be blank when provided." }
        require(retryCount >= 0) { "Sync retry count must not be negative." }
    }
}
