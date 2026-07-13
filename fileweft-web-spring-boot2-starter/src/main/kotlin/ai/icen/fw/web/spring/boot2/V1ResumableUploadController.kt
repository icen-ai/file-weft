package ai.icen.fw.web.spring.boot2

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.upload.ResumableUploadDto
import ai.icen.fw.web.api.v1.upload.StartResumableUploadRequest
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.upload.ResumableUploadApiFacade
import ai.icen.fw.web.runtime.v1.upload.ResumableUploadApiLocations
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.URI

/**
 * Boot 2 MVC edge for the formal v1 resumable-upload resource.
 *
 * The controller accepts only public transport values. Trusted tenant and
 * owner identity, authorization, storage handles, durable multipart state,
 * and completion reconciliation stay behind [ResumableUploadApiFacade]. Part
 * bodies remain streaming and are never materialized in the servlet adapter.
 */
@RestController
@RequestMapping(
    value = ["/fileweft/v1/uploads"],
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class V1ResumableUploadController(
    private val uploads: ResumableUploadApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider? = null,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun start(
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKeys: List<String>?,
        @RequestBody(required = false) request: StartResumableUploadRequest?,
    ): ResponseEntity<ApiResponse<*>> = executeCreated {
        uploads.start(
            idempotencyKeys,
            requireNotNull(request) { "Upload request body must be supplied." },
        )
    }

    @GetMapping("/{uploadId}")
    fun inspect(
        @PathVariable("uploadId") uploadId: String,
    ): ResponseEntity<ApiResponse<*>> = executeOk { uploads.inspect(uploadId) }

    @PutMapping(
        value = ["/{uploadId}/parts/{partNumber}"],
        consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE],
    )
    fun uploadPart(
        @PathVariable("uploadId") uploadId: String,
        @PathVariable("partNumber") partNumber: String,
        @RequestHeader(name = PART_LENGTH_HEADER, required = false) partLengths: List<String>?,
        content: InputStream,
    ): ResponseEntity<ApiResponse<*>> = executeOk {
        val contentLength = requiredSinglePositiveLong(partLengths, "Multipart part length")
        val parsedPartNumber = partNumber.takeIf(PART_NUMBER_PATTERN::matches)
            ?.toIntOrNull()
            ?.takeIf { parsed -> parsed <= 10_000 }
            ?: throw IllegalArgumentException("Multipart part number is invalid.")
        PushbackInputStream(content, 1).use { body ->
            val firstByte = body.read()
            require(firstByte >= 0) { "Multipart part body must not be empty." }
            body.unread(firstByte)
            uploads.uploadPart(uploadId, parsedPartNumber, contentLength, body)
        }
    }

    @PostMapping("/{uploadId}/complete")
    fun complete(
        @PathVariable("uploadId") uploadId: String,
    ): ResponseEntity<ApiResponse<*>> = executeOk { uploads.complete(uploadId) }

    @DeleteMapping("/{uploadId}")
    fun abort(
        @PathVariable("uploadId") uploadId: String,
    ): ResponseEntity<ApiResponse<*>> = executeOk { uploads.abort(uploadId) }

    private fun executeCreated(action: () -> ResumableUploadDto): ResponseEntity<ApiResponse<*>> {
        val traceId = currentTraceId()
        return try {
            // Parse the host-visible context/servlet-prefixed collection URI before creating state.
            val requestUri = ServletUriComponentsBuilder.fromCurrentRequestUri().replaceQuery(null).build().toUri()
            val collectionUri = URI.create(requireNotNull(requestUri.rawPath) { "Upload collection path is unavailable." })
            val result = action()
            val response: ApiResponse<*> = responses.success(result, traceId)
            val location = ResumableUploadApiLocations.inspect(collectionUri, result.uploadId)
            val builder = ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .privateNoStore()
                .location(location)
            builder.body(response)
        } catch (failure: Exception) {
            failureResponse(failure, traceId)
        }
    }

    private fun executeOk(action: () -> Any): ResponseEntity<ApiResponse<*>> {
        val traceId = currentTraceId()
        return try {
            val response: ApiResponse<*> = responses.success(action(), traceId)
            ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .privateNoStore()
                .body(response)
        } catch (failure: Exception) {
            failureResponse(failure, traceId)
        }
    }

    private fun failureResponse(failure: Exception, traceId: String?): ResponseEntity<ApiResponse<*>> {
        val mapped = responses.failure(failure, traceId)
        val response: ApiResponse<*> = mapped.response
        return ResponseEntity.status(mapped.status.statusCode)
            .contentType(MediaType.APPLICATION_JSON)
            .privateNoStore()
            .body(response)
    }

    private fun ResponseEntity.BodyBuilder.privateNoStore(): ResponseEntity.BodyBuilder =
        header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(X_CONTENT_TYPE_OPTIONS, "nosniff")

    private fun requiredSinglePositiveLong(values: List<String>?, field: String): Long {
        require(values?.size == 1) { "$field header must be supplied exactly once." }
        val value = values.single()
        return requireNotNull(
            value.takeIf(POSITIVE_DECIMAL::matches)?.toLongOrNull()?.takeIf { parsed -> parsed > 0 },
        ) {
            "$field header must be a positive integer."
        }
    }

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        // Observability must not make a safely executable API operation fail.
        null
    }

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"
        const val PART_LENGTH_HEADER: String = "X-FileWeft-Part-Length"
        const val PRIVATE_NO_STORE: String = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS: String = "X-Content-Type-Options"
        val PART_NUMBER_PATTERN: Regex = Regex("[1-9][0-9]{0,4}")
        val POSITIVE_DECIMAL: Regex = Regex("[1-9][0-9]{0,18}")
    }
}
