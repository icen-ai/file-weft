package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentCancellationToken
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentContentOrigin
import ai.icen.fw.agent.api.AgentMessage
import ai.icen.fw.agent.api.AgentMessageRole
import ai.icen.fw.agent.api.AgentTextContentBlock
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.LanguageModelCall
import ai.icen.fw.agent.api.LanguageModelDescriptor
import ai.icen.fw.agent.api.LanguageModelFinishReason
import ai.icen.fw.agent.api.LanguageModelObserver
import ai.icen.fw.agent.api.LanguageModelProvider
import ai.icen.fw.agent.api.LanguageModelRequest
import ai.icen.fw.agent.api.LanguageModelResponse
import ai.icen.fw.agent.api.ModelId
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** In-memory proof that the public model contract exercises a valid Java-8 CompletionStage provider. */
class LanguageModelProviderContractTestBehaviorTest : LanguageModelProviderContractTest() {
    private val capability = AgentCapabilityId("answer")
    private val descriptor = LanguageModelDescriptor(
        ProviderId("test-model-provider"),
        ModelId("test-model"),
        "Test model",
        setOf(capability),
        128,
        64,
        false,
        false,
        100,
        1_000,
    )

    override val languageModelProvider: LanguageModelProvider = object : LanguageModelProvider {
        override fun descriptor(): LanguageModelDescriptor = descriptor

        override fun start(request: LanguageModelRequest, observer: LanguageModelObserver): LanguageModelCall {
            val response = LanguageModelResponse(
                request.requestId,
                request.providerId,
                request.modelId,
                LanguageModelFinishReason.STOP,
                AgentUsage(inputTokens = 3, outputTokens = 2, modelCalls = 1, durationMillis = 10, costMicros = 5),
                120,
                AgentMessage(
                    Identifier("assistant-message"),
                    AgentMessageRole.ASSISTANT,
                    listOf(AgentTextContentBlock(AgentContentOrigin.MODEL, "ok")),
                    120,
                ),
            )
            return object : LanguageModelCall {
                override fun completion(): CompletionStage<LanguageModelResponse> =
                    CompletableFuture.completedFuture(response)

                override fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean> =
                    CompletableFuture.completedFuture(false)
            }
        }
    }

    override fun modelRequest(descriptor: LanguageModelDescriptor): LanguageModelRequest = LanguageModelRequest(
        Identifier("model-request"),
        Identifier("tenant-1"),
        descriptor.providerId,
        descriptor.modelId,
        listOf(
            AgentMessage(
                Identifier("user-message"),
                AgentMessageRole.USER,
                listOf(AgentTextContentBlock(AgentContentOrigin.USER, "hello")),
                100,
            ),
        ),
        emptyList(),
        16,
        100,
        200,
        AgentCancellationToken.NONE,
        maximumInputTokens = 32,
        maximumCostMicros = 10,
    )

    override fun requiredCapabilities(): Set<AgentCapabilityId> = setOf(capability)
}
