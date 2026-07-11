package com.fileweft.dev.api.connector

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fileweft.core.id.Identifier
import com.fileweft.spi.connector.ConnectorFileSource
import com.fileweft.spi.connector.ConnectorInvocation
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncStatus
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

class DevPlatformConnectorTest {
    private lateinit var server: HttpServer
    private val receivedSecret = AtomicReference<String?>()
    private val receivedTarget = AtomicReference<String?>()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/platform/v1/documents/tenant-a/document-1") { exchange ->
                receivedSecret.set(exchange.requestHeaders.getFirst("X-FileWeft-Dev-Platform-Key"))
                receivedTarget.set(exchange.requestHeaders.getFirst("X-FileWeft-Target"))
                val payload = """{"externalId":"downstream-1"}""".toByteArray()
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            start()
        }
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `sends the platform shared credential on connector synchronization`() {
        val secret = "connector-security-test-secret-0123456789"
        val connector = DevPlatformConnector(
            URI("http://127.0.0.1:${server.address.port}/"),
            jacksonObjectMapper(),
            1_000,
            1_000,
            "compliance",
            secret,
        )

        val result = connector.sync(
            ConnectorSyncRequest(
                tenantId = Identifier("tenant-a"),
                businessId = Identifier("document-1"),
                source = ConnectorFileSource(URI("http://rustfs:9000/fileweft-dev/object"), "contract.txt"),
                invocation = ConnectorInvocation("delivery-1", Duration.ofSeconds(1)),
            ),
        )

        assertEquals(ConnectorSyncStatus.SUCCESS, result.status)
        assertEquals("downstream-1", result.externalId)
        assertEquals(secret, receivedSecret.get())
        assertEquals("compliance", receivedTarget.get())
    }
}
