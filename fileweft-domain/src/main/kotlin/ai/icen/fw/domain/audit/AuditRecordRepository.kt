package ai.icen.fw.domain.audit

import ai.icen.fw.core.id.Identifier

interface AuditRecordRepository {
    fun append(record: AuditRecord)

    fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord>
}
