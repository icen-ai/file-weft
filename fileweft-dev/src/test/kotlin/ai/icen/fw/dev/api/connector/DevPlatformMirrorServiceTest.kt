package ai.icen.fw.dev.api.connector

import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.dev.api.service.DevDeliveryView
import ai.icen.fw.spi.tenant.TenantProvider
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

class DevPlatformMirrorServiceTest {
    private lateinit var server: HttpServer
    private val receivedPath = AtomicReference<String?>()
    private val receivedSecret = AtomicReference<String?>()
    private val receivedTarget = AtomicReference<String?>()
    private val sharedSecret = "mirror-service-shared-secret-0123456789"
    private val capabilityUrl = "https://storage.internal/object?capability=secret"
    private val idempotencyKey = "idempotency-secret"

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/platform/v1/documents/tenant-internal/document-1") { exchange ->
                receivedPath.set(exchange.requestURI.rawPath)
                receivedSecret.set(exchange.requestHeaders.getFirst("X-FileWeft-Dev-Platform-Key"))
                receivedTarget.set(exchange.requestHeaders.getFirst("X-FileWeft-Target"))
                val payload =
                    """
                    {
                      "id": "platform-row-1",
                      "targetId": "compliance",
                      "tenantId": "tenant-internal",
                      "documentId": "document-1",
                      "externalId": "external-private-1",
                      "fileName": "approved-contract.pdf",
                      "contentType": "application/pdf",
                      "contentHash": "sha256:private",
                      "downloadUri": "$capabilityUrl",
                      "downloadedBytes": 4096,
                      "lastIdempotencyKey": "$idempotencyKey",
                      "createdTime": 1000,
                      "updatedTime": 2000,
                      "nested": {
                        "token": "platform-token",
                        "secret": "$sharedSecret"
                      }
                    }
                    """.trimIndent().toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { response -> response.write(payload) }
            }
            start()
        }
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `maps downstream response through an allowlist without leaking capability data`() {
        val mapper = jacksonObjectMapper()
        val service = DevPlatformMirrorService(
            tenantProvider = object : TenantProvider {
                override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-internal"))
            },
            baseUrl = URI("http://127.0.0.1:${server.address.port}/"),
            sharedSecret = sharedSecret,
            objectMapper = mapper,
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000,
        )

        val records = service.readDocument(Identifier("document-1"), listOf(delivery()))

        assertEquals(
            DevPlatformMirrorDocument("approved-contract.pdf", 4_096, 1_000, 2_000),
            records.single().platform,
        )
        assertEquals("SUCCESS", records.single().deliveryStatus)
        assertEquals("/platform/v1/documents/tenant-internal/document-1", receivedPath.get())
        assertEquals(sharedSecret, receivedSecret.get())
        assertEquals("compliance", receivedTarget.get())

        val serialized = mapper.valueToTree<JsonNode>(records)
        assertEquals(setOf("targetId", "deliveryStatus", "platform"), serialized.path(0).properties().map { it.key }.toSet())
        assertEquals(
            setOf("fileName", "downloadedBytes", "createdTime", "updatedTime"),
            serialized.path(0).path("platform").properties().map { it.key }.toSet(),
        )
        assertNoSensitiveData(
            serialized,
            setOf(
                "tenant-internal",
                "document-1",
                "external-private-1",
                "sha256:private",
                capabilityUrl,
                idempotencyKey,
                "platform-token",
                sharedSecret,
            ),
        )
    }

    private fun delivery(): DevDeliveryView = DevDeliveryView(
        id = "delivery-1",
        profileId = "regulated",
        targetId = "compliance",
        displayName = "Compliance archive",
        connectorId = "complianceConnector",
        requirement = "REQUIRED",
        ownerRef = "compliance-ops",
        status = "SUCCESS",
        externalId = "external-private-1",
        errorMessage = null,
        retryCount = 0,
        removalStatus = "NONE",
        removalErrorMessage = null,
        removalRetryCount = 0,
        deliveryGeneration = 1,
        updatedTime = 2_000,
    )

    private fun assertNoSensitiveData(node: JsonNode, forbiddenValues: Set<String>) {
        when {
            node.isObject -> node.properties().forEach { (name, value) ->
                val normalizedName = name.lowercase().replace("_", "").replace("-", "")
                val forbiddenKeyFragments = listOf("downloaduri", "lastidempotencykey", "tenantid", "token", "secret")
                assertFalse(forbiddenKeyFragments.any(normalizedName::contains), "Sensitive key was exposed: $name")
                assertNoSensitiveData(value, forbiddenValues)
            }
            node.isArray -> node.forEach { child -> assertNoSensitiveData(child, forbiddenValues) }
            node.isTextual -> assertFalse(
                forbiddenValues.any { forbidden -> node.textValue().contains(forbidden) },
                "Sensitive value was exposed: ${node.textValue()}",
            )
        }
    }
}
