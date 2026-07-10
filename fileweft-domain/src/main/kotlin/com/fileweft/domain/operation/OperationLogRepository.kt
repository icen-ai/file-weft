package com.fileweft.domain.operation

import com.fileweft.core.id.Identifier

interface OperationLogRepository {
    fun append(record: OperationLogRecord)

    fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<OperationLogRecord>
}
