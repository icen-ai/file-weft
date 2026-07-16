package ai.icen.fw.workflow.web.runtime

import ai.icen.fw.workflow.web.api.WorkflowWebApplicationResult
import ai.icen.fw.workflow.web.api.WorkflowWebError
import ai.icen.fw.workflow.web.api.WorkflowWebErrorCodes
import ai.icen.fw.workflow.web.api.WorkflowWebHttpStatusPolicy
import ai.icen.fw.workflow.web.api.WorkflowWebResponse
import ai.icen.fw.workflow.web.api.WorkflowWebRoute
import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContext
import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContextProvider
import ai.icen.fw.workflow.web.api.WorkflowWebWritePreconditions

/** Shared fail-closed execution boundary used by Boot 2, Boot 3 and other HTTP adapters. */
class WorkflowWebControllerRuntime @JvmOverloads constructor(
    private val trustedContextProvider: WorkflowWebTrustedContextProvider,
    private val observationPort: WorkflowWebRuntimeObservationPort = WorkflowWebRuntimeObservationPort.NO_OP,
) {
    fun <T> executeRead(
        route: WorkflowWebRoute,
        metadata: WorkflowWebRequestMetadata,
        invocation: WorkflowWebReadInvocation<T>,
    ): WorkflowWebHttpResponse<T> {
        val validation = validate(route, metadata, false)
        if (validation != null) return failure(route, validation)
        val context = currentContext(route) ?: return failure(route, WorkflowWebErrorCodes.UNAUTHENTICATED)
        val result = try {
            invocation.invoke(context)
        } catch (_: RuntimeException) {
            observe(route, WorkflowWebRuntimeObservationCode.APPLICATION_FAILURE)
            return failure(route, WorkflowWebErrorCodes.INTERNAL_ERROR, context.traceId)
        }
        return project(result, context)
    }

    fun <T> executeWrite(
        route: WorkflowWebRoute,
        metadata: WorkflowWebRequestMetadata,
        invocation: WorkflowWebWriteInvocation<T>,
    ): WorkflowWebHttpResponse<T> {
        val validation = validate(route, metadata, true)
        if (validation != null) return failure(route, validation)
        val preconditions = try {
            WorkflowWebWritePreconditions.parse(
                metadata.idempotencyHeaderValues.single(),
                metadata.ifMatchHeaderValues.single(),
            )
        } catch (_: RuntimeException) {
            return failure(route, WorkflowWebErrorCodes.INVALID_REQUEST)
        }
        val context = currentContext(route) ?: return failure(route, WorkflowWebErrorCodes.UNAUTHENTICATED)
        val result = try {
            invocation.invoke(context, preconditions)
        } catch (_: RuntimeException) {
            observe(route, WorkflowWebRuntimeObservationCode.APPLICATION_FAILURE)
            return failure(route, WorkflowWebErrorCodes.INTERNAL_ERROR, context.traceId)
        }
        return project(result, context)
    }

    private fun validate(
        route: WorkflowWebRoute,
        metadata: WorkflowWebRequestMetadata,
        write: Boolean,
    ): String? {
        val invalid = route.method != metadata.method || route.idempotencyRequired != write ||
            route.ifMatchRequired != write || !acceptsJson(metadata.accept) ||
            if (write) {
                !isJson(metadata.contentType) || metadata.idempotencyHeaderValues.size != 1 ||
                    metadata.ifMatchHeaderValues.size != 1
            } else {
                metadata.verifiedBodyBytes != 0 || metadata.contentType != null ||
                    metadata.idempotencyHeaderValues.isNotEmpty() || metadata.ifMatchHeaderValues.isNotEmpty()
            }
        if (invalid) {
            return WorkflowWebErrorCodes.INVALID_REQUEST
        }
        return null
    }

    private fun currentContext(route: WorkflowWebRoute): WorkflowWebTrustedContext? = try {
        trustedContextProvider.currentContext()
    } catch (_: RuntimeException) {
        observe(route, WorkflowWebRuntimeObservationCode.AUTHENTICATION_UNAVAILABLE)
        null
    }

    private fun <T> project(
        result: WorkflowWebApplicationResult<T>,
        context: WorkflowWebTrustedContext,
    ): WorkflowWebHttpResponse<T> = if (result.code == WorkflowWebErrorCodes.OK) {
        WorkflowWebHttpResponse.of(
            WorkflowWebHttpStatusPolicy.statusFor(result.code),
            WorkflowWebResponse.success(requireNotNull(result.value), context.traceId),
        )
    } else {
        responseFailure(result.code, context.traceId)
    }

    private fun <T> failure(
        route: WorkflowWebRoute,
        code: String,
        traceId: String? = null,
    ): WorkflowWebHttpResponse<T> {
        if (code == WorkflowWebErrorCodes.INVALID_REQUEST) {
            observe(route, WorkflowWebRuntimeObservationCode.REQUEST_REJECTED)
        }
        return responseFailure(code, traceId)
    }

    private fun <T> responseFailure(code: String, traceId: String?): WorkflowWebHttpResponse<T> {
        val safeCode = if (WorkflowWebErrorCodes.isPublicCode(code) && code != WorkflowWebErrorCodes.OK) {
            code
        } else WorkflowWebErrorCodes.INTERNAL_ERROR
        val error = WorkflowWebError(safeCode, messageFor(safeCode))
        return WorkflowWebHttpResponse.of(
            WorkflowWebHttpStatusPolicy.statusFor(safeCode),
            WorkflowWebResponse.failure(error, traceId),
        )
    }

    private fun observe(route: WorkflowWebRoute, code: WorkflowWebRuntimeObservationCode) {
        try {
            observationPort.observe(WorkflowWebRuntimeObservation.of(route.operationId, code))
        } catch (_: RuntimeException) {
            // Observation is best-effort and contains no business data; it never changes the public result.
        }
    }

    private fun isJson(value: String?): Boolean = value?.trim()?.lowercase()?.let {
        it == "application/json" || it == "application/json;charset=utf-8" || it == "application/json; charset=utf-8"
    } == true

    private fun acceptsJson(value: String?): Boolean = value == null || value.split(',').let { ranges ->
        ranges.size <= 8 && ranges.any { range ->
            val mediaType = range.trim().substringBefore(';').lowercase()
            mediaType == "application/json" || mediaType == "*/*"
        }
    }

    private fun messageFor(code: String): String = when (code) {
        WorkflowWebErrorCodes.INVALID_REQUEST -> "The Workflow request is invalid."
        WorkflowWebErrorCodes.UNAUTHENTICATED -> "Authentication is required."
        WorkflowWebErrorCodes.FORBIDDEN -> "The Workflow operation is not permitted."
        WorkflowWebErrorCodes.NOT_FOUND -> "The Workflow resource was not found."
        WorkflowWebErrorCodes.CONFLICT -> "The Workflow request conflicts with current state."
        WorkflowWebErrorCodes.PRECONDITION_REQUIRED -> "Workflow mutation preconditions are required."
        WorkflowWebErrorCodes.PRECONDITION_FAILED -> "Workflow mutation preconditions no longer match."
        WorkflowWebErrorCodes.CAPABILITY_UNSUPPORTED -> "The Workflow capability is not installed."
        WorkflowWebErrorCodes.FEATURE_UNAVAILABLE -> "The Workflow feature is temporarily unavailable."
        WorkflowWebErrorCodes.CONTENT_UNAVAILABLE -> "The Workflow content is unavailable."
        WorkflowWebErrorCodes.OUTCOME_UNKNOWN -> "The Workflow operation outcome requires reconciliation."
        WorkflowWebErrorCodes.TOO_MANY_REQUESTS -> "The Workflow request limit was exceeded."
        else -> "The Workflow request could not be completed."
    }
}
