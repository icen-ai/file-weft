package ai.icen.fw.web.spring.boot3.v1.system

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.plugin.PluginPageQuery
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.V1MethodNotAllowedException
import ai.icen.fw.web.runtime.v1.plugin.PluginInventoryApiFacade
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Authorized process-wide plugin inventory plus its manual-compatible alias. */
@RestController
class V1PluginInventoryController(
    private val plugins: PluginInventoryApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @GetMapping(value = [V1_PATH, COMPATIBILITY_PATH], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun page(
        @RequestParam(name = "cursor", required = false) cursor: String?,
        @RequestParam(name = "limit", required = false) limit: String?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        plugins.page(PluginPageQuery(cursor, parseLimit(limit)))
    }

    @RequestMapping(
        value = [V1_PATH, COMPATIBILITY_PATH],
        method = [RequestMethod.HEAD],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun pageHead(): ResponseEntity<ApiResponse<Any?>> {
        val mapped = responses.failure(V1MethodNotAllowedException(), currentTraceId())
        return response(HttpStatus.valueOf(mapped.status.statusCode), mapped.response, allowedMethod = "GET")
    }

    private fun parseLimit(value: String?): Int {
        if (value == null) return PluginPageQuery.DEFAULT_LIMIT
        require(LIMIT_PATTERN.matches(value)) { "Plugin inventory limit is invalid." }
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
        const val V1_PATH = "/fileweft/v1/plugins"
        const val COMPATIBILITY_PATH = "/fileweft/plugins"
        const val PRIVATE_NO_STORE = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
        val LIMIT_PATTERN: Regex = Regex("[1-9][0-9]{0,2}")
    }
}
