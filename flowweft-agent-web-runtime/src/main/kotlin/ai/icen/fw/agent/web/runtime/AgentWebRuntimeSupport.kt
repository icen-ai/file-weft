package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.web.api.AgentWebApplicationResult
import ai.icen.fw.agent.web.api.AgentWebErrorCode
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

internal const val MAX_WEB_RUNTIME_PAGE_SIZE: Int = 200
internal const val MAX_WEB_RUNTIME_CODE_BYTES: Int = 128
internal const val MAX_WEB_RUNTIME_TOKEN_BYTES: Int = 512
internal const val MAX_WEB_RUNTIME_OUTBOX_EVENTS: Int = 64

private val WEB_RUNTIME_CODE = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")

internal fun webRuntimeCode(value: String, label: String): String {
    require(value.isNotBlank() && value == value.trim()) { "$label is invalid." }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_WEB_RUNTIME_CODE_BYTES) {
        "$label is too large."
    }
    require(WEB_RUNTIME_CODE.matches(value)) { "$label is invalid." }
    return value
}

internal fun webRuntimeToken(value: String, label: String): String {
    require(value.isNotBlank() && value == value.trim()) { "$label is invalid." }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_WEB_RUNTIME_TOKEN_BYTES) {
        "$label is too large."
    }
    require(value.none { character -> character.isISOControl() }) { "$label contains unsafe characters." }
    return value
}

internal fun webRuntimeDigest(value: String, label: String): String {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "$label is invalid."
    }
    return value
}

internal class AgentWebRuntimeDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(webRuntimeCode(domain, "Agent Web digest domain"))
    }

    fun add(value: String): AgentWebRuntimeDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): AgentWebRuntimeDigest = add(value.toString())
    fun add(value: Int): AgentWebRuntimeDigest = add(value.toString())
    fun add(value: Boolean): AgentWebRuntimeDigest = add(if (value) "1" else "0")

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

internal fun <T> immutableRuntimeList(values: Collection<T>, maximum: Int, label: String): List<T> {
    require(values.size <= maximum) { "$label contains too many values." }
    return Collections.unmodifiableList(ArrayList(values))
}

internal class AgentWebHiddenException : RuntimeException()
internal class AgentWebUnauthenticatedException : RuntimeException()
internal class AgentWebInvalidRequestException : RuntimeException()
internal class AgentWebDeniedException : RuntimeException()
internal class AgentWebUnavailableException : RuntimeException()
internal class AgentWebConflictException : RuntimeException()
internal class AgentWebPreconditionException : RuntimeException()
internal class AgentWebOutcomeUnknownException : RuntimeException()
internal class AgentWebAlreadyDecidedException : RuntimeException()
internal class AgentWebExpiredException : RuntimeException()

internal inline fun <T> agentWebApplicationCall(block: () -> AgentWebApplicationResult<T>): AgentWebApplicationResult<T> =
    try {
        block()
    } catch (_: AgentWebHiddenException) {
        AgentWebApplicationResult.hidden()
    } catch (_: AgentWebUnauthenticatedException) {
        AgentWebApplicationResult.failure(AgentWebErrorCode.UNAUTHENTICATED)
    } catch (_: AgentWebInvalidRequestException) {
        AgentWebApplicationResult.failure(AgentWebErrorCode.INVALID_REQUEST)
    } catch (_: AgentWebDeniedException) {
        AgentWebApplicationResult.failure(AgentWebErrorCode.FORBIDDEN)
    } catch (_: AgentWebPreconditionException) {
        AgentWebApplicationResult.failure(AgentWebErrorCode.PRECONDITION_FAILED)
    } catch (_: AgentWebConflictException) {
        AgentWebApplicationResult.failure(AgentWebErrorCode.CONFLICT)
    } catch (_: AgentWebAlreadyDecidedException) {
        AgentWebApplicationResult.failure(AgentWebErrorCode.APPROVAL_ALREADY_DECIDED)
    } catch (_: AgentWebExpiredException) {
        AgentWebApplicationResult.failure(AgentWebErrorCode.APPROVAL_EXPIRED)
    } catch (_: AgentWebOutcomeUnknownException) {
        AgentWebApplicationResult.failure(AgentWebErrorCode.OUTCOME_UNKNOWN)
    } catch (_: AgentWebUnavailableException) {
        AgentWebApplicationResult.failure(AgentWebErrorCode.FEATURE_UNAVAILABLE)
    } catch (_: RuntimeException) {
        AgentWebApplicationResult.failure(AgentWebErrorCode.INTERNAL_ERROR)
    }
