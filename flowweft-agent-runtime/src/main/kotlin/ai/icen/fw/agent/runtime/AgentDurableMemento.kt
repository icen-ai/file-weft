package ai.icen.fw.agent.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Arrays

private const val AGENT_STATE_MAX_MEMENTO_BYTES = 64 * 1_024 * 1_024
private const val AGENT_EVENT_MAX_MEMENTO_BYTES = 24 * 1_024 * 1_024

/** Immutable digest-bound state bytes suitable for a BLOB plus digest column. */
class AgentDurableStateMemento private constructor(
    payload: ByteArray,
    digest: String?,
) {
    private val payloadSnapshot: ByteArray = boundedMementoBytes(
        payload,
        AGENT_STATE_MAX_MEMENTO_BYTES,
        "Agent durable state memento is too large.",
    )
    val digest: String = digest?.let { expected ->
        requireRuntimeDigest(expected, "Agent durable state memento digest is invalid.")
        require(runtimeSha256(payloadSnapshot) == expected) {
            "Agent durable state memento digest does not match its bytes."
        }
        expected
    } ?: runtimeSha256(payloadSnapshot)

    val payload: ByteArray
        get() = payloadSnapshot.copyOf()

    companion object {
        internal fun create(payload: ByteArray): AgentDurableStateMemento = AgentDurableStateMemento(payload, null)

        @JvmStatic
        fun restore(payload: ByteArray, digest: String): AgentDurableStateMemento =
            AgentDurableStateMemento(payload, digest)
    }
}

/** Immutable digest-bound event bytes suitable for the ordered event ledger. */
class AgentRunEventMemento private constructor(
    payload: ByteArray,
    digest: String?,
) {
    private val payloadSnapshot: ByteArray = boundedMementoBytes(
        payload,
        AGENT_EVENT_MAX_MEMENTO_BYTES,
        "Agent run event memento is too large.",
    )
    val digest: String = digest?.let { expected ->
        requireRuntimeDigest(expected, "Agent run event memento digest is invalid.")
        require(runtimeSha256(payloadSnapshot) == expected) {
            "Agent run event memento digest does not match its bytes."
        }
        expected
    } ?: runtimeSha256(payloadSnapshot)

    val payload: ByteArray
        get() = payloadSnapshot.copyOf()

    companion object {
        internal fun create(payload: ByteArray): AgentRunEventMemento = AgentRunEventMemento(payload, null)

        @JvmStatic
        fun restore(payload: ByteArray, digest: String): AgentRunEventMemento = AgentRunEventMemento(payload, digest)
    }
}

internal class AgentMementoWriter(
    private val maximumBytes: Int,
) {
    private val bytes = ByteArrayOutputStream()
    private val output = DataOutputStream(bytes)
    private var nestingDepth = 0

    fun byte(value: Int) {
        require(value in 0..255) { "Agent memento byte value is invalid." }
        output.writeByte(value)
    }

    fun boolean(value: Boolean) = byte(if (value) 1 else 0)

    fun int(value: Int) = output.writeInt(value)

    fun long(value: Long) = output.writeLong(value)

    fun double(value: Double) = long(java.lang.Double.doubleToRawLongBits(value))

    fun text(value: String) = text(value, MAX_MEMENTO_TEXT_BYTES)

    fun text(value: String, maximumTextBytes: Int) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= maximumTextBytes) { "Agent memento text is too large." }
        int(encoded.size)
        output.write(encoded)
        requireSize()
    }

    fun bytes(value: ByteArray) = bytes(value, MAX_MEMENTO_VALUE_BYTES)

    fun bytes(value: ByteArray, maximumValueBytes: Int) {
        require(value.size <= maximumValueBytes) { "Agent memento byte value is too large." }
        int(value.size)
        output.write(value)
        requireSize()
    }

    fun <T> nullable(value: T?, writer: (T) -> Unit) {
        boolean(value != null)
        if (value != null) nested { writer(value) }
    }

    fun <T> list(values: Collection<T>, maximumItems: Int = MAX_MEMENTO_ITEMS, writer: (T) -> Unit) {
        require(values.size <= maximumItems) { "Agent memento collection is too large." }
        int(values.size)
        nested { values.forEach(writer) }
    }

    fun finish(): ByteArray {
        output.flush()
        requireSize()
        return bytes.toByteArray()
    }

    private fun requireSize() {
        require(bytes.size() <= maximumBytes) { "Agent memento exceeds its total size limit." }
    }

    private inline fun nested(action: () -> Unit) {
        require(nestingDepth < MAX_MEMENTO_NESTING) { "Agent memento nesting is too deep." }
        nestingDepth += 1
        try {
            action()
        } finally {
            nestingDepth -= 1
        }
    }
}

internal class AgentMementoReader(
    payload: ByteArray,
    private val maximumBytes: Int,
) : AutoCloseable {
    private val input: DataInputStream
    private var nestingDepth = 0

    init {
        require(payload.size <= maximumBytes) { "Agent memento exceeds its total size limit." }
        input = DataInputStream(ByteArrayInputStream(payload))
    }

    fun byte(): Int = guarded { input.readUnsignedByte() }

    fun boolean(): Boolean = when (val value = byte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Agent memento boolean marker is invalid: $value.")
    }

    fun int(): Int = guarded { input.readInt() }

    fun long(): Long = guarded { input.readLong() }

    fun double(): Double = java.lang.Double.longBitsToDouble(long()).also { value ->
        require(!value.isNaN() && !value.isInfinite()) { "Agent memento floating-point value is invalid." }
    }

    fun text(): String = text(MAX_MEMENTO_TEXT_BYTES)

    fun text(maximumTextBytes: Int): String {
        val encoded = bytes(maximumTextBytes)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded))
                .toString()
                .also { decoded ->
                    require(Arrays.equals(encoded, decoded.toByteArray(StandardCharsets.UTF_8))) {
                        "Agent memento text is not canonical UTF-8."
                    }
                }
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Agent memento text is not valid UTF-8.")
        }
    }

    fun bytes(): ByteArray = bytes(MAX_MEMENTO_VALUE_BYTES)

    fun bytes(maximumValueBytes: Int): ByteArray {
        val size = int()
        return fixedBytes(size, maximumValueBytes)
    }

    fun fixedBytes(size: Int, maximumValueBytes: Int): ByteArray {
        require(size in 0..maximumValueBytes && size <= input.available()) {
            "Agent memento byte length is invalid."
        }
        return ByteArray(size).also { value -> guarded { input.readFully(value) } }
    }

    fun <T> nullable(reader: () -> T): T? = if (boolean()) nested(reader) else null

    fun <T> list(maximumItems: Int = MAX_MEMENTO_ITEMS, reader: () -> T): List<T> {
        val size = int()
        require(size in 0..maximumItems) { "Agent memento collection length is invalid." }
        return nested {
            ArrayList<T>(size).also { values -> repeat(size) { values += reader() } }
        }
    }

    fun end() {
        require(input.available() == 0) { "Agent memento contains trailing bytes." }
    }

    override fun close() = input.close()

    private inline fun <T> guarded(action: () -> T): T = try {
        action()
    } catch (_: EOFException) {
        throw IllegalArgumentException("Agent memento is truncated.")
    }

    private inline fun <T> nested(action: () -> T): T {
        require(nestingDepth < MAX_MEMENTO_NESTING) { "Agent memento nesting is too deep." }
        nestingDepth += 1
        return try {
            action()
        } finally {
            nestingDepth -= 1
        }
    }
}

internal fun encodeAgentMementoFrame(
    magic: ByteArray,
    maximumBytes: Int,
    body: AgentMementoWriter.() -> Unit,
): ByteArray {
    require(magic.size == AGENT_MEMENTO_MAGIC_BYTES) { "Agent memento magic length is invalid." }
    val bodyBytes = AgentMementoWriter(maximumBytes).apply(body).finish()
    val frame = ByteArrayOutputStream()
    DataOutputStream(frame).use { output ->
        output.write(magic)
        output.writeInt(AGENT_MEMENTO_FORMAT_VERSION)
        output.writeInt(bodyBytes.size)
        output.write(bodyBytes)
    }
    return frame.toByteArray().also { payload ->
        require(payload.size <= maximumBytes) { "Agent memento frame exceeds its total size limit." }
    }
}

internal fun <T> decodeAgentMementoFrame(
    payload: ByteArray,
    expectedMagic: ByteArray,
    maximumBytes: Int,
    body: AgentMementoReader.(Int) -> T,
): T {
    require(payload.size <= maximumBytes) { "Agent memento frame exceeds its total size limit." }
    return AgentMementoReader(payload, maximumBytes).use { frame ->
        val magic = ByteArray(AGENT_MEMENTO_MAGIC_BYTES) { frame.byte().toByte() }
        require(Arrays.equals(magic, expectedMagic)) { "Agent memento magic is invalid." }
        val version = frame.int()
        require(version == AGENT_MEMENTO_FORMAT_VERSION ||
            version == AGENT_MEMENTO_FORMAT_VERSION - 1
        ) {
            "Agent memento format version $version is unsupported."
        }
        val bodyLength = frame.int()
        require(bodyLength >= 0 && bodyLength == payload.size - AGENT_MEMENTO_FRAME_HEADER_BYTES) {
            "Agent memento body length is invalid."
        }
        val bodyBytes = frame.fixedBytes(bodyLength, maximumBytes)
        frame.end()
        AgentMementoReader(bodyBytes, maximumBytes).use { reader -> body(reader, version).also { reader.end() } }
    }
}

private fun boundedMementoBytes(payload: ByteArray, maximumBytes: Int, message: String): ByteArray {
    require(payload.isNotEmpty() && payload.size <= maximumBytes) { message }
    return payload.copyOf()
}

internal const val MAX_MEMENTO_TEXT_BYTES = 1 * 1_024 * 1_024
internal const val MAX_MEMENTO_VALUE_BYTES = 16 * 1_024 * 1_024
internal const val MAX_MEMENTO_ITEMS = 4_096
internal const val MAX_MEMENTO_NESTING = 16
internal const val AGENT_STATE_MEMENTO_MAX_BYTES = AGENT_STATE_MAX_MEMENTO_BYTES
internal const val AGENT_EVENT_MEMENTO_MAX_BYTES = AGENT_EVENT_MAX_MEMENTO_BYTES
internal const val AGENT_MEMENTO_FORMAT_VERSION = 2
private const val AGENT_MEMENTO_MAGIC_BYTES = 8
private const val AGENT_MEMENTO_FRAME_HEADER_BYTES = 16
