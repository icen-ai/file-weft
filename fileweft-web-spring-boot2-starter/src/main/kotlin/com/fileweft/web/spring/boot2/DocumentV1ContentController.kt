package com.fileweft.web.spring.boot2

import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.runtime.v1.ApiHttpFailure
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.V1MethodNotAllowedException
import com.fileweft.web.runtime.v1.V1RangeNotSupportedException
import com.fileweft.web.runtime.v1.document.DocumentApiDownload
import com.fileweft.web.runtime.v1.document.DocumentApiDownloadFacade
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

/**
 * Boot 2 MVC edge for the v1 document content contract.
 *
 * Trusted tenant and user context, authorization, version resolution, storage
 * paths, and download audit records remain behind [DocumentApiDownloadFacade].
 * This adapter deliberately exposes only the safe metadata normalized by the
 * runtime facade and never attempts HTTP range processing in the initial v1
 * contract.
 */
@RestController
@RequestMapping("/fileweft/v1/documents")
class DocumentV1ContentController(
    private val documents: DocumentApiDownloadFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider? = null,
) {
    @GetMapping("/{documentId}/content")
    fun currentContent(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = HttpHeaders.RANGE, required = false) range: String?,
    ): ResponseEntity<StreamingResponseBody> = download(range) { documents.download(documentId) }

    @GetMapping("/{documentId}/versions/{versionId}/content")
    fun versionContent(
        @PathVariable("documentId") documentId: String,
        @PathVariable("versionId") versionId: String,
        @RequestHeader(name = HttpHeaders.RANGE, required = false) range: String?,
    ): ResponseEntity<StreamingResponseBody> = download(range) { documents.download(documentId, versionId) }

    /**
     * Spring MVC otherwise derives a successful HEAD response from GET. That
     * would open application storage, which is not part of the v1 contract.
     */
    @RequestMapping(value = ["/{documentId}/content"], method = [RequestMethod.HEAD])
    fun currentContentHead(): ResponseEntity<StreamingResponseBody> =
        throw transportFailure(V1MethodNotAllowedException(), currentTraceId(), allowGet = true)

    /** See [currentContentHead]. */
    @RequestMapping(value = ["/{documentId}/versions/{versionId}/content"], method = [RequestMethod.HEAD])
    fun versionContentHead(): ResponseEntity<StreamingResponseBody> =
        throw transportFailure(V1MethodNotAllowedException(), currentTraceId(), allowGet = true)

    private fun download(
        range: String?,
        open: () -> DocumentApiDownload,
    ): ResponseEntity<StreamingResponseBody> {
        val traceId = currentTraceId()
        if (range != null) {
            throw transportFailure(V1RangeNotSupportedException(), traceId)
        }
        val opened = try {
            open()
        } catch (failure: Exception) {
            throw transportFailure(failure, traceId)
        }
        return try {
            val headers = HttpHeaders()
            headers[HttpHeaders.CACHE_CONTROL] = CACHE_CONTROL
            headers[X_CONTENT_TYPE_OPTIONS] = NO_SNIFF
            // The runtime facade owns sanitization and RFC 5987 encoding.
            headers[HttpHeaders.CONTENT_DISPOSITION] = opened.contentDisposition
            headers.contentType = MediaType.parseMediaType(opened.contentType)
            opened.verifiedContentLength?.let { verifiedLength ->
                require(verifiedLength >= 0) { "Verified response length must not be negative." }
                headers.contentLength = verifiedLength
            }
            ResponseEntity.ok()
                .headers(headers)
                .body(StreamingResponseBody { output -> stream(opened, output) })
        } catch (failure: Exception) {
            closeAfterResponseBuildFailure(opened, failure)
            throw transportFailure(failure, traceId)
        }
    }

    private fun stream(opened: DocumentApiDownload, output: java.io.OutputStream) {
        // This runs after the controller has returned. It intentionally has
        // no JSON failure mapping: a client/output failure must propagate as a
        // terminated response, while use still closes the caller-owned handle.
        opened.use { download ->
            download.content.copyTo(output)
            output.flush()
        }
    }

    private fun closeAfterResponseBuildFailure(opened: DocumentApiDownload, failure: Exception) {
        try {
            opened.close()
        } catch (closeFailure: Exception) {
            failure.addSuppressed(closeFailure)
        }
    }

    private fun transportFailure(
        failure: Throwable,
        traceId: String?,
        allowGet: Boolean = false,
    ): DocumentV1ContentTransportFailure = DocumentV1ContentTransportFailure(
        mapped = responses.failure(failure, traceId),
        allowGet = allowGet,
    )

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        // Observability must not make a safely executable API operation fail.
        null
    }

    private companion object {
        const val CACHE_CONTROL: String = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS: String = "X-Content-Type-Options"
        const val NO_SNIFF: String = "nosniff"
    }
}

/**
 * A synchronous, controller-originated v1 content failure. Streaming starts
 * only after the controller returns, so raw stream exceptions never use this
 * wrapper and cannot be converted into a second JSON response.
 */
internal class DocumentV1ContentTransportFailure(
    internal val mapped: ApiHttpFailure,
    internal val allowGet: Boolean,
) : RuntimeException()
