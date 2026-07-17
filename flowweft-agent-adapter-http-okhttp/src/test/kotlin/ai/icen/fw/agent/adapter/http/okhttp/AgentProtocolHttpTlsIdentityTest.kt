package ai.icen.fw.agent.adapter.http.okhttp

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.security.cert.CertificateException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AgentProtocolHttpTlsIdentityTest {
    @Test
    fun `pinned trust manager accepts exact SPKI and rejects identity drift`() {
        val held = HeldCertificate.Builder().commonName("agent.example").build()
        val trust = HandshakeCertificates.Builder()
            .addTrustedCertificate(held.certificate)
            .build()
            .trustManager
        val expected = tlsIdentityDigest(held.certificate, AgentProtocolHttpTlsIdentityKind.LEAF_SPKI_SHA256)
        val exact = AgentProtocolPinnedTrustManager(
            trust,
            AgentProtocolHttpTlsIdentityKind.LEAF_SPKI_SHA256,
            expected,
        )
        val authType = held.keyPair.public.algorithm
        exact.checkServerTrusted(arrayOf(held.certificate), authType)

        val drifted = AgentProtocolPinnedTrustManager(
            trust,
            AgentProtocolHttpTlsIdentityKind.LEAF_SPKI_SHA256,
            "0".repeat(64),
        )
        assertFailsWith<CertificateException> {
            drifted.checkServerTrusted(arrayOf(held.certificate), authType)
        }
        assertFalse(drifted.toString().contains(expected))
    }
}
