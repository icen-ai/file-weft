package ai.icen.fw.web.spring.boot3.v1.document

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.document.DocumentDeliveryRecoveryResultDto
import ai.icen.fw.web.runtime.v1.IdempotencyKeyParser
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentDeliveryRecoveryApiFacade
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/fileweft/v1/documents"], produces = [MediaType.APPLICATION_JSON_VALUE])
class V1DocumentDeliveryRecoveryController(
    private val recoveries: DocumentDeliveryRecoveryApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @PostMapping("/{documentId}/deliveries/{deliveryId}/retry")
    fun retryDelivery(
        @PathVariable("documentId") documentId: String,
        @PathVariable("deliveryId") deliveryId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) keys: List<String>?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        recoveries.retryDelivery(documentId, deliveryId, IdempotencyKeyParser.parse(keys))
    }

    @PostMapping("/{documentId}/deliveries/{deliveryId}/removal/retry")
    fun retryRemoval(
        @PathVariable("documentId") documentId: String,
        @PathVariable("deliveryId") deliveryId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) keys: List<String>?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        recoveries.retryRemoval(documentId, deliveryId, IdempotencyKeyParser.parse(keys))
    }

    private fun execute(action: () -> DocumentDeliveryRecoveryResultDto): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            ResponseEntity.ok(responses.success<Any?>(action(), traceId))
        } catch (failure: Exception) {
            val mapped = responses.failure(failure, traceId)
            ResponseEntity.status(mapped.status.statusCode).body(mapped.response)
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
