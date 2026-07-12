package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorReport
import com.fileweft.core.result.DoctorStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoctorReportJsonCodecTest {
    @Test
    fun `writes one canonical schema independent of hostile host mapper features`() {
        val hostMapper = ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS)
            .activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY,
            )
        val codec = DoctorReportJsonCodec(hostMapper)

        val raw = codec.serialize(report())
        val restored = DoctorReportJsonCodec(ObjectMapper()).deserialize(raw, TENANT_ID, DOCUMENT_ID, "HEALTHY")

        assertTrue(raw.contains("\"schemaVersion\":1"))
        assertTrue(raw.contains("\"inspectedAt\":99"))
        assertTrue(raw.contains("\"tenantId\":\"tenant-1\""))
        assertTrue(raw.contains("\"checkerName\":\"storage\""))
        assertFalse(raw.contains("tenant_id"))
        assertFalse(raw.contains("@class"))
        assertFalse(raw.contains("\"schemaVersion\":\"1\""))
        assertFalse(raw.contains("\"inspectedAt\":\"99\""))
        assertEquals(DoctorStatus.HEALTHY, restored.status)
        assertEquals("reachable", restored.checks.single().evidence["state"])
    }

    @Test
    fun `reads an unversioned legacy snake case report after mapper configuration changes`() {
        val raw = """
            {
              "tenant_id":{"value":"tenant-1"},
              "document_id":{"value":"document-1"},
              "checks":[{
                "checker_name":"storage",
                "status":"WARNING",
                "reason":"Legacy warning.",
                "evidence":{"state":"degraded"},
                "repair_suggestion":"Repair storage."
              }],
              "inspected_at":99,
              "status":"WARNING"
            }
        """.trimIndent()

        val restored = DoctorReportJsonCodec(ObjectMapper()).deserialize(
            raw,
            TENANT_ID,
            DOCUMENT_ID,
            "WARNING",
        )

        assertEquals(DoctorStatus.WARNING, restored.status)
        assertEquals("Repair storage.", restored.checks.single().repairSuggestion)
    }

    @Test
    fun `rejects unknown canonical schema versions`() {
        val raw = DoctorReportJsonCodec(ObjectMapper()).serialize(report()).replace(
            "\"schemaVersion\":1",
            "\"schemaVersion\":2",
        )

        assertFailsWith<IllegalStateException> {
            DoctorReportJsonCodec(ObjectMapper()).deserialize(raw, TENANT_ID, DOCUMENT_ID, "HEALTHY")
        }
    }

    @Test
    fun `rejects schema versions that overflow an integer`() {
        val raw = DoctorReportJsonCodec(ObjectMapper()).serialize(report()).replace(
            "\"schemaVersion\":1",
            "\"schemaVersion\":4294967297",
        )

        assertFailsWith<IllegalStateException> {
            DoctorReportJsonCodec(ObjectMapper()).deserialize(raw, TENANT_ID, DOCUMENT_ID, "HEALTHY")
        }
    }

    private fun report() = DoctorReport(
        tenantId = TENANT_ID,
        documentId = DOCUMENT_ID,
        checks = listOf(
            DoctorCheckResult(
                "storage",
                DoctorStatus.HEALTHY,
                "Storage is reachable.",
                mapOf("state" to "reachable"),
            ),
        ),
        inspectedAt = 99,
    )

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val DOCUMENT_ID = Identifier("document-1")
    }
}
