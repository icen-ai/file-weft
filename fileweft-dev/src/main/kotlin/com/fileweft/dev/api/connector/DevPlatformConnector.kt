package com.fileweft.dev.api.connector

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.spi.connector.ConnectorHealth
import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.ConnectorRemoveRequest
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncResult
import com.fileweft.spi.connector.ConnectorSyncStatus
import com.fileweft.spi.connector.FileConnector
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.min

/** HTTP connector for the repository's isolated development platform simulator. */
class DevPlatformConnector(
    private val baseUrl: URI,
    private val objectMapper: ObjectMapper,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
) : FileConnector {
    init {
        require(baseUrl.isAbsolute) { "Development platform URL must be absolute." }
        require(baseUrl.scheme == "http" || baseUrl.scheme == "https") { "Development platform URL must use HTTP or HTTPS." }
        require(connectTimeoutMillis > 0) { "Development platform connect timeout must be positive." }
        require(readTimeoutMillis > 0) { "Development platform read timeout must be positive." }
    }

    override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult {
        val body = linkedMapOf(
            "idempotencyKey" to request.invocation.idempotencyKey,
            "downloadUri" to request.source.downloadUri.toString(),
            "fileName" to request.source.fileName,
            "contentType" to request.source.contentType,
            "contentHash" to request.source.contentHash,
            "attributes" to request.attributes,
        )
        return invoke(
            method = "PUT",
            path = "platform/v1/documents/${pathSegment(request.tenantId.value)}/${pathSegment(request.businessId.value)}",
            body = objectMapper.writeValueAsBytes(body),
            invocationTimeoutMillis = timeoutMillis(request.invocation.timeout.toMillis()),
        )
    }

    override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult = invoke(
        method = "DELETE",
        path = "platform/v1/documents/${pathSegment(request.tenantId.value)}/${pathSegment(request.businessId.value)}",
        body = objectMapper.writeValueAsBytes(
            mapOf("idempotencyKey" to request.invocation.idempotencyKey, "externalId" to request.externalId),
        ),
        invocationTimeoutMillis = timeoutMillis(request.invocation.timeout.toMillis()),
    )

    override fun health(): ConnectorHealth = try {
        val connection = connection("platform/v1/health", "GET", min(connectTimeoutMillis, readTimeoutMillis))
        try {
            if (connection.responseCode in 200..299) ConnectorHealth(ConnectorHealthStatus.HEALTHY)
            else ConnectorHealth(ConnectorHealthStatus.UNHEALTHY, "Development platform returned HTTP ${connection.responseCode}.")
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        ConnectorHealth(ConnectorHealthStatus.UNHEALTHY, "Development platform is unreachable.")
    }

    private fun invoke(method: String, path: String, body: ByteArray, invocationTimeoutMillis: Int): ConnectorSyncResult = try {
        val connection = connection(path, method, invocationTimeoutMillis)
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            BufferedOutputStream(connection.outputStream).use { output -> output.write(body) }
            val status = connection.responseCode
            when {
                status in 200..299 -> {
                    val response = connection.inputStream.use { input -> objectMapper.readTree(input) }
                    val externalId = response.path("externalId").asText().takeIf { value -> value.isNotBlank() }
                    if (externalId == null) {
                        ConnectorSyncResult(ConnectorSyncStatus.PERMANENT_FAILURE, message = "Development platform did not return externalId.")
                    } else {
                        ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, externalId)
                    }
                }

                status in 400..499 -> ConnectorSyncResult(
                    ConnectorSyncStatus.PERMANENT_FAILURE,
                    message = "Development platform rejected the request with HTTP $status.",
                )

                else -> ConnectorSyncResult(
                    ConnectorSyncStatus.RETRYABLE_FAILURE,
                    message = "Development platform returned HTTP $status.",
                )
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE, message = "Development platform request could not complete.")
    }

    private fun connection(path: String, method: String, timeoutMillis: Int): HttpURLConnection = (baseUrl.resolve(path).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = min(connectTimeoutMillis, timeoutMillis)
        readTimeout = min(readTimeoutMillis, timeoutMillis)
        instanceFollowRedirects = false
    }

    private fun timeoutMillis(value: Long): Int = when {
        value <= 0 -> 1
        value > Int.MAX_VALUE -> Int.MAX_VALUE
        else -> value.toInt()
    }

    private fun pathSegment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
