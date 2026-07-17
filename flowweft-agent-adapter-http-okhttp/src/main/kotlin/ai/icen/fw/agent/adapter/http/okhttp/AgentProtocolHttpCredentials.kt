package ai.icen.fw.agent.adapter.http.okhttp

import ai.icen.fw.agent.adapter.http.AgentProtocolHttpMethod
import ai.icen.fw.agent.api.AgentRemoteAuthenticationScheme
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchRequest
import java.net.URI
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManager
import javax.net.ssl.X509TrustManager

/** Exact request presented to the trusted host credential broker. It contains no secret material. */
class AgentProtocolHttpCredentialRequest internal constructor(
    val dispatch: AgentRemoteProtocolDispatchRequest,
    val method: AgentProtocolHttpMethod,
    val targetUri: URI,
    val requestBodyDigest: String,
    val requestedAt: Long,
) {
    val credentialLeaseBindingDigest: String = dispatch.credentialLease.bindingDigest

    init {
        require(targetUri == dispatch.profile.resourceUri) { "HTTP credential target differs from the approved resource." }
        require(requestBodyDigest.matches(SHA256)) { "HTTP credential request body digest is invalid." }
        dispatch.credentialLease.requireCurrentFor(dispatch.credentialRequest, requestedAt)
    }

    override fun toString(): String =
        "AgentProtocolHttpCredentialRequest(method=$method, target=<redacted>, lease=<redacted>, body=<redacted>)"
}

fun interface AgentProtocolHttpCredentialProvider {
    /** Resolves the opaque short lease; implementations must never return long-lived credentials. */
    fun acquire(request: AgentProtocolHttpCredentialRequest): CompletionStage<AgentProtocolHttpCredentialMaterial>
}

/**
 * Host-created TLS material. Key managers may hold an mTLS key, while the trust manager may use a
 * private CA. Neither is exposed through a public getter or string representation.
 */
class AgentProtocolHttpTlsMaterial private constructor(
    keyManagers: Collection<KeyManager>,
    trustManager: X509TrustManager,
) {
    private val keyManagerSnapshot: Array<KeyManager> = keyManagers.toTypedArray()
    private val trustManagerValue: X509TrustManager = trustManager

    internal fun keyManagers(): Array<KeyManager> = keyManagerSnapshot.copyOf()

    internal fun trustManager(): X509TrustManager = trustManagerValue

    override fun toString(): String = "AgentProtocolHttpTlsMaterial(keys=<redacted>, trust=<redacted>)"

    companion object {
        @JvmStatic
        fun customTrust(trustManager: X509TrustManager): AgentProtocolHttpTlsMaterial =
            AgentProtocolHttpTlsMaterial(emptyList(), trustManager)

        @JvmStatic
        fun mutualTls(
            keyManagers: Collection<KeyManager>,
            trustManager: X509TrustManager,
        ): AgentProtocolHttpTlsMaterial {
            require(keyManagers.isNotEmpty()) { "mTLS requires at least one key manager." }
            return AgentProtocolHttpTlsMaterial(keyManagers, trustManager)
        }
    }
}

/**
 * Erasable, one-exchange authentication material. Only this adapter module can reveal header
 * values. OAuth and DPoP proofs remain unavailable to codecs and protocol callers.
 */
class AgentProtocolHttpCredentialMaterial private constructor(
    val scheme: AgentRemoteAuthenticationScheme,
    headers: Map<String, CharArray>,
    private val tlsMaterialValue: AgentProtocolHttpTlsMaterial?,
) : AutoCloseable {
    private val destroyed = AtomicBoolean(false)
    private val headerSnapshot: Map<String, CharArray>

    init {
        val snapshot = LinkedHashMap<String, CharArray>()
        headers.forEach { (name, value) ->
            require(name in ALLOWED_AUTH_HEADERS) { "HTTP credential material contains an unsupported header." }
            require(value.isNotEmpty() && value.size <= MAX_AUTH_HEADER_CHARS && value.all(::isVisibleAscii)) {
                "HTTP credential material contains an invalid header value."
            }
            snapshot[name] = value.copyOf()
        }
        when (scheme) {
            AgentRemoteAuthenticationScheme.OAUTH2_BEARER -> require("Authorization" in snapshot) {
                "OAuth credential material requires an Authorization header."
            }
            AgentRemoteAuthenticationScheme.MUTUAL_TLS -> require(snapshot.isEmpty() && tlsMaterialValue != null) {
                "mTLS credential material requires TLS key material and no authorization header."
            }
        }
        headerSnapshot = Collections.unmodifiableMap(snapshot)
    }

    fun headerNames(): Set<String> = Collections.unmodifiableSet(LinkedHashSet(headerSnapshot.keys))

    fun isDestroyed(): Boolean = destroyed.get()

    internal fun headersForTransport(): Map<String, String> {
        check(!destroyed.get()) { "HTTP credential material was already destroyed." }
        return headerSnapshot.mapValuesTo(LinkedHashMap()) { (_, value) -> String(value) }
    }

    internal fun tlsMaterial(): AgentProtocolHttpTlsMaterial? {
        check(!destroyed.get()) { "HTTP credential material was already destroyed." }
        return tlsMaterialValue
    }

    override fun close() {
        if (destroyed.compareAndSet(false, true)) {
            headerSnapshot.values.forEach { value -> java.util.Arrays.fill(value, '\u0000') }
        }
    }

    override fun toString(): String =
        "AgentProtocolHttpCredentialMaterial(scheme=$scheme, headers=${headerSnapshot.keys}, values=<redacted>)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun oauthBearer(
            accessToken: CharArray,
            dpopProof: CharArray? = null,
            tlsMaterial: AgentProtocolHttpTlsMaterial? = null,
        ): AgentProtocolHttpCredentialMaterial {
            require(accessToken.isNotEmpty() && accessToken.size <= MAX_AUTH_HEADER_CHARS &&
                accessToken.all { character -> character.code in 0x21..0x7e }
            ) { "OAuth access token is invalid." }
            require(dpopProof == null || (dpopProof.isNotEmpty() && dpopProof.size <= MAX_AUTH_HEADER_CHARS &&
                dpopProof.all { character -> character.code in 0x21..0x7e })
            ) { "DPoP proof is invalid." }
            val authorization = "Bearer ".toCharArray() + accessToken
            val headers = LinkedHashMap<String, CharArray>()
            headers["Authorization"] = authorization
            dpopProof?.let { proof -> headers["DPoP"] = proof.copyOf() }
            val material = AgentProtocolHttpCredentialMaterial(
                AgentRemoteAuthenticationScheme.OAUTH2_BEARER,
                headers,
                tlsMaterial,
            )
            headers.values.forEach { value -> java.util.Arrays.fill(value, '\u0000') }
            return material
        }

        @JvmStatic
        fun mutualTls(tlsMaterial: AgentProtocolHttpTlsMaterial): AgentProtocolHttpCredentialMaterial =
            AgentProtocolHttpCredentialMaterial(
                AgentRemoteAuthenticationScheme.MUTUAL_TLS,
                emptyMap(),
                tlsMaterial,
            )
    }
}

internal fun requireCredentialMatches(
    dispatch: AgentRemoteProtocolDispatchRequest,
    material: AgentProtocolHttpCredentialMaterial,
) {
    if (material.scheme != dispatch.profile.credential.scheme) {
        throw AgentProtocolHttpTransportException(
            "http-credential-scheme-mismatch",
            AgentProtocolHttpTransportOutcome.REJECTED_BEFORE_DISPATCH,
            false,
        )
    }
}

private fun isVisibleAscii(value: Char): Boolean = value.code in 0x21..0x7e || value == ' '

private val SHA256 = Regex("[0-9a-f]{64}")
private val ALLOWED_AUTH_HEADERS = setOf("Authorization", "DPoP")
private const val MAX_AUTH_HEADER_CHARS = 16_384
