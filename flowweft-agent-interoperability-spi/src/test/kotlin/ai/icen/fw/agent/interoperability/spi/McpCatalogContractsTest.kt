package ai.icen.fw.agent.interoperability.spi

import ai.icen.fw.agent.api.AgentRemoteAuthenticationScheme
import ai.icen.fw.agent.api.AgentRemoteCredentialBinding
import ai.icen.fw.agent.api.AgentRemotePeerObservation
import ai.icen.fw.agent.api.AgentRemotePeerProfile
import ai.icen.fw.agent.api.AgentRemoteProtocolBaselines
import ai.icen.fw.agent.api.AgentRemoteProtocolBindingId
import ai.icen.fw.agent.api.AgentRemoteProtocolCapabilities
import ai.icen.fw.agent.api.AgentRemoteProtocolKind
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class McpCatalogContractsTest {
    @Test
    fun `catalog extends the existing peer profile without duplicating protocol or tool identity`() {
        val fixture = InteroperabilityContractFixture.create()

        assertSame(fixture.profile, fixture.catalog.profile)
        assertSame(fixture.observation, fixture.catalog.observation)
        assertNotEquals(fixture.snapshot.providerId, fixture.snapshot.profile.peerId)
        assertEquals(fixture.profile.toolCatalogDigest, fixture.catalog.profile.toolCatalogDigest)
        assertTrue(fixture.snapshot.supports(AgentInteroperabilityCapabilities.MCP_RESOURCES_READ))
        assertTrue(fixture.snapshot.supports(AgentInteroperabilityCapabilities.MCP_PROMPTS_GET))
        assertNotNull(fixture.catalog.resource(McpResourceId("resource.policy-handbook")))
        assertNotNull(fixture.catalog.prompt(McpPromptId("prompt.summarize-policy")))
        assertFalse(fixture.catalog.toString().contains("https://"))
        assertFalse(fixture.catalog.resources.single().toString().contains("resource-locator"))
        assertFalse(fixture.catalog.prompts.single().toString().contains("prompt-arguments-schema"))
        assertFalse(fixture.catalog.resources.single().toString().contains("resource.policy-handbook"))
        assertFalse(fixture.catalog.prompts.single().toString().contains("prompt.summarize-policy"))
    }

    @Test
    fun `catalog rejects descriptors owned by another peer`() {
        val fixture = InteroperabilityContractFixture.create()
        val foreign = McpResourceDescriptor.of(
            AgentInteroperabilityContractVersions.V1,
            ProviderId("peer.other"),
            McpResourceId("resource.foreign"),
            "revision-1",
            "descriptor-v1",
            InteroperabilityContractFixture.digest("foreign-descriptor"),
            InteroperabilityContractFixture.digest("foreign-locator"),
            "application/json",
            null,
            1_024,
        )

        assertFailsWith<IllegalArgumentException> {
            McpCatalogSnapshot.of(
                AgentInteroperabilityContractVersions.V1,
                fixture.profile,
                fixture.observation,
                "catalog-revision-1",
                listOf(foreign),
                emptyList(),
                50L,
                800L,
            )
        }
    }

    @Test
    fun `catalog collections are defensive and immutable`() {
        val fixture = InteroperabilityContractFixture.create()
        @Suppress("UNCHECKED_CAST")
        val resources = fixture.catalog.resources as MutableList<McpResourceDescriptor>

        assertFailsWith<UnsupportedOperationException> { resources.clear() }
        assertEquals(1, fixture.catalog.resources.size)
    }

    @Test
    fun `A2A capability snapshot reuses the canonical Agent Card profile without a parallel message model`() {
        val peer = ProviderId("peer.a2a")
        val endpoint = URI("https://a2a.example/agent")
        val credential = AgentRemoteCredentialBinding(
            Identifier("a2a-credential-reference"),
            peer,
            AgentRemoteAuthenticationScheme.OAUTH2_BEARER,
            endpoint,
            listOf("a2a.invoke"),
            "credential-revision-1",
        )
        val profile = AgentRemotePeerProfile(
            peer,
            AgentRemoteProtocolKind.A2A,
            AgentRemoteProtocolBaselines.A2A_1_0,
            AgentRemoteProtocolBindingId.A2A_JSON_RPC_HTTP,
            ProviderId("protocol.a2a"),
            ProviderId("reconciler.a2a"),
            endpoint,
            "agent-card-v1",
            InteroperabilityContractFixture.digest("agent-card"),
            listOf(
                AgentRemoteProtocolCapabilities.A2A_INITIALIZE,
                AgentRemoteProtocolCapabilities.A2A_SEND_MESSAGE,
                AgentRemoteProtocolCapabilities.A2A_CANCEL_TASK,
                AgentInteroperabilityCapabilities.INTEROPERABILITY_DIAGNOSTICS,
            ),
            InteroperabilityContractFixture.digest("a2a-security"),
            InteroperabilityContractFixture.digest("a2a-tls-peer"),
            credential,
            "profile-revision-1",
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
            10L,
        )
        val snapshot = AgentInteroperabilityCapabilitySnapshot.of(
            AgentInteroperabilityContractVersions.V1,
            ProviderId("interoperability.a2a"),
            "provider-revision-1",
            profile,
            observation,
            null,
            10L,
            100L,
        )

        assertTrue(snapshot.supports(AgentRemoteProtocolCapabilities.A2A_SEND_MESSAGE))
        assertEquals(null, snapshot.mcpCatalog)
    }
}
