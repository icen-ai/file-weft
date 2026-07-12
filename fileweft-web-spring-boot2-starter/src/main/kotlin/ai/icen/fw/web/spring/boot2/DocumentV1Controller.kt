package ai.icen.fw.web.spring.boot2

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
 * Boot 2 MVC edge for the v1 document read API.
 *
 * Request values intentionally remain strings here. Parsing and bounded DTO
 * construction happen inside this controller, while tenant identity, user
 * identity, authorization, and all reads stay behind [DocumentApiReadFacade].
 */
@RestController
@RequestMapping(
    value = ["/fileweft/v1/documents"],
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class DocumentV1Controller(
    private val documents: DocumentApiReadFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider? = null,
) {
    @GetMapping("/{documentId}")
    fun detail(@PathVariable("documentId") documentId: String): ResponseEntity<ApiResponse<*>> =
        execute { documents.detail(documentId) }

    @GetMapping
    fun page(
        @RequestParam(name = "cursor", required = false) cursor: String?,
        @RequestParam(name = "limit", required = false) limit: String?,
        @RequestParam(name = "lifecycleState", required = false) lifecycleState: String?,
        @RequestParam(name = "folderId", required = false) folderId: String?,
    ): ResponseEntity<ApiResponse<*>> = execute {
        documents.page(
            DocumentPageQuery(
                cursor = cursor,
                limit = parseLimit(limit),
                lifecycleState = lifecycleState,
                folderId = folderId,
            ),
        )
    }

    private fun execute(action: () -> Any): ResponseEntity<ApiResponse<*>> {
        val traceId = currentTraceId()
        return try {
            val response: ApiResponse<*> = responses.success(action(), traceId)
            ResponseEntity.ok(response)
        } catch (failure: Exception) {
            val mapped = responses.failure(failure, traceId)
            val response: ApiResponse<*> = mapped.response
            ResponseEntity.status(mapped.status.statusCode).body(response)
        }
    }

    private fun parseLimit(value: String?): Int {
        if (value == null) {
            return DocumentPageQuery.DEFAULT_LIMIT
        }
        require(value.matches(LIMIT_PATTERN)) {
            "Document page limit is invalid."
        }
        return value.toInt()
    }

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        // Observability must not make a safely executable API operation fail.
        null
    }

    private companion object {
        val LIMIT_PATTERN: Regex = Regex("[1-9][0-9]{0,2}")
    }
}
