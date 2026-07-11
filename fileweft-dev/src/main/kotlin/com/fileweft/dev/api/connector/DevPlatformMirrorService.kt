package com.fileweft.dev.api.connector

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.core.id.Identifier
import com.fileweft.dev.api.service.DevDeliveryView
import com.fileweft.dev.platform.DevPlatformAuthenticator
import com.fileweft.spi.tenant.TenantProvider
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class DevPlatformMirrorRecord(
    val targetId: String,
    val deliveryStatus: String,
    val platform: JsonNode?,
)

/**
 * Server-side read facade for the proof-lab downstream mirror.  The browser
 * never receives the simulator's shared credential and cannot call its
 * internal interface directly.
 */
class DevPlatformMirrorService(
    private val tenantProvider: TenantProvider,
    private val baseUrl: URI,
    private val sharedSecret: String,
    private val objectMapper: ObjectMapper,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
) {
    init {
        require(baseUrl.isAbsolute) { "Development platform URL must be absolute." }
        require(baseUrl.scheme == "http" || baseUrl.scheme == "https") { "Development platform URL must use HTTP or HTTPS." }
        require(sharedSecret.length >= MINIMUM_SECRET_LENGTH) {
            "Development platform shared secret must contain at least $MINIMUM_SECRET_LENGTH characters."
        }
        require(connectTimeoutMillis > 0) { "Development platform connect timeout must be positive." }
        require(readTimeoutMillis > 0) { "Development platform read timeout must be positive." }
    }

    fun readDocument(documentId: Identifier, deliveries: List<DevDeliveryView>): List<DevPlatformMirrorRecord> {
        val tenantId = tenantProvider.currentTenant().tenantId
        return deliveries.map { delivery ->
            DevPlatformMirrorRecord(
                targetId = delivery.targetId,
                deliveryStatus = delivery.status,
                platform = readTarget(tenantId, documentId, delivery.targetId),
            )
        }
    }

    private fun readTarget(tenantId: Identifier, documentId: Identifier, targetId: String): JsonNode? {
        val path = "platform/v1/documents/${pathSegment(tenantId.value)}/${pathSegment(documentId.value)}"
        val connection = (baseUrl.resolve(path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = false
            setRequestProperty(TARGET_HEADER, targetId)
            setRequestProperty(DevPlatformAuthenticator.HEADER_NAME, sharedSecret)
        }
        try {
            return when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> null
                in 200..299 -> connection.inputStream.use(objectMapper::readTree)
                else -> throw IllegalStateException("Development platform mirror returned HTTP $status.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun pathSegment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val TARGET_HEADER = "X-FileWeft-Target"
        const val MINIMUM_SECRET_LENGTH = 32
    }
}
