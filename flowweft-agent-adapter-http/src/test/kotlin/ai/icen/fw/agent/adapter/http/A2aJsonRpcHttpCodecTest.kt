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
    fun `authorized GetTask and ListTasks bind exact owner context and bounded disclosure`() {
        val getPayload = bytes(
            """{"tenant":"remote-tenant-a","id":"task-1","historyLength":0}""",
        )
        val get = codec.getTask(
            "request-get",
            getPayload,
            codec.canonicalDigest(getPayload),
            "task-1",
            "remote-tenant-a",
            "context-owned-a",
        )
        assertEquals("GetTask", get.operationName)
        assertEquals("remote-tenant-a", get.boundTenantRoutingId)
        assertEquals("context-owned-a", get.boundContextId)
        assertEquals(0, get.boundHistoryLength)
        val fetched = codec.decode(
            get,
            response(
                """{"jsonrpc":"2.0","id":"request-get","result":{"id":"task-1","contextId":"context-owned-a","status":{"state":"TASK_STATE_WORKING"},"history":[]}}""",
            ),
        )
        assertEquals("task-1", fetched.remoteTaskId)

        val listPayload = bytes(
            """{"tenant":"remote-tenant-a","contextId":"context-owned-a","status":"TASK_STATE_WORKING","pageSize":25,"pageToken":"page-in","historyLength":0,"statusTimestampAfter":"2026-07-16T00:00:00Z","includeArtifacts":false}""",
        )
        val list = codec.listTasks(
            "request-list",
            listPayload,
            codec.canonicalDigest(listPayload),
            "remote-tenant-a",
            "context-owned-a",
        )
        assertEquals(25, list.boundPageSize)
        assertEquals("page-in", list.boundCursor)
        assertFalse(requireNotNull(list.boundIncludeArtifacts))
        val listed = codec.decode(
            list,
            response(
                """{"jsonrpc":"2.0","id":"request-list","result":{"tasks":[{"id":"task-1","contextId":"context-owned-a","status":{"state":"TASK_STATE_WORKING"}}],"nextPageToken":"","pageSize":10,"totalSize":2}}""",
            ),
        )
        assertEquals(listOf("task-1"), listed.taskIds)
        assertEquals(10, listed.pageSize)
        assertEquals(2, listed.totalSize)
        assertNull(listed.nextPageToken)

        val metadataLeak = response(
            """{"jsonrpc":"2.0","id":"request-list","result":{"tasks":[{"id":"task-1","contextId":"context-owned-a","status":{"state":"TASK_STATE_WORKING"},"metadata":{"tenant":"other"}}],"nextPageToken":"","pageSize":10,"totalSize":1}}""",
        )
        assertEquals(
            AgentProtocolHttpErrorKind.IDENTITY_MISMATCH,
            assertFailsWith<AgentProtocolHttpCodecException> { codec.decode(list, metadataLeak) }.kind,
        )
        val wrongContext = response(
            """{"jsonrpc":"2.0","id":"request-list","result":{"tasks":[{"id":"task-2","contextId":"context-other","status":{"state":"TASK_STATE_WORKING"}}],"nextPageToken":"","pageSize":10,"totalSize":1}}""",
        )
        assertEquals(
            AgentProtocolHttpErrorKind.IDENTITY_MISMATCH,
            assertFailsWith<AgentProtocolHttpCodecException> { codec.decode(list, wrongContext) }.kind,
        )
    }

    @Test
    fun `A2A response version is optional but a conflicting version is rejected`() {
        val get = codec.getTask("request-version", "task-1", 0)
        val noEcho = AgentProtocolHttpWireResponse(
            200,
            mapOf("Content-Type" to "application/json"),
            bytes(
                """{"jsonrpc":"2.0","id":"request-version","result":{"id":"task-1","status":{"state":"TASK_STATE_WORKING"}}}""",
            ),
        )
        assertEquals("task-1", codec.decode(get, noEcho).remoteTaskId)
        val drift = AgentProtocolHttpWireResponse(
            200,
            mapOf("Content-Type" to "application/json", "A2A-Version" to "0.3"),
            noEcho.body(),
        )
        assertEquals(
            AgentProtocolHttpErrorKind.UNSUPPORTED_VERSION,
            assertFailsWith<AgentProtocolHttpCodecException> { codec.decode(get, drift) }.kind,
        )
        assertTrue(get.headers.value("A2A-Version") == "1.0")
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
                mapOf("Content-Type" to "application/json", "A2A-Version" to "1.0"),
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
