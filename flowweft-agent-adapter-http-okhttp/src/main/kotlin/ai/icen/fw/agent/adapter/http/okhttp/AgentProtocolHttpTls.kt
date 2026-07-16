package ai.icen.fw.agent.adapter.http.okhttp

import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

enum class AgentProtocolHttpTlsIdentityKind {
    LEAF_CERTIFICATE_SHA256,
    LEAF_SPKI_SHA256,
}

internal class AgentProtocolPinnedTrustManager(
    private val delegate: X509TrustManager,
    private val identityKind: AgentProtocolHttpTlsIdentityKind,
    expectedIdentityDigest: String,
) : X509TrustManager {
    private val expectedIdentityDigest: String = expectedIdentityDigest.also {
        require(it.matches(SHA256)) { "Approved Agent protocol TLS identity digest is invalid." }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        delegate.checkServerTrusted(chain, authType)
        if (chain.isEmpty() || tlsIdentityDigest(chain[0], identityKind) != expectedIdentityDigest) {
            throw CertificateException("Agent protocol TLS peer identity did not match the approved profile.")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers.copyOf()

    override fun toString(): String = "AgentProtocolPinnedTrustManager(identity=<redacted>)"
}

internal class AgentProtocolTlsContext(
    val context: SSLContext,
    val trustManager: X509TrustManager,
)

internal fun pinnedTlsContext(
    tlsMaterial: AgentProtocolHttpTlsMaterial?,
    identityKind: AgentProtocolHttpTlsIdentityKind,
    expectedIdentityDigest: String,
): AgentProtocolTlsContext {
    val baseTrust = tlsMaterial?.trustManager() ?: systemTrustManager()
    val pinned = AgentProtocolPinnedTrustManager(baseTrust, identityKind, expectedIdentityDigest)
    val keyManagers: Array<KeyManager>? = tlsMaterial?.keyManagers()?.takeIf { it.isNotEmpty() }
    val context = SSLContext.getInstance("TLS")
    context.init(keyManagers, arrayOf(pinned), SecureRandom())
    return AgentProtocolTlsContext(context, pinned)
}

internal fun tlsIdentityDigest(
    certificate: X509Certificate,
    identityKind: AgentProtocolHttpTlsIdentityKind,
): String = when (identityKind) {
    AgentProtocolHttpTlsIdentityKind.LEAF_CERTIFICATE_SHA256 -> sha256Bytes(certificate.encoded)
    AgentProtocolHttpTlsIdentityKind.LEAF_SPKI_SHA256 -> sha256Bytes(certificate.publicKey.encoded)
}

private fun systemTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    val managers = factory.trustManagers.filterIsInstance<X509TrustManager>()
    require(managers.size == 1) { "System TLS trust manager is unavailable." }
    return managers.single()
}

private val SHA256 = Regex("[0-9a-f]{64}")
