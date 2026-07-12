package ai.icen.fw.web.spring.boot3.v1.system

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.V1MethodNotAllowedException
import ai.icen.fw.web.runtime.v1.health.HealthApiFacade
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/** Public dependency-free process liveness route plus its manual-compatible alias. */
@RestController
class V1HealthController(
    private val health: HealthApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @GetMapping(value = [V1_PATH, COMPATIBILITY_PATH], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun inspect(): ResponseEntity<ApiResponse<Any?>> = execute()

    @RequestMapping(
        value = [V1_PATH, COMPATIBILITY_PATH],
        method = [RequestMethod.HEAD],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun inspectHead(): ResponseEntity<ApiResponse<Any?>> {
        val mapped = responses.failure(V1MethodNotAllowedException(), currentTraceId())
        return response(HttpStatus.valueOf(mapped.status.statusCode), mapped.response, allowedMethod = "GET")
    }

    private fun execute(): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            response(HttpStatus.OK, responses.success<Any?>(health.inspect(), traceId))
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
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
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
        const val V1_PATH = "/fileweft/v1/health"
        const val COMPATIBILITY_PATH = "/fileweft/health"
        const val NO_STORE = "no-store"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
    }
}
