package ai.icen.fw.agent.api;

import ai.icen.fw.core.id.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaAgentApiCompatibilityTest {

    @Test
    void anonymousModelProviderUsesCompletionStageObserverAndExplicitCancel() {
        ProviderId providerId = new ProviderId("provider.java");
        ModelId modelId = new ModelId("model.java");
        AgentCapabilityId capabilityId = new AgentCapabilityId("agent.answer");
        LanguageModelDescriptor descriptor = new LanguageModelDescriptor(
            providerId,
            modelId,
            "Java model",
            Collections.singleton(capabilityId),
            8_192L,
            1_024L,
            true,
            false
        );
        AgentContentBlock extensionBlock = new AgentContentBlock() {
            @Override
            public String kind() {
                return "java-extension";
            }

            @Override
            public AgentContentOrigin origin() {
                return AgentContentOrigin.USER;
            }

            @Override
            public String bindingDigest() {
                try {
                    return digest("java-extension-canonical-payload".getBytes(StandardCharsets.UTF_8));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
        AgentMessage user = new AgentMessage(
            id("message-user"),
            AgentMessageRole.USER,
            Collections.singletonList(extensionBlock),
            10L
        );
        LanguageModelRequest request = new LanguageModelRequest(
            id("model-request"),
            id("tenant-java"),
            providerId,
            modelId,
            Collections.singletonList(user),
            Collections.<AgentToolDescriptor>emptyList(),
            256L,
            20L,
            200L,
            AgentCancellationToken.NONE
        );
        AgentMessage assistant = new AgentMessage(
            id("message-assistant"),
            AgentMessageRole.ASSISTANT,
            Collections.<AgentContentBlock>singletonList(
                new AgentTextContentBlock(AgentContentOrigin.MODEL, "done")
            ),
            30L
        );
        LanguageModelResponse response = new LanguageModelResponse(
            request.getRequestId(),
            providerId,
            modelId,
            LanguageModelFinishReason.STOP,
            new AgentUsage(10L, 2L, 1, 0, 5L, 0L, Collections.<String, Long>emptyMap()),
            40L,
            assistant,
            "provider-request-java"
        );
        AtomicReference<LanguageModelEvent> observed = new AtomicReference<LanguageModelEvent>();
        AtomicReference<AgentCancellation> cancelled = new AtomicReference<AgentCancellation>();

        LanguageModelProvider provider = new LanguageModelProvider() {
            @Override
            public LanguageModelDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public LanguageModelCall start(LanguageModelRequest actual, LanguageModelObserver observer) {
                observer.onEvent(new LanguageModelTextDeltaEvent(actual.getRequestId(), 1L, 25L, "do"));
                return new LanguageModelCall() {
                    @Override
                    public CompletionStage<LanguageModelResponse> completion() {
                        return CompletableFuture.completedFuture(response);
                    }

                    @Override
                    public CompletionStage<Boolean> cancel(AgentCancellation cancellation) {
                        cancelled.set(cancellation);
                        return CompletableFuture.completedFuture(Boolean.TRUE);
                    }
                };
            }
        };

        LanguageModelCall call = provider.start(request, observed::set);
        assertSame(response, call.completion().toCompletableFuture().join());
        AgentCancellation cancellation = new AgentCancellation("caller.cancelled", 50L);
        assertTrue(call.cancel(cancellation).toCompletableFuture().join());
        assertSame(cancellation, cancelled.get());
        assertTrue(observed.get() instanceof LanguageModelTextDeltaEvent);
        assertEquals("java-extension", extensionBlock.kind());
        assertEquals(1_000_000_000L, descriptor.getMaximumCostMicros());
        assertEquals(60_000L, descriptor.getMaximumDurationMillis());
        assertNull(AgentCancellationToken.NONE.cancellation());
    }

    @Test
    void anonymousRunServiceExposesStableRunIdCompletionAndCancel() {
        Identifier runId = id("run-java");
        Identifier tenantId = id("tenant-java");
        AgentCapabilityId capabilityId = new AgentCapabilityId("agent.answer");
        AgentBudget budget = new AgentBudget(8_192L, 1_024L, 4, 2, 60_000L);
        AgentMessage message = new AgentMessage(
            id("message-java"),
            AgentMessageRole.USER,
            Collections.<AgentContentBlock>singletonList(
                new AgentTextContentBlock(AgentContentOrigin.USER, "question")
            ),
            10L
        );
        AgentRunContext context = new AgentRunContext(
            tenantId,
            id("user-java"),
            "USER",
            id("request-java"),
            10L
        );
        AgentRunRequest request = new AgentRunRequest(
            context,
            capabilityId,
            Collections.singletonList(message),
            budget,
            "tenant-java:request-java",
            1_000L,
            AgentCancellationToken.NONE
        );
        AgentRunSnapshot snapshot = new AgentRunSnapshot(
            runId,
            tenantId,
            capabilityId,
            AgentRunStatus.QUEUED,
            Collections.singletonList(message),
            budget,
            new AgentUsage(),
            0L,
            10L,
            10L
        );
        AtomicReference<AgentCancellation> cancelled = new AtomicReference<AgentCancellation>();

        AgentRunService service = new AgentRunService() {
            @Override
            public AgentRunCall start(AgentRunRequest ignored, AgentRunObserver observer) {
                observer.onEvent(new AgentRunStatusChangedEvent(
                    runId,
                    tenantId,
                    1L,
                    10L,
                    null,
                    AgentRunStatus.QUEUED
                ));
                return new AgentRunCall() {
                    @Override
                    public Identifier runId() {
                        return runId;
                    }

                    @Override
                    public CompletionStage<AgentRunSnapshot> completion() {
                        return CompletableFuture.completedFuture(snapshot);
                    }

                    @Override
                    public CompletionStage<Boolean> cancel(AgentCancellation cancellation) {
                        cancelled.set(cancellation);
                        return CompletableFuture.completedFuture(Boolean.TRUE);
                    }
                };
            }
        };

        AgentRunCall call = service.start(request, AgentRunObserver.NOOP);
        assertEquals(runId, call.runId());
        assertSame(snapshot, call.completion().toCompletableFuture().join());
        AgentCancellation cancellation = new AgentCancellation("operator.cancelled", 20L);
        assertTrue(call.cancel(cancellation).toCompletableFuture().join());
        assertSame(cancellation, cancelled.get());
    }

    @Test
    void javaCanBuildTheBoundAuthorizationChainAndFreshExecutionReceipt() throws Exception {
        byte[] schema = "{\"type\":\"object\"}".getBytes(StandardCharsets.UTF_8);
        byte[] arguments = "{\"documentId\":\"document-java\"}".getBytes(StandardCharsets.UTF_8);
        AgentToolDescriptor descriptor = new AgentToolDescriptor(
            new ProviderId("tools.java"),
            new ToolId("document.read"),
            "Read document",
            "Read one authorized document.",
            AgentToolRisk.READ_ONLY,
            schema,
            digest(schema),
            Collections.singleton(new AgentCapabilityId("agent.answer")),
            true,
            4096
        );
        AgentAuthorizationRequest preflight = AgentAuthorizationRequest.preflight(
            id("authorization-java-preflight"),
            id("execution-java"),
            id("tenant-java"),
            id("user-java"),
            "USER",
            id("run-java"),
            id("step-java"),
            new ProviderId("authorization.java"),
            descriptor,
            arguments,
            "tenant-java:run-java:step-java:document.read",
            "document:read",
            "document",
            id("document-java"),
            "revision-1",
            "agent.tool.document-read",
            10L,
            1000L
        );
        AgentAuthorizationDecision initial = AgentAuthorizationDecision.allow(
            id("authorization-java-initial"),
            new ProviderId("authorization.java"),
            preflight,
            "authorization-revision-1",
            20L,
            900L
        );
        AgentPolicyProposal proposal = AgentPolicyProposal.create(
            id("proposal-java"),
            new ProviderId("policy.java"),
            preflight,
            initial,
            AgentToolRisk.READ_ONLY,
            new AgentBudget(1000L, 100L, 2, 1, 1000L),
            new AgentUsage(),
            30L,
            800L
        );
        AgentPolicyDecision policy = AgentPolicyDecision.allow(
            id("policy-java"),
            new ProviderId("policy.java"),
            proposal,
            "policy-revision-1",
            40L,
            700L
        );
        AgentAuthorizationRequest recheck = AgentAuthorizationRequest.executionRecheck(
            id("authorization-java-recheck"),
            preflight,
            50L,
            650L
        );
        AgentAuthorizationDecision execution = AgentAuthorizationDecision.allow(
            id("authorization-java-execution"),
            new ProviderId("authorization.java"),
            recheck,
            "authorization-revision-1",
            60L,
            600L
        );
        AuthorizedToolInvocation invocation = AuthorizedToolInvocation.authorize(
            id("invocation-java"),
            proposal,
            descriptor,
            policy,
            recheck,
            execution,
            null,
            null,
            arguments,
            "tenant-java:run-java:step-java:document.read",
            1,
            70L,
            500L,
            AgentCancellationToken.NONE
        );
        AgentExecutionContextConsumption receipt = AgentExecutionContextConsumption.claimed(
            id("receipt-java"),
            new ProviderId("execution-store.java"),
            invocation,
            70L,
            "store-revision-1"
        );
        AgentAuthorizationRequest finalRecheck = AgentAuthorizationRequest.finalExecutionRecheck(
            id("authorization-java-final-recheck"),
            recheck,
            71L,
            500L
        );
        AgentAuthorizationDecision finalAuthorization = AgentAuthorizationDecision.allow(
            id("authorization-java-final"),
            new ProviderId("authorization.java"),
            finalRecheck,
            "authorization-revision-1",
            72L,
            500L
        );
        AgentDispatchAuthorizationFenceRequest dispatchFence = new AgentDispatchAuthorizationFenceRequest(
            id("dispatch-fence-java"),
            new ProviderId("runtime.worker.java"),
            invocation,
            finalRecheck,
            finalAuthorization,
            73L,
            500L
        );
        AgentDispatchAuthorizationFenceConsumption dispatchReceipt =
            AgentDispatchAuthorizationFenceConsumption.consumed(
                id("dispatch-fence-receipt-java"),
                dispatchFence,
                74L,
                "authorization-store-revision-1"
            );
        AgentExecutableToolInvocation executable = AgentExecutableToolInvocation.create(
            invocation,
            receipt,
            finalRecheck,
            finalAuthorization,
            dispatchFence,
            dispatchReceipt,
            new ProviderId("execution-store.java"),
            new ProviderId("runtime.worker.java"),
            descriptor.getMaximumCostMicros(),
            425L,
            75L
        );

        assertSame(invocation, executable.getInvocation());
        assertEquals(AgentExecutionContextConsumptionStatus.CLAIMED, receipt.getStatus());
        assertEquals(0L, descriptor.getMaximumCostMicros());
        assertEquals(60_000L, descriptor.getMaximumDurationMillis());
        assertEquals(AgentDispatchAuthorizationFenceStatus.CONSUMED, dispatchReceipt.getStatus());
        assertFalse(executable.toString().contains("tenant-java"));
        assertFalse(executable.toString().contains("document-java"));
    }

    private static Identifier id(String value) {
        return new Identifier(value);
    }

    private static String digest(byte[] value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }
}
