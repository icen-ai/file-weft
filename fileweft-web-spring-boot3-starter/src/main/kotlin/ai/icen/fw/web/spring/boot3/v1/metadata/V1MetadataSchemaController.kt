package ai.icen.fw.web.spring.boot3.v1.metadata

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.metadata.MetadataSchemaDto
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.metadata.MetadataSchemaApiFacade
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(
    value = ["/fileweft/v1/metadata/schemas"],
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class V1MetadataSchemaController(
    private val schemas: MetadataSchemaApiFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    @GetMapping("/{schemaId}")
    fun get(@PathVariable("schemaId") schemaId: String): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(responses.success<Any?>(schemas.get(schemaId), traceId))
        } catch (failure: Exception) {
            val mapped = responses.failure(failure, traceId)
            ResponseEntity.status(mapped.status.statusCode)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapped.response)
        }
    }

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }
}
