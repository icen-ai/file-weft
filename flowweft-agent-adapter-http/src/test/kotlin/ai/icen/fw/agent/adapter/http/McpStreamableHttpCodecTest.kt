package ai.icen.fw.agent.adapter.http

import ai.icen.fw.agent.api.AgentRemoteProtocolBaselines
import ai.icen.fw.agent.api.AgentRemoteProtocolBindingId
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpStreamableHttpCodecTest {
    private val codec = McpStreamableHttpCodec()

    @Test
    fun `tool call pins version arguments and never carries credentials`() {
        val arguments = bytes("""{"count":2,"query":"safe"}""")
        val request = codec.callTool(
            "request-1",
            "search_documents",
            arguments,
            codec.canonicalDigest(arguments),
            "session-secret",
        )

        assertEquals(AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP, request.bindingId)
        assertEquals(AgentRemoteProtocolBaselines.MCP_2025_11_25, request.headers.value("mcp-protocol-version"))
        assertEquals("application/json, text/event-stream", request.headers.value("accept"))
        assertEquals("tools/call", request.operationName)
        assertFalse(request.headers.names().any { it == "authorization" || it == "cookie" })
        assertFalse(request.toString().contains("session-secret"))
        assertFalse(request.toString().contains("safe"))
    }

    @Test
    fun `task cancellation and list cursor remain bound to their request`() {
        val cancel = codec.cancelTask("request-2", "task-7")
        val mismatch = response("""{"jsonrpc":"2.0","id":"request-2","result":{"taskId":"task-8","status":"cancelled"}}""")

        val failure = assertFailsWith<AgentProtocolHttpCodecException> { codec.decode(cancel, mismatch) }
        assertEquals(AgentProtocolHttpErrorKind.IDENTITY_MISMATCH, failure.kind)

        val list = codec.listTasks("request-3", "cursor-in")
        val decoded = codec.decode(
            list,
            response("""{"jsonrpc":"2.0","id":"request-3","result":{"tasks":[],"nextCursor":"cursor-out"}}"""),
        )
        assertEquals("cursor-in", list.boundCursor)
        assertEquals("cursor-out", decoded.nextCursor)
    }

    @Test
    fun `streamable response accepts one bounded SSE json rpc event`() {
        val initialize = codec.initialize("request-4", "flowweft", "1.0.0")
        val body = bytes(
            "id: event-secret\n" +
                "data: {\"jsonrpc\":\"2.0\",\"id\":\"request-4\",\"result\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"serverInfo\":{}}}\n\n",
        )
        val response = AgentProtocolHttpWireResponse(
            200,
            mapOf("Content-Type" to "text/event-stream", "MCP-Session-Id" to "session-secret"),
            body,
        )

        val decoded = codec.decode(initialize, response)

        assertEquals("event-secret", decoded.sseEventId)
        assertEquals("session-secret", decoded.sessionId)
        assertFalse(decoded.toString().contains("session-secret"))
        assertFalse(decoded.toString().contains("event-secret"))
        assertNull(decoded.error)
    }

    @Test
    fun `duplicate fields and response id drift fail closed`() {
        val request = codec.initialize("request-5", "flowweft", "1.0.0")
        val duplicate = response(
            """{"jsonrpc":"2.0","id":"request-5","id":"request-5","result":{"protocolVersion":"2025-11-25"}}""",
        )
        assertEquals(
            AgentProtocolHttpErrorKind.MALFORMED_JSON,
            assertFailsWith<AgentProtocolHttpCodecException> { codec.decode(request, duplicate) }.kind,
        )

        val drift = response(
            """{"jsonrpc":"2.0","id":"another-request","result":{"protocolVersion":"2025-11-25"}}""",
        )
        assertEquals(
            AgentProtocolHttpErrorKind.IDENTITY_MISMATCH,
            assertFailsWith<AgentProtocolHttpCodecException> { codec.decode(request, drift) }.kind,
        )
    }

    private fun response(json: String): AgentProtocolHttpWireResponse = AgentProtocolHttpWireResponse(
        200,
        mapOf("Content-Type" to "application/json"),
        bytes(json),
    )

    private fun bytes(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)
}
