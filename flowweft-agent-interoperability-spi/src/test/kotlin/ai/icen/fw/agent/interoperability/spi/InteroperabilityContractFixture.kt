package ai.icen.fw.agent.interoperability.spi

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
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchResult
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

internal class InteroperabilityContractFixture private constructor(
    val providerId: ProviderId,
    val profile: AgentRemotePeerProfile,
    val invocation: AgentRemoteProtocolInvocationRequest,
    val dispatch: AgentRemoteProtocolDispatchRequest,
    val laterDispatch: AgentRemoteProtocolDispatchRequest,
    val observation: AgentRemotePeerObservation,
    val dispatchResult: AgentRemoteProtocolDispatchResult,
    val catalog: McpCatalogSnapshot,
    val snapshot: AgentInteroperabilityCapabilitySnapshot,
) {
    companion object {
        fun create(): InteroperabilityContractFixture {
            val peer = ProviderId("peer.mcp")
            val protocolProvider = ProviderId("protocol.http")
            val reconciler = ProviderId("reconciler.http")
            val authorizationProvider = ProviderId("authorization.remote")
            val networkProvider = ProviderId("network.remote")
            val broker = ProviderId("credential.broker")
            val interoperabilityProvider = ProviderId("interoperability.catalog")
            val endpoint = URI("https://mcp.example/protocol")
            val credential = AgentRemoteCredentialBinding(
                id("credential-reference"),
                peer,
                AgentRemoteAuthenticationScheme.OAUTH2_BEARER,
                endpoint,
                listOf("mcp.invoke"),
                "credential-revision-1",
            )
            val profile = AgentRemotePeerProfile(
                peer,
                AgentRemoteProtocolKind.MCP,
                AgentRemoteProtocolBaselines.MCP_2025_11_25,
                AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP,
                protocolProvider,
                reconciler,
                endpoint,
                "descriptor-v1",
                digest("peer-descriptor"),
                listOf(
                    AgentRemoteProtocolCapabilities.MCP_INITIALIZE,
                    AgentInteroperabilityCapabilities.MCP_RESOURCES_LIST,
                    AgentInteroperabilityCapabilities.MCP_RESOURCES_READ,
                    AgentInteroperabilityCapabilities.MCP_PROMPTS_LIST,
                    AgentInteroperabilityCapabilities.MCP_PROMPTS_GET,
                    AgentInteroperabilityCapabilities.MCP_CATALOG_SNAPSHOT,
                    AgentInteroperabilityCapabilities.INTEROPERABILITY_DIAGNOSTICS,
                ),
                digest("security-schemes"),
                digest("tls-peer"),
                credential,
                "profile-revision-1",
            )
            val payload = payload("{}")
            val context = AgentRunContext(
                id("tenant-a"),
                id("principal-a"),
                "USER",
                id("caller-request"),
                10L,
            )
            val operation = AgentRemoteOperationBinding(
                context,
                id("run-1"),
                id("step-initialize"),
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
                authorizationProvider,
                AgentBudget(100L, 100L, 1, 1, 2_000L, 0L),
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
            val publicAddress = AgentRemoteResolvedAddress(byteArrayOf(8, 8, 8, 8))
            val resolution = AgentRemoteNetworkResolution(
                id("resolution-1"),
                networkProvider,
                resolutionRequest,
                listOf(publicAddress),
                26L,
                800L,
            )
            val preflight = AgentRemoteAuthorizationRequest(
                id("authorization-preflight-1"),
                authorizationProvider,
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
                authorizationProvider,
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
            val decision = AgentRemoteAuthorizationDecision.allow(
                id("authorization-decision-1"),
                authorizationProvider,
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
                broker,
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
            val laterDispatch = AgentRemoteProtocolDispatchRequest(
                id("dispatch-2"),
                invocation,
                profile,
                finalAuthorization,
                decision,
                resolutionRequest,
                resolution,
                credentialRequest,
                lease,
                70L,
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
            val receipt = AgentRemoteTransportReceipt(
                id("transport-receipt-1"),
                dispatch,
                publicAddress.addressDigest,
                profile.approvedTlsPeerIdentityDigest,
                true,
                50L,
            )
            val dispatchResult = AgentRemoteProtocolDispatchResult(
                id("dispatch-result-1"),
                dispatch,
                AgentRemoteProtocolResultStatus.SUCCEEDED,
                receipt,
                observation,
                payload("{\"protocolVersion\":\"2025-11-25\"}"),
                null,
                null,
                AgentUsage(durationMillis = 10L),
                digest("dispatch-evidence"),
                null,
                50L,
            )
            val messageSchemaDigest = digest("agent-message-schema-v1")
            val resource = McpResourceDescriptor.of(
                AgentInteroperabilityContractVersions.V1,
                profile.peerId,
                McpResourceId("resource.policy-handbook"),
                "resource-revision-1",
                "descriptor-v1",
                digest("resource-descriptor"),
                digest("resource-locator"),
                "application/json",
                digest("resource-content"),
                32_768,
            )
            val prompt = McpPromptDescriptor.of(
                AgentInteroperabilityContractVersions.V1,
                profile.peerId,
                McpPromptId("prompt.summarize-policy"),
                "prompt-revision-1",
                "descriptor-v1",
                digest("prompt-descriptor"),
                "json-schema-2020-12",
                digest("prompt-arguments-schema"),
                messageSchemaDigest,
                8_192,
                32_768,
            )
            val catalog = McpCatalogSnapshot.of(
                AgentInteroperabilityContractVersions.V1,
                profile,
                observation,
                "catalog-revision-1",
                listOf(resource),
                listOf(prompt),
                50L,
                800L,
            )
            val snapshot = AgentInteroperabilityCapabilitySnapshot.of(
                AgentInteroperabilityContractVersions.V1,
                interoperabilityProvider,
                "catalog-revision-1",
                profile,
                observation,
                catalog,
                50L,
                800L,
            )
            return InteroperabilityContractFixture(
                interoperabilityProvider,
                profile,
                invocation,
                dispatch,
                laterDispatch,
                observation,
                dispatchResult,
                catalog,
                snapshot,
            )
        }

        fun digest(value: String): String = digest(value.toByteArray(StandardCharsets.UTF_8))

        private fun payload(value: String): AgentRemoteProtocolPayload {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            return AgentRemoteProtocolPayload("application/json", bytes, digest(bytes))
        }

        private fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private fun id(value: String): Identifier = Identifier(value)
    }
}
