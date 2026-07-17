package ai.icen.fw.agent.adapter.http;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JavaAgentProtocolHttpCompatibilityTest {
    @Test
    void codecsAndTransportAreJava8Friendly() {
        McpStreamableHttpCodec mcp = new McpStreamableHttpCodec();
        A2aJsonRpcHttpCodec a2a = new A2aJsonRpcHttpCodec();

        AgentProtocolHttpWireRequest mcpRequest = mcp.cancelTask("request-1", "task-secret");
        byte[] listPayload = (
            "{\"tenant\":\"remote-tenant\",\"contextId\":\"context-secret\"," +
                "\"pageSize\":10,\"pageToken\":\"cursor-secret\",\"historyLength\":0," +
                "\"includeArtifacts\":false}"
        ).getBytes(StandardCharsets.UTF_8);
        AgentProtocolHttpWireRequest a2aRequest = a2a.listTasks(
            "request-2",
            listPayload,
            a2a.canonicalDigest(listPayload),
            "remote-tenant",
            "context-secret"
        );
        AgentProtocolHttpTransport transport = request -> CompletableFuture.completedFuture(
            new AgentProtocolHttpWireResponse(
                200,
                Collections.singletonMap("Content-Type", "application/json"),
                "{}".getBytes(StandardCharsets.UTF_8)
            )
        );

        assertEquals("tasks/cancel", mcpRequest.getOperationName());
        assertEquals("ListTasks", a2aRequest.getOperationName());
        assertEquals(Integer.valueOf(10), a2aRequest.getBoundPageSize());
        assertFalse(mcpRequest.toString().contains("task-secret"));
        assertFalse(a2aRequest.toString().contains("cursor-secret"));
        assertFalse(transport.toString().isEmpty());
    }
}
