package ai.icen.fw.application.audit

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.domain.audit.AuditRecord
import ai.icen.fw.domain.audit.AuditRecordRepository
import ai.icen.fw.domain.operation.OperationLogRecord
import ai.icen.fw.domain.operation.OperationLogRepository
import ai.icen.fw.spi.observability.TraceContextProvider
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class AuditTrailTest {
    @Test
    fun `appends generated immutable audit evidence`() {
        val repository = RecordingRepository()
        val record = AuditTrail(
            repository,
            object : IdentifierGenerator { override fun nextId(): Identifier = Identifier("audit-1") },
            Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
        ).record(
            tenantId = Identifier("tenant-1"),
            resourceType = "DOCUMENT",
            resourceId = Identifier("document-1"),
            action = "document.sync",
            operatorId = Identifier("10001"),
            details = mapOf("connector" to "test"),
            operatorName = "外部审批人",
        )

        assertEquals(record, repository.records.single())
        assertEquals("audit-1", record.id.value)
        assertEquals(100, record.createdAt)
        assertEquals("test", record.details["connector"])
        assertEquals("10001", record.operatorId?.value)
        assertEquals("外部审批人", record.operatorName)
    }

    @Test
    fun `mirrors audit evidence into operation history with the active trace`() {
        val audits = RecordingRepository()
        val operations = RecordingOperationLogs()
        AuditTrail(
            audits,
            object : IdentifierGenerator { override fun nextId(): Identifier = Identifier("audit-2") },
            Clock.fixed(Instant.ofEpochMilli(200), ZoneOffset.UTC),
            operations,
            object : TraceContextProvider {
                override fun currentTraceContext(): TraceContext = TraceContext(Identifier("trace-acceptance-1"))
            },
        ).record(
            tenantId = Identifier("tenant-1"),
            resourceType = "DOCUMENT",
            resourceId = Identifier("document-1"),
            action = "document:create",
            operatorId = Identifier("external-user-id"),
            operatorName = "外部编辑者",
            details = mapOf("source" to "HTTP"),
        )

        val operation = operations.records.single()
        assertEquals("audit-2", operation.id.value)
        assertEquals("trace-acceptance-1", operation.traceId?.value)
        assertEquals("external-user-id", operation.operatorId?.value)
        assertEquals("外部编辑者", operation.operatorName)
        assertEquals("HTTP", operation.details["source"])
        assertEquals(200, operation.createdAt)
    }

    private class RecordingRepository : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        override fun append(record: AuditRecord) { records += record }
        override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> = emptyList()
    }

    private class RecordingOperationLogs : OperationLogRepository {
        val records = mutableListOf<OperationLogRecord>()

        override fun append(record: OperationLogRecord) {
            records += record
        }

        override fun findByResource(
            tenantId: Identifier,
            resourceType: String,
            resourceId: Identifier,
            limit: Int,
        ): List<OperationLogRecord> = emptyList()
    }
}
