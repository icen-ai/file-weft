package com.fileweft.web.spring.boot2

import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.api.ApiResponse
import com.fileweft.web.api.v1.document.DocumentDeliveryRecoveryResultDto
import com.fileweft.web.runtime.v1.IdempotencyKeyParser
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentDeliveryRecoveryApiFacade
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/fileweft/v1/documents"], produces = [MediaType.APPLICATION_JSON_VALUE])
class DocumentV1DeliveryRecoveryController(
    private val recoveries: DocumentDeliveryRecoveryApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider? = null,
) {
    @PostMapping("/{documentId}/deliveries/{deliveryId}/retry")
    fun retryDelivery(
        @PathVariable("documentId") documentId: String,
        @PathVariable("deliveryId") deliveryId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) keys: List<String>?,
    ): ResponseEntity<ApiResponse<*>> = execute {
        recoveries.retryDelivery(documentId, deliveryId, IdempotencyKeyParser.parse(keys))
    }

    @PostMapping("/{documentId}/deliveries/{deliveryId}/removal/retry")
    fun retryRemoval(
        @PathVariable("documentId") documentId: String,
        @PathVariable("deliveryId") deliveryId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) keys: List<String>?,
    ): ResponseEntity<ApiResponse<*>> = execute {
        recoveries.retryRemoval(documentId, deliveryId, IdempotencyKeyParser.parse(keys))
    }

    private fun execute(action: () -> DocumentDeliveryRecoveryResultDto): ResponseEntity<ApiResponse<*>> {
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

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}
