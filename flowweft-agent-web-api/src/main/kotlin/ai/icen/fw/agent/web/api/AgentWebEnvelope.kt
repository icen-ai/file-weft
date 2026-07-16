package ai.icen.fw.agent.web.api

import ai.icen.fw.core.id.Identifier

/** Open error vocabulary: unknown future codes remain values instead of causing enum failures. */
class AgentWebErrorCode(value: String) {
    val value: String = agentWebCode(value, "Agent Web error code")

    override fun equals(other: Any?): Boolean = other is AgentWebErrorCode && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val OK = AgentWebErrorCode("OK")
        @JvmField val INVALID_REQUEST = AgentWebErrorCode("INVALID_REQUEST")
        @JvmField val UNAUTHENTICATED = AgentWebErrorCode("UNAUTHENTICATED")
        @JvmField val FORBIDDEN = AgentWebErrorCode("FORBIDDEN")
        @JvmField val NOT_FOUND = AgentWebErrorCode("NOT_FOUND")
        @JvmField val CONFLICT = AgentWebErrorCode("CONFLICT")
        @JvmField val PRECONDITION_REQUIRED = AgentWebErrorCode("PRECONDITION_REQUIRED")
        @JvmField val PRECONDITION_FAILED = AgentWebErrorCode("PRECONDITION_FAILED")
        @JvmField val TOO_MANY_REQUESTS = AgentWebErrorCode("TOO_MANY_REQUESTS")
        @JvmField val CAPABILITY_UNSUPPORTED = AgentWebErrorCode("CAPABILITY_UNSUPPORTED")
        @JvmField val FEATURE_UNAVAILABLE = AgentWebErrorCode("FEATURE_UNAVAILABLE")
        @JvmField val APPROVAL_EXPIRED = AgentWebErrorCode("APPROVAL_EXPIRED")
        @JvmField val APPROVAL_ALREADY_DECIDED = AgentWebErrorCode("APPROVAL_ALREADY_DECIDED")
        @JvmField val OUTCOME_UNKNOWN = AgentWebErrorCode("OUTCOME_UNKNOWN")
        @JvmField val INTERNAL_ERROR = AgentWebErrorCode("INTERNAL_ERROR")
    }
}

/** Framework-neutral application result. Provider exceptions and raw failure payloads never cross it. */
class AgentWebApplicationResult<T> private constructor(
    val code: AgentWebErrorCode,
    val value: T?,
    val replayed: Boolean,
) {
    init {
        require((code == AgentWebErrorCode.OK) == (value != null)) {
            "Only successful Agent Web results may contain a value."
        }
        require(!replayed || code == AgentWebErrorCode.OK) {
            "Only successful Agent Web results may be replayed."
        }
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun <T> success(value: T, replayed: Boolean = false): AgentWebApplicationResult<T> =
            AgentWebApplicationResult(AgentWebErrorCode.OK, value, replayed)

        @JvmStatic
        fun <T> failure(code: AgentWebErrorCode): AgentWebApplicationResult<T> {
            require(code != AgentWebErrorCode.OK) { "Agent Web failure requires a failure code." }
            return AgentWebApplicationResult(code, null, false)
        }

        @JvmStatic
        fun <T> hidden(): AgentWebApplicationResult<T> = failure(AgentWebErrorCode.NOT_FOUND)

        @JvmStatic
        fun <T> unsupported(): AgentWebApplicationResult<T> = failure(AgentWebErrorCode.CAPABILITY_UNSUPPORTED)
    }
}

/** Opaque application-issued cursor. It is never interpreted as a URL by controllers or clients. */
class AgentWebCursor private constructor(token: String) {
    val token: String = agentWebText(token, AGENT_WEB_MAX_ID_BYTES, "Agent Web cursor").also { value ->
        require(TOKEN_PATTERN.matches(value)) { "Agent Web cursor is invalid." }
    }

    override fun toString(): String = "AgentWebCursor(<redacted>)"

    companion object {
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._~:-]*")

        @JvmStatic
        fun of(token: String): AgentWebCursor = AgentWebCursor(token)
    }
}

class AgentWebPageQuery @JvmOverloads constructor(
    val limit: Int = DEFAULT_LIMIT,
    val cursor: AgentWebCursor? = null,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "Agent Web page limit is invalid." }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 50
        const val MAX_LIMIT: Int = AGENT_WEB_MAX_PAGE_SIZE
    }
}

class AgentWebPage<T>(
    items: Collection<T>,
    val nextCursor: AgentWebCursor?,
) {
    val items: List<T> = agentWebList(items, AGENT_WEB_MAX_PAGE_SIZE, "Agent Web page")
}

/** Minimum receipt for a committed or idempotently replayed mutation. */
class AgentWebCommandReceiptDto(
    resourceType: String,
    resourceId: Identifier,
    val resourceVersion: Long,
    state: String,
) {
    val resourceType: String = agentWebCode(resourceType, "Agent Web receipt resource type")
    val resourceId: Identifier = agentWebIdentifier(resourceId, "Agent Web receipt resource identifier")
    val state: String = agentWebCode(state, "Agent Web receipt state")

    init {
        require(resourceVersion >= 0L) { "Agent Web receipt version must not be negative." }
    }
}

/** Safe HTTP status projection; adapters never infer it from exceptions or provider messages. */
class AgentWebHttpStatusPolicy private constructor() {
    companion object {
        @JvmStatic
        fun statusFor(code: AgentWebErrorCode): Int = when (code) {
            AgentWebErrorCode.OK -> 200
            AgentWebErrorCode.INVALID_REQUEST -> 400
            AgentWebErrorCode.UNAUTHENTICATED -> 401
            AgentWebErrorCode.FORBIDDEN -> 403
            AgentWebErrorCode.NOT_FOUND -> 404
            AgentWebErrorCode.CONFLICT,
            AgentWebErrorCode.APPROVAL_ALREADY_DECIDED -> 409
            AgentWebErrorCode.PRECONDITION_FAILED -> 412
            AgentWebErrorCode.PRECONDITION_REQUIRED -> 428
            AgentWebErrorCode.TOO_MANY_REQUESTS -> 429
            AgentWebErrorCode.CAPABILITY_UNSUPPORTED,
            AgentWebErrorCode.FEATURE_UNAVAILABLE,
            AgentWebErrorCode.OUTCOME_UNKNOWN -> 503
            AgentWebErrorCode.APPROVAL_EXPIRED -> 410
            else -> 500
        }
    }
}

class AgentWebHttpContract private constructor() {
    companion object {
        const val JSON_MEDIA_TYPE: String = "application/json"
        const val EVENT_STREAM_MEDIA_TYPE: String = "text/event-stream"
        const val IDEMPOTENCY_HEADER: String = "Idempotency-Key"
        const val IF_MATCH_HEADER: String = "If-Match"
        const val ETAG_HEADER: String = "ETag"
        const val CACHE_CONTROL_HEADER: String = "Cache-Control"
        const val CACHE_CONTROL_VALUE: String = "private, no-store"
        const val PRAGMA_HEADER: String = "Pragma"
        const val PRAGMA_VALUE: String = "no-cache"
        const val CONTENT_TYPE_OPTIONS_HEADER: String = "X-Content-Type-Options"
        const val CONTENT_TYPE_OPTIONS_VALUE: String = "nosniff"
    }
}
