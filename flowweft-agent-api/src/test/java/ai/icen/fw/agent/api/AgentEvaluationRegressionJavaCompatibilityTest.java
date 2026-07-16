package ai.icen.fw.agent.api;

import ai.icen.fw.core.id.Identifier;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvaluationRegressionJavaCompatibilityTest {

    @Test
    void javaCanBuildAndInspectRegressionAndDiagnosticContracts() {
        AgentCapabilityId capability = new AgentCapabilityId("agent.answer");
        AgentEvaluationProviderSnapshot provider = new AgentEvaluationProviderSnapshot(
            new ProviderId("model.local"),
            "2026.07.1",
            Collections.singletonList(capability),
            digest('a'),
            100L,
            200L
        );
        AgentEvaluationRetrievalExpectation retrieval = new AgentEvaluationRetrievalExpectation(
            Collections.singletonList(id("evidence-1")), 1
        );
        AgentEvaluationCitationExpectation citations = new AgentEvaluationCitationExpectation(
            Collections.singletonList(id("evidence-1")), 1
        );
        AgentEvaluationToolExpectation tool = new AgentEvaluationToolExpectation(
            AgentEvaluationToolDecision.REQUIRE_APPROVAL,
            new ProviderId("tool.local"),
            new ToolId("document.publish"),
            digest('b')
        );
        AgentEvaluationExpectedOutcome expected = new AgentEvaluationExpectedOutcome(
            retrieval,
            citations,
            tool,
            AgentEvaluationRefusalExpectation.MUST_ANSWER,
            Long.valueOf(1_000L),
            Long.valueOf(500L)
        );
        AgentEvaluationCase evaluationCase = new AgentEvaluationCase(
            id("case-1"),
            id("fixture-1"),
            capability,
            digest('c'),
            expected,
            Arrays.asList("grounded", "tool-policy")
        );
        AgentEvaluationSuite suite = new AgentEvaluationSuite(
            id("suite-1"), "Regression", "1.0", Collections.singletonList(evaluationCase), 90L
        );
        AgentEvaluationObservationContext context = new AgentEvaluationObservationContext(
            suite.getSuiteId(),
            suite.getSuiteDigest(),
            evaluationCase.getCaseId(),
            evaluationCase.getBindingDigest(),
            id("tenant-1"),
            id("principal-1"),
            "USER",
            "authorization-v1",
            provider.getSnapshotDigest(),
            110L
        );
        AgentEvaluationRetrievalObservation observation = new AgentEvaluationRetrievalObservation(
            context, digest('d'), 1, 0, 0, true
        );
        AgentEvaluationCostObservation cost = new AgentEvaluationCostObservation(context, 1_000L, 900L);
        AgentEvaluationLatencyObservation latency = new AgentEvaluationLatencyObservation(context, 100L, 500L, 500L);
        AgentEvaluationDiagnostic diagnostic = new AgentEvaluationDiagnostic(
            AgentEvaluationDiagnosticStatus.READY,
            null,
            provider.getProviderId(),
            capability,
            provider.getSnapshotDigest(),
            110L
        );

        context.requireMatches(suite, evaluationCase);
        assertTrue(provider.supports(capability));
        assertTrue(provider.isCurrent(110L));
        assertTrue(observation.satisfies(retrieval));
        assertFalse(cost.exceeded());
        assertFalse(latency.exceeded());
        assertEquals(AgentEvaluationObservationKind.RETRIEVAL, observation.kind());
        assertEquals(context, observation.context());
        assertEquals(AgentEvaluationDiagnosticStatus.READY, diagnostic.getStatus());
        assertEquals(2, suite.getCases().size() + evaluationCase.getTags().size() - 1);
    }

    private static Identifier id(String value) {
        return new Identifier(value);
    }

    private static String digest(char value) {
        char[] characters = new char[64];
        Arrays.fill(characters, value);
        return new String(characters);
    }
}
