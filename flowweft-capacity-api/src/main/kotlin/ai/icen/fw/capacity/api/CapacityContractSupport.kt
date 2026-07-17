package ai.icen.fw.capacity.api

import ai.icen.fw.core.id.Identifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

internal object CapacityContractLimits {
    const val MAX_CODE_BYTES: Int = 128
    const val MAX_TOKEN_BYTES: Int = 512
    const val MAX_POLICIES: Int = 256
    const val MAX_LIMITS: Int = 64
    const val MAX_WORKLOADS: Int = 64
    const val MAX_DEGRADATIONS: Int = 64
    const val MAX_DOCTOR_SIGNALS: Int = 128
    const val MAX_PROVIDER_CAPABILITIES: Int = 64
    const val MAX_REQUEST_DURATION_MILLIS: Long = 300_000L
    const val MAX_LEASE_DURATION_MILLIS: Long = 86_400_000L
}

private val CAPACITY_CODE_PATTERN = Regex("[A-Za-z0-9]+(?:[._:-][A-Za-z0-9]+)*")

internal fun requireCapacityIdentifier(value: Identifier, label: String): Identifier {
    requireCapacityToken(value.value, label)
    return value
}

internal fun requireCapacityCode(value: String, label: String): String {
    require(value.toByteArray(StandardCharsets.UTF_8).size <= CapacityContractLimits.MAX_CODE_BYTES &&
        CAPACITY_CODE_PATTERN.matches(value)
    ) { "$label is invalid." }
    return value
}

internal fun requireCapacityToken(value: String, label: String): String {
    require(value.isNotBlank() && value == value.trim() &&
        value.toByteArray(StandardCharsets.UTF_8).size <= CapacityContractLimits.MAX_TOKEN_BYTES &&
        value.none(Char::isISOControl)
    ) { "$label is invalid." }
    return value
}

internal fun requireCapacityDigest(value: String, label: String): String {
    require(value.length == 64 && value.all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }) { "$label digest is invalid." }
    return value
}

internal fun capacitySafeAdd(first: Long, second: Long, label: String): Long = try {
    Math.addExact(first, second)
} catch (_: ArithmeticException) {
    throw IllegalArgumentException("$label overflows the supported range.")
}

internal class CapacityDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(domain)
    }

    fun add(value: String): CapacityDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): CapacityDigest = add(value.toString())

    fun add(value: Boolean): CapacityDigest = add(value.toString())

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

internal fun <T> capacityList(values: Collection<T>, maximum: Int, label: String): List<T> {
    require(values.size <= maximum) { "$label contains too many values." }
    return Collections.unmodifiableList(ArrayList(values))
}

internal fun <T> capacitySet(values: Collection<T>, maximum: Int, label: String): Set<T> {
    require(values.size <= maximum) { "$label contains too many values." }
    val snapshot = LinkedHashSet(values)
    require(snapshot.size == values.size) { "$label contains duplicate values." }
    return Collections.unmodifiableSet(snapshot)
}

internal fun <K, V> capacityMap(values: Map<K, V>, maximum: Int, label: String): Map<K, V> {
    require(values.size <= maximum) { "$label contains too many values." }
    return Collections.unmodifiableMap(LinkedHashMap(values))
}
