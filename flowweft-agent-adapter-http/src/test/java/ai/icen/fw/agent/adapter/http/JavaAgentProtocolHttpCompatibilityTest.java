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
        AgentProtocolHttpWireRequest a2aRequest = a2a.listTasks("request-2", "cursor-secret", 10);
        AgentProtocolHttpTransport transport = request -> CompletableFuture.completedFuture(
            new AgentProtocolHttpWireResponse(
                200,
                Collections.singletonMap("Content-Type", "application/json"),
                "{}".getBytes(StandardCharsets.UTF_8)
            )
        );

        assertEquals("tasks/cancel", mcpRequest.getOperationName());
        assertEquals("ListTasks", a2aRequest.getOperationName());
        assertFalse(mcpRequest.toString().contains("task-secret"));
        assertFalse(a2aRequest.toString().contains("cursor-secret"));
        assertFalse(transport.toString().isEmpty());
    }
}
