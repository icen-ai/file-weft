package ai.icen.fw.web.spring.boot3.v1.upload

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.upload.ResumableUploadDto
import ai.icen.fw.web.api.v1.upload.StartResumableUploadRequest
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.upload.ResumableUploadApiFacade
import ai.icen.fw.web.runtime.v1.upload.ResumableUploadApiLocations
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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
 * Spring Boot 3 MVC edge for the formal resumable-upload resource.
 *
 * The controller owns HTTP cardinality and body-shape checks only. Session,
 * tenant, owner, authorization, idempotency, and storage rules remain in the
 * shared facade and application service. Part content is never buffered.
 */
@RestController
@RequestMapping(
    value = ["/fileweft/v1/uploads"],
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class V1ResumableUploadController(
    private val uploads: ResumableUploadApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun start(
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKeys: List<String>?,
        @RequestBody(required = false) request: StartResumableUploadRequest?,
    ): ResponseEntity<ApiResponse<Any?>> = executeCreated {
        uploads.start(
            idempotencyKeys,
            requireNotNull(request) { "Resumable upload request body is required." },
        )
    }

    @GetMapping("/{uploadId}")
    fun inspect(
        @PathVariable("uploadId") uploadId: String,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        uploads.inspect(uploadId)
    }

    @PutMapping(
        path = ["/{uploadId}/parts/{partNumber}"],
        consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE],
    )
    fun uploadPart(
        @PathVariable("uploadId") uploadId: String,
        @PathVariable("partNumber") partNumber: String,
        @RequestHeader(name = PART_LENGTH, required = false) partLengths: List<String>?,
        content: InputStream,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        val parsedPartNumber = parsePartNumber(partNumber)
        val parsedPartLength = parsePartLength(partLengths)
        PushbackInputStream(content, 1).use { body ->
            val firstByte = body.read()
            require(firstByte >= 0) { "Multipart part body must not be empty." }
            body.unread(firstByte)
            uploads.uploadPart(uploadId, parsedPartNumber, parsedPartLength, body)
        }
    }

    @PostMapping("/{uploadId}/complete")
    fun complete(
        @PathVariable("uploadId") uploadId: String,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        uploads.complete(uploadId)
    }

    @DeleteMapping("/{uploadId}")
    fun abort(
        @PathVariable("uploadId") uploadId: String,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        uploads.abort(uploadId)
    }

    private fun executeCreated(action: () -> ResumableUploadDto): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            // Parse the host-visible context/servlet-prefixed collection URI before creating state.
            val requestUri = ServletUriComponentsBuilder.fromCurrentRequestUri().replaceQuery(null).build().toUri()
            val collectionUri = URI.create(requireNotNull(requestUri.rawPath) { "Upload collection path is unavailable." })
            val result = action()
            val location = ResumableUploadApiLocations.inspect(collectionUri, result.uploadId)
            val response = ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .privateNoStore()
                .location(location)
            response.body(responses.success<Any?>(result, traceId))
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

    private fun parsePartNumber(value: String): Int =
        value.takeIf(PART_NUMBER_PATTERN::matches)
            ?.toIntOrNull()
            ?.takeIf { partNumber -> partNumber <= 10_000 }
            ?: throw IllegalArgumentException("Multipart part number is invalid.")

    private fun parsePartLength(values: List<String>?): Long {
        if (values?.size != 1) {
            throw IllegalArgumentException("X-FileWeft-Part-Length header must be supplied exactly once.")
        }
        val value = values[0]
        if (!PART_LENGTH_PATTERN.matches(value)) {
            throw IllegalArgumentException("X-FileWeft-Part-Length header is invalid.")
        }
        return value.toLongOrNull()?.takeIf { length -> length > 0 }
            ?: throw IllegalArgumentException("X-FileWeft-Part-Length header is invalid.")
    }

    /** Observability must not make a safely executable upload operation fail. */
    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val IDEMPOTENCY_KEY: String = "Idempotency-Key"
        const val PART_LENGTH: String = "X-FileWeft-Part-Length"
        const val PRIVATE_NO_STORE: String = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS: String = "X-Content-Type-Options"
        val PART_NUMBER_PATTERN: Regex = Regex("[1-9][0-9]{0,4}")
        val PART_LENGTH_PATTERN: Regex = Regex("[1-9][0-9]{0,18}")
    }
}
