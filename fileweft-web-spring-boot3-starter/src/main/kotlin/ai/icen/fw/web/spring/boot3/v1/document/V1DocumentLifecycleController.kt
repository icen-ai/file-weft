package ai.icen.fw.web.spring.boot3.v1.document

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.document.DocumentLifecycleCommandResultDto
import ai.icen.fw.web.api.v1.document.PublishDocumentCommand
import ai.icen.fw.web.api.v1.document.PublishDocumentRequest
import ai.icen.fw.web.api.v1.workflow.ApproveWorkflowTaskCommand
import ai.icen.fw.web.api.v1.workflow.ApproveWorkflowTaskRequest
import ai.icen.fw.web.api.v1.workflow.RejectWorkflowTaskCommand
import ai.icen.fw.web.api.v1.workflow.RejectWorkflowTaskRequest
import ai.icen.fw.web.api.v1.workflow.SubmitDocumentReviewCommand
import ai.icen.fw.web.api.v1.workflow.SubmitDocumentReviewRequest
import ai.icen.fw.web.runtime.v1.IdempotencyKeyParser
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentLifecycleApiFacade
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Spring Boot 3 MVC edge for formal v1 document lifecycle and review commands.
 *
 * Header cardinality is validated before any command is built. Mutable JSON
 * transport beans are converted immediately to the immutable public commands;
 * an omitted body deliberately selects that command's documented defaults.
 */
@RestController
@RequestMapping(
    value = ["/fileweft/v1"],
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class V1DocumentLifecycleController(
    private val documents: DocumentLifecycleApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @PostMapping("/documents/{documentId}/revise")
    fun revise(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKeys: List<String>?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        val idempotencyKey = IdempotencyKeyParser.parse(idempotencyKeys)
        documents.revise(documentId, idempotencyKey)
    }

    @PostMapping("/documents/{documentId}/publish")
    fun publish(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKeys: List<String>?,
        @RequestBody(required = false) request: PublishDocumentRequest?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        val idempotencyKey = IdempotencyKeyParser.parse(idempotencyKeys)
        val command = PublishDocumentCommand(request?.deliveryProfileId)
        documents.publish(documentId, command, idempotencyKey)
    }

    @PostMapping("/documents/{documentId}/offline")
    fun offline(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKeys: List<String>?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        val idempotencyKey = IdempotencyKeyParser.parse(idempotencyKeys)
        documents.offline(documentId, idempotencyKey)
    }

    @PostMapping("/documents/{documentId}/restore")
    fun restore(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKeys: List<String>?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        val idempotencyKey = IdempotencyKeyParser.parse(idempotencyKeys)
        documents.restore(documentId, idempotencyKey)
    }

    @PostMapping("/documents/{documentId}/archive")
    fun archive(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKeys: List<String>?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        val idempotencyKey = IdempotencyKeyParser.parse(idempotencyKeys)
        documents.archive(documentId, idempotencyKey)
    }

    @PostMapping("/documents/{documentId}/submit")
    fun submitForReview(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKeys: List<String>?,
        @RequestBody(required = false) request: SubmitDocumentReviewRequest?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        val idempotencyKey = IdempotencyKeyParser.parse(idempotencyKeys)
        val command = SubmitDocumentReviewCommand(request?.reviewRouteId)
        documents.submitForReview(documentId, command, idempotencyKey)
    }

    @PostMapping("/workflows/{workflowId}/tasks/{taskId}/approve")
    fun approve(
        @PathVariable("workflowId") workflowId: String,
        @PathVariable("taskId") taskId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKeys: List<String>?,
        @RequestBody(required = false) request: ApproveWorkflowTaskRequest?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        val idempotencyKey = IdempotencyKeyParser.parse(idempotencyKeys)
        val command = ApproveWorkflowTaskCommand(
            comment = request?.comment,
            deliveryProfileId = request?.deliveryProfileId,
        )
        documents.approve(workflowId, taskId, command, idempotencyKey)
    }

    @PostMapping("/workflows/{workflowId}/withdraw")
    fun withdrawReview(
        @PathVariable("workflowId") workflowId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKeys: List<String>?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        val idempotencyKey = IdempotencyKeyParser.parse(idempotencyKeys)
        documents.withdrawReview(workflowId, idempotencyKey)
    }

    @PostMapping("/workflows/{workflowId}/tasks/{taskId}/reject")
    fun reject(
        @PathVariable("workflowId") workflowId: String,
        @PathVariable("taskId") taskId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKeys: List<String>?,
        @RequestBody(required = false) request: RejectWorkflowTaskRequest?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        val idempotencyKey = IdempotencyKeyParser.parse(idempotencyKeys)
        val command = RejectWorkflowTaskCommand(request?.comment)
        documents.reject(workflowId, taskId, command, idempotencyKey)
    }

    private fun execute(action: () -> DocumentLifecycleCommandResultDto): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(responses.success<Any?>(action(), traceId))
        } catch (failure: Exception) {
            val mapped = responses.failure(failure, traceId)
            ResponseEntity.status(mapped.status.statusCode)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapped.response)
        }
    }

    /** Observability must not make a safely executable command fail. */
    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val IDEMPOTENCY_KEY: String = "Idempotency-Key"
    }
}
