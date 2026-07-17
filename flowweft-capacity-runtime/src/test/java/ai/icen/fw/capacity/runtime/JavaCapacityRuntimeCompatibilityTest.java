package ai.icen.fw.capacity.runtime;

import ai.icen.fw.capacity.api.CapacityPurpose;
import ai.icen.fw.capacity.api.ResourceScope;
import ai.icen.fw.capacity.api.WorkloadKind;
import ai.icen.fw.core.id.Identifier;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaCapacityRuntimeCompatibilityTest {
    @Test
    void javaCanComposeServicesAndReceivesExplicitFailClosedResult() {
        Identifier tenant = new Identifier("tenant-java");
        Identifier provider = new Identifier("provider-java");
        ResourceScope target = ResourceScope.resource(
            tenant,
            "document",
            new Identifier("resource-java"),
            provider
        );
        CapacityRuntime runtime = new CapacityRuntime(
            purpose -> null,
            new FixedCapacityPolicySource(Collections.emptyList()),
            new ImmutableCapacityProviderRegistry(Collections.emptyMap()),
            request -> {
                throw new IllegalStateException("must not be reached");
            },
            CapacityExternalCallBoundary.UNMANAGED_NON_TRANSACTIONAL
        );

        CapacityRuntimeResult<CapacityObservationReceipt> result = runtime.observation.observe(
            new CapacityObserveCommand(
                provider,
                target,
                new WorkloadKind("custom.java"),
                100L
            )
        );

        assertFalse(result.isSuccess());
        assertEquals(CapacityRuntimeErrorCode.UNAUTHENTICATED, result.getErrorCode());
        assertNull(result.getUnknownOutcomeReference());
        assertEquals(CapacityPurpose.OBSERVE.getValue(), "capacity.observe");
    }
}
