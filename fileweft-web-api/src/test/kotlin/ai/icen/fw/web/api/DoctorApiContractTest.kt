package ai.icen.fw.web.api

import ai.icen.fw.web.api.v1.doctor.DoctorCheckDto
import ai.icen.fw.web.api.v1.doctor.DoctorReportDto
import ai.icen.fw.web.api.v1.doctor.DoctorTaskDetailDto
import ai.icen.fw.web.api.v1.doctor.DoctorTaskDto
import ai.icen.fw.web.api.v1.doctor.DoctorTaskReceiptDto
import ai.icen.fw.web.api.v1.doctor.SystemDoctorReportDto
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoctorApiContractTest {
    @Test
    fun `exposes immutable document task and system Doctor contracts`() {
        val source = mutableListOf(DoctorCheckDto("storage", "HEALTHY", "Storage check passed."))
        val system = SystemDoctorReportDto("HEALTHY", source, 100)
        val report = DoctorReportDto("document-1", "HEALTHY", source, 100)
        val task = DoctorTaskDto("task-1", "document-1", "SUCCESS", 90, 100)
        val detail = DoctorTaskDetailDto(task, report)
        val receipt = DoctorTaskReceiptDto("task-1", "document-1", "PENDING")

        source.clear()

        assertEquals(1, system.checks.size)
        assertEquals("task-1", detail.task.id)
        assertEquals("document-1", detail.report?.documentId)
        assertEquals("task-1", receipt.taskId)
        assertThrows<UnsupportedOperationException> {
            (system.checks as MutableList<DoctorCheckDto>).clear()
        }
        assertNull(DoctorTaskDetailDto(task).report)
    }

    @Test
    fun `rejects inconsistent Doctor task details and invalid times`() {
        val task = DoctorTaskDto("task-1", "document-1", "SUCCESS", 90, 100)
        val otherReport = DoctorReportDto("document-2", "HEALTHY", emptyList(), 100)

        assertThrows<IllegalArgumentException> { DoctorTaskDetailDto(task, otherReport) }
        assertThrows<IllegalArgumentException> { DoctorTaskDto("task-1", "document-1", "PENDING", -1, 0) }
        assertThrows<IllegalArgumentException> { SystemDoctorReportDto("HEALTHY", emptyList(), -1) }
    }

    @Test
    fun `Doctor public contracts contain no internal diagnostic or worker fields`() {
        val contractTypes = listOf(
            DoctorCheckDto::class.java,
            DoctorReportDto::class.java,
            SystemDoctorReportDto::class.java,
            DoctorTaskDto::class.java,
            DoctorTaskReceiptDto::class.java,
            DoctorTaskDetailDto::class.java,
        )
        val fields = contractTypes.flatMap { type -> type.declaredFields.map { field -> field.name } }.toSet()
        val forbidden = setOf(
            "tenantId",
            "evidence",
            "storagePath",
            "fileObjectId",
            "connectorId",
            "externalId",
            "exceptionType",
            "payload",
            "requestedBy",
            "lastError",
            "retryCount",
            "leaseOwner",
            "leaseToken",
        )

        assertTrue(fields.intersect(forbidden).isEmpty())
        assertEquals(
            setOf("status", "checks", "inspectedTime"),
            SystemDoctorReportDto::class.java.declaredFields.map { field -> field.name }.toSet(),
        )
        assertEquals(
            setOf("taskId", "documentId", "status"),
            DoctorTaskReceiptDto::class.java.declaredFields.map { field -> field.name }.toSet(),
        )
    }
}
