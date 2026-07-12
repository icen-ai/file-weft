package com.fileweft.web.spring.boot3.v1.doctor

import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.api.ApiResponse
import com.fileweft.web.runtime.v1.IdempotencyKeyParser
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.V1MethodNotAllowedException
import com.fileweft.web.runtime.v1.doctor.DoctorApiFacade
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/** Spring Boot 3 MVC edge for document-scoped formal Doctor operations. */
@RestController
@RequestMapping(value = ["/fileweft/v1/documents"], produces = [MediaType.APPLICATION_JSON_VALUE])
class V1DocumentDoctorController(
    private val doctor: DoctorApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @GetMapping("/{documentId}/doctor")
    fun inspect(
        @PathVariable("documentId") documentId: String,
    ): ResponseEntity<ApiResponse<Any?>> = execute(HttpStatus.OK) {
        doctor.inspectDocument(documentId)
    }

    @PostMapping("/{documentId}/doctor/tasks")
    fun schedule(
        @PathVariable("documentId") documentId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKeys: List<String>?,
    ): ResponseEntity<ApiResponse<Any?>> = execute(HttpStatus.ACCEPTED) {
        doctor.scheduleDocument(documentId, IdempotencyKeyParser.parse(idempotencyKeys))
    }

    @GetMapping("/{documentId}/doctor/tasks/{taskId}")
    fun task(
        @PathVariable("documentId") documentId: String,
        @PathVariable("taskId") taskId: String,
    ): ResponseEntity<ApiResponse<Any?>> = execute(HttpStatus.OK) {
        doctor.task(documentId, taskId)
    }

    @RequestMapping(value = ["/{documentId}/doctor"], method = [RequestMethod.HEAD])
    fun inspectHead(
        @Suppress("UNUSED_PARAMETER") @PathVariable("documentId") documentId: String,
    ): ResponseEntity<ApiResponse<Any?>> = methodNotAllowed("GET")

    @RequestMapping(value = ["/{documentId}/doctor/tasks"], method = [RequestMethod.HEAD])
    fun scheduleHead(
        @Suppress("UNUSED_PARAMETER") @PathVariable("documentId") documentId: String,
    ): ResponseEntity<ApiResponse<Any?>> = methodNotAllowed("POST")

    @RequestMapping(value = ["/{documentId}/doctor/tasks/{taskId}"], method = [RequestMethod.HEAD])
    fun taskHead(
        @Suppress("UNUSED_PARAMETER") @PathVariable("documentId") documentId: String,
        @Suppress("UNUSED_PARAMETER") @PathVariable("taskId") taskId: String,
    ): ResponseEntity<ApiResponse<Any?>> = methodNotAllowed("GET")

    private fun execute(status: HttpStatus, action: () -> Any): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            response(status, responses.success<Any?>(action(), traceId))
        } catch (failure: Exception) {
            val mapped = responses.failure(failure, traceId)
            response(HttpStatus.valueOf(mapped.status.statusCode), mapped.response)
        }
    }

    private fun methodNotAllowed(allowedMethod: String): ResponseEntity<ApiResponse<Any?>> {
        val mapped = responses.failure(V1MethodNotAllowedException(), currentTraceId())
        return response(HttpStatus.valueOf(mapped.status.statusCode), mapped.response, allowedMethod)
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
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val PRIVATE_NO_STORE = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
    }
}
