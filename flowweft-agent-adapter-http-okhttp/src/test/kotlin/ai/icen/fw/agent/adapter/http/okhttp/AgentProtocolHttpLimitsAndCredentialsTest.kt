package ai.icen.fw.agent.adapter.http.okhttp

import ai.icen.fw.agent.adapter.http.AgentProtocolHttpCodecLimits
import okhttp3.Headers
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentProtocolHttpLimitsAndCredentialsTest {
    @Test
    fun `bounded reader rejects declared and streamed overflow without retaining extra bytes`() {
        val declared = readBoundedResponse(ByteArrayInputStream(bytes("ignored")), 8L, 4)
        assertFalse(declared.complete)
        assertContentEquals(ByteArray(0), declared.bytes)

        val streamed = readBoundedResponse(ByteArrayInputStream(bytes("123456")), -1L, 4)
        assertFalse(streamed.complete)
        assertContentEquals(bytes("1234"), streamed.bytes)
    }

    @Test
    fun `response header count and value bounds fail closed`() {
        val limits = AgentProtocolHttpCodecLimits(maximumHeaderCount = 1, maximumHeaderValueCodePoints = 8)
        assertFailsWith<AgentProtocolHttpResponseLimitException> {
            captureBoundedHeaders(Headers.headersOf("A", "1", "B", "2"), limits)
        }
        assertFailsWith<AgentProtocolHttpResponseLimitException> {
            captureBoundedHeaders(Headers.headersOf("A", "123456789"), limits)
        }
    }

    @Test
    fun `oauth and DPoP values are redacted and erasable`() {
        val material = AgentProtocolHttpCredentialMaterial.oauthBearer(
            "access-token-secret".toCharArray(),
            "dpop-proof-secret".toCharArray(),
        )

        assertEquals(setOf("Authorization", "DPoP"), material.headerNames())
        assertFalse(material.toString().contains("access-token-secret"))
        assertFalse(material.toString().contains("dpop-proof-secret"))
        material.close()
        assertTrue(material.isDestroyed())
        assertFailsWith<IllegalStateException> { material.headersForTransport() }
    }

    private fun bytes(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)
}
