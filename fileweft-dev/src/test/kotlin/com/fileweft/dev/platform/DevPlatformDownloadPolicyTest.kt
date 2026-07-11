package com.fileweft.dev.platform

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI

class DevPlatformDownloadPolicyTest {
    private lateinit var server: HttpServer

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/ok") { exchange ->
                val payload = "safe".toByteArray()
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            createContext("/redirect") { exchange ->
                exchange.responseHeaders.add("Location", "/ok")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            createContext("/large") { exchange ->
                val payload = ByteArray(17) { 'x'.code.toByte() }
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
    fun `downloads only an explicitly allowlisted HTTP source`() {
        val policy = DevPlatformDownloadPolicy(listOf("127.0.0.1"), 32)

        assertEquals(4, policy.verifyDownload(uri("/ok")))
    }

    @Test
    fun `rejects schemes user information and hosts outside the allowlist before opening a connection`() {
        val policy = DevPlatformDownloadPolicy(listOf("rustfs"), 32)

        assertThrows(IllegalArgumentException::class.java) { policy.verifyDownload(URI("ftp://rustfs/object")) }
        assertThrows(IllegalArgumentException::class.java) { policy.verifyDownload(URI("http://user:password@rustfs/object")) }
        assertThrows(IllegalArgumentException::class.java) { policy.verifyDownload(uri("/ok")) }
    }

    @Test
    fun `does not follow redirects and stops oversized source responses`() {
        val policy = DevPlatformDownloadPolicy(listOf("127.0.0.1"), 8)

        assertThrows(DevPlatformRetryableException::class.java) { policy.verifyDownload(uri("/redirect")) }
        assertThrows(DevPlatformRetryableException::class.java) { policy.verifyDownload(uri("/large")) }
    }

    private fun uri(path: String): URI = URI("http://127.0.0.1:${server.address.port}$path")
}
