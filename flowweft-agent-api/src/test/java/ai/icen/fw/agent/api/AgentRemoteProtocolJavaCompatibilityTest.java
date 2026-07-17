package ai.icen.fw.agent.api;

import ai.icen.fw.core.id.Identifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentRemoteProtocolJavaCompatibilityTest {

    @Test
    void javaCanImplementProviderAndUseFrozenProtocolContracts() throws Exception {
        ProviderId peerId = new ProviderId("peer.mcp.java");
        ProviderId transportId = new ProviderId("transport.mcp.java");
        ProviderId reconcilerId = new ProviderId("reconciler.mcp.java");
        URI endpoint = URI.create("https://mcp-java.example/protocol");
        AgentRemoteCredentialBinding credential = new AgentRemoteCredentialBinding(
            new Identifier("credential-java"),
            peerId,
            AgentRemoteAuthenticationScheme.OAUTH2_BEARER,
            endpoint,
            Collections.singletonList("mcp.invoke"),
            "revision-1"
        );
        AgentRemoteToolBinding tool = new AgentRemoteToolBinding(
            peerId,
            new ToolId("document.read"),
            digest("tool-java")
        );
        AgentRemotePeerProfile profile = new AgentRemotePeerProfile(
            peerId,
            AgentRemoteProtocolKind.MCP,
            AgentRemoteProtocolBaselines.MCP_2025_11_25,
            AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP,
            transportId,
            reconcilerId,
            endpoint,
            "server-v1",
            digest("descriptor-java"),
            Collections.singletonList(AgentRemoteProtocolCapabilities.MCP_TOOL_CALL),
            digest("security-java"),
            digest("tls-java"),
            credential,
            "profile-1",
            0,
            Collections.singletonList(tool)
        );
        AgentRemotePeerObservation observation = new AgentRemotePeerObservation(
            peerId,
            AgentRemoteProtocolKind.MCP,
            AgentRemoteProtocolBaselines.MCP_2025_11_25,
            AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP,
            "server-v1",
            profile.getDescriptorDigest(),
            profile.getCapabilityDigest(),
            profile.getToolCatalogDigest(),
            profile.getSecuritySchemeDigest(),
            100L
        );
        observation.requireMatches(profile);

        AgentRemoteProtocolProvider provider = new AgentRemoteProtocolProvider() {
            @Override public ProviderId providerId() { return transportId; }
            @Override public ProviderId peerId() { return peerId; }
            @Override public AgentRemoteProtocolKind protocol() { return AgentRemoteProtocolKind.MCP; }
            @Override public AgentRemoteProtocolBindingId bindingId() {
                return AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP;
            }
            @Override public AgentRemoteProtocolCall start(AgentRemoteProtocolDispatchRequest request) { return null; }
        };

        assertEquals(transportId, provider.providerId());
        assertEquals(
            AgentRemoteProtocolCapabilities.MCP_TOOL_CALL,
            AgentRemoteProtocolCapabilities.requiredFor(
                AgentRemoteProtocolKind.MCP,
                AgentRemoteOperationKind.MCP_TOOL_CALL
            )
        );
        assertEquals(
            AgentRemoteProtocolCapabilities.A2A_GET_TASK,
            AgentRemoteProtocolCapabilities.requiredFor(
                AgentRemoteProtocolKind.A2A,
                AgentRemoteOperationKind.A2A_GET_TASK
            )
        );
        assertEquals(
            AgentRemoteProtocolCapabilities.A2A_LIST_TASKS,
            AgentRemoteProtocolCapabilities.requiredFor(
                AgentRemoteProtocolKind.A2A,
                AgentRemoteOperationKind.A2A_LIST_TASKS
            )
        );
        assertNotNull(profile.getProfileDigest());
        assertFalse(profile.toString().contains(endpoint.getHost()));
        assertFalse(credential.toString().contains(credential.getCredentialReference().getValue()));
    }

    private static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte valueByte : bytes) {
            result.append(String.format("%02x", valueByte & 0xff));
        }
        return result.toString();
    }
}
