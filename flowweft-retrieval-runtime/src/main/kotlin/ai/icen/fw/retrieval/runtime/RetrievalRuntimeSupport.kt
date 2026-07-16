package ai.icen.fw.retrieval.runtime

import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal const val MAX_RUNTIME_CANDIDATES: Int = 1_000
internal const val MAX_RUNTIME_CONTENT_CODE_POINTS: Int = 1_000_000

internal fun requireRuntimeText(value: String, maximumCodePoints: Int, message: String): String {
    require(value.isNotEmpty() && value.codePointCount(0, value.length) <= maximumCodePoints) { message }
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        require(codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) { message }
        require(!Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT.toInt()) {
            message
        }
        offset += Character.charCount(codePoint)
    }
    return value
}

internal fun requireRuntimeDigest(value: String, message: String): String {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { message }
    return value
}

internal fun requireRuntimeIdentifier(identifier: Identifier, message: String): Identifier {
    requireRuntimeText(identifier.value, 256, message)
    return identifier
}

internal fun <T> immutableRuntimeList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

internal class RetrievalRuntimeDigest(tag: String) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val number = ByteArray(8)
    private var complete = false

    init {
        text(tag)
    }

    fun text(value: String): RetrievalRuntimeDigest = apply {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        integer(bytes.size)
        update(bytes)
    }

    fun optionalText(value: String?): RetrievalRuntimeDigest = apply {
        bool(value != null)
        value?.let(::text)
    }

    fun integer(value: Int): RetrievalRuntimeDigest = apply {
        number[0] = (value ushr 24).toByte()
        number[1] = (value ushr 16).toByte()
        number[2] = (value ushr 8).toByte()
        number[3] = value.toByte()
        update(number, 4)
    }

    fun long(value: Long): RetrievalRuntimeDigest = apply {
        for (index in 0 until 8) number[index] = (value ushr (56 - index * 8)).toByte()
        update(number, 8)
    }

    fun bool(value: Boolean): RetrievalRuntimeDigest = apply {
        number[0] = if (value) 1 else 0
        update(number, 1)
    }

    fun floating(value: Double): RetrievalRuntimeDigest = long(java.lang.Double.doubleToRawLongBits(value))

    fun finish(): String {
        check(!complete) { "Runtime digest is already complete." }
        complete = true
        val bytes = digest.digest()
        val alphabet = "0123456789abcdef"
        val output = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xff
            output[index * 2] = alphabet[unsigned ushr 4]
            output[index * 2 + 1] = alphabet[unsigned and 0x0f]
        }
        return String(output)
    }

    private fun update(bytes: ByteArray, length: Int = bytes.size) {
        check(!complete) { "Runtime digest is already complete." }
        digest.update(bytes, 0, length)
    }
}

/** Stable purpose passed to host-provided identifier generators. */
class RetrievalRuntimeIdPurpose private constructor(val id: String) {
    init {
        requireRuntimeText(id, 64, "Retrieval runtime identifier purpose is invalid.")
        require(Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$").matches(id)) {
            "Retrieval runtime identifier purpose is invalid."
        }
    }

    override fun equals(other: Any?): Boolean = other is RetrievalRuntimeIdPurpose && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        @JvmField val ATTEMPT = RetrievalRuntimeIdPurpose("attempt")
        @JvmField val LINEAGE = RetrievalRuntimeIdPurpose("lineage")
        @JvmField val CANDIDATE_AUTHORIZATION_BATCH = RetrievalRuntimeIdPurpose("candidate-authorization-batch")
        @JvmField val CANDIDATE_AUTHORIZATION = RetrievalRuntimeIdPurpose("candidate-authorization")
        @JvmField val DELETION_VISIBILITY = RetrievalRuntimeIdPurpose("deletion-visibility")
        @JvmField val CONTENT_EGRESS_DECISION = RetrievalRuntimeIdPurpose("content-egress-decision")
        @JvmField val HYDRATION = RetrievalRuntimeIdPurpose("hydration")
        @JvmField val RERANK = RetrievalRuntimeIdPurpose("rerank")

        @JvmStatic
        fun of(id: String): RetrievalRuntimeIdPurpose = RetrievalRuntimeIdPurpose(id)
    }
}

/** Host-controlled identifier source. Implementations must return a fresh identifier for every call. */
fun interface RetrievalRuntimeIdGenerator {
    fun nextId(purpose: RetrievalRuntimeIdPurpose): Identifier
}

/** Default stateless identifier generator suitable for multi-node runtimes. */
class UuidRetrievalRuntimeIdGenerator @JvmOverloads constructor(prefix: String = "retrieval") :
    RetrievalRuntimeIdGenerator {
    private val prefix: String = requireRuntimeText(prefix, 64, "Retrieval identifier prefix is invalid.").also {
        require(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$").matches(it)) {
            "Retrieval identifier prefix is invalid."
        }
    }

    override fun nextId(purpose: RetrievalRuntimeIdPurpose): Identifier =
        Identifier("$prefix-${purpose.id}-${UUID.randomUUID()}")
}

internal fun interface RetrievalRuntimeScheduledTask {
    fun cancel()
}

internal fun interface RetrievalRuntimeDeadlineScheduler {
    fun schedule(delayMillis: Long, action: Runnable): RetrievalRuntimeScheduledTask
}

internal class ExecutorRetrievalRuntimeDeadlineScheduler(
    private val executor: ScheduledExecutorService,
) : RetrievalRuntimeDeadlineScheduler {
    override fun schedule(delayMillis: Long, action: Runnable): RetrievalRuntimeScheduledTask {
        require(delayMillis >= 0L) { "Retrieval deadline delay must not be negative." }
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return RetrievalRuntimeScheduledTask { future.cancel(false) }
    }
}
