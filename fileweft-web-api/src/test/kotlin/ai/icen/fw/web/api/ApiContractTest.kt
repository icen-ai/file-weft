package ai.icen.fw.web.api

import ai.icen.fw.web.api.v1.doctor.DoctorCheckDto
import ai.icen.fw.web.api.v1.doctor.DoctorReportDto
import ai.icen.fw.web.api.v1.doctor.ScheduleDocumentDoctorCommand
import ai.icen.fw.web.api.v1.document.AddDocumentVersionCommand
import ai.icen.fw.web.api.v1.document.CreateDocumentDraftCommand
import ai.icen.fw.web.api.v1.document.DocumentCommandResultDto
import ai.icen.fw.web.api.v1.document.DocumentDetailDto
import ai.icen.fw.web.api.v1.document.DocumentDto
import ai.icen.fw.web.api.v1.document.DocumentPageQuery
import ai.icen.fw.web.api.v1.document.DocumentVersionDto
import ai.icen.fw.web.api.v1.document.RenameDocumentCommand
import ai.icen.fw.web.api.v1.document.RenameDocumentRequest
import ai.icen.fw.web.api.v1.workflow.ApproveWorkflowTaskCommand
import ai.icen.fw.web.api.v1.workflow.RejectWorkflowTaskCommand
import ai.icen.fw.web.api.v1.workflow.SubmitDocumentReviewCommand
import ai.icen.fw.web.api.v1.workflow.WorkflowTaskDto
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiContractTest {

    @Test
    fun `uses a stable response envelope for success and failure`() {
        val document = document()

        val success = ApiResponse.success(document, "Document loaded", "trace-1")
        val failure = ApiResponse.failure<DocumentDto>(
            ApiError(ApiErrorCodes.NOT_FOUND, "Document was not found."),
            "trace-2",
        )

        assertTrue(success.isSuccess())
        assertFalse(success.isFailure())
        assertEquals(ApiResponse.SUCCESS_CODE, success.code)
        assertEquals("Document loaded", success.message)
        assertEquals(document, success.data)
        assertNull(success.error)
        assertEquals("trace-1", success.traceId)

        assertTrue(failure.isFailure())
        assertFalse(failure.isSuccess())
        assertEquals(ApiErrorCodes.NOT_FOUND, failure.code)
        assertEquals("Document was not found.", failure.message)
        assertNull(failure.data)
        assertEquals(ApiErrorCodes.NOT_FOUND, failure.error?.code)
        assertEquals(ApiErrorCodes.OK, ApiResponse.SUCCESS_CODE)
        assertEquals("INVALID_REQUEST", ApiErrorCodes.INVALID_REQUEST)
        assertEquals("UNAUTHENTICATED", ApiErrorCodes.UNAUTHENTICATED)
        assertEquals("FORBIDDEN", ApiErrorCodes.FORBIDDEN)
        assertEquals("METHOD_NOT_ALLOWED", ApiErrorCodes.METHOD_NOT_ALLOWED)
        assertEquals("RANGE_NOT_SUPPORTED", ApiErrorCodes.RANGE_NOT_SUPPORTED)
        assertEquals("CONFLICT", ApiErrorCodes.CONFLICT)
        assertEquals("FEATURE_UNAVAILABLE", ApiErrorCodes.FEATURE_UNAVAILABLE)
        assertEquals("CONTENT_UNAVAILABLE", ApiErrorCodes.CONTENT_UNAVAILABLE)
        assertEquals("INTERNAL_ERROR", ApiErrorCodes.INTERNAL_ERROR)
    }

    @Test
    fun `defensively copies pages doctor payloads and document details`() {
        val sourceItems = mutableListOf(document())
        val page = ApiPage(sourceItems, "cursor-2", 1)
        val checks = mutableListOf(DoctorCheckDto("storage", "HEALTHY", "Object exists."))
        val report = DoctorReportDto("document-1", "HEALTHY", checks, 10)
        val versions = mutableListOf(version())
        val detail = DocumentDetailDto(document(), versions)

        sourceItems.clear()
        checks.clear()
        versions.clear()

        assertEquals(1, page.items.size)
        assertEquals("cursor-2", page.nextCursor)
        assertEquals(1, report.checks.size)
        assertEquals(1, detail.versions.size)
        assertThrows<UnsupportedOperationException> {
            (page.items as MutableList<DocumentDto>).clear()
        }
        assertThrows<UnsupportedOperationException> {
            (detail.versions as MutableList<DocumentVersionDto>).clear()
        }
    }

    @Test
    fun `rejects inconsistent or unbounded public values`() {
        assertThrows<IllegalArgumentException> { ApiPage(emptyList<String>(), " ") }
        assertThrows<IllegalArgumentException> { ApiPage(listOf("one"), total = -1) }
        assertThrows<IllegalArgumentException> { ApiPage(listOf("one"), total = 0) }
        assertThrows<IllegalArgumentException> { DocumentPageQuery(limit = 101) }
        assertThrows<IllegalArgumentException> { DocumentPageQuery(cursor = "x".repeat(513)) }
        assertThrows<IllegalArgumentException> { DocumentDto("document-1", "DOC-1", "Title", "DRAFT", 2, 1) }
        assertThrows<IllegalArgumentException> {
            DocumentDetailDto(document(), listOf(version("another-version")))
        }
        assertThrows<IllegalArgumentException> {
            DoctorReportDto(
                "document-1",
                "HEALTHY",
                listOf(
                    DoctorCheckDto("storage", "HEALTHY", "OK"),
                    DoctorCheckDto("storage", "WARNING", "Slow"),
                ),
                1,
            )
        }
    }

    @Test
    fun `write command contracts carry no caller tenant or identity`() {
        val commandTypes = listOf(
            CreateDocumentDraftCommand::class.java,
            RenameDocumentCommand::class.java,
            RenameDocumentRequest::class.java,
            AddDocumentVersionCommand::class.java,
            DocumentPageQuery::class.java,
            SubmitDocumentReviewCommand::class.java,
            ApproveWorkflowTaskCommand::class.java,
            RejectWorkflowTaskCommand::class.java,
            ScheduleDocumentDoctorCommand::class.java,
        )
        val forbiddenInputs = setOf(
            "tenantId",
            "tenant",
            "userId",
            "operatorId",
            "actorId",
            "reviewerId",
            "requestedReviewerId",
        )

        val commandFields = commandTypes.flatMap { type ->
            type.declaredFields.map { field -> field.name }
        }.toSet()

        assertTrue(commandFields.intersect(forbiddenInputs).isEmpty())
        assertEquals("route-1", SubmitDocumentReviewCommand("route-1").reviewRouteId)
        assertEquals("Approved", ApproveWorkflowTaskCommand("Approved").comment)
        assertEquals("Rejected", RejectWorkflowTaskCommand("Rejected").comment)

        val renameRequest = RenameDocumentRequest().apply { title = "清税证明" }
        assertEquals("清税证明", RenameDocumentCommand(requireNotNull(renameRequest.title)).title)
    }

    @Test
    fun `public responses do not expose raw diagnostic evidence host identities or integrity hashes`() {
        val workflowFields = WorkflowTaskDto::class.java.declaredFields.map { it.name }.toSet()
        val doctorFields = DoctorCheckDto::class.java.declaredFields.map { it.name }.toSet()
        val errorFields = ApiError::class.java.declaredFields.map { it.name }.toSet()
        val documentFields = DocumentDto::class.java.declaredFields.map { it.name }.toSet()
        val versionFields = DocumentVersionDto::class.java.declaredFields.map { it.name }.toSet()
        val commandResultFields = DocumentCommandResultDto::class.java.declaredFields.map { it.name }.toSet()

        assertTrue("assignedToCurrentUser" in workflowFields)
        assertTrue(setOf("assigneeId", "assignee", "comment", "reviewerId").intersect(workflowFields).isEmpty())
        assertTrue(setOf("evidence", "storagePath", "connectorId", "externalId").intersect(doctorFields).isEmpty())
        assertTrue(setOf("attributes", "stackTrace", "cause").intersect(errorFields).isEmpty())
        assertTrue(setOf("tenantId", "assetId", "storagePath", "fileObjectId").intersect(documentFields).isEmpty())
        assertTrue(setOf("contentHash", "storagePath", "fileObjectId", "tenantId").intersect(versionFields).isEmpty())
        assertTrue(setOf("tenantId", "assetId", "fileObjectId", "storagePath").intersect(commandResultFields).isEmpty())
    }

    @Test
    fun `write commands reject unsafe text while preserving UTF8 content`() {
        val command = CreateDocumentDraftCommand("DOC-1", "清税证明", "证明.pdf", 5, folderId = "财务")

        assertEquals("清税证明", command.title)
        assertEquals("财务", command.folderId)
        assertThrows<IllegalArgumentException> {
            CreateDocumentDraftCommand(" ", "Title", "proof.pdf", 5)
        }
        assertThrows<IllegalArgumentException> {
            CreateDocumentDraftCommand("DOC-1", "Title\r\nInjected", "proof.pdf", 5)
        }
        assertThrows<IllegalArgumentException> {
            AddDocumentVersionCommand("1.0", "proof\u0000.pdf", 5)
        }
        assertThrows<IllegalArgumentException> {
            AddDocumentVersionCommand("1.0", "proof.pdf", -1)
        }
        assertThrows<IllegalArgumentException> {
            CreateDocumentDraftCommand("DOC-1", "Title", "../proof.pdf", 5)
        }
        assertThrows<IllegalArgumentException> {
            AddDocumentVersionCommand("1.0", "folder\\proof.pdf", 5)
        }
        assertThrows<IllegalArgumentException> {
            RenameDocumentCommand(" ")
        }

    }

    @Test
    fun `command results expose only the committed public document state`() {
        val result = DocumentCommandResultDto("document-1", "version-1")

        assertEquals("document-1", result.documentId)
        assertEquals("version-1", result.versionId)
        assertEquals(setOf("documentId", "versionId"), DocumentCommandResultDto::class.java.declaredFields.map { it.name }.toSet())
    }

    private fun document(): DocumentDto =
        DocumentDto("document-1", "DOC-1", "Tax certificate", "DRAFT", 10, 10, "version-1", "finance")

    private fun version(id: String = "version-1"): DocumentVersionDto =
        DocumentVersionDto(id, "1.0", "proof.pdf", 5, 10, 10, "application/pdf")
}
