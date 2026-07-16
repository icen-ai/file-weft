package ai.icen.fw.agent.runtime;

import ai.icen.fw.agent.api.AgentRemoteProtocolKind;
import ai.icen.fw.agent.api.ProviderId;
import ai.icen.fw.core.id.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentRemoteProtocolRuntimeJavaCompatibilityTest {

    @Test
    void javaCanUseRuntimeRegistriesConfigurationAndSafeDiagnostics() {
        AgentRemotePeerProfileRegistry profiles = (tenantId, peerId, protocol) -> null;
        AgentRemoteProtocolProviderRegistry providers = (peerId, protocol) -> null;
        AgentRemoteProtocolReconcilerRegistry reconcilers = (peerId, protocol) -> null;
        AgentRemoteAuthorizationProviderRegistry authorizers = providerId -> null;
        ProviderId peerId = new ProviderId("peer.java");

        assertNull(profiles.find(new Identifier("tenant-java"), peerId, AgentRemoteProtocolKind.MCP));
        assertNull(providers.find(peerId, AgentRemoteProtocolKind.MCP));
        assertNull(reconcilers.find(peerId, AgentRemoteProtocolKind.MCP));
        assertNull(authorizers.find(new ProviderId("authorization.java")));

        AgentRemoteProtocolRuntimeConfiguration configuration = new AgentRemoteProtocolRuntimeConfiguration();
        assertEquals(30_000L, configuration.getAuthorizationTtlMillis());
        AgentRemoteProtocolRuntimeException failure =
            new AgentRemoteProtocolRuntimeException("protocol.mcp-version-unsupported");
        assertEquals("protocol.mcp-version-unsupported", failure.getCode());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("tenant-secret"));

        AgentRemoteProtocolInvocationKey key = new AgentRemoteProtocolInvocationKey(
            new Identifier("tenant-secret"),
            new Identifier("invocation-secret")
        );
        assertFalse(key.toString().contains("tenant-secret"));
        assertFalse(key.toString().contains("invocation-secret"));
    }
}
