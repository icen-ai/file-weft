package ai.icen.fw.release.smoke.library;

import ai.icen.fw.agent.api.AgentCancellation;
import ai.icen.fw.agent.api.AgentCancellationToken;
import ai.icen.fw.agent.api.AgentCapabilityId;
import ai.icen.fw.agent.api.AgentContentOrigin;
import ai.icen.fw.agent.api.AgentMessage;
import ai.icen.fw.agent.api.AgentMessageRole;
import ai.icen.fw.agent.api.AgentTextContentBlock;
import ai.icen.fw.agent.api.AgentUsage;
import ai.icen.fw.agent.api.LanguageModelCall;
import ai.icen.fw.agent.api.LanguageModelDescriptor;
import ai.icen.fw.agent.api.LanguageModelFinishReason;
import ai.icen.fw.agent.api.LanguageModelObserver;
import ai.icen.fw.agent.api.LanguageModelProvider;
import ai.icen.fw.agent.api.LanguageModelRequest;
import ai.icen.fw.agent.api.LanguageModelResponse;
import ai.icen.fw.agent.api.ModelId;
import ai.icen.fw.agent.api.ProviderId;
import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.testkit.agent.LanguageModelProviderContractTest;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Independent Java 8 host proof for the published TestKit artifact.
 *
 * <p>JUnit discovers and executes the inherited public contract test; this is not a compilation-only
 * fixture and has no project dependency on either published TestKit project.
 */
public final class PublishedLanguageModelProviderContractTest extends LanguageModelProviderContractTest {
    private static final AgentCapabilityId CAPABILITY = new AgentCapabilityId("answer");
    private static final LanguageModelDescriptor DESCRIPTOR = new LanguageModelDescriptor(
        new ProviderId("release-smoke-model-provider"),
        new ModelId("release-smoke-model"),
        "Release smoke model",
        Collections.singleton(CAPABILITY),
        128L,
        64L,
        false,
        false,
        100L,
        1_000L
    );

    private static final LanguageModelProvider PROVIDER = new LanguageModelProvider() {
        @Override
        public LanguageModelDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public LanguageModelCall start(LanguageModelRequest request, LanguageModelObserver observer) {
            AgentMessage answer = new AgentMessage(
                new Identifier("assistant-message"),
                AgentMessageRole.ASSISTANT,
                Collections.singletonList(new AgentTextContentBlock(AgentContentOrigin.MODEL, "ok")),
                120L
            );
            LanguageModelResponse response = new LanguageModelResponse(
                request.getRequestId(),
                request.getProviderId(),
                request.getModelId(),
                LanguageModelFinishReason.STOP,
                new AgentUsage(3L, 2L, 1, 0, 10L, 5L),
                120L,
                answer,
                null
            );
            return new LanguageModelCall() {
                @Override
                public CompletionStage<LanguageModelResponse> completion() {
                    return CompletableFuture.completedFuture(response);
                }

                @Override
                public CompletionStage<Boolean> cancel(AgentCancellation cancellation) {
                    return CompletableFuture.completedFuture(Boolean.FALSE);
                }
            };
        }
    };

    @Override
    protected LanguageModelProvider getLanguageModelProvider() {
        return PROVIDER;
    }

    @Override
    protected LanguageModelRequest modelRequest(LanguageModelDescriptor descriptor) {
        AgentMessage prompt = new AgentMessage(
            new Identifier("user-message"),
            AgentMessageRole.USER,
            Collections.singletonList(new AgentTextContentBlock(AgentContentOrigin.USER, "hello")),
            100L
        );
        return new LanguageModelRequest(
            new Identifier("model-request"),
            new Identifier("tenant-1"),
            descriptor.getProviderId(),
            descriptor.getModelId(),
            Collections.singletonList(prompt),
            Collections.emptyList(),
            16L,
            100L,
            200L,
            AgentCancellationToken.NONE,
            0.0d,
            32L,
            10L
        );
    }

    @Override
    protected Set<AgentCapabilityId> requiredCapabilities() {
        return Collections.singleton(CAPABILITY);
    }
}
