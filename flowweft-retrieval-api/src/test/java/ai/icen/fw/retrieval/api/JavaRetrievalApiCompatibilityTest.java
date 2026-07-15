package ai.icen.fw.retrieval.api;

import ai.icen.fw.core.id.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JavaRetrievalApiCompatibilityTest {
    @Test
    void completeSecurityChainIsCallableFromPlainJava8() {
        Identifier tenantId = id("tenant-java");
        Identifier documentId = id("document-java");
        RetrievalAuthorizationRequest authorization = RetrievalAuthorizationRequest.create(
            id("query-authorization-java"),
            tenantId,
            RetrievalAuthorizationSubject.create(
                RetrievalPrincipal.create(id("user-java"), "USER"),
                Collections.singletonMap("department", "研发")
            ),
            "document:read",
            "agent-answer",
            100L
        );
        RetrievalAccessPlan plan = RetrievalAccessPlan.authorizedIds(
            id("query-decision-java"),
            authorization,
            "host-authorization",
            "policy-7",
            110L,
            1000L,
            Collections.singletonList(documentId)
        );
        CandidateRetrieverDescriptor descriptor = CandidateRetrieverDescriptor.builder(
                "local-index",
                "provider-java",
                repeat('c', 64),
                repeat('d', 64),
                "capability-1"
            )
            .tenantConstraint("fw_tenant_id", "tenant-capability-1")
            .supportMode(RetrievalMode.FULL_TEXT)
            .supportAccessProfile(RetrievalAccessProfile.AUTHORIZED_ID_SET)
            .limits(10, 100)
            .queryEgress(false)
            .cancellation(true)
            .cursorPagination(true)
            .tenantAndAccessPreselectionGuaranteed(true)
            .build();
        RetrievalRequestSpec requestSpec = RetrievalRequestSpec.create(
            id("request-java"),
            RetrievalMode.FULL_TEXT,
            "天津水务",
            5,
            500L
        );
        ExecutableRetrievalRequest executable = RetrievalExecutionGate.prepare(
            id("attempt-java"),
            authorization,
            RetrievalPlanResult.allow(plan, 110L),
            requestSpec,
            descriptor,
            RetrievalExecutionPolicy.create(false, true, 10, 1000L),
            120L
        ).requireExecutable();
        RetrievalEvidenceRef evidence = RetrievalEvidenceRef.document(
            tenantId,
            id("catalog-java"),
            id("projection-java"),
            documentId,
            id("version-java"),
            id("asset-java"),
            id("object-java"),
            repeat('b', 64),
            "generation-java",
            RetrievalLineageRevisions.create("projection-v1", "acl-v1", null, null, null)
        );
        RetrievalCandidate candidate = RetrievalCandidate.create(
            evidence,
            RetrievalMode.FULL_TEXT,
            1.0d,
            1
        );
        RetrievalResultEnvelope envelope = RetrievalResultEnvelope.create(
            executable,
            descriptor,
            "generation-java",
            130L,
            Collections.singletonList(candidate),
            "cursor_java_page_2",
            false,
            false
        );
        PrefilteredCandidateBatch prefiltered = envelope.verifyFor(executable, descriptor, 140L);
        RetrievalPageCursor cursor = prefiltered.getNextCursor();
        assertEquals("cursor_java_page_2", cursor.getOpaqueToken());
        RetrievalRequestSpec resumedSpec = RetrievalRequestSpec.create(
            id("request-java-page-2"),
            RetrievalMode.FULL_TEXT,
            "天津水务",
            5,
            500L,
            cursor
        );
        assertEquals(cursor.getDigest(), resumedSpec.getPageCursor().getDigest());
        long[] lineageTimes = new long[] {145L, 150L};
        AtomicInteger lineageClockIndex = new AtomicInteger();
        RetrievalLineageResolverDescriptor lineageDescriptor = RetrievalLineageResolverDescriptor.create(
            "host-lineage",
            "lineage-java",
            repeat('1', 64),
            repeat('2', 64),
            "lineage-revision",
            true
        );
        RetrievalLineageResolver resolver = new RetrievalLineageResolver() {
            @Override
            public RetrievalLineageResolverDescriptor descriptor() {
                return lineageDescriptor;
            }

            @Override
            public RetrievalCall<RetrievalLineageResolutionBatch> resolve(
                RetrievalLineageResolutionRequest request
            ) {
                return RetrievalCalls.completed(RetrievalLineageResolutionBatch.success(
                request,
                Collections.singletonList(
                    RetrievalLineageResolution.create(
                        request.getSource().getCandidates().get(0),
                        evidence,
                        "catalog-authority",
                        "lineage-7"
                    )
                ),
                150L
            ));
            }
        };
        ResolvedCandidateBatch resolvedBatch = RetrievalLineageResolutionGate.create(
            resolver,
            () -> lineageTimes[lineageClockIndex.getAndIncrement()]
        ).resolve(executable, prefiltered, id("lineage-java"), lineageDescriptor)
            .completion().toCompletableFuture().join();
        long[] authorizationTimes = new long[] {160L, 170L};
        AtomicInteger authorizationClockIndex = new AtomicInteger();
        LongSupplier authorizationClock = () -> authorizationTimes[authorizationClockIndex.getAndIncrement()];
        RetrievalCandidateAuthorizerDescriptor authorizerDescriptor = RetrievalCandidateAuthorizerDescriptor.create(
            "host-authorization",
            "authorization-java",
            repeat('3', 64),
            repeat('4', 64),
            "authorization-revision",
            true
        );
        RetrievalCandidateAuthorizer authorizer = new RetrievalCandidateAuthorizer() {
            @Override
            public RetrievalCandidateAuthorizerDescriptor descriptor() {
                return authorizerDescriptor;
            }

            @Override
            public RetrievalCall<RetrievalCandidateAuthorizationDecisionBatch> authorize(
                RetrievalCandidateAuthorizationBatch requests
            ) {
                RetrievalCandidateAuthorizationRequest exactRequest = requests.getRequests().get(0);
                RetrievalCandidateAuthorizationDecision decision = RetrievalCandidateAuthorizationDecision.allow(
                    id("candidate-decision-java"),
                    exactRequest,
                    "host-authorization",
                    "policy-8",
                    165L,
                    210L
                );
                return RetrievalCalls.completed(
                    RetrievalCandidateAuthorizationDecisionBatch.success(
                    requests,
                    Collections.singletonList(decision),
                    165L
                ));
            }
        };
        AuthorizedCandidateBatch authorized = RetrievalCandidateAuthorizationGate.create(
            authorizer,
            authorizationClock
        ).authorize(
            authorization,
            resolvedBatch,
            id("candidate-authorization-batch-java"),
            Collections.singletonList(id("candidate-authorization-java")),
            authorizerDescriptor
        ).completion().toCompletableFuture().join();
        RetrievalStageProviderBinding contentBinding = RetrievalStageProviderBinding.create(
            "content-hydration",
            "content-java",
            "content-instance-java",
            repeat('5', 64),
            repeat('6', 64),
            "content-revision",
            repeat('7', 64),
            true
        );
        RetrievalContentEgressDecision egressDecision = RetrievalContentEgressDecision.create(
            id("content-egress-java"),
            authorized.getCandidates().get(0),
            contentBinding,
            repeat('8', 64),
            "runtime-policy",
            "policy-java",
            false,
            false,
            175L,
            205L
        );
        RetrievalHydrationRequest hydrationRequest = RetrievalHydrationRequest.create(
            id("hydration-java"),
            authorized.getCandidates().get(0),
            contentBinding,
            egressDecision,
            1000,
            180L,
            205L
        );
        long[] hydrationTimes = new long[] {180L, 190L};
        AtomicInteger hydrationClockIndex = new AtomicInteger();
        RetrievedContent content = RetrievalContentHydrationGate.create(
            request -> RetrievalCalls.completed(
                RetrievedContentPayload.success(request, "第一行\n第二行", "text/plain", repeat('b', 64))
            ),
            () -> hydrationTimes[hydrationClockIndex.getAndIncrement()]
        ).hydrate(hydrationRequest).completion().toCompletableFuture().join();

        CandidateRetriever retriever = new CandidateRetriever() {
            @Override
            public CandidateRetrieverDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public RetrievalCall<RetrievalResultEnvelope> start(ExecutableRetrievalRequest ignored) {
                return new RetrievalCall<RetrievalResultEnvelope>() {
                    @Override
                    public CompletionStage<RetrievalResultEnvelope> completion() {
                        return CompletableFuture.completedFuture(envelope);
                    }

                    @Override
                    public CompletionStage<RetrievalCancellationOutcome> cancel(
                        RetrievalCancellationReason reason
                    ) {
                        return CompletableFuture.completedFuture(RetrievalCancellationOutcome.ACCEPTED);
                    }
                };
            }
        };

        assertEquals("第一行\n第二行", content.getText());
        assertEquals(contentBinding.getDigest(), content.getProviderBindingDigest());
        assertEquals("content-instance-java", content.getProviderInstanceId());
        assertEquals(contentBinding.getConfigurationDigest(), content.getProviderConfigurationDigest());
        assertEquals(contentBinding.getCapabilityDigest(), content.getProviderCapabilityDigest());
        assertEquals(contentBinding.getCapabilityRevision(), content.getProviderRevision());
        assertEquals(contentBinding.getDescriptorDigest(), content.getProviderDescriptorDigest());
        assertEquals(egressDecision.getDigest(), content.getEgressDecisionDigest());
        assertEquals(
            RetrievalCancellationOutcome.ACCEPTED,
            retriever.start(executable)
                .cancel(RetrievalCancellationReason.CALLER_CANCELLED)
                .toCompletableFuture()
                .join()
        );
        assertFalse(Arrays.stream(RetrievalResultEnvelope.class.getMethods())
            .anyMatch(method -> method.getName().equals("getCandidates")));
    }

    private static Identifier id(String value) {
        return new Identifier(value);
    }

    private static String repeat(char character, int count) {
        char[] value = new char[count];
        Arrays.fill(value, character);
        return new String(value);
    }
}
