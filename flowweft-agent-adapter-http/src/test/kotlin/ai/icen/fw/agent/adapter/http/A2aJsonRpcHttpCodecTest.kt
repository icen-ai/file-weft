package ai.icen.fw.agent.adapter.http

import ai.icen.fw.agent.api.AgentRemoteProtocolBaselines
import ai.icen.fw.agent.api.AgentRemoteProtocolBindingId
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class A2aJsonRpcHttpCodecTest {
    private val codec = A2aJsonRpcHttpCodec()

    @Test
    fun `send message binds version message id and canonical digest`() {
        val message = bytes(
            """{"messageId":"message-1","role":"ROLE_USER","parts":[{"text":"private prompt","mediaType":"text/plain"}]}""",
        )
        val request = codec.sendMessage(
            "request-1",
            message,
            "message-1",
            codec.canonicalDigest(message),
        )

        assertEquals(AgentRemoteProtocolBindingId.A2A_JSON_RPC_HTTP, request.bindingId)
        assertEquals(AgentRemoteProtocolBaselines.A2A_1_0, request.headers.value("a2a-version"))
        assertEquals("SendMessage", request.operationName)
        assertEquals("message-1", request.boundMessageId)
        assertFalse(request.toString().contains("private prompt"))

        val decoded = codec.decode(
            request,
            response(
                """{"jsonrpc":"2.0","id":"request-1","result":{"message":{"messageId":"reply-1","contextId":"context-1","role":"ROLE_AGENT","parts":[{"text":"done"}]}}}""",
            ),
        )
        assertEquals("reply-1", decoded.remoteMessageId)
        assertNull(decoded.error)
    }

    @Test
    fun `cancel and pagination identities fail closed on drift`() {
        val cancel = codec.cancelTask("request-2", "task-1")
        val mismatch = response(
            """{"jsonrpc":"2.0","id":"request-2","result":{"id":"task-2","status":{"state":"TASK_STATE_CANCELED"}}}""",
        )
        assertEquals(
            AgentProtocolHttpErrorKind.IDENTITY_MISMATCH,
            assertFailsWith<AgentProtocolHttpCodecException> { codec.decode(cancel, mismatch) }.kind,
        )

        val list = codec.listTasks("request-3", "page-in", 25)
        val page = codec.decode(
            list,
            response(
                """{"jsonrpc":"2.0","id":"request-3","result":{"tasks":[],"nextPageToken":"page-out","pageSize":25,"totalSize":0}}""",
            ),
        )
        assertEquals("page-in", list.boundCursor)
        assertEquals("page-out", page.nextPageToken)
    }

    @Test
    fun `unknown fields and A2A errors are safely classified`() {
        val unknown = bytes(
            """{"messageId":"message-2","role":"ROLE_USER","parts":[{"text":"hello"}],"authority":true}""",
        )
        assertEquals(
            AgentProtocolHttpErrorKind.UNKNOWN_FIELD,
            assertFailsWith<AgentProtocolHttpCodecException> {
                codec.sendMessage("request-4", unknown, "message-2", codec.canonicalDigest(unknown))
            }.kind,
        )

        val get = codec.getTask("request-5", "missing-task")
        val error = codec.decode(
            get,
            AgentProtocolHttpWireResponse(
                404,
                mapOf("Content-Type" to "application/json"),
                bytes("""{"jsonrpc":"2.0","id":"request-5","error":{"code":-32001,"message":"sensitive remote detail"}}"""),
            ),
        )
        assertEquals(AgentProtocolHttpErrorKind.REMOTE_NOT_FOUND, error.error?.kind)
        assertFalse(error.toString().contains("sensitive remote detail"))
    }

    private fun response(json: String): AgentProtocolHttpWireResponse = AgentProtocolHttpWireResponse(
        200,
        mapOf("Content-Type" to "application/json", "A2A-Version" to "1.0"),
        bytes(json),
    )

    private fun bytes(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)
}
