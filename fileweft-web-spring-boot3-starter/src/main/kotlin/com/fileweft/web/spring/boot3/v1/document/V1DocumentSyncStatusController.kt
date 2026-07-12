package com.fileweft.web.spring.boot3.v1.document

import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.api.ApiResponse
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentSyncStatusApiFacade
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/fileweft/v1/documents"], produces = [MediaType.APPLICATION_JSON_VALUE])
class V1DocumentSyncStatusController(
    private val synchronization: DocumentSyncStatusApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @GetMapping("/{documentId}/sync-status")
    fun status(@PathVariable("documentId") documentId: String): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            ResponseEntity.ok(responses.success<Any?>(synchronization.status(documentId), traceId))
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
}
