package ai.icen.fw.web.spring.boot3.v1.document

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.document.DocumentPageQuery
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentApiReadFacade
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Formal v1 document read routes.
 *
 * The controller deliberately accepts only opaque document filters. It never
 * accepts a tenant, user, role, or authorization decision from the HTTP
 * request: [DocumentApiReadFacade] delegates that work to the application
 * service and its trusted SPI context.
 */
@RestController
@RequestMapping(
    value = ["/fileweft/v1/documents"],
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class V1DocumentReadController(
    private val documents: DocumentApiReadFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @GetMapping("/{documentId}")
    fun detail(@PathVariable("documentId") documentId: String): ResponseEntity<ApiResponse<Any?>> =
        execute { documents.detail(documentId) }

    @GetMapping
    fun page(
        @RequestParam(name = "cursor", required = false) cursor: String?,
        @RequestParam(name = "limit", required = false) limit: String?,
        @RequestParam(name = "lifecycleState", required = false) lifecycleState: String?,
        @RequestParam(name = "folderId", required = false) folderId: String?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        documents.page(
            DocumentPageQuery(
                cursor = cursor,
                limit = parseLimit(limit),
                lifecycleState = lifecycleState,
                folderId = folderId,
            ),
        )
    }

    private fun parseLimit(value: String?): Int {
        if (value == null) {
            return DocumentPageQuery.DEFAULT_LIMIT
        }
        require(LIMIT_PATTERN.matches(value)) { "Document page limit is invalid." }
        return value.toInt()
    }

    /**
     * Keep error handling adjacent to the endpoint invocation. This prevents a
     * host's broad exception advice from accidentally turning internal failure
     * messages into this public protocol.
     */
    private fun <T> execute(action: () -> T): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            ResponseEntity.ok(responses.success<Any?>(action(), traceId))
        } catch (failure: Exception) {
            val mapped = responses.failure(failure, traceId)
            ResponseEntity.status(mapped.status.statusCode).body(mapped.response)
        }
    }

    /** Observability must not make a business request unavailable. */
    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }

    private companion object {
        val LIMIT_PATTERN = Regex("[1-9][0-9]{0,2}")
    }
}
