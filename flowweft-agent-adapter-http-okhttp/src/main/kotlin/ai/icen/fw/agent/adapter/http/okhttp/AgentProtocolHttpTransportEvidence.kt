package ai.icen.fw.agent.adapter.http.okhttp

import ai.icen.fw.agent.api.AgentRemoteTransportReceipt
import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletionStage

enum class AgentProtocolHttpTransportOutcome {
    RESPONSE,
    REDIRECT_REJECTED,
    RESPONSE_LIMIT_REJECTED,
    REJECTED_BEFORE_DISPATCH,
    CONNECT_FAILED,
    CANCELLED_BEFORE_DISPATCH,
    CANCELLED_OUTCOME_UNKNOWN,
    OUTCOME_UNKNOWN,
}

class AgentProtocolHttpTransportException(
    code: String,
    val outcome: AgentProtocolHttpTransportOutcome,
    val requestMayHaveReachedPeer: Boolean,
) : RuntimeException("Agent protocol HTTP transport failed: ${requireTransportCode(code)}") {
    val code: String = requireTransportCode(code)

    override fun toString(): String =
        "AgentProtocolHttpTransportException(code=$code, outcome=$outcome, peerData=<redacted>)"
}

class AgentProtocolHttpExchangeEvidence internal constructor(
    val transportReceipt: AgentRemoteTransportReceipt,
    val tlsIdentityKind: AgentProtocolHttpTlsIdentityKind,
    val outcome: AgentProtocolHttpTransportOutcome,
    val statusCode: Int,
    responseHeadersDigest: String,
    responseBodyDigest: String,
    val responseHeadersComplete: Boolean,
    val responseBodyComplete: Boolean,
    val startedAt: Long,
    val requestHeadersStartedAt: Long,
    val responseHeadersReceivedAt: Long,
    val completedAt: Long,
) {
    val responseHeadersDigest: String = requireDigest(responseHeadersDigest)
    val responseBodyDigest: String = requireDigest(responseBodyDigest)
    val evidenceDigest: String

    init {
        require(statusCode == 0 || statusCode in 100..599) { "HTTP evidence status is invalid." }
        val timingValid = if (statusCode == 0) {
            startedAt <= requestHeadersStartedAt && responseHeadersReceivedAt == -1L &&
                requestHeadersStartedAt <= completedAt
        } else {
            startedAt <= requestHeadersStartedAt && requestHeadersStartedAt <= responseHeadersReceivedAt &&
                responseHeadersReceivedAt <= completedAt
        }
        require(timingValid && completedAt == transportReceipt.completedAt) {
            "HTTP evidence timing is invalid."
        }
        evidenceDigest = digestFields(
            "flowweft.agent.http.exchange-evidence.v1",
            transportReceipt.bindingDigest,
            tlsIdentityKind.name,
            outcome.name,
            statusCode.toString(),
            this.responseHeadersDigest,
            this.responseBodyDigest,
            responseHeadersComplete.toString(),
            responseBodyComplete.toString(),
            startedAt.toString(),
            requestHeadersStartedAt.toString(),
            responseHeadersReceivedAt.toString(),
            completedAt.toString(),
        )
    }

    override fun toString(): String =
        "AgentProtocolHttpExchangeEvidence(outcome=$outcome, status=$statusCode, payload=<redacted>, peer=<redacted>)"
}

fun interface AgentProtocolHttpEvidenceRecorder {
    /** Completion means the dispatch-bound evidence was durably accepted. */
    fun record(evidence: AgentProtocolHttpExchangeEvidence): CompletionStage<Void>
}

fun interface AgentProtocolHttpReceiptIdSource {
    fun nextId(): Identifier

    companion object {
        @JvmStatic
        fun randomUuid(): AgentProtocolHttpReceiptIdSource = AgentProtocolHttpReceiptIdSource {
            Identifier("agent-http-receipt-${UUID.randomUUID()}")
        }
    }
}

fun interface AgentProtocolHttpClock {
    fun currentTimeMillis(): Long

    companion object {
        @JvmStatic
        fun system(): AgentProtocolHttpClock = AgentProtocolHttpClock(System::currentTimeMillis)
    }
}

internal fun sha256Bytes(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun digestFields(domain: String, vararg fields: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(byteArrayOf(
            (bytes.size ushr 24).toByte(),
            (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(),
            bytes.size.toByte(),
        ))
        digest.update(bytes)
    }
    add(domain)
    fields.forEach(::add)
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun requireTransportCode(value: String): String {
    require(value.matches(TRANSPORT_CODE)) { "Agent protocol HTTP transport code is invalid." }
    return value
}

private fun requireDigest(value: String): String {
    require(value.matches(SHA256)) { "Agent protocol HTTP evidence digest is invalid." }
    return value
}

private val TRANSPORT_CODE = Regex("[a-z0-9]+(?:[.-][a-z0-9]+){1,15}")
private val SHA256 = Regex("[0-9a-f]{64}")
