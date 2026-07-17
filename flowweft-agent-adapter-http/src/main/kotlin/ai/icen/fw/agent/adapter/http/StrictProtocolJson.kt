package ai.icen.fw.agent.adapter.http

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class StrictProtocolJson(
    private val limits: AgentProtocolHttpCodecLimits,
    private val unknownFieldPolicy: AgentProtocolHttpUnknownFieldPolicy,
) {
    val mapper: ObjectMapper = ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    fun objectNode(): ObjectNode = mapper.createObjectNode()

    fun arrayNode(): ArrayNode = mapper.createArrayNode()

    fun maximumSseEvents(): Int = limits.maximumSseEvents

    fun parseObject(body: ByteArray): ObjectNode {
        if (body.isEmpty() || body.size > limits.maximumBodyBytes) {
            codecFailure("json.body.limit", AgentProtocolHttpErrorKind.LIMIT_EXCEEDED)
        }
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            codecFailure("json.malformed", AgentProtocolHttpErrorKind.MALFORMED_JSON)
        }
        validateTree(root, 1)
        if (!root.isObject) {
            codecFailure("json.root.not-object", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        return root as ObjectNode
    }

    fun canonical(node: JsonNode): ByteArray {
        validateTree(node, 1)
        val bytes = try {
            mapper.writeValueAsBytes(canonicalTree(node))
        } catch (_: Exception) {
            codecFailure("json.canonicalization.failed", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        if (bytes.size > limits.maximumBodyBytes) {
            codecFailure("json.body.limit", AgentProtocolHttpErrorKind.LIMIT_EXCEEDED)
        }
        return bytes
    }

    fun digest(node: JsonNode): String = sha256(canonical(node))

    fun requireAllowed(node: ObjectNode, allowed: Set<String>, code: String) {
        val unknown = node.fieldNames().asSequence().firstOrNull { field ->
            field !in allowed && !(field == "_meta" &&
                unknownFieldPolicy == AgentProtocolHttpUnknownFieldPolicy.ALLOW_DOCUMENTED_METADATA)
        }
        if (unknown != null) {
            codecFailure(code, AgentProtocolHttpErrorKind.UNKNOWN_FIELD)
        }
    }

    fun requireText(node: ObjectNode, field: String, code: String, maximumCodePoints: Int = 512): String {
        val value = node.get(field)
        if (value == null || !value.isTextual) {
            codecFailure(code, AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        return requireProtocolToken(value.textValue(), code, maximumCodePoints)
    }

    fun optionalText(node: ObjectNode, field: String, code: String, maximumCodePoints: Int = 512): String? {
        val value = node.get(field) ?: return null
        if (!value.isTextual) {
            codecFailure(code, AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        return requireProtocolToken(value.textValue(), code, maximumCodePoints)
    }

    fun requireObject(node: ObjectNode, field: String, code: String): ObjectNode {
        val value = node.get(field)
        if (value == null || !value.isObject) {
            codecFailure(code, AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        return value as ObjectNode
    }

    fun requireArray(node: ObjectNode, field: String, code: String): ArrayNode {
        val value = node.get(field)
        if (value == null || !value.isArray) {
            codecFailure(code, AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        return value as ArrayNode
    }

    private fun validateTree(node: JsonNode, depth: Int) {
        if (depth > limits.maximumDepth) {
            codecFailure("json.depth.limit", AgentProtocolHttpErrorKind.LIMIT_EXCEEDED)
        }
        when {
            node.isObject -> {
                if (node.size() > limits.maximumObjectFields) {
                    codecFailure("json.object-fields.limit", AgentProtocolHttpErrorKind.LIMIT_EXCEEDED)
                }
                node.elements().forEachRemaining { child -> validateTree(child, depth + 1) }
            }
            node.isArray -> {
                if (node.size() > limits.maximumArrayItems) {
                    codecFailure("json.array-items.limit", AgentProtocolHttpErrorKind.LIMIT_EXCEEDED)
                }
                node.elements().forEachRemaining { child -> validateTree(child, depth + 1) }
            }
            node.isTextual -> if (node.textValue().codePointCount(0, node.textValue().length) >
                limits.maximumStringCodePoints
            ) {
                codecFailure("json.string.limit", AgentProtocolHttpErrorKind.LIMIT_EXCEEDED)
            }
            node.isBinary -> codecFailure("json.binary.unsupported", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
            node.isNumber -> if (node.asText().length > 128) {
                codecFailure("json.number.limit", AgentProtocolHttpErrorKind.LIMIT_EXCEEDED)
            }
        }
    }

    private fun canonicalTree(node: JsonNode): JsonNode = when {
        node.isObject -> objectNode().also { output ->
            node.fieldNames().asSequence().toList().sorted().forEach { field ->
                output.set<JsonNode>(field, canonicalTree(node.get(field)))
            }
        }
        node.isArray -> arrayNode().also { output ->
            node.elements().forEachRemaining { child -> output.add(canonicalTree(child)) }
        }
        else -> node.deepCopy<JsonNode>()
    }
}

internal fun jsonRpcEnvelope(
    json: StrictProtocolJson,
    requestId: String,
    method: String,
    params: ObjectNode,
): ObjectNode = json.objectNode().apply {
    put("jsonrpc", "2.0")
    put("id", requireProtocolToken(requestId, "jsonrpc.request-id.invalid"))
    put("method", method)
    set<ObjectNode>("params", params)
}

internal fun classifyJsonRpcError(code: Int): AgentProtocolHttpErrorKind = when (code) {
    -32700, -32600, -32601, -32602, -32603 -> AgentProtocolHttpErrorKind.REMOTE_PROTOCOL_ERROR
    else -> if (code in -32099..-32000) {
        AgentProtocolHttpErrorKind.REMOTE_SERVER_ERROR
    } else {
        AgentProtocolHttpErrorKind.REMOTE_PROTOCOL_ERROR
    }
}

internal class StrictJsonRpcResponse(
    val result: JsonNode?,
    val error: AgentJsonRpcRemoteError?,
    val sseEventId: String?,
)

internal fun decodeStrictJsonRpcResponse(
    json: StrictProtocolJson,
    request: AgentProtocolHttpWireRequest,
    response: AgentProtocolHttpWireResponse,
    allowSse: Boolean,
    errorClassifier: (Int) -> AgentProtocolHttpErrorKind = ::classifyJsonRpcError,
): StrictJsonRpcResponse {
    val mediaType = contentType(response.headers)
    val framed = when (mediaType) {
        "application/json" -> response.body() to null
        "text/event-stream" -> {
            if (!allowSse) {
                codecFailure("http.sse.unsupported", AgentProtocolHttpErrorKind.UNSUPPORTED_STREAM)
            }
            decodeSingleSseEvent(response.body(), json.maximumSseEvents())
        }
        else -> codecFailure("http.content-type.unsupported", AgentProtocolHttpErrorKind.INVALID_CONTENT_TYPE)
    }
    val root = json.parseObject(framed.first)
    json.requireAllowed(root, setOf("jsonrpc", "id", "result", "error"), "jsonrpc.response.unknown-field")
    if (json.requireText(root, "jsonrpc", "jsonrpc.version.invalid", 8) != "2.0") {
        codecFailure("jsonrpc.version.unsupported", AgentProtocolHttpErrorKind.UNSUPPORTED_VERSION)
    }
    if (json.requireText(root, "id", "jsonrpc.response-id.invalid") != request.requestId) {
        codecFailure("jsonrpc.response-id.mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
    }
    val result = root.get("result")
    val errorNode = root.get("error")
    if ((result == null) == (errorNode == null)) {
        codecFailure("jsonrpc.response.outcome.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
    }
    val error = errorNode?.let { node ->
        if (!node.isObject) {
            codecFailure("jsonrpc.error.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val errorObject = node as ObjectNode
        json.requireAllowed(errorObject, setOf("code", "message", "data"), "jsonrpc.error.unknown-field")
        val codeNode = errorObject.get("code")
        val messageNode = errorObject.get("message")
        if (codeNode == null || !codeNode.isIntegralNumber || !codeNode.canConvertToInt() ||
            messageNode == null || !messageNode.isTextual
        ) {
            codecFailure("jsonrpc.error.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        requireProtocolToken(messageNode.textValue(), "jsonrpc.error-message.invalid", 4_096)
        val data = errorObject.get("data")?.let(json::canonical)
        AgentJsonRpcRemoteError(codeNode.intValue(), errorClassifier(codeNode.intValue()), data)
    }
    if (error == null && response.statusCode !in 200..299) {
        codecFailure("http.success-status.invalid", classifyHttpStatus(response.statusCode))
    }
    return StrictJsonRpcResponse(result, error, framed.second)
}

private fun decodeSingleSseEvent(body: ByteArray, maximumEvents: Int): Pair<ByteArray, String?> {
    val text = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body))
            .toString()
    } catch (_: Exception) {
        codecFailure("http.sse.utf8-invalid", AgentProtocolHttpErrorKind.MALFORMED_JSON)
    }
    val currentData = ArrayList<String>()
    var currentEventId: String? = null
    var payload: ByteArray? = null
    var selectedEventId: String? = null
    var eventCount = 0
    fun finishEvent() {
        if (currentData.isEmpty()) return
        eventCount += 1
        if (eventCount > maximumEvents || payload != null) {
            codecFailure("http.sse.response-count.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        payload = currentData.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        selectedEventId = currentEventId
        currentData.clear()
        currentEventId = null
    }
    text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
        if (line.isEmpty()) {
            finishEvent()
            return@forEach
        }
        if (line.startsWith(":")) return@forEach
        val separator = line.indexOf(':')
        val name = if (separator < 0) line else line.substring(0, separator)
        val rawValue = if (separator < 0) "" else line.substring(separator + 1).removePrefix(" ")
        when (name) {
            "data" -> currentData.add(rawValue)
            "id" -> currentEventId = requireProtocolToken(rawValue, "http.sse.event-id.invalid")
            "event" -> requireProtocolToken(rawValue, "http.sse.event-name.invalid")
            "retry" -> if (rawValue.toLongOrNull() == null || rawValue.startsWith('-')) {
                codecFailure("http.sse.retry.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
            }
            else -> codecFailure("http.sse.field.unknown", AgentProtocolHttpErrorKind.UNKNOWN_FIELD)
        }
    }
    finishEvent()
    if (payload == null || eventCount != 1) {
        codecFailure("http.sse.response-count.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
    }
    return requireNotNull(payload) to selectedEventId
}

private fun classifyHttpStatus(statusCode: Int): AgentProtocolHttpErrorKind = when (statusCode) {
    401 -> AgentProtocolHttpErrorKind.REMOTE_AUTHENTICATION
    403 -> AgentProtocolHttpErrorKind.REMOTE_AUTHORIZATION
    404 -> AgentProtocolHttpErrorKind.REMOTE_NOT_FOUND
    408, 425, 502, 503, 504 -> AgentProtocolHttpErrorKind.REMOTE_RETRYABLE
    409 -> AgentProtocolHttpErrorKind.REMOTE_CONFLICT
    429 -> AgentProtocolHttpErrorKind.REMOTE_RATE_LIMITED
    in 500..599 -> AgentProtocolHttpErrorKind.REMOTE_SERVER_ERROR
    else -> AgentProtocolHttpErrorKind.REMOTE_PROTOCOL_ERROR
}
