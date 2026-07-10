package com.fileweft.application.audit

import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.domain.operation.OperationLogRecord
import com.fileweft.domain.operation.OperationLogRepository
import com.fileweft.spi.observability.TraceContextProvider
import java.time.Clock

/** Appends an immutable audit record; transaction ownership remains with the caller. */
class AuditTrail(
    private val auditRecordRepository: AuditRecordRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
    private val operationLogRepository: OperationLogRepository? = null,
    private val traceContextProvider: TraceContextProvider? = null,
) {
    fun record(
        tenantId: Identifier,
        resourceType: String,
        resourceId: Identifier,
        action: String,
        operatorId: Identifier? = null,
        details: Map<String, String> = emptyMap(),
        operatorName: String? = null,
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
            operatorName = operatorName,
        )
        auditRecordRepository.append(record)
        operationLogRepository?.append(
            OperationLogRecord(
                id = record.id,
                tenantId = record.tenantId,
                resourceType = record.resourceType,
                resourceId = record.resourceId,
                action = record.action,
                operatorId = record.operatorId,
                operatorName = record.operatorName,
                traceId = traceContextProvider?.currentTraceContext()?.traceId,
                details = record.details,
                createdAt = record.createdAt,
            ),
        )
        return record
    }
}
