package ai.icen.fw.agent.adapter.network.jdk

import ai.icen.fw.core.id.Identifier
import java.net.InetAddress
import java.util.UUID

class JdkAgentRemoteNetworkResolverConfiguration @JvmOverloads constructor(
    val lookupTimeoutMillis: Long = 2_000L,
    val resolutionTtlMillis: Long = 30_000L,
    val maximumAddresses: Int = 16,
) {
    init {
        require(lookupTimeoutMillis in 1L..30_000L) {
            "Agent remote DNS lookup timeout is invalid."
        }
        require(resolutionTtlMillis in 1L..300_000L) {
            "Agent remote DNS resolution TTL is invalid."
        }
        require(maximumAddresses in 1..32) {
            "Agent remote DNS address limit is invalid."
        }
    }

    override fun toString(): String =
        "JdkAgentRemoteNetworkResolverConfiguration(timeout=$lookupTimeoutMillis, ttl=$resolutionTtlMillis, maximumAddresses=$maximumAddresses)"
}

fun interface AgentRemoteDnsLookup {
    fun resolve(host: String): Collection<ByteArray>

    companion object {
        @JvmField
        val SYSTEM: AgentRemoteDnsLookup = AgentRemoteDnsLookup { host ->
            InetAddress.getAllByName(host).map { address -> address.address.copyOf() }
        }
    }
}

fun interface JdkAgentRemoteNetworkClock {
    fun currentTimeMillis(): Long

    companion object {
        @JvmField
        val SYSTEM: JdkAgentRemoteNetworkClock = JdkAgentRemoteNetworkClock(System::currentTimeMillis)
    }
}

fun interface JdkAgentRemoteNetworkIdSource {
    fun nextId(purpose: String): Identifier

    companion object {
        @JvmField
        val RANDOM_UUID: JdkAgentRemoteNetworkIdSource = JdkAgentRemoteNetworkIdSource { purpose ->
            Identifier("$purpose-${UUID.randomUUID()}")
        }
    }
}

class JdkAgentRemoteNetworkResolutionException(code: String) :
    RuntimeException("Agent remote network resolution failed: ${networkCode(code)}") {
    val code: String = networkCode(code)

    override fun toString(): String = "JdkAgentRemoteNetworkResolutionException(code=$code, details=<redacted>)"
}

internal fun networkCode(value: String): String = value.also {
    require(it.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*"))) {
        "Agent remote network diagnostic code is invalid."
    }
}
