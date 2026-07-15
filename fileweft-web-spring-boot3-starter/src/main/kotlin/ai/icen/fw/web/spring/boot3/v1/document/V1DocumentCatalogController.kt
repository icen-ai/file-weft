package ai.icen.fw.web.spring.boot3.v1.document

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.catalog.DocumentCatalogPageQuery
import ai.icen.fw.web.api.v1.catalog.MoveDocumentToFolderRequest
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.V1MethodNotAllowedException
import ai.icen.fw.web.runtime.v1.catalog.DocumentCatalogApiFacade
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Host-owned catalog browsing and the single controlled document binding command. */
@RestController
class V1DocumentCatalogController(
    private val catalog: DocumentCatalogApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @GetMapping(value = [FOLDERS_PATH], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun page(
        @RequestParam(name = "cursor", required = false) cursor: String?,
        @RequestParam(name = "limit", required = false) limit: String?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        catalog.page(DocumentCatalogPageQuery(cursor, parseLimit(limit)))
    }

    @RequestMapping(value = [FOLDERS_PATH], method = [RequestMethod.HEAD])
    fun pageHead(): ResponseEntity<ApiResponse<Any?>> {
        val mapped = responses.failure(V1MethodNotAllowedException(), currentTraceId())
        return response(HttpStatus.valueOf(mapped.status.statusCode), mapped.response, "GET")
    }

    @PutMapping(
        value = [MOVE_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun move(
        @PathVariable("documentId") documentId: String,
        @RequestBody request: MoveDocumentToFolderRequest,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        catalog.move(documentId, request.folderId)
    }

    private fun parseLimit(value: String?): Int {
        if (value == null) return DocumentCatalogPageQuery.DEFAULT_LIMIT
        require(LIMIT_PATTERN.matches(value)) { "Document catalog page limit is invalid." }
        return value.toInt()
    }

    private fun <T> execute(action: () -> T): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            response(HttpStatus.OK, responses.success<Any?>(action(), traceId))
        } catch (failure: Exception) {
            val mapped = responses.failure(failure, traceId)
            response(HttpStatus.valueOf(mapped.status.statusCode), mapped.response)
        }
    }

    private fun response(
        status: HttpStatus,
        body: ApiResponse<Any?>,
        allowedMethod: String? = null,
    ): ResponseEntity<ApiResponse<Any?>> {
        val builder = ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
            .header(HttpHeaders.PRAGMA, NO_CACHE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
        if (allowedMethod != null) builder.header(HttpHeaders.ALLOW, allowedMethod)
        return builder.body(body)
    }

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val FOLDERS_PATH = "/fileweft/v1/catalog/folders"
        const val MOVE_PATH = "/fileweft/v1/documents/{documentId}/catalog-folder"
        const val PRIVATE_NO_STORE = "private, no-store"
        const val NO_CACHE = "no-cache"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
        val LIMIT_PATTERN = Regex("[1-9][0-9]{0,2}")
    }
}
