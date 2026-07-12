package ai.icen.fw.application.sync

import ai.icen.fw.core.id.Identifier

interface SyncRecordRepository {
    fun findBySourceEvent(tenantId: Identifier, sourceEventId: Identifier, connectorName: String): SyncRecord?

    fun save(record: SyncRecord)
}
