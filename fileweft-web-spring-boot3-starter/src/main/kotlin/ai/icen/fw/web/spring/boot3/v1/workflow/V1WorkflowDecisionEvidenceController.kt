package ai.icen.fw.web.spring.boot3.v1.workflow

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowDecisionEvidencePageQuery
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.workflow.WorkflowDecisionEvidenceApiReadFacade
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/fileweft/v1"], produces = [MediaType.APPLICATION_JSON_VALUE])
class V1WorkflowDecisionEvidenceController(
    private val evidence: WorkflowDecisionEvidenceApiReadFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @GetMapping("/documents/{documentId}/workflow-decisions")
    fun documentEvidence(
        @PathVariable("documentId") documentId: String,
        @RequestParam(name = "cursor", required = false) cursor: String?,
        @RequestParam(name = "limit", required = false) limit: String?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        evidence.documentEvidence(
            documentId,
            DocumentWorkflowDecisionEvidencePageQuery(cursor, parseLimit(limit)),
        )
    }

    private fun parseLimit(value: String?): Int {
        if (value == null) return DocumentWorkflowDecisionEvidencePageQuery.DEFAULT_LIMIT
        require(LIMIT_PATTERN.matches(value)) { "Workflow decision evidence page limit is invalid." }
        return value.toInt()
    }

    private fun <T> execute(action: () -> T): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            response(HttpStatus.OK, responses.success<Any?>(action(), traceId))
        } catch (failure: Exception) {
            val mapped = responses.failure(failure, traceId)
            response(HttpStatus.valueOf(mapped.status.statusCode), mapped.response)
        }
    }

    private fun response(
        status: HttpStatus,
        body: ApiResponse<Any?>,
    ): ResponseEntity<ApiResponse<Any?>> = ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
        .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
        .body(body)

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val PRIVATE_NO_STORE = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
        val LIMIT_PATTERN: Regex = Regex("[1-9][0-9]{0,2}")
    }
}
