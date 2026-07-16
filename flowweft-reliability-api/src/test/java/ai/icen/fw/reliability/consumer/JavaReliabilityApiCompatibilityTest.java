package ai.icen.fw.reliability.consumer;

import ai.icen.fw.reliability.api.ReliabilityAction;
import ai.icen.fw.reliability.api.ReliabilityAuthorizationSnapshot;
import ai.icen.fw.reliability.api.ReliabilityCallContext;
import ai.icen.fw.reliability.api.ReliabilityErrorBudgetEvaluation;
import ai.icen.fw.reliability.api.ReliabilityMetricCode;
import ai.icen.fw.reliability.api.ReliabilityMetricComponentClass;
import ai.icen.fw.reliability.api.ReliabilityMetricEvidence;
import ai.icen.fw.reliability.api.ReliabilityMetricOutcome;
import ai.icen.fw.reliability.api.ReliabilityPrincipalRef;
import ai.icen.fw.reliability.api.ReliabilityProviderSpi;
import ai.icen.fw.reliability.api.ReliabilityPurpose;
import ai.icen.fw.reliability.api.ReliabilityResourceRef;
import ai.icen.fw.reliability.api.ReliabilitySliKind;
import ai.icen.fw.reliability.api.ReliabilitySliObservation;
import ai.icen.fw.reliability.api.ReliabilitySloEvaluationRequest;
import ai.icen.fw.reliability.api.ReliabilitySloEvaluator;
import ai.icen.fw.reliability.api.ReliabilitySloObjective;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaReliabilityApiCompatibilityTest {
    @Test
    void javaConsumerCanConstructAndEvaluatePublicContracts() throws ReflectiveOperationException {
        ReliabilityResourceRef resource = ReliabilityResourceRef.of("service", "api", "1", digest('a'));
        ReliabilityPrincipalRef principal = ReliabilityPrincipalRef.of("user", "operator");
        ReliabilityAuthorizationSnapshot authorization = ReliabilityAuthorizationSnapshot.of(
            "auth-1",
            "tenant-a",
            principal,
            ReliabilityPurpose.EVALUATE_SLO,
            ReliabilityAction.EVALUATE_SLO,
            resource,
            "host-authz",
            "1",
            "1",
            digest('b'),
            9_900L,
            11_100L
        );
        ReliabilityCallContext context = ReliabilityCallContext.of(
            "request-1",
            "tenant-a",
            principal,
            ReliabilityPurpose.EVALUATE_SLO,
            ReliabilityAction.EVALUATE_SLO,
            resource,
            authorization,
            digest('c'),
            10_000L,
            11_000L
        );
        ReliabilitySloObjective objective = ReliabilitySloObjective.of(
            "availability",
            "1",
            digest('d'),
            resource,
            ReliabilitySliKind.AVAILABILITY,
            990_000L,
            1_000L,
            100L,
            2_000L,
            0L,
            20_000L
        );
        ReliabilitySliObservation observation = ReliabilitySliObservation.of(
            objective.getObjectiveDigest(), 8_000L, 9_000L, 995L, 1_000L, 9_100L
        );
        ReliabilitySloEvaluationRequest request = ReliabilitySloEvaluationRequest.of(
            context, objective, observation, 8_000L, 9_000L, 10_000L
        );

        ReliabilityErrorBudgetEvaluation result = ReliabilitySloEvaluator.STANDARD.evaluate(request);

        assertEquals(Long.valueOf(500_000L), result.getBudgetConsumedPpm());
        assertEquals(Long.valueOf(500_000L), result.getRemainingBudgetPpm());
        assertNotNull(ReliabilityProviderSpi.class.getMethod("restore",
            Class.forName("ai.icen.fw.reliability.api.ReliabilityRestoreRequest")));

        ReliabilityMetricEvidence metric = ReliabilityMetricEvidence.of(
            ReliabilityMetricCode.SLO_EVALUATION_RESULT,
            ReliabilityMetricOutcome.SLO_SATISFIED,
            null,
            ReliabilityMetricComponentClass.OTHER,
            10_000L
        );
        assertEquals(ReliabilityMetricOutcome.SLO_SATISFIED, metric.getOutcome());
    }

    private static String digest(char value) {
        StringBuilder builder = new StringBuilder(64);
        for (int index = 0; index < 64; index++) builder.append(value);
        return builder.toString();
    }
}
