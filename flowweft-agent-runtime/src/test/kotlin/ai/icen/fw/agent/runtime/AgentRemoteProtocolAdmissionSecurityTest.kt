package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCancellationToken
import ai.icen.fw.agent.api.AgentRemoteAuthenticationScheme
import ai.icen.fw.agent.api.AgentRemoteCredentialBinding
import ai.icen.fw.agent.api.AgentRemoteCredentialBroker
import ai.icen.fw.agent.api.AgentRemoteCredentialLease
import ai.icen.fw.agent.api.AgentRemoteCredentialLeaseRequest
import ai.icen.fw.agent.api.AgentRemoteNetworkResolution
import ai.icen.fw.agent.api.AgentRemoteNetworkResolutionRequest
import ai.icen.fw.agent.api.AgentRemoteNetworkResolver
import ai.icen.fw.agent.api.AgentRemoteOperationBinding
import ai.icen.fw.agent.api.AgentRemoteOperationKind
import ai.icen.fw.agent.api.AgentRemotePeerProfile
import ai.icen.fw.agent.api.AgentRemoteProtocolBaselines
import ai.icen.fw.agent.api.AgentRemoteProtocolBindingId
import ai.icen.fw.agent.api.AgentRemoteProtocolCapabilities
import ai.icen.fw.agent.api.AgentRemoteProtocolInvocationRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolKind
import ai.icen.fw.agent.api.AgentRemoteProtocolPayload
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AgentRemoteProtocolAdmissionSecurityTest {

    @Test
    fun `unsupported MCP version remains an explicit safe diagnostic`() {
        val profile = profile("2026-07-28", AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP)
        val request = request(profile)

        val failure = assertThrows(CompletionException::class.java) {
            coordinator(profile).start(request).toCompletableFuture().join()
        }

        assertEquals("protocol.mcp-version-unsupported", (failure.cause as AgentRemoteProtocolRuntimeException).code)
    }

    @Test
    fun `unsupported binding remains explicit and journal keys redact subject identifiers`() {
        val profile = profile(
            AgentRemoteProtocolBaselines.MCP_2025_11_25,
            AgentRemoteProtocolBindingId("mcp.websocket"),
        )
        val request = request(profile)

        val failure = assertThrows(CompletionException::class.java) {
            coordinator(profile).start(request).toCompletableFuture().join()
        }
        assertEquals("protocol.mcp-binding-unsupported", (failure.cause as AgentRemoteProtocolRuntimeException).code)

        val scope = AgentRemoteProtocolIdempotencyScope.from(request)
        val initial = AgentRemoteProtocolInvocationState.initial(id("invocation-secret"), request, profile, 20L)
        val restored = AgentRemoteProtocolInvocationState.restore(
            initial.invocationId,
            initial.invocation,
            initial.profile,
            initial.status,
            initial.stateVersion,
            initial.hopIndex,
            initial.usage,
            initial.lastDispatch,
            initial.lastDispatchResult,
            initial.reconciliationRequest,
            initial.reconciliationResult,
            initial.authorizationRevision,
            initial.failureCode,
            initial.unknownEvidenceDigest,
            initial.createdAt,
            initial.updatedAt,
            initial.stateDigest,
        )
        assertEquals(initial.stateDigest, restored.stateDigest)
        assertThrows(IllegalArgumentException::class.java) {
            AgentRemoteProtocolInvocationState.restore(
                initial.invocationId,
                initial.invocation,
                initial.profile,
                initial.status,
                initial.stateVersion,
                initial.hopIndex,
                initial.usage,
                null,
                null,
                null,
                null,
                initial.authorizationRevision,
                initial.failureCode,
                initial.unknownEvidenceDigest,
                initial.createdAt,
                initial.updatedAt,
                digest("tampered-state"),
            )
        }
        assertFalse(scope.toString().contains("tenant-secret"))
        assertFalse(initial.key().toString().contains("tenant-secret"))
        assertFalse(initial.key().toString().contains("invocation-secret"))
    }

    @Test
    fun `unadvertised capability remains an explicit safe diagnostic`() {
        val profile = profile(
            AgentRemoteProtocolBaselines.MCP_2025_11_25,
            AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP,
            listOf(AgentRemoteProtocolCapabilities.A2A_INITIALIZE),
        )

        val failure = assertThrows(CompletionException::class.java) {
            coordinator(profile).start(request(profile)).toCompletableFuture().join()
        }

        assertEquals("protocol.capability-unsupported", (failure.cause as AgentRemoteProtocolRuntimeException).code)
    }

    private fun coordinator(profile: AgentRemotePeerProfile) = SecureAgentRemoteProtocolCoordinator(
        object : AgentRemoteProtocolDispatchJournal {
            override fun reserve(initialState: AgentRemoteProtocolInvocationState): AgentRemoteProtocolReserveResult =
                error("version admission must not reach the journal")

            override fun load(key: AgentRemoteProtocolInvocationKey): AgentRemoteProtocolInvocationState? =
                error("version admission must not reach the journal")

            override fun findByIdempotency(
                scope: AgentRemoteProtocolIdempotencyScope,
            ): AgentRemoteProtocolInvocationState? = error("version admission must not reach the journal")

            override fun compareAndSet(commit: AgentRemoteProtocolStateCommit): AgentRemoteProtocolCommitResult =
                error("version admission must not reach the journal")

            override fun outcomeUnknown(atTime: Long, limit: Int): List<AgentRemoteProtocolInvocationState> =
                error("version admission must not reach the journal")
        },
        AgentRemotePeerProfileRegistry { tenantId, peerId, protocol ->
            require(tenantId == id("tenant-secret"))
            if (peerId == profile.peerId && protocol == profile.protocol) profile else null
        },
        AgentRemoteAuthorizationProviderRegistry { null },
        object : AgentRemoteNetworkResolver {
            override fun providerId(): ProviderId = ProviderId("resolver.unreachable")

            override fun resolve(
                request: AgentRemoteNetworkResolutionRequest,
            ): java.util.concurrent.CompletionStage<AgentRemoteNetworkResolution> =
                throw AssertionError("version admission must not resolve DNS")
        },
        object : AgentRemoteCredentialBroker {
            override fun brokerId(): ProviderId = ProviderId("broker.unreachable")

            override fun lease(
                request: AgentRemoteCredentialLeaseRequest,
            ): java.util.concurrent.CompletionStage<AgentRemoteCredentialLease> =
                throw AssertionError("version admission must not lease credentials")
        },
        AgentRemoteProtocolProviderRegistry { _, _ -> null },
        AgentRemoteProtocolReconcilerRegistry { _, _ -> null },
        AgentRuntimeClock { 20L },
        AgentRuntimeIdGenerator { purpose -> id("$purpose-id") },
    )

    private fun profile(
        version: String,
        bindingId: AgentRemoteProtocolBindingId,
        capabilities: Collection<ai.icen.fw.agent.api.AgentCapabilityId> =
            listOf(AgentRemoteProtocolCapabilities.MCP_INITIALIZE),
    ): AgentRemotePeerProfile {
        val endpoint = URI("https://mcp.example/protocol")
        return AgentRemotePeerProfile(
            PEER,
            AgentRemoteProtocolKind.MCP,
            version,
            bindingId,
            TRANSPORT_PROVIDER,
            RECONCILER_PROVIDER,
            endpoint,
            "server-v1",
            digest("descriptor"),
            capabilities,
            digest("security"),
            digest("tls"),
            AgentRemoteCredentialBinding(
                id("credential-reference"),
                PEER,
                AgentRemoteAuthenticationScheme.OAUTH2_BEARER,
                endpoint,
                listOf("mcp.invoke"),
                "credential-revision",
            ),
            "profile-revision",
        )
    }

    private fun request(profile: AgentRemotePeerProfile): AgentRemoteProtocolInvocationRequest {
        val payloadBytes = "{}".toByteArray(StandardCharsets.UTF_8)
        val payload = AgentRemoteProtocolPayload("application/json", payloadBytes, digest(payloadBytes))
        val operation = AgentRemoteOperationBinding(
            AgentRunContext(id("tenant-secret"), id("principal-secret"), "USER", id("request-secret"), 10L),
            id("run-secret"),
            id("step-secret"),
            profile.peerId,
            profile.protocol,
            AgentRemoteOperationKind.INITIALIZE,
            payload.digest,
            "agent.remote.initialize",
            "remote-peer",
            id("peer-resource"),
            "1",
            "initialize reviewed peer",
        )
        return AgentRemoteProtocolInvocationRequest(
            id("invocation-request-secret"),
            operation,
            payload,
            AgentRemoteProtocolCapabilities.MCP_INITIALIZE,
            profile.profileDigest,
            ProviderId("authorization.remote"),
            AgentBudget(100, 100, 1, 1, 1_000, 0),
            AgentUsage(),
            "initialize-secret",
            20L,
            200L,
            1_000L,
            1_024,
            AgentCancellationToken.NONE,
            null,
        )
    }

    private fun digest(value: String): String = digest(value.toByteArray(StandardCharsets.UTF_8))

    private fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun id(value: String) = Identifier(value)

    private companion object {
        val PEER = ProviderId("peer.mcp")
        val TRANSPORT_PROVIDER = ProviderId("transport.mcp")
        val RECONCILER_PROVIDER = ProviderId("reconciler.mcp")
    }
}
