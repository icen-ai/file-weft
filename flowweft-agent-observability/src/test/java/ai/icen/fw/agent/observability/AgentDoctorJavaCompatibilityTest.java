package ai.icen.fw.agent.observability;

import ai.icen.fw.agent.api.ProviderId;
import ai.icen.fw.core.id.Identifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentDoctorJavaCompatibilityTest {

    @Test
    void javaCanImplementProductionProbePortsAndConsumeSafeReport() throws Exception {
        AgentDoctorRequest request = new AgentDoctorRequest(
            new Identifier("request-1"),
            AgentDoctorScope.TENANT,
            new Identifier("tenant-1"),
            new Identifier("principal-1"),
            "USER",
            "authorization-v1",
            100L,
            1_000L
        );
        ProviderId providerId = new ProviderId("provider.model");
        String descriptor = sha256("descriptor");
        String capability = sha256("capability");
        String configuration = sha256("configuration");
        AgentProviderDiagnosticExpectation expectation = new AgentProviderDiagnosticExpectation(
            providerId,
            AgentProviderKind.MODEL,
            descriptor,
            capability,
            configuration
        );
        AgentDoctorAuthorizationPort authorization = diagnostic -> new AgentDoctorAuthorization(
            true,
            diagnostic.getBindingDigest(),
            diagnostic.getScope(),
            diagnostic.getTenantId(),
            diagnostic.getPrincipalId(),
            diagnostic.getPrincipalType(),
            diagnostic.getAuthorizationRevision(),
            90L,
            1_100L
        );
        AgentProviderTopologyPort topology = diagnostic -> new AgentProviderTopologySnapshot(
            diagnostic.getRequestBindingDigest(),
            Collections.singletonList(expectation),
            Arrays.asList(AgentProviderKind.values())
        );
        AgentProviderDiagnosticProbeRegistry providers = (kind, selectedProvider) -> diagnostic ->
            new AgentProviderDiagnosticProbeResult(
                diagnostic.getRequestBindingDigest(),
                selectedProvider,
                kind,
                AgentProviderProbeState.AVAILABLE,
                120L,
                descriptor,
                capability,
                configuration
            );
        AgentDurableDiagnosticProbeRegistry durable = workload -> diagnostic ->
            new AgentDurableDiagnosticSnapshot(
                diagnostic.getRequestBindingDigest(),
                workload,
                AgentDoctorWindow.RECENT_1_HOUR,
                120L
            );
        ProductionAgentDoctor doctor = new ProductionAgentDoctor(
            authorization,
            topology,
            providers,
            durable,
            new AgentDoctorPolicy(),
            AgentDoctorObservationSink.NOOP,
            () -> 120L
        );

        AgentDoctorReport report = doctor.diagnose(request);

        assertEquals(AgentDoctorStatus.HEALTHY, report.getStatus());
        assertFalse(report.toString().contains("tenant-1"));
        assertEquals(0L, report.count(AgentDoctorStatus.ERROR));
    }

    private static String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder digest = new StringBuilder();
        for (byte item : bytes) {
            digest.append(String.format("%02x", item & 0xff));
        }
        return digest.toString();
    }
}
