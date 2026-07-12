package com.fileweft.web.spring.boot2

import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.api.ApiResponse
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.V1MethodNotAllowedException
import com.fileweft.web.runtime.v1.doctor.DoctorApiFacade
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/** Tenant-context-derived system Doctor route plus its manual-compatible alias. */
@RestController
class SystemV1DoctorController(
    private val doctor: DoctorApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider? = null,
) {
    @GetMapping(value = [V1_PATH, COMPATIBILITY_PATH], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun inspect(): ResponseEntity<ApiResponse<*>> = execute()

    @RequestMapping(
        value = [V1_PATH, COMPATIBILITY_PATH],
        method = [RequestMethod.HEAD],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun inspectHead(): ResponseEntity<ApiResponse<*>> {
        val mapped = responses.failure(V1MethodNotAllowedException(), currentTraceId())
        val response: ApiResponse<*> = mapped.response
        return response(HttpStatus.valueOf(mapped.status.statusCode), response, allowedMethod = "GET")
    }

    private fun execute(): ResponseEntity<ApiResponse<*>> {
        val traceId = currentTraceId()
        return try {
            val result: ApiResponse<*> = responses.success(doctor.inspectSystem(), traceId)
            response(HttpStatus.OK, result)
        } catch (failure: Exception) {
            val mapped = responses.failure(failure, traceId)
            val result: ApiResponse<*> = mapped.response
            response(HttpStatus.valueOf(mapped.status.statusCode), result)
        }
    }

    private fun response(
        status: HttpStatus,
        body: ApiResponse<*>,
        allowedMethod: String? = null,
    ): ResponseEntity<ApiResponse<*>> {
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
        const val V1_PATH = "/fileweft/v1/doctor"
        const val COMPATIBILITY_PATH = "/fileweft/doctor"
        const val PRIVATE_NO_STORE = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
    }
}
