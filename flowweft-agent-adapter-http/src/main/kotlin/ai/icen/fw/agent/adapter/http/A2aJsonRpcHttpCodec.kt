package ai.icen.fw.agent.adapter.http

import ai.icen.fw.agent.api.AgentRemoteProtocolBaselines
import ai.icen.fw.agent.api.AgentRemoteProtocolBindingId
import ai.icen.fw.agent.api.AgentRemoteProtocolKind
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

class A2aJsonRpcHttpResponse internal constructor(
    val requestId: String,
    val operationName: String,
    result: ByteArray?,
    val error: AgentJsonRpcRemoteError?,
    val remoteTaskId: String?,
    val remoteMessageId: String?,
    val nextPageToken: String?,
    taskIds: Collection<String>,
    val pageSize: Int?,
    val totalSize: Int?,
) {
    private val resultSnapshot: ByteArray? = result?.copyOf()
    val resultDigest: String? = resultSnapshot?.let(::sha256)
    val taskIds: List<String> = Collections.unmodifiableList(ArrayList(taskIds))

    fun result(): ByteArray? = resultSnapshot?.copyOf()

    override fun toString(): String =
        "A2aJsonRpcHttpResponse(operation=$operationName, error=$error, " +
            "task=<redacted>, message=<redacted>, cursor=<redacted>, tasks=${taskIds.size}, result=<redacted>)"
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
        tenantRoutingId: String? = null,
        expectedContextId: String? = null,
    ): AgentProtocolHttpWireRequest {
        val message = json.parseObject(canonicalMessage)
        val contextId = validateOutboundMessage(message, messageId, expectedContextId)
        val digest = requireSha256(messageDigest, "a2a.message-digest.invalid")
        if (json.digest(message) != digest) {
            codecFailure("a2a.message-digest.mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        val tenant = tenantRoutingId?.let { requireProtocolToken(it, "a2a.tenant.invalid") }
        val params = json.objectNode().apply {
            tenant?.let { put("tenant", it) }
            set<ObjectNode>("message", message)
        }
        canonicalConfiguration?.let { bytes ->
            val configuration = json.parseObject(bytes)
            validateConfiguration(configuration)
            params.set<ObjectNode>("configuration", configuration)
        }
        return request(
            requestId,
            "SendMessage",
            params,
            messageId,
            digest,
            null,
            null,
            tenantRoutingId = tenant,
            contextId = contextId,
        )
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

    /**
     * FlowWeft's authorized A2A 1.0 task-read profile. The payload is the exact ProtoJSON
     * `GetTaskRequest` params object and must explicitly bind tenant, task and history limit.
     */
    fun getTask(
        requestId: String,
        canonicalRequest: ByteArray,
        requestDigest: String,
        expectedTaskId: String,
        expectedTenantRoutingId: String,
        expectedContextId: String,
    ): AgentProtocolHttpWireRequest {
        val params = json.parseObject(canonicalRequest)
        json.requireAllowed(params, setOf("tenant", "id", "historyLength"), "a2a.get-request.unknown-field")
        val digest = requireCanonicalDigest(params, requestDigest, "a2a.get-request-digest.mismatch")
        val tenant = json.requireText(params, "tenant", "a2a.tenant.invalid")
        val taskId = json.requireText(params, "id", "a2a.task-id.invalid")
        val historyLength = requireBoundedInteger(
            params,
            "historyLength",
            0,
            MAX_HISTORY_LENGTH,
            "a2a.history-length.invalid",
        )
        if (tenant != requireProtocolToken(expectedTenantRoutingId, "a2a.tenant.invalid") ||
            taskId != requireProtocolToken(expectedTaskId, "a2a.task-id.invalid")
        ) {
            codecFailure("a2a.get-request.identity-mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        val contextId = requireProtocolToken(expectedContextId, "a2a.context-id.invalid")
        return request(
            requestId,
            "GetTask",
            params,
            null,
            null,
            taskId,
            null,
            argumentsDigest = digest,
            tenantRoutingId = tenant,
            contextId = contextId,
            historyLength = historyLength,
        )
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

    /**
     * FlowWeft's subject-owned-context subset of A2A 1.0 `ListTasksRequest`. Every optional
     * disclosure control is made explicit so an omitted ProtoJSON field cannot mean unbounded data.
     */
    fun listTasks(
        requestId: String,
        canonicalRequest: ByteArray,
        requestDigest: String,
        expectedTenantRoutingId: String,
        expectedContextId: String,
    ): AgentProtocolHttpWireRequest {
        val params = json.parseObject(canonicalRequest)
        json.requireAllowed(
            params,
            setOf(
                "tenant",
                "contextId",
                "status",
                "pageSize",
                "pageToken",
                "historyLength",
                "statusTimestampAfter",
                "includeArtifacts",
            ),
            "a2a.list-request.unknown-field",
        )
        val digest = requireCanonicalDigest(params, requestDigest, "a2a.list-request-digest.mismatch")
        val tenant = json.requireText(params, "tenant", "a2a.tenant.invalid")
        val contextId = json.requireText(params, "contextId", "a2a.context-id.invalid")
        if (tenant != requireProtocolToken(expectedTenantRoutingId, "a2a.tenant.invalid") ||
            contextId != requireProtocolToken(expectedContextId, "a2a.context-id.invalid")
        ) {
            codecFailure("a2a.list-request.identity-mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        params.get("status")?.let { status ->
            if (!status.isTextual || status.textValue() !in A2A_TASK_STATES) {
                codecFailure("a2a.task-state.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
            }
        }
        val pageSize = requireBoundedInteger(params, "pageSize", 1, 100, "a2a.page-size.invalid")
        val pageToken = params.get("pageToken")?.let { token ->
            if (!token.isTextual) {
                codecFailure("a2a.page-token.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
            }
            requireProtocolToken(token.textValue(), "a2a.page-token.invalid", 2_048)
        }
        val historyLength = requireBoundedInteger(
            params,
            "historyLength",
            0,
            MAX_HISTORY_LENGTH,
            "a2a.history-length.invalid",
        )
        params.get("statusTimestampAfter")?.let { timestamp ->
            if (!timestamp.isTextual) {
                codecFailure("a2a.status-timestamp.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
            }
            requireUtcTimestamp(timestamp.textValue(), "a2a.status-timestamp.invalid")
        }
        val includeArtifactsNode = params.get("includeArtifacts")
        if (includeArtifactsNode == null || !includeArtifactsNode.isBoolean) {
            codecFailure("a2a.include-artifacts.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        return request(
            requestId,
            "ListTasks",
            params,
            null,
            null,
            null,
            pageToken,
            argumentsDigest = digest,
            tenantRoutingId = tenant,
            contextId = contextId,
            historyLength = historyLength,
            pageSize = pageSize,
            includeArtifacts = includeArtifactsNode.booleanValue(),
        )
    }

    @JvmOverloads
    fun cancelTask(
        requestId: String,
        taskId: String,
        tenantRoutingId: String? = null,
    ): AgentProtocolHttpWireRequest {
        val boundTaskId = requireProtocolToken(taskId, "a2a.task-id.invalid")
        val tenant = tenantRoutingId?.let { requireProtocolToken(it, "a2a.tenant.invalid") }
        val params = json.objectNode().apply {
            tenant?.let { put("tenant", it) }
            put("id", boundTaskId)
        }
        return request(
            requestId,
            "CancelTask",
            params,
            null,
            null,
            boundTaskId,
            null,
            tenantRoutingId = tenant,
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
                emptyList(),
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
            identities.taskIds,
            identities.pageSize,
            identities.totalSize,
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
        argumentsDigest: String? = null,
        tenantRoutingId: String? = null,
        contextId: String? = null,
        historyLength: Int? = null,
        pageSize: Int? = null,
        includeArtifacts: Boolean? = null,
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
            argumentsDigest,
            messageId,
            messageDigest,
            taskId,
            cursor,
            tenantRoutingId,
            contextId,
            historyLength,
            pageSize,
            includeArtifacts,
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

    private fun validateOutboundMessage(
        message: ObjectNode,
        expectedMessageId: String,
        expectedContextId: String?,
    ): String? {
        json.requireAllowed(
            message,
            setOf("messageId", "contextId", "taskId", "role", "parts", "metadata", "extensions", "referenceTaskIds"),
            "a2a.message.unknown-field",
        )
        val messageId = json.requireText(message, "messageId", "a2a.message-id.invalid")
        if (messageId != requireProtocolToken(expectedMessageId, "a2a.message-id.invalid")) {
            codecFailure("a2a.message-id.mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        val contextId = json.optionalText(message, "contextId", "a2a.context-id.invalid")
        if (expectedContextId != null &&
            contextId != requireProtocolToken(expectedContextId, "a2a.context-id.invalid")
        ) {
            codecFailure("a2a.context-id.mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        if (json.requireText(message, "role", "a2a.message-role.invalid", 32) != "ROLE_USER") {
            codecFailure("a2a.message-role.unsupported", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val parts = json.requireArray(message, "parts", "a2a.message-parts.invalid")
        if (parts.isEmpty) {
            codecFailure("a2a.message-parts.empty", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        parts.forEach { part -> validatePart(part, true) }
        val extensions = message.get("extensions")
        if (extensions != null && (!extensions.isArray || extensions.size() != 0)) {
            codecFailure("a2a.message-extension.unnegotiated", AgentProtocolHttpErrorKind.UNKNOWN_FIELD)
        }
        return contextId
    }

    private fun validatePart(node: JsonNode, allowMetadata: Boolean) {
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
        if (!allowMetadata && part.has("metadata")) {
            codecFailure("a2a.list-result.metadata-forbidden", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
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
                val taskIdentity = task?.let {
                    validateTask(it, null, request.boundContextId, null, null, true)
                }
                A2aResultIdentity(
                    taskId = taskIdentity?.taskId,
                    messageId = message?.let {
                        validateMessage(it, setOf("ROLE_AGENT"), true)
                    },
                    nextPageToken = null,
                    taskIds = taskIdentity?.let { listOf(it.taskId) } ?: emptyList(),
                    pageSize = null,
                    totalSize = null,
                )
            }
            "GetTask", "CancelTask" -> {
                val task = validateTask(
                    result,
                    request.boundTaskId,
                    request.boundContextId,
                    request.boundHistoryLength,
                    null,
                    true,
                )
                A2aResultIdentity(task.taskId, null, null, listOf(task.taskId), null, null)
            }
            "ListTasks" -> {
                json.requireAllowed(
                    result,
                    setOf("tasks", "nextPageToken", "pageSize", "totalSize"),
                    "a2a.list-result.unknown-field",
                )
                val taskIds = ArrayList<String>()
                val tasks = json.requireArray(result, "tasks", "a2a.list-result.tasks.invalid")
                tasks.forEach { task ->
                    taskIds.add(
                        validateTask(
                            task,
                            null,
                            request.boundContextId,
                            request.boundHistoryLength,
                            request.boundIncludeArtifacts,
                            false,
                        ).taskId,
                    )
                }
                if (taskIds.toSet().size != taskIds.size) {
                    codecFailure("a2a.list-result.task-id-duplicate", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
                }
                val pageSize = requireBoundedInteger(
                    result,
                    "pageSize",
                    1,
                    100,
                    "a2a.list-result.page-size.invalid",
                )
                val totalSize = requireBoundedInteger(
                    result,
                    "totalSize",
                    0,
                    Int.MAX_VALUE,
                    "a2a.list-result.total-size.invalid",
                )
                if (tasks.size() > pageSize || totalSize < tasks.size() ||
                    request.boundPageSize?.let { pageSize > it } == true
                ) {
                    codecFailure("a2a.list-result.pagination.invalid", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
                }
                A2aResultIdentity(
                    null,
                    null,
                    nextPageToken(result),
                    taskIds,
                    pageSize,
                    totalSize,
                )
            }
            else -> codecFailure("a2a.operation.unsupported", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }

    private fun validateTask(
        node: JsonNode,
        expectedTaskId: String?,
        expectedContextId: String?,
        historyLength: Int?,
        includeArtifacts: Boolean?,
        allowMetadata: Boolean,
    ): A2aTaskIdentity {
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
        val contextId = json.optionalText(task, "contextId", "a2a.context-id.invalid")
        if (expectedContextId != null && contextId != expectedContextId) {
            codecFailure("a2a.context-id.mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        validateTaskStatus(json.requireObject(task, "status", "a2a.task-status.invalid"), allowMetadata)
        if (!allowMetadata && task.has("metadata")) {
            codecFailure("a2a.list-result.metadata-forbidden", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        val artifacts = task.get("artifacts")
        when (includeArtifacts) {
            false -> if (artifacts != null) {
                codecFailure("a2a.list-result.artifacts-forbidden", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
            }
            true -> {
                if (artifacts == null || !artifacts.isArray) {
                    codecFailure("a2a.list-result.artifacts-missing", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
                }
                artifacts.forEach { artifact -> validateArtifact(artifact, allowMetadata) }
            }
            null -> if (artifacts != null) {
                if (!artifacts.isArray) {
                    codecFailure("a2a.task-artifacts.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
                }
                artifacts.forEach { artifact -> validateArtifact(artifact, allowMetadata) }
            }
        }
        task.get("history")?.let { history ->
            if (!history.isArray || historyLength?.let { history.size() > it } == true) {
                codecFailure("a2a.task-history.invalid", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
            }
            history.forEach { message ->
                validateMessage(message, setOf("ROLE_USER", "ROLE_AGENT"), allowMetadata)
            }
        }
        return A2aTaskIdentity(taskId, contextId)
    }

    private fun validateTaskStatus(status: ObjectNode, allowMetadata: Boolean) {
        json.requireAllowed(status, setOf("state", "message", "timestamp"), "a2a.task-status.unknown-field")
        if (json.requireText(status, "state", "a2a.task-state.invalid", 64) !in A2A_TASK_STATES) {
            codecFailure("a2a.task-state.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        status.get("message")?.let { message -> validateMessage(message, setOf("ROLE_AGENT"), allowMetadata) }
        status.get("timestamp")?.let { timestamp ->
            if (!timestamp.isTextual) {
                codecFailure("a2a.task-timestamp.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
            }
            requireUtcTimestamp(timestamp.textValue(), "a2a.task-timestamp.invalid")
        }
    }

    private fun validateArtifact(node: JsonNode, allowMetadata: Boolean) {
        if (!node.isObject) {
            codecFailure("a2a.artifact.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val artifact = node as ObjectNode
        json.requireAllowed(
            artifact,
            setOf("artifactId", "name", "description", "parts", "metadata", "extensions"),
            "a2a.artifact.unknown-field",
        )
        json.requireText(artifact, "artifactId", "a2a.artifact-id.invalid")
        listOf("name", "description").forEach { field ->
            artifact.get(field)?.let { json.requireText(artifact, field, "a2a.artifact-field.invalid", 4_096) }
        }
        val parts = json.requireArray(artifact, "parts", "a2a.artifact-parts.invalid")
        if (parts.isEmpty) {
            codecFailure("a2a.artifact-parts.empty", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        parts.forEach { part -> validatePart(part, allowMetadata) }
        if (!allowMetadata && artifact.has("metadata")) {
            codecFailure("a2a.list-result.metadata-forbidden", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        rejectUnnegotiatedExtensions(artifact.get("extensions"), "a2a.artifact-extension.unnegotiated")
    }

    private fun validateMessage(
        node: JsonNode,
        allowedRoles: Set<String>,
        allowMetadata: Boolean,
    ): String {
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
        if (json.requireText(message, "role", "a2a.response-message-role.invalid", 32) !in allowedRoles) {
            codecFailure("a2a.response-message-role.unsupported", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val parts = json.requireArray(message, "parts", "a2a.response-message-parts.invalid")
        if (parts.isEmpty) {
            codecFailure("a2a.response-message-parts.empty", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        parts.forEach { part -> validatePart(part, allowMetadata) }
        if (!allowMetadata && message.has("metadata")) {
            codecFailure("a2a.list-result.metadata-forbidden", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        rejectUnnegotiatedExtensions(message.get("extensions"), "a2a.response-extension.unnegotiated")
        message.get("referenceTaskIds")?.let { references ->
            if (!references.isArray) {
                codecFailure("a2a.reference-task-ids.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
            }
            references.forEach { reference ->
                if (!reference.isTextual) {
                    codecFailure("a2a.reference-task-id.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
                }
                requireProtocolToken(reference.textValue(), "a2a.reference-task-id.invalid")
            }
        }
        return messageId
    }

    private fun rejectUnnegotiatedExtensions(node: JsonNode?, code: String) {
        if (node != null && (!node.isArray || node.size() != 0)) {
            codecFailure(code, AgentProtocolHttpErrorKind.UNKNOWN_FIELD)
        }
    }

    private fun requireCanonicalDigest(node: ObjectNode, digest: String, mismatchCode: String): String {
        val expected = requireSha256(digest, "a2a.request-digest.invalid")
        if (json.digest(node) != expected) {
            codecFailure(mismatchCode, AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        return expected
    }

    private fun requireBoundedInteger(
        node: ObjectNode,
        field: String,
        minimum: Int,
        maximum: Int,
        code: String,
    ): Int {
        val value = node.get(field)
        if (value == null || !value.isIntegralNumber || !value.canConvertToInt() ||
            value.intValue() !in minimum..maximum
        ) {
            codecFailure(code, AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        return value.intValue()
    }

    private fun requireUtcTimestamp(value: String, code: String): String {
        val timestamp = requireProtocolToken(value, code, 128)
        if (!timestamp.endsWith("Z")) {
            codecFailure(code, AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        try {
            Instant.parse(timestamp)
        } catch (_: DateTimeParseException) {
            codecFailure(code, AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        return timestamp
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
        val taskIds: List<String>,
        val pageSize: Int?,
        val totalSize: Int?,
    )

    private class A2aTaskIdentity(
        val taskId: String,
        val contextId: String?,
    )

    private companion object {
        const val JSON_CONTENT_TYPE = "application/json"
        const val A2A_VERSION_HEADER = "A2A-Version"
        const val MAX_HISTORY_LENGTH = 1_000
        val A2A_TASK_STATES: Set<String> = setOf(
            "TASK_STATE_UNSPECIFIED",
            "TASK_STATE_SUBMITTED",
            "TASK_STATE_WORKING",
            "TASK_STATE_COMPLETED",
            "TASK_STATE_FAILED",
            "TASK_STATE_CANCELED",
            "TASK_STATE_INPUT_REQUIRED",
            "TASK_STATE_REJECTED",
            "TASK_STATE_AUTH_REQUIRED",
        )
    }
}
