package ai.icen.fw.workflow.web.api

import java.beans.Transient

/** Stable public response and failure codes for the standalone Workflow API. */
class WorkflowWebErrorCodes private constructor() {
    companion object {
        const val OK: String = "OK"
        const val INVALID_REQUEST: String = "INVALID_REQUEST"
        const val UNAUTHENTICATED: String = "UNAUTHENTICATED"
        const val FORBIDDEN: String = "FORBIDDEN"
        const val NOT_FOUND: String = "NOT_FOUND"
        const val METHOD_NOT_ALLOWED: String = "METHOD_NOT_ALLOWED"
        const val NOT_ACCEPTABLE: String = "NOT_ACCEPTABLE"
        const val UNSUPPORTED_MEDIA_TYPE: String = "UNSUPPORTED_MEDIA_TYPE"
        const val CONFLICT: String = "CONFLICT"
        const val PRECONDITION_REQUIRED: String = "PRECONDITION_REQUIRED"
        const val PRECONDITION_FAILED: String = "PRECONDITION_FAILED"
        const val CAPABILITY_UNSUPPORTED: String = "CAPABILITY_UNSUPPORTED"
        const val FEATURE_UNAVAILABLE: String = "FEATURE_UNAVAILABLE"
        const val CONTENT_UNAVAILABLE: String = "CONTENT_UNAVAILABLE"
        const val OUTCOME_UNKNOWN: String = "OUTCOME_UNKNOWN"
        const val TOO_MANY_REQUESTS: String = "TOO_MANY_REQUESTS"
        const val INTERNAL_ERROR: String = "INTERNAL_ERROR"

        private val PUBLIC_CODES: Set<String> = setOf(
            OK,
            INVALID_REQUEST,
            UNAUTHENTICATED,
            FORBIDDEN,
            NOT_FOUND,
            METHOD_NOT_ALLOWED,
            NOT_ACCEPTABLE,
            UNSUPPORTED_MEDIA_TYPE,
            CONFLICT,
            PRECONDITION_REQUIRED,
            PRECONDITION_FAILED,
            CAPABILITY_UNSUPPORTED,
            FEATURE_UNAVAILABLE,
            CONTENT_UNAVAILABLE,
            OUTCOME_UNKNOWN,
            TOO_MANY_REQUESTS,
            INTERNAL_ERROR,
        )

        @JvmStatic
        fun isPublicCode(code: String): Boolean = code in PUBLIC_CODES
    }
}

/** Public failures deliberately carry no arbitrary attributes or exception text. */
class WorkflowWebError(code: String, message: String) {
    val code: String = requiredCode(code, "Workflow API error code").also {
        require(WorkflowWebErrorCodes.isPublicCode(it)) { "Workflow API error code is not public." }
    }
    val message: String = requiredText(message, "Workflow API error message", 512)
}

/** Stable JSON envelope shared by future Boot 2 and Boot 3 controller adapters. */
class WorkflowWebResponse<T> private constructor(
    code: String,
    message: String,
    val data: T?,
    val error: WorkflowWebError?,
    traceId: String?,
) {
    val code: String = requiredCode(code, "Workflow API response code")
    val message: String = requiredText(message, "Workflow API response message", 512)
    val traceId: String? = optionalText(traceId, "Workflow API trace id", 256)

    init {
        require(error == null || data == null) { "Failed Workflow API responses cannot contain data." }
        require(error == null || (error.code == this.code && error.message == this.message)) {
            "Workflow API response and error must agree."
        }
    }

    @Transient
    fun isSuccess(): Boolean = error == null

    @Transient
    fun isFailure(): Boolean = error != null

    companion object {
        @JvmStatic
        @JvmOverloads
        fun <T> success(data: T? = null, traceId: String? = null): WorkflowWebResponse<T> =
            WorkflowWebResponse(WorkflowWebErrorCodes.OK, WorkflowWebErrorCodes.OK, data, null, traceId)

        @JvmStatic
        @JvmOverloads
        fun <T> failure(error: WorkflowWebError, traceId: String? = null): WorkflowWebResponse<T> =
            WorkflowWebResponse(error.code, error.message, null, error, traceId)
    }
}

/** Opaque cursor page. The cursor cannot be used as a tenant or authorization snapshot. */
class WorkflowWebPage<T> @JvmOverloads constructor(
    items: Collection<T>,
    nextCursor: String? = null,
) {
    val items: List<T> = immutableList(items, "Workflow page items", MAX_PAGE_SIZE)
    val nextCursor: String? = optionalText(nextCursor, "Workflow page cursor", 1024)

    companion object {
        const val MAX_PAGE_SIZE: Int = 200
    }
}

class WorkflowWebPageQuery @JvmOverloads constructor(
    cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    val cursor: String? = optionalText(cursor, "Workflow page cursor", 1024)

    init {
        require(limit in 1..MAX_LIMIT) { "Workflow page limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 50
        const val MAX_LIMIT: Int = 200
    }
}

/**
 * Framework-neutral result returned by Workflow application ports.
 *
 * Hidden and cross-tenant resources must use [hidden], never FORBIDDEN or a count-bearing result.
 * Missing optional runtime wiring must use [unsupported], never an empty successful payload.
 */
class WorkflowWebApplicationResult<T> private constructor(
    code: String,
    val value: T?,
    val replayed: Boolean,
) {
    val code: String = requiredCode(code, "Workflow application result code")

    init {
        require((this.code == WorkflowWebErrorCodes.OK) == (value != null)) {
            "Only successful Workflow application results may contain a value."
        }
        require(!replayed || this.code == WorkflowWebErrorCodes.OK) {
            "Only successful Workflow application results may be replayed."
        }
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun <T> success(value: T, replayed: Boolean = false): WorkflowWebApplicationResult<T> =
            WorkflowWebApplicationResult(WorkflowWebErrorCodes.OK, value, replayed)

        @JvmStatic
        fun <T> failure(code: String): WorkflowWebApplicationResult<T> {
            require(code != WorkflowWebErrorCodes.OK) { "A failure requires a failure code." }
            require(WorkflowWebErrorCodes.isPublicCode(code)) { "Workflow failure code is not public." }
            return WorkflowWebApplicationResult(code, null, false)
        }

        @JvmStatic
        fun <T> hidden(): WorkflowWebApplicationResult<T> = failure(WorkflowWebErrorCodes.NOT_FOUND)

        @JvmStatic
        fun <T> unsupported(): WorkflowWebApplicationResult<T> =
            failure(WorkflowWebErrorCodes.CAPABILITY_UNSUPPORTED)
    }
}

/** Safe HTTP status projection; adapters must not infer status from exception messages. */
class WorkflowWebHttpStatusPolicy private constructor() {
    companion object {
        @JvmStatic
        fun statusFor(code: String): Int = when (code) {
            WorkflowWebErrorCodes.OK -> 200
            WorkflowWebErrorCodes.INVALID_REQUEST -> 400
            WorkflowWebErrorCodes.UNAUTHENTICATED -> 401
            WorkflowWebErrorCodes.FORBIDDEN -> 403
            WorkflowWebErrorCodes.NOT_FOUND -> 404
            WorkflowWebErrorCodes.METHOD_NOT_ALLOWED -> 405
            WorkflowWebErrorCodes.NOT_ACCEPTABLE -> 406
            WorkflowWebErrorCodes.CONFLICT -> 409
            WorkflowWebErrorCodes.PRECONDITION_REQUIRED -> 428
            WorkflowWebErrorCodes.PRECONDITION_FAILED -> 412
            WorkflowWebErrorCodes.UNSUPPORTED_MEDIA_TYPE -> 415
            WorkflowWebErrorCodes.TOO_MANY_REQUESTS -> 429
            WorkflowWebErrorCodes.CAPABILITY_UNSUPPORTED,
            WorkflowWebErrorCodes.FEATURE_UNAVAILABLE,
            WorkflowWebErrorCodes.CONTENT_UNAVAILABLE,
            WorkflowWebErrorCodes.OUTCOME_UNKNOWN -> 503
            else -> 500
        }
    }
}

/** Framework-neutral response headers required for protected Workflow JSON routes. */
class WorkflowWebHttpContract private constructor() {
    companion object {
        const val JSON_MEDIA_TYPE: String = "application/json"
        const val CACHE_CONTROL_HEADER: String = "Cache-Control"
        const val CACHE_CONTROL_VALUE: String = "private, no-store"
        const val PRAGMA_HEADER: String = "Pragma"
        const val PRAGMA_VALUE: String = "no-cache"
        const val CONTENT_TYPE_OPTIONS_HEADER: String = "X-Content-Type-Options"
        const val CONTENT_TYPE_OPTIONS_VALUE: String = "nosniff"
    }
}
