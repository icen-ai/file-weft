package ai.icen.fw.agent.adapter.http

import ai.icen.fw.agent.api.AgentRemoteProtocolBaselines
import ai.icen.fw.agent.api.AgentRemoteProtocolBindingId
import ai.icen.fw.agent.api.AgentRemoteProtocolKind
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.LinkedHashMap

class A2aJsonRpcHttpResponse internal constructor(
    val requestId: String,
    val operationName: String,
    result: ByteArray?,
    val error: AgentJsonRpcRemoteError?,
    val remoteTaskId: String?,
    val remoteMessageId: String?,
    val nextPageToken: String?,
) {
    private val resultSnapshot: ByteArray? = result?.copyOf()
    val resultDigest: String? = resultSnapshot?.let(::sha256)

    fun result(): ByteArray? = resultSnapshot?.copyOf()

    override fun toString(): String =
        "A2aJsonRpcHttpResponse(operation=$operationName, error=$error, " +
            "task=<redacted>, message=<redacted>, cursor=<redacted>, result=<redacted>)"
}

/** Strict A2A 1.0 JSON-RPC over HTTP codec. gRPC and HTTP+JSON REST are separate bindings. */
class A2aJsonRpcHttpCodec @JvmOverloads constructor(
    private val limits: AgentProtocolHttpCodecLimits = AgentProtocolHttpCodecLimits(),
    unknownFieldPolicy: AgentProtocolHttpUnknownFieldPolicy = AgentProtocolHttpUnknownFieldPolicy.REJECT,
) {
    private val json = StrictProtocolJson(limits, unknownFieldPolicy)

    @JvmOverloads
    fun sendMessage(
        requestId: String,
        canonicalMessage: ByteArray,
        messageId: String,
        messageDigest: String,
        canonicalConfiguration: ByteArray? = null,
    ): AgentProtocolHttpWireRequest {
        val message = json.parseObject(canonicalMessage)
        validateOutboundMessage(message, messageId)
        val digest = requireSha256(messageDigest, "a2a.message-digest.invalid")
        if (json.digest(message) != digest) {
            codecFailure("a2a.message-digest.mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        val params = json.objectNode().set<ObjectNode>("message", message)
        canonicalConfiguration?.let { bytes ->
            val configuration = json.parseObject(bytes)
            validateConfiguration(configuration)
            params.set<ObjectNode>("configuration", configuration)
        }
        return request(requestId, "SendMessage", params, messageId, digest, null, null)
    }

    @JvmOverloads
    fun getTask(
        requestId: String,
        taskId: String,
        historyLength: Int? = null,
    ): AgentProtocolHttpWireRequest {
        if (historyLength != null && historyLength !in 0..1_000) {
            codecFailure("a2a.history-length.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val boundTaskId = requireProtocolToken(taskId, "a2a.task-id.invalid")
        val params = json.objectNode().put("id", boundTaskId)
        historyLength?.let { params.put("historyLength", it) }
        return request(requestId, "GetTask", params, null, null, boundTaskId, null)
    }

    @JvmOverloads
    fun listTasks(
        requestId: String,
        pageToken: String? = null,
        pageSize: Int? = null,
    ): AgentProtocolHttpWireRequest {
        if (pageSize != null && pageSize !in 1..100) {
            codecFailure("a2a.page-size.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val boundToken = pageToken?.let { requireProtocolToken(it, "a2a.page-token.invalid", 2_048) }
        val params = json.objectNode().apply {
            boundToken?.let { put("pageToken", it) }
            pageSize?.let { put("pageSize", it) }
        }
        return request(requestId, "ListTasks", params, null, null, null, boundToken)
    }

    fun cancelTask(requestId: String, taskId: String): AgentProtocolHttpWireRequest {
        val boundTaskId = requireProtocolToken(taskId, "a2a.task-id.invalid")
        return request(
            requestId,
            "CancelTask",
            json.objectNode().put("id", boundTaskId),
            null,
            null,
            boundTaskId,
            null,
        )
    }

    fun canonicalDigest(jsonObject: ByteArray): String = json.digest(json.parseObject(jsonObject))

    fun decode(
        request: AgentProtocolHttpWireRequest,
        response: AgentProtocolHttpWireResponse,
    ): A2aJsonRpcHttpResponse {
        requireA2aRequest(request)
        val responseVersion = response.headers.value(A2A_VERSION_HEADER)
        if (responseVersion != null && responseVersion != AgentRemoteProtocolBaselines.A2A_1_0) {
            codecFailure("a2a.response-version.mismatch", AgentProtocolHttpErrorKind.UNSUPPORTED_VERSION)
        }
        val envelope = decodeStrictJsonRpcResponse(
            json,
            request,
            response,
            allowSse = false,
            errorClassifier = ::classifyA2aError,
        )
        if (envelope.error != null) {
            return A2aJsonRpcHttpResponse(
                request.requestId,
                request.operationName,
                null,
                envelope.error,
                null,
                null,
                null,
            )
        }
        val result = envelope.result
            ?: codecFailure("a2a.result.missing", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        if (!result.isObject) {
            codecFailure("a2a.result.not-object", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val resultObject = result as ObjectNode
        val identities = validateResult(request, resultObject)
        return A2aJsonRpcHttpResponse(
            request.requestId,
            request.operationName,
            json.canonical(resultObject),
            null,
            identities.taskId,
            identities.messageId,
            identities.nextPageToken,
        )
    }

    private fun request(
        requestId: String,
        method: String,
        params: ObjectNode,
        messageId: String?,
        messageDigest: String?,
        taskId: String?,
        cursor: String?,
    ): AgentProtocolHttpWireRequest {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = JSON_CONTENT_TYPE
        headers["Accept"] = JSON_CONTENT_TYPE
        headers[A2A_VERSION_HEADER] = AgentRemoteProtocolBaselines.A2A_1_0
        return AgentProtocolHttpWireRequest(
            AgentRemoteProtocolKind.A2A,
            AgentRemoteProtocolBindingId.A2A_JSON_RPC_HTTP,
            AgentProtocolHttpMethod.POST,
            requestId,
            method,
            requestHeaders(headers, limits),
            json.canonical(jsonRpcEnvelope(json, requestId, method, params)),
            null,
            null,
            messageId,
            messageDigest,
            taskId,
            cursor,
        )
    }

    private fun requireA2aRequest(request: AgentProtocolHttpWireRequest) {
        if (request.protocol != AgentRemoteProtocolKind.A2A ||
            request.bindingId != AgentRemoteProtocolBindingId.A2A_JSON_RPC_HTTP
        ) {
            codecFailure("a2a.request.binding-mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        if (request.headers.value(A2A_VERSION_HEADER) != AgentRemoteProtocolBaselines.A2A_1_0) {
            codecFailure("a2a.request-version.mismatch", AgentProtocolHttpErrorKind.UNSUPPORTED_VERSION)
        }
    }

    private fun validateOutboundMessage(message: ObjectNode, expectedMessageId: String) {
        json.requireAllowed(
            message,
            setOf("messageId", "contextId", "taskId", "role", "parts", "metadata", "extensions", "referenceTaskIds"),
            "a2a.message.unknown-field",
        )
        val messageId = json.requireText(message, "messageId", "a2a.message-id.invalid")
        if (messageId != requireProtocolToken(expectedMessageId, "a2a.message-id.invalid")) {
            codecFailure("a2a.message-id.mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        if (json.requireText(message, "role", "a2a.message-role.invalid", 32) != "ROLE_USER") {
            codecFailure("a2a.message-role.unsupported", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val parts = json.requireArray(message, "parts", "a2a.message-parts.invalid")
        if (parts.isEmpty) {
            codecFailure("a2a.message-parts.empty", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        parts.forEach(::validatePart)
        val extensions = message.get("extensions")
        if (extensions != null && (!extensions.isArray || extensions.size() != 0)) {
            codecFailure("a2a.message-extension.unnegotiated", AgentProtocolHttpErrorKind.UNKNOWN_FIELD)
        }
    }

    private fun validatePart(node: JsonNode) {
        if (!node.isObject) {
            codecFailure("a2a.part.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val part = node as ObjectNode
        json.requireAllowed(
            part,
            setOf("text", "raw", "url", "data", "metadata", "filename", "mediaType"),
            "a2a.part.unknown-field",
        )
        val contentFields = listOf("text", "raw", "url", "data").count { part.has(it) }
        if (contentFields != 1) {
            codecFailure("a2a.part.content.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        listOf("text", "raw", "url", "filename", "mediaType").forEach { field ->
            if (part.has(field)) json.requireText(part, field, "a2a.part-field.invalid", 131_072)
        }
    }

    private fun validateConfiguration(configuration: ObjectNode) {
        json.requireAllowed(
            configuration,
            setOf("acceptedOutputModes", "taskPushNotificationConfig", "historyLength", "returnImmediately"),
            "a2a.configuration.unknown-field",
        )
        if (configuration.has("taskPushNotificationConfig")) {
            codecFailure("a2a.push-config.unsupported", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        configuration.get("historyLength")?.let { value ->
            if (!value.isIntegralNumber || !value.canConvertToInt() || value.intValue() !in 0..1_000) {
                codecFailure("a2a.history-length.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
            }
        }
        configuration.get("returnImmediately")?.let { value ->
            if (!value.isBoolean) {
                codecFailure("a2a.return-immediately.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
            }
        }
        configuration.get("acceptedOutputModes")?.let { value ->
            if (!value.isArray || value.size() == 0) {
                codecFailure("a2a.output-modes.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
            }
            value.forEach { mode ->
                if (!mode.isTextual) {
                    codecFailure("a2a.output-mode.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
                }
                requireProtocolToken(mode.textValue(), "a2a.output-mode.invalid", 256)
            }
        }
    }

    private fun validateResult(request: AgentProtocolHttpWireRequest, result: ObjectNode): A2aResultIdentity =
        when (request.operationName) {
            "SendMessage" -> {
                json.requireAllowed(result, setOf("task", "message"), "a2a.send-result.unknown-field")
                val task = result.get("task")
                val message = result.get("message")
                if ((task == null) == (message == null)) {
                    codecFailure("a2a.send-result.payload.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
                }
                A2aResultIdentity(
                    taskId = task?.let { validateTask(it, null) },
                    messageId = message?.let(::validateInboundMessage),
                    nextPageToken = null,
                )
            }
            "GetTask", "CancelTask" -> A2aResultIdentity(
                validateTask(result, request.boundTaskId),
                null,
                null,
            )
            "ListTasks" -> {
                json.requireAllowed(
                    result,
                    setOf("tasks", "nextPageToken", "pageSize", "totalSize"),
                    "a2a.list-result.unknown-field",
                )
                json.requireArray(result, "tasks", "a2a.list-result.tasks.invalid")
                    .forEach { task -> validateTask(task, null) }
                A2aResultIdentity(
                    null,
                    null,
                    nextPageToken(result),
                )
            }
            else -> codecFailure("a2a.operation.unsupported", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }

    private fun validateTask(node: JsonNode, expectedTaskId: String?): String {
        if (!node.isObject) {
            codecFailure("a2a.task.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val task = node as ObjectNode
        json.requireAllowed(
            task,
            setOf("id", "contextId", "status", "artifacts", "history", "metadata"),
            "a2a.task.unknown-field",
        )
        val taskId = json.requireText(task, "id", "a2a.task-id.invalid")
        if (expectedTaskId != null && taskId != expectedTaskId) {
            codecFailure("a2a.task-id.mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        if (!task.has("status") || !task.get("status").isObject) {
            codecFailure("a2a.task-status.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        return taskId
    }

    private fun validateInboundMessage(node: JsonNode): String {
        if (!node.isObject) {
            codecFailure("a2a.response-message.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val message = node as ObjectNode
        json.requireAllowed(
            message,
            setOf("messageId", "contextId", "taskId", "role", "parts", "metadata", "extensions", "referenceTaskIds"),
            "a2a.response-message.unknown-field",
        )
        val messageId = json.requireText(message, "messageId", "a2a.response-message-id.invalid")
        if (json.requireText(message, "role", "a2a.response-message-role.invalid", 32) != "ROLE_AGENT") {
            codecFailure("a2a.response-message-role.unsupported", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val parts = json.requireArray(message, "parts", "a2a.response-message-parts.invalid")
        if (parts.isEmpty) {
            codecFailure("a2a.response-message-parts.empty", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        parts.forEach(::validatePart)
        val extensions = message.get("extensions")
        if (extensions != null && (!extensions.isArray || extensions.size() != 0)) {
            codecFailure("a2a.response-extension.unnegotiated", AgentProtocolHttpErrorKind.UNKNOWN_FIELD)
        }
        return messageId
    }

    private fun nextPageToken(result: ObjectNode): String? {
        val value = result.get("nextPageToken")
        if (value == null || !value.isTextual) {
            codecFailure("a2a.next-page-token.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val token = value.textValue()
        return if (token.isEmpty()) null else requireProtocolToken(token, "a2a.next-page-token.invalid", 2_048)
    }

    private fun classifyA2aError(code: Int): AgentProtocolHttpErrorKind = when (code) {
        -32001 -> AgentProtocolHttpErrorKind.REMOTE_NOT_FOUND
        -32002 -> AgentProtocolHttpErrorKind.REMOTE_CONFLICT
        -32009 -> AgentProtocolHttpErrorKind.UNSUPPORTED_VERSION
        -32003, -32004, -32005, -32006, -32007, -32008 ->
            AgentProtocolHttpErrorKind.REMOTE_PROTOCOL_ERROR
        else -> classifyJsonRpcError(code)
    }

    private class A2aResultIdentity(
        val taskId: String?,
        val messageId: String?,
        val nextPageToken: String?,
    )

    private companion object {
        const val JSON_CONTENT_TYPE = "application/json"
        const val A2A_VERSION_HEADER = "A2A-Version"
    }
}
