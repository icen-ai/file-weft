package ai.icen.fw.retrieval.runtime;

import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.retrieval.api.CandidateRetriever;
import ai.icen.fw.retrieval.api.CandidateRetrieverDescriptor;
import ai.icen.fw.retrieval.api.ExecutableRetrievalRequest;
import ai.icen.fw.retrieval.api.RetrievalAuthorizationRequest;
import ai.icen.fw.retrieval.api.RetrievalAuthorizationSubject;
import ai.icen.fw.retrieval.api.RetrievalCall;
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizationBatch;
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizationDecisionBatch;
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizer;
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizerDescriptor;
import ai.icen.fw.retrieval.api.RetrievalDenialCode;
import ai.icen.fw.retrieval.api.RetrievalExecutionPolicy;
import ai.icen.fw.retrieval.api.RetrievalMode;
import ai.icen.fw.retrieval.api.RetrievalLineageResolutionBatch;
import ai.icen.fw.retrieval.api.RetrievalLineageResolutionRequest;
import ai.icen.fw.retrieval.api.RetrievalLineageResolver;
import ai.icen.fw.retrieval.api.RetrievalLineageResolverDescriptor;
import ai.icen.fw.retrieval.api.RetrievalPlanResult;
import ai.icen.fw.retrieval.api.RetrievalPrincipal;
import ai.icen.fw.retrieval.api.RetrievalRequestSpec;
import ai.icen.fw.retrieval.spi.FilenameCatalog;
import ai.icen.fw.retrieval.spi.RetrievalContentProvider;
import ai.icen.fw.retrieval.spi.RetrievalContentProviderDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaRetrievalRuntimeCompatibilityTest {
    @Test
    void deniedSecurityFlowIsUsableFromPlainJava8() throws Exception {
        assertNotNull(SafeFilenameCandidateRetriever.class.getMethod("create", FilenameCatalog.class));
        assertNotNull(SafeFilenameCandidateRetriever.class.getMethod("createFullTextFallback", FilenameCatalog.class));
        assertNotNull(SecureRetrievalRuntime.class.getMethod(
            "createWithFilenameFallback",
            ai.icen.fw.retrieval.api.RetrievalAuthorizationPlanner.class,
            CandidateRetriever.class,
            FilenameCatalog.class,
            RetrievalLineageResolver.class,
            RetrievalCandidateAuthorizer.class,
            RetrievalContentProvider.class,
            java.util.function.LongSupplier.class,
            RetrievalRuntimeIdGenerator.class,
            ScheduledExecutorService.class,
            RetrievalRuntimeConfiguration.class
        ));
        RetrievalAuthorizationRequest authorization = RetrievalAuthorizationRequest.create(
            id("authorization-java"),
            id("tenant-java"),
            RetrievalAuthorizationSubject.create(
                RetrievalPrincipal.create(id("user-java"), "USER"),
                Collections.singletonMap("department", "研发")
            ),
            "document:read",
            "agent-answer",
            100L
        );
        RetrievalRuntimeRequest request = RetrievalRuntimeRequest.create(
            authorization,
            RetrievalRequestSpec.create(
                id("request-java"),
                RetrievalMode.FULL_TEXT,
                "天津水务",
                5,
                500L
            ),
            RetrievalExecutionPolicy.create(false, true, 10, 1000L),
            false
        );
        CandidateRetriever neverCalledCandidateProvider = new CandidateRetriever() {
            @Override
            public CandidateRetrieverDescriptor descriptor() {
                throw new AssertionError("denial must precede provider discovery");
            }

            @Override
            public RetrievalCall<ai.icen.fw.retrieval.api.RetrievalResultEnvelope> start(
                ExecutableRetrievalRequest ignored
            ) {
                throw new AssertionError("denial must precede provider execution");
            }
        };
        RetrievalContentProvider neverCalledContentProvider = new RetrievalContentProvider() {
            @Override
            public RetrievalContentProviderDescriptor descriptor() {
                throw new AssertionError("denial must precede content provider discovery");
            }

            @Override
            public RetrievalCall<ai.icen.fw.retrieval.api.RetrievedContentPayload> hydrate(
                ai.icen.fw.retrieval.api.RetrievalHydrationRequest ignored
            ) {
                throw new AssertionError("denial must precede hydration");
            }
        };
        RetrievalLineageResolver neverCalledLineageResolver = new RetrievalLineageResolver() {
            @Override
            public RetrievalLineageResolverDescriptor descriptor() {
                throw new AssertionError("denial must precede lineage provider discovery");
            }

            @Override
            public RetrievalCall<RetrievalLineageResolutionBatch> resolve(
                RetrievalLineageResolutionRequest ignored
            ) {
                throw new AssertionError("denial must precede lineage resolution");
            }
        };
        RetrievalCandidateAuthorizer neverCalledAuthorizer = new RetrievalCandidateAuthorizer() {
            @Override
            public RetrievalCandidateAuthorizerDescriptor descriptor() {
                throw new AssertionError("denial must precede authorizer discovery");
            }

            @Override
            public RetrievalCall<RetrievalCandidateAuthorizationDecisionBatch> authorize(
                RetrievalCandidateAuthorizationBatch ignored
            ) {
                throw new AssertionError("denial must precede candidate authorization");
            }
        };
        AtomicInteger ids = new AtomicInteger();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            SecureRetrievalRuntime runtime = SecureRetrievalRuntime.create(
                exact -> RetrievalPlanResult.deny(
                    id("denial-java"),
                    exact,
                    "host-authorization",
                    "policy-java",
                    110L,
                    RetrievalDenialCode.POLICY_DENIED
                ),
                neverCalledCandidateProvider,
                neverCalledLineageResolver,
                neverCalledAuthorizer,
                neverCalledContentProvider,
                () -> 120L,
                purpose -> id(purpose.getId() + "-" + ids.incrementAndGet()),
                scheduler,
                RetrievalRuntimeConfiguration.create(10, 1000, 10000, 10, 10, false, false)
            );

            RetrievalRuntimeCall call = runtime.start(request);
            RetrievalRuntimeResult result = call.completion().toCompletableFuture().join();

            assertEquals(RetrievalRuntimeStatus.DENIED, result.getStatus());
            assertEquals(RetrievalDenialCode.POLICY_DENIED, result.getDenialCode());
            assertEquals(0, result.getItems().size());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static Identifier id(String value) {
        return new Identifier(value);
    }
}
