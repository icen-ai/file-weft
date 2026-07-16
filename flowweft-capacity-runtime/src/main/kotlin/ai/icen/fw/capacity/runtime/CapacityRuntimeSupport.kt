package ai.icen.fw.capacity.runtime

import ai.icen.fw.core.id.Identifier
import java.nio.CharBuffer
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.Collections
import java.util.UUID as JavaUuid

internal object CapacityRuntimeLimits {
    const val MAX_RAW_IDEMPOTENCY_KEY_BYTES: Int = 512
    const val MAX_COMMAND_ITEMS: Int = 64
    const val MAX_POLICY_CANDIDATES: Int = 256
    const val MAX_OPERATION_DURATION_MILLIS: Long = 300_000L
}

internal class CapacityRuntimeDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(domain)
    }

    fun add(value: String): CapacityRuntimeDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): CapacityRuntimeDigest = add(value.toString())

    fun add(value: Boolean): CapacityRuntimeDigest = add(value.toString())

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

internal fun requireRuntimeIdentifier(value: Identifier, label: String): Identifier {
    require(value.value.isNotBlank() && value.value == value.value.trim() &&
        value.value.toByteArray(StandardCharsets.UTF_8).size <= 512 && value.value.none(Char::isISOControl)
    ) { "$label is invalid." }
    return value
}

internal fun requireRuntimeCode(value: String, label: String): String {
    require(value.matches(Regex("[A-Za-z0-9]+(?:[._:-][A-Za-z0-9]+)*")) && value.length <= 128) {
        "$label is invalid."
    }
    return value
}

internal fun requireRuntimeDigest(value: String, label: String): String {
    require(value.length == 64 && value.all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }) { "$label digest is invalid." }
    return value
}

internal fun requireRuntimeDuration(value: Long): Long {
    require(value in 1L..CapacityRuntimeLimits.MAX_OPERATION_DURATION_MILLIS) {
        "Capacity runtime operation duration is invalid."
    }
    return value
}

internal fun runtimeDeadline(now: Long, maximumDurationMillis: Long, authorizationExpiresAt: Long): Long {
    val desired = if (now > Long.MAX_VALUE - maximumDurationMillis) Long.MAX_VALUE else now + maximumDurationMillis
    val deadline = minOf(desired, authorizationExpiresAt)
    require(deadline > now) { "Capacity runtime authorization expires before the operation can start." }
    return deadline
}

internal fun runtimeBoundedDeadline(
    now: Long,
    maximumDurationMillis: Long,
    authorizationExpiresAt: Long,
    upperBoundExclusive: Long,
): Long {
    val deadline = minOf(
        runtimeDeadline(now, maximumDurationMillis, authorizationExpiresAt),
        upperBoundExclusive,
    )
    require(deadline > now) { "Capacity runtime evidence expires before the operation can continue." }
    return deadline
}

internal fun hashRawIdempotencyKey(rawKey: CharSequence): String {
    require(rawKey.isNotEmpty() && rawKey.length <= CapacityRuntimeLimits.MAX_RAW_IDEMPOTENCY_KEY_BYTES &&
        rawKey.none { character -> Character.isISOControl(character.code) }
    ) { "Capacity idempotency key is invalid." }
    val encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val encoded = try {
        encoder.encode(CharBuffer.wrap(rawKey))
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("Capacity idempotency key is invalid.")
    }
    require(encoded.remaining() in 1..CapacityRuntimeLimits.MAX_RAW_IDEMPOTENCY_KEY_BYTES) {
        "Capacity idempotency key is invalid."
    }
    val bytes = ByteArray(encoded.remaining())
    encoded.get(bytes)
    return try {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    } finally {
        Arrays.fill(bytes, 0)
        // ByteBuffer narrows clear()'s return type only from Java 9 onward.
        // Keep the invokevirtual on Buffer so the Java 8 runtime lane remains
        // linkable while we still zero the backing bytes best-effort.
        (encoded as java.nio.Buffer).clear()
        while (encoded.hasRemaining()) encoded.put(0.toByte())
    }
}

internal fun <T> runtimeList(values: Collection<T>, maximum: Int, label: String): List<T> {
    require(values.size <= maximum) { "$label contains too many values." }
    return Collections.unmodifiableList(ArrayList(values))
}

internal fun <T> runtimeSet(values: Collection<T>, maximum: Int, label: String): Set<T> {
    require(values.size <= maximum) { "$label contains too many values." }
    val snapshot = LinkedHashSet(values)
    require(snapshot.size == values.size) { "$label contains duplicate values." }
    return Collections.unmodifiableSet(snapshot)
}

fun interface CapacityRuntimeClock {
    fun currentTimeMillis(): Long

    companion object {
        @JvmField val SYSTEM: CapacityRuntimeClock = CapacityRuntimeClock(System::currentTimeMillis)
    }
}

fun interface CapacityRuntimeIdGenerator {
    fun nextId(kind: String): Identifier

    companion object {
        @JvmField
        val UUID: CapacityRuntimeIdGenerator = CapacityRuntimeIdGenerator { kind ->
            Identifier("${requireRuntimeCode(kind, "Capacity runtime identifier kind")}-${JavaUuid.randomUUID()}")
        }
    }
}
