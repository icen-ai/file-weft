package ai.icen.fw.web.api

import ai.icen.fw.web.api.v1.document.DocumentLifecycleCommandResultDto
import ai.icen.fw.web.api.v1.document.PublishDocumentCommand
import ai.icen.fw.web.api.v1.document.PublishDocumentRequest
import ai.icen.fw.web.api.v1.workflow.ApproveWorkflowTaskCommand
import ai.icen.fw.web.api.v1.workflow.ApproveWorkflowTaskRequest
import ai.icen.fw.web.api.v1.workflow.RejectWorkflowTaskCommand
import ai.icen.fw.web.api.v1.workflow.RejectWorkflowTaskRequest
import ai.icen.fw.web.api.v1.workflow.SubmitDocumentReviewCommand
import ai.icen.fw.web.api.v1.workflow.SubmitDocumentReviewRequest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LifecycleCommandContractsTest {
    @Test
    fun `keeps committed lifecycle identifiers stable without post commit validation`() {
        val result = DocumentLifecycleCommandResultDto(
            documentId = "",
            workflowId = "workflow\u0000committed",
            taskId = "task/committed",
        )

        assertEquals("", result.documentId)
        assertEquals("workflow\u0000committed", result.workflowId)
        assertEquals("task/committed", result.taskId)
        assertNull(DocumentLifecycleCommandResultDto("document-1").workflowId)
        assertNull(DocumentLifecycleCommandResultDto("document-1").taskId)
    }

    @Test
    fun `converts mutable lifecycle request beans into validated immutable commands`() {
        val publishRequest = PublishDocumentRequest().apply { deliveryProfileId = "regulated" }
        val submitRequest = SubmitDocumentReviewRequest().apply { reviewRouteId = "dual-control" }
        val approveRequest = ApproveWorkflowTaskRequest().apply {
            comment = "Approved"
            deliveryProfileId = "regulated"
        }
        val rejectRequest = RejectWorkflowTaskRequest().apply { comment = "Needs correction" }

        assertEquals("regulated", PublishDocumentCommand(publishRequest.deliveryProfileId).deliveryProfileId)
        assertEquals("dual-control", SubmitDocumentReviewCommand(submitRequest.reviewRouteId).reviewRouteId)
        assertEquals("Approved", ApproveWorkflowTaskCommand(approveRequest.comment, approveRequest.deliveryProfileId).comment)
        assertEquals(
            "regulated",
            ApproveWorkflowTaskCommand(approveRequest.comment, approveRequest.deliveryProfileId).deliveryProfileId,
        )
        assertEquals("Needs correction", RejectWorkflowTaskCommand(rejectRequest.comment).comment)
    }

    @Test
    fun `keeps mutable request beans permissive but immutable commands reject invalid text`() {
        val request = ApproveWorkflowTaskRequest().apply { comment = "\r\nunsafe" }

        assertEquals("\r\nunsafe", request.comment)
        assertFailsWith<IllegalArgumentException> {
            ApproveWorkflowTaskCommand(request.comment, request.deliveryProfileId)
        }
        assertFailsWith<IllegalArgumentException> { PublishDocumentCommand(" ") }
        assertFailsWith<IllegalArgumentException> { SubmitDocumentReviewCommand("route\u0000id") }
        assertFailsWith<IllegalArgumentException> { RejectWorkflowTaskCommand("\n") }
    }
}
