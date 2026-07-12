package com.fileweft.web.spring.boot3.v1.doctor

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
class V1SystemDoctorController(
    private val doctor: DoctorApiFacade,
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
            response(HttpStatus.OK, responses.success<Any?>(doctor.inspectSystem(), traceId))
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
        const val V1_PATH = "/fileweft/v1/doctor"
        const val COMPATIBILITY_PATH = "/fileweft/doctor"
        const val PRIVATE_NO_STORE = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
    }
}
