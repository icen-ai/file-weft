package com.fileweft.application.audit

import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
import java.time.Clock

/** Appends an immutable audit record; transaction ownership remains with the caller. */
class AuditTrail(
    private val auditRecordRepository: AuditRecordRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
) {
    fun record(
        tenantId: Identifier,
        resourceType: String,
        resourceId: Identifier,
        action: String,
        operatorId: Identifier? = null,
        details: Map<String, String> = emptyMap(),
    ): AuditRecord {
        val record = AuditRecord(
            id = identifierGenerator.nextId(),
            tenantId = tenantId,
            resourceType = resourceType,
            resourceId = resourceId,
            action = action,
            operatorId = operatorId,
            details = details,
            createdAt = clock.millis(),
        )
        auditRecordRepository.append(record)
        return record
    }
}
