package ai.icen.fw.agent.web.spring.boot2

import ai.icen.fw.agent.evaluation.AgentEvaluationDatasetReference
import ai.icen.fw.agent.web.api.AgentWebApplicationResult
import ai.icen.fw.agent.web.api.AgentWebCursor
import ai.icen.fw.agent.web.api.AgentWebDurablePage
import ai.icen.fw.agent.web.api.AgentWebErrorCode
import ai.icen.fw.agent.web.api.AgentWebHttpContract
import ai.icen.fw.agent.web.api.AgentWebHttpStatusPolicy
import ai.icen.fw.agent.web.api.AgentWebPageQuery
import ai.icen.fw.agent.web.api.AgentWebRunEventDto
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.agent.web.api.AgentWebTrustedContextProvider
import ai.icen.fw.agent.web.api.AgentWebVersionTag
import ai.icen.fw.agent.web.api.AgentWebWritePreconditions
import ai.icen.fw.core.id.Identifier
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import javax.servlet.http.HttpServletRequest

/** A dedicated strict codec. The host's shared [ObjectMapper] is copied and never mutated. */
class FlowWeftAgentWebBoot2JsonCodec(objectMapper: ObjectMapper) {
    private val mapper: ObjectMapper = objectMapper.copy()
        .deactivateDefaultTyping()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    internal fun <T : Any> decode(body: ByteArray, type: Class<T>): T {
        require(body.isNotEmpty()) { "Agent Web JSON body is required." }
        return try {
            mapper.readValue(body, type)
        } catch (_: Exception) {
            throw IllegalArgumentException("Agent Web JSON body is invalid.")
        }
    }

    internal fun encode(value: Any): String = try {
        mapper.writeValueAsString(value)
    } catch (_: Exception) {
        throw AgentWebResponseEncodingException()
    }
}

/** Stable, provider-text-free JSON envelope used by this servlet adapter. */
class FlowWeftAgentWebBoot2Response<T>(
    val code: String,
    val data: T?,
    val replayed: Boolean,
)

private class AgentWebSseCursorFrame(
    val runId: String,
    val nextSequence: Long,
    val cursor: String,
    val issuedAt: Long,
    val expiresAt: Long,
)

internal class FlowWeftAgentWebBoot2RequestSupport(
    private val codec: FlowWeftAgentWebBoot2JsonCodec,
    private val contexts: AgentWebTrustedContextProvider,
) {
    fun <P, T : Any> read(
        request: HttpServletRequest,
        preparation: () -> P,
        version: (T) -> Long? = { null },
        invocation: (AgentWebTrustedContext, P) -> AgentWebApplicationResult<T>,
    ): ResponseEntity<*> = execute(request, false) { context, _ ->
        val prepared = preparation()
        project(invokeSafely { invocation(context, prepared) }, version)
    }

    fun <P, T : Any> write(
        request: HttpServletRequest,
        preparation: (ByteArray) -> P,
        version: (T) -> Long? = { null },
        invocation: (P, AgentWebTrustedContext, AgentWebWritePreconditions) -> AgentWebApplicationResult<T>,
    ): ResponseEntity<*> = execute(request, true) { context, body ->
        val preconditions = mutationPreconditions(request)
        val prepared = preparation(body)
        project(invokeSafely { invocation(prepared, context, preconditions) }, version)
    }

    fun <P> events(
        request: HttpServletRequest,
        preparation: () -> P,
        invocation: (AgentWebTrustedContext, P) -> AgentWebApplicationResult<AgentWebDurablePage<AgentWebRunEventDto>>,
    ): ResponseEntity<*> = execute(request, false, allowEventStream = true) { context, _ ->
        val prepared = preparation()
        val result = invokeSafely { invocation(context, prepared) }
        if (wantsEventStream(singleHeader(request, "Accept"))) projectEvents(result) else project(result) { null }
    }

    fun <T : Any> decode(body: ByteArray, type: Class<T>): T = codec.decode(body, type)

    fun noQuery(request: HttpServletRequest) {
        require(request.parameterMap.isEmpty()) { "Agent Web route does not accept query parameters." }
    }

    fun page(request: HttpServletRequest): AgentWebPageQuery {
        requireOnlyQuery(request, setOf("cursor", "limit"))
        val cursor = singleQuery(request, "cursor", MAX_ID_BYTES)?.let(AgentWebCursor::of)
        val limit = singleQuery(request, "limit", 3)?.let { value ->
            require(value.matches(Regex("[1-9][0-9]{0,2}"))) { "Agent Web page limit is invalid." }
            value.toInt()
        } ?: AgentWebPageQuery.DEFAULT_LIMIT
        return AgentWebPageQuery(limit, cursor)
    }

    fun datasetReference(request: HttpServletRequest, suiteId: String): AgentEvaluationDatasetReference {
        requireOnlyQuery(request, setOf("version", "suiteDigest"))
        return AgentEvaluationDatasetReference(
            identifier(suiteId),
            requiredQuery(request, "version", MAX_ID_BYTES),
            requiredQuery(request, "suiteDigest", 64),
        )
    }

    fun identifier(value: String): Identifier {
        requireSafeText(value, MAX_ID_BYTES, "Agent Web identifier")
        return Identifier(value)
    }

    private fun execute(
        request: HttpServletRequest,
        write: Boolean,
        allowEventStream: Boolean = false,
        invocation: (AgentWebTrustedContext, ByteArray) -> ResponseEntity<*>,
    ): ResponseEntity<*> {
        return try {
            val accept = singleHeader(request, "Accept")
            requireAccepted(accept, allowEventStream)
            val contentType = singleHeader(request, "Content-Type")
            if (write) {
                require(isJson(contentType)) { "Agent Web mutations require application/json." }
            } else {
                require(contentType == null) { "Agent Web reads do not accept a request media type." }
            }
            val body = readBody(request)
            if (!write) require(body.isEmpty()) { "Agent Web GET bodies are not supported." }
            val context = try {
                contexts.currentContext()
            } catch (_: Exception) {
                return failure(AgentWebErrorCode.INTERNAL_ERROR)
            } ?: return failure(AgentWebErrorCode.UNAUTHENTICATED)
            invocation(context, body)
        } catch (rejection: AgentWebTransportRejection) {
            failure(rejection.code)
        } catch (_: IllegalArgumentException) {
            failure(AgentWebErrorCode.INVALID_REQUEST)
        } catch (_: AgentWebRequestReadException) {
            failure(AgentWebErrorCode.INVALID_REQUEST)
        } catch (_: AgentWebResponseEncodingException) {
            failure(AgentWebErrorCode.INTERNAL_ERROR)
        } catch (_: Exception) {
            failure(AgentWebErrorCode.INTERNAL_ERROR)
        }
    }

    private fun mutationPreconditions(request: HttpServletRequest): AgentWebWritePreconditions {
        val idempotencyKey = requiredMutationHeader(request, AgentWebHttpContract.IDEMPOTENCY_HEADER)
        val ifMatch = requiredMutationHeader(request, AgentWebHttpContract.IF_MATCH_HEADER)
        return AgentWebWritePreconditions.parse(idempotencyKey, ifMatch)
    }

    private fun requiredMutationHeader(request: HttpServletRequest, name: String): String {
        val values = headerValues(request, name)
        if (values.isEmpty()) throw AgentWebTransportRejection(AgentWebErrorCode.PRECONDITION_REQUIRED)
        require(values.size == 1) { "Agent Web mutation header must have one value." }
        return values.single()
    }

    private fun <T : Any> invokeSafely(invocation: () -> AgentWebApplicationResult<T>): AgentWebApplicationResult<T> =
        try {
            invocation()
        } catch (_: Exception) {
            AgentWebApplicationResult.failure(AgentWebErrorCode.INTERNAL_ERROR)
        }

    private fun <T : Any> project(
        result: AgentWebApplicationResult<T>,
        version: (T) -> Long?,
    ): ResponseEntity<FlowWeftAgentWebBoot2Response<T>> {
        val headers = commonHeaders(MediaType.APPLICATION_JSON)
        result.value?.let(version)?.let { stateVersion ->
            headers.set(AgentWebHttpContract.ETAG_HEADER, AgentWebVersionTag.of(stateVersion).toHeaderValue())
        }
        return ResponseEntity.status(AgentWebHttpStatusPolicy.statusFor(result.code))
            .headers(headers)
            .body(FlowWeftAgentWebBoot2Response(result.code.value, result.value, result.replayed))
    }

    private fun projectEvents(
        result: AgentWebApplicationResult<AgentWebDurablePage<AgentWebRunEventDto>>,
    ): ResponseEntity<*> {
        val page = result.value
        if (result.code != AgentWebErrorCode.OK || page == null) return project(result) { null }
        val body = StringBuilder()
        page.items.forEach { event ->
            body.append("id: ").append(event.sequence).append('\n')
                .append("event: ").append(event.type.value).append('\n')
                .append("data: ").append(codec.encode(event)).append("\n\n")
        }
        page.nextCursor?.let { next ->
            val frame = AgentWebSseCursorFrame(
                next.runId.value,
                next.nextSequence,
                next.cursor.token,
                next.issuedAt,
                next.expiresAt,
            )
            body.append("event: flowweft.cursor\n")
                .append("data: ").append(codec.encode(frame)).append("\n\n")
        }
        if (body.isEmpty()) body.append(": flowweft.agent.events\n\n")
        return ResponseEntity.ok()
            .headers(commonHeaders(MediaType.parseMediaType(AgentWebHttpContract.EVENT_STREAM_MEDIA_TYPE)))
            .body(body.toString())
    }

    private fun failure(code: AgentWebErrorCode): ResponseEntity<FlowWeftAgentWebBoot2Response<Any>> =
        project(AgentWebApplicationResult.failure(code)) { null }

    private fun commonHeaders(contentType: MediaType): HttpHeaders = HttpHeaders().also { headers ->
        headers.contentType = contentType
        headers.set(AgentWebHttpContract.CACHE_CONTROL_HEADER, AgentWebHttpContract.CACHE_CONTROL_VALUE)
        headers.set(AgentWebHttpContract.PRAGMA_HEADER, AgentWebHttpContract.PRAGMA_VALUE)
        headers.set(AgentWebHttpContract.CONTENT_TYPE_OPTIONS_HEADER, AgentWebHttpContract.CONTENT_TYPE_OPTIONS_VALUE)
    }

    private fun readBody(request: HttpServletRequest): ByteArray {
        val declared = request.contentLengthLong
        require(declared <= MAX_BODY_BYTES.toLong()) { "Agent Web request body is too large." }
        val output = ByteArrayOutputStream(if (declared in 1..MAX_BODY_BYTES.toLong()) declared.toInt() else 512)
        val buffer = ByteArray(8_192)
        try {
            request.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    require(output.size() + read <= MAX_BODY_BYTES) { "Agent Web request body is too large." }
                    output.write(buffer, 0, read)
                }
            }
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (_: Exception) {
            throw AgentWebRequestReadException()
        }
        val body = output.toByteArray()
        if (declared >= 0L) require(body.size.toLong() == declared) { "Agent Web request body length is invalid." }
        validateUtf8AndDepth(body)
        return body
    }

    private fun validateUtf8AndDepth(body: ByteArray) {
        if (body.isEmpty()) return
        val decoded = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("Agent Web request body is not valid UTF-8.")
        }
        var depth = 0
        var quoted = false
        var escaped = false
        decoded.forEach { character ->
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else if (character == '"') quoted = true
            else if (character == '{' || character == '[') {
                depth += 1
                require(depth <= MAX_JSON_DEPTH) { "Agent Web JSON nesting is too deep." }
            } else if (character == '}' || character == ']') {
                depth -= 1
                require(depth >= 0) { "Agent Web JSON nesting is invalid." }
            }
        }
        require(!quoted && depth == 0) { "Agent Web JSON envelope is incomplete." }
    }

    private fun requireOnlyQuery(request: HttpServletRequest, allowed: Set<String>) {
        require(request.parameterMap.keys.all { it in allowed }) { "Agent Web query parameter is unsupported." }
    }

    private fun requiredQuery(request: HttpServletRequest, name: String, maximumUtf8Bytes: Int): String =
        singleQuery(request, name, maximumUtf8Bytes)
            ?: throw IllegalArgumentException("Agent Web query parameter is required.")

    private fun singleQuery(request: HttpServletRequest, name: String, maximumUtf8Bytes: Int): String? {
        val values = request.parameterMap[name] ?: return null
        require(values.size == 1) { "Agent Web query parameter must have one value." }
        return values.single().also { value ->
            requireSafeText(value, maximumUtf8Bytes, "Agent Web query parameter")
        }
    }

    private fun requireSafeText(value: String, maximumUtf8Bytes: Int, label: String) {
        require(value.isNotBlank() && value == value.trim()) { "$label is invalid." }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= maximumUtf8Bytes) { "$label is too large." }
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            require(!Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT.toInt()) {
                "$label is invalid."
            }
            require(codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) {
                "$label is invalid."
            }
            require(codePoint !in 0xFDD0..0xFDEF && (codePoint and 0xFFFF) != 0xFFFE &&
                (codePoint and 0xFFFF) != 0xFFFF
            ) { "$label is invalid." }
            offset += Character.charCount(codePoint)
        }
    }

    private fun headerValues(request: HttpServletRequest, name: String): List<String> {
        val values = Collections.list(request.getHeaders(name))
        require(values.size <= 2) { "Agent Web header has too many values." }
        values.forEach { value -> requireSafeText(value, MAX_HEADER_BYTES, "Agent Web header") }
        return values
    }

    private fun singleHeader(request: HttpServletRequest, name: String): String? {
        val values = headerValues(request, name)
        require(values.size <= 1) { "Agent Web header must have one value." }
        return values.singleOrNull()
    }

    private fun requireAccepted(value: String?, allowEventStream: Boolean) {
        if (value == null) return
        val mediaTypes = acceptedMediaTypes(value)
        require(mediaTypes.any { it == AgentWebHttpContract.JSON_MEDIA_TYPE || it == "*/*" ||
            allowEventStream && it == AgentWebHttpContract.EVENT_STREAM_MEDIA_TYPE
        }) { "Agent Web response representation is not acceptable." }
    }

    private fun wantsEventStream(value: String?): Boolean = value != null &&
        acceptedMediaTypes(value).contains(AgentWebHttpContract.EVENT_STREAM_MEDIA_TYPE)

    private fun acceptedMediaTypes(value: String): Set<String> {
        val ranges = value.split(',')
        require(ranges.size <= 8) { "Agent Web Accept header is too large." }
        return ranges.mapNotNull { range ->
            val parts = range.trim().split(';').map(String::trim)
            val quality = parts.drop(1).firstOrNull { it.startsWith("q=", ignoreCase = true) }
                ?.substringAfter('=')?.toDoubleOrNull() ?: 1.0
            require(quality in 0.0..1.0) { "Agent Web Accept quality is invalid." }
            parts.first().lowercase().takeIf { quality > 0.0 }
        }.toSet()
    }

    private fun isJson(value: String?): Boolean = value?.trim()?.lowercase()?.let {
        it == AgentWebHttpContract.JSON_MEDIA_TYPE || it == "application/json;charset=utf-8" ||
            it == "application/json; charset=utf-8"
    } == true

    private class AgentWebTransportRejection(val code: AgentWebErrorCode) : RuntimeException()
    private class AgentWebRequestReadException : RuntimeException()

    companion object {
        private const val MAX_BODY_BYTES: Int = 128 * 1024
        private const val MAX_HEADER_BYTES: Int = 512
        private const val MAX_ID_BYTES: Int = 512
        private const val MAX_JSON_DEPTH: Int = 64
    }
}

private class AgentWebResponseEncodingException : RuntimeException()
