package ai.icen.fw.agent.runtime;

import ai.icen.fw.agent.api.AgentCapabilityId;
import ai.icen.fw.agent.api.AgentEvaluationCase;
import ai.icen.fw.agent.api.AgentEvaluationExpectedOutcome;
import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot;
import ai.icen.fw.agent.api.AgentEvaluationRefusalExpectation;
import ai.icen.fw.agent.api.AgentEvaluationSuite;
import ai.icen.fw.agent.api.ProviderId;
import ai.icen.fw.core.id.Identifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentEvaluationRuntimeJavaCompatibilityTest {

    @Test
    void javaCanConsumeDurableEvaluationPortsAndState() throws Exception {
        byte[] payload = "fixture".getBytes(StandardCharsets.UTF_8);
        String payloadDigest = sha256(payload);
        AgentCapabilityId capability = new AgentCapabilityId("agent.answer");
        AgentEvaluationCase evaluationCase = new AgentEvaluationCase(
            id("case-1"),
            id("fixture-1"),
            capability,
            payloadDigest,
            new AgentEvaluationExpectedOutcome(
                null,
                null,
                null,
                AgentEvaluationRefusalExpectation.NOT_APPLICABLE,
                null,
                500L
            ),
            Collections.singletonList("latency")
        );
        AgentEvaluationSuite suite = new AgentEvaluationSuite(
            id("suite-1"), "Regression", "1.0", Collections.singletonList(evaluationCase), 90L
        );
        AgentEvaluationProviderSnapshot snapshot = new AgentEvaluationProviderSnapshot(
            new ProviderId("evaluator.local"),
            "1.0.0",
            Collections.singletonList(capability),
            sha256("provider".getBytes(StandardCharsets.UTF_8)),
            90L,
            1_000L
        );
        AgentEvaluationRunRequest request = new AgentEvaluationRunRequest(
            id("request-1"),
            id("tenant-1"),
            id("principal-1"),
            "USER",
            "authorization-v1",
            suite,
            snapshot,
            "idempotency-key",
            100L,
            1_000L,
            2
        );
        AgentEvaluationRunState state = AgentEvaluationRunState.initial(id("evaluation-1"), request);
        AgentEvaluationFixturePort fixtures = load -> CompletableFuture.completedFuture(
            new AgentEvaluationFixture(load.getFixtureId(), "text/plain", payload, payloadDigest)
        );
        AgentEvaluationFailureClassifier classifier = failure -> new AgentEvaluationFailureDecision(
            AgentEvaluationFailureKind.RETRYABLE,
            new ai.icen.fw.agent.api.AgentEvaluationDiagnosticReason("evaluator.transient")
        );

        AgentEvaluationFixture loaded = fixtures.load(
            new AgentEvaluationFixtureLoadRequest(
                id("fixture-request-1"),
                state.getEvaluationId(),
                new ai.icen.fw.agent.api.AgentEvaluationObservationContext(
                    suite.getSuiteId(),
                    suite.getSuiteDigest(),
                    evaluationCase.getCaseId(),
                    evaluationCase.getBindingDigest(),
                    state.getTenantId(),
                    state.getPrincipalId(),
                    state.getPrincipalType(),
                    state.getAuthorizationRevision(),
                    snapshot.getSnapshotDigest(),
                    100L
                ),
                evaluationCase.getFixtureId(),
                evaluationCase.getInputDigest(),
                100L,
                1_000L
            )
        ).toCompletableFuture().get();

        assertEquals(AgentEvaluationRunStatus.QUEUED, state.getStatus());
        assertEquals(payloadDigest, loaded.getPayloadDigest());
        assertEquals(AgentEvaluationFailureKind.RETRYABLE, classifier.classify(new Exception()).getKind());
        assertNotNull(state.getIdempotencyScope().getScopeDigest());
        assertEquals(30_000L, new AgentEvaluationRuntimeConfiguration().getLeaseDurationMillis());
    }

    private static Identifier id(String value) {
        return new Identifier(value);
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder();
        for (byte item : digest) {
            value.append(String.format("%02x", item & 0xff));
        }
        return value.toString();
    }
}
