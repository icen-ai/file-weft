package ai.icen.fw.agent.adapter.http.runtime

import ai.icen.fw.agent.adapter.http.AgentProtocolHttpExchangeRequest
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpTransport
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpWireResponse
import ai.icen.fw.agent.api.AgentAuthorizationDecision
import ai.icen.fw.agent.api.AgentAuthorizationRequest
import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCancellationToken
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceConsumption
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceRequest
import ai.icen.fw.agent.api.AgentExecutableToolInvocation
import ai.icen.fw.agent.api.AgentExecutionContextConsumption
import ai.icen.fw.agent.api.AgentPolicyDecision
import ai.icen.fw.agent.api.AgentPolicyProposal
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
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.AgentToolRisk
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.AuthorizedToolInvocation
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.ToolId
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
import kotlin.test.assertNull

class AgentProtocolHttpA2aReadRuntimeTest {
    @Test
    fun `ListTasks succeeds only inside the authorized page and total disclosure bounds`() {
        val fixture = fixture(pageSize = 25, maximumVisibleTasks = 100)
        val response = response(
            """{"jsonrpc":"2.0","id":"dispatch-list","result":{"tasks":[{"id":"task-1","contextId":"context-owned","status":{"state":"TASK_STATE_WORKING"}}],"nextPageToken":"","pageSize":10,"totalSize":2}}""",
        )
        val transport = CapturingTransport(CompletableFuture.completedFuture(response))
        val provider = provider(fixture, transport, evidence(fixture, response))

        val result = provider.start(fixture.dispatch).completion().toCompletableFuture().join()

        assertEquals(AgentRemoteProtocolResultStatus.SUCCEEDED, result.status)
        assertEquals("ListTasks", transport.request?.wireRequest?.operationName)
        assertEquals(25, transport.request?.wireRequest?.boundPageSize)
        assertEquals("context-owned", transport.request?.wireRequest?.boundContextId)
        assertFalse(result.toString().contains("context-owned"))
    }

    @Test
    fun `ListTasks rejects an overlarge request before transport and an overlarge total as unknown data`() {
        val overlargeRequest = fixture(pageSize = 25, maximumVisibleTasks = 10)
        val transport = CapturingTransport(CompletableFuture.completedFuture(response("{}")))
        val rejected = try {
            provider(overlargeRequest, transport, AgentProtocolHttpRuntimeEvidenceSource {
                CompletableFuture.completedFuture(null)
            }).start(overlargeRequest.dispatch).completion().toCompletableFuture().join()
            error("Expected pre-transport disclosure rejection")
        } catch (expected: CompletionException) {
            expected.cause
        }
        assertIs<AgentProtocolHttpRuntimeException>(rejected)
        assertNull(transport.request)

        val overlargeTotal = fixture(pageSize = 10, maximumVisibleTasks = 10)
        val response = response(
            """{"jsonrpc":"2.0","id":"dispatch-list","result":{"tasks":[],"nextPageToken":"","pageSize":10,"totalSize":11}}""",
        )
        val result = provider(
            overlargeTotal,
            CapturingTransport(CompletableFuture.completedFuture(response)),
            evidence(overlargeTotal, response),
        ).start(overlargeTotal.dispatch).completion().toCompletableFuture().join()
        assertEquals(AgentRemoteProtocolResultStatus.OUTCOME_UNKNOWN, result.status)
        assertNull(result.response)
    }

    private fun fixture(pageSize: Int, maximumVisibleTasks: Int): Fixture {
        val endpoint = URI("https://a2a.example/rpc")
        val payloadBytes = (
            "{\"contextId\":\"context-owned\",\"historyLength\":0,\"includeArtifacts\":false," +
                "\"pageSize\":$pageSize,\"tenant\":\"remote-tenant\"}"
        ).toByteArray(StandardCharsets.UTF_8)
        val payload = AgentRemoteProtocolPayload("application/json", payloadBytes, digest(payloadBytes))
        val context = AgentRunContext(TENANT, PRINCIPAL, "USER", id("caller-request"), 1L)
        val executable = executable(payloadBytes, context)
        val credential = AgentRemoteCredentialBinding(
            id("credential-reference"),
            PEER,
            AgentRemoteAuthenticationScheme.OAUTH2_BEARER,
            endpoint,
            listOf("a2a.task.read"),
            "credential-revision-1",
        )
        val profile = AgentRemotePeerProfile(
            PEER,
            AgentRemoteProtocolKind.A2A,
            AgentRemoteProtocolBaselines.A2A_1_0,
            AgentRemoteProtocolBindingId.A2A_JSON_RPC_HTTP,
            PROVIDER,
            RECONCILER,
            endpoint,
            "agent-card-v1",
            digest("agent-card"),
            listOf(AgentRemoteProtocolCapabilities.A2A_LIST_TASKS),
            digest("security"),
            digest("tls-peer"),
            credential,
            "profile-revision-1",
        )
        val operation = AgentRemoteOperationBinding(
            context,
            RUN,
            STEP,
            PEER,
            AgentRemoteProtocolKind.A2A,
            AgentRemoteOperationKind.A2A_LIST_TASKS,
            payload.digest,
            ACTION,
            RESOURCE_TYPE,
            RESOURCE,
            RESOURCE_REVISION,
            PURPOSE,
            parentInvocationId = id("parent-send"),
            parentOperationDigest = digest("parent-operation"),
            executorProviderId = EXECUTOR,
            executorToolId = TOOL,
            a2aTenantRoutingId = "remote-tenant",
            a2aContextId = "context-owned",
            a2aMaximumVisibleTasks = maximumVisibleTasks,
        )
        val invocation = AgentRemoteProtocolInvocationRequest(
            id("remote-invocation"),
            operation,
            payload,
            AgentRemoteProtocolCapabilities.A2A_LIST_TASKS,
            profile.profileDigest,
            AUTHORIZATION,
            AgentBudget(100, 100, 1, 1, 1_000L, 0L),
            AgentUsage(),
            IDEMPOTENCY_KEY,
            20L,
            500L,
            700L,
            4_096,
            AgentCancellationToken.NONE,
            executable,
        )
        val resolutionRequest = AgentRemoteNetworkResolutionRequest(
            id("resolution-request"),
            PEER,
            profile.profileDigest,
            endpoint,
            null,
            0,
            25L,
            450L,
        )
        val resolution = AgentRemoteNetworkResolution(
            id("resolution"),
            NETWORK,
            resolutionRequest,
            listOf(AgentRemoteResolvedAddress(byteArrayOf(8, 8, 8, 8))),
            26L,
            450L,
        )
        val preflight = AgentRemoteAuthorizationRequest(
            id("remote-authorization-preflight"),
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
            400L,
        )
        val finalAuthorization = AgentRemoteAuthorizationRequest(
            id("remote-authorization-final"),
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
            390L,
        )
        finalAuthorization.requireChildOf(preflight)
        val decision = AgentRemoteAuthorizationDecision.allow(
            id("remote-authorization-decision"),
            AUTHORIZATION,
            finalAuthorization,
            "authorization-revision-1",
            31L,
            380L,
        )
        val credentialRequest = AgentRemoteCredentialLeaseRequest(
            id("credential-request"),
            profile,
            invocation.bindingDigest,
            finalAuthorization,
            decision,
            32L,
            370L,
        )
        val lease = AgentRemoteCredentialLease(
            id("credential-lease"),
            BROKER,
            credentialRequest,
            credential.credentialReference,
            PEER,
            endpoint,
            credential.credentialRevision,
            33L,
            360L,
        )
        val dispatch = AgentRemoteProtocolDispatchRequest(
            id("dispatch-list"),
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
            PEER,
            AgentRemoteProtocolKind.A2A,
            AgentRemoteProtocolBaselines.A2A_1_0,
            AgentRemoteProtocolBindingId.A2A_JSON_RPC_HTTP,
            profile.descriptorVersion,
            profile.descriptorDigest,
            profile.capabilityDigest,
            profile.toolCatalogDigest,
            profile.securitySchemeDigest,
            45L,
        )
        return Fixture(profile, dispatch, observation)
    }

    private fun executable(arguments: ByteArray, context: AgentRunContext): AgentExecutableToolInvocation {
        val schema = "{\"type\":\"object\"}".toByteArray(StandardCharsets.UTF_8)
        val descriptor = AgentToolDescriptor(
            EXECUTOR,
            TOOL,
            "Read A2A tasks",
            "Reads tasks from one authorized remote context.",
            AgentToolRisk.READ_ONLY,
            schema,
            digest(schema),
            listOf(AgentCapabilityId("remote.a2a.task.list")),
            true,
            4_096,
            0L,
            600L,
        )
        val preflight = AgentAuthorizationRequest.preflight(
            id("tool-authorization-preflight"),
            id("execution-context"),
            context.tenantId,
            context.principalId,
            context.principalType,
            RUN,
            STEP,
            AUTHORIZATION,
            descriptor,
            arguments,
            IDEMPOTENCY_KEY,
            ACTION,
            RESOURCE_TYPE,
            RESOURCE,
            RESOURCE_REVISION,
            PURPOSE,
            2L,
            900L,
        )
        val initialDecision = AgentAuthorizationDecision.allow(
            id("tool-authorization-initial"),
            AUTHORIZATION,
            preflight,
            "authorization-revision-1",
            3L,
            899L,
        )
        val proposal = AgentPolicyProposal.create(
            id("policy-proposal"),
            POLICY,
            preflight,
            initialDecision,
            AgentToolRisk.READ_ONLY,
            AgentBudget(100, 100, 1, 1, 1_000L, 0L),
            AgentUsage(),
            4L,
            850L,
        )
        val policy = AgentPolicyDecision.allow(
            id("policy-decision"),
            POLICY,
            proposal,
            "policy-revision-1",
            5L,
            849L,
        )
        val executionRequest = AgentAuthorizationRequest.executionRecheck(
            id("tool-authorization-execution"),
            preflight,
            6L,
            800L,
        )
        val executionDecision = AgentAuthorizationDecision.allow(
            id("tool-authorization-execution-decision"),
            AUTHORIZATION,
            executionRequest,
            "authorization-revision-1",
            7L,
            799L,
        )
        val invocation = AuthorizedToolInvocation.authorize(
            id("tool-invocation"),
            proposal,
            descriptor,
            policy,
            executionRequest,
            executionDecision,
            null,
            null,
            arguments,
            IDEMPOTENCY_KEY,
            1,
            8L,
            700L,
            AgentCancellationToken.NONE,
        )
        val consumption = AgentExecutionContextConsumption.claimed(
            id("execution-consumption"),
            EXECUTION_CONSUMER,
            invocation,
            9L,
            "execution-store-revision-1",
        )
        val finalRequest = AgentAuthorizationRequest.finalExecutionRecheck(
            id("tool-authorization-final"),
            executionRequest,
            10L,
            650L,
        )
        val finalDecision = AgentAuthorizationDecision.allow(
            id("tool-authorization-final-decision"),
            AUTHORIZATION,
            finalRequest,
            "authorization-revision-1",
            11L,
            649L,
        )
        val fence = AgentDispatchAuthorizationFenceRequest(
            id("dispatch-fence"),
            DISPATCH_CONSUMER,
            invocation,
            finalRequest,
            finalDecision,
            12L,
            640L,
        )
        val fenceConsumption = AgentDispatchAuthorizationFenceConsumption.consumed(
            id("dispatch-fence-consumption"),
            fence,
            13L,
            "authorization-store-revision-1",
        )
        return AgentExecutableToolInvocation.create(
            invocation,
            consumption,
            finalRequest,
            finalDecision,
            fence,
            fenceConsumption,
            EXECUTION_CONSUMER,
            DISPATCH_CONSUMER,
            0L,
            500L,
            13L,
        )
    }

    private fun provider(
        fixture: Fixture,
        transport: AgentProtocolHttpTransport,
        evidence: AgentProtocolHttpRuntimeEvidenceSource,
    ): AgentProtocolHttpRuntimeProvider = AgentProtocolHttpRuntimeProvider(
        PROVIDER,
        PEER,
        AgentRemoteProtocolKind.A2A,
        transport,
        evidence,
        object : AgentProtocolHttpPeerObservationProvider {
            override fun providerId(): ProviderId = PROVIDER

            override fun observe(
                request: AgentProtocolHttpPeerObservationRequest,
            ): CompletionStage<AgentRemotePeerObservation> = CompletableFuture.completedFuture(fixture.observation)
        },
        clock = AgentProtocolHttpRuntimeClock { 45L },
        ids = AgentProtocolHttpRuntimeIdSource { purpose -> id("$purpose-test") },
    )

    private fun evidence(
        fixture: Fixture,
        response: AgentProtocolHttpWireResponse,
    ): AgentProtocolHttpRuntimeEvidenceSource = AgentProtocolHttpRuntimeEvidenceSource {
        val receipt = AgentRemoteTransportReceipt(
            id("transport-receipt"),
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

    private fun response(body: String): AgentProtocolHttpWireResponse = AgentProtocolHttpWireResponse(
        200,
        mapOf("Content-Type" to "application/json", "A2A-Version" to "1.0"),
        body.toByteArray(StandardCharsets.UTF_8),
    )

    private fun digest(value: String): String = digest(value.toByteArray(StandardCharsets.UTF_8))

    private fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun id(value: String): Identifier = Identifier(value)

    private class Fixture(
        val profile: AgentRemotePeerProfile,
        val dispatch: AgentRemoteProtocolDispatchRequest,
        val observation: AgentRemotePeerObservation,
    )

    private class CapturingTransport(
        private val stage: CompletionStage<AgentProtocolHttpWireResponse>,
    ) : AgentProtocolHttpTransport {
        var request: AgentProtocolHttpExchangeRequest? = null

        override fun exchange(request: AgentProtocolHttpExchangeRequest): CompletionStage<AgentProtocolHttpWireResponse> {
            this.request = request
            return stage
        }
    }

    private companion object {
        val TENANT = idStatic("tenant-a")
        val PRINCIPAL = idStatic("principal-a")
        val RUN = idStatic("run-a")
        val STEP = idStatic("step-list")
        val RESOURCE = idStatic("context-owned")
        val PEER = ProviderId("peer.a2a")
        val PROVIDER = ProviderId("protocol.http")
        val RECONCILER = ProviderId("reconciler.http")
        val AUTHORIZATION = ProviderId("authorization.remote")
        val NETWORK = ProviderId("network.remote")
        val BROKER = ProviderId("credential.broker")
        val POLICY = ProviderId("policy.remote")
        val EXECUTOR = ProviderId("executor.remote-protocol")
        val EXECUTION_CONSUMER = ProviderId("execution.store")
        val DISPATCH_CONSUMER = ProviderId("runtime.worker")
        val TOOL = ToolId("remote.a2a.list-tasks")
        const val ACTION = "agent.remote.task.list"
        const val RESOURCE_TYPE = "remote-task-context"
        const val RESOURCE_REVISION = "context-revision-1"
        const val PURPOSE = "list delegated tasks in owned context"
        const val IDEMPOTENCY_KEY = "tenant-a:run-a:step-list:a2a-list"

        fun idStatic(value: String): Identifier = Identifier(value)
    }
}
