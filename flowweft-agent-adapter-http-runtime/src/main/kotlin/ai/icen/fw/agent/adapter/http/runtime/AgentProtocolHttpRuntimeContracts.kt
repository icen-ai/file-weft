package ai.icen.fw.agent.adapter.http.runtime

import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpExchangeEvidence
import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpTransportOutcome
import ai.icen.fw.agent.api.AgentRemotePeerObservation
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolKind
import ai.icen.fw.agent.api.AgentRemoteTransportReceipt
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

enum class AgentProtocolHttpRuntimeTransportOutcome {
    RESPONSE,
    REDIRECT_REJECTED,
    RESPONSE_LIMIT_REJECTED,
    REJECTED_BEFORE_DISPATCH,
    CONNECT_FAILED,
    CANCELLED_BEFORE_DISPATCH,
    CANCELLED_OUTCOME_UNKNOWN,
    OUTCOME_UNKNOWN,
}

/**
 * Payload-free transport evidence used by the runtime bridge. The OkHttp recorder is the only
 * component that may create this value from a live exchange; tests and alternate transports may
 * construct the same bounded evidence without exposing headers, bodies, addresses or URLs.
 */
class AgentProtocolHttpRuntimeEvidence(
    val transportReceipt: AgentRemoteTransportReceipt,
    val outcome: AgentProtocolHttpRuntimeTransportOutcome,
    val statusCode: Int,
    responseHeadersDigest: String,
    responseBodyDigest: String,
    val responseHeadersComplete: Boolean,
    val responseBodyComplete: Boolean,
    val startedAt: Long,
    val requestHeadersStartedAt: Long,
    val responseHeadersReceivedAt: Long,
    val completedAt: Long,
    evidenceDigest: String,
) {
    val responseHeadersDigest: String = requireRuntimeSha256(responseHeadersDigest)
    val responseBodyDigest: String = requireRuntimeSha256(responseBodyDigest)
    val evidenceDigest: String = requireRuntimeSha256(evidenceDigest)

    init {
        require(statusCode == 0 || statusCode in 100..599) { "Agent protocol HTTP evidence status is invalid." }
        require(completedAt == transportReceipt.completedAt && startedAt <= completedAt) {
            "Agent protocol HTTP evidence timing is invalid."
        }
        require(requestHeadersStartedAt == -1L || requestHeadersStartedAt in startedAt..completedAt) {
            "Agent protocol HTTP request timing is invalid."
        }
        require(responseHeadersReceivedAt == -1L ||
            requestHeadersStartedAt != -1L && responseHeadersReceivedAt in requestHeadersStartedAt..completedAt
        ) { "Agent protocol HTTP response timing is invalid." }
        require(outcome != AgentProtocolHttpRuntimeTransportOutcome.RESPONSE ||
            statusCode != 0 && responseHeadersComplete && responseBodyComplete
        ) { "Successful Agent protocol HTTP evidence is incomplete." }
    }

    internal constructor(evidence: AgentProtocolHttpExchangeEvidence) : this(
        evidence.transportReceipt,
        evidence.outcome.toRuntimeOutcome(),
        evidence.statusCode,
        evidence.responseHeadersDigest,
        evidence.responseBodyDigest,
        evidence.responseHeadersComplete,
        evidence.responseBodyComplete,
        evidence.startedAt,
        evidence.requestHeadersStartedAt,
        evidence.responseHeadersReceivedAt,
        evidence.completedAt,
        evidence.evidenceDigest,
    )

    override fun toString(): String =
        "AgentProtocolHttpRuntimeEvidence(outcome=$outcome, status=$statusCode, payload=<redacted>, peer=<redacted>)"
}

class AgentProtocolHttpEvidenceQuery(dispatch: AgentRemoteProtocolDispatchRequest) {
    val dispatchRequestId: Identifier = dispatch.requestId
    val dispatchBindingDigest: String = dispatch.bindingDigest

    override fun toString(): String = "AgentProtocolHttpEvidenceQuery(dispatch=<redacted>)"
}

fun interface AgentProtocolHttpRuntimeEvidenceSource {
    /** Atomically claims evidence for this exact dispatch; evidence may never satisfy another call. */
    fun take(query: AgentProtocolHttpEvidenceQuery): CompletionStage<AgentProtocolHttpRuntimeEvidence?>
}

/** A fresh descriptor/Agent Card check that runs before the operation frame is emitted. */
class AgentProtocolHttpPeerObservationRequest(
    val dispatch: AgentRemoteProtocolDispatchRequest,
    val requestedAt: Long,
) {
    val bindingDigest: String

    init {
        require(requestedAt >= dispatch.requestedAt && requestedAt < dispatch.invocation.deadlineAt) {
            "Agent protocol peer observation request is outside the dispatch window."
        }
        bindingDigest = runtimeDigest(
            "flowweft.agent.http.peer-observation-request.v1",
            dispatch.bindingDigest,
            dispatch.profile.profileDigest,
            dispatch.profile.descriptorDigest,
            dispatch.profile.capabilityDigest,
            dispatch.profile.toolCatalogDigest,
            dispatch.profile.securitySchemeDigest,
            dispatch.profile.profileRevision,
            dispatch.profile.credential.credentialRevision,
            requestedAt.toString(),
        )
    }

    override fun toString(): String = "AgentProtocolHttpPeerObservationRequest(peer=<redacted>)"
}

interface AgentProtocolHttpPeerObservationProvider {
    fun providerId(): ProviderId

    /** Must revalidate the exact reviewed descriptor/card and return no remote payload. */
    fun observe(request: AgentProtocolHttpPeerObservationRequest): CompletionStage<AgentRemotePeerObservation>
}

class AgentProtocolMcpSessionBinding(
    dispatch: AgentRemoteProtocolDispatchRequest,
    sessionId: String,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    val tenantId: Identifier = dispatch.invocation.operation.context.tenantId
    val principalId: Identifier = dispatch.invocation.operation.context.principalId
    val principalType: String = dispatch.invocation.operation.context.principalType
    val runId: Identifier = dispatch.invocation.operation.runId
    val peerId: ProviderId = dispatch.profile.peerId
    val protocolProviderId: ProviderId = dispatch.profile.protocolProviderId
    val profileDigest: String = dispatch.profile.profileDigest
    val descriptorDigest: String = dispatch.profile.descriptorDigest
    val capabilityDigest: String = dispatch.profile.capabilityDigest
    val credentialRevision: String = dispatch.profile.credential.credentialRevision
    val sessionId: String = requireSessionId(sessionId)
    val bindingDigest: String

    init {
        require(dispatch.profile.protocol == AgentRemoteProtocolKind.MCP) {
            "Only MCP dispatches may create an MCP session."
        }
        require(issuedAt >= dispatch.requestedAt && expiresAt > issuedAt) {
            "Agent protocol MCP session lifetime is invalid."
        }
        bindingDigest = runtimeDigest(
            "flowweft.agent.http.mcp-session.v1",
            tenantId.value,
            principalType,
            principalId.value,
            runId.value,
            peerId.value,
            protocolProviderId.value,
            profileDigest,
            descriptorDigest,
            capabilityDigest,
            credentialRevision,
            this.sessionId,
            issuedAt.toString(),
            expiresAt.toString(),
        )
    }

    fun requireCurrentFor(dispatch: AgentRemoteProtocolDispatchRequest, atTime: Long) {
        val operation = dispatch.invocation.operation
        require(dispatch.profile.protocol == AgentRemoteProtocolKind.MCP &&
            tenantId == operation.context.tenantId && principalId == operation.context.principalId &&
            principalType == operation.context.principalType && runId == operation.runId &&
            peerId == dispatch.profile.peerId && protocolProviderId == dispatch.profile.protocolProviderId &&
            profileDigest == dispatch.profile.profileDigest && descriptorDigest == dispatch.profile.descriptorDigest &&
            capabilityDigest == dispatch.profile.capabilityDigest &&
            credentialRevision == dispatch.profile.credential.credentialRevision && atTime in issuedAt until expiresAt
        ) { "Agent protocol MCP session belongs to another subject, run, profile or credential revision." }
    }

    override fun toString(): String = "AgentProtocolMcpSessionBinding(session=<redacted>, subject=<redacted>)"
}

class AgentProtocolMcpSessionLoadRequest(
    val dispatch: AgentRemoteProtocolDispatchRequest,
    val requestedAt: Long,
) {
    val bindingDigest: String

    init {
        require(dispatch.profile.protocol == AgentRemoteProtocolKind.MCP &&
            requestedAt >= dispatch.requestedAt && requestedAt < dispatch.invocation.deadlineAt
        ) { "Agent protocol MCP session load request is invalid." }
        bindingDigest = runtimeDigest(
            "flowweft.agent.http.mcp-session-load.v1",
            dispatch.bindingDigest,
            dispatch.invocation.operation.context.tenantId.value,
            dispatch.invocation.operation.context.principalType,
            dispatch.invocation.operation.context.principalId.value,
            dispatch.invocation.operation.runId.value,
            dispatch.profile.profileDigest,
            dispatch.profile.credential.credentialRevision,
            requestedAt.toString(),
        )
    }

    override fun toString(): String = "AgentProtocolMcpSessionLoadRequest(subject=<redacted>)"
}

class AgentProtocolMcpSessionSaveRequest(
    val dispatch: AgentRemoteProtocolDispatchRequest,
    val binding: AgentProtocolMcpSessionBinding,
    resultEvidenceDigest: String,
) {
    val resultEvidenceDigest: String = requireRuntimeSha256(resultEvidenceDigest)
    val bindingDigest: String

    init {
        binding.requireCurrentFor(dispatch, binding.issuedAt)
        bindingDigest = runtimeDigest(
            "flowweft.agent.http.mcp-session-save.v1",
            dispatch.bindingDigest,
            binding.bindingDigest,
            this.resultEvidenceDigest,
        )
    }

    override fun toString(): String = "AgentProtocolMcpSessionSaveRequest(session=<redacted>)"
}

interface AgentProtocolMcpSessionStore {
    fun storeId(): ProviderId

    fun load(request: AgentProtocolMcpSessionLoadRequest): CompletionStage<AgentProtocolMcpSessionBinding?>

    /** Completion means the subject/profile-bound session was durably accepted. */
    fun save(request: AgentProtocolMcpSessionSaveRequest): CompletionStage<Void>

    companion object {
        /** Suitable only when the peer never issues MCP sessions. Issued sessions fail closed. */
        @JvmStatic
        fun stateless(): AgentProtocolMcpSessionStore = StatelessAgentProtocolMcpSessionStore
    }
}

class AgentProtocolHttpRuntimeConfiguration @JvmOverloads constructor(
    clientName: String = "flowweft",
    clientVersion: String = "1.0",
    runtimeProfileRevision: String = "1",
    val mcpSessionLifetimeMillis: Long = 3_600_000L,
    val mcpTaskTtlMillis: Long? = null,
) {
    val clientName: String = requireRuntimeToken(clientName, 128)
    val clientVersion: String = requireRuntimeToken(clientVersion, 128)
    val runtimeProfileRevision: String = requireRuntimeToken(runtimeProfileRevision, 128)
    val configurationDigest: String

    init {
        require(mcpSessionLifetimeMillis in 1_000L..86_400_000L) {
            "Agent protocol MCP session lifetime is invalid."
        }
        require(mcpTaskTtlMillis == null || mcpTaskTtlMillis in 1L..86_400_000L) {
            "Agent protocol MCP task TTL is invalid."
        }
        configurationDigest = runtimeDigest(
            "flowweft.agent.http.runtime-configuration.v1",
            this.clientName,
            this.clientVersion,
            this.runtimeProfileRevision,
            mcpSessionLifetimeMillis.toString(),
            mcpTaskTtlMillis?.toString() ?: "-",
        )
    }
}

enum class AgentProtocolHttpRuntimeFailurePhase {
    PRE_OPERATION,
    EVIDENCE,
    TRANSPORT_BEFORE_REQUEST,
    TRANSPORT_AFTER_REQUEST,
    RESPONSE_CODEC,
    SESSION_PERSISTENCE,
}

class AgentProtocolHttpRuntimeException(
    code: String,
    val phase: AgentProtocolHttpRuntimeFailurePhase,
    val requestMayHaveReachedPeer: Boolean,
) : RuntimeException("Agent protocol HTTP runtime failed: ${requireRuntimeCode(code)}") {
    val code: String = requireRuntimeCode(code)

    override fun toString(): String =
        "AgentProtocolHttpRuntimeException(code=$code, phase=$phase, peerData=<redacted>)"
}

enum class AgentProtocolHttpRuntimeDiagnosticOutcome {
    SUCCEEDED,
    REMOTE_ERROR,
    REJECTED_BEFORE_REQUEST,
    OUTCOME_UNKNOWN,
    CANCELLATION_CONFIRMED,
    CANCELLATION_REJECTED,
}

class AgentProtocolHttpRuntimeDiagnostic(
    val providerId: ProviderId,
    val peerId: ProviderId,
    val protocol: AgentRemoteProtocolKind,
    operation: String,
    safeCode: String,
    val outcome: AgentProtocolHttpRuntimeDiagnosticOutcome,
    val durationMillis: Long,
    val recordedAt: Long,
) {
    val operation: String = requireRuntimeCode(operation)
    val safeCode: String = requireRuntimeCode(safeCode)

    init {
        require(durationMillis >= 0L && recordedAt >= 0L) { "Agent protocol diagnostic timing is invalid." }
    }

    override fun toString(): String =
        "AgentProtocolHttpRuntimeDiagnostic(protocol=$protocol, operation=$operation, code=$safeCode, " +
            "outcome=$outcome, identity=<redacted>)"
}

fun interface AgentProtocolHttpRuntimeDiagnosticSink {
    /** Implementations must keep only stable codes, identifiers, counters and timing buckets. */
    fun record(diagnostic: AgentProtocolHttpRuntimeDiagnostic)

    companion object {
        @JvmField
        val NONE: AgentProtocolHttpRuntimeDiagnosticSink = AgentProtocolHttpRuntimeDiagnosticSink { }
    }
}

fun interface AgentProtocolHttpRuntimeIdSource {
    fun nextId(purpose: String): Identifier

    companion object {
        @JvmStatic
        fun randomUuid(): AgentProtocolHttpRuntimeIdSource = AgentProtocolHttpRuntimeIdSource { purpose ->
            Identifier("${requireRuntimeCode(purpose)}-${UUID.randomUUID()}")
        }
    }
}

fun interface AgentProtocolHttpRuntimeClock {
    fun currentTimeMillis(): Long

    companion object {
        @JvmStatic
        fun system(): AgentProtocolHttpRuntimeClock = AgentProtocolHttpRuntimeClock(System::currentTimeMillis)
    }
}

private object StatelessAgentProtocolMcpSessionStore : AgentProtocolMcpSessionStore {
    private val id = ProviderId("agent.http.session.stateless")

    override fun storeId(): ProviderId = id

    override fun load(
        request: AgentProtocolMcpSessionLoadRequest,
    ): CompletionStage<AgentProtocolMcpSessionBinding?> = CompletableFuture.completedFuture(null)

    override fun save(request: AgentProtocolMcpSessionSaveRequest): CompletionStage<Void> =
        CompletableFuture<Void>().also { future ->
            future.completeExceptionally(
                AgentProtocolHttpRuntimeException(
                    "protocol.http.session-store-missing",
                    AgentProtocolHttpRuntimeFailurePhase.SESSION_PERSISTENCE,
                    true,
                ),
            )
        }
}

internal fun AgentProtocolHttpTransportOutcome.toRuntimeOutcome(): AgentProtocolHttpRuntimeTransportOutcome =
    AgentProtocolHttpRuntimeTransportOutcome.valueOf(name)

internal fun requireRuntimeCode(value: String): String {
    require(value.matches(RUNTIME_CODE)) { "Agent protocol runtime code is invalid." }
    return value
}

internal fun requireRuntimeSha256(value: String): String {
    require(value.matches(RUNTIME_SHA256)) { "Agent protocol runtime digest is invalid." }
    return value
}

internal fun requireRuntimeToken(value: String, maximumCodePoints: Int): String {
    require(value.isNotBlank() && value == value.trim() &&
        value.codePointCount(0, value.length) <= maximumCodePoints &&
        value.none { character -> character.code < 0x20 || character.code == 0x7f }
    ) { "Agent protocol runtime token is invalid." }
    return value
}

internal fun requireSessionId(value: String): String {
    require(value.isNotEmpty() && value.length <= 512 && value.all { character -> character.code in 0x21..0x7e }) {
        "Agent protocol MCP session identifier is invalid."
    }
    return value
}

internal fun runtimeSha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun runtimeDigest(domain: String, vararg fields: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    add(domain)
    fields.forEach(::add)
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private val RUNTIME_CODE = Regex("[A-Za-z0-9]+(?:[._:/-][A-Za-z0-9]+){1,15}")
private val RUNTIME_SHA256 = Regex("[0-9a-f]{64}")
