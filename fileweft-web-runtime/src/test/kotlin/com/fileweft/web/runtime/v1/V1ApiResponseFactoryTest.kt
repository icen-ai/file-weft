package com.fileweft.web.runtime.v1

import com.fileweft.application.document.DocumentFolderReadAccessUnavailableException
import com.fileweft.application.document.DocumentContentUnavailableException
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.idempotency.IdempotencyInProgressException
import com.fileweft.application.idempotency.IdempotencyKeyConflictException
import com.fileweft.application.offline.DocumentRestoreConflictException
import com.fileweft.application.offline.DocumentRestoreConflictReason
import com.fileweft.application.workflow.DocumentReviewConflictException
import com.fileweft.application.upload.StoredObjectIntegrityException
import com.fileweft.application.security.ApplicationForbiddenException
import com.fileweft.application.security.ApplicationUnauthenticatedException
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.DocumentNumberAlreadyExistsException
import com.fileweft.domain.workflow.WorkflowDecisionConflictException
import com.fileweft.domain.workflow.WorkflowTaskAssignmentDeniedException
import com.fileweft.domain.workflow.WorkflowTaskNotFoundException
import com.fileweft.web.api.ApiErrorCodes
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V1ApiResponseFactoryTest {
    private val responses = V1ApiResponseFactory()

    @Test
    fun `maps trusted authorization and document failures to stable statuses without exposing causes`() {
        val cases = listOf(
            V1MethodNotAllowedException() to Triple(405, ApiErrorCodes.METHOD_NOT_ALLOWED, "Method is not allowed."),
            V1RangeNotSupportedException() to Triple(416, ApiErrorCodes.RANGE_NOT_SUPPORTED, "Range requests are not supported."),
            ApplicationUnauthenticatedException("host identity is unavailable") to Triple(401, ApiErrorCodes.UNAUTHENTICATED, "Authentication is required."),
            ApplicationForbiddenException("policy=restricted-folder") to Triple(403, ApiErrorCodes.FORBIDDEN, "Access denied."),
            WorkflowTaskAssignmentDeniedException(Identifier("private-task")) to Triple(403, ApiErrorCodes.FORBIDDEN, "Access denied."),
            DocumentNotFoundException(Identifier("private-document")) to Triple(404, ApiErrorCodes.NOT_FOUND, "Resource was not found."),
            WorkflowTaskNotFoundException(Identifier("private-workflow"), Identifier("private-task")) to Triple(
                404,
                ApiErrorCodes.NOT_FOUND,
                "Resource was not found.",
            ),
            DocumentFolderReadAccessUnavailableException() to Triple(503, ApiErrorCodes.FEATURE_UNAVAILABLE, "The requested feature is unavailable."),
            V1FeatureUnavailableException() to Triple(503, ApiErrorCodes.FEATURE_UNAVAILABLE, "The requested feature is unavailable."),
            DocumentNumberAlreadyExistsException("private-number") to Triple(
                409,
                ApiErrorCodes.CONFLICT,
                "Request conflicts with the current resource state.",
            ),
            WorkflowDecisionConflictException("private workflow state") to Triple(
                409,
                ApiErrorCodes.CONFLICT,
                "Request conflicts with the current resource state.",
            ),
            DocumentReviewConflictException("private review route race") to Triple(
                409,
                ApiErrorCodes.CONFLICT,
                "Request conflicts with the current resource state.",
            ),
            IdempotencyKeyConflictException() to Triple(
                409,
                ApiErrorCodes.CONFLICT,
                "Request conflicts with the current resource state.",
            ),
            DocumentRestoreConflictException(DocumentRestoreConflictReason.WITHDRAWAL_INCOMPLETE) to Triple(
                409,
                ApiErrorCodes.CONFLICT,
                "Request conflicts with the current resource state.",
            ),
            DocumentContentUnavailableException(
                "s3://private-bucket/internal-object is unavailable",
                IllegalStateException("sdk credential detail"),
            ) to Triple(
                503,
                ApiErrorCodes.CONTENT_UNAVAILABLE,
                "Document content is unavailable.",
            ),
            IllegalArgumentException("cursor=secret-token") to Triple(400, ApiErrorCodes.INVALID_REQUEST, "Request is invalid."),
            StoredObjectIntegrityException("storage acknowledged a private path") to Triple(
                500,
                ApiErrorCodes.INTERNAL_ERROR,
                "An unexpected error occurred.",
            ),
            IdempotencyInProgressException() to Triple(
                500,
                ApiErrorCodes.INTERNAL_ERROR,
                "An unexpected error occurred.",
            ),
            IllegalStateException("jdbc://internal") to Triple(500, ApiErrorCodes.INTERNAL_ERROR, "An unexpected error occurred."),
        )

        cases.forEach { (failure, expected) ->
            val result = responses.failure(failure, "trace-1")

            assertEquals(expected.first, result.status.statusCode)
            assertEquals(expected.second, result.response.code)
            assertEquals(expected.third, result.response.message)
            assertEquals(expected.second, result.response.error?.code)
            assertEquals(expected.third, result.response.error?.message)
            assertEquals("trace-1", result.response.traceId)
            if (failure is V1MethodNotAllowedException || failure is V1RangeNotSupportedException) {
                assertEquals(failure.message, result.response.message)
            } else {
                assertFalse(result.response.message.contains(failure.message.orEmpty()))
            }
        }
    }

    @Test
    fun `only retains bounded non control trace identifiers`() {
        assertEquals("trace-ok", responses.success("payload", "trace-ok").traceId)
        assertNull(responses.success("payload", " ").traceId)
        assertNull(responses.success("payload", "trace\u0000bad").traceId)
        assertNull(responses.success("payload", "t".repeat(129)).traceId)
    }

    @Test
    fun `returns one stable success envelope`() {
        val result = responses.success("payload", "trace-1")

        assertTrue(result.isSuccess())
        assertEquals("OK", result.code)
        assertEquals("payload", result.data)
        assertNull(result.error)
    }
}
