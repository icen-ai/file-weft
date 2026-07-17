package ai.icen.fw.agent.adapter.http

import ai.icen.fw.agent.api.AgentRemoteOperationKind
import ai.icen.fw.agent.api.AgentRemoteProtocolBindingId
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolKind
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CompletionStage

enum class AgentProtocolHttpMethod {
    GET,
    POST,
}

enum class AgentProtocolHttpUnknownFieldPolicy {
    REJECT,
    ALLOW_DOCUMENTED_METADATA,
}

enum class AgentProtocolHttpErrorKind {
    MALFORMED_JSON,
    LIMIT_EXCEEDED,
    INVALID_CONTENT_TYPE,
    UNSUPPORTED_VERSION,
    INVALID_ENVELOPE,
    UNKNOWN_FIELD,
    IDENTITY_MISMATCH,
    UNSUPPORTED_STREAM,
    REMOTE_PROTOCOL_ERROR,
    REMOTE_AUTHENTICATION,
    REMOTE_AUTHORIZATION,
    REMOTE_NOT_FOUND,
    REMOTE_CONFLICT,
    REMOTE_RATE_LIMITED,
    REMOTE_RETRYABLE,
    REMOTE_SERVER_ERROR,
}

/** Safe, stable failure. Remote headers, payloads, messages and credentials are never retained. */
class AgentProtocolHttpCodecException(
    code: String,
    val kind: AgentProtocolHttpErrorKind,
) : IllegalArgumentException("Agent protocol HTTP codec rejected the exchange: ${requireSafeCode(code)}") {
    val code: String = requireSafeCode(code)

    override fun toString(): String = "AgentProtocolHttpCodecException(code=$code, kind=$kind)"
}

class AgentProtocolHttpCodecLimits @JvmOverloads constructor(
    val maximumBodyBytes: Int = 1_048_576,
    val maximumDepth: Int = 32,
    val maximumObjectFields: Int = 256,
    val maximumArrayItems: Int = 1_024,
    val maximumStringCodePoints: Int = 131_072,
    val maximumHeaderCount: Int = 64,
    val maximumHeaderValueCodePoints: Int = 4_096,
    val maximumSseEvents: Int = 1_024,
) {
    init {
        require(maximumBodyBytes in 1..8_388_608) { "Agent protocol body limit is invalid." }
        require(maximumDepth in 1..128) { "Agent protocol nesting limit is invalid." }
        require(maximumObjectFields in 1..4_096) { "Agent protocol object-field limit is invalid." }
        require(maximumArrayItems in 1..16_384) { "Agent protocol array-item limit is invalid." }
        require(maximumStringCodePoints in 1..1_048_576) { "Agent protocol string limit is invalid." }
        require(maximumHeaderCount in 1..256) { "Agent protocol header-count limit is invalid." }
        require(maximumHeaderValueCodePoints in 1..16_384) { "Agent protocol header-value limit is invalid." }
        require(maximumSseEvents in 1..16_384) { "Agent protocol SSE event limit is invalid." }
    }
}

/** Case-insensitive immutable headers whose string form never exposes values. */
class AgentProtocolHttpHeaders private constructor(
    private val values: Map<String, String>,
) {
    fun value(name: String): String? = values[name.lowercase(Locale.ROOT)]

    fun names(): Set<String> = Collections.unmodifiableSet(LinkedHashSet(values.keys))

    fun asMap(): Map<String, String> = values

    fun size(): Int = values.size

    override fun toString(): String = "AgentProtocolHttpHeaders(names=${values.keys}, values=<redacted>)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            headers: Map<String, String>,
            limits: AgentProtocolHttpCodecLimits = AgentProtocolHttpCodecLimits(),
        ): AgentProtocolHttpHeaders {
            if (headers.size > limits.maximumHeaderCount) {
                codecFailure("http.headers.too-many", AgentProtocolHttpErrorKind.LIMIT_EXCEEDED)
            }
            val snapshot = LinkedHashMap<String, String>()
            headers.forEach { (name, value) ->
                val normalized = name.lowercase(Locale.ROOT)
                if (!HTTP_HEADER_NAME.matches(normalized) || snapshot.containsKey(normalized)) {
                    codecFailure("http.header.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
                }
                if (value.codePointCount(0, value.length) > limits.maximumHeaderValueCodePoints ||
                    value.any { character -> character.code !in 0x20..0x7e }
                ) {
                    codecFailure("http.header-value.invalid", AgentProtocolHttpErrorKind.LIMIT_EXCEEDED)
                }
                snapshot[normalized] = value
            }
            return AgentProtocolHttpHeaders(Collections.unmodifiableMap(snapshot))
        }

        @JvmStatic
        fun empty(): AgentProtocolHttpHeaders = AgentProtocolHttpHeaders(emptyMap())
    }
}

/** Immutable codec output. The body and all bound identity values are redacted from diagnostics. */
class AgentProtocolHttpWireRequest internal constructor(
    val protocol: AgentRemoteProtocolKind,
    val bindingId: AgentRemoteProtocolBindingId,
    val httpMethod: AgentProtocolHttpMethod,
    val requestId: String,
    val operationName: String,
    val headers: AgentProtocolHttpHeaders,
    body: ByteArray,
    val boundToolName: String?,
    val boundArgumentsDigest: String?,
    val boundMessageId: String?,
    val boundMessageDigest: String?,
    val boundTaskId: String?,
    val boundCursor: String?,
    val boundTenantRoutingId: String? = null,
    val boundContextId: String? = null,
    val boundHistoryLength: Int? = null,
    val boundPageSize: Int? = null,
    val boundIncludeArtifacts: Boolean? = null,
) {
    private val bodySnapshot: ByteArray = body.copyOf()
    val bodySizeBytes: Int = bodySnapshot.size
    val bodyDigest: String = sha256(bodySnapshot)

    fun body(): ByteArray = bodySnapshot.copyOf()

    override fun toString(): String =
        "AgentProtocolHttpWireRequest(protocol=$protocol, binding=$bindingId, operation=$operationName, " +
            "bodySize=$bodySizeBytes, headers=$headers, identity=<redacted>, body=<redacted>)"
}

/** Transport response with bounded immutable headers/body and no payload-bearing string form. */
class AgentProtocolHttpWireResponse @JvmOverloads constructor(
    val statusCode: Int,
    headers: Map<String, String>,
    body: ByteArray,
    limits: AgentProtocolHttpCodecLimits = AgentProtocolHttpCodecLimits(),
) {
    val headers: AgentProtocolHttpHeaders = AgentProtocolHttpHeaders.of(headers, limits)
    private val bodySnapshot: ByteArray
    val bodySizeBytes: Int
    val bodyDigest: String

    init {
        if (statusCode !in 100..599) {
            codecFailure("http.status.invalid", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        if (body.size > limits.maximumBodyBytes) {
            codecFailure("http.response-body.too-large", AgentProtocolHttpErrorKind.LIMIT_EXCEEDED)
        }
        bodySnapshot = body.copyOf()
        bodySizeBytes = bodySnapshot.size
        bodyDigest = sha256(bodySnapshot)
    }

    fun body(): ByteArray = bodySnapshot.copyOf()

    override fun toString(): String =
        "AgentProtocolHttpWireResponse(status=$statusCode, bodySize=$bodySizeBytes, headers=$headers, body=<redacted>)"
}

class AgentJsonRpcRemoteError internal constructor(
    val remoteCode: Int,
    val kind: AgentProtocolHttpErrorKind,
    data: ByteArray?,
) {
    private val dataSnapshot: ByteArray? = data?.copyOf()
    val dataDigest: String? = dataSnapshot?.let(::sha256)

    fun data(): ByteArray? = dataSnapshot?.copyOf()

    override fun toString(): String =
        "AgentJsonRpcRemoteError(remoteCode=$remoteCode, kind=$kind, data=<redacted>)"
}

/**
 * A dispatch-bound transport command. Implementations must connect only to the already-resolved
 * address set, validate the exact approved TLS peer identity, consume the opaque credential lease
 * through its broker, disable redirects, and never perform DNS resolution or credential logging.
 */
class AgentProtocolHttpExchangeRequest(
    val dispatch: AgentRemoteProtocolDispatchRequest,
    val wireRequest: AgentProtocolHttpWireRequest,
) {
    init {
        val profile = dispatch.profile
        if (profile.protocol != wireRequest.protocol || profile.bindingId != wireRequest.bindingId ||
            profile.maximumRedirects != 0 || dispatch.networkResolution.targetUri != profile.resourceUri
        ) {
            codecFailure("http.dispatch.profile-mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
        }
        if (SENSITIVE_REQUEST_HEADERS.any { wireRequest.headers.value(it) != null }) {
            codecFailure("http.dispatch.inline-credential", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
        }
        val operation = dispatch.invocation.operation
        when (operation.operation) {
            AgentRemoteOperationKind.INITIALIZE -> if (
                operation.protocol != AgentRemoteProtocolKind.MCP || wireRequest.operationName != "initialize"
            ) {
                codecFailure("http.dispatch.initialize-mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
            }
            AgentRemoteOperationKind.MCP_TOOL_CALL -> if (
                wireRequest.operationName != "tools/call" ||
                operation.toolArgumentsDigest != wireRequest.boundArgumentsDigest
            ) {
                codecFailure("http.dispatch.arguments-mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
            }
            AgentRemoteOperationKind.A2A_SEND_MESSAGE -> if (
                wireRequest.operationName != "SendMessage" ||
                operation.messageId?.value != wireRequest.boundMessageId ||
                operation.messageDigest != wireRequest.boundMessageDigest ||
                operation.a2aTenantRoutingId != wireRequest.boundTenantRoutingId ||
                operation.a2aContextId != wireRequest.boundContextId
            ) {
                codecFailure("http.dispatch.message-mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
            }
            AgentRemoteOperationKind.A2A_GET_TASK -> if (
                wireRequest.operationName != "GetTask" || operation.remoteTaskId != wireRequest.boundTaskId ||
                operation.payloadDigest != wireRequest.boundArgumentsDigest ||
                operation.a2aTenantRoutingId != wireRequest.boundTenantRoutingId ||
                operation.a2aContextId != wireRequest.boundContextId
            ) {
                codecFailure("http.dispatch.task-read-mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
            }
            AgentRemoteOperationKind.A2A_LIST_TASKS -> if (
                wireRequest.operationName != "ListTasks" || wireRequest.boundTaskId != null ||
                operation.payloadDigest != wireRequest.boundArgumentsDigest ||
                operation.a2aTenantRoutingId != wireRequest.boundTenantRoutingId ||
                operation.a2aContextId != wireRequest.boundContextId
            ) {
                codecFailure("http.dispatch.task-list-mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
            }
            AgentRemoteOperationKind.A2A_CANCEL_TASK -> if (
                wireRequest.operationName != "CancelTask" || operation.remoteTaskId != wireRequest.boundTaskId ||
                operation.a2aTenantRoutingId != wireRequest.boundTenantRoutingId
            ) {
                codecFailure("http.dispatch.task-mismatch", AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
            }
        }
    }

    override fun toString(): String =
        "AgentProtocolHttpExchangeRequest(protocol=${wireRequest.protocol}, dispatch=<redacted>, wire=$wireRequest)"
}

fun interface AgentProtocolHttpTransport {
    /**
     * No redirect following, endpoint re-resolution, trust-all TLS, raw credential access or
     * response buffering beyond limits.
     */
    fun exchange(request: AgentProtocolHttpExchangeRequest): CompletionStage<AgentProtocolHttpWireResponse>
}

internal val SENSITIVE_REQUEST_HEADERS: Set<String> = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "x-api-key",
    "api-key",
)

internal fun codecFailure(code: String, kind: AgentProtocolHttpErrorKind): Nothing =
    throw AgentProtocolHttpCodecException(code, kind)

internal fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun requireSha256(value: String, code: String): String {
    if (!SHA256.matches(value)) {
        codecFailure(code, AgentProtocolHttpErrorKind.IDENTITY_MISMATCH)
    }
    return value
}

internal fun requireProtocolToken(value: String, code: String, maximumCodePoints: Int = 512): String {
    val codePoints = value.codePointCount(0, value.length)
    if (value.isBlank() || codePoints !in 1..maximumCodePoints ||
        value.any { character -> character.code < 0x20 || character.code == 0x7f }
    ) {
        codecFailure(code, AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
    }
    return value
}

internal fun contentType(headers: AgentProtocolHttpHeaders): String {
    val raw = headers.value("content-type")
        ?: codecFailure("http.content-type.missing", AgentProtocolHttpErrorKind.INVALID_CONTENT_TYPE)
    return raw.substringBefore(';').trim().lowercase(Locale.ROOT)
}

internal fun requestHeaders(
    values: Map<String, String>,
    limits: AgentProtocolHttpCodecLimits,
): AgentProtocolHttpHeaders {
    val headers = AgentProtocolHttpHeaders.of(values, limits)
    if (SENSITIVE_REQUEST_HEADERS.any { headers.value(it) != null }) {
        codecFailure("http.request.inline-credential", AgentProtocolHttpErrorKind.INVALID_ENVELOPE)
    }
    return headers
}

private fun requireSafeCode(value: String): String {
    require(SAFE_CODE.matches(value)) { "Agent protocol failure code is invalid." }
    return value
}

private val SAFE_CODE = Regex("[a-z0-9]+(?:[.-][a-z0-9]+){1,15}")
private val SHA256 = Regex("[0-9a-f]{64}")
private val HTTP_HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9a-z-]{1,128}")
