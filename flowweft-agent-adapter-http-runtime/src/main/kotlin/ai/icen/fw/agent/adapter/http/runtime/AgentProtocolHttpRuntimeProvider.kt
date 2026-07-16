package ai.icen.fw.agent.adapter.http.runtime

import ai.icen.fw.agent.adapter.http.A2aJsonRpcHttpCodec
import ai.icen.fw.agent.adapter.http.A2aJsonRpcHttpResponse
import ai.icen.fw.agent.adapter.http.AgentJsonRpcRemoteError
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpCodecException
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpErrorKind
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpExchangeRequest
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpTransport
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpWireRequest
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpWireResponse
import ai.icen.fw.agent.adapter.http.McpStreamableHttpCodec
import ai.icen.fw.agent.adapter.http.McpStreamableHttpResponse
import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpTransportException
import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentRemoteOperationKind
import ai.icen.fw.agent.api.AgentRemotePeerObservation
import ai.icen.fw.agent.api.AgentRemoteProtocolBindingId
import ai.icen.fw.agent.api.AgentRemoteProtocolCall
import ai.icen.fw.agent.api.AgentRemoteProtocolCapabilities
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchResult
import ai.icen.fw.agent.api.AgentRemoteProtocolKind
import ai.icen.fw.agent.api.AgentRemoteProtocolPayload
import ai.icen.fw.agent.api.AgentRemoteProtocolProvider
import ai.icen.fw.agent.api.AgentRemoteProtocolResultStatus
import ai.icen.fw.agent.api.AgentRemoteTaskSupport
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.ProviderId
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Production composition boundary for an already-authorized HTTP dispatch. It never authorizes,
 * resolves a host, reads a secret or retries. The profile-bound observer must complete before the
 * operation frame is handed to the single-hop transport.
 */
class AgentProtocolHttpRuntimeProvider @JvmOverloads constructor(
    private val configuredProviderId: ProviderId,
    private val configuredPeerId: ProviderId,
    private val configuredProtocol: AgentRemoteProtocolKind,
    private val transport: AgentProtocolHttpTransport,
    private val evidenceSource: AgentProtocolHttpRuntimeEvidenceSource,
    private val observationProvider: AgentProtocolHttpPeerObservationProvider,
    private val sessionStore: AgentProtocolMcpSessionStore = AgentProtocolMcpSessionStore.stateless(),
    private val configuration: AgentProtocolHttpRuntimeConfiguration = AgentProtocolHttpRuntimeConfiguration(),
    private val mcpCodec: McpStreamableHttpCodec = McpStreamableHttpCodec(),
    private val a2aCodec: A2aJsonRpcHttpCodec = A2aJsonRpcHttpCodec(),
    private val ids: AgentProtocolHttpRuntimeIdSource = AgentProtocolHttpRuntimeIdSource.randomUuid(),
    private val clock: AgentProtocolHttpRuntimeClock = AgentProtocolHttpRuntimeClock.system(),
    private val diagnostics: AgentProtocolHttpRuntimeDiagnosticSink = AgentProtocolHttpRuntimeDiagnosticSink.NONE,
) : AgentRemoteProtocolProvider {
    private val json: ObjectMapper = ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    override fun providerId(): ProviderId = configuredProviderId

    override fun peerId(): ProviderId = configuredPeerId

    override fun protocol(): AgentRemoteProtocolKind = configuredProtocol

    override fun bindingId(): AgentRemoteProtocolBindingId = when (configuredProtocol) {
        AgentRemoteProtocolKind.MCP -> AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP
        AgentRemoteProtocolKind.A2A -> AgentRemoteProtocolBindingId.A2A_JSON_RPC_HTTP
    }

    override fun start(request: AgentRemoteProtocolDispatchRequest): AgentRemoteProtocolCall {
        val call = RuntimeCall(request, clock, diagnostics)
        begin(request, call)
        return call
    }

    private fun begin(request: AgentRemoteProtocolDispatchRequest, call: RuntimeCall) {
        val now = clock.currentTimeMillis()
        try {
            requireDispatch(request, now)
            val observationRequest = AgentProtocolHttpPeerObservationRequest(request, now)
            val stage = requireNotNull(observationProvider.observe(observationRequest))
            call.track(stage)
            stage.whenComplete { observation, failure ->
                if (failure != null || observation == null) {
                    call.fail("protocol.http.peer-observation-failed", AgentProtocolHttpRuntimeFailurePhase.PRE_OPERATION, false)
                } else {
                    afterObservation(request, observation, call)
                }
            }
        } catch (_: RuntimeException) {
            call.fail("protocol.http.dispatch-rejected", AgentProtocolHttpRuntimeFailurePhase.PRE_OPERATION, false)
        }
    }

    private fun afterObservation(
        request: AgentRemoteProtocolDispatchRequest,
        observation: AgentRemotePeerObservation,
        call: RuntimeCall,
    ) {
        val now = clock.currentTimeMillis()
        try {
            requireDispatch(request, now)
            require(observationProvider.providerId() == configuredProviderId) {
                "Agent protocol peer observation came from another provider."
            }
            observation.requireMatches(request.profile)
            require(observation.observedAt in request.requestedAt..now && now < request.invocation.deadlineAt) {
                "Agent protocol peer observation is not fresh for this dispatch."
            }
            if (request.profile.protocol == AgentRemoteProtocolKind.MCP &&
                request.invocation.operation.operation == AgentRemoteOperationKind.MCP_TOOL_CALL
            ) {
                val loadRequest = AgentProtocolMcpSessionLoadRequest(request, now)
                val stage = requireNotNull(sessionStore.load(loadRequest))
                call.track(stage)
                stage.whenComplete { session, failure ->
                    if (failure != null) {
                        call.fail("protocol.http.session-load-failed", AgentProtocolHttpRuntimeFailurePhase.PRE_OPERATION, false)
                    } else {
                        if (session != null) {
                            try {
                                session.requireCurrentFor(request, clock.currentTimeMillis())
                            } catch (_: RuntimeException) {
                                call.fail(
                                    "protocol.http.session-binding-invalid",
                                    AgentProtocolHttpRuntimeFailurePhase.PRE_OPERATION,
                                    false,
                                )
                                return@whenComplete
                            }
                        }
                        encodeAndExchange(request, observation, session, call)
                    }
                }
            } else {
                encodeAndExchange(request, observation, null, call)
            }
        } catch (_: RuntimeException) {
            call.fail("protocol.http.peer-observation-invalid", AgentProtocolHttpRuntimeFailurePhase.PRE_OPERATION, false)
        }
    }

    private fun encodeAndExchange(
        request: AgentRemoteProtocolDispatchRequest,
        observation: AgentRemotePeerObservation,
        session: AgentProtocolMcpSessionBinding?,
        call: RuntimeCall,
    ) {
        val wire = try {
            requireDispatch(request, clock.currentTimeMillis())
            buildWireRequest(request, session).also { built -> requireWireBinding(request, built, session) }
        } catch (_: AgentProtocolHttpCodecException) {
            call.fail("protocol.http.request-codec-rejected", AgentProtocolHttpRuntimeFailurePhase.PRE_OPERATION, false)
            return
        } catch (_: RuntimeException) {
            call.fail("protocol.http.request-binding-rejected", AgentProtocolHttpRuntimeFailurePhase.PRE_OPERATION, false)
            return
        }
        val exchange = try {
            AgentProtocolHttpExchangeRequest(request, wire)
        } catch (_: RuntimeException) {
            call.fail("protocol.http.exchange-binding-rejected", AgentProtocolHttpRuntimeFailurePhase.PRE_OPERATION, false)
            return
        }
        val stage = try {
            requireNotNull(transport.exchange(exchange))
        } catch (_: RuntimeException) {
            call.fail(
                "protocol.http.transport-rejected",
                AgentProtocolHttpRuntimeFailurePhase.TRANSPORT_BEFORE_REQUEST,
                false,
            )
            return
        }
        call.track(stage)
        stage.whenComplete { response, failure ->
            claimEvidence(request, observation, session, wire, response, failure, call)
        }
    }

    private fun claimEvidence(
        request: AgentRemoteProtocolDispatchRequest,
        observation: AgentRemotePeerObservation,
        session: AgentProtocolMcpSessionBinding?,
        wire: AgentProtocolHttpWireRequest,
        response: AgentProtocolHttpWireResponse?,
        failure: Throwable?,
        call: RuntimeCall,
    ) {
        val stage = try {
            requireNotNull(evidenceSource.take(AgentProtocolHttpEvidenceQuery(request)))
        } catch (_: RuntimeException) {
            call.fail("protocol.http.evidence-unavailable", AgentProtocolHttpRuntimeFailurePhase.EVIDENCE, true)
            return
        }
        call.track(stage)
        stage.whenComplete { evidence, evidenceFailure ->
            if (evidenceFailure != null) {
                call.fail("protocol.http.evidence-unavailable", AgentProtocolHttpRuntimeFailurePhase.EVIDENCE, true)
            } else if (failure != null || response == null) {
                completeTransportFailure(request, observation, session, wire, failure, evidence, call)
            } else if (evidence == null) {
                call.fail("protocol.http.evidence-missing", AgentProtocolHttpRuntimeFailurePhase.EVIDENCE, true)
            } else {
                decodeResponse(request, observation, session, wire, response, evidence, call)
            }
        }
    }

    private fun completeTransportFailure(
        request: AgentRemoteProtocolDispatchRequest,
        observation: AgentRemotePeerObservation,
        session: AgentProtocolMcpSessionBinding?,
        wire: AgentProtocolHttpWireRequest,
        failure: Throwable?,
        evidence: AgentProtocolHttpRuntimeEvidence?,
        call: RuntimeCall,
    ) {
        val transportFailure = unwrap(failure) as? AgentProtocolHttpTransportException
        val mayHaveReached = transportFailure?.requestMayHaveReachedPeer ?: true
        if (!mayHaveReached) {
            if (evidence != null) {
                call.fail("protocol.http.evidence-conflict", AgentProtocolHttpRuntimeFailurePhase.EVIDENCE, true)
            } else {
                call.fail(
                    "protocol.http.rejected-before-request",
                    AgentProtocolHttpRuntimeFailurePhase.TRANSPORT_BEFORE_REQUEST,
                    false,
                )
            }
            return
        }
        if (evidence == null) {
            call.fail(
                "protocol.http.transport-outcome-unknown",
                AgentProtocolHttpRuntimeFailurePhase.TRANSPORT_AFTER_REQUEST,
                true,
            )
            return
        }
        try {
            requireEvidenceIdentity(request, evidence)
            val result = result(
                request,
                observation,
                session,
                wire,
                evidence,
                AgentRemoteProtocolResultStatus.OUTCOME_UNKNOWN,
                null,
                null,
                "protocol.http.transport-outcome-unknown",
                runtimeDigest(
                    "flowweft.agent.http.transport-unknown.v1",
                    evidence.evidenceDigest,
                    transportFailure?.outcome?.name ?: "UNCLASSIFIED",
                    configuration.configurationDigest,
                ),
            )
            call.complete(result, AgentProtocolHttpRuntimeDiagnosticOutcome.OUTCOME_UNKNOWN, "protocol.http.outcome-unknown")
        } catch (_: RuntimeException) {
            call.fail("protocol.http.evidence-invalid", AgentProtocolHttpRuntimeFailurePhase.EVIDENCE, true)
        }
    }

    private fun decodeResponse(
        request: AgentRemoteProtocolDispatchRequest,
        observation: AgentRemotePeerObservation,
        session: AgentProtocolMcpSessionBinding?,
        wire: AgentProtocolHttpWireRequest,
        response: AgentProtocolHttpWireResponse,
        evidence: AgentProtocolHttpRuntimeEvidence,
        call: RuntimeCall,
    ) {
        try {
            requireEvidenceIdentity(request, evidence)
            require(evidence.outcome == AgentProtocolHttpRuntimeTransportOutcome.RESPONSE &&
                evidence.statusCode == response.statusCode && evidence.responseBodyDigest == response.bodyDigest &&
                evidence.responseHeadersComplete && evidence.responseBodyComplete
            ) { "Agent protocol HTTP response differs from its recorded evidence." }
            when (request.profile.protocol) {
                AgentRemoteProtocolKind.MCP -> completeMcpResponse(request, observation, session, wire, response, evidence, call)
                AgentRemoteProtocolKind.A2A -> completeA2aResponse(request, observation, wire, response, evidence, call)
            }
        } catch (failure: AgentProtocolHttpCodecException) {
            completeCodecUnknown(request, observation, session, wire, evidence, failure.kind, call)
        } catch (_: RuntimeException) {
            completeCodecUnknown(
                request,
                observation,
                session,
                wire,
                evidence,
                AgentProtocolHttpErrorKind.INVALID_ENVELOPE,
                call,
            )
        }
    }

    private fun completeMcpResponse(
        request: AgentRemoteProtocolDispatchRequest,
        observation: AgentRemotePeerObservation,
        session: AgentProtocolMcpSessionBinding?,
        wire: AgentProtocolHttpWireRequest,
        response: AgentProtocolHttpWireResponse,
        evidence: AgentProtocolHttpRuntimeEvidence,
        call: RuntimeCall,
    ) {
        val decoded = mcpCodec.decode(wire, response)
        require(decoded.requestId == wire.requestId && decoded.operationName == wire.operationName &&
            decoded.nextCursor == null
        ) { "MCP response identity differs from the authorized operation." }
        if (request.invocation.operation.operation == AgentRemoteOperationKind.MCP_TOOL_CALL) {
            require(if (session == null) decoded.sessionId == null else decoded.sessionId == session.sessionId) {
                "MCP response session differs from the subject-bound session."
            }
        }
        val remoteError = decoded.error
        if (remoteError != null) {
            completeRemoteError(request, observation, session, wire, evidence, remoteError, call)
            return
        }
        val responsePayload = responsePayload(decoded.result(), decoded.resultDigest)
        val evidenceDigest = decodedEvidence(
            request,
            observation,
            session,
            wire,
            evidence,
            decoded.resultDigest,
            decoded.remoteTaskId,
            decoded.sessionId,
            null,
            null,
        )
        if (request.invocation.operation.operation == AgentRemoteOperationKind.INITIALIZE && decoded.sessionId != null) {
            persistSessionAndComplete(
                request,
                observation,
                wire,
                evidence,
                decoded,
                responsePayload,
                evidenceDigest,
                call,
            )
            return
        }
        val result = result(
            request,
            observation,
            session,
            wire,
            evidence,
            AgentRemoteProtocolResultStatus.SUCCEEDED,
            responsePayload,
            decoded.remoteTaskId,
            null,
            evidenceDigest,
        )
        call.complete(result, AgentProtocolHttpRuntimeDiagnosticOutcome.SUCCEEDED, "protocol.http.succeeded")
    }

    private fun persistSessionAndComplete(
        request: AgentRemoteProtocolDispatchRequest,
        observation: AgentRemotePeerObservation,
        wire: AgentProtocolHttpWireRequest,
        evidence: AgentProtocolHttpRuntimeEvidence,
        decoded: McpStreamableHttpResponse,
        responsePayload: AgentRemoteProtocolPayload?,
        evidenceDigest: String,
        call: RuntimeCall,
    ) {
        val session = try {
            val expiresAt = boundedAdd(evidence.completedAt, configuration.mcpSessionLifetimeMillis)
            AgentProtocolMcpSessionBinding(request, requireNotNull(decoded.sessionId), evidence.completedAt, expiresAt)
        } catch (_: RuntimeException) {
            completeCodecUnknown(
                request,
                observation,
                null,
                wire,
                evidence,
                AgentProtocolHttpErrorKind.IDENTITY_MISMATCH,
                call,
            )
            return
        }
        val save = try {
            requireNotNull(sessionStore.save(AgentProtocolMcpSessionSaveRequest(request, session, evidenceDigest)))
        } catch (_: RuntimeException) {
            completeSessionUnknown(request, observation, wire, evidence, call)
            return
        }
        call.track(save)
        save.whenComplete { _, failure ->
            if (failure != null) {
                completeSessionUnknown(request, observation, wire, evidence, call)
            } else {
                val result = try {
                    result(
                        request,
                        observation,
                        session,
                        wire,
                        evidence,
                        AgentRemoteProtocolResultStatus.SUCCEEDED,
                        responsePayload,
                        decoded.remoteTaskId,
                        null,
                        runtimeDigest(
                            "flowweft.agent.http.session-result.v1",
                            evidenceDigest,
                            session.bindingDigest,
                            sessionStore.storeId().value,
                        ),
                    )
                } catch (_: RuntimeException) {
                    completeSessionUnknown(request, observation, wire, evidence, call)
                    return@whenComplete
                }
                call.complete(result, AgentProtocolHttpRuntimeDiagnosticOutcome.SUCCEEDED, "protocol.http.succeeded")
            }
        }
    }

    private fun completeSessionUnknown(
        request: AgentRemoteProtocolDispatchRequest,
        observation: AgentRemotePeerObservation,
        wire: AgentProtocolHttpWireRequest,
        evidence: AgentProtocolHttpRuntimeEvidence,
        call: RuntimeCall,
    ) {
        try {
            val result = result(
                request,
                observation,
                null,
                wire,
                evidence,
                AgentRemoteProtocolResultStatus.OUTCOME_UNKNOWN,
                null,
                null,
                "protocol.http.session-persistence-unknown",
                runtimeDigest(
                    "flowweft.agent.http.session-persistence-unknown.v1",
                    evidence.evidenceDigest,
                    sessionStore.storeId().value,
                ),
            )
            call.complete(result, AgentProtocolHttpRuntimeDiagnosticOutcome.OUTCOME_UNKNOWN, "protocol.http.outcome-unknown")
        } catch (_: RuntimeException) {
            call.fail(
                "protocol.http.session-persistence-unknown",
                AgentProtocolHttpRuntimeFailurePhase.SESSION_PERSISTENCE,
                true,
            )
        }
    }

    private fun completeA2aResponse(
        request: AgentRemoteProtocolDispatchRequest,
        observation: AgentRemotePeerObservation,
        wire: AgentProtocolHttpWireRequest,
        response: AgentProtocolHttpWireResponse,
        evidence: AgentProtocolHttpRuntimeEvidence,
        call: RuntimeCall,
    ) {
        val decoded = a2aCodec.decode(wire, response)
        require(decoded.requestId == wire.requestId && decoded.operationName == wire.operationName &&
            decoded.nextPageToken == null
        ) { "A2A response identity differs from the authorized operation." }
        val remoteError = decoded.error
        if (remoteError != null) {
            completeRemoteError(request, observation, null, wire, evidence, remoteError, call)
            return
        }
        val status = if (request.invocation.operation.operation == AgentRemoteOperationKind.A2A_CANCEL_TASK) {
            AgentRemoteProtocolResultStatus.CANCELLATION_CONFIRMED
        } else {
            AgentRemoteProtocolResultStatus.SUCCEEDED
        }
        val responsePayload = responsePayload(decoded.result(), decoded.resultDigest)
        val evidenceDigest = decodedEvidence(
            request,
            observation,
            null,
            wire,
            evidence,
            decoded.resultDigest,
            decoded.remoteTaskId,
            null,
            decoded.remoteMessageId,
            null,
        )
        val result = result(
            request,
            observation,
            null,
            wire,
            evidence,
            status,
            responsePayload,
            decoded.remoteTaskId,
            null,
            evidenceDigest,
        )
        call.complete(
            result,
            if (status == AgentRemoteProtocolResultStatus.CANCELLATION_CONFIRMED) {
                AgentProtocolHttpRuntimeDiagnosticOutcome.CANCELLATION_CONFIRMED
            } else {
                AgentProtocolHttpRuntimeDiagnosticOutcome.SUCCEEDED
            },
            if (status == AgentRemoteProtocolResultStatus.CANCELLATION_CONFIRMED) {
                "protocol.http.cancellation-confirmed"
            } else {
                "protocol.http.succeeded"
            },
        )
    }

    private fun completeRemoteError(
        request: AgentRemoteProtocolDispatchRequest,
        observation: AgentRemotePeerObservation,
        session: AgentProtocolMcpSessionBinding?,
        wire: AgentProtocolHttpWireRequest,
        evidence: AgentProtocolHttpRuntimeEvidence,
        error: AgentJsonRpcRemoteError,
        call: RuntimeCall,
    ) {
        val cancellationRejected = request.invocation.operation.operation == AgentRemoteOperationKind.A2A_CANCEL_TASK &&
            error.kind in setOf(
                AgentProtocolHttpErrorKind.REMOTE_NOT_FOUND,
                AgentProtocolHttpErrorKind.REMOTE_CONFLICT,
                AgentProtocolHttpErrorKind.REMOTE_PROTOCOL_ERROR,
            )
        val status = if (cancellationRejected) {
            AgentRemoteProtocolResultStatus.CANCELLATION_REJECTED
        } else {
            AgentRemoteProtocolResultStatus.FAILED
        }
        val safeCode = if (cancellationRejected) null else remoteFailureCode(error.kind)
        val evidenceDigest = decodedEvidence(
            request,
            observation,
            session,
            wire,
            evidence,
            null,
            request.invocation.operation.remoteTaskId,
            session?.sessionId,
            null,
            error,
        )
        val result = result(
            request,
            observation,
            session,
            wire,
            evidence,
            status,
            null,
            request.invocation.operation.remoteTaskId,
            safeCode,
            evidenceDigest,
        )
        call.complete(
            result,
            if (cancellationRejected) {
                AgentProtocolHttpRuntimeDiagnosticOutcome.CANCELLATION_REJECTED
            } else {
                AgentProtocolHttpRuntimeDiagnosticOutcome.REMOTE_ERROR
            },
            safeCode ?: "protocol.http.cancellation-rejected",
        )
    }

    private fun completeCodecUnknown(
        request: AgentRemoteProtocolDispatchRequest,
        observation: AgentRemotePeerObservation,
        session: AgentProtocolMcpSessionBinding?,
        wire: AgentProtocolHttpWireRequest,
        evidence: AgentProtocolHttpRuntimeEvidence,
        kind: AgentProtocolHttpErrorKind,
        call: RuntimeCall,
    ) {
        try {
            val result = result(
                request,
                observation,
                session,
                wire,
                evidence,
                AgentRemoteProtocolResultStatus.OUTCOME_UNKNOWN,
                null,
                null,
                "protocol.http.response-invalid",
                runtimeDigest(
                    "flowweft.agent.http.response-invalid.v1",
                    request.bindingDigest,
                    wire.bodyDigest,
                    evidence.evidenceDigest,
                    kind.name,
                    configuration.configurationDigest,
                ),
            )
            call.complete(result, AgentProtocolHttpRuntimeDiagnosticOutcome.OUTCOME_UNKNOWN, "protocol.http.response-invalid")
        } catch (_: RuntimeException) {
            call.fail("protocol.http.response-invalid", AgentProtocolHttpRuntimeFailurePhase.RESPONSE_CODEC, true)
        }
    }

    private fun result(
        request: AgentRemoteProtocolDispatchRequest,
        observation: AgentRemotePeerObservation,
        session: AgentProtocolMcpSessionBinding?,
        wire: AgentProtocolHttpWireRequest,
        evidence: AgentProtocolHttpRuntimeEvidence,
        status: AgentRemoteProtocolResultStatus,
        response: AgentRemoteProtocolPayload?,
        remoteTaskId: String?,
        safeFailureCode: String?,
        evidenceDigest: String,
    ): AgentRemoteProtocolDispatchResult {
        requireEvidenceIdentity(request, evidence)
        val duration = (evidence.completedAt - request.requestedAt).coerceAtLeast(0L)
        val boundEvidence = runtimeDigest(
            "flowweft.agent.http.runtime-result-evidence.v1",
            requireRuntimeSha256(evidenceDigest),
            request.bindingDigest,
            request.profile.profileDigest,
            request.profile.descriptorDigest,
            request.profile.capabilityDigest,
            request.profile.toolCatalogDigest,
            request.profile.securitySchemeDigest,
            request.profile.profileRevision,
            request.authorizationDecision.authorizationRevision,
            request.profile.credential.credentialRevision,
            request.credentialLease.bindingDigest,
            observation.bindingDigest,
            configuration.configurationDigest,
            sessionStore.storeId().value,
            session?.bindingDigest ?: "-",
            wire.operationName,
            wire.bodyDigest,
            wire.boundToolName ?: "-",
            wire.boundArgumentsDigest ?: "-",
            wire.boundMessageId ?: "-",
            wire.boundMessageDigest ?: "-",
            wire.boundTaskId ?: "-",
            wire.boundCursor ?: "-",
            evidence.evidenceDigest,
            status.name,
        )
        return AgentRemoteProtocolDispatchResult(
            ids.nextId("agent-http-result"),
            request,
            status,
            evidence.transportReceipt,
            observation,
            response,
            null,
            remoteTaskId,
            AgentUsage(toolCalls = 1, durationMillis = duration),
            boundEvidence,
            safeFailureCode,
            evidence.completedAt,
        )
    }

    private fun buildWireRequest(
        request: AgentRemoteProtocolDispatchRequest,
        session: AgentProtocolMcpSessionBinding?,
    ): AgentProtocolHttpWireRequest {
        val invocation = request.invocation
        val operation = invocation.operation
        val requestId = request.requestId.value
        val payload = invocation.payload.bytes()
        return when (operation.operation) {
            AgentRemoteOperationKind.INITIALIZE -> {
                require(operation.protocol == AgentRemoteProtocolKind.MCP) {
                    "A2A initialization requires an Agent Card binding not exposed by the HTTP codec."
                }
                require(payload.contentEquals(EMPTY_JSON_OBJECT) && mcpCodec.canonicalDigest(payload) == invocation.payload.digest) {
                    "MCP initialization payload must be the canonical empty object."
                }
                mcpCodec.initialize(
                    requestId,
                    configuration.clientName,
                    configuration.clientVersion,
                    AgentRemoteProtocolCapabilities.MCP_TASKS_EXPERIMENTAL in request.profile.capabilities,
                )
            }
            AgentRemoteOperationKind.MCP_TOOL_CALL -> {
                require(operation.protocol == AgentRemoteProtocolKind.MCP &&
                    mcpCodec.canonicalDigest(payload) == operation.toolArgumentsDigest
                ) { "MCP tool arguments changed after authorization." }
                val exactBindings = request.profile.toolBindings.filter { binding -> binding.toolId == operation.toolId }
                require(exactBindings.size == 1 && exactBindings.single().matches(operation)) {
                    "MCP wire tool name is ambiguous or no longer approved."
                }
                val taskSupport = exactBindings.single().taskSupport
                if (taskSupport != AgentRemoteTaskSupport.NONE) {
                    require(configuration.mcpTaskTtlMillis != null) {
                        "MCP Tasks require an explicit bounded task TTL."
                    }
                }
                mcpCodec.callTool(
                    requestId,
                    requireNotNull(operation.toolId).value,
                    payload,
                    requireNotNull(operation.toolArgumentsDigest),
                    session?.sessionId,
                    if (taskSupport == AgentRemoteTaskSupport.NONE) null else configuration.mcpTaskTtlMillis,
                )
            }
            AgentRemoteOperationKind.A2A_SEND_MESSAGE -> {
                require(operation.protocol == AgentRemoteProtocolKind.A2A &&
                    a2aCodec.canonicalDigest(payload) == operation.messageDigest
                ) { "A2A message changed after authorization." }
                a2aCodec.sendMessage(
                    requestId,
                    payload,
                    requireNotNull(operation.messageId).value,
                    requireNotNull(operation.messageDigest),
                )
            }
            AgentRemoteOperationKind.A2A_CANCEL_TASK -> {
                require(operation.protocol == AgentRemoteProtocolKind.A2A &&
                    a2aCodec.canonicalDigest(payload) == operation.messageDigest
                ) { "A2A cancellation payload changed after authorization." }
                val remoteTaskId = requireNotNull(operation.remoteTaskId)
                requireCancellationPayload(payload, remoteTaskId)
                a2aCodec.cancelTask(requestId, remoteTaskId)
            }
        }
    }

    private fun requireWireBinding(
        request: AgentRemoteProtocolDispatchRequest,
        wire: AgentProtocolHttpWireRequest,
        session: AgentProtocolMcpSessionBinding?,
    ) {
        val operation = request.invocation.operation
        require(wire.protocol == operation.protocol && wire.bindingId == request.profile.bindingId &&
            wire.requestId == request.requestId.value && wire.boundCursor == null
        ) { "Agent protocol wire request changed protocol or identity." }
        when (operation.operation) {
            AgentRemoteOperationKind.INITIALIZE -> require(
                wire.operationName == "initialize" && wire.headers.value("MCP-Session-Id") == null,
            ) { "Agent protocol initialization contains an unbound session." }
            AgentRemoteOperationKind.MCP_TOOL_CALL -> require(
                wire.operationName == "tools/call" && wire.boundToolName == operation.toolId?.value &&
                    wire.boundArgumentsDigest == operation.toolArgumentsDigest &&
                    wire.headers.value("MCP-Session-Id") == session?.sessionId,
            ) { "MCP wire request changed tool arguments or session." }
            AgentRemoteOperationKind.A2A_SEND_MESSAGE -> require(
                wire.operationName == "SendMessage" && wire.boundMessageId == operation.messageId?.value &&
                    wire.boundMessageDigest == operation.messageDigest && wire.boundTaskId == null,
            ) { "A2A wire request changed message identity." }
            AgentRemoteOperationKind.A2A_CANCEL_TASK -> require(
                wire.operationName == "CancelTask" && wire.boundTaskId == operation.remoteTaskId &&
                    wire.boundMessageId == null && wire.boundMessageDigest == null,
            ) { "A2A wire cancellation changed task identity." }
        }
    }

    private fun requireDispatch(request: AgentRemoteProtocolDispatchRequest, atTime: Long) {
        require(request.profile.protocolProviderId == configuredProviderId && request.profile.peerId == configuredPeerId &&
            request.profile.protocol == configuredProtocol && request.profile.bindingId == bindingId() &&
            request.invocation.operation.peerId == configuredPeerId &&
            request.invocation.operation.protocol == configuredProtocol && request.profile.maximumRedirects == 0
        ) { "Agent protocol dispatch belongs to another configured provider." }
        request.invocation.requireCurrent(atTime)
        request.profile.requireOperation(request.invocation.operation)
        request.networkResolution.requirePublicAndCurrent(request.networkRequest, atTime)
        request.credentialLease.requireCurrentFor(request.credentialRequest, atTime)
        require(request.profile.credential.credentialRevision == request.credentialLease.credentialRevision &&
            request.authorizationRequest.providerId == request.invocation.authorizationProviderId &&
            request.authorizationDecision.providerId == request.invocation.authorizationProviderId
        ) { "Agent protocol provider or credential revision drifted." }
    }

    private fun requireEvidenceIdentity(
        request: AgentRemoteProtocolDispatchRequest,
        evidence: AgentProtocolHttpRuntimeEvidence,
    ) {
        val receipt = evidence.transportReceipt
        require(receipt.dispatchRequestId == request.requestId && receipt.dispatchBindingDigest == request.bindingDigest &&
            receipt.tlsVerified && receipt.tlsPeerIdentityDigest == request.profile.approvedTlsPeerIdentityDigest &&
            receipt.connectedAddressDigest in request.networkResolution.addresses.map { it.addressDigest } &&
            evidence.completedAt == receipt.completedAt && evidence.completedAt <= request.invocation.deadlineAt
        ) { "Agent protocol HTTP evidence belongs to another dispatch or TLS peer." }
    }

    private fun requireCancellationPayload(payload: ByteArray, taskId: String) {
        val root = json.readTree(payload)
        require(root.isObject && root.size() == 1 && root.get("task")?.isTextual == true &&
            root.get("task").textValue() == taskId
        ) { "A2A cancellation payload does not bind the exact remote task." }
    }

    private fun decodedEvidence(
        request: AgentRemoteProtocolDispatchRequest,
        observation: AgentRemotePeerObservation,
        session: AgentProtocolMcpSessionBinding?,
        wire: AgentProtocolHttpWireRequest,
        evidence: AgentProtocolHttpRuntimeEvidence,
        resultDigest: String?,
        remoteTaskId: String?,
        responseSessionId: String?,
        remoteMessageId: String?,
        error: AgentJsonRpcRemoteError?,
    ): String = runtimeDigest(
        "flowweft.agent.http.decoded-evidence.v1",
        request.bindingDigest,
        observation.bindingDigest,
        configuration.configurationDigest,
        session?.bindingDigest ?: "-",
        wire.operationName,
        wire.bodyDigest,
        evidence.evidenceDigest,
        resultDigest ?: "-",
        remoteTaskId ?: "-",
        responseSessionId ?: "-",
        remoteMessageId ?: "-",
        error?.remoteCode?.toString() ?: "-",
        error?.kind?.name ?: "-",
        error?.dataDigest ?: "-",
    )

    private fun responsePayload(bytes: ByteArray?, digest: String?): AgentRemoteProtocolPayload? {
        if (bytes == null || digest == null) return null
        return AgentRemoteProtocolPayload("application/json", bytes, digest)
    }

    private fun remoteFailureCode(kind: AgentProtocolHttpErrorKind): String = when (kind) {
        AgentProtocolHttpErrorKind.REMOTE_AUTHENTICATION -> "protocol.http.remote-authentication"
        AgentProtocolHttpErrorKind.REMOTE_AUTHORIZATION -> "protocol.http.remote-authorization"
        AgentProtocolHttpErrorKind.REMOTE_NOT_FOUND -> "protocol.http.remote-not-found"
        AgentProtocolHttpErrorKind.REMOTE_CONFLICT -> "protocol.http.remote-conflict"
        AgentProtocolHttpErrorKind.REMOTE_RATE_LIMITED -> "protocol.http.remote-rate-limited"
        AgentProtocolHttpErrorKind.REMOTE_RETRYABLE -> "protocol.http.remote-retryable"
        AgentProtocolHttpErrorKind.REMOTE_SERVER_ERROR -> "protocol.http.remote-server-error"
        AgentProtocolHttpErrorKind.UNSUPPORTED_VERSION -> "protocol.http.remote-version-unsupported"
        else -> "protocol.http.remote-protocol-error"
    }

    private fun boundedAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private fun unwrap(failure: Throwable?): Throwable? {
        var current = failure
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = current.cause
        }
        return current
    }

    private class RuntimeCall(
        private val request: AgentRemoteProtocolDispatchRequest,
        private val clock: AgentProtocolHttpRuntimeClock,
        private val diagnostics: AgentProtocolHttpRuntimeDiagnosticSink,
    ) : AgentRemoteProtocolCall {
        private val output = CompletableFuture<AgentRemoteProtocolDispatchResult>()
        private val active = AtomicReference<CompletableFuture<*>?>()
        private val cancellation = AtomicReference<AgentCancellation?>()
        private val terminal = AtomicBoolean(false)

        override fun completion(): CompletionStage<AgentRemoteProtocolDispatchResult> = output

        override fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean> {
            if (output.isDone || cancellation.requestedAt < request.requestedAt ||
                cancellation.requestedAt > request.invocation.deadlineAt ||
                !this.cancellation.compareAndSet(null, cancellation)
            ) return CompletableFuture.completedFuture(false)
            val accepted = active.get()?.cancel(true) ?: false
            return CompletableFuture.completedFuture(accepted)
        }

        fun track(stage: CompletionStage<*>) {
            val future = stage.toCompletableFuture()
            active.set(future)
            if (cancellation.get() != null) future.cancel(true)
        }

        fun complete(
            result: AgentRemoteProtocolDispatchResult,
            outcome: AgentProtocolHttpRuntimeDiagnosticOutcome,
            safeCode: String,
        ) {
            if (!terminal.compareAndSet(false, true)) return
            active.set(null)
            output.complete(result)
            diagnostic(outcome, safeCode)
        }

        fun fail(code: String, phase: AgentProtocolHttpRuntimeFailurePhase, mayHaveReached: Boolean) {
            if (!terminal.compareAndSet(false, true)) return
            active.set(null)
            val normalized = if (cancellation.get() != null && !mayHaveReached) {
                "protocol.http.cancelled-before-request"
            } else {
                code
            }
            output.completeExceptionally(AgentProtocolHttpRuntimeException(normalized, phase, mayHaveReached))
            diagnostic(
                if (mayHaveReached) AgentProtocolHttpRuntimeDiagnosticOutcome.OUTCOME_UNKNOWN
                else AgentProtocolHttpRuntimeDiagnosticOutcome.REJECTED_BEFORE_REQUEST,
                normalized,
            )
        }

        private fun diagnostic(outcome: AgentProtocolHttpRuntimeDiagnosticOutcome, safeCode: String) {
            try {
                val now = clock.currentTimeMillis().coerceAtLeast(request.requestedAt)
                diagnostics.record(
                    AgentProtocolHttpRuntimeDiagnostic(
                        request.profile.protocolProviderId,
                        request.profile.peerId,
                        request.profile.protocol,
                        operationCode(request.invocation.operation.operation),
                        safeCode,
                        outcome,
                        now - request.requestedAt,
                        now,
                    ),
                )
            } catch (_: RuntimeException) {
                // Diagnostics are deliberately payload-free and must not change dispatch semantics.
            }
        }

        private fun operationCode(operation: AgentRemoteOperationKind): String = when (operation) {
            AgentRemoteOperationKind.INITIALIZE -> "protocol.initialize"
            AgentRemoteOperationKind.MCP_TOOL_CALL -> "protocol.mcp-tool-call"
            AgentRemoteOperationKind.A2A_SEND_MESSAGE -> "protocol.a2a-send-message"
            AgentRemoteOperationKind.A2A_CANCEL_TASK -> "protocol.a2a-cancel-task"
        }
    }

    private companion object {
        val EMPTY_JSON_OBJECT = byteArrayOf('{'.code.toByte(), '}'.code.toByte())
    }
}
