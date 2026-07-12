package ai.icen.fw.domain.operation

import ai.icen.fw.core.id.Identifier

interface OperationLogRepository {
    fun append(record: OperationLogRecord)

    fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<OperationLogRecord>
}
