package ai.icen.fw.agent.adapter.http.okhttp

import ai.icen.fw.agent.api.AgentRemoteResolvedAddress
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

internal enum class AgentProtocolHttpAddressMode {
    PUBLIC_ONLY,
    LOOPBACK_TEST_ONLY,
}

/** DNS adapter over the runtime's immutable resolution; this class never calls a resolver. */
internal class AgentProtocolPinnedDns(
    hostname: String,
    resolvedAddresses: Collection<AgentRemoteResolvedAddress>,
    private val mode: AgentProtocolHttpAddressMode = AgentProtocolHttpAddressMode.PUBLIC_ONLY,
) : Dns {
    private val hostname: String = hostname.lowercase(Locale.ROOT)
    private val addresses: List<InetAddress>
    private val digestByAddress: Map<String, String>

    init {
        require(this.hostname.isNotBlank()) { "Pinned Agent protocol hostname is invalid." }
        require(resolvedAddresses.isNotEmpty() && resolvedAddresses.size <= 32) {
            "Pinned Agent protocol address count is invalid."
        }
        val addressSnapshot = ArrayList<InetAddress>()
        val digests = LinkedHashMap<String, String>()
        resolvedAddresses.forEach { resolved ->
            val bytes = resolved.bytes()
            val address = InetAddress.getByAddress(this.hostname, bytes)
            val accepted = when (mode) {
                AgentProtocolHttpAddressMode.PUBLIC_ONLY -> resolved.isPubliclyRoutable() && !isForbiddenAddress(address)
                AgentProtocolHttpAddressMode.LOOPBACK_TEST_ONLY -> address.isLoopbackAddress
            }
            require(accepted) { "Pinned Agent protocol address is not allowed by this transport policy." }
            val key = addressKey(bytes)
            require(digests.put(key, resolved.addressDigest) == null) {
                "Pinned Agent protocol resolution contains a duplicate address."
            }
            addressSnapshot.add(address)
        }
        addresses = Collections.unmodifiableList(addressSnapshot)
        digestByAddress = Collections.unmodifiableMap(digests)
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.lowercase(Locale.ROOT) != this.hostname) {
            throw UnknownHostException("Agent protocol DNS lookup escaped the approved hostname.")
        }
        return addresses
    }

    fun approvedDigest(address: InetAddress): String? = digestByAddress[addressKey(address.address)]

    override fun toString(): String = "AgentProtocolPinnedDns(host=<redacted>, addresses=<redacted>)"
}

internal fun isForbiddenAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return true
    val bytes = address.address
    if (bytes.size == 4) {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 0 || first == 10 || first == 127 || first >= 224 ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }
    if (bytes.size == 16) {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return (first and 0xfe) == 0xfc || (first == 0xfe && (second and 0xc0) == 0x80) ||
            bytes.all { it == 0.toByte() } ||
            (bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte()) ||
            (bytes.take(10).all { it == 0.toByte() } && bytes[10] == 0xff.toByte() &&
                bytes[11] == 0xff.toByte())
    }
    return true
}

private fun addressKey(bytes: ByteArray): String = bytes.joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}
