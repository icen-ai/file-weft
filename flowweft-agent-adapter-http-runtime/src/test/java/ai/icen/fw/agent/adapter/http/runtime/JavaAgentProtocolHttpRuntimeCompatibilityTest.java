package ai.icen.fw.agent.adapter.http.runtime;

import ai.icen.fw.agent.api.AgentRemoteProtocolKind;
import ai.icen.fw.agent.api.AgentRemoteProtocolProvider;
import ai.icen.fw.agent.api.ProviderId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaAgentProtocolHttpRuntimeCompatibilityTest {
    @Test
    void publicRuntimeContractsRemainJava8FriendlyAndPayloadFree() throws Exception {
        AgentProtocolHttpRuntimeConfiguration configuration =
            new AgentProtocolHttpRuntimeConfiguration("flowweft", "1.0", "profile-1", 60_000L, null);
        AgentProtocolHttpRuntimeException failure = new AgentProtocolHttpRuntimeException(
            "protocol.http.rejected-before-request",
            AgentProtocolHttpRuntimeFailurePhase.TRANSPORT_BEFORE_REQUEST,
            false
        );
        AgentProtocolHttpRuntimeDiagnostic diagnostic = new AgentProtocolHttpRuntimeDiagnostic(
            new ProviderId("protocol.http"),
            new ProviderId("peer.http"),
            AgentRemoteProtocolKind.MCP,
            "protocol.initialize",
            "protocol.http.succeeded",
            AgentProtocolHttpRuntimeDiagnosticOutcome.SUCCEEDED,
            4L,
            10L
        );
        AgentProtocolHttpRuntimeDiagnostic a2aReadDiagnostic = new AgentProtocolHttpRuntimeDiagnostic(
            new ProviderId("protocol.http"),
            new ProviderId("peer.a2a"),
            AgentRemoteProtocolKind.A2A,
            "protocol.a2a-list-tasks",
            "protocol.http.succeeded",
            AgentProtocolHttpRuntimeDiagnosticOutcome.SUCCEEDED,
            3L,
            10L
        );

        assertNotNull(configuration.getConfigurationDigest());
        assertEquals("protocol.http.rejected-before-request", failure.getCode());
        assertFalse(failure.getRequestMayHaveReachedPeer());
        assertFalse(diagnostic.toString().contains("https://"));
        assertEquals("protocol.a2a-list-tasks", a2aReadDiagnostic.getOperation());
        assertNotNull(AgentProtocolHttpRuntimeIdSource.Companion.randomUuid().nextId("agent-http-result"));
        assertEquals("agent.http.session.stateless", AgentProtocolMcpSessionStore.Companion.stateless().storeId().getValue());
        assertTrue(AgentRemoteProtocolProvider.class.isAssignableFrom(AgentProtocolHttpRuntimeProvider.class));
        assertTrue(AgentProtocolHttpRuntimeDiagnosticSink.NONE != null);
    }
}
