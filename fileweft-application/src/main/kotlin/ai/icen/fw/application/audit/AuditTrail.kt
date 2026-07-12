package ai.icen.fw.application.audit

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.audit.AuditRecord
import ai.icen.fw.domain.audit.AuditRecordRepository
import ai.icen.fw.domain.operation.OperationLogRecord
import ai.icen.fw.domain.operation.OperationLogRepository
import ai.icen.fw.spi.observability.TraceContextProvider
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
