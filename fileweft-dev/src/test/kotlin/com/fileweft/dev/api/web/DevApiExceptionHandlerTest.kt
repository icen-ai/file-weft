package com.fileweft.dev.api.web

import com.fileweft.application.publish.ActiveDocumentReviewWorkflowException
import com.fileweft.application.security.ApplicationForbiddenException
import com.fileweft.application.security.ApplicationUnauthenticatedException
import com.fileweft.application.workflow.DocumentReviewConflictException
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.InvalidLifecycleTransitionException
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.document.LifecycleState
import com.fileweft.domain.workflow.WorkflowConflictException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DevApiExceptionHandlerTest {
    private val handler = DevApiExceptionHandler()

    @Test
    fun `maps an active local review workflow to a conflict response`() {
        val response = handler.activeWorkflowConflict(ActiveDocumentReviewWorkflowException(Identifier("document-1")))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("ACTIVE_REVIEW_WORKFLOW", response.body?.code)
    }

    @Test
    fun `maps an invalid lifecycle transition to a conflict response`() {
        val response = handler.lifecycleConflict(
            InvalidLifecycleTransitionException(LifecycleState.DRAFT, LifecycleCommand.APPROVE),
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("INVALID_LIFECYCLE_TRANSITION", response.body?.code)
    }

    @Test
    fun `maps a document review race to a fixed conflict response`() {
        val response = handler.documentReviewConflict(
            DocumentReviewConflictException("document document-1 changed while route secret-route was resolved"),
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("DOCUMENT_REVIEW_CONFLICT", response.body?.code)
        assertEquals("Document review conflicts with the current workflow state.", response.body?.message)
        assertNotEquals("document document-1 changed while route secret-route was resolved", response.body?.message)
    }

    @Test
    fun `maps a workflow decision race to a fixed conflict response`() {
        val response = handler.workflowConflict(
            WorkflowConflictException("workflow workflow-1 task task-2 was already approved"),
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("WORKFLOW_CONFLICT", response.body?.code)
        assertEquals("Workflow command conflicts with the current workflow state.", response.body?.message)
        assertNotEquals("workflow workflow-1 task task-2 was already approved", response.body?.message)
    }

    @Test
    fun `maps missing resources without exposing their type or identifier`() {
        val response = handler.notFound(NoSuchElementException("Workflow private-workflow was not found."))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("NOT_FOUND", response.body?.code)
        assertEquals("Resource was not found.", response.body?.message)
        assertNotEquals("Workflow private-workflow was not found.", response.body?.message)
    }

    @Test
    fun `maps generic security failure to a fixed forbidden response without exposing its cause`() {
        val response = handler.forbidden(SecurityException("Document read denied for tenant tenant-a."))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("FORBIDDEN", response.body?.code)
        assertEquals("Access denied.", response.body?.message)
        assertNotEquals("Document read denied for tenant tenant-a.", response.body?.message)
    }

    @Test
    fun `maps invalid input to a fixed response without exposing its cause`() {
        val response = handler.invalid(IllegalArgumentException("folderId=finance is restricted for tenant tenant-a"))

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals("INVALID_REQUEST", response.body?.code)
        assertEquals("Request is invalid.", response.body?.message)
        assertNotEquals("folderId=finance is restricted for tenant tenant-a", response.body?.message)
    }

    @Test
    fun `maps missing trusted identity to unauthenticated without exposing a cause`() {
        val response = handler.unauthenticated(ApplicationUnauthenticatedException("host identity unavailable"))

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals("UNAUTHENTICATED", response.body?.code)
        assertEquals("Authentication is required.", response.body?.message)
    }

    @Test
    fun `maps policy denial to a safe forbidden response without exposing the policy reason`() {
        val response = handler.applicationForbidden(ApplicationForbiddenException("role=admin is required"))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("FORBIDDEN", response.body?.code)
        assertEquals("Access denied.", response.body?.message)
    }
}
