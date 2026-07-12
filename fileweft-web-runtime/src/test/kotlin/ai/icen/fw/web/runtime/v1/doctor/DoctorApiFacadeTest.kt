package ai.icen.fw.web.runtime.v1.doctor

import ai.icen.fw.application.doctor.DocumentDoctorTaskReceipt
import ai.icen.fw.application.doctor.DocumentDoctorTaskView
import ai.icen.fw.application.task.BackgroundTaskStatus
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorReport
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.web.runtime.v1.V1FeatureUnavailableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoctorApiFacadeTest {
    @Test
    fun `normalizes known checks and aggregates every unknown extension without copying raw text`() {
        val report = report(
            documentId = DOCUMENT_ID,
            checks = listOf(
                maliciousCheck("storage", DoctorStatus.WARNING, "s3://private-bucket/customer/object"),
                maliciousCheck("vendor-secret-checker", DoctorStatus.HEALTHY, "connector-secret=first"),
                maliciousCheck("jdbc://internal/database\u0007", DoctorStatus.ERROR, "password=second"),
            ),
        )
        val facade = DoctorApiFacade.forTesting(documentDoctor = { report })

        val dto = facade.inspectDocument(DOCUMENT_ID.value)

        assertEquals(DoctorStatus.ERROR.name, dto.status)
        assertEquals(listOf("storage", "extensions"), dto.checks.map { check -> check.checkerName })
        assertEquals("Storage check requires attention.", dto.checks[0].reason)
        assertEquals("Extension check failed.", dto.checks[1].reason)
        assertEquals(
            "Review authorized operational logs, repair the affected component, and run Doctor again.",
            dto.checks[1].repairSuggestion,
        )
        val publicText = dto.checks.joinToString("|") { check ->
            listOf(check.checkerName, check.status, check.reason, check.repairSuggestion).joinToString("|")
        }
        listOf(
            "s3://",
            "private-bucket",
            "vendor-secret-checker",
            "connector-secret",
            "jdbc://",
            "password",
            "java.lang",
        ).forEach { secret ->
            assertFalse(publicText.contains(secret), "Public Doctor projection leaked $secret")
        }
    }

    @Test
    fun `maps task receipt task detail and completed report to stable public DTOs`() {
        val report = report(DOCUMENT_ID, listOf(healthy("lifecycle")))
        val taskView = DocumentDoctorTaskView(
            tenantId = TENANT_ID,
            taskId = TASK_ID,
            documentId = DOCUMENT_ID,
            status = BackgroundTaskStatus.SUCCESS,
            createdTime = 10,
            updatedTime = 20,
            report = report,
        )
        val facade = DoctorApiFacade.forTesting(
            scheduler = { documentId, _ -> DocumentDoctorTaskReceipt(TASK_ID, documentId) },
            taskQuery = { _, _ -> taskView },
        )

        val receipt = facade.scheduleDocument(DOCUMENT_ID.value, "doctor-key-1")
        val detail = facade.task(DOCUMENT_ID.value, TASK_ID.value)

        assertEquals(TASK_ID.value, receipt.taskId)
        assertEquals("PENDING", receipt.status)
        assertEquals("SUCCESS", detail.task.status)
        assertEquals(10, detail.task.createdTime)
        assertEquals("lifecycle", detail.report?.checks?.single()?.checkerName)
    }

    @Test
    fun `maps pending task without inventing a report`() {
        val taskView = DocumentDoctorTaskView(
            tenantId = TENANT_ID,
            taskId = TASK_ID,
            documentId = DOCUMENT_ID,
            status = BackgroundTaskStatus.PENDING,
            createdTime = 10,
            updatedTime = 10,
        )
        val facade = DoctorApiFacade.forTesting(taskQuery = { _, _ -> taskView })

        val detail = facade.task(DOCUMENT_ID.value, TASK_ID.value)

        assertNull(detail.report)
    }

    @Test
    fun `maps system report without exposing tenant or document identity`() {
        val facade = DoctorApiFacade.forTesting(
            systemDoctor = { report(null, listOf(healthy("connector"))) },
        )

        val dto = facade.inspectSystem()

        assertEquals("HEALTHY", dto.status)
        assertEquals("connector", dto.checks.single().checkerName)
        assertTrue(dto.javaClass.declaredFields.none { field -> field.name == "tenantId" || field.name == "documentId" })
    }

    @Test
    fun `uses fixed public text for every Doctor status`() {
        val facade = DoctorApiFacade.forTesting(
            documentDoctor = {
                report(
                    DOCUMENT_ID,
                    listOf(
                        DoctorCheckResult("lifecycle", DoctorStatus.HEALTHY, "raw-healthy"),
                        DoctorCheckResult("workflow", DoctorStatus.WARNING, "raw-warning"),
                        DoctorCheckResult("storage", DoctorStatus.ERROR, "raw-error"),
                        DoctorCheckResult("catalog", DoctorStatus.SKIPPED, "raw-skipped"),
                    ),
                )
            },
        )

        val checks = facade.inspectDocument(DOCUMENT_ID.value).checks.associateBy { check -> check.checkerName }

        assertEquals("Lifecycle check passed.", checks.getValue("lifecycle").reason)
        assertEquals("Workflow check requires attention.", checks.getValue("workflow").reason)
        assertEquals("Storage check failed.", checks.getValue("storage").reason)
        assertEquals("Catalog check was skipped.", checks.getValue("catalog").reason)
        assertNull(checks.getValue("lifecycle").repairSuggestion)
        assertNull(checks.getValue("catalog").repairSuggestion)
        assertTrue(checks.getValue("workflow").repairSuggestion?.startsWith("Review authorized") == true)
        assertTrue(checks.getValue("storage").repairSuggestion?.startsWith("Review authorized") == true)
    }

    @Test
    fun `fails closed for absent capabilities and mismatched application results`() {
        val unavailable = DoctorApiFacade.forTesting()

        assertThrows<V1FeatureUnavailableException> { unavailable.inspectDocument(DOCUMENT_ID.value) }
        assertThrows<V1FeatureUnavailableException> { unavailable.scheduleDocument(DOCUMENT_ID.value, "doctor-key-1") }
        assertThrows<V1FeatureUnavailableException> { unavailable.task(DOCUMENT_ID.value, TASK_ID.value) }
        assertThrows<V1FeatureUnavailableException> { unavailable.inspectSystem() }

        val wrongDocument = DoctorApiFacade.forTesting(
            documentDoctor = { report(Identifier("other-document"), listOf(healthy("storage"))) },
        )
        assertThrows<IllegalStateException> { wrongDocument.inspectDocument(DOCUMENT_ID.value) }

        val wrongSystem = DoctorApiFacade.forTesting(
            systemDoctor = { report(DOCUMENT_ID, listOf(healthy("connector"))) },
        )
        assertThrows<IllegalStateException> { wrongSystem.inspectSystem() }
    }

    @Test
    fun `validates path identifiers and idempotency keys before invoking application capabilities`() {
        var schedules = 0
        val facade = DoctorApiFacade.forTesting(
            scheduler = { documentId, _ ->
                schedules++
                DocumentDoctorTaskReceipt(TASK_ID, documentId)
            },
        )

        assertThrows<IllegalArgumentException> { facade.scheduleDocument(" ", "doctor-key-1") }
        assertThrows<IllegalArgumentException> { facade.scheduleDocument(DOCUMENT_ID.value, "contains space") }
        assertEquals(0, schedules)
    }

    private fun report(
        documentId: Identifier?,
        checks: List<DoctorCheckResult>,
    ): DoctorReport = DoctorReport(TENANT_ID, documentId, checks, 100)

    private fun healthy(name: String): DoctorCheckResult =
        DoctorCheckResult(name, DoctorStatus.HEALTHY, "raw health text must not be copied")

    private fun maliciousCheck(name: String, status: DoctorStatus, secret: String): DoctorCheckResult =
        DoctorCheckResult(
            checkerName = name,
            status = status,
            reason = secret,
            evidence = mapOf(
                "exceptionType" to "com.vendor.internal.SecretConnectorException",
                "storagePath" to "s3://private-bucket/customer/object",
                "credential" to "connector-secret=password",
            ),
            repairSuggestion = "Run com.vendor.internal.RepairTool with password=secret.",
        )

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val DOCUMENT_ID = Identifier("document-1")
        val TASK_ID = Identifier("task-1")
    }
}
