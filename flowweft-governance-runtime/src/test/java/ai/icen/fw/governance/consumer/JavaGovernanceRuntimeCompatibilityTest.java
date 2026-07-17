package ai.icen.fw.governance.consumer;

import ai.icen.fw.governance.api.GovernanceAuthorizationSnapshot;
import ai.icen.fw.governance.api.GovernanceCallContext;
import ai.icen.fw.governance.api.GovernanceDeletionStage;
import ai.icen.fw.governance.api.GovernanceEffectiveClock;
import ai.icen.fw.governance.api.GovernanceFailure;
import ai.icen.fw.governance.api.GovernanceFailureClass;
import ai.icen.fw.governance.api.GovernancePrincipalRef;
import ai.icen.fw.governance.api.GovernancePurpose;
import ai.icen.fw.governance.api.GovernanceResourceRef;
import ai.icen.fw.governance.api.GovernanceVersionFence;
import ai.icen.fw.governance.runtime.GovernanceAuthorizedCallFactory;
import ai.icen.fw.governance.runtime.GovernanceClockObservationRequest;
import ai.icen.fw.governance.runtime.GovernanceDeletionPlanCommand;
import ai.icen.fw.governance.runtime.GovernanceDeletionProviderRegistry;
import ai.icen.fw.governance.runtime.GovernanceDeletionTarget;
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetExecutionBinding;
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItem;
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemKind;
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOperation;
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOperationStatus;
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOutcome;
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetManifest;
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetRequest;
import ai.icen.fw.governance.runtime.GovernanceMetricsPort;
import ai.icen.fw.governance.runtime.GovernanceRuntimeAuthorizationPort;
import ai.icen.fw.governance.runtime.GovernanceRuntimeClockPort;
import ai.icen.fw.governance.runtime.GovernanceRuntimeIdPort;
import ai.icen.fw.governance.runtime.GovernanceTrustedInvocation;
import ai.icen.fw.governance.runtime.GovernanceWorkerSignalPort;
import ai.icen.fw.governance.runtime.ProviderNeutralGovernanceCapabilityProvider;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaGovernanceRuntimeCompatibilityTest {
    private static final String DIGEST =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void publicRuntimePortsAndFactoriesRemainUsableFromPureJava() {
        GovernancePrincipalRef principal = GovernancePrincipalRef.of("user", "user-7");
        GovernanceResourceRef resource = GovernanceResourceRef.of("document", "document-9", "r1", DIGEST);
        GovernanceTrustedInvocation invocation = GovernanceTrustedInvocation.of(
            "tenant-a",
            principal,
            GovernancePurpose.PLAN_SECURE_DELETION,
            resource,
            "delete-document-9",
            1_000L,
            1_100L
        );
        GovernanceRuntimeIdPort identifiers = request ->
            request.getKind().getCode() + "-" + request.getOrdinal();
        GovernanceRuntimeAuthorizationPort authorization = request -> GovernanceAuthorizationSnapshot.of(
            "authorization-" + request.getRequestId(),
            request.getInvocation().getTenantId(),
            request.getInvocation().getPrincipal(),
            request.getPurpose(),
            request.getInvocation().getResource(),
            "host-authorization",
            "authority-r1",
            "authorization-r1",
            DIGEST,
            990L,
            2_000L
        );
        GovernanceAuthorizedCallFactory calls = new GovernanceAuthorizedCallFactory(authorization, identifiers);

        assertEquals(
            GovernancePurpose.PLAN_SECURE_DELETION,
            calls.create(invocation, GovernancePurpose.PLAN_SECURE_DELETION, "java-plan").getPurpose()
        );
        GovernanceDeletionPlanCommand command = GovernanceDeletionPlanCommand.of(
            invocation,
            GovernanceVersionFence.of(resource, 7L),
            false
        );
        assertFalse(command.getDryRun());
        GovernanceDeletionTargetItem targetItem = GovernanceDeletionTargetItem.of(
            1,
            GovernanceDeletionTargetItemKind.OBJECT_CONTENT,
            "file-object-1",
            "storage-r1",
            DIGEST,
            "storage-provider",
            "provider-r1"
        );
        assertNotNull(targetItem.getItemIdentityDigest());
        assertNotNull(targetItem.getItemBindingDigest());

        GovernanceRuntimeClockPort clock = new GovernanceRuntimeClockPort() {
            @Override
            public long nowEpochMilli() {
                return 1_000L;
            }

            @Override
            public GovernanceEffectiveClock observe(GovernanceClockObservationRequest request) {
                return GovernanceEffectiveClock.of(
                    "clock-1",
                    "host-clock",
                    "clock-r1",
                    request.getObservedAtEpochMilli(),
                    request.getObservedAtEpochMilli(),
                    request.getRequiredUntilEpochMilli() + 1_000L
                );
            }
        };
        GovernanceDeletionProviderRegistry providers = stage -> null;
        GovernanceMetricsPort metrics = metric -> { };
        GovernanceWorkerSignalPort workers = record -> CompletableFuture.completedFuture(null);

        assertNotNull(new ProviderNeutralGovernanceCapabilityProvider(
            clock,
            providers,
            "governance-runtime",
            "runtime-r1",
            256,
            60_000L
        ));
        assertNotNull(metrics);
        assertNotNull(workers);
    }

    /**
     * P0.1 public type surface added in commit db464e7 must remain reachable from pure Java.
     *
     * <p>The companion factories that consume only public API inputs are exercised end to end
     * (manifest {@code of()} plus its {@code calculateTargetDigest} / {@code calculatePlanningIdentityDigest}
     * / {@code calculatePreparationDigest} @JvmStatic helpers, and the operation-status enum). The
     * {@code rehydrate()} factories on {@link GovernanceDeletionTargetExecutionBinding},
     * {@link GovernanceDeletionTargetItemOperation}, {@link GovernanceDeletionTargetItemOutcome} and
     * {@link GovernanceDeletionTargetManifest} cannot be driven from this Java test: their
     * {@code expected*Digest} arguments must reproduce values produced by
     * {@code GovernanceRuntimeSupport.digest(...)} (see GovernanceDeletionTargetLedgerTest.kt's
     * rehydrateOperation/executionBinding helpers), and that support object is package-private to
     * {@code ai.icen.fw.governance.runtime}, so it is intentionally not reachable from this
     * {@code ai.icen.fw.governance.consumer} test package. The public surface of those types is
     * therefore covered via their public compute helpers and enum constants instead.
     */
    @Test
    void javaCanConstructDeletionTargetLedgerContracts() {
        // 1. GovernanceDeletionTargetItem (already covered above but reused as manifest input).
        GovernanceDeletionTargetItem targetItem = GovernanceDeletionTargetItem.of(
            1,
            GovernanceDeletionTargetItemKind.OBJECT_CONTENT,
            "file-object-1",
            "storage-r1",
            DIGEST,
            "storage-provider",
            "provider-r1"
        );
        assertNotNull(targetItem.getItemIdentityDigest());
        assertNotNull(targetItem.getItemBindingDigest());
        assertEquals(1, targetItem.getOrdinal());
        assertEquals(GovernanceDeletionTargetItemKind.OBJECT_CONTENT, targetItem.getKind());

        // 2. GovernanceDeletionTargetRequest via its public of() factory (mirrors the Kotlin
        //    planningRequest helper), using only public API inputs.
        GovernancePrincipalRef principal = GovernancePrincipalRef.of("user", "user-a");
        GovernanceResourceRef resource = GovernanceResourceRef.of("document", "document-1", "resource-r1", DIGEST);
        GovernancePurpose purpose = GovernancePurpose.PLAN_SECURE_DELETION;
        GovernanceAuthorizationSnapshot authorization = GovernanceAuthorizationSnapshot.of(
            "authorization-a",
            "tenant-a",
            principal,
            purpose,
            resource,
            "host-authorization",
            "authority-r1",
            "authorization-r1",
            DIGEST,
            900L,
            2_000L
        );
        GovernanceCallContext context = GovernanceCallContext.of(
            "request-a",
            "tenant-a",
            principal,
            purpose,
            authorization,
            "shared-idempotency-key",
            1_000L,
            1_100L
        );
        GovernanceDeletionTargetRequest planningRequest = GovernanceDeletionTargetRequest.of(
            context, DIGEST
        );
        assertNotNull(planningRequest.getRequestDigest());
        assertEquals(DIGEST, planningRequest.getAssessmentDigest());

        // 3. GovernanceDeletionTarget via its public of() factory, with a canonical targetDigest
        //    computed through the public @JvmStatic helper so the manifest's canonical check passes.
        GovernanceDeletionStage stage = GovernanceDeletionStage.PURGE_OBJECT_CONTENT;
        String targetRevision = "target-r1";
        String targetDigest = GovernanceDeletionTargetManifest.calculateTargetDigest(
            stage, targetRevision, Collections.singletonList(targetItem)
        );
        GovernanceDeletionTarget target = GovernanceDeletionTarget.of(
            stage, "target-object-1", targetRevision, targetDigest
        );
        assertNotNull(target.getTargetBindingDigest());

        // 4. GovernanceDeletionTargetManifest via its public of() factory. All canonical digests
        //    (targetDigest, preparationDigest, manifestDigest) are computed internally and exposed
        //    through getters; the public @JvmStatic planning/preparation helpers are exercised too.
        GovernanceDeletionTargetManifest manifest = GovernanceDeletionTargetManifest.of(
            planningRequest, target, Collections.singletonList(targetItem)
        );
        assertEquals(stage, manifest.getStage());
        assertEquals(1, manifest.getItems().size());
        assertEquals(targetItem.getItemBindingDigest(), manifest.getItems().get(0).getItemBindingDigest());
        assertEquals("tenant-a", manifest.getTenantId());
        assertEquals(target.getTargetRef(), manifest.getTargetRef());
        assertEquals(targetDigest, manifest.getTargetDigest());
        assertEquals(1_000L, manifest.getCreatedAtEpochMilli());

        String planningIdentityDigest = GovernanceDeletionTargetManifest.calculatePlanningIdentityDigest(
            planningRequest
        );
        assertEquals(planningIdentityDigest, manifest.getPlanningIdentityDigest());
        String preparationDigest = GovernanceDeletionTargetManifest.calculatePreparationDigest(
            planningIdentityDigest, stage
        );
        assertEquals(preparationDigest, manifest.getPreparationDigest());
        assertNotNull(manifest.getManifestDigest());
        assertEquals(64, manifest.getManifestDigest().length());
        assertNotEquals(manifest.getTargetBindingDigest(), manifest.getManifestDigest());

        // asTarget() projects the same opaque target identity back out of the manifest.
        GovernanceDeletionTarget manifestTarget = manifest.asTarget();
        assertEquals(target.getTargetDigest(), manifestTarget.getTargetDigest());
        assertEquals(stage, manifestTarget.getStage());

        // 5. GovernanceDeletionTargetItemOperationStatus enum surface (@JvmField constants plus the
        //    of() factory). The Operation/Outcome/ExecutionBinding concrete instances require either a
        //    full GovernanceDeletionExecutionRequest chain (prepared()) or an exact rehydrate() digest
        //    produced by the package-private GovernanceRuntimeSupport; both are out of reach here, so
        //    their public status surface is verified through the enum and factory instead.
        assertSameStatus(GovernanceDeletionTargetItemOperationStatus.PREPARED, "prepared");
        assertSameStatus(GovernanceDeletionTargetItemOperationStatus.STARTED, "started");
        assertSameStatus(
            GovernanceDeletionTargetItemOperationStatus.VERIFIED_ABSENT, "verified-absent");
        assertSameStatus(
            GovernanceDeletionTargetItemOperationStatus.OUTCOME_UNKNOWN, "outcome-unknown");
        assertSameStatus(
            GovernanceDeletionTargetItemOperationStatus.PERMANENT_FAILURE, "permanent-failure");

        // 6. GovernanceFailure remains constructible from Java via its public of() factory; it is the
        //    payload expected by the outcomeUnknown / permanentFailure factories above.
        GovernanceFailure permanent = GovernanceFailure.of(
            GovernanceFailureClass.PERMANENT_FAILURE, "provider-permanent", false, false
        );
        assertNotNull(permanent.getFailureDigest());
        GovernanceFailure unknown = GovernanceFailure.of(
            GovernanceFailureClass.OUTCOME_UNKNOWN, "provider-unknown", false, true
        );
        assertTrue(unknown.getReconciliationRequired());
        assertFalse(unknown.getRetryable());
        assertFalse(permanent.getReconciliationRequired());
        assertEquals(GovernanceFailureClass.PERMANENT_FAILURE, permanent.getClassification());
        assertEquals(GovernanceFailureClass.OUTCOME_UNKNOWN, unknown.getClassification());

        // Reference the public execution-binding / operation / outcome types so the compiler proves
        // they remain on the public surface even though their value constructors need a digest or a
        // full execution request that this Java package cannot reproduce.
        Class<?>[] publicTypes = new Class<?>[] {
            GovernanceDeletionTargetExecutionBinding.class,
            GovernanceDeletionTargetItemOperation.class,
            GovernanceDeletionTargetItemOutcome.class,
        };
        for (Class<?> type : publicTypes) {
            assertNotNull(type, "Public deletion target type must remain on the runtime surface");
        }
    }

    private static void assertSameStatus(
        GovernanceDeletionTargetItemOperationStatus expected, String code
    ) {
        assertEquals(expected, GovernanceDeletionTargetItemOperationStatus.of(code));
        assertEquals(code, expected.getCode());
    }
}
