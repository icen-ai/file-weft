package ai.icen.fw.agent.adapter.credential.runtime

import ai.icen.fw.agent.adapter.http.AgentProtocolHttpMethod
import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpCredentialMaterial
import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpCredentialRequest
import ai.icen.fw.agent.api.AgentRemoteAuthenticationScheme
import ai.icen.fw.agent.api.AgentRemoteCredentialLease
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletionStage

class AgentCredentialRuntimeConfiguration @JvmOverloads constructor(
    val leaseTtlMillis: Long = 10_000L,
    val materialTimeoutMillis: Long = 3_000L,
    val maximumActiveLeases: Int = 10_000,
    val maximumActiveLeasesPerTenant: Int = 1_000,
) {
    init {
        require(leaseTtlMillis in 1L..60_000L) { "Agent credential lease TTL is invalid." }
        require(materialTimeoutMillis in 1L..30_000L) { "Agent credential material timeout is invalid." }
        require(maximumActiveLeases in 1..100_000) { "Agent credential lease capacity is invalid." }
        require(maximumActiveLeasesPerTenant in 1..maximumActiveLeases) {
            "Agent credential per-tenant lease capacity is invalid."
        }
    }

    override fun toString(): String =
        "AgentCredentialRuntimeConfiguration(ttl=$leaseTtlMillis, timeout=$materialTimeoutMillis, capacity=$maximumActiveLeases)"
}

/** Narrow secret-source request. It intentionally has no operation payload, prompt, or response. */
class AgentRemoteCredentialMaterialRequest internal constructor(
    val tenantId: Identifier,
    val principalId: Identifier,
    val principalType: String,
    val brokerId: ProviderId,
    val leaseId: Identifier,
    val credentialReference: Identifier,
    val ownerPeerId: ProviderId,
    val scheme: AgentRemoteAuthenticationScheme,
    val protectedResourceAudience: URI,
    val credentialRevision: String,
    val method: AgentProtocolHttpMethod,
    val requestBodyDigest: String,
    val requestedAt: Long,
    val expiresAt: Long,
    leaseBindingDigest: String,
) {
    val leaseBindingDigest: String = credentialDigest(leaseBindingDigest, "Credential lease binding")
    val requestDigest: String

    init {
        require(protectedResourceAudience.isAbsolute &&
            protectedResourceAudience.scheme.equals("https", ignoreCase = true)
        ) { "Agent credential audience is invalid." }
        credentialDigest(requestBodyDigest, "Credential request body")
        require(requestedAt >= 0L && expiresAt > requestedAt) {
            "Agent credential material request lifetime is invalid."
        }
        requestDigest = CredentialDigest("flowweft.agent.credential-material-request.v1")
            .add(tenantId.value)
            .add(principalId.value)
            .add(principalType)
            .add(brokerId.value)
            .add(leaseId.value)
            .add(credentialReference.value)
            .add(ownerPeerId.value)
            .add(scheme.name)
            .add(protectedResourceAudience.toASCIIString())
            .add(credentialRevision)
            .add(method.name)
            .add(requestBodyDigest)
            .add(requestedAt)
            .add(expiresAt)
            .add(this.leaseBindingDigest)
            .finish()
    }

    override fun toString(): String =
        "AgentRemoteCredentialMaterialRequest(scheme=$scheme, credential=<redacted>, target=<redacted>)"

    companion object {
        internal fun from(
            request: AgentProtocolHttpCredentialRequest,
            lease: AgentRemoteCredentialLease,
        ): AgentRemoteCredentialMaterialRequest {
            val operation = request.dispatch.invocation.operation
            return AgentRemoteCredentialMaterialRequest(
                operation.context.tenantId,
                operation.context.principalId,
                operation.context.principalType,
                lease.brokerId,
                lease.leaseId,
                lease.credentialReference,
                lease.ownerPeerId,
                request.dispatch.profile.credential.scheme,
                lease.protectedResourceAudience,
                lease.credentialRevision,
                request.method,
                request.requestBodyDigest,
                request.requestedAt,
                minOf(lease.expiresAt, request.dispatch.invocation.deadlineAt),
                lease.bindingDigest,
            )
        }
    }
}

/** Secret-bearing result whose value remains hidden inside the existing erasable material type. */
class AgentRemoteCredentialMaterialResult private constructor(
    requestDigest: String,
    credentialRevision: String,
    val material: AgentProtocolHttpCredentialMaterial,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    val requestDigest: String = credentialDigest(requestDigest, "Credential material request")
    val credentialRevision: String = credentialToken(credentialRevision, "Credential material revision")

    init {
        require(issuedAt >= 0L && expiresAt > issuedAt) { "Agent credential material lifetime is invalid." }
    }

    fun matches(request: AgentRemoteCredentialMaterialRequest, atTime: Long): Boolean =
        requestDigest == request.requestDigest && credentialRevision == request.credentialRevision &&
            material.scheme == request.scheme && issuedAt in request.requestedAt..atTime &&
            atTime in issuedAt until expiresAt && expiresAt <= request.expiresAt

    override fun toString(): String =
        "AgentRemoteCredentialMaterialResult(scheme=${material.scheme}, material=<redacted>)"

    companion object {
        @JvmStatic
        fun bound(
            request: AgentRemoteCredentialMaterialRequest,
            material: AgentProtocolHttpCredentialMaterial,
            issuedAt: Long,
            expiresAt: Long,
        ): AgentRemoteCredentialMaterialResult = AgentRemoteCredentialMaterialResult(
            request.requestDigest,
            request.credentialRevision,
            material,
            issuedAt,
            expiresAt,
        )
    }
}

fun interface AgentRemoteCredentialMaterialSource {
    fun acquire(request: AgentRemoteCredentialMaterialRequest): CompletionStage<AgentRemoteCredentialMaterialResult>
}

fun interface AgentCredentialRuntimeClock {
    fun currentTimeMillis(): Long

    companion object {
        @JvmField
        val SYSTEM: AgentCredentialRuntimeClock = AgentCredentialRuntimeClock(System::currentTimeMillis)
    }
}

fun interface AgentCredentialRuntimeIdSource {
    fun nextId(purpose: String): Identifier

    companion object {
        @JvmField
        val RANDOM_UUID: AgentCredentialRuntimeIdSource = AgentCredentialRuntimeIdSource { purpose ->
            Identifier("$purpose-${UUID.randomUUID()}")
        }
    }
}

class AgentCredentialRuntimeException(code: String) :
    RuntimeException("Agent credential runtime failed: ${credentialCode(code)}") {
    val code: String = credentialCode(code)

    override fun toString(): String = "AgentCredentialRuntimeException(code=$code, credential=<redacted>)"
}

internal fun credentialCode(value: String): String = value.also {
    require(it.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*"))) {
        "Agent credential diagnostic code is invalid."
    }
}

internal fun credentialToken(value: String, label: String): String = value.also {
    require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)) { "$label is invalid." }
}

internal fun credentialDigest(value: String, label: String): String = value.also {
    require(it.matches(Regex("[0-9a-f]{64}"))) { "$label digest is invalid." }
}

internal class CredentialDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(domain)
    }

    fun add(value: String): CredentialDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): CredentialDigest = add(value.toString())

    fun finish(): String = digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
