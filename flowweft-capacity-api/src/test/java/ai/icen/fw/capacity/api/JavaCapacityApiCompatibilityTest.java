package ai.icen.fw.capacity.api;

import ai.icen.fw.core.id.Identifier;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCapacityApiCompatibilityTest {
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

    private static Identifier id(String value) {
        return new Identifier(value);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
