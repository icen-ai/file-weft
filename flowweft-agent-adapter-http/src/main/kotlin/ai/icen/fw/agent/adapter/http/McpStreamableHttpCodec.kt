package ai.icen.fw.agent.adapter.http

import ai.icen.fw.agent.api.AgentRemoteProtocolBaselines
import ai.icen.fw.agent.api.AgentRemoteProtocolBindingId
import ai.icen.fw.agent.api.AgentRemoteProtocolKind
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.LinkedHashMap

class McpStreamableHttpResponse internal constructor(
    val requestId: String,
    val operationName: String,
    result: ByteArray?,
    val error: AgentJsonRpcRemoteError?,
    val sessionId: String?,
    val remoteTaskId: String?,
    val nextCursor: String?,
    val sseEventId: String?,
) {
    private val resultSnapshot: ByteArray? = result?.copyOf()
    val resultDigest: String? = resultSnapshot?.let(::sha256)

    fun result(): ByteArray? = resultSnapshot?.copyOf()

    override fun toString(): String =
        "McpStreamableHttpResponse(operation=$operationName, error=$error, " +
            "session=<redacted>, task=<redacted>, cursor=<redacted>, result=<redacted>)"
}

/** Strict MCP 2025-11-25 Streamable HTTP JSON-RPC codec. It performs no network I/O. */
class McpStreamableHttpCodec @JvmOverloads constructor(
    private val limits: AgentProtocolHttpCodecLimits = AgentProtocolHttpCodecLimits(),
    unknownFieldPolicy: AgentProtocolHttpUnknownFieldPolicy = AgentProtocolHttpUnknownFieldPolicy.REJECT,
) {
    private val json = StrictProtocolJson(limits, unknownFieldPolicy)

    @JvmOverloads
    fun initialize(
        requestId: String,
        clientName: String,
        clientVersion: String,
        requestExperimentalTasks: Boolean = false,
    ): AgentProtocolHttpWireRequest {
        val capabilities = json.objectNode()
        if (requestExperimentalTasks) {
            val call = json.objectNode()
            val tools = json.objectNode().set<ObjectNode>("call", call)
            val requests = json.objectNode().set<ObjectNode>("tools", tools)
            capabilities.set<ObjectNode>("tasks", json.objectNode().set<ObjectNode>("requests", requests))
        }
        val params = json.objectNode().apply {
            put("protocolVersion", AgentRemoteProtocolBaselines.MCP_2025_11_25)
            set<ObjectNode>("capabilities", capabilities)
            set<ObjectNode>("clientInfo", json.objectNode().apply {
                put("name", requireProtocolToken(clientName, "mcp.client-name.invalid"))
                put("version", requireProtocolToken(clientVersion, "mcp.client-version.invalid"))
            })
        }
        return request(requestId, "initialize", params, null, null, null, null, null, initialize = true)
    }

    @JvmOverloads
    fun callTool(
        requestId: String,
        toolName: String,
        canonicalArguments: ByteArray,
        argumentsDigest: String,
        sessionId: String? = null,
        taskTtlMillis: Long? = null,
    ): AgentProtocolHttpWireRequest {
        val arguments = json.parseObject(canonicalArguments)
        val digest = requireSha256(argumentsDigest, "mcp.arguments-digest.invalid")
        if (json.digest(arguments) != digest) {
            codecFailure("mcp.arguments-digest.mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        if (taskTtlMillis != null && taskTtlMillis !in 1L..86_400_000L) {
            codecFailure("mcp.task-ttl.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val params = json.objectNode().apply {
            put("name", requireProtocolToken(toolName, "mcp.tool-name.invalid"))
            set<ObjectNode>("arguments", arguments)
            taskTtlMillis?.let { ttl ->
                set<ObjectNode>("task", json.objectNode().put("ttl", ttl))
            }
        }
        return request(requestId, "tools/call", params, toolName, digest, null, null, sessionId)
    }

    @JvmOverloads
    fun getTask(requestId: String, taskId: String, sessionId: String? = null): AgentProtocolHttpWireRequest =
        taskRequest(requestId, "tasks/get", taskId, sessionId)

    @JvmOverloads
    fun getTaskResult(requestId: String, taskId: String, sessionId: String? = null): AgentProtocolHttpWireRequest =
        taskRequest(requestId, "tasks/result", taskId, sessionId)

    @JvmOverloads
    fun cancelTask(requestId: String, taskId: String, sessionId: String? = null): AgentProtocolHttpWireRequest =
        taskRequest(requestId, "tasks/cancel", taskId, sessionId)

    @JvmOverloads
    fun listTasks(requestId: String, cursor: String? = null, sessionId: String? = null): AgentProtocolHttpWireRequest {
        val boundCursor = cursor?.let { requireProtocolToken(it, "mcp.cursor.invalid", 2_048) }
        val params = json.objectNode().apply { boundCursor?.let { put("cursor", it) } }
        return request(requestId, "tasks/list", params, null, null, null, boundCursor, sessionId)
    }

    fun canonicalDigest(jsonObject: ByteArray): String = json.digest(json.parseObject(jsonObject))

    fun decode(
        request: AgentProtocolHttpWireRequest,
        response: AgentProtocolHttpWireResponse,
    ): McpStreamableHttpResponse {
        requireMcpRequest(request)
        val versionHeader = response.headers.value(MCP_PROTOCOL_VERSION_HEADER)
        if (versionHeader != null && versionHeader != AgentRemoteProtocolBaselines.MCP_2025_11_25) {
            codecFailure("mcp.response-version.mismatch", AgentProtocolHttpErrorKind.UNSUPPORTED_VERSION)
        }
        val sessionId = response.headers.value(MCP_SESSION_HEADER)?.let(::requireMcpSessionId)
        val envelope = decodeStrictJsonRpcResponse(json, request, response, allowSse = true)
        if (envelope.error != null) {
            return McpStreamableHttpResponse(
                request.requestId,
                request.operationName,
                null,
                envelope.error,
                sessionId,
                null,
                null,
                envelope.sseEventId,
            )
        }
        val result = envelope.result
            ?: codecFailure("mcp.result.missing", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        if (!result.isObject) {
            codecFailure("mcp.result.not-object", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val resultObject = result as ObjectNode
        val identities = validateResult(request, resultObject)
        return McpStreamableHttpResponse(
            request.requestId,
            request.operationName,
            json.canonical(resultObject),
            null,
            sessionId,
            identities.first,
            identities.second,
            envelope.sseEventId,
        )
    }

    private fun taskRequest(
        requestId: String,
        method: String,
        taskId: String,
        sessionId: String?,
    ): AgentProtocolHttpWireRequest {
        val boundTaskId = requireProtocolToken(taskId, "mcp.task-id.invalid")
        val params = json.objectNode().put("taskId", boundTaskId)
        return request(requestId, method, params, null, null, boundTaskId, null, sessionId)
    }

    private fun request(
        requestId: String,
        method: String,
        params: ObjectNode,
        toolName: String?,
        argumentsDigest: String?,
        taskId: String?,
        cursor: String?,
        sessionId: String?,
        initialize: Boolean = false,
    ): AgentProtocolHttpWireRequest {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = JSON_CONTENT_TYPE
        headers["Accept"] = "$JSON_CONTENT_TYPE, $SSE_CONTENT_TYPE"
        if (!initialize) {
            headers[MCP_PROTOCOL_VERSION_HEADER] = AgentRemoteProtocolBaselines.MCP_2025_11_25
            sessionId?.let { headers[MCP_SESSION_HEADER] = requireMcpSessionId(it) }
        } else if (sessionId != null) {
            codecFailure("mcp.initialize.session-forbidden", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val body = json.canonical(jsonRpcEnvelope(json, requestId, method, params))
        return AgentProtocolHttpWireRequest(
            AgentRemoteProtocolKind.MCP,
            AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP,
            AgentProtocolHttpMethod.POST,
            requestId,
            method,
            requestHeaders(headers, limits),
            body,
            toolName,
            argumentsDigest,
            null,
            null,
            taskId,
            cursor,
        )
    }

    private fun requireMcpRequest(request: AgentProtocolHttpWireRequest) {
        if (request.protocol != AgentRemoteProtocolKind.MCP ||
            request.bindingId != AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP
        ) {
            codecFailure("mcp.request.binding-mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        val expectedVersion = request.operationName != "initialize"
        if (expectedVersion && request.headers.value(MCP_PROTOCOL_VERSION_HEADER) !=
            AgentRemoteProtocolBaselines.MCP_2025_11_25
        ) {
            codecFailure("mcp.request-version.mismatch", AgentProtocolHttpErrorKind.UNSUPPORTED_VERSION)
        }
    }

    private fun validateResult(
        request: AgentProtocolHttpWireRequest,
        result: ObjectNode,
    ): Pair<String?, String?> = when (request.operationName) {
        "initialize" -> {
            json.requireAllowed(
                result,
                setOf("protocolVersion", "capabilities", "serverInfo", "instructions", "_meta"),
                "mcp.initialize-result.unknown-field",
            )
            if (json.requireText(result, "protocolVersion", "mcp.initialize-version.invalid") !=
                AgentRemoteProtocolBaselines.MCP_2025_11_25
            ) {
                codecFailure("mcp.initialize-version.mismatch", AgentProtocolHttpErrorKind.UNSUPPORTED_VERSION)
            }
            null to null
        }
        "tools/call" -> {
            val task = result.get("task")
            val taskId = if (task != null) validateTask(task, null) else null
            taskId to null
        }
        "tasks/get", "tasks/cancel" -> validateTask(result, request.boundTaskId) to null
        "tasks/list" -> {
            json.requireAllowed(result, setOf("tasks", "nextCursor", "_meta"), "mcp.task-list.unknown-field")
            val tasks = json.requireArray(result, "tasks", "mcp.task-list.tasks.invalid")
            tasks.forEach { task -> validateTask(task, null) }
            null to json.optionalText(result, "nextCursor", "mcp.next-cursor.invalid", 2_048)
        }
        "tasks/result" -> {
            val meta = json.requireObject(result, "_meta", "mcp.task-result.meta.missing")
            val related = json.requireObject(meta, RELATED_TASK_META, "mcp.task-result.related-task.missing")
            val taskId = json.requireText(related, "taskId", "mcp.task-result.task-id.invalid")
            if (taskId != request.boundTaskId) {
                codecFailure("mcp.task-result.task-id.mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
            }
            taskId to null
        }
        else -> codecFailure("mcp.operation.unsupported", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
    }

    private fun validateTask(node: JsonNode, expectedTaskId: String?): String {
        if (!node.isObject) {
            codecFailure("mcp.task.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val task = node as ObjectNode
        json.requireAllowed(
            task,
            setOf("taskId", "status", "statusMessage", "createdAt", "lastUpdatedAt", "ttl", "pollInterval", "_meta"),
            "mcp.task.unknown-field",
        )
        val taskId = json.requireText(task, "taskId", "mcp.task-id.invalid")
        val status = json.requireText(task, "status", "mcp.task-status.invalid", 32)
        if (status !in TASK_STATUSES) {
            codecFailure("mcp.task-status.unsupported", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        if (expectedTaskId != null && taskId != expectedTaskId) {
            codecFailure("mcp.task-id.mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        return taskId
    }

    private fun requireMcpSessionId(value: String): String {
        if (value.isEmpty() || value.length > 512 || value.any { character -> character.code !in 0x21..0x7e }) {
            codecFailure("mcp.session-id.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        return value
    }

    private companion object {
        const val JSON_CONTENT_TYPE = "application/json"
        const val SSE_CONTENT_TYPE = "text/event-stream"
        const val MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
        const val MCP_SESSION_HEADER = "MCP-Session-Id"
        const val RELATED_TASK_META = "io.modelcontextprotocol/related-task"
        val TASK_STATUSES = setOf("working", "input_required", "completed", "failed", "cancelled")
    }
}
