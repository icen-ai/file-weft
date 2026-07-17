package ai.icen.fw.workflow.web.runtime

import ai.icen.fw.workflow.web.api.WorkflowWebApplicationResult
import ai.icen.fw.workflow.web.api.WorkflowWebHttpContract
import ai.icen.fw.workflow.web.api.WorkflowWebResponse
import ai.icen.fw.workflow.web.api.WorkflowWebRoute
import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContext
import ai.icen.fw.workflow.web.api.WorkflowWebWritePreconditions
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

class WorkflowWebRequestMetadata private constructor(
    method: String,
    contentType: String?,
    accept: String?,
    val verifiedBodyBytes: Int,
    idempotencyHeaderValues: Collection<String>,
    ifMatchHeaderValues: Collection<String>,
) {
    val method: String = requireToken(method, "Workflow HTTP method", 16)
    val contentType: String? = contentType?.let { requireHeaderValue(it, "Workflow Content-Type") }
    val accept: String? = accept?.let { requireHeaderValue(it, "Workflow Accept") }
    val idempotencyHeaderValues: List<String> = immutableHeaders(
        idempotencyHeaderValues,
        "Workflow Idempotency-Key",
    )
    val ifMatchHeaderValues: List<String> = immutableHeaders(ifMatchHeaderValues, "Workflow If-Match")

    init {
        require(this.method == "GET" || this.method == "POST" || this.method == "PUT") {
            "Unsupported Workflow HTTP method."
        }
        require(verifiedBodyBytes in 0..MAX_BODY_BYTES) { "Workflow request body exceeds the verified limit." }
    }

    override fun toString(): String = "WorkflowWebRequestMetadata(<redacted>)"

    companion object {
        const val MAX_BODY_BYTES: Int = 4 * 1024 * 1024

        @JvmStatic
        @JvmOverloads
        fun of(
            method: String,
            contentType: String? = null,
            accept: String? = null,
            verifiedBodyBytes: Int = 0,
            idempotencyHeaderValues: Collection<String> = emptyList(),
            ifMatchHeaderValues: Collection<String> = emptyList(),
        ): WorkflowWebRequestMetadata = WorkflowWebRequestMetadata(
            method,
            contentType,
            accept,
            verifiedBodyBytes,
            idempotencyHeaderValues,
            ifMatchHeaderValues,
        )
    }
}

class WorkflowWebRuntimeObservationCode private constructor(val code: String) {
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowWebRuntimeObservationCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = code

    companion object {
        @JvmField val REQUEST_REJECTED = WorkflowWebRuntimeObservationCode("REQUEST_REJECTED")
        @JvmField val AUTHENTICATION_UNAVAILABLE = WorkflowWebRuntimeObservationCode("AUTHENTICATION_UNAVAILABLE")
        @JvmField val APPLICATION_FAILURE = WorkflowWebRuntimeObservationCode("APPLICATION_FAILURE")
    }
}

/** Value-free signal: no tenant, principal, request body, header value or exception is retained. */
class WorkflowWebRuntimeObservation private constructor(
    operationId: String,
    val code: WorkflowWebRuntimeObservationCode,
) {
    val operationId: String = requireToken(operationId, "Workflow operation id", 128)
    override fun toString(): String = "WorkflowWebRuntimeObservation(operationId=$operationId, code=$code)"

    companion object {
        @JvmStatic fun of(
            operationId: String,
            code: WorkflowWebRuntimeObservationCode,
        ): WorkflowWebRuntimeObservation = WorkflowWebRuntimeObservation(operationId, code)
    }
}

fun interface WorkflowWebRuntimeObservationPort {
    fun observe(observation: WorkflowWebRuntimeObservation)

    companion object {
        @JvmField val NO_OP = WorkflowWebRuntimeObservationPort { }
    }
}

class WorkflowWebHttpResponse<T> private constructor(
    val status: Int,
    headers: Map<String, String>,
    val body: WorkflowWebResponse<T>,
) {
    val headers: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(headers))

    init {
        require(status in 100..599) { "Workflow HTTP response status is invalid." }
        require(this.headers["Content-Type"] == WorkflowWebHttpContract.JSON_MEDIA_TYPE &&
            this.headers[WorkflowWebHttpContract.CACHE_CONTROL_HEADER] == WorkflowWebHttpContract.CACHE_CONTROL_VALUE &&
            this.headers[WorkflowWebHttpContract.PRAGMA_HEADER] == WorkflowWebHttpContract.PRAGMA_VALUE &&
            this.headers[WorkflowWebHttpContract.CONTENT_TYPE_OPTIONS_HEADER] ==
            WorkflowWebHttpContract.CONTENT_TYPE_OPTIONS_VALUE
        ) { "Workflow HTTP security headers are incomplete." }
    }

    override fun toString(): String = "WorkflowWebHttpResponse(status=$status, code=${body.code})"

    companion object {
        @JvmStatic fun <T> of(
            status: Int,
            body: WorkflowWebResponse<T>,
        ): WorkflowWebHttpResponse<T> = WorkflowWebHttpResponse(status, standardHeaders(), body)

        private fun standardHeaders(): Map<String, String> = linkedMapOf(
            "Content-Type" to WorkflowWebHttpContract.JSON_MEDIA_TYPE,
            WorkflowWebHttpContract.CACHE_CONTROL_HEADER to WorkflowWebHttpContract.CACHE_CONTROL_VALUE,
            WorkflowWebHttpContract.PRAGMA_HEADER to WorkflowWebHttpContract.PRAGMA_VALUE,
            WorkflowWebHttpContract.CONTENT_TYPE_OPTIONS_HEADER to WorkflowWebHttpContract.CONTENT_TYPE_OPTIONS_VALUE,
        )
    }
}

fun interface WorkflowWebReadInvocation<T> {
    fun invoke(context: WorkflowWebTrustedContext): WorkflowWebApplicationResult<T>
}

fun interface WorkflowWebWriteInvocation<T> {
    fun invoke(
        context: WorkflowWebTrustedContext,
        preconditions: WorkflowWebWritePreconditions,
    ): WorkflowWebApplicationResult<T>
}

private fun immutableHeaders(values: Collection<String>, label: String): List<String> {
    require(values.size <= 2) { "$label has too many values." }
    val copy = ArrayList<String>(values.size)
    values.forEach { copy += requireHeaderValue(it, label) }
    return Collections.unmodifiableList(copy)
}

private fun requireHeaderValue(value: String, label: String): String = value.also {
    require(it.isNotEmpty() && it.toByteArray(StandardCharsets.UTF_8).size <= 512) { "$label is invalid." }
    require(it.none { character -> Character.isISOControl(character) }) { "$label is invalid." }
}

private fun requireToken(value: String, label: String, maximum: Int): String = value.also {
    require(it.length in 1..maximum && it.matches(Regex("[A-Za-z][A-Za-z0-9._:-]*"))) { "$label is invalid." }
}
