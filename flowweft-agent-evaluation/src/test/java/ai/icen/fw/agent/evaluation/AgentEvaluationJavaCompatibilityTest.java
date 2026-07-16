package ai.icen.fw.agent.evaluation;

import ai.icen.fw.agent.api.AgentCapabilityId;
import ai.icen.fw.agent.api.AgentEvaluationCase;
import ai.icen.fw.agent.api.AgentEvaluationExpectedOutcome;
import ai.icen.fw.agent.api.AgentEvaluationLatencyObservation;
import ai.icen.fw.agent.api.AgentEvaluationObservationContext;
import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot;
import ai.icen.fw.agent.api.AgentEvaluationRefusalExpectation;
import ai.icen.fw.agent.api.AgentEvaluationSuite;
import ai.icen.fw.agent.api.ProviderId;
import ai.icen.fw.core.id.Identifier;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvaluationJavaCompatibilityTest {

    @Test
    void javaCanRunPinnedDigestOnlyRegressionEvidence() {
        AgentCapabilityId capability = new AgentCapabilityId("agent.answer");
        AgentEvaluationCase evaluationCase = new AgentEvaluationCase(
            id("case-1"),
            id("fixture-1"),
            capability,
            digest('a'),
            new AgentEvaluationExpectedOutcome(
                null,
                null,
                null,
                AgentEvaluationRefusalExpectation.NOT_APPLICABLE,
                null,
                Long.valueOf(500L)
            ),
            Collections.singletonList("latency")
        );
        AgentEvaluationSuite suite = new AgentEvaluationSuite(
            id("suite-1"), "Regression", "1.0", Collections.singletonList(evaluationCase), 100L
        );
        AgentEvaluationProviderSnapshot provider = new AgentEvaluationProviderSnapshot(
            new ProviderId("model.local"),
            "1.0",
            Collections.singletonList(capability),
            digest('b'),
            90L,
            1_000L
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
            150L
        );
        AgentEvaluationLatencyObservation latency = new AgentEvaluationLatencyObservation(context, 150L, 450L, 500L);
        AgentEvaluationEvidenceBatch evidence = new AgentEvaluationEvidenceBatch(
            context,
            evaluationCase.getFixtureId(),
            evaluationCase.getInputDigest(),
            digest('c'),
            Collections.singletonList(latency),
            450L
        );
        DeterministicAgentEvaluationEvaluator evaluator = new DeterministicAgentEvaluationEvaluator();
        InMemoryAgentEvaluationRunner runner = new InMemoryAgentEvaluationRunner(
            new InMemoryAgentEvaluationDatasetRegistry(Collections.singletonList(suite)),
            new InMemoryAgentEvaluationProviderInventory(Collections.singletonList(provider)),
            new InMemoryAgentEvaluationEvaluatorRegistry(Collections.singletonList(evaluator))
        );
        AgentEvaluationRegressionRun request = new AgentEvaluationRegressionRun(
            AgentEvaluationDatasetReference.from(suite),
            provider,
            AgentEvaluationEvaluatorReference.from(evaluator.descriptor()),
            AgentEvaluationSubjectBinding.from(context),
            Collections.singletonList(evidence),
            500L
        );

        AgentEvaluationRegressionReport report = runner.run(request);

        assertTrue(report.getPassed());
        assertEquals(10_000, report.getScoreBasisPoints());
        assertEquals(AgentEvaluationDoctorStatus.READY, report.getDoctor().getStatus());
        assertEquals(1, report.getCaseScores().size());
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
