package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets

/** Fixed conversational roles understood by the runtime and model adapters. */
enum class AgentMessageRole {
    SYSTEM,
    DEVELOPER,
    USER,
    ASSISTANT,
    TOOL,
    CONTEXT,
}

/** Origin is explicit so untrusted retrieval/tool/A2A data is never promoted to instructions. */
enum class AgentContentOrigin {
    SYSTEM,
    DEVELOPER,
    USER,
    RETRIEVAL,
    MODEL,
    TOOL,
    A2A,
    MEMORY,
}

/**
 * Open content-block boundary. It is deliberately not sealed so adapters can add structured blocks.
 * Implementations must return a stable, bounded kind, remain immutable, and expose a SHA-256
 * [bindingDigest] over every security-relevant field and the canonical payload. Returning a digest
 * that omits payload or metadata breaks request idempotency and is a provider contract violation.
 */
interface AgentContentBlock {
    fun kind(): String

    fun origin(): AgentContentOrigin

    fun bindingDigest(): String
}

/**
 * Optional exact-size contract for blocks that may cross a provider result boundary. Agent input
 * extensions can remain open, but tool results are accepted by the durable runtime only when every
 * block implements this contract; otherwise a provider could bypass its declared result-byte cap.
 */
interface AgentSizedContentBlock : AgentContentBlock {
    fun canonicalPayloadSizeBytes(): Long
}

class AgentTextContentBlock(
    private val origin: AgentContentOrigin,
    text: String,
) : AgentSizedContentBlock {
    val text: String = requireAgentContent(
        text,
        AgentContractLimits.MAX_CONTENT_CODE_POINTS,
        "Agent text content is invalid.",
    )

    override fun kind(): String = KIND

    override fun origin(): AgentContentOrigin = origin

    override fun bindingDigest(): String = AgentDigestBuilder("flowweft.agent.content.text.v1")
        .add(KIND)
        .add(origin.name)
        .add(text)
        .finish()

    override fun canonicalPayloadSizeBytes(): Long = text.toByteArray(StandardCharsets.UTF_8).size.toLong()

    companion object {
        const val KIND: String = "text"
    }
}

/** Immutable binary block. Credentials and provider secrets must never be represented as content. */
class AgentBinaryContentBlock(
    private val origin: AgentContentOrigin,
    mediaType: String,
    data: ByteArray,
    digest: String,
) : AgentSizedContentBlock {
    val mediaType: String = requireMediaType(mediaType, "Agent binary media type is invalid.")
    val digest: String = requireSha256(digest, "Agent binary content digest is invalid.")
    private val dataSnapshot: ByteArray

    val data: ByteArray
        get() = dataSnapshot.copyOf()

    init {
        val snapshot = immutableAgentBytes(data)
        require(snapshot.isNotEmpty()) { "Agent binary content must not be empty." }
        require(snapshot.size <= AgentContractLimits.MAX_BINARY_BYTES) { "Agent binary content is too large." }
        requireDigestMatches(snapshot, this.digest, "Agent binary content digest does not match its bytes.")
        dataSnapshot = snapshot
    }

    override fun kind(): String = KIND

    override fun origin(): AgentContentOrigin = origin

    override fun bindingDigest(): String = AgentDigestBuilder("flowweft.agent.content.binary.v1")
        .add(KIND)
        .add(origin.name)
        .add(mediaType)
        .add(dataSnapshot.size)
        .add(digest)
        .finish()

    override fun canonicalPayloadSizeBytes(): Long = dataSnapshot.size.toLong()

    companion object {
        const val KIND: String = "binary"
    }
}

/** Canonical, non-secret tool arguments proposed by a model. */
class AgentToolCallContentBlock(
    callId: String,
    val toolId: ToolId,
    schemaDigest: String,
    arguments: ByteArray,
    argumentsDigest: String,
) : AgentSizedContentBlock {
    val callId: String = requireStableAgentId(callId, "Agent tool call identifier is invalid.")
    val schemaDigest: String = requireSha256(schemaDigest, "Agent tool call schema digest is invalid.")
    val argumentsDigest: String = requireSha256(argumentsDigest, "Agent tool call arguments digest is invalid.")
    private val argumentsSnapshot: ByteArray

    val arguments: ByteArray
        get() = argumentsSnapshot.copyOf()

    init {
        val snapshot = immutableAgentBytes(arguments)
        require(snapshot.isNotEmpty()) { "Agent tool call arguments must not be empty." }
        require(snapshot.size <= AgentContractLimits.MAX_ARGUMENT_BYTES) { "Agent tool call arguments are too large." }
        requireUtf8(snapshot, "Agent tool call arguments must be valid UTF-8 canonical JSON.")
        requireDigestMatches(
            snapshot,
            this.argumentsDigest,
            "Agent tool call arguments digest does not match its canonical bytes.",
        )
        argumentsSnapshot = snapshot
    }

    override fun kind(): String = KIND

    override fun origin(): AgentContentOrigin = AgentContentOrigin.MODEL

    override fun bindingDigest(): String = AgentDigestBuilder("flowweft.agent.content.tool-call.v1")
        .add(KIND)
        .add(origin().name)
        .add(callId)
        .add(toolId.value)
        .add(schemaDigest)
        .add(argumentsSnapshot.size)
        .add(argumentsDigest)
        .finish()

    override fun canonicalPayloadSizeBytes(): Long = argumentsSnapshot.size.toLong()

    companion object {
        const val KIND: String = "tool-call"
    }
}

class AgentMessage(
    id: Identifier,
    val role: AgentMessageRole,
    blocks: List<AgentContentBlock>,
    val createdAt: Long,
) {
    val id: Identifier = requireOpaqueIdentifier(id, "Agent message identifier is invalid.")
    val blocks: List<AgentContentBlock>
    val bindingDigest: String
    private val blockBindings: List<AgentContentBlockBinding>

    init {
        val blockSnapshot = immutableAgentList(blocks)
        val bindings = blockSnapshot.map { block ->
            requireAgentContentBlockContract(block)
            AgentContentBlockBinding(
                block.kind(),
                block.origin(),
                requireSha256(block.bindingDigest(), "Agent content block binding digest is invalid."),
            )
        }
        requireNonNegativeTime(createdAt, "Agent message creation time must not be negative.")
        require(blockSnapshot.isNotEmpty()) { "Agent message must contain at least one content block." }
        require(blockSnapshot.size <= AgentContractLimits.MAX_BLOCKS_PER_MESSAGE) {
            "Agent message contains too many content blocks."
        }
        val permittedOrigins = when (role) {
            AgentMessageRole.SYSTEM -> setOf(AgentContentOrigin.SYSTEM)
            AgentMessageRole.DEVELOPER -> setOf(AgentContentOrigin.DEVELOPER)
            AgentMessageRole.USER -> setOf(AgentContentOrigin.USER)
            AgentMessageRole.ASSISTANT -> setOf(AgentContentOrigin.MODEL, AgentContentOrigin.A2A)
            AgentMessageRole.TOOL -> setOf(AgentContentOrigin.TOOL)
            AgentMessageRole.CONTEXT -> setOf(
                AgentContentOrigin.RETRIEVAL,
                AgentContentOrigin.A2A,
                AgentContentOrigin.MEMORY,
            )
        }
        require(blockSnapshot.all { block -> block.origin() in permittedOrigins }) {
            "Agent message content origin is not valid for its role."
        }
        this.blocks = blockSnapshot
        blockBindings = immutableAgentList(bindings)
        val digest = AgentDigestBuilder("flowweft.agent.message.v1")
            .add(this.id.value)
            .add(role.name)
            .add(createdAt)
            .add(blockSnapshot.size)
        blockSnapshot.forEachIndexed { index, block ->
            val binding = blockBindings[index]
            digest.add(binding.kind).add(binding.origin.name).add(binding.digest)
        }
        bindingDigest = digest.finish()
    }

    /**
     * Revalidates open extension blocks immediately before a security or provider boundary. This
     * detects mutable or swapped kind/origin/digest implementations instead of trusting the digest
     * captured when the message was created.
     */
    fun requireBindingIntact() {
        require(blocks.size == blockBindings.size) { "Agent message content binding changed." }
        blocks.forEachIndexed { index, block ->
            requireAgentContentBlockContract(block)
            val expected = blockBindings[index]
            require(
                block.kind() == expected.kind &&
                    block.origin() == expected.origin &&
                    block.bindingDigest() == expected.digest,
            ) { "Agent message content binding changed." }
            if (block is AgentToolResultContentBlock) block.requireBindingIntact()
        }
    }
}

private class AgentContentBlockBinding(
    val kind: String,
    val origin: AgentContentOrigin,
    val digest: String,
)

/** Prevent open extensions from spoofing the security semantics of a reserved built-in kind. */
internal fun requireAgentContentBlockContract(block: AgentContentBlock) {
    val kind = requireStableAgentId(block.kind(), "Agent content block kind is invalid.")
    requireSha256(block.bindingDigest(), "Agent content block binding digest is invalid.")
    val validReservedImplementation = when (kind) {
        AgentTextContentBlock.KIND -> block is AgentTextContentBlock
        AgentBinaryContentBlock.KIND -> block is AgentBinaryContentBlock
        AgentToolCallContentBlock.KIND -> block is AgentToolCallContentBlock
        AgentToolResultContentBlock.KIND -> block is AgentToolResultContentBlock
        AgentCitationContentBlock.KIND -> block is AgentCitationContentBlock
        else -> true
    }
    require(validReservedImplementation) { "Agent content block uses a reserved kind without its canonical type." }
}
