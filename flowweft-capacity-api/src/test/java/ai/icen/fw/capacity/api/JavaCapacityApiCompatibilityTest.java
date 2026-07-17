package ai.icen.fw.capacity.api;

import ai.icen.fw.core.id.Identifier;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCapacityApiCompatibilityTest {
    private static final Identifier TENANT = id("tenant-1");
    private static final Identifier PROVIDER = id("provider-1");
    private static final Identifier RESOURCE = id("resource-1");
    private static final CapacityDegradationCapability DEFER =
        CapacityDegradationCapability.DEFER_SECONDARY_INDEXING;
    private static final CapacityDegradationCapability ASYNC =
        CapacityDegradationCapability.ASYNC_CONNECTOR_DELIVERY;
    private static final String DIGEST_A = repeat('a', 64);
    private static final String DIGEST_B = repeat('b', 64);
    private static final String DIGEST_C = repeat('c', 64);
    private static final String DIGEST_D = repeat('d', 64);

    @Test
    void javaCanUseProviderNeutralCapacityContracts() {
        Identifier tenant = id("tenant-java");
        Identifier provider = id("provider-java");
        ResourceScope target = ResourceScope.resource(
            tenant,
            "document",
            id("resource-java"),
            provider
        );
        CapacityTrustedContext context = CapacityTrustedContext.authenticated(
            tenant,
            id("principal-java"),
            "USER",
            id("request-java"),
            CapacityPurpose.OBSERVE,
            target,
            id("authentication-java"),
            id("authorization-java"),
            "auth-r1",
            repeat('a', 64),
            100L,
            1_000L
        );
        context.requireFresh(200L);
        assertEquals(64, context.getBindingDigest().length());

        CapacityLimit limit = new CapacityLimit(
            CapacityDimension.QUEUE_DEPTH,
            100L,
            70L,
            90L
        );
        CapacityPolicy policy = new CapacityPolicy(
            id("policy-java"),
            CapacityPolicy.CONTRACT_VERSION,
            "revision-1",
            1L,
            target,
            Collections.singleton(WorkloadKind.UPLOAD),
            Collections.singleton(limit),
            0L,
            900L
        );
        CapacityPolicyResolution resolution = CapacityPolicyResolution.resolve(
            target,
            WorkloadKind.UPLOAD,
            Collections.singleton(policy),
            200L
        );
        CapacityMeasureSnapshot measure = new CapacityMeasureSnapshot(
            resolution.limitFor(CapacityDimension.QUEUE_DEPTH),
            10L,
            5L
        );
        CapacityUsageSnapshot snapshot = CapacityUsageSnapshot.capture(
            provider,
            resolution,
            Collections.singleton(measure),
            3L,
            200L,
            800L
        );
        assertEquals(CapacityPressureLevel.NORMAL, measure.getPressure());
        assertEquals(64, snapshot.getSnapshotDigest().length());

        CapacitySnapshotRequest request = new CapacitySnapshotRequest(
            id("snapshot-java"),
            context,
            target,
            WorkloadKind.UPLOAD,
            200L,
            400L
        );
        assertFalse(request.toString().contains("tenant-java"));
        CapacityProviderResult<CapacityUsageSnapshot> result = CapacityProviderResult.success(snapshot);
        assertNotNull(result.getValue());
        assertFalse(result.getReplayed());

        CapacityProviderDescriptor descriptor = new CapacityProviderDescriptor(
            provider,
            "capacity.provider.v1",
            Arrays.asList(
                CapacityProviderCapability.ATOMIC_ADMISSION,
                CapacityProviderCapability.HIERARCHICAL_POLICIES,
                CapacityProviderCapability.FENCED_LEASES,
                CapacityProviderCapability.USAGE_SNAPSHOTS
            ),
            repeat('b', 64),
            100L,
            900L
        );
        assertTrue(descriptor.isCurrent(200L));
        assertEquals("custom.queue", new WorkloadKind("custom.queue").getValue());
    }

    @Test
    void javaAtomicAdmissionOutcomesCannotHideRetryReservationOrSecurityDegradation() {
        CapacityPolicyResolution resolution = resolution();
        CapacityAdmissionRequest request = admissionRequest(resolution);
        CapacityReservationLease lease = CapacityReservationLease.issue(
            id("reservation-1"),
            id("lease-1"),
            PROVIDER,
            request,
            1L,
            6L,
            300L,
            600L
        );
        CapacityUsageSnapshot admittedUsage = usage(resolution, 6L, 300L, 20L, 10L);
        CapacityAdmissionDecision admitted = CapacityAdmissionDecision.admit(
            id("decision-admit"),
            PROVIDER,
            request,
            admittedUsage,
            lease,
            300L,
            550L
        );
        assertEquals(CapacityAdmissionOutcome.ADMIT, admitted.getOutcome());
        assertEquals(lease.getLeaseDigest(), admitted.getLease().getLeaseDigest());

        CapacityAdmissionDecision degraded = CapacityAdmissionDecision.degrade(
            id("decision-degrade"),
            PROVIDER,
            request,
            admittedUsage,
            lease,
            Collections.singleton(DEFER),
            CapacityDecisionReason.WATERMARK_PRESSURE,
            300L,
            550L
        );
        assertEquals(Collections.singleton(DEFER), degraded.getDegradationCapabilities());

        assertThrows(IllegalArgumentException.class, () -> CapacityAdmissionDecision.degrade(
            id("decision-unsafe"),
            PROVIDER,
            request,
            admittedUsage,
            lease,
            Collections.singleton(ASYNC),
            CapacityDecisionReason.WATERMARK_PRESSURE,
            300L,
            550L
        ));
        assertThrows(IllegalArgumentException.class, () -> CapacityAdmissionDecision.admit(
            id("decision-outlives-lease"),
            PROVIDER,
            request,
            admittedUsage,
            lease,
            300L,
            650L
        ));

        CapacityUsageSnapshot unchangedUsage = usage(resolution, 5L, 300L, 50L, 0L);
        CapacityAdmissionDecision throttled = CapacityAdmissionDecision.throttle(
            id("decision-throttle"),
            PROVIDER,
            request,
            unchangedUsage,
            250L,
            CapacityDecisionReason.WATERMARK_PRESSURE,
            300L,
            650L
        );
        assertEquals(250L, throttled.getRetryAfterMillis());
        assertNull(throttled.getLease());
        CapacityAdmissionDecision rejected = CapacityAdmissionDecision.reject(
            id("decision-reject"),
            PROVIDER,
            request,
            unchangedUsage,
            CapacityDecisionReason.LIMIT_EXCEEDED,
            300L,
            650L
        );
        assertNull(rejected.getRetryAfterMillis());
    }

    @Test
    void javaReservationRenewalAndReleaseArePrincipalScopedFencedAndCasBound() {
        CapacityPolicyResolution resolution = resolution();
        CapacityAdmissionRequest admission = admissionRequest(resolution);
        CapacityReservationLease lease = CapacityReservationLease.issue(
            id("reservation-lease"),
            id("lease-current"),
            PROVIDER,
            admission,
            10L,
            6L,
            300L,
            600L
        );
        CapacityTrustedContext leaseContext = context(CapacityPurpose.LEASE, "lease-request");
        CapacityLeaseRenewalRequest renewalRequest = new CapacityLeaseRenewalRequest(
            id("renew-operation"),
            leaseContext,
            lease,
            CapacityWritePrecondition.renewal(DIGEST_B, 6L, resolution.getResolutionDigest()),
            900L,
            350L,
            500L
        );
        CapacityReservationLease renewed = CapacityReservationLease.renewed(
            renewalRequest,
            resolution.getResolutionDigest(),
            11L,
            7L,
            400L,
            800L
        );
        CapacityUsageSnapshot renewedUsage = usage(resolution, 7L, 400L, 20L, 10L);
        CapacityLeaseRenewalReceipt renewalReceipt = new CapacityLeaseRenewalReceipt(
            id("renew-receipt"),
            PROVIDER,
            renewalRequest,
            renewed,
            renewedUsage,
            400L
        );
        assertEquals(11L, renewalReceipt.getRenewedLease().getFencingToken());

        CapacityLeaseReleaseRequest releaseRequest = new CapacityLeaseReleaseRequest(
            id("release-operation"),
            leaseContext,
            renewed,
            CapacityWritePrecondition.release(DIGEST_C, 7L),
            "completed",
            420L,
            500L
        );
        CapacityUsageSnapshot releasedUsage = usage(resolution, 8L, 430L, 20L, 0L);
        CapacityLeaseReleaseReceipt releaseReceipt = new CapacityLeaseReleaseReceipt(
            id("release-receipt"),
            PROVIDER,
            releaseRequest,
            releasedUsage,
            8L,
            430L
        );
        assertEquals(8L, releaseReceipt.getReleasedStateVersion());

        assertThrows(IllegalArgumentException.class, () -> new CapacityLeaseRenewalRequest(
            id("cross-principal-renew"),
            context(CapacityPurpose.LEASE, "principal-2", "principal-2"),
            renewed,
            CapacityWritePrecondition.renewal(DIGEST_D, 7L, resolution.getResolutionDigest()),
            950L,
            450L,
            550L
        ));
    }

    private static CapacityPolicyResolution resolution() {
        ResourceScope target = target();
        return CapacityPolicyResolution.resolve(
            target,
            WorkloadKind.UPLOAD,
            Arrays.asList(
                policy("system", ResourceScope.system(), 100L, 40L, 80L),
                policy("tenant", ResourceScope.tenant(TENANT), 80L, 30L, 70L),
                policy("provider", ResourceScope.provider(TENANT, PROVIDER), 60L, 25L, 55L),
                policy("resource", target, 70L, 20L, 50L)
            ),
            200L
        );
    }

    private static CapacityAdmissionRequest admissionRequest(CapacityPolicyResolution resolution) {
        return admissionRequest(resolution, context(CapacityPurpose.ADMISSION, "request-1"));
    }

    private static CapacityAdmissionRequest admissionRequest(
        CapacityPolicyResolution resolution,
        CapacityTrustedContext context
    ) {
        return new CapacityAdmissionRequest(
            id("admission-operation"),
            context,
            target(),
            WorkloadKind.UPLOAD,
            Collections.singletonList(new CapacityDemand(CapacityDimension.QUEUE_DEPTH, 10L)),
            Collections.singleton(DEFER),
            CapacityWritePrecondition.admission(DIGEST_A, 5L, resolution.getResolutionDigest()),
            250L,
            700L
        );
    }

    private static CapacityUsageSnapshot usage(
        CapacityPolicyResolution resolution,
        long stateVersion,
        long observedAt,
        long used,
        long reserved
    ) {
        return CapacityUsageSnapshot.capture(
            PROVIDER,
            resolution,
            Collections.singletonList(
                new CapacityMeasureSnapshot(resolution.limitFor(CapacityDimension.QUEUE_DEPTH), used, reserved)
            ),
            stateVersion,
            observedAt,
            750L
        );
    }

    private static CapacityPolicy policy(
        String name,
        ResourceScope scope,
        long limit,
        long warning,
        long critical
    ) {
        return new CapacityPolicy(
            id("policy-" + name),
            CapacityPolicy.CONTRACT_VERSION,
            "revision-" + name,
            1L,
            scope,
            Collections.singleton(WorkloadKind.UPLOAD),
            Collections.singleton(
                new CapacityLimit(CapacityDimension.QUEUE_DEPTH, limit, warning, critical)
            ),
            0L,
            1_000L,
            Collections.singleton(DEFER),
            true
        );
    }

    private static CapacityTrustedContext context(CapacityPurpose purpose, String requestId) {
        return context(purpose, "principal-1", requestId);
    }

    private static CapacityTrustedContext context(CapacityPurpose purpose, String principalId, String requestId) {
        return CapacityTrustedContext.authenticated(
            TENANT,
            id(principalId),
            "USER",
            id(requestId),
            purpose,
            target(),
            id("authentication-1"),
            id("authorization-decision-1"),
            "auth-r1",
            DIGEST_D,
            100L,
            1_000L
        );
    }

    private static ResourceScope target() {
        return ResourceScope.resource(TENANT, "document", RESOURCE, PROVIDER);
    }

    private static Identifier id(String value) {
        return new Identifier(value);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
