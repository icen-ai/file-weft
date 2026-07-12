package ai.icen.fw.web.spring.boot2

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.audit.DocumentAuditLogPageQuery
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.V1MethodNotAllowedException
import ai.icen.fw.web.runtime.v1.audit.DocumentAuditLogApiFacade
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Authorized, tenant-derived and redacted document audit history. */
@RestController
class DocumentV1AuditLogController(
    private val auditLogs: DocumentAuditLogApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider? = null,
) {
    @GetMapping(value = [PATH], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun page(
        @PathVariable("documentId") documentId: String,
        @RequestParam(name = "cursor", required = false) cursor: String?,
        @RequestParam(name = "limit", required = false) limit: String?,
    ): ResponseEntity<ApiResponse<*>> = execute {
        auditLogs.page(documentId, DocumentAuditLogPageQuery(cursor, parseLimit(limit)))
    }

    @RequestMapping(value = [PATH], method = [RequestMethod.HEAD], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun pageHead(@PathVariable("documentId") documentId: String): ResponseEntity<ApiResponse<*>> {
        val mapped = responses.failure(V1MethodNotAllowedException(), currentTraceId())
        val body: ApiResponse<*> = mapped.response
        return response(HttpStatus.valueOf(mapped.status.statusCode), body, "GET")
    }

    private fun parseLimit(value: String?): Int {
        if (value == null) return DocumentAuditLogPageQuery.DEFAULT_LIMIT
        require(LIMIT_PATTERN.matches(value)) { "Document audit-log limit is invalid." }
        return value.toInt()
    }

    private fun execute(action: () -> Any): ResponseEntity<ApiResponse<*>> {
        val traceId = currentTraceId()
        return try {
            val body: ApiResponse<*> = responses.success(action(), traceId)
            response(HttpStatus.OK, body)
        } catch (failure: Exception) {
            val mapped = responses.failure(failure, traceId)
            val body: ApiResponse<*> = mapped.response
            response(HttpStatus.valueOf(mapped.status.statusCode), body)
        }
    }

    private fun response(
        status: HttpStatus,
        body: ApiResponse<*>,
        allowedMethod: String? = null,
    ): ResponseEntity<ApiResponse<*>> {
        val builder = ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
        if (allowedMethod != null) builder.header(HttpHeaders.ALLOW, allowedMethod)
        return builder.body(body)
    }

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val PATH = "/fileweft/v1/documents/{documentId}/logs"
        const val PRIVATE_NO_STORE = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
        val LIMIT_PATTERN: Regex = Regex("[1-9][0-9]{0,2}")
    }
}
