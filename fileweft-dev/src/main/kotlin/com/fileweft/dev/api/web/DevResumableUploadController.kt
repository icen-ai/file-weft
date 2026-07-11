package com.fileweft.dev.api.web

import com.fileweft.application.upload.ResumableUploadPart
import com.fileweft.application.upload.ResumableUploadService
import com.fileweft.application.upload.ResumableUploadSessionView
import com.fileweft.application.upload.StartResumableUploadCommand
import com.fileweft.core.id.Identifier
import com.fileweft.dev.api.service.DevAccessService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PutMapping

data class DevStartResumableUploadRequest(
    val fileName: String,
    val contentLength: Long,
    val assetType: String,
    val idempotencyKey: String,
    val contentType: String? = null,
    val contentHash: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class DevResumableUploadPartResponse(val partNumber: Int, val contentLength: Long)

data class DevResumableUploadSessionResponse(
    val id: String,
    val fileName: String,
    val contentLength: Long,
    val assetType: String,
    val contentType: String?,
    val status: String,
    val expiresAt: Long,
    val lastError: String?,
    val parts: List<DevResumableUploadPartResponse>,
)

data class DevResumableUploadCompletionResponse(val sessionId: String, val fileObjectId: String, val fileAssetId: String)

data class DevResumableUploadCleanupResponse(val inspected: Int, val expired: Int, val failed: Int)

data class DevStalledResumableUploadResponse(
    val id: String,
    val fileName: String,
    val contentLength: Long,
    val expiresAt: Long,
    val updatedTime: Long,
    val lastError: String?,
)

/** Development surface for verifying resumable browser uploads against the actual storage adapter. */
@RestController
@RequestMapping("/api/resumable-uploads")
class DevResumableUploadController(
    private val uploads: ResumableUploadService,
    private val access: DevAccessService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun start(@RequestBody request: DevStartResumableUploadRequest): DevResumableUploadSessionResponse =
        uploads.start(
            StartResumableUploadCommand(
                request.fileName, request.contentLength, request.assetType, request.idempotencyKey,
                request.contentType, request.contentHash, request.metadata,
            ),
        ).let { session -> uploads.inspect(session.id).toResponse() }

    @GetMapping("/{sessionId}")
    fun inspect(@PathVariable sessionId: String): DevResumableUploadSessionResponse =
        uploads.inspect(Identifier(sessionId)).toResponse()

    @PutMapping("/{sessionId}/parts/{partNumber}", consumes = ["application/octet-stream"])
    fun uploadPart(
        @PathVariable sessionId: String,
        @PathVariable partNumber: Int,
        @RequestHeader(PART_LENGTH_HEADER) contentLength: Long,
        request: HttpServletRequest,
    ): DevResumableUploadPartResponse = request.inputStream.use { content ->
        uploads.uploadPart(Identifier(sessionId), partNumber, contentLength, content).toResponse()
    }

    @PostMapping("/{sessionId}/complete")
    fun complete(@PathVariable sessionId: String): DevResumableUploadCompletionResponse {
        val completed = uploads.complete(Identifier(sessionId))
        return DevResumableUploadCompletionResponse(sessionId, completed.fileObject.id.value, completed.fileAsset.id.value)
    }

    @DeleteMapping("/{sessionId}")
    fun abort(@PathVariable sessionId: String): DevResumableUploadSessionResponse =
        uploads.abort(Identifier(sessionId)).let { session -> uploads.inspect(session.id).toResponse() }

    @PostMapping("/cleanup")
    fun cleanup(): DevResumableUploadCleanupResponse {
        access.requireAction(Identifier(CLEANUP_RESOURCE_ID), "SYSTEM", CLEANUP_ACTION)
        return uploads.cleanupExpired().let { DevResumableUploadCleanupResponse(it.inspected, it.expired, it.failed) }
    }

    @GetMapping("/maintenance")
    fun stalledCompletions(@RequestParam(defaultValue = "100") limit: Int): List<DevStalledResumableUploadResponse> {
        return uploads.inspectStalledCompletions(limit).map { session ->
            DevStalledResumableUploadResponse(
                id = session.id.value,
                fileName = session.fileName,
                contentLength = session.contentLength,
                expiresAt = session.expiresAt,
                updatedTime = session.updatedTime,
                lastError = session.lastError,
            )
        }
    }

    private fun ResumableUploadSessionView.toResponse() = DevResumableUploadSessionResponse(
        id = session.id.value,
        fileName = session.fileName,
        contentLength = session.contentLength,
        assetType = session.assetType,
        contentType = session.contentType,
        status = session.status.name,
        expiresAt = session.expiresAt,
        lastError = session.lastError,
        parts = parts.map { part -> part.toResponse() },
    )

    private fun ResumableUploadPart.toResponse() = DevResumableUploadPartResponse(partNumber, contentLength)

    private companion object {
        const val CLEANUP_RESOURCE_ID = "resumable-upload-cleanup"
        const val CLEANUP_ACTION = "system:upload:cleanup"
        const val PART_LENGTH_HEADER = "X-FileWeft-Part-Length"
    }
}
