package com.fileweft.domain.audit

import com.fileweft.core.id.Identifier

interface AuditRecordRepository {
    fun append(record: AuditRecord)

    fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord>
}
