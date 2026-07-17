package ai.icen.fw.governance.consumer;

import ai.icen.fw.governance.api.GovernanceAuthorizationSnapshot;
import ai.icen.fw.governance.api.GovernanceCallContext;
import ai.icen.fw.governance.api.GovernanceCapability;
import ai.icen.fw.governance.api.GovernanceCapabilityProvider;
import ai.icen.fw.governance.api.GovernanceCapabilityRequest;
import ai.icen.fw.governance.api.GovernanceCapabilityResult;
import ai.icen.fw.governance.api.GovernanceCapabilitySnapshot;
import ai.icen.fw.governance.api.GovernanceDeletionPlan;
import ai.icen.fw.governance.api.GovernanceDeletionReconciler;
import ai.icen.fw.governance.api.GovernanceDeletionStage;
import ai.icen.fw.governance.api.GovernanceDeletionStep;
import ai.icen.fw.governance.api.GovernanceDeletionStepExecutor;
import ai.icen.fw.governance.api.GovernanceDoctor;
import ai.icen.fw.governance.api.GovernanceDoctorFinding;
import ai.icen.fw.governance.api.GovernanceDoctorMode;
import ai.icen.fw.governance.api.GovernanceDoctorRequest;
import ai.icen.fw.governance.api.GovernanceDoctorResult;
import ai.icen.fw.governance.api.GovernanceDoctorSeverity;
import ai.icen.fw.governance.api.GovernanceDoctorStatus;
import ai.icen.fw.governance.api.GovernanceEffectiveClock;
import ai.icen.fw.governance.api.GovernanceLegalHoldResolution;
import ai.icen.fw.governance.api.GovernanceLegalHoldResolutionRequest;
import ai.icen.fw.governance.api.GovernanceLegalHoldResolver;
import ai.icen.fw.governance.api.GovernancePrincipalRef;
import ai.icen.fw.governance.api.GovernancePurpose;
import ai.icen.fw.governance.api.GovernanceResourceRef;
import ai.icen.fw.governance.api.GovernanceRetentionAssessment;
import ai.icen.fw.governance.api.GovernanceRetentionEvaluationRequest;
import ai.icen.fw.governance.api.GovernanceRetentionEvaluator;
import ai.icen.fw.governance.api.GovernanceRetentionOutcome;
import ai.icen.fw.governance.api.GovernanceRetentionPolicyMode;
import ai.icen.fw.governance.api.GovernanceRetentionPolicySnapshot;
import ai.icen.fw.governance.api.GovernanceVersionFence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaGovernanceApiCompatibilityTest {
    private static final String SHA_A = repeat('a');
    private static final String SHA_B = repeat('b');
    private static final String SHA_C = repeat('c');
    private static final GovernancePrincipalRef PRINCIPAL = GovernancePrincipalRef.of("user", "user-7");
    private static final GovernanceResourceRef RESOURCE = GovernanceResourceRef.of(
        "document", "document-9", "revision-12", SHA_A
    );

    @Test
    void exposesJavaFriendlyGovernanceFactoriesAndPorts() throws Exception {
        GovernanceEffectiveClock clock = GovernanceEffectiveClock.of(
            "clock-1", "governance-clock", "clock-r1", 1_000L, 50_000L, 51_000L
        );
        GovernanceLegalHoldResolution clear = GovernanceLegalHoldResolution.clear(
            RESOURCE,
            "tenant-a",
            "hold-registry",
            "registry-r4",
            clock,
            Collections.emptyList(),
            31_000L
        );
        GovernanceRetentionPolicySnapshot policy = GovernanceRetentionPolicySnapshot.of(
            "tenant-a",
            RESOURCE,
            "records-policy",
            "policy-v4",
            SHA_B,
            GovernanceRetentionPolicyMode.RETAIN_UNTIL,
            0L,
            900L,
            41_000L,
            Long.valueOf(40_000L)
        );
        GovernanceRetentionEvaluationRequest evaluationRequest = GovernanceRetentionEvaluationRequest.of(
            context(GovernancePurpose.EVALUATE_RETENTION, "evaluate", 950L),
            GovernanceVersionFence.of(RESOURCE, 7L),
            policy,
            clear,
            clock
        );
        GovernanceRetentionEvaluator evaluator = GovernanceRetentionAssessment::evaluate;
        GovernanceRetentionAssessment assessment = evaluator.evaluate(evaluationRequest);
        assertEquals(GovernanceRetentionOutcome.ELIGIBLE_FOR_DELETION, assessment.getOutcome());

        GovernanceLegalHoldResolutionRequest holdRequest = GovernanceLegalHoldResolutionRequest.of(
            context(GovernancePurpose.RESOLVE_LEGAL_HOLD, "resolve-holds", 950L), RESOURCE, clock
        );
        GovernanceLegalHoldResolver holdResolver = current -> CompletableFuture.completedFuture(clear);
        assertSame(clear, holdResolver.resolve(holdRequest).toCompletableFuture().get());

        List<GovernanceDeletionStep> steps = new ArrayList<>();
        int sequence = 1;
        for (GovernanceDeletionStage stage : GovernanceDeletionPlan.REQUIRED_STAGE_ORDER) {
            steps.add(GovernanceDeletionStep.of(
                "step-" + sequence,
                sequence,
                stage,
                "target-" + sequence,
                "target-r1",
                repeat((char) ('0' + sequence)),
                "step-idempotency-" + sequence
            ));
            sequence++;
        }
        GovernanceDeletionPlan plan = GovernanceDeletionPlan.of(
            "deletion-plan-1",
            context(GovernancePurpose.PLAN_SECURE_DELETION, "plan", 2_000L),
            GovernanceVersionFence.of(RESOURCE, 7L),
            assessment,
            steps,
            false,
            2_050L,
            100_000L
        );
        assertFalse(plan.getDryRun());
        assertEquals(GovernanceDeletionPlan.REQUIRED_STAGE_ORDER, plan.getSteps().stream()
            .map(GovernanceDeletionStep::getStage).collect(java.util.stream.Collectors.toList()));

        GovernanceDeletionStepExecutor executor = current -> CompletableFuture.completedFuture(null);
        GovernanceDeletionReconciler reconciler = current -> CompletableFuture.completedFuture(null);
        assertTrue(executor != null && reconciler != null);

        GovernanceCapabilityRequest capabilityRequest = GovernanceCapabilityRequest.of(
            context(GovernancePurpose.DISCOVER_CAPABILITIES, "capability", 2_000L),
            Collections.singletonList(GovernanceCapability.RECONCILIATION)
        );
        GovernanceCapabilitySnapshot capabilitySnapshot = GovernanceCapabilitySnapshot.of(
            "governance-runtime",
            "runtime-r1",
            Arrays.asList(
                GovernanceCapability.RETENTION_EVALUATION,
                GovernanceCapability.LEGAL_HOLD_RESOLUTION,
                GovernanceCapability.RECONCILIATION
            ),
            256,
            16,
            2_050L,
            10_000L
        );
        GovernanceCapabilityResult capabilityResult = GovernanceCapabilityResult.available(
            capabilityRequest, capabilitySnapshot, 2_060L
        );
        GovernanceCapabilityProvider capabilityProvider = current ->
            CompletableFuture.completedFuture(capabilityResult);
        assertTrue(capabilityProvider.capabilities(capabilityRequest).toCompletableFuture().get()
            .getSnapshot().supports(GovernanceCapability.RECONCILIATION));

        GovernanceDoctorRequest doctorRequest = GovernanceDoctorRequest.of(
            context(GovernancePurpose.INSPECT_DOCTOR, "doctor", 2_000L),
            GovernanceDoctorMode.CONSISTENCY
        );
        GovernanceDoctorResult doctorResult = GovernanceDoctorResult.of(
            doctorRequest,
            GovernanceDoctorStatus.READY,
            Collections.singletonList(
                GovernanceDoctorFinding.of(
                    "deletion-ledger-consistent", GovernanceDoctorSeverity.INFO, 1L
                )
            ),
            2_050L,
            10_000L
        );
        GovernanceDoctor doctor = current -> CompletableFuture.completedFuture(doctorResult);
        assertSame(doctorResult, doctor.inspect(doctorRequest).toCompletableFuture().get());
    }

    @Test
    void publicContractsExposeNoRepositoryOrSecretBearingMethods() {
        Class<?>[] contracts = {
            GovernanceCallContext.class,
            GovernanceAuthorizationSnapshot.class,
            GovernanceDeletionPlan.class,
            GovernanceDeletionStep.class
        };
        for (Class<?> contract : contracts) {
            Arrays.stream(contract.getMethods()).forEach(method -> {
                String name = method.getName().toLowerCase();
                assertTrue(!name.contains("repository") && !name.contains("password") &&
                    !name.contains("privatekey") && !name.contains("rawtoken") &&
                    !name.contains("bearertoken") && !name.contains("secret"));
            });
        }
    }

    private static GovernanceCallContext context(GovernancePurpose purpose, String id, long now) {
        GovernanceAuthorizationSnapshot authorization = GovernanceAuthorizationSnapshot.of(
            "authorization-" + id,
            "tenant-a",
            PRINCIPAL,
            purpose,
            RESOURCE,
            "host-authorization",
            "authority-r4",
            "authorization-r8",
            SHA_C,
            now - 50L,
            now + 5_000L
        );
        return GovernanceCallContext.of(
            "request-" + id,
            "tenant-a",
            PRINCIPAL,
            purpose,
            authorization,
            "idempotency-" + id,
            now,
            now + 100L
        );
    }

    private static String repeat(char value) {
        char[] chars = new char[64];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
