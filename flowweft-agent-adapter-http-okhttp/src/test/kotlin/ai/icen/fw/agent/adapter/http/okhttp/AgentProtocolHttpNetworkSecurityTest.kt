package ai.icen.fw.agent.adapter.http.okhttp

import ai.icen.fw.agent.adapter.http.AgentProtocolHttpCodecLimits
import ai.icen.fw.agent.api.AgentRemoteResolvedAddress
import okhttp3.ConnectionSpec
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class AgentProtocolHttpNetworkSecurityTest {
    @Test
    fun `production pinned DNS rejects loopback private metadata and multicast`() {
        listOf(
            byteArrayOf(127, 0, 0, 1),
            byteArrayOf(10, 0, 0, 1),
            byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()),
            byteArrayOf(224.toByte(), 0, 0, 1),
        ).forEach { address ->
            assertFailsWith<IllegalArgumentException> {
                AgentProtocolPinnedDns("agent.example", listOf(AgentRemoteResolvedAddress(address)))
            }
        }
    }

    @Test
    fun `pinned DNS never re-resolves or escapes the approved hostname`() {
        val address = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)
        val resolved = AgentRemoteResolvedAddress(address)
        val dns = AgentProtocolPinnedDns("agent.example", listOf(resolved))

        assertEquals(address.toList(), dns.lookup("AGENT.EXAMPLE").single().address.toList())
        assertEquals(resolved.addressDigest, dns.approvedDigest(InetAddress.getByAddress(address)))
        assertFailsWith<UnknownHostException> { dns.lookup("rebound.attacker.example") }
        assertFalse(dns.toString().contains("93.184.216.34"))
    }

    @Test
    fun `isolated loopback harness proves redirects and environment proxies stay disabled`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/target")))
            server.enqueue(MockResponse().setBody("must-not-be-followed"))
            val loopback = AgentRemoteResolvedAddress(byteArrayOf(127, 0, 0, 1))
            val dns = AgentProtocolPinnedDns(
                server.hostName,
                listOf(loopback),
                AgentProtocolHttpAddressMode.LOOPBACK_TEST_ONLY,
            )
            val client = hardenedAgentProtocolClientBuilder(
                dns,
                AgentProtocolOkHttpTransportConfiguration(AgentProtocolHttpCodecLimits(), callTimeoutMillis = 2_000L),
                2_000L,
            )
                .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
                .build()
            val response = client.newCall(Request.Builder().url(server.url("/start")).build()).execute()
            response.use { assertEquals(302, it.code) }

            assertEquals(1, server.requestCount)
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertFalse(client.retryOnConnectionFailure)
            assertSame(Proxy.NO_PROXY, client.proxy)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
