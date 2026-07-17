package ai.icen.fw.agent.adapter.credential.runtime

import ai.icen.fw.agent.adapter.http.AgentProtocolHttpMethod
import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpCredentialMaterial
import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpCredentialRequest
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
import ai.icen.fw.agent.api.AgentRemotePeerProfile
import ai.icen.fw.agent.api.AgentRemoteProtocolBaselines
import ai.icen.fw.agent.api.AgentRemoteProtocolBindingId
import ai.icen.fw.agent.api.AgentRemoteProtocolCapabilities
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolInvocationRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolKind
import ai.icen.fw.agent.api.AgentRemoteProtocolPayload
import ai.icen.fw.agent.api.AgentRemoteResolvedAddress
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpaqueAgentRemoteCredentialBrokerTest {
    @Test
    fun `lease replays until exact one time HTTP material acquisition consumes it`() = withScheduler { scheduler ->
        val fixture = Fixture()
        var sourceCalls = 0
        val broker = fixture.broker(scheduler) { request ->
            sourceCalls++
            assertEquals(fixture.context.tenantId, request.tenantId)
            assertEquals(fixture.profile.credential.credentialReference, request.credentialReference)
            assertFalse(request.toString().contains(fixture.context.tenantId.value))
            val token = "short-token".toCharArray()
            val material = AgentProtocolHttpCredentialMaterial.oauthBearer(token)
            java.util.Arrays.fill(token, '\u0000')
            CompletableFuture.completedFuture(
                AgentRemoteCredentialMaterialResult.bound(request, material, fixture.clock.now, 80L),
            )
        }

        val first = broker.lease(fixture.leaseRequest).toCompletableFuture().join()
        val replay = broker.lease(fixture.leaseRequest).toCompletableFuture().join()
        assertSame(first, replay)
        assertEquals(1, broker.activeLeaseCount())

        val credentialRequest = fixture.httpCredentialRequest(first)
        val material = broker.acquire(credentialRequest).toCompletableFuture().join()
        assertEquals(setOf("Authorization"), material.headerNames())
        assertEquals(1, sourceCalls)
        assertEquals(0, broker.activeLeaseCount())

        val consumed = assertFailsWith<CompletionException> {
            broker.acquire(credentialRequest).toCompletableFuture().join()
        }
        assertEquals(
            "credential.lease-missing-or-consumed",
            (consumed.cause as AgentCredentialRuntimeException).code,
        )
        material.close()
        assertTrue(material.isDestroyed())
        broker.close()
    }

    @Test
    fun `stale or mismatched source result is destroyed and never reaches transport`() = withScheduler { scheduler ->
        val fixture = Fixture()
        lateinit var sourceMaterial: AgentProtocolHttpCredentialMaterial
        val broker = fixture.broker(scheduler) { request ->
            sourceMaterial = AgentProtocolHttpCredentialMaterial.oauthBearer("short-token".toCharArray())
            val result = AgentRemoteCredentialMaterialResult.bound(request, sourceMaterial, 45L, 46L)
            fixture.clock.now = 47L
            CompletableFuture.completedFuture(result)
        }
        val lease = broker.lease(fixture.leaseRequest).toCompletableFuture().join()

        val failure = assertFailsWith<CompletionException> {
            broker.acquire(fixture.httpCredentialRequest(lease)).toCompletableFuture().join()
        }

        assertEquals(
            "credential.material-binding-mismatch",
            (failure.cause as AgentCredentialRuntimeException).code,
        )
        assertTrue(sourceMaterial.isDestroyed())
        broker.close()
    }

    @Test
    fun `material source timeout is bounded and consumes no reusable secret lease`() = withScheduler { scheduler ->
        val fixture = Fixture()
        val never = CompletableFuture<AgentRemoteCredentialMaterialResult>()
        val broker = fixture.broker(
            scheduler,
            AgentCredentialRuntimeConfiguration(50L, 25L, 10, 5),
        ) { never }
        val lease = broker.lease(fixture.leaseRequest).toCompletableFuture().join()

        val failure = assertFailsWith<CompletionException> {
            broker.acquire(fixture.httpCredentialRequest(lease)).toCompletableFuture().join()
        }

        assertEquals("credential.material-timed-out", (failure.cause as AgentCredentialRuntimeException).code)
        assertTrue(never.isCancelled)
        assertEquals(0, broker.activeLeaseCount())
        broker.close()
    }

    @Test
    fun `revocation is tenant bound and does not expose lease identity in strings`() = withScheduler { scheduler ->
        val fixture = Fixture()
        val broker = fixture.broker(scheduler) { CompletableFuture<AgentRemoteCredentialMaterialResult>() }
        val lease = broker.lease(fixture.leaseRequest).toCompletableFuture().join()

        assertFalse(broker.revoke(Identifier("another-tenant"), lease.leaseId))
        assertTrue(broker.revoke(fixture.context.tenantId, lease.leaseId))
        assertFalse(broker.toString().contains(lease.leaseId.value))
        broker.close()
    }

    private fun withScheduler(block: (java.util.concurrent.ScheduledExecutorService) -> Unit) {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            block(scheduler)
        } finally {
            scheduler.shutdownNow()
        }
    }

    private class MutableClock(var now: Long) : AgentCredentialRuntimeClock {
        override fun currentTimeMillis(): Long = now
    }

    private class Fixture {
        val clock = MutableClock(40L)
        val endpoint = URI("https://mcp.example/protocol")
        val context = AgentRunContext(id("tenant-a"), id("principal-a"), "USER", id("request-a"), 10L)
        val profile: AgentRemotePeerProfile
        val leaseRequest: AgentRemoteCredentialLeaseRequest
        private val networkRequest: AgentRemoteNetworkResolutionRequest
        private val networkResolution: AgentRemoteNetworkResolution
        private val finalAuthorization: AgentRemoteAuthorizationRequest
        private val finalDecision: AgentRemoteAuthorizationDecision
        private val invocation: AgentRemoteProtocolInvocationRequest

        init {
            val credential = AgentRemoteCredentialBinding(
                id("credential-reference"),
                PEER,
                AgentRemoteAuthenticationScheme.OAUTH2_BEARER,
                endpoint,
                listOf("mcp.invoke"),
                "credential-r1",
            )
            profile = AgentRemotePeerProfile(
                PEER,
                AgentRemoteProtocolKind.MCP,
                AgentRemoteProtocolBaselines.MCP_2025_11_25,
                AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP,
                TRANSPORT,
                RECONCILER,
                endpoint,
                "server-v1",
                digest("descriptor"),
                listOf(AgentRemoteProtocolCapabilities.MCP_INITIALIZE),
                digest("security"),
                digest("tls-peer"),
                credential,
                "profile-r1",
            )
            val payloadBytes = "{}".toByteArray(StandardCharsets.UTF_8)
            val payload = AgentRemoteProtocolPayload("application/json", payloadBytes, digest(payloadBytes))
            val operation = AgentRemoteOperationBinding(
                context,
                id("run-a"),
                id("step-a"),
                PEER,
                AgentRemoteProtocolKind.MCP,
                AgentRemoteOperationKind.INITIALIZE,
                payload.digest,
                "agent.remote.initialize",
                "remote-peer",
                id("peer-resource"),
                "1",
                "initialize reviewed peer",
            )
            invocation = AgentRemoteProtocolInvocationRequest(
                id("invocation-a"),
                operation,
                payload,
                AgentRemoteProtocolCapabilities.MCP_INITIALIZE,
                profile.profileDigest,
                AUTHORIZATION,
                AgentBudget(100, 100, 1, 1, 1_000, 0),
                AgentUsage(),
                "invocation-key-a",
                20L,
                200L,
                1_000L,
                1_024,
                AgentCancellationToken.NONE,
                null,
            )
            networkRequest = AgentRemoteNetworkResolutionRequest(
                id("network-request"),
                PEER,
                profile.profileDigest,
                endpoint,
                null,
                0,
                20L,
                200L,
            )
            networkResolution = AgentRemoteNetworkResolution(
                id("network-resolution"),
                NETWORK,
                networkRequest,
                listOf(AgentRemoteResolvedAddress(byteArrayOf(8, 8, 8, 8))),
                25L,
                100L,
            )
            finalAuthorization = AgentRemoteAuthorizationRequest(
                id("authorization-final"),
                AUTHORIZATION,
                AgentRemoteAuthorizationPhase.FINAL_DISPATCH,
                id("authorization-preflight"),
                invocation,
                context,
                profile,
                endpoint,
                networkResolution.bindingDigest,
                credential.bindingDigest,
                0,
                30L,
                100L,
            )
            finalDecision = AgentRemoteAuthorizationDecision.allow(
                id("authorization-decision"),
                AUTHORIZATION,
                finalAuthorization,
                "authorization-r1",
                30L,
                100L,
            )
            leaseRequest = AgentRemoteCredentialLeaseRequest(
                id("lease-request"),
                profile,
                invocation.bindingDigest,
                finalAuthorization,
                finalDecision,
                35L,
                100L,
            )
        }

        fun broker(
            scheduler: java.util.concurrent.ScheduledExecutorService,
            configuration: AgentCredentialRuntimeConfiguration = AgentCredentialRuntimeConfiguration(50L, 100L, 10, 5),
            source: AgentRemoteCredentialMaterialSource,
        ): OpaqueAgentRemoteCredentialBroker = OpaqueAgentRemoteCredentialBroker(
            BROKER,
            source,
            scheduler,
            configuration,
            clock,
            AgentCredentialRuntimeIdSource { id("lease-${nextId++}") },
        )

        fun httpCredentialRequest(lease: AgentRemoteCredentialLease): AgentProtocolHttpCredentialRequest {
            val dispatch = AgentRemoteProtocolDispatchRequest(
                id("dispatch-a"),
                invocation,
                profile,
                finalAuthorization,
                finalDecision,
                networkRequest,
                networkResolution,
                leaseRequest,
                lease,
                clock.now,
            )
            val constructor = AgentProtocolHttpCredentialRequest::class.java.declaredConstructors
                .single { it.parameterCount == 5 }
            constructor.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return constructor.newInstance(
                dispatch,
                AgentProtocolHttpMethod.POST,
                endpoint,
                digest("wire-body"),
                clock.now,
            ) as AgentProtocolHttpCredentialRequest
        }

        private var nextId: Int = 1
    }

    private companion object {
        val BROKER = ProviderId("credential.broker")
        val PEER = ProviderId("peer.mcp")
        val TRANSPORT = ProviderId("transport.mcp")
        val RECONCILER = ProviderId("reconciler.mcp")
        val NETWORK = ProviderId("network.jdk")
        val AUTHORIZATION = ProviderId("authorization.remote")

        fun id(value: String) = Identifier(value)

        fun digest(value: String): String = digest(value.toByteArray(StandardCharsets.UTF_8))

        fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
