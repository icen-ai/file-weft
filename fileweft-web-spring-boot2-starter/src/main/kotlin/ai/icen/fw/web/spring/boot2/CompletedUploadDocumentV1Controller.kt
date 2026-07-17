package ai.icen.fw.web.spring.boot2

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.document.AddDocumentVersionFromCompletedUploadRequest
import ai.icen.fw.web.api.v1.document.CreateDocumentFromCompletedUploadRequest
import ai.icen.fw.web.api.v1.document.DocumentCommandResultDto
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.CompletedUploadDocumentApiFacade
import ai.icen.fw.web.runtime.v1.document.DocumentApiLocations
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Boot 2 JSON edge for atomically consuming completed uploads as document content. */
@RestController
@RequestMapping(
    value = ["/fileweft/v1/documents"],
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class CompletedUploadDocumentV1Controller(
    private val claims: CompletedUploadDocumentApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider? = null,
) {
    @PostMapping(params = [UPLOAD_ID_PARAMETER], consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createDocument(
        @RequestParam(name = UPLOAD_ID_PARAMETER, required = false) uploadIds: List<String>?,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKeys: List<String>?,
        @RequestBody(required = false) request: CreateDocumentFromCompletedUploadRequest?,
    ): ResponseEntity<ApiResponse<*>> = executeCreated {
        claims.createDocument(
            requiredSingle(uploadIds, "Upload id"),
            idempotencyKeys,
            requireNotNull(request) { "Document request body must be supplied." },
        )
    }

    @PostMapping(
        value = ["/{documentId}/versions"],
        params = [UPLOAD_ID_PARAMETER],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun addDocumentVersion(
        @PathVariable("documentId") documentId: String,
        @RequestParam(name = UPLOAD_ID_PARAMETER, required = false) uploadIds: List<String>?,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKeys: List<String>?,
        @RequestBody(required = false) request: AddDocumentVersionFromCompletedUploadRequest?,
    ): ResponseEntity<ApiResponse<*>> = executeCreated {
        claims.addDocumentVersion(
            documentId,
            requiredSingle(uploadIds, "Upload id"),
            idempotencyKeys,
            requireNotNull(request) { "Document version request body must be supplied." },
        )
    }

    private fun executeCreated(action: () -> DocumentCommandResultDto): ResponseEntity<ApiResponse<*>> {
        val traceId = currentTraceId()
        return try {
            val result = action()
            val response: ApiResponse<*> = responses.success(result, traceId)
            val builder = ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .privateNoStore()
            DocumentApiLocations.detailIfRoutable(result.documentId)?.let(builder::location)
            builder.body(response)
        } catch (failure: Exception) {
            val mapped = responses.failure(failure, traceId)
            val response: ApiResponse<*> = mapped.response
            ResponseEntity.status(mapped.status.statusCode)
                .contentType(MediaType.APPLICATION_JSON)
                .privateNoStore()
                .body(response)
        }
    }

    private fun <T> requiredSingle(values: List<T>?, field: String): T {
        require(values?.size == 1) { "$field must be supplied exactly once." }
        return values[0]
    }

    private fun ResponseEntity.BodyBuilder.privateNoStore(): ResponseEntity.BodyBuilder =
        header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(X_CONTENT_TYPE_OPTIONS, "nosniff")

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val UPLOAD_ID_PARAMETER: String = "uploadId"
        const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"
        const val PRIVATE_NO_STORE: String = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS: String = "X-Content-Type-Options"
    }
}
