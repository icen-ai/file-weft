package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier
import java.util.concurrent.CompletionStage

/** Immutable capabilities and hard limits for one configured provider/model pair. */
class LanguageModelDescriptor @JvmOverloads constructor(
    val providerId: ProviderId,
    val modelId: ModelId,
    displayName: String,
    capabilities: Collection<AgentCapabilityId>,
    val maximumInputTokens: Long,
    val maximumOutputTokens: Long,
    val supportsStreaming: Boolean,
    val supportsTools: Boolean,
    val maximumCostMicros: Long = 1_000_000_000L,
    val maximumDurationMillis: Long = 60_000L,
) {
    val displayName: String = requireAgentToken(
        displayName,
        AgentContractLimits.MAX_NAME_CODE_POINTS,
        "Language model display name is invalid.",
    )
    val capabilities: Set<AgentCapabilityId>
    val descriptorDigest: String

    init {
        val capabilitySnapshot = immutableAgentList(capabilities)
        require(capabilitySnapshot.size <= AgentContractLimits.MAX_CAPABILITIES) {
            "Language model declares too many capabilities."
        }
        require(capabilitySnapshot.toSet().size == capabilitySnapshot.size) {
            "Language model capability identifiers must be unique."
        }
        require(maximumInputTokens > 0) { "Language model input limit must be positive." }
        require(maximumOutputTokens > 0) { "Language model output limit must be positive." }
        require(maximumCostMicros >= 0L) { "Language model cost reservation must not be negative." }
        require(maximumDurationMillis in 1L..86_400_000L) {
            "Language model duration reservation is invalid."
        }
        this.capabilities = immutableAgentSet(capabilitySnapshot)
        val digest = AgentDigestBuilder("flowweft.agent.model-descriptor.v1")
            .add(providerId.value)
            .add(modelId.value)
            .add(this.displayName)
            .add(maximumInputTokens)
            .add(maximumOutputTokens)
            .add(supportsStreaming)
            .add(supportsTools)
            .add(maximumCostMicros)
            .add(maximumDurationMillis)
            .add(this.capabilities.size)
        this.capabilities.map { capability -> capability.value }.sorted().forEach(digest::add)
        descriptorDigest = digest.finish()
    }
}

/** Provider-neutral model request. Provider credentials are configured out of band and never appear here. */
class LanguageModelRequest @JvmOverloads constructor(
    requestId: Identifier,
    tenantId: Identifier,
    val providerId: ProviderId,
    val modelId: ModelId,
    messages: List<AgentMessage>,
    tools: List<AgentToolDescriptor>,
    val maximumOutputTokens: Long,
    val requestedAt: Long,
    val deadlineAt: Long,
    val cancellationToken: AgentCancellationToken,
    val temperature: Double = 0.0,
    val maximumInputTokens: Long = Long.MAX_VALUE,
    val maximumCostMicros: Long = 0L,
) {
    val requestId: Identifier = requireOpaqueIdentifier(requestId, "Language model request identifier is invalid.")
    val tenantId: Identifier = requireOpaqueIdentifier(tenantId, "Language model tenant identifier is invalid.")
    val messages: List<AgentMessage>
    val tools: List<AgentToolDescriptor>
    val bindingDigest: String

    init {
        val messageSnapshot = immutableAgentList(messages)
        val toolSnapshot = immutableAgentList(tools)
        require(messageSnapshot.isNotEmpty()) { "Language model request requires at least one message." }
        require(messageSnapshot.size <= AgentContractLimits.MAX_MESSAGES) {
            "Language model request contains too many messages."
        }
        require(messageSnapshot.map { message -> message.id }.distinct().size == messageSnapshot.size) {
            "Language model request message identifiers must be unique."
        }
        messageSnapshot.forEach(AgentMessage::requireBindingIntact)
        require(toolSnapshot.size <= AgentContractLimits.MAX_TOOLS) { "Language model request contains too many tools." }
        require(toolSnapshot.map { tool -> tool.toolId }.distinct().size == toolSnapshot.size) {
            "Language model request tool identifiers must be unique."
        }
        require(maximumOutputTokens > 0) { "Language model output token limit must be positive." }
        require(maximumInputTokens > 0) { "Language model input token reservation must be positive." }
        require(maximumCostMicros >= 0L) { "Language model cost reservation must not be negative." }
        requireNonNegativeTime(requestedAt, "Language model request time must not be negative.")
        require(deadlineAt > requestedAt) { "Language model deadline must follow request time." }
        require(!temperature.isNaN() && !temperature.isInfinite() && temperature in 0.0..2.0) {
            "Language model temperature must be finite and between 0 and 2."
        }
        this.messages = messageSnapshot
        this.tools = toolSnapshot
        val digest = AgentDigestBuilder("flowweft.agent.model-request.v1")
            .add(this.requestId.value)
            .add(this.tenantId.value)
            .add(providerId.value)
            .add(modelId.value)
            .add(maximumOutputTokens)
            .add(maximumInputTokens)
            .add(maximumCostMicros)
            .add(requestedAt)
            .add(deadlineAt)
            .add(java.lang.Double.toHexString(temperature))
            .add(messageSnapshot.size)
        messageSnapshot.forEach { message -> digest.add(message.bindingDigest) }
        digest.add(toolSnapshot.size)
        toolSnapshot.forEach { tool -> digest.add(tool.descriptorDigest) }
        bindingDigest = digest.finish()
    }

    fun requireSupportedBy(descriptor: LanguageModelDescriptor) {
        require(providerId == descriptor.providerId && modelId == descriptor.modelId) {
            "Language model request does not match the selected descriptor."
        }
        require(maximumOutputTokens <= descriptor.maximumOutputTokens) {
            "Language model request exceeds the selected model output limit."
        }
        require(maximumInputTokens <= descriptor.maximumInputTokens) {
            "Language model request exceeds the selected model input reservation."
        }
        require(maximumCostMicros <= descriptor.maximumCostMicros) {
            "Language model request exceeds the selected model cost reservation."
        }
        require(deadlineAt - requestedAt <= descriptor.maximumDurationMillis) {
            "Language model request exceeds the selected model duration reservation."
        }
        require(tools.isEmpty() || descriptor.supportsTools) {
            "Language model request supplies tools to a model that does not support them."
        }
    }
}

enum class LanguageModelFinishReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    CONTENT_FILTER,
    CANCELLED,
}

class LanguageModelResponse @JvmOverloads constructor(
    requestId: Identifier,
    val providerId: ProviderId,
    val modelId: ModelId,
    val finishReason: LanguageModelFinishReason,
    val usage: AgentUsage,
    val completedAt: Long,
    val message: AgentMessage? = null,
    providerRequestId: String? = null,
) {
    val requestId: Identifier = requireOpaqueIdentifier(requestId, "Language model response request identifier is invalid.")
    val providerRequestId: String? = providerRequestId?.let {
        requireAgentToken(it, AgentContractLimits.MAX_ID_CODE_POINTS, "Language model provider request identifier is invalid.")
    }
    val bindingDigest: String

    init {
        requireNonNegativeTime(completedAt, "Language model completion time must not be negative.")
        require(message == null || message.role == AgentMessageRole.ASSISTANT) {
            "Language model responses may contain only an assistant message."
        }
        require(
            finishReason == LanguageModelFinishReason.CONTENT_FILTER ||
                finishReason == LanguageModelFinishReason.CANCELLED ||
                message != null,
        ) { "Completed language model responses require an assistant message." }
        message?.requireBindingIntact()
        if (finishReason == LanguageModelFinishReason.TOOL_CALLS) {
            require(message != null && message.blocks.isNotEmpty() &&
                message.blocks.all { block -> block is AgentToolCallContentBlock }
            ) { "Tool-calling language model responses may contain only canonical tool-call blocks." }
        } else {
            require(message == null || message.blocks.none { block -> block is AgentToolCallContentBlock }) {
                "Only TOOL_CALLS language model responses may contain tool-call blocks."
            }
        }
        val digest = AgentDigestBuilder("flowweft.agent.model-response.v1")
            .add(this.requestId.value)
            .add(providerId.value)
            .add(modelId.value)
            .add(finishReason.name)
            .add(usage.inputTokens)
            .add(usage.outputTokens)
            .add(usage.modelCalls)
            .add(usage.toolCalls)
            .add(usage.durationMillis)
            .add(usage.costMicros)
            .add(usage.additionalUnits.size)
        usage.additionalUnits.toSortedMap().forEach { (name, value) -> digest.add(name).add(value) }
        bindingDigest = digest
            .add(completedAt)
            .add(message?.bindingDigest ?: "-")
            .add(this.providerRequestId ?: "-")
            .finish()
    }

    fun requireValidFor(request: LanguageModelRequest, descriptor: LanguageModelDescriptor) {
        request.messages.forEach(AgentMessage::requireBindingIntact)
        message?.requireBindingIntact()
        request.requireSupportedBy(descriptor)
        require(requestId == request.requestId) { "Language model response request identifier does not match." }
        require(providerId == request.providerId && modelId == request.modelId) {
            "Language model response provider or model does not match the request."
        }
        require(completedAt in request.requestedAt..request.deadlineAt) {
            "Language model response completed outside the request lifetime."
        }
        require(usage.outputTokens <= request.maximumOutputTokens) {
            "Language model response exceeds the requested output token limit."
        }
        require(usage.inputTokens <= request.maximumInputTokens) {
            "Language model response exceeds the reserved input token limit."
        }
        require(usage.costMicros <= request.maximumCostMicros) {
            "Language model response exceeds the reserved cost limit."
        }
        require(usage.durationMillis <= request.deadlineAt - request.requestedAt) {
            "Language model response exceeds the reserved wall-clock window."
        }
        require(usage.modelCalls == 1 && usage.toolCalls == 0) {
            "Language model response usage must describe exactly one model call and no tool calls."
        }
    }
}

/** Open streaming event boundary. Events carry assembled safe blocks, not vendor SDK delta objects. */
interface LanguageModelEvent {
    val requestId: Identifier
    val sequence: Long
    val occurredAt: Long
}

class LanguageModelTextDeltaEvent(
    requestId: Identifier,
    override val sequence: Long,
    override val occurredAt: Long,
    text: String,
) : LanguageModelEvent {
    override val requestId: Identifier = requireOpaqueIdentifier(
        requestId,
        "Language model event request identifier is invalid.",
    )
    val text: String = requireAgentContent(
        text,
        AgentContractLimits.MAX_CONTENT_CODE_POINTS,
        "Language model text delta is invalid.",
    )

    init {
        requirePositiveSequence(sequence, "Language model event sequence must be positive.")
        requireNonNegativeTime(occurredAt, "Language model event time must not be negative.")
    }
}

class LanguageModelContentBlockEvent(
    requestId: Identifier,
    override val sequence: Long,
    override val occurredAt: Long,
    val block: AgentContentBlock,
) : LanguageModelEvent {
    override val requestId: Identifier = requireOpaqueIdentifier(
        requestId,
        "Language model event request identifier is invalid.",
    )

    init {
        requirePositiveSequence(sequence, "Language model event sequence must be positive.")
        requireNonNegativeTime(occurredAt, "Language model event time must not be negative.")
        requireAgentContentBlockContract(block)
    }
}

class LanguageModelUsageEvent(
    requestId: Identifier,
    override val sequence: Long,
    override val occurredAt: Long,
    val cumulativeUsage: AgentUsage,
) : LanguageModelEvent {
    override val requestId: Identifier = requireOpaqueIdentifier(
        requestId,
        "Language model event request identifier is invalid.",
    )

    init {
        requirePositiveSequence(sequence, "Language model event sequence must be positive.")
        requireNonNegativeTime(occurredAt, "Language model event time must not be negative.")
    }
}

interface LanguageModelObserver {
    fun onEvent(event: LanguageModelEvent)

    companion object {
        @JvmField
        val NOOP: LanguageModelObserver = object : LanguageModelObserver {
            override fun onEvent(event: LanguageModelEvent) = Unit
        }
    }
}

interface LanguageModelCall {
    fun completion(): CompletionStage<LanguageModelResponse>

    fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean>
}

interface LanguageModelProvider {
    fun descriptor(): LanguageModelDescriptor

    fun start(request: LanguageModelRequest, observer: LanguageModelObserver): LanguageModelCall
}
