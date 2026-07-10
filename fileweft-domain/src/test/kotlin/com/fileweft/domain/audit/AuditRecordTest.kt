package com.fileweft.domain.audit

import com.fileweft.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuditRecordTest {
    @Test
    fun `defensively copies audit details`() {
        val details = linkedMapOf("status" to "SUCCESS")
        val record = AuditRecord(
            Identifier("audit-1"), Identifier("tenant-1"), "DOCUMENT", Identifier("document-1"),
            "document.sync", details = details, createdAt = 1,
        )
        details["status"] = "CHANGED"

        assertEquals("SUCCESS", record.details["status"])
        assertFailsWith<UnsupportedOperationException> {
            (record.details as MutableMap<String, String>)["new"] = "value"
        }
    }

    @Test
    fun `rejects invalid audit resource action and timestamp`() {
        assertFailsWith<IllegalArgumentException> {
            AuditRecord(Identifier("audit-1"), Identifier("tenant-1"), " ", Identifier("document-1"), "document.sync", createdAt = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            AuditRecord(Identifier("audit-1"), Identifier("tenant-1"), "DOCUMENT", Identifier("document-1"), " ", createdAt = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            AuditRecord(Identifier("audit-1"), Identifier("tenant-1"), "DOCUMENT", Identifier("document-1"), "document.sync", createdAt = -1)
        }
    }
}
