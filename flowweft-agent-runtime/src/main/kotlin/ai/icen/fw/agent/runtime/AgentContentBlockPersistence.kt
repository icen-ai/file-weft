package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentBinaryContentBlock
import ai.icen.fw.agent.api.AgentCitation
import ai.icen.fw.agent.api.AgentCitationContentBlock
import ai.icen.fw.agent.api.AgentContentBlock
import ai.icen.fw.agent.api.AgentContentOrigin
import ai.icen.fw.agent.api.AgentSizedContentBlock
import ai.icen.fw.agent.api.AgentTextContentBlock
import ai.icen.fw.agent.api.AgentToolCallContentBlock
import ai.icen.fw.agent.api.AgentToolResultContentBlock
import ai.icen.fw.agent.api.AgentToolResultStatus
import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.core.id.Identifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Collections
import java.util.LinkedHashMap

private const val AGENT_CONTENT_MAX_CODEC_VERSION = 65_535
private const val AGENT_CONTENT_MAX_ENCODED_BYTES = 16 * 1_024 * 1_024

/**
 * Explicit codec for one open Agent content kind. Implementations are selected only by an exact
 * `(kind, codecVersion)` pair and receive bounded canonical bytes, never a class name or JDBC handle.
 */
interface AgentContentBlockPersistenceCodec {
    fun kind(): String

    fun codecVersion(): Int

    fun encode(block: AgentContentBlock): ByteArray

    fun decode(origin: AgentContentOrigin, payload: ByteArray): AgentContentBlock
}

/**
 * Provider-neutral, immutable envelope used by durable state codecs. [payloadDigest] independently
 * detects codec-byte corruption even when a faulty extension omits payload fields from its binding.
 */
class AgentEncodedContentBlock private constructor(
    kind: String,
    val origin: AgentContentOrigin,
    val codecVersion: Int,
    bindingDigest: String,
    payload: ByteArray,
    val canonicalPayloadSizeBytes: Long?,
    payloadDigest: String? = null,
) {
    @JvmOverloads
    constructor(
        kind: String,
        origin: AgentContentOrigin,
        codecVersion: Int,
        bindingDigest: String,
        payload: ByteArray,
        canonicalPayloadSizeBytes: Long? = null,
    ) : this(kind, origin, codecVersion, bindingDigest, payload, canonicalPayloadSizeBytes, null)

    val kind: String = requireRuntimeCode(kind, "Encoded Agent content kind is invalid.")
    val bindingDigest: String = requireRuntimeDigest(bindingDigest, "Encoded Agent content digest is invalid.")
    private val payloadSnapshot: ByteArray = payload.copyOf()
    val payloadDigest: String = payloadDigest?.let { digest ->
        requireRuntimeDigest(digest, "Encoded Agent content payload digest is invalid.")
    } ?: runtimeSha256(payloadSnapshot)

    val payload: ByteArray
        get() = payloadSnapshot.copyOf()

    init {
        require(codecVersion in 1..AGENT_CONTENT_MAX_CODEC_VERSION) {
            "Encoded Agent content codec version is invalid."
        }
        require(payloadSnapshot.size <= AGENT_CONTENT_MAX_ENCODED_BYTES) {
            "Encoded Agent content payload is too large."
        }
        require(canonicalPayloadSizeBytes == null || canonicalPayloadSizeBytes >= 0L) {
            "Encoded Agent canonical content size is invalid."
        }
        require(runtimeSha256(payloadSnapshot) == this.payloadDigest) {
            "Encoded Agent content payload digest does not match its bytes."
        }
    }

    override fun toString(): String = "AgentEncodedContentBlock(kind=$kind, codecVersion=$codecVersion)"

    companion object {
        /** Reconstitutes an envelope only when its separately stored payload digest still matches. */
        @JvmStatic
        fun restore(
            kind: String,
            origin: AgentContentOrigin,
            codecVersion: Int,
            bindingDigest: String,
            payload: ByteArray,
            canonicalPayloadSizeBytes: Long?,
            payloadDigest: String,
        ): AgentEncodedContentBlock = AgentEncodedContentBlock(
            kind,
            origin,
            codecVersion,
            bindingDigest,
            payload,
            canonicalPayloadSizeBytes,
            payloadDigest,
        )
    }
}

/**
 * Fail-closed registry for built-in and host extension content. Every encode is immediately decoded
 * and rebound, making a non-deterministic or lossy extension codec fail before a run is persisted.
 * All exact registered extension versions remain readable; writes use the greatest registered
 * version for that kind so adding a reader never silently selects an older wire format.
 */
class AgentContentBlockPersistenceRegistry @JvmOverloads constructor(
    extensionCodecs: Collection<AgentContentBlockPersistenceCodec> = emptyList(),
) {
    private val extensionCodecs: Map<CodecKey, AgentContentBlockPersistenceCodec>

    init {
        require(extensionCodecs.size <= MAX_EXTENSION_CODECS) { "Too many Agent content persistence codecs." }
        val registered = LinkedHashMap<CodecKey, AgentContentBlockPersistenceCodec>()
        extensionCodecs.forEach { codec ->
            val kind = requireRuntimeCode(codec.kind(), "Agent content persistence codec kind is invalid.")
            require(kind !in RESERVED_KINDS) { "Built-in Agent content kinds cannot be replaced." }
            val version = codec.codecVersion()
            require(version in 1..AGENT_CONTENT_MAX_CODEC_VERSION) {
                "Agent content persistence codec version is invalid."
            }
            require(registered.put(CodecKey(kind, version), codec) == null) {
                "Agent content persistence codec kind and version must be unique."
            }
        }
        this.extensionCodecs = Collections.unmodifiableMap(registered)
    }

    fun encode(block: AgentContentBlock): AgentEncodedContentBlock {
        val kind = requireRuntimeCode(block.kind(), "Agent content kind is invalid for persistence.")
        val origin = block.origin()
        val bindingDigest = requireRuntimeDigest(
            block.bindingDigest(),
            "Agent content binding digest is invalid for persistence.",
        )
        val canonicalSize = (block as? AgentSizedContentBlock)?.canonicalPayloadSizeBytes()?.also { size ->
            require(size >= 0L) { "Agent canonical content size is invalid for persistence." }
        }
        val version = if (kind in RESERVED_KINDS) BUILTIN_CODEC_VERSION else {
            val candidates = extensionCodecs.keys.filter { key -> key.kind == kind }
            require(candidates.isNotEmpty()) {
                "Agent extension content requires a registered persistence codec when writing."
            }
            // Version ordering is part of the persistence contract: all registered versions remain
            // readable, while a writer emits only the greatest explicitly registered version.
            candidates.maxOf { candidate -> candidate.version }
        }
        val payload = encodePayload(block, kind, version)
        val encoded = AgentEncodedContentBlock(kind, origin, version, bindingDigest, payload, canonicalSize)
        val rebound = decode(encoded)
        require(rebound.kind() == kind && rebound.origin() == origin && rebound.bindingDigest() == bindingDigest) {
            "Agent content persistence codec changed its security binding."
        }
        val reboundSize = (rebound as? AgentSizedContentBlock)?.canonicalPayloadSizeBytes()
        require(reboundSize == canonicalSize) { "Agent content persistence codec changed its canonical size." }
        val canonicalPayload = encodePayload(rebound, kind, version)
        require(Arrays.equals(payload, canonicalPayload)) { "Agent content persistence codec is not deterministic." }
        return encoded
    }

    fun decode(encoded: AgentEncodedContentBlock): AgentContentBlock {
        val payload = encoded.payload
        val block = when (encoded.kind) {
            AgentTextContentBlock.KIND -> decodeText(encoded.origin, encoded.codecVersion, payload)
            AgentBinaryContentBlock.KIND -> decodeBinary(encoded.origin, encoded.codecVersion, payload)
            AgentToolCallContentBlock.KIND -> decodeToolCall(encoded.origin, encoded.codecVersion, payload)
            AgentToolResultContentBlock.KIND -> decodeToolResult(encoded.origin, encoded.codecVersion, payload)
            AgentCitationContentBlock.KIND -> decodeCitation(encoded.origin, encoded.codecVersion, payload)
            else -> decodeExtension(encoded.kind, encoded.codecVersion, encoded.origin, payload)
        }
        require(block.kind() == encoded.kind && block.origin() == encoded.origin) {
            "Decoded Agent content kind or origin does not match its envelope."
        }
        require(block.bindingDigest() == encoded.bindingDigest) {
            "Decoded Agent content binding digest does not match its envelope."
        }
        val decodedSize = (block as? AgentSizedContentBlock)?.canonicalPayloadSizeBytes()
        require(decodedSize == encoded.canonicalPayloadSizeBytes) {
            "Decoded Agent canonical content size does not match its envelope."
        }
        return block
    }

    private fun encodePayload(block: AgentContentBlock, kind: String, version: Int): ByteArray = when (kind) {
        AgentTextContentBlock.KIND -> encodeText(requireType<AgentTextContentBlock>(block, kind), version)
        AgentBinaryContentBlock.KIND -> encodeBinary(requireType<AgentBinaryContentBlock>(block, kind), version)
        AgentToolCallContentBlock.KIND -> encodeToolCall(requireType<AgentToolCallContentBlock>(block, kind), version)
        AgentToolResultContentBlock.KIND -> encodeToolResult(
            requireType<AgentToolResultContentBlock>(block, kind),
            version,
        )
        AgentCitationContentBlock.KIND -> encodeCitation(
            requireType<AgentCitationContentBlock>(block, kind),
            version,
        )
        else -> encodeExtension(kind, version, block)
    }

    private fun encodeText(block: AgentTextContentBlock, version: Int): ByteArray =
        writer(version) { text(block.text) }

    private fun decodeText(origin: AgentContentOrigin, version: Int, payload: ByteArray): AgentContentBlock =
        reader(version, payload) { AgentTextContentBlock(origin, text()) }

    private fun encodeBinary(block: AgentBinaryContentBlock, version: Int): ByteArray = writer(version) {
        text(block.mediaType)
        text(block.digest)
        bytes(block.data)
    }

    private fun decodeBinary(origin: AgentContentOrigin, version: Int, payload: ByteArray): AgentContentBlock =
        reader(version, payload) {
            val mediaType = text()
            val digest = text()
            val data = bytes()
            AgentBinaryContentBlock(origin, mediaType, data, digest)
        }

    private fun encodeToolCall(block: AgentToolCallContentBlock, version: Int): ByteArray = writer(version) {
        text(block.callId)
        text(block.toolId.value)
        text(block.schemaDigest)
        text(block.argumentsDigest)
        bytes(block.arguments)
    }

    private fun decodeToolCall(origin: AgentContentOrigin, version: Int, payload: ByteArray): AgentContentBlock {
        require(origin == AgentContentOrigin.MODEL) { "Persisted Agent tool calls require MODEL origin." }
        return reader(version, payload) {
            val callId = text()
            val toolId = ToolId(text())
            val schemaDigest = text()
            val argumentsDigest = text()
            AgentToolCallContentBlock(callId, toolId, schemaDigest, bytes(), argumentsDigest)
        }
    }

    private fun encodeCitation(block: AgentCitationContentBlock, version: Int): ByteArray = writer(version) {
        val citation = block.citation
        text(citation.citationId.value)
        text(citation.tenantId.value)
        text(citation.documentId.value)
        text(citation.documentVersionId.value)
        text(citation.evidenceId.value)
        text(citation.contentDigest)
        nullableLong(citation.startOffset)
        nullableLong(citation.endOffset)
        nullableInt(citation.pageNumber)
    }

    private fun decodeCitation(origin: AgentContentOrigin, version: Int, payload: ByteArray): AgentContentBlock {
        require(origin == AgentContentOrigin.RETRIEVAL) { "Persisted Agent citations require RETRIEVAL origin." }
        return reader(version, payload) {
            AgentCitationContentBlock(
                AgentCitation(
                    Identifier(text()),
                    Identifier(text()),
                    Identifier(text()),
                    Identifier(text()),
                    Identifier(text()),
                    text(),
                    nullableLong(),
                    nullableLong(),
                    nullableInt(),
                ),
            )
        }
    }

    private fun encodeToolResult(block: AgentToolResultContentBlock, version: Int): ByteArray = writer(version) {
        block.requireBindingIntact()
        text(block.callId)
        text(block.toolId.value)
        text(block.status.name)
        nullableText(block.safeErrorCode)
        list(block.blocks) { nested -> encoded(encode(nested)) }
    }

    private fun decodeToolResult(origin: AgentContentOrigin, version: Int, payload: ByteArray): AgentContentBlock {
        require(origin == AgentContentOrigin.TOOL) { "Persisted Agent tool results require TOOL origin." }
        return reader(version, payload) {
            val callId = text()
            val toolId = ToolId(text())
            val status = AgentToolResultStatus.valueOf(text())
            val safeErrorCode = nullableText()
            val blocks = list {
                val nested = encoded()
                require(
                    nested.kind != AgentToolCallContentBlock.KIND &&
                        nested.kind != AgentToolResultContentBlock.KIND,
                ) { "Persisted Agent tool results cannot nest execution-control blocks." }
                decode(nested)
            }
            AgentToolResultContentBlock(callId, toolId, status, blocks, safeErrorCode)
        }
    }

    private fun extensionCodec(kind: String, version: Int): AgentContentBlockPersistenceCodec {
        val codec = extensionCodecs[CodecKey(kind, version)]
            ?: throw IllegalArgumentException("No Agent content persistence codec is registered for '$kind' v$version.")
        require(codec.kind() == kind && codec.codecVersion() == version) {
            "Agent content persistence codec registration changed after construction."
        }
        return codec
    }

    private fun encodeExtension(kind: String, version: Int, block: AgentContentBlock): ByteArray {
        val payload = try {
            extensionCodec(kind, version).encode(block).copyOf()
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("Agent extension content codec failed while encoding.")
        }
        require(payload.size <= AGENT_CONTENT_MAX_ENCODED_BYTES) {
            "Encoded Agent extension content is too large."
        }
        return payload
    }

    private fun decodeExtension(
        kind: String,
        version: Int,
        origin: AgentContentOrigin,
        payload: ByteArray,
    ): AgentContentBlock = try {
        extensionCodec(kind, version).decode(origin, payload.copyOf())
    } catch (_: RuntimeException) {
        throw IllegalArgumentException("Agent extension content codec failed while decoding.")
    }

    private inline fun <reified T : AgentContentBlock> requireType(block: AgentContentBlock, kind: String): T =
        block as? T ?: throw IllegalArgumentException("Agent content uses reserved kind '$kind' with a non-canonical type.")

    private fun writer(version: Int, action: PayloadWriter.() -> Unit): ByteArray {
        require(version == BUILTIN_CODEC_VERSION) { "Built-in Agent content codec version is unsupported." }
        return PayloadWriter().apply(action).finish()
    }

    private fun <T : AgentContentBlock> reader(
        version: Int,
        payload: ByteArray,
        action: PayloadReader.() -> T,
    ): T {
        require(version == BUILTIN_CODEC_VERSION) { "Built-in Agent content codec version is unsupported." }
        return PayloadReader(payload).use { input -> action(input).also { input.end() } }
    }

    private data class CodecKey(val kind: String, val version: Int)

    private inner class PayloadWriter {
        private val bytes = ByteArrayOutputStream()
        private val output = DataOutputStream(bytes)

        fun text(value: String) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            require(encoded.size <= MAX_TEXT_BYTES) { "Agent content persistence text is too large." }
            output.writeInt(encoded.size)
            output.write(encoded)
        }

        fun nullableText(value: String?) = nullable(value, ::text)

        fun bytes(value: ByteArray) {
            require(value.size <= AGENT_CONTENT_MAX_ENCODED_BYTES) {
                "Agent content persistence bytes are too large."
            }
            output.writeInt(value.size)
            output.write(value)
        }

        fun nullableLong(value: Long?) = nullable(value, output::writeLong)

        fun nullableInt(value: Int?) = nullable(value, output::writeInt)

        fun <T> nullable(value: T?, writer: (T) -> Unit) {
            output.writeBoolean(value != null)
            if (value != null) writer(value)
        }

        fun <T> list(values: Collection<T>, writer: (T) -> Unit) {
            require(values.size <= MAX_RUNTIME_ITEMS) { "Agent content persistence collection is too large." }
            output.writeInt(values.size)
            values.forEach(writer)
        }

        fun encoded(value: AgentEncodedContentBlock) {
            text(value.kind)
            text(value.origin.name)
            output.writeInt(value.codecVersion)
            text(value.bindingDigest)
            text(value.payloadDigest)
            nullableLong(value.canonicalPayloadSizeBytes)
            bytes(value.payload)
        }

        fun finish(): ByteArray {
            output.flush()
            return bytes.toByteArray().also { payload ->
                require(payload.size <= AGENT_CONTENT_MAX_ENCODED_BYTES) {
                    "Encoded Agent content payload is too large."
                }
            }
        }
    }

    private inner class PayloadReader(payload: ByteArray) : AutoCloseable {
        private val input: DataInputStream

        init {
            require(payload.size <= AGENT_CONTENT_MAX_ENCODED_BYTES) {
                "Encoded Agent content payload is too large."
            }
            input = DataInputStream(ByteArrayInputStream(payload))
        }

        fun text(): String {
            val size = input.readInt()
            require(size in 0..MAX_TEXT_BYTES && size <= input.available()) {
                "Encoded Agent content text length is invalid."
            }
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return String(bytes, StandardCharsets.UTF_8).also { value ->
                require(Arrays.equals(bytes, value.toByteArray(StandardCharsets.UTF_8))) {
                    "Encoded Agent content text is not canonical UTF-8."
                }
            }
        }

        fun nullableText(): String? = nullable { text() }

        fun bytes(): ByteArray {
            val size = input.readInt()
            require(size in 0..AGENT_CONTENT_MAX_ENCODED_BYTES && size <= input.available()) {
                "Encoded Agent content byte length is invalid."
            }
            val result = ByteArray(size)
            input.readFully(result)
            return result
        }

        fun nullableLong(): Long? = nullable { input.readLong() }

        fun nullableInt(): Int? = nullable { input.readInt() }

        fun <T> nullable(reader: () -> T): T? = if (input.readBoolean()) reader() else null

        fun <T> list(reader: PayloadReader.() -> T): List<T> {
            val size = input.readInt()
            require(size in 0..MAX_RUNTIME_ITEMS) { "Encoded Agent content collection length is invalid." }
            return ArrayList<T>(size).also { values -> repeat(size) { values += reader(this) } }
        }

        fun encoded(): AgentEncodedContentBlock {
            val kind = text()
            val origin = AgentContentOrigin.valueOf(text())
            val version = input.readInt()
            val bindingDigest = text()
            val payloadDigest = text()
            val canonicalSize = nullableLong()
            val payload = bytes()
            return AgentEncodedContentBlock.restore(
                kind,
                origin,
                version,
                bindingDigest,
                payload,
                canonicalSize,
                payloadDigest,
            )
        }

        fun end() {
            require(input.available() == 0) { "Encoded Agent content contains trailing data." }
        }

        override fun close() = input.close()
    }

    private companion object {
        const val BUILTIN_CODEC_VERSION = 1
        const val MAX_EXTENSION_CODECS = 256
        const val MAX_TEXT_BYTES = 1 * 1_024 * 1_024
        val RESERVED_KINDS = setOf(
            AgentTextContentBlock.KIND,
            AgentBinaryContentBlock.KIND,
            AgentToolCallContentBlock.KIND,
            AgentToolResultContentBlock.KIND,
            AgentCitationContentBlock.KIND,
        )
    }
}
