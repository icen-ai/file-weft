package com.fileweft.application.audit

import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
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
            details = mapOf("connector" to "test"),
        )

        assertEquals(record, repository.records.single())
        assertEquals("audit-1", record.id.value)
        assertEquals(100, record.createdAt)
        assertEquals("test", record.details["connector"])
    }

    private class RecordingRepository : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        override fun append(record: AuditRecord) { records += record }
        override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> = emptyList()
    }
}
