package ai.icen.fw.governance.consumer;

import ai.icen.fw.governance.api.GovernanceAuthorizationSnapshot;
import ai.icen.fw.governance.api.GovernanceEffectiveClock;
import ai.icen.fw.governance.api.GovernancePrincipalRef;
import ai.icen.fw.governance.api.GovernancePurpose;
import ai.icen.fw.governance.api.GovernanceResourceRef;
import ai.icen.fw.governance.api.GovernanceVersionFence;
import ai.icen.fw.governance.runtime.GovernanceAuthorizedCallFactory;
import ai.icen.fw.governance.runtime.GovernanceClockObservationRequest;
import ai.icen.fw.governance.runtime.GovernanceDeletionPlanCommand;
import ai.icen.fw.governance.runtime.GovernanceDeletionProviderRegistry;
import ai.icen.fw.governance.runtime.GovernanceMetricsPort;
import ai.icen.fw.governance.runtime.GovernanceRuntimeAuthorizationPort;
import ai.icen.fw.governance.runtime.GovernanceRuntimeClockPort;
import ai.icen.fw.governance.runtime.GovernanceRuntimeIdPort;
import ai.icen.fw.governance.runtime.GovernanceTrustedInvocation;
import ai.icen.fw.governance.runtime.GovernanceWorkerSignalPort;
import ai.icen.fw.governance.runtime.ProviderNeutralGovernanceCapabilityProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
