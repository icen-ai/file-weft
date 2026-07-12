package ai.icen.fw.web.spring.boot2

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
 * Spring Boot 2 MVC edge for formal v1 lifecycle and review commands.
 *
 * Every command requires one caller idempotency key. Tenant, operator,
 * catalog scope and workflow assignment remain trusted Application concerns;
 * none can be supplied through this transport.
 */
@RestController
@RequestMapping(
    value = ["/fileweft/v1"],
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class DocumentV1LifecycleController(
    private val documents: DocumentLifecycleApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider? = null,
) {
    @PostMapping("/documents/{documentId}/revise")
    fun revise(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKeys: List<String>?,
    ): ResponseEntity<ApiResponse<*>> = execute {
        documents.revise(documentId, IdempotencyKeyParser.parse(idempotencyKeys))
    }

    @PostMapping("/documents/{documentId}/publish")
    fun publish(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKeys: List<String>?,
        @RequestBody(required = false) request: PublishDocumentRequest?,
    ): ResponseEntity<ApiResponse<*>> = execute {
        val idempotencyKey = IdempotencyKeyParser.parse(idempotencyKeys)
        val command = PublishDocumentCommand(request?.deliveryProfileId)
        documents.publish(documentId, command, idempotencyKey)
    }

    @PostMapping("/documents/{documentId}/offline")
    fun offline(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKeys: List<String>?,
    ): ResponseEntity<ApiResponse<*>> = execute {
        documents.offline(documentId, IdempotencyKeyParser.parse(idempotencyKeys))
    }

    @PostMapping("/documents/{documentId}/restore")
    fun restore(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKeys: List<String>?,
    ): ResponseEntity<ApiResponse<*>> = execute {
        documents.restore(documentId, IdempotencyKeyParser.parse(idempotencyKeys))
    }

    @PostMapping("/documents/{documentId}/archive")
    fun archive(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKeys: List<String>?,
    ): ResponseEntity<ApiResponse<*>> = execute {
        documents.archive(documentId, IdempotencyKeyParser.parse(idempotencyKeys))
    }

    @PostMapping("/documents/{documentId}/submit")
    fun submitForReview(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKeys: List<String>?,
        @RequestBody(required = false) request: SubmitDocumentReviewRequest?,
    ): ResponseEntity<ApiResponse<*>> = execute {
        val idempotencyKey = IdempotencyKeyParser.parse(idempotencyKeys)
        val command = SubmitDocumentReviewCommand(request?.reviewRouteId)
        documents.submitForReview(documentId, command, idempotencyKey)
    }

    @PostMapping("/workflows/{workflowId}/tasks/{taskId}/approve")
    fun approve(
        @PathVariable("workflowId") workflowId: String,
        @PathVariable("taskId") taskId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKeys: List<String>?,
        @RequestBody(required = false) request: ApproveWorkflowTaskRequest?,
    ): ResponseEntity<ApiResponse<*>> = execute {
        val idempotencyKey = IdempotencyKeyParser.parse(idempotencyKeys)
        val command = ApproveWorkflowTaskCommand(request?.comment, request?.deliveryProfileId)
        documents.approve(workflowId, taskId, command, idempotencyKey)
    }

    @PostMapping("/workflows/{workflowId}/tasks/{taskId}/reject")
    fun reject(
        @PathVariable("workflowId") workflowId: String,
        @PathVariable("taskId") taskId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKeys: List<String>?,
        @RequestBody(required = false) request: RejectWorkflowTaskRequest?,
    ): ResponseEntity<ApiResponse<*>> = execute {
        val idempotencyKey = IdempotencyKeyParser.parse(idempotencyKeys)
        val command = RejectWorkflowTaskCommand(request?.comment)
        documents.reject(workflowId, taskId, command, idempotencyKey)
    }

    private fun execute(
        action: () -> DocumentLifecycleCommandResultDto,
    ): ResponseEntity<ApiResponse<*>> {
        val traceId = currentTraceId()
        return try {
            val response: ApiResponse<*> = responses.success(action(), traceId)
            ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response)
        } catch (failure: Exception) {
            val mapped = responses.failure(failure, traceId)
            val response: ApiResponse<*> = mapped.response
            ResponseEntity.status(mapped.status.statusCode)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response)
        }
    }

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        // Observability must never turn a safely executable command into a failure.
        null
    }

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"
    }
}
