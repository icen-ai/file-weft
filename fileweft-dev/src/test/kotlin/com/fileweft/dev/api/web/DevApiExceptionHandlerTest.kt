package com.fileweft.dev.api.web

import com.fileweft.application.publish.ActiveDocumentReviewWorkflowException
import com.fileweft.application.security.ApplicationForbiddenException
import com.fileweft.application.security.ApplicationUnauthenticatedException
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.InvalidLifecycleTransitionException
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.document.LifecycleState
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
