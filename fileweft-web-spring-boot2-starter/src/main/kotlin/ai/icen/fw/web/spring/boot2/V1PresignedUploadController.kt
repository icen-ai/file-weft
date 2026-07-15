package ai.icen.fw.web.spring.boot2

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.upload.PresignedUploadGrantDto
import ai.icen.fw.web.api.v1.upload.StartPresignedUploadRequest
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.upload.PresignedUploadApiFacade
import ai.icen.fw.web.runtime.v1.upload.PresignedUploadApiLocations
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.net.URI

/** Thin Boot 2 MVC edge for constrained direct-to-storage PUT grants. */
@RestController
@RequestMapping(
    value = ["/flowweft/v1/presigned-uploads"],
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class V1PresignedUploadController(
    private val uploads: PresignedUploadApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun start(
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKeys: List<String>?,
        @RequestBody(required = false) request: StartPresignedUploadRequest?,
    ): ResponseEntity<ApiResponse<Any?>> = executeCreated {
        uploads.start(
            idempotencyKeys,
            requireNotNull(request) { "Presigned upload request body is required." },
        )
    }

    @PostMapping("/{uploadId}/grant")
    fun reissue(@PathVariable("uploadId") uploadId: String): ResponseEntity<ApiResponse<Any?>> = execute {
        uploads.reissue(uploadId)
    }

    @GetMapping("/{uploadId}")
    fun inspect(@PathVariable("uploadId") uploadId: String): ResponseEntity<ApiResponse<Any?>> = execute {
        uploads.inspect(uploadId)
    }

    @DeleteMapping("/{uploadId}")
    fun cancel(@PathVariable("uploadId") uploadId: String): ResponseEntity<ApiResponse<Any?>> = execute {
        uploads.cancel(uploadId)
    }

    @PostMapping("/{uploadId}/finalize")
    fun finalizeUpload(
        @PathVariable("uploadId") uploadId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKeys: List<String>?,
    ): ResponseEntity<ApiResponse<Any?>> = execute { uploads.finalizeUpload(uploadId, idempotencyKeys) }

    private fun executeCreated(action: () -> PresignedUploadGrantDto): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            val requestUri = ServletUriComponentsBuilder.fromCurrentRequestUri().replaceQuery(null).build().toUri()
            val collectionUri = URI.create(requireNotNull(requestUri.rawPath) {
                "Presigned upload collection path is unavailable."
            })
            val result = action()
            ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .privateNoStore()
                .location(PresignedUploadApiLocations.inspect(collectionUri, result.uploadId))
                .body(responses.success<Any?>(result, traceId))
        } catch (failure: Exception) {
            failureResponse(failure, traceId)
        }
    }

    private fun execute(action: () -> Any): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .privateNoStore()
                .body(responses.success<Any?>(action(), traceId))
        } catch (failure: Exception) {
            failureResponse(failure, traceId)
        }
    }

    private fun failureResponse(failure: Exception, traceId: String?): ResponseEntity<ApiResponse<Any?>> {
        val mapped = responses.failure(failure, traceId)
        return ResponseEntity.status(mapped.status.statusCode)
            .contentType(MediaType.APPLICATION_JSON)
            .privateNoStore()
            .body(mapped.response)
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
        const val IDEMPOTENCY_KEY: String = "Idempotency-Key"
        const val PRIVATE_NO_STORE: String = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS: String = "X-Content-Type-Options"
    }
}
