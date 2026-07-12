package ai.icen.fw.dev.api.connector

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.dev.api.service.DevDeliveryView
import ai.icen.fw.dev.platform.DevPlatformAuthenticator
import ai.icen.fw.spi.tenant.TenantProvider
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class DevPlatformMirrorRecord(
    val targetId: String,
    val deliveryStatus: String,
    val platform: DevPlatformMirrorDocument?,
)

/** Explicit downstream allowlist returned by the development API. */
data class DevPlatformMirrorDocument(
    val fileName: String,
    val downloadedBytes: Long,
    val createdTime: Long,
    val updatedTime: Long,
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

    private fun readTarget(tenantId: Identifier, documentId: Identifier, targetId: String): DevPlatformMirrorDocument? {
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
                in 200..299 -> connection.inputStream.use { input -> objectMapper.readTree(input).toMirrorDocument() }
                else -> throw IllegalStateException("Development platform mirror returned HTTP $status.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun pathSegment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun JsonNode.toMirrorDocument(): DevPlatformMirrorDocument {
        require(isObject) { "Development platform mirror response must be a JSON object." }
        val createdTime = requiredNonNegativeLong("createdTime")
        val updatedTime = requiredNonNegativeLong("updatedTime")
        require(updatedTime >= createdTime) { "Development platform mirror update time precedes its creation time." }
        return DevPlatformMirrorDocument(
            fileName = requiredText("fileName"),
            downloadedBytes = requiredNonNegativeLong("downloadedBytes"),
            createdTime = createdTime,
            updatedTime = updatedTime,
        )
    }

    private fun JsonNode.requiredText(fieldName: String): String {
        val field = get(fieldName)
        require(field != null && field.isTextual && field.textValue().isNotBlank()) {
            "Development platform mirror field $fieldName must be a non-blank string."
        }
        return field.textValue()
    }

    private fun JsonNode.requiredNonNegativeLong(fieldName: String): Long {
        val field = get(fieldName)
        require(field != null && field.isIntegralNumber && field.canConvertToLong()) {
            "Development platform mirror field $fieldName must be an integer."
        }
        return field.longValue().also { value ->
            require(value >= 0) { "Development platform mirror field $fieldName must not be negative." }
        }
    }

    private companion object {
        const val TARGET_HEADER = "X-FileWeft-Target"
        const val MINIMUM_SECRET_LENGTH = 32
    }
}
