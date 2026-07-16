package ai.icen.fw.agent.adapter.http.runtime

import ai.icen.fw.agent.adapter.http.AgentProtocolHttpExchangeRequest
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpTransport
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpWireResponse
import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpTransportException
import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpTransportOutcome
import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCancellationToken
import ai.icen.fw.agent.api.AgentRemoteAuthenticationScheme
import ai.icen.fw.agent.api.AgentRemoteAuthorizationDecision
import ai.icen.fw.agent.api.AgentRemoteAuthorizationPhase
import ai.icen.fw.agent.api.AgentRemoteAuthorizationRequest
import ai.icen.fw.agent.api.AgentRemoteCredentialBinding
import ai.icen.fw.agent.api.AgentRemoteCredentialLease
import ai.icen.fw.agent.api.AgentRemoteCredentialLeaseRequest
import ai.icen.fw.agent.api.AgentRemoteNetworkResolution
import ai.icen.fw.agent.api.AgentRemoteNetworkResolutionRequest
import ai.icen.fw.agent.api.AgentRemoteOperationBinding
import ai.icen.fw.agent.api.AgentRemoteOperationKind
import ai.icen.fw.agent.api.AgentRemotePeerObservation
import ai.icen.fw.agent.api.AgentRemotePeerProfile
import ai.icen.fw.agent.api.AgentRemoteProtocolBaselines
import ai.icen.fw.agent.api.AgentRemoteProtocolBindingId
import ai.icen.fw.agent.api.AgentRemoteProtocolCapabilities
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolInvocationRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolKind
import ai.icen.fw.agent.api.AgentRemoteProtocolPayload
import ai.icen.fw.agent.api.AgentRemoteProtocolResultStatus
import ai.icen.fw.agent.api.AgentRemoteResolvedAddress
import ai.icen.fw.agent.api.AgentRemoteTransportReceipt
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentProtocolHttpRuntimeProviderTest {
    @Test
    fun `authorized MCP initialization is observed encoded transported decoded and evidenced once`() {
        val fixture = fixture()
        val response = response(
            fixture.dispatch,
            200,
            """{"jsonrpc":"2.0","id":"dispatch-1","result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"peer","version":"1"}}}""",
        )
        val transport = CapturingTransport(CompletableFuture.completedFuture(response))
        val diagnostics = ArrayList<AgentProtocolHttpRuntimeDiagnostic>()
        val provider = provider(fixture, transport, evidenceSource(fixture, response), diagnostics)

        val result = provider.start(fixture.dispatch).completion().toCompletableFuture().join()

        assertEquals(AgentRemoteProtocolResultStatus.SUCCEEDED, result.status)
        assertNotNull(result.response)
        assertNull(result.remoteTaskId)
        assertEquals(fixture.observation.bindingDigest, result.observation?.bindingDigest)
        assertEquals(1, result.usage.toolCalls)
        assertEquals("initialize", transport.request?.wireRequest?.operationName)
        assertFalse(transport.request.toString().contains("tenant-secret"))
        assertEquals(AgentProtocolHttpRuntimeDiagnosticOutcome.SUCCEEDED, diagnostics.single().outcome)
    }

    @Test
    fun `explicit remote JSON RPC error becomes a known safe failure without retaining remote message`() {
        val fixture = fixture()
        val response = response(
            fixture.dispatch,
            400,
            """{"jsonrpc":"2.0","id":"dispatch-1","error":{"code":-32602,"message":"remote-secret-message"}}""",
        )
        val provider = provider(
            fixture,
            CapturingTransport(CompletableFuture.completedFuture(response)),
            evidenceSource(fixture, response),
            ArrayList(),
        )

        val result = provider.start(fixture.dispatch).completion().toCompletableFuture().join()

        assertEquals(AgentRemoteProtocolResultStatus.FAILED, result.status)
        assertEquals("protocol.http.remote-protocol-error", result.safeFailureCode)
        assertNull(result.response)
        assertFalse(result.toString().contains("remote-secret-message"))
    }

    @Test
    fun `response identity failure after request started becomes outcome unknown`() {
        val fixture = fixture()
        val response = response(
            fixture.dispatch,
            200,
            """{"jsonrpc":"2.0","id":"another-dispatch","result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"peer","version":"1"}}}""",
        )
        val provider = provider(
            fixture,
            CapturingTransport(CompletableFuture.completedFuture(response)),
            evidenceSource(fixture, response),
            ArrayList(),
        )

        val result = provider.start(fixture.dispatch).completion().toCompletableFuture().join()

        assertEquals(AgentRemoteProtocolResultStatus.OUTCOME_UNKNOWN, result.status)
        assertEquals("protocol.http.response-invalid", result.safeFailureCode)
        assertNull(result.response)
    }

    @Test
    fun `transport rejection before request is classified without fabricating TLS receipt`() {
        val fixture = fixture()
        val failed = CompletableFuture<AgentProtocolHttpWireResponse>()
        failed.completeExceptionally(
            AgentProtocolHttpTransportException(
                "http-connect-failed",
                AgentProtocolHttpTransportOutcome.CONNECT_FAILED,
                false,
            ),
        )
        val provider = provider(
            fixture,
            CapturingTransport(failed),
            AgentProtocolHttpRuntimeEvidenceSource { CompletableFuture.completedFuture(null) },
            ArrayList(),
        )

        val failure = try {
            provider.start(fixture.dispatch).completion().toCompletableFuture().join()
            error("Expected dispatch rejection")
        } catch (expected: CompletionException) {
            expected.cause
        }

        val classified = assertIs<AgentProtocolHttpRuntimeException>(failure)
        assertEquals(AgentProtocolHttpRuntimeFailurePhase.TRANSPORT_BEFORE_REQUEST, classified.phase)
        assertFalse(classified.requestMayHaveReachedPeer)
        assertEquals("protocol.http.rejected-before-request", classified.code)
    }

    @Test
    fun `profile observation drift fails before transport`() {
        val fixture = fixture()
        val transport = CapturingTransport(CompletableFuture.completedFuture(response(fixture.dispatch, 200, "{}")))
        val drifted = AgentRemotePeerObservation(
            fixture.profile.peerId,
            fixture.profile.protocol,
            fixture.profile.protocolVersion,
            fixture.profile.bindingId,
            fixture.profile.descriptorVersion,
            digest("drifted-descriptor"),
            fixture.profile.capabilityDigest,
            fixture.profile.toolCatalogDigest,
            fixture.profile.securitySchemeDigest,
            45L,
        )
        val provider = AgentProtocolHttpRuntimeProvider(
            fixture.profile.protocolProviderId,
            fixture.profile.peerId,
            fixture.profile.protocol,
            transport,
            AgentProtocolHttpRuntimeEvidenceSource { CompletableFuture.completedFuture(null) },
            observationProvider(fixture, drifted),
            clock = AgentProtocolHttpRuntimeClock { 45L },
            ids = AgentProtocolHttpRuntimeIdSource { purpose -> id("$purpose-test") },
        )

        val failure = try {
            provider.start(fixture.dispatch).completion().toCompletableFuture().join()
            error("Expected observation rejection")
        } catch (expected: CompletionException) {
            expected.cause
        }

        assertIs<AgentProtocolHttpRuntimeException>(failure)
        assertNull(transport.request)
    }

    private fun provider(
        fixture: Fixture,
        transport: AgentProtocolHttpTransport,
        evidence: AgentProtocolHttpRuntimeEvidenceSource,
        diagnostics: MutableList<AgentProtocolHttpRuntimeDiagnostic>,
    ): AgentProtocolHttpRuntimeProvider = AgentProtocolHttpRuntimeProvider(
        fixture.profile.protocolProviderId,
        fixture.profile.peerId,
        fixture.profile.protocol,
        transport,
        evidence,
        observationProvider(fixture, fixture.observation),
        clock = AgentProtocolHttpRuntimeClock { 45L },
        ids = AgentProtocolHttpRuntimeIdSource { purpose -> id("$purpose-test") },
        diagnostics = AgentProtocolHttpRuntimeDiagnosticSink { diagnostic -> diagnostics.add(diagnostic) },
    )

    private fun observationProvider(
        fixture: Fixture,
        observation: AgentRemotePeerObservation,
    ): AgentProtocolHttpPeerObservationProvider = object : AgentProtocolHttpPeerObservationProvider {
        override fun providerId(): ProviderId = fixture.profile.protocolProviderId

        override fun observe(
            request: AgentProtocolHttpPeerObservationRequest,
        ): CompletionStage<AgentRemotePeerObservation> = CompletableFuture.completedFuture(observation)
    }

    private fun evidenceSource(
        fixture: Fixture,
        response: AgentProtocolHttpWireResponse,
    ): AgentProtocolHttpRuntimeEvidenceSource = AgentProtocolHttpRuntimeEvidenceSource {
        val receipt = AgentRemoteTransportReceipt(
            id("transport-receipt-1"),
            fixture.dispatch,
            fixture.dispatch.networkResolution.addresses.single().addressDigest,
            fixture.profile.approvedTlsPeerIdentityDigest,
            true,
            50L,
        )
        CompletableFuture.completedFuture(
            AgentProtocolHttpRuntimeEvidence(
                receipt,
                AgentProtocolHttpRuntimeTransportOutcome.RESPONSE,
                response.statusCode,
                digest("response-headers"),
                response.bodyDigest,
                true,
                true,
                42L,
                43L,
                49L,
                50L,
                digest("exchange-evidence"),
            ),
        )
    }

    private fun fixture(): Fixture {
        val endpoint = URI("https://mcp.example/protocol")
        val credential = AgentRemoteCredentialBinding(
            id("credential-reference"),
            PEER,
            AgentRemoteAuthenticationScheme.OAUTH2_BEARER,
            endpoint,
            listOf("mcp.invoke"),
            "credential-revision-1",
        )
        val profile = AgentRemotePeerProfile(
            PEER,
            AgentRemoteProtocolKind.MCP,
            AgentRemoteProtocolBaselines.MCP_2025_11_25,
            AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP,
            PROVIDER,
            RECONCILER,
            endpoint,
            "descriptor-v1",
            digest("descriptor"),
            listOf(AgentRemoteProtocolCapabilities.MCP_INITIALIZE),
            digest("security"),
            digest("tls-peer"),
            credential,
            "profile-revision-1",
        )
        val payloadBytes = "{}".toByteArray(StandardCharsets.UTF_8)
        val payload = AgentRemoteProtocolPayload("application/json", payloadBytes, digest(payloadBytes))
        val context = AgentRunContext(id("tenant-secret"), id("principal-secret"), "USER", id("caller-request"), 10L)
        val operation = AgentRemoteOperationBinding(
            context,
            id("run-1"),
            id("step-1"),
            profile.peerId,
            profile.protocol,
            AgentRemoteOperationKind.INITIALIZE,
            payload.digest,
            "agent.remote.initialize",
            "remote-peer",
            id("peer-resource"),
            "resource-revision-1",
            "initialize reviewed peer",
        )
        val invocation = AgentRemoteProtocolInvocationRequest(
            id("invocation-1"),
            operation,
            payload,
            AgentRemoteProtocolCapabilities.MCP_INITIALIZE,
            profile.profileDigest,
            AUTHORIZATION,
            AgentBudget(100, 100, 1, 1, 1_000L, 0L),
            AgentUsage(),
            "initialize-idempotency",
            20L,
            900L,
            1_200L,
            4_096,
            AgentCancellationToken.NONE,
            null,
        )
        val resolutionRequest = AgentRemoteNetworkResolutionRequest(
            id("resolution-request-1"),
            profile.peerId,
            profile.profileDigest,
            endpoint,
            null,
            0,
            25L,
            800L,
        )
        val resolution = AgentRemoteNetworkResolution(
            id("resolution-1"),
            NETWORK,
            resolutionRequest,
            listOf(AgentRemoteResolvedAddress(byteArrayOf(8, 8, 8, 8))),
            26L,
            800L,
        )
        val preflight = AgentRemoteAuthorizationRequest(
            id("authorization-preflight-1"),
            AUTHORIZATION,
            AgentRemoteAuthorizationPhase.PREFLIGHT,
            null,
            invocation,
            context,
            profile,
            endpoint,
            resolution.bindingDigest,
            credential.bindingDigest,
            0,
            27L,
            700L,
        )
        val finalAuthorization = AgentRemoteAuthorizationRequest(
            id("authorization-final-1"),
            AUTHORIZATION,
            AgentRemoteAuthorizationPhase.FINAL_DISPATCH,
            preflight.requestId,
            invocation,
            context,
            profile,
            endpoint,
            resolution.bindingDigest,
            credential.bindingDigest,
            0,
            30L,
            650L,
        )
        finalAuthorization.requireChildOf(preflight)
        val decision = AgentRemoteAuthorizationDecision.allow(
            id("authorization-decision-1"),
            AUTHORIZATION,
            finalAuthorization,
            "authorization-revision-1",
            31L,
            600L,
        )
        val credentialRequest = AgentRemoteCredentialLeaseRequest(
            id("credential-request-1"),
            profile,
            invocation.bindingDigest,
            finalAuthorization,
            decision,
            32L,
            550L,
        )
        val lease = AgentRemoteCredentialLease(
            id("credential-lease-1"),
            BROKER,
            credentialRequest,
            credential.credentialReference,
            profile.peerId,
            endpoint,
            credential.credentialRevision,
            33L,
            500L,
        )
        val dispatch = AgentRemoteProtocolDispatchRequest(
            id("dispatch-1"),
            invocation,
            profile,
            finalAuthorization,
            decision,
            resolutionRequest,
            resolution,
            credentialRequest,
            lease,
            40L,
        )
        val observation = AgentRemotePeerObservation(
            profile.peerId,
            profile.protocol,
            profile.protocolVersion,
            profile.bindingId,
            profile.descriptorVersion,
            profile.descriptorDigest,
            profile.capabilityDigest,
            profile.toolCatalogDigest,
            profile.securitySchemeDigest,
            45L,
        )
        return Fixture(profile, dispatch, observation)
    }

    private fun response(
        dispatch: AgentRemoteProtocolDispatchRequest,
        status: Int,
        body: String,
    ): AgentProtocolHttpWireResponse {
        assertEquals("dispatch-1", dispatch.requestId.value)
        return AgentProtocolHttpWireResponse(
            status,
            mapOf("Content-Type" to "application/json"),
            body.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun digest(value: String): String = digest(value.toByteArray(StandardCharsets.UTF_8))

    private fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun id(value: String): Identifier = Identifier(value)

    private class CapturingTransport(
        private val stage: CompletionStage<AgentProtocolHttpWireResponse>,
    ) : AgentProtocolHttpTransport {
        var request: AgentProtocolHttpExchangeRequest? = null

        override fun exchange(request: AgentProtocolHttpExchangeRequest): CompletionStage<AgentProtocolHttpWireResponse> {
            this.request = request
            return stage
        }
    }

    private class Fixture(
        val profile: AgentRemotePeerProfile,
        val dispatch: AgentRemoteProtocolDispatchRequest,
        val observation: AgentRemotePeerObservation,
    )

    private companion object {
        val PEER = ProviderId("peer.mcp")
        val PROVIDER = ProviderId("protocol.http")
        val RECONCILER = ProviderId("reconciler.http")
        val AUTHORIZATION = ProviderId("authorization.remote")
        val NETWORK = ProviderId("network.remote")
        val BROKER = ProviderId("credential.broker")
    }
}
