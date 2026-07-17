package ai.icen.fw.adapter.dify

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale

/**
 * Exact-origin SSRF policy for FlowWeft-owned download URLs. Private address
 * access is disabled by default and can only be enabled on this administrator
 * profile together with an exact HTTPS origin allowlist.
 */
class DifySourceTrustPolicy @JvmOverloads constructor(
    allowedOrigins: Collection<URI>,
    val allowPrivateAddresses: Boolean = false,
) {
    val allowedOrigins: List<URI> = immutableList(allowedOrigins.map(::normalizeOrigin).distinct())
    private val originKeys: Set<String> = this.allowedOrigins.mapTo(linkedSetOf(), ::originKey)

    init {
        require(this.allowedOrigins.isNotEmpty()) { "At least one trusted source origin is required." }
    }

    /** Validates request-controlled URI structure before DNS or network access. */
    fun requireTrustedSourceUri(uri: URI) {
        require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true)) {
            "Dify source URI must use HTTPS."
        }
        require(!uri.host.isNullOrBlank()) { "Dify source URI must contain a host." }
        require(uri.port == -1 || uri.port in 1..65535) { "Dify source URI port is invalid." }
        require(uri.rawUserInfo == null && uri.rawFragment == null) {
            "Dify source URI must not contain user information or a fragment."
        }
        require(uri.normalize().rawPath == uri.rawPath) { "Dify source URI must not contain dot segments." }
        require(!hasEncodedPathConfusion(uri.rawPath.orEmpty())) {
            "Dify source URI must not contain encoded path traversal or separators."
        }
        require(uri.toASCIIString().length <= MAX_SOURCE_URI_ASCII_LENGTH) {
            "Dify source URI exceeds the configured safety bound."
        }
        require(originKey(uri) in originKeys) { "Dify source URI origin is not trusted by this profile." }
    }

    internal fun resolveTrusted(hostname: String): List<InetAddress> =
        resolveTrustedAddresses(hostname, allowPrivateAddresses)

    private companion object {
        const val MAX_SOURCE_URI_ASCII_LENGTH: Int = 8192
    }
}

internal interface DifyTrustedAddressResolver {
    fun resolve(hostname: String): List<InetAddress>
}

internal class PolicyDifyTrustedAddressResolver(
    private val policy: DifySourceTrustPolicy,
) : DifyTrustedAddressResolver {
    override fun resolve(hostname: String): List<InetAddress> = policy.resolveTrusted(hostname)
}

/** One-request DNS snapshot: OkHttp may connect only to the addresses already checked by the policy. */
internal class DifyPinnedDns(
    hostname: String,
    addresses: Collection<InetAddress>,
) : Dns {
    private val hostname: String = hostname.lowercase(Locale.ROOT)
    private val addresses: List<InetAddress> = immutableList(addresses)

    init {
        require(this.hostname.isNotBlank()) { "Pinned Dify source hostname must not be blank." }
        require(this.addresses.isNotEmpty()) { "Pinned Dify source addresses must not be empty." }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.lowercase(Locale.ROOT) != this.hostname) {
            throw UnknownHostException("Dify source DNS lookup escaped the validated hostname.")
        }
        return addresses
    }
}

/**
 * Resolves each Dify API connection to one already-validated address snapshot.
 * OkHttp connects to the returned [InetAddress] values directly, so DNS cannot
 * change between the policy check and the socket connection.
 */
internal class DifyValidatingDns(
    private val allowPrivateAddresses: Boolean,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        resolveTrustedAddresses(hostname, allowPrivateAddresses)
}

private fun resolveTrustedAddresses(hostname: String, allowPrivateAddresses: Boolean): List<InetAddress> {
    val addresses = try {
        InetAddress.getAllByName(hostname).toList()
    } catch (failure: UnknownHostException) {
        throw failure
    }
    if (addresses.isEmpty()) throw UnknownHostException("Trusted host did not resolve.")
    if (!allowPrivateAddresses && addresses.any(::isNonPublicAddress)) {
        throw UnknownHostException("Trusted host resolved to a non-public address.")
    }
    return addresses
}

private fun normalizeOrigin(value: URI): URI {
    require(value.isAbsolute && value.scheme.equals("https", ignoreCase = true)) {
        "Trusted source origins must use HTTPS."
    }
    require(!value.host.isNullOrBlank()) { "Trusted source origin must contain a host." }
    require(value.port == -1 || value.port in 1..65535) { "Trusted source origin port is invalid." }
    require(value.rawUserInfo == null && value.rawQuery == null && value.rawFragment == null) {
        "Trusted source origin must not contain user information, query, or fragment."
    }
    require(value.rawPath.isNullOrEmpty() || value.rawPath == "/") {
        "Trusted source origin must not contain a path."
    }
    return URI("https", null, value.host.lowercase(Locale.ROOT), effectiveHttpsPort(value), null, null, null)
}

private fun originKey(value: URI): String =
    "https://${value.host.lowercase(Locale.ROOT)}:${effectiveHttpsPort(value)}"

private fun effectiveHttpsPort(value: URI): Int = if (value.port == -1) 443 else value.port

internal fun hasEncodedPathConfusion(rawPath: String): Boolean {
    val lower = rawPath.lowercase(Locale.ROOT)
    if ("%2f" in lower || "%5c" in lower || '\\' in rawPath) return true
    return lower.split('/').any { segment ->
        val decodedDots = segment.replace("%2e", ".")
        decodedDots == "." || decodedDots == ".."
    }
}

private fun isNonPublicAddress(address: InetAddress): Boolean {
    if (
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return true
    val bytes = address.address
    return when (address) {
        is Inet4Address -> isReservedIpv4(bytes)
        is Inet6Address -> isReservedIpv6(bytes)
        else -> true
    }
}

private fun isReservedIpv4(bytes: ByteArray): Boolean {
    val a = bytes[0].toInt() and 0xff
    val b = bytes[1].toInt() and 0xff
    val c = bytes[2].toInt() and 0xff
    return a == 0 || a == 10 || a == 127 || a >= 224 ||
        (a == 100 && b in 64..127) ||
        (a == 169 && b == 254) ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 0 && c == 0) ||
        (a == 192 && b == 0 && c == 2) ||
        (a == 192 && b == 168) ||
        (a == 192 && b == 88 && c == 99) ||
        (a == 198 && b in 18..19) ||
        (a == 198 && b == 51 && c == 100) ||
        (a == 203 && b == 0 && c == 113)
}

private fun isReservedIpv6(bytes: ByteArray): Boolean {
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    val uniqueLocal = (first and 0xfe) == 0xfc
    val linkLocal = first == 0xfe && (second and 0xc0) == 0x80
    val documentation = bytes.size == 16 && bytes[0] == 0x20.toByte() && bytes[1] == 0x01.toByte() &&
        bytes[2] == 0x0d.toByte() && bytes[3] == 0xb8.toByte()
    val discardOnly = bytes.size == 16 && bytes[0] == 0x01.toByte() &&
        bytes.sliceArray(1 until 8).all { it == 0.toByte() }
    val orchid = bytes.size == 16 && bytes[0] == 0x20.toByte() && bytes[1] == 0x01.toByte() &&
        ((bytes[2].toInt() and 0xff) in 0x10..0x2f)
    val benchmarking = bytes.size == 16 && bytes[0] == 0x20.toByte() && bytes[1] == 0x01.toByte() &&
        bytes[2] == 0x02.toByte() && bytes[3] == 0x00.toByte()
    val teredo = bytes.size == 16 && bytes[0] == 0x20.toByte() && bytes[1] == 0x01.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
    val sixToFour = bytes.size == 16 && bytes[0] == 0x20.toByte() && bytes[1] == 0x02.toByte()
    val nat64 = bytes.size == 16 && (
        (bytes[0] == 0x00.toByte() && bytes[1] == 0x64.toByte() && bytes[2] == 0xff.toByte() &&
            bytes[3] == 0x9b.toByte()) ||
            (bytes[0] == 0x00.toByte() && bytes[1] == 0x64.toByte() && bytes[2] == 0xff.toByte() &&
                bytes[3] == 0x9b.toByte() && bytes[4] == 0x00.toByte() && bytes[5] == 0x01.toByte())
        )
    val ipv4Compatible = bytes.size == 16 && bytes.take(12).all { it == 0.toByte() }
    val ipv4Mapped = bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
        bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte() &&
        isReservedIpv4(bytes.copyOfRange(12, 16))
    return uniqueLocal || linkLocal || documentation || discardOnly || orchid || benchmarking || teredo ||
        sixToFour || nat64 || ipv4Compatible || ipv4Mapped
}
