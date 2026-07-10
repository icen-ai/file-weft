package com.fileweft.core.result

import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DoctorReportTest {
    @Test
    fun `calculates the most severe check status`() {
        val report = DoctorReport(
            tenantId = Identifier("tenant-1"),
            documentId = Identifier("document-1"),
            checks = listOf(
                check("storage", DoctorStatus.HEALTHY),
                check("connector", DoctorStatus.WARNING),
                check("permission", DoctorStatus.ERROR),
            ),
            inspectedAt = 100,
        )

        assertEquals(DoctorStatus.ERROR, report.status)
        assertEquals(listOf("storage", "connector", "permission"), report.checks.map { it.checkerName })
    }

    @Test
    fun `uses skipped status when no check can run`() {
        val report = DoctorReport(Identifier("tenant-1"), checks = emptyList(), inspectedAt = 0)

        assertEquals(DoctorStatus.SKIPPED, report.status)
    }

    @Test
    fun `defensively copies context evidence and report checks`() {
        val attributes = linkedMapOf("trace" to "trace-1")
        val evidence = linkedMapOf("location" to "objects/1")
        val checks = mutableListOf(check("storage", DoctorStatus.HEALTHY, evidence))
        val context = DoctorCheckContext(Identifier("tenant-1"), attributes = attributes)
        val report = DoctorReport(Identifier("tenant-1"), checks = checks, inspectedAt = 1)
        attributes["trace"] = "changed"
        evidence["location"] = "changed"
        checks.clear()

        assertEquals("trace-1", context.attributes["trace"])
        assertEquals("objects/1", report.checks.single().evidence["location"])
        assertEquals(1, report.checks.size)
        assertFailsWith<UnsupportedOperationException> {
            (context.attributes as MutableMap<String, String>)["new"] = "value"
        }
        assertFailsWith<UnsupportedOperationException> {
            (report.checks as MutableList<DoctorCheckResult>).add(check("extra", DoctorStatus.HEALTHY))
        }
    }

    @Test
    fun `rejects duplicate checker names and invalid operator content`() {
        assertFailsWith<IllegalArgumentException> {
            DoctorReport(
                Identifier("tenant-1"),
                checks = listOf(check("storage", DoctorStatus.HEALTHY), check("storage", DoctorStatus.ERROR)),
                inspectedAt = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DoctorCheckResult("storage", DoctorStatus.ERROR, " ")
        }
        assertFailsWith<IllegalArgumentException> {
            DoctorCheckResult("storage", DoctorStatus.ERROR, "Unavailable", repairSuggestion = " ")
        }
    }

    private fun check(
        name: String,
        status: DoctorStatus,
        evidence: Map<String, String> = emptyMap(),
    ): DoctorCheckResult = DoctorCheckResult(name, status, "Check completed.", evidence)
}
