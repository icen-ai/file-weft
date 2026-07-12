package ai.icen.fw.web.api

import ai.icen.fw.web.api.v1.audit.DocumentAuditLogDto
import ai.icen.fw.web.api.v1.audit.DocumentAuditLogPageQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentAuditLogApiContractTest {
    @Test
    fun `keeps string operator snapshots and omits sensitive persistence fields`() {
        val dto = DocumentAuditLogDto(
            id = "audit-a",
            action = "document:create",
            createdTime = 100,
            operatorId = "90071992547409930001",
            operatorName = "审核员甲",
            traceId = "trace-a",
        )

        assertEquals("90071992547409930001", dto.operatorId)
        assertEquals("审核员甲", dto.operatorName)
        assertThrows<IllegalArgumentException> { DocumentAuditLogPageQuery(limit = 101) }
        assertThrows<IllegalArgumentException> {
            DocumentAuditLogDto("audit-a", "unsafe\naction", 1)
        }

        val fields = DocumentAuditLogDto::class.java.declaredFields.map { field -> field.name }.toSet()
        assertEquals(setOf("id", "action", "createdTime", "operatorId", "operatorName", "traceId"), fields)
        assertTrue(fields.none { field ->
            field.contains("tenant", ignoreCase = true) ||
                field.contains("detail", ignoreCase = true) ||
                field.contains("payload", ignoreCase = true) ||
                field.contains("storage", ignoreCase = true)
        })
    }
}
