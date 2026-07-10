package com.fileweft.dev.platform

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

data class DevPlatformSyncRequest(
    val idempotencyKey: String,
    val downloadUri: URI,
    val fileName: String,
    val contentType: String? = null,
    val contentHash: String? = null,
)

data class DevPlatformRemoveRequest(
    val idempotencyKey: String,
    val externalId: String,
)

data class DevPlatformResponse(
    val externalId: String,
    val downloadedBytes: Long? = null,
)

data class DevPlatformFaultRequest(
    val mode: DevPlatformFaultMode,
)

@RestController
@RequestMapping("/platform/v1")
class DevPlatformController(
    private val platform: DevPlatformService,
    private val faults: DevPlatformFaultControl,
) {
    @PutMapping("/documents/{tenantId}/{documentId}")
    fun synchronize(
        @PathVariable tenantId: String,
        @PathVariable documentId: String,
        @RequestBody request: DevPlatformSyncRequest,
    ): DevPlatformResponse {
        val document = platform.synchronize(
            tenantId,
            documentId,
            DevPlatformSyncCommand(request.idempotencyKey, request.downloadUri, request.fileName, request.contentType, request.contentHash),
        )
        return DevPlatformResponse(document.externalId, document.downloadedBytes)
    }

    @DeleteMapping("/documents/{tenantId}/{documentId}")
    fun remove(
        @PathVariable tenantId: String,
        @PathVariable documentId: String,
        @RequestBody request: DevPlatformRemoveRequest,
    ): DevPlatformResponse {
        platform.remove(tenantId, documentId, request.idempotencyKey)
        return DevPlatformResponse(request.externalId)
    }

    @GetMapping("/documents/{tenantId}/{documentId}")
    fun get(@PathVariable tenantId: String, @PathVariable documentId: String): ResponseEntity<DevPlatformDocument> =
        platform.get(tenantId, documentId)?.let { document -> ResponseEntity.ok(document) }
            ?: ResponseEntity.notFound().build()

    @GetMapping("/documents")
    fun list(@RequestParam(required = false) tenantId: String?): List<DevPlatformDocument> = platform.list(tenantId)

    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, String>> = when (faults.current()) {
        DevPlatformFaultMode.AVAILABLE -> ResponseEntity.ok(mapOf("status" to "UP"))
        else -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("status" to faults.current().name))
    }

    @PostMapping("/admin/fault-mode")
    fun setFaultMode(@RequestBody request: DevPlatformFaultRequest): Map<String, String> {
        faults.set(request.mode)
        return mapOf("mode" to faults.current().name)
    }

    @ExceptionHandler(DevPlatformRetryableException::class)
    fun retryable(failure: DevPlatformRetryableException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("message" to (failure.message ?: "Retryable failure")))

    @ExceptionHandler(DevPlatformPermanentException::class, IllegalArgumentException::class)
    fun permanent(failure: RuntimeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(mapOf("message" to (failure.message ?: "Rejected")))
}
