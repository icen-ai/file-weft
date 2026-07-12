package ai.icen.fw.web.spring.boot3.v1.workflow

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowPageQuery
import ai.icen.fw.web.api.v1.workflow.WorkflowTaskPageQuery
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.workflow.WorkflowApiReadFacade
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/fileweft/v1"], produces = [MediaType.APPLICATION_JSON_VALUE])
class V1WorkflowReadController(
    private val workflows: WorkflowApiReadFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @GetMapping("/workflows/tasks")
    fun pendingTasks(
        @RequestParam(name = "cursor", required = false) cursor: String?,
        @RequestParam(name = "limit", required = false) limit: String?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        workflows.pendingTasks(WorkflowTaskPageQuery(cursor, parseLimit(limit)))
    }

    @GetMapping("/documents/{documentId}/workflows")
    fun documentHistory(
        @PathVariable("documentId") documentId: String,
        @RequestParam(name = "cursor", required = false) cursor: String?,
        @RequestParam(name = "limit", required = false) limit: String?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        workflows.documentHistory(documentId, DocumentWorkflowPageQuery(cursor, parseLimit(limit)))
    }

    private fun parseLimit(value: String?): Int {
        if (value == null) return WorkflowTaskPageQuery.DEFAULT_LIMIT
        require(LIMIT_PATTERN.matches(value)) { "Workflow page limit is invalid." }
        return value.toInt()
    }

    private fun <T> execute(action: () -> T): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            ResponseEntity.ok(responses.success<Any?>(action(), traceId))
        } catch (failure: Exception) {
            val mapped = responses.failure(failure, traceId)
            ResponseEntity.status(mapped.status.statusCode).body(mapped.response)
        }
    }

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }

    private companion object {
        val LIMIT_PATTERN: Regex = Regex("[1-9][0-9]{0,2}")
    }
}
