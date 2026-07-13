package ai.icen.fw.web.runtime.v1

import ai.icen.fw.application.document.DocumentFolderReadAccessUnavailableException
import ai.icen.fw.application.document.DocumentContentUnavailableException
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.idempotency.IdempotencyConflictException
import ai.icen.fw.application.offline.DocumentRestoreConflictException
import ai.icen.fw.application.publish.ActiveDocumentReviewWorkflowException
import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.security.ApplicationUnauthenticatedException
import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.application.upload.ResumableUploadStateException
import ai.icen.fw.application.upload.ResumableUploadUnavailableException
import ai.icen.fw.domain.document.DocumentConflictException
import ai.icen.fw.domain.workflow.WorkflowConflictException
import ai.icen.fw.web.api.ApiError
import ai.icen.fw.web.api.ApiErrorCodes
import ai.icen.fw.web.api.ApiResponse

/**
 * Transport-neutral v1 response and failure classifier shared by the Boot 2
 * and Boot 3 MVC adapters. It intentionally returns only fixed public
 * messages: exception messages may contain host policy, storage, or database
 * details and must never become the HTTP contract.
 */
class V1ApiResponseFactory {
    @JvmOverloads
    fun <T> success(data: T? = null, traceId: String? = null): ApiResponse<T> =
        ApiResponse.success(data = data, traceId = safeTraceId(traceId))

    @JvmOverloads
    fun failure(failure: Throwable, traceId: String? = null): ApiHttpFailure {
        val mapped = when (failure) {
            is V1MethodNotAllowedException -> MappedFailure(
                ApiHttpStatus.METHOD_NOT_ALLOWED,
                ApiErrorCodes.METHOD_NOT_ALLOWED,
                "Method is not allowed.",
            )
            is V1NotAcceptableException -> MappedFailure(
                ApiHttpStatus.NOT_ACCEPTABLE,
                ApiErrorCodes.NOT_ACCEPTABLE,
                "The requested response representation is not acceptable.",
            )
            is V1UnsupportedMediaTypeException -> MappedFailure(
                ApiHttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ApiErrorCodes.UNSUPPORTED_MEDIA_TYPE,
                "The request media type is not supported.",
            )
            is V1RangeNotSupportedException -> MappedFailure(
                ApiHttpStatus.RANGE_NOT_SATISFIABLE,
                ApiErrorCodes.RANGE_NOT_SUPPORTED,
                "Range requests are not supported.",
            )
            is ApplicationUnauthenticatedException -> MappedFailure(
                ApiHttpStatus.UNAUTHORIZED,
                ApiErrorCodes.UNAUTHENTICATED,
                "Authentication is required.",
            )
            is ApplicationForbiddenException,
            is SecurityException,
            -> MappedFailure(ApiHttpStatus.FORBIDDEN, ApiErrorCodes.FORBIDDEN, "Access denied.")
            is DocumentNotFoundException,
            is NoSuchElementException,
            -> MappedFailure(ApiHttpStatus.NOT_FOUND, ApiErrorCodes.NOT_FOUND, "Resource was not found.")
            is DocumentContentUnavailableException -> MappedFailure(
                ApiHttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCodes.CONTENT_UNAVAILABLE,
                "Document content is unavailable.",
            )
            is ApplicationTransactionOutcomeUnknownException -> MappedFailure(
                ApiHttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCodes.OUTCOME_UNKNOWN,
                "Request outcome is unknown; inspect the resource state before retrying.",
            )
            is ResumableUploadUnavailableException -> MappedFailure(
                ApiHttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCodes.FEATURE_UNAVAILABLE,
                "The requested feature is unavailable.",
            )
            is DocumentFolderReadAccessUnavailableException,
            is V1FeatureUnavailableException,
            -> MappedFailure(
                ApiHttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCodes.FEATURE_UNAVAILABLE,
                "The requested feature is unavailable.",
            )
            is DocumentConflictException,
            is WorkflowConflictException,
            is ActiveDocumentReviewWorkflowException,
            is IdempotencyConflictException,
            is DocumentRestoreConflictException,
            is ResumableUploadStateException,
            -> MappedFailure(
                ApiHttpStatus.CONFLICT,
                ApiErrorCodes.CONFLICT,
                "Request conflicts with the current resource state.",
            )
            is IllegalArgumentException -> MappedFailure(
                ApiHttpStatus.BAD_REQUEST,
                ApiErrorCodes.INVALID_REQUEST,
                "Request is invalid.",
            )
            else -> MappedFailure(
                ApiHttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCodes.INTERNAL_ERROR,
                "An unexpected error occurred.",
            )
        }
        return ApiHttpFailure(
            mapped.status,
            ApiResponse.failure(ApiError(mapped.code, mapped.message), safeTraceId(traceId)),
        )
    }

    private fun safeTraceId(traceId: String?): String? = traceId?.takeIf { value ->
        value.isNotBlank() &&
            value.length <= MAX_TRACE_ID_LENGTH &&
            value.none { character -> Character.isISOControl(character) }
    }

    private data class MappedFailure(
        val status: ApiHttpStatus,
        val code: String,
        val message: String,
    )

    private companion object {
        const val MAX_TRACE_ID_LENGTH: Int = 128
    }
}

/** Fixed HTTP status classification without importing a Web framework. */
enum class ApiHttpStatus(val statusCode: Int) {
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    CONFLICT(409),
    RANGE_NOT_SATISFIABLE(416),
    SERVICE_UNAVAILABLE(503),
    INTERNAL_SERVER_ERROR(500),
    NOT_ACCEPTABLE(406),
    UNSUPPORTED_MEDIA_TYPE(415),
}

/** A public-safe response plus the status selected by an outer HTTP adapter. */
class ApiHttpFailure(
    val status: ApiHttpStatus,
    val response: ApiResponse<Any?>,
)
