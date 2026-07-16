package ai.icen.fw.observability;

import ai.icen.fw.core.id.Identifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SystemDoctorJavaCompatibilityTest {
    private static final String DIGEST =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

    @Test
    void exposesJava8FriendlyTrustedRequestsProbesAndReadiness() {
        SystemDoctorRequest request = new SystemDoctorRequest(
                new Identifier("java-request"),
                SystemDoctorScope.TENANT,
                new Identifier("java-tenant"),
                new Identifier("java-principal"),
                "human",
                "java-revision",
                10L,
                1_000L
        );
        SystemDoctorTopology topology = topology();
        SystemDoctorAuthorizationPort authorization = ignored -> new SystemDoctorAuthorization(
                true,
                request.getBindingDigest(),
                request.getScope(),
                request.getTenantId(),
                request.getPrincipalId(),
                request.getPrincipalType(),
                request.getAuthorizationRevision(),
                10L,
                1_000L
        );
        SystemDoctorProbeRegistry registry = (capability, probeId) -> {
            if (capability != SystemDoctorCapability.DATABASE) {
                return null;
            }
            return probeRequest -> new SystemDoctorProbeResult(
                    probeRequest.getProbeBindingDigest(),
                    probeRequest.getCapability(),
                    SystemDoctorProbeState.HEALTHY,
                    probeRequest.getContractVersion(),
                    probeRequest.getConfigurationDigest(),
                    20L,
                    Collections.singletonList(new SystemDoctorProbeSignal(
                            SystemDoctorSeverity.HEALTHY,
                            SystemDoctorCode.DATABASE_AVAILABLE,
                            1L,
                            SystemDoctorBucket.AVAILABLE,
                            SystemDoctorRepairAction.NONE
                    )),
                    false
            );
        };
        ProductionSystemDoctor doctor = new ProductionSystemDoctor(
                authorization,
                topology,
                registry,
                SystemDoctorProbeExecutionPort.DIRECT,
                SystemDoctorObservationSink.NOOP,
                () -> 20L
        );

        SystemDoctorReport report = doctor.inspectTenant(request);

        assertEquals(SystemDoctorReadiness.READY, report.getReadiness());
        assertEquals(1, report.getHealthyRequiredProbeCount());
        assertNotNull(report.getFindings());
    }

    private static SystemDoctorTopology topology() {
        List<SystemDoctorProbeRequirement> requirements = new ArrayList<>();
        for (SystemDoctorCapability capability : SystemDoctorCapability.values()) {
            requirements.add(new SystemDoctorProbeRequirement(
                    capability,
                    capability.name().toLowerCase().replace('_', '-'),
                    capability == SystemDoctorCapability.DATABASE,
                    "v1",
                    DIGEST,
                    100L,
                    1_000L
            ));
        }
        return new SystemDoctorTopology(requirements);
    }
}
