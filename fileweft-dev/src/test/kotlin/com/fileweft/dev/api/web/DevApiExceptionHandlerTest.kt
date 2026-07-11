package com.fileweft.dev.api.web

import com.fileweft.application.publish.ActiveDocumentReviewWorkflowException
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.InvalidLifecycleTransitionException
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.document.LifecycleState
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals

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
}
