package com.fileweft.application.sync

import com.fileweft.core.id.Identifier

interface SyncRecordRepository {
    fun findBySourceEvent(tenantId: Identifier, sourceEventId: Identifier, connectorName: String): SyncRecord?

    fun save(record: SyncRecord)
}
