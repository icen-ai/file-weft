package com.fileweft.dev.platform

import java.net.HttpURLConnection
import java.net.URI
import java.time.Clock
import java.util.UUID

data class DevPlatformSyncCommand(
    val idempotencyKey: String,
    val downloadUri: URI,
    val fileName: String,
    val contentType: String? = null,
    val contentHash: String? = null,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "Idempotency key must not be blank." }
        require(downloadUri.isAbsolute) { "Download URI must be absolute." }
        require(fileName.isNotBlank()) { "File name must not be blank." }
    }
}

class DevPlatformService(
    private val repository: DevPlatformRepository,
    private val faultControl: DevPlatformFaultControl,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun synchronize(targetId: String, tenantId: String, documentId: String, command: DevPlatformSyncCommand): DevPlatformDocument {
        require(targetId.isNotBlank()) { "Target id must not be blank." }
        require(tenantId.isNotBlank()) { "Tenant id must not be blank." }
        require(documentId.isNotBlank()) { "Document id must not be blank." }
        failWhenConfigured(targetId)
        val now = clock.millis()
        val existing = repository.find(targetId, tenantId, documentId)
        val document = DevPlatformDocument(
            id = existing?.id ?: UUID.randomUUID().toString(),
            targetId = targetId,
            tenantId = tenantId,
            documentId = documentId,
            externalId = existing?.externalId ?: "$targetId:$tenantId:$documentId",
            fileName = command.fileName,
            contentType = command.contentType,
            contentHash = command.contentHash,
            downloadUri = command.downloadUri.toString(),
            downloadedBytes = verifyDownload(command.downloadUri),
            lastIdempotencyKey = command.idempotencyKey,
            createdTime = existing?.createdTime ?: now,
            updatedTime = now,
        )
        repository.save(document)
        return document
    }

    fun remove(targetId: String, tenantId: String, documentId: String, idempotencyKey: String): Boolean {
        require(idempotencyKey.isNotBlank()) { "Idempotency key must not be blank." }
        failWhenConfigured(targetId)
        return repository.delete(targetId, tenantId, documentId)
    }

    fun get(targetId: String, tenantId: String, documentId: String): DevPlatformDocument? = repository.find(targetId, tenantId, documentId)

    fun list(targetId: String?, tenantId: String?): List<DevPlatformDocument> = repository.findAll(targetId, tenantId)

    private fun failWhenConfigured(targetId: String) {
        when (faultControl.current(targetId)) {
            DevPlatformFaultMode.AVAILABLE -> Unit
            DevPlatformFaultMode.RETRYABLE_FAILURE -> throw DevPlatformRetryableException("Development platform is configured for retryable failure.")
            DevPlatformFaultMode.PERMANENT_FAILURE -> throw DevPlatformPermanentException("Development platform is configured for permanent failure.")
        }
    }

    private fun verifyDownload(uri: URI): Long {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = DOWNLOAD_TIMEOUT_MILLIS
        connection.readTimeout = DOWNLOAD_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        try {
            if (connection.responseCode !in 200..299) {
                throw DevPlatformRetryableException("Source download returned HTTP ${connection.responseCode}.")
            }
            var total = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) total = Math.addExact(total, read.toLong())
                }
            }
            return total
        } catch (failure: DevPlatformRetryableException) {
            throw failure
        } catch (failure: Exception) {
            throw DevPlatformRetryableException("Source download could not complete.", failure)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MILLIS = 10_000
        const val BUFFER_SIZE = 8 * 1024
    }
}

class DevPlatformRetryableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class DevPlatformPermanentException(message: String) : RuntimeException(message)
