package ai.icen.fw.testkit.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentProtocolConformanceFixturesTest {

    @Test
    fun `standard catalog covers every protocol attack exactly once`() {
        val catalog = AgentProtocolConformanceFixtures.standard()

        assertEquals(AgentProtocolSecurityAttack.values().size, catalog.scenarios.size)
        assertEquals(AgentProtocolSecurityAttack.values().toSet(), catalog.scenarios.map { it.attack }.toSet())
    }

    @Test
    fun `baseline fixtures pin reviewed MCP and A2A protocol identities`() {
        val mcp = AgentProtocolConformanceFixtures.mcpBaseline()
        val a2a = AgentProtocolConformanceFixtures.a2aBaseline()

        assertEquals(AgentProtocolKind.MCP, mcp.approvedProfile.protocol)
        assertEquals(AgentProtocolBaselines.MCP_VERSION, mcp.approvedProfile.version)
        assertEquals("mcp.example", mcp.network.endpoint.host)
        assertEquals(mcp.network.endpoint.toASCIIString(), requireNotNull(mcp.credential).audience)
        assertEquals(AgentProtocolKind.A2A, a2a.approvedProfile.protocol)
        assertEquals(AgentProtocolBaselines.A2A_VERSION, a2a.approvedProfile.version)
        assertEquals("a2a.example", a2a.network.endpoint.host)
        assertTrue(AgentProtocolBaselines.A2A_SPECIFICATION_URI.contains("/v1.0.0/"))
        assertNotEquals(
            AgentProtocolConformanceFixtures.mcpVersionMismatch().approvedProfile.version,
            AgentProtocolConformanceFixtures.mcpVersionMismatch().observedProfile.version,
        )
    }

    @Test
    fun `network and credential attack fixtures remain fail closed and secret safe`() {
        val tls = AgentProtocolConformanceFixtures.tlsDowngrade()
        val tlsIdentity = AgentProtocolConformanceFixtures.tlsIdentityMismatch()
        val privateAddress = AgentProtocolConformanceFixtures.privateAddressSsrf()
        val redirect = AgentProtocolConformanceFixtures.redirectToPrivateAddress()
        val credential = requireNotNull(AgentProtocolConformanceFixtures.mcpBaseline().credential)

        assertEquals("http", tls.network.endpoint.scheme)
        assertNotEquals(
            tlsIdentity.network.approvedTlsIdentityDigest,
            tlsIdentity.network.presentedTlsIdentityDigest,
        )
        assertTrue(privateAddress.network.resolvedAddresses.contains("169.254.169.254"))
        assertEquals("169.254.169.254", redirect.network.redirectTargets.single().host)
        assertFalse(credential.toString().contains(credential.credentialReference.value))
        assertFalse(credential.toString().contains(credential.credentialMaterialDigest))
        assertEquals(
            AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
            AgentProtocolConformanceFixtures.credentialCrossPeerReuse().expectedDisposition,
        )
    }

    @Test
    fun `catalog and profile collections are defensive snapshots`() {
        val catalog = AgentProtocolConformanceFixtures.standard()
        val profile = AgentProtocolConformanceFixtures.mcpBaseline().approvedProfile

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (catalog.scenarios as MutableList<AgentProtocolConformanceScenario>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (profile.capabilities as MutableSet<String>).clear()
        }
    }

    @Test
    fun `tool and message attacks isolate every canonical binding`() {
        val toolIdentity = AgentProtocolConformanceFixtures.mcpToolIdentityMismatch()
        val toolDescriptor = AgentProtocolConformanceFixtures.mcpToolDescriptorMismatch()
        val toolArguments = AgentProtocolConformanceFixtures.mcpToolArgumentMismatch()
        val messageIdentity = AgentProtocolConformanceFixtures.a2aMessageIdentityMismatch()
        val messageDigest = AgentProtocolConformanceFixtures.a2aMessageDigestMismatch()

        assertNotEquals(toolIdentity.approvedOperation.toolId, toolIdentity.attemptedOperation.toolId)
        assertEquals(
            toolIdentity.approvedOperation.toolArgumentsDigest,
            toolIdentity.attemptedOperation.toolArgumentsDigest,
        )
        assertNotEquals(
            toolDescriptor.approvedOperation.toolDescriptorDigest,
            toolDescriptor.attemptedOperation.toolDescriptorDigest,
        )
        assertEquals(toolDescriptor.approvedOperation.toolId, toolDescriptor.attemptedOperation.toolId)
        assertNotEquals(
            toolArguments.approvedOperation.toolArgumentsDigest,
            toolArguments.attemptedOperation.toolArgumentsDigest,
        )
        assertEquals(
            toolArguments.approvedOperation.toolDescriptorDigest,
            toolArguments.attemptedOperation.toolDescriptorDigest,
        )
        assertNotEquals(messageIdentity.approvedOperation.messageId, messageIdentity.attemptedOperation.messageId)
        assertEquals(
            messageIdentity.approvedOperation.messageDigest,
            messageIdentity.attemptedOperation.messageDigest,
        )
        assertEquals(messageDigest.approvedOperation.messageId, messageDigest.attemptedOperation.messageId)
        assertNotEquals(
            messageDigest.approvedOperation.messageDigest,
            messageDigest.attemptedOperation.messageDigest,
        )
    }
}
