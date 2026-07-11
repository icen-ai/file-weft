package com.fileweft.web.spring.boot3.v1.document

import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.api.ApiResponse
import com.fileweft.web.runtime.v1.ApiHttpFailure
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.V1MethodNotAllowedException
import com.fileweft.web.runtime.v1.V1RangeNotSupportedException
import com.fileweft.web.runtime.v1.document.DocumentApiDownload
import com.fileweft.web.runtime.v1.document.DocumentApiDownloadFacade
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

/** Formal v1 transport for current and explicitly selected document content. */
@RestController
@RequestMapping("/fileweft/v1/documents")
class V1DocumentContentController(
    private val documents: DocumentApiDownloadFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @GetMapping("/{documentId}/content")
    fun current(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = HttpHeaders.RANGE, required = false) range: String?,
    ): ResponseEntity<StreamingResponseBody> = execute {
        rejectRange(range)
        streamingResponse(documents.download(documentId))
    }

    @GetMapping("/{documentId}/versions/{versionId}/content")
    fun version(
        @PathVariable("documentId") documentId: String,
        @PathVariable("versionId") versionId: String,
        @RequestHeader(name = HttpHeaders.RANGE, required = false) range: String?,
    ): ResponseEntity<StreamingResponseBody> = execute {
        rejectRange(range)
        streamingResponse(documents.download(documentId, versionId))
    }

    @RequestMapping(path = ["/{documentId}/content"], method = [RequestMethod.HEAD])
    fun currentHead(): ResponseEntity<StreamingResponseBody> = execute {
        throw V1MethodNotAllowedException()
    }

    @RequestMapping(path = ["/{documentId}/versions/{versionId}/content"], method = [RequestMethod.HEAD])
    fun versionHead(): ResponseEntity<StreamingResponseBody> = execute {
        throw V1MethodNotAllowedException()
    }

    /**
     * Converts an already authorized, caller-owned handle into a response. If
     * response preparation fails, ownership never reaches MVC and the handle
     * is closed here. Once streaming starts, a failure leaves the handle's use
     * scope only after it closes, then propagates outside the
     * synchronous JSON mapper so no envelope is appended to partial content.
     */
    private fun streamingResponse(download: DocumentApiDownload): ResponseEntity<StreamingResponseBody> = try {
        val verifiedLength = download.verifiedContentLength
        require(verifiedLength == null || verifiedLength >= 0L) {
            "Verified document content length must not be negative."
        }
        val contentType = MediaType.parseMediaType(download.contentType)
        val body = StreamingResponseBody { output ->
            download.use { opened ->
                opened.content.copyTo(output)
                output.flush()
            }
        }
        val response = ResponseEntity.ok()
            .contentType(contentType)
            .header(HttpHeaders.CONTENT_DISPOSITION, download.contentDisposition)
            .header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
        if (verifiedLength != null) {
            response.contentLength(verifiedLength)
        }
        response.body(body)
    } catch (failure: Exception) {
        closeBeforeResponse(download, failure)
        throw failure
    }

    private fun execute(action: () -> ResponseEntity<StreamingResponseBody>): ResponseEntity<StreamingResponseBody> = try {
        action()
    } catch (failure: Exception) {
        throw V1DocumentContentTransportException(
            mapped = responses.failure(failure, currentTraceId()),
            allowGet = failure is V1MethodNotAllowedException,
        )
    }

    private fun rejectRange(range: String?) {
        if (range != null) {
            throw V1RangeNotSupportedException()
        }
    }

    private fun closeBeforeResponse(download: DocumentApiDownload, failure: Exception) {
        try {
            download.close()
        } catch (closeFailure: Exception) {
            failure.addSuppressed(closeFailure)
        }
    }

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }
}

/** Internal control-flow carrier containing only an already sanitized failure. */
internal class V1DocumentContentTransportException(
    internal val mapped: ApiHttpFailure,
    internal val allowGet: Boolean,
) : RuntimeException()

/** Maps only synchronous content preparation failures; stream errors bypass it. */
@RestControllerAdvice(assignableTypes = [V1DocumentContentController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class V1DocumentContentFailureHandler {
    @ExceptionHandler(V1DocumentContentTransportException::class)
    internal fun failureResponse(failure: V1DocumentContentTransportException): ResponseEntity<ApiResponse<Any?>> {
        val response = ResponseEntity.status(failure.mapped.status.statusCode)
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
        if (failure.allowGet) {
            response.allow(HttpMethod.GET)
        }
        return response.body(failure.mapped.response)
    }
}

private const val PRIVATE_NO_STORE = "private, no-store"
private const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
private const val NOSNIFF = "nosniff"
