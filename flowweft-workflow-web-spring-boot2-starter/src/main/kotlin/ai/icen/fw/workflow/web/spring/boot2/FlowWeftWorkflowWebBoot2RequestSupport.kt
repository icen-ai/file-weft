package ai.icen.fw.workflow.web.spring.boot2

import ai.icen.fw.workflow.web.api.WorkflowWebError
import ai.icen.fw.workflow.web.api.WorkflowWebErrorCodes
import ai.icen.fw.workflow.web.api.WorkflowWebHttpStatusPolicy
import ai.icen.fw.workflow.web.api.WorkflowIncidentQuery
import ai.icen.fw.workflow.web.api.WorkflowWebPageQuery
import ai.icen.fw.workflow.web.api.WorkflowWebResourceId
import ai.icen.fw.workflow.web.api.WorkflowWebResponse
import ai.icen.fw.workflow.web.runtime.WorkflowWebHttpResponse
import ai.icen.fw.workflow.web.runtime.WorkflowWebRequestMetadata
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import javax.servlet.http.HttpServletRequest

/** Dedicated strict decoder; it copies but never mutates the host's shared ObjectMapper. */
class FlowWeftWorkflowWebBoot2JsonCodec(objectMapper: ObjectMapper) {
    private val mapper: ObjectMapper = objectMapper.copy()
        .deactivateDefaultTyping()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    internal fun <T : Any> decode(body: ByteArray, type: Class<T>): T {
        require(body.isNotEmpty()) { "Workflow JSON body is required." }
        return try {
            mapper.readValue(body, type)
        } catch (_: Exception) {
            throw IllegalArgumentException("Workflow JSON body is invalid.")
        }
    }

    internal fun requireObject(body: ByteArray) {
        require(body.isNotEmpty()) { "Workflow JSON body is required." }
        val node: JsonNode = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            throw IllegalArgumentException("Workflow JSON body is invalid.")
        }
        require(node.isObject) { "Workflow JSON body must be an object." }
    }
}

internal class FlowWeftWorkflowWebBoot2RequestSupport(
    private val codec: FlowWeftWorkflowWebBoot2JsonCodec,
) {
    fun handle(
        request: HttpServletRequest,
        invocation: (ByteArray, WorkflowWebRequestMetadata) -> WorkflowWebHttpResponse<*>,
    ): ResponseEntity<WorkflowWebResponse<*>> = try {
        val body = readBody(request)
        val contentType = singleHeader(request, "Content-Type")
        val accept = singleHeader(request, "Accept")
        if (!acceptsJson(accept)) {
            throw WorkflowWebTransportRejection(WorkflowWebErrorCodes.NOT_ACCEPTABLE)
        }
        if ((request.method == "POST" || request.method == "PUT") && !isJson(contentType)) {
            throw WorkflowWebTransportRejection(WorkflowWebErrorCodes.UNSUPPORTED_MEDIA_TYPE)
        }
        if (request.method == "GET" && contentType != null) {
            throw WorkflowWebTransportRejection(WorkflowWebErrorCodes.UNSUPPORTED_MEDIA_TYPE)
        }
        val metadata = WorkflowWebRequestMetadata.of(
            method = request.method,
            contentType = contentType,
            accept = accept,
            verifiedBodyBytes = body.size,
            idempotencyHeaderValues = headerValues(request, "Idempotency-Key"),
            ifMatchHeaderValues = headerValues(request, "If-Match"),
        )
        project(invocation(body, metadata))
    } catch (rejection: WorkflowWebTransportRejection) {
        failureResponse(rejection.code)
    } catch (_: IllegalArgumentException) {
        invalidResponse()
    } catch (_: WorkflowWebRequestReadException) {
        invalidResponse()
    }

    fun <T : Any> decode(body: ByteArray, type: Class<T>): T = codec.decode(body, type)

    fun requireObject(body: ByteArray) = codec.requireObject(body)

    fun noQuery(request: HttpServletRequest) {
        require(request.parameterMap.isEmpty()) { "Workflow route does not accept query parameters." }
    }

    fun page(request: HttpServletRequest): WorkflowWebPageQuery {
        requireOnlyQuery(request, setOf("cursor", "limit"))
        return WorkflowWebPageQuery(
            singleQuery(request, "cursor", 1_024),
            parseLimit(singleQuery(request, "limit", 3)),
        )
    }

    fun incidentQuery(request: HttpServletRequest): WorkflowIncidentQuery {
        requireOnlyQuery(request, setOf("state", "cursor", "limit"))
        return WorkflowIncidentQuery(
            singleQuery(request, "state", 96),
            singleQuery(request, "cursor", 1_024),
            parseLimit(singleQuery(request, "limit", 3)),
        )
    }

    fun requireOnlyQuery(request: HttpServletRequest, allowed: Set<String>) {
        require(request.parameterMap.keys.all { it in allowed }) { "Workflow query parameter is unsupported." }
    }

    fun singleQuery(request: HttpServletRequest, name: String, maximumUtf8Bytes: Int): String? {
        val values = request.parameterMap[name] ?: return null
        require(values.size == 1) { "Workflow query parameter must have one value." }
        return values.single().also { value ->
            require(value.toByteArray(StandardCharsets.UTF_8).size <= maximumUtf8Bytes) {
                "Workflow query parameter is too large."
            }
            require(value.none { Character.isISOControl(it) }) { "Workflow query parameter is invalid." }
        }
    }

    fun resource(value: String): WorkflowWebResourceId = WorkflowWebResourceId.of(value)

    private fun parseLimit(value: String?): Int = value?.let {
        require(it.matches(Regex("[1-9][0-9]{0,2}"))) { "Workflow page limit is invalid." }
        it.toInt()
    } ?: WorkflowWebPageQuery.DEFAULT_LIMIT

    private fun readBody(request: HttpServletRequest): ByteArray {
        val declared = request.contentLengthLong
        require(declared <= WorkflowWebRequestMetadata.MAX_BODY_BYTES.toLong()) {
            "Workflow request body is too large."
        }
        val initial = if (declared in 1..WorkflowWebRequestMetadata.MAX_BODY_BYTES.toLong()) {
            declared.toInt()
        } else 512
        val output = ByteArrayOutputStream(initial)
        val buffer = ByteArray(8_192)
        try {
            request.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    require(output.size() + read <= WorkflowWebRequestMetadata.MAX_BODY_BYTES) {
                        "Workflow request body is too large."
                    }
                    output.write(buffer, 0, read)
                }
            }
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (_: Exception) {
            throw WorkflowWebRequestReadException()
        }
        val body = output.toByteArray()
        if (declared >= 0L) require(body.size.toLong() == declared) { "Workflow request body length is invalid." }
        validateJsonEnvelope(body)
        return body
    }

    private fun validateJsonEnvelope(body: ByteArray) {
        if (body.isEmpty()) return
        val decoded = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("Workflow request body is not valid UTF-8.")
        }
        var depth = 0
        var quoted = false
        var escaped = false
        decoded.forEach { character ->
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else if (character == '"') {
                quoted = true
            } else if (character == '{' || character == '[') {
                depth += 1
                require(depth <= MAX_JSON_DEPTH) { "Workflow JSON nesting is too deep." }
            } else if (character == '}' || character == ']') {
                depth -= 1
                require(depth >= 0) { "Workflow JSON nesting is invalid." }
            }
        }
        require(!quoted && depth == 0) { "Workflow JSON envelope is incomplete." }
    }

    private fun headerValues(request: HttpServletRequest, name: String): List<String> {
        val values = Collections.list(request.getHeaders(name))
        require(values.size <= 2) { "Workflow header has too many values." }
        values.forEach { value ->
            require(value.isNotEmpty() && value.toByteArray(StandardCharsets.UTF_8).size <= 512) {
                "Workflow header is invalid."
            }
            require(value.none { Character.isISOControl(it) }) { "Workflow header is invalid." }
        }
        return values
    }

    private fun singleHeader(request: HttpServletRequest, name: String): String? {
        val values = headerValues(request, name)
        require(values.size <= 1) { "Workflow header must have one value." }
        return values.singleOrNull()
    }

    private fun project(response: WorkflowWebHttpResponse<*>): ResponseEntity<WorkflowWebResponse<*>> {
        val headers = HttpHeaders()
        response.headers.forEach { (name, value) -> headers.set(name, value) }
        return ResponseEntity.status(response.status).headers(headers).body(response.body)
    }

    private fun invalidResponse(): ResponseEntity<WorkflowWebResponse<*>> {
        return failureResponse(WorkflowWebErrorCodes.INVALID_REQUEST)
    }

    private fun failureResponse(code: String): ResponseEntity<WorkflowWebResponse<*>> {
        val message = when (code) {
            WorkflowWebErrorCodes.NOT_ACCEPTABLE -> "The requested Workflow representation is not available."
            WorkflowWebErrorCodes.UNSUPPORTED_MEDIA_TYPE -> "Workflow requests require application/json."
            else -> "The Workflow request is invalid."
        }
        val body = WorkflowWebResponse.failure<Any>(
            WorkflowWebError(code, message),
        )
        return project(WorkflowWebHttpResponse.of(WorkflowWebHttpStatusPolicy.statusFor(body.code), body))
    }

    private class WorkflowWebRequestReadException : RuntimeException()

    private class WorkflowWebTransportRejection(val code: String) : RuntimeException()

    private fun isJson(value: String?): Boolean = value?.trim()?.lowercase()?.let {
        it == "application/json" || it == "application/json;charset=utf-8" ||
            it == "application/json; charset=utf-8"
    } == true

    private fun acceptsJson(value: String?): Boolean = value == null || value.split(',').let { ranges ->
        ranges.size <= 8 && ranges.any { range ->
            val mediaType = range.trim().substringBefore(';').lowercase()
            mediaType == "application/json" || mediaType == "*/*"
        }
    }

    companion object {
        private const val MAX_JSON_DEPTH: Int = 64
    }
}
