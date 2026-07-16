package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.core.id.Identifier
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet

object AgentProtocolBaselines {
    const val MCP_VERSION: String = "2025-11-25"
    const val A2A_VERSION: String = "1.0"
    const val MCP_SPECIFICATION_URI: String = "https://modelcontextprotocol.io/specification/2025-11-25/basic"
    const val MCP_AUTHORIZATION_URI: String =
        "https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization"
    const val MCP_SECURITY_URI: String =
        "https://modelcontextprotocol.io/docs/tutorials/security/security_best_practices"
    const val A2A_SPECIFICATION_URI: String =
        "https://github.com/a2aproject/A2A/blob/v1.0.0/docs/specification.md"
    const val A2A_NORMATIVE_PROTO_URI: String =
        "https://github.com/a2aproject/A2A/blob/v1.0.0/specification/a2a.proto"
    const val A2A_RELEASE_URI: String = "https://github.com/a2aproject/A2A/releases/tag/v1.0.0"
}

enum class AgentProtocolKind {
    MCP,
    A2A,
}

enum class AgentProtocolSecurityAttack {
    MCP_BASELINE,
    A2A_BASELINE,
    TLS_DOWNGRADE,
    TLS_IDENTITY_MISMATCH,
    PRIVATE_ADDRESS_SSRF,
    REDIRECT_TO_PRIVATE_ADDRESS,
    OAUTH_AUDIENCE_MISMATCH,
    CREDENTIAL_CROSS_PEER_REUSE,
    CAPABILITY_DIGEST_DRIFT,
    UNKNOWN_REQUIRED_CAPABILITY,
    MCP_VERSION_MISMATCH,
    A2A_VERSION_MISMATCH,
    MCP_TOOL_IDENTITY_MISMATCH,
    MCP_TOOL_DESCRIPTOR_MISMATCH,
    MCP_TOOL_ARGUMENT_MISMATCH,
    A2A_MESSAGE_IDENTITY_MISMATCH,
    A2A_MESSAGE_DIGEST_MISMATCH,
    A2A_BOUND_CANCELLATION,
    A2A_CANCELLATION_CROSS_SUBJECT,
}

enum class AgentProtocolExpectedDisposition {
    ALLOW_BOUND_OPERATION,
    BLOCK_BEFORE_PROTOCOL_DISPATCH,
}

enum class AgentProtocolAuthenticationScheme {
    OAUTH2_BEARER,
    MUTUAL_TLS,
}

enum class AgentProtocolOperationKind {
    INITIALIZE,
    TOOL_CALL,
    SEND_MESSAGE,
    CANCEL_TASK,
}

class AgentProtocolPeerProfile(
    val protocol: AgentProtocolKind,
    version: String,
    peerId: ProviderId,
    descriptorVersion: String,
    descriptorDigest: String,
    capabilities: Collection<String>,
    securitySchemeDigest: String,
) {
    val version: String = protocolToken(version, "Protocol version is invalid.")
    val peerId: ProviderId = peerId
    val descriptorVersion: String = protocolToken(descriptorVersion, "Peer descriptor version is invalid.")
    val descriptorDigest: String = protocolDigest(descriptorDigest, "Peer descriptor digest is invalid.")
    val capabilities: Set<String>
    val securitySchemeDigest: String = protocolDigest(
        securitySchemeDigest,
        "Peer security-scheme digest is invalid.",
    )
    val capabilityDigest: String
    val profileDigest: String

    init {
        val capabilitySnapshot = protocolSet(
            capabilities.map { capability -> protocolCode(capability, "Protocol capability identifier is invalid.") },
            "Peer declares too many capabilities.",
        )
        require(capabilitySnapshot.isNotEmpty()) { "Peer profile requires at least one capability." }
        this.capabilities = capabilitySnapshot
        val capabilityHasher = ProtocolDigest("flowweft.testkit.agent.protocol.capabilities.v1")
            .add(protocol.name)
            .add(this.version)
            .add(this.capabilities.size)
        this.capabilities.sorted().forEach(capabilityHasher::add)
        capabilityDigest = capabilityHasher.finish()
        profileDigest = ProtocolDigest("flowweft.testkit.agent.protocol.peer-profile.v1")
            .add(protocol.name)
            .add(this.version)
            .add(this.peerId.value)
            .add(this.descriptorVersion)
            .add(this.descriptorDigest)
            .add(capabilityDigest)
            .add(this.securitySchemeDigest)
            .finish()
    }

    override fun toString(): String =
        "AgentProtocolPeerProfile(protocol=$protocol, version=$version, peerId=$peerId)"
}

class AgentProtocolNetworkFixture(
    endpoint: URI,
    resolvedAddresses: Collection<String>,
    redirectTargets: Collection<URI>,
    presentedTlsIdentityDigest: String,
    approvedTlsIdentityDigest: String,
    val productionMode: Boolean,
) {
    val endpoint: URI = protocolUri(endpoint, "Protocol endpoint is invalid.")
    val resolvedAddresses: List<String> = protocolList(
        resolvedAddresses.map { address -> protocolToken(address, "Resolved address is invalid.") },
        "Protocol network fixture has too many resolved addresses.",
    )
    val redirectTargets: List<URI> = protocolList(
        redirectTargets.map { target -> protocolUri(target, "Protocol redirect target is invalid.") },
        "Protocol network fixture has too many redirects.",
    )
    val presentedTlsIdentityDigest: String = protocolDigest(
        presentedTlsIdentityDigest,
        "Presented TLS identity digest is invalid.",
    )
    val approvedTlsIdentityDigest: String = protocolDigest(
        approvedTlsIdentityDigest,
        "Approved TLS identity digest is invalid.",
    )
    val bindingDigest: String

    init {
        require(this.resolvedAddresses.isNotEmpty()) { "Protocol network fixture requires a resolved address." }
        val digest = ProtocolDigest("flowweft.testkit.agent.protocol.network.v1")
            .add(this.endpoint.toASCIIString())
            .add(this.presentedTlsIdentityDigest)
            .add(this.approvedTlsIdentityDigest)
            .add(productionMode)
            .add(this.resolvedAddresses.size)
        this.resolvedAddresses.sorted().forEach(digest::add)
        digest.add(this.redirectTargets.size)
        this.redirectTargets.forEach { target -> digest.add(target.toASCIIString()) }
        bindingDigest = digest.finish()
    }

    override fun toString(): String = "AgentProtocolNetworkFixture(endpoint=${endpoint.scheme}://<redacted>)"
}

/** Safe reference to synthetic credential material; bearer tokens or private keys never enter the fixture. */
class AgentProtocolCredentialFixture(
    credentialReference: Identifier,
    val scheme: AgentProtocolAuthenticationScheme,
    val ownerPeerId: ProviderId,
    audience: String,
    scopes: Collection<String>,
    credentialMaterialDigest: String,
) {
    val credentialReference: Identifier = protocolIdentifier(
        credentialReference,
        "Protocol credential reference is invalid.",
    )
    val audience: String = protocolToken(audience, "Protocol credential audience is invalid.")
    val scopes: Set<String> = protocolSet(
        scopes.map { scope -> protocolCode(scope, "Protocol credential scope is invalid.") },
        "Protocol credential contains too many scopes.",
    )
    val credentialMaterialDigest: String = protocolDigest(
        credentialMaterialDigest,
        "Protocol credential-material digest is invalid.",
    )
    val bindingDigest: String

    init {
        require(this.scopes.isNotEmpty()) { "Protocol credential requires a least-privilege scope." }
        val audienceUri = protocolUri(URI.create(this.audience), "Protocol credential audience URI is invalid.")
        require(audienceUri.scheme.lowercase() == "https") {
            "Protocol credential audience requires HTTPS."
        }
        val digest = ProtocolDigest("flowweft.testkit.agent.protocol.credential.v1")
            .add(this.credentialReference.value)
            .add(scheme.name)
            .add(ownerPeerId.value)
            .add(this.audience)
            .add(this.credentialMaterialDigest)
            .add(this.scopes.size)
        this.scopes.sorted().forEach(digest::add)
        bindingDigest = digest.finish()
    }

    override fun toString(): String = "AgentProtocolCredentialFixture(reference=<redacted>, material=<redacted>)"
}

class AgentProtocolOperationBinding @JvmOverloads constructor(
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    val operation: AgentProtocolOperationKind,
    val peerId: ProviderId,
    messageId: Identifier? = null,
    messageDigest: String? = null,
    toolId: ToolId? = null,
    toolDescriptorDigest: String? = null,
    toolArgumentsDigest: String? = null,
    remoteTaskId: String? = null,
) {
    val tenantId: Identifier = protocolIdentifier(tenantId, "Protocol operation tenant is invalid.")
    val principalId: Identifier = protocolIdentifier(principalId, "Protocol operation principal is invalid.")
    val principalType: String = protocolCode(principalType, "Protocol operation principal type is invalid.")
    val messageId: Identifier? = messageId?.let { value ->
        protocolIdentifier(value, "Protocol message identifier is invalid.")
    }
    val messageDigest: String? = messageDigest?.let { value ->
        protocolDigest(value, "Protocol message digest is invalid.")
    }
    val toolId: ToolId? = toolId
    val toolDescriptorDigest: String? = toolDescriptorDigest?.let { value ->
        protocolDigest(value, "Protocol tool descriptor digest is invalid.")
    }
    val toolArgumentsDigest: String? = toolArgumentsDigest?.let { value ->
        protocolDigest(value, "Protocol tool arguments digest is invalid.")
    }
    val remoteTaskId: String? = remoteTaskId?.let { value ->
        protocolToken(value, "Protocol remote task identifier is invalid.")
    }
    val bindingDigest: String

    init {
        require((this.messageId == null) == (this.messageDigest == null)) {
            "Protocol message identity and digest must be provided together."
        }
        val hasTool = this.toolId != null || this.toolDescriptorDigest != null || this.toolArgumentsDigest != null
        require(!hasTool || this.toolId != null && this.toolDescriptorDigest != null && this.toolArgumentsDigest != null) {
            "Protocol tool identity, descriptor and arguments must be provided together."
        }
        require(operation != AgentProtocolOperationKind.TOOL_CALL || hasTool) {
            "Protocol tool calls require an exact tool binding."
        }
        require(operation != AgentProtocolOperationKind.SEND_MESSAGE || this.messageId != null) {
            "Protocol messages require an exact message binding."
        }
        require(operation != AgentProtocolOperationKind.CANCEL_TASK || this.remoteTaskId != null) {
            "Protocol cancellation requires an opaque remote task identifier."
        }
        bindingDigest = ProtocolDigest("flowweft.testkit.agent.protocol.operation.v1")
            .add(this.tenantId.value)
            .add(this.principalType)
            .add(this.principalId.value)
            .add(operation.name)
            .add(peerId.value)
            .add(this.messageId?.value ?: "-")
            .add(this.messageDigest ?: "-")
            .add(this.toolId?.value ?: "-")
            .add(this.toolDescriptorDigest ?: "-")
            .add(this.toolArgumentsDigest ?: "-")
            .add(this.remoteTaskId ?: "-")
            .finish()
    }

    override fun toString(): String = "AgentProtocolOperationBinding(operation=$operation, values=<redacted>)"
}

class AgentProtocolConformanceScenario(
    scenarioId: Identifier,
    val attack: AgentProtocolSecurityAttack,
    val approvedProfile: AgentProtocolPeerProfile,
    val observedProfile: AgentProtocolPeerProfile,
    val network: AgentProtocolNetworkFixture,
    val credential: AgentProtocolCredentialFixture?,
    val approvedOperation: AgentProtocolOperationBinding,
    val attemptedOperation: AgentProtocolOperationBinding,
    requestedCapability: String,
    val expectedDisposition: AgentProtocolExpectedDisposition,
) {
    val scenarioId: Identifier = protocolIdentifier(scenarioId, "Protocol scenario identifier is invalid.")
    val requestedCapability: String = protocolCode(
        requestedCapability,
        "Protocol requested capability is invalid.",
    )
    val bindingDigest: String

    init {
        require(approvedProfile.protocol == observedProfile.protocol) {
            "Protocol scenario profiles must describe the same protocol family."
        }
        require(approvedOperation.peerId == approvedProfile.peerId && attemptedOperation.peerId == observedProfile.peerId) {
            "Protocol operations must bind their exact peer profiles."
        }
        validateAttackShape()
        bindingDigest = ProtocolDigest("flowweft.testkit.agent.protocol.scenario.v1")
            .add(this.scenarioId.value)
            .add(attack.name)
            .add(approvedProfile.profileDigest)
            .add(observedProfile.profileDigest)
            .add(network.bindingDigest)
            .add(credential?.bindingDigest ?: "-")
            .add(approvedOperation.bindingDigest)
            .add(attemptedOperation.bindingDigest)
            .add(this.requestedCapability)
            .add(expectedDisposition.name)
            .finish()
    }

    private fun validateAttackShape() {
        when (attack) {
            AgentProtocolSecurityAttack.MCP_BASELINE -> require(
                approvedProfile.protocol == AgentProtocolKind.MCP &&
                    approvedProfile.version == AgentProtocolBaselines.MCP_VERSION &&
                    approvedProfile.profileDigest == observedProfile.profileDigest &&
                    expectedDisposition == AgentProtocolExpectedDisposition.ALLOW_BOUND_OPERATION,
            ) { "MCP baseline fixture is invalid." }
            AgentProtocolSecurityAttack.A2A_BASELINE -> require(
                approvedProfile.protocol == AgentProtocolKind.A2A &&
                    approvedProfile.version == AgentProtocolBaselines.A2A_VERSION &&
                    approvedProfile.profileDigest == observedProfile.profileDigest &&
                    expectedDisposition == AgentProtocolExpectedDisposition.ALLOW_BOUND_OPERATION,
            ) { "A2A baseline fixture is invalid." }
            AgentProtocolSecurityAttack.TLS_DOWNGRADE -> require(
                network.productionMode && network.endpoint.scheme.lowercase() != "https",
            ) { "TLS downgrade fixture requires a non-HTTPS production endpoint." }
            AgentProtocolSecurityAttack.TLS_IDENTITY_MISMATCH -> require(
                network.productionMode &&
                    network.endpoint.scheme.lowercase() == "https" &&
                    network.presentedTlsIdentityDigest != network.approvedTlsIdentityDigest,
            ) { "TLS identity fixture requires a mismatched production peer identity." }
            AgentProtocolSecurityAttack.PRIVATE_ADDRESS_SSRF -> require(
                network.resolvedAddresses.any { address -> address.startsWith("169.254.") || address.startsWith("10.") },
            ) { "Private-address fixture requires a link-local or private destination." }
            AgentProtocolSecurityAttack.REDIRECT_TO_PRIVATE_ADDRESS -> require(network.redirectTargets.isNotEmpty()) {
                "Redirect fixture requires a redirect target."
            }
            AgentProtocolSecurityAttack.OAUTH_AUDIENCE_MISMATCH -> require(
                credential != null && credential.audience != network.endpoint.toASCIIString(),
            ) { "OAuth audience fixture requires a wrong token audience." }
            AgentProtocolSecurityAttack.CREDENTIAL_CROSS_PEER_REUSE -> require(
                credential != null && credential.ownerPeerId != approvedProfile.peerId,
            ) { "Credential isolation fixture requires a credential owned by another peer." }
            AgentProtocolSecurityAttack.CAPABILITY_DIGEST_DRIFT -> require(
                approvedProfile.capabilityDigest != observedProfile.capabilityDigest ||
                    approvedProfile.descriptorDigest != observedProfile.descriptorDigest,
            ) { "Capability drift fixture requires a changed descriptor or capability digest." }
            AgentProtocolSecurityAttack.UNKNOWN_REQUIRED_CAPABILITY -> require(
                requestedCapability !in approvedProfile.capabilities,
            ) { "Unknown capability fixture requires an unadvertised capability." }
            AgentProtocolSecurityAttack.MCP_VERSION_MISMATCH -> require(
                approvedProfile.protocol == AgentProtocolKind.MCP && approvedProfile.version != observedProfile.version,
            ) { "MCP version mismatch fixture is invalid." }
            AgentProtocolSecurityAttack.A2A_VERSION_MISMATCH -> require(
                approvedProfile.protocol == AgentProtocolKind.A2A && approvedProfile.version != observedProfile.version,
            ) { "A2A version mismatch fixture is invalid." }
            AgentProtocolSecurityAttack.MCP_TOOL_IDENTITY_MISMATCH -> require(
                sameAuthorityAndOperation(approvedOperation, attemptedOperation) &&
                    approvedOperation.operation == AgentProtocolOperationKind.TOOL_CALL &&
                    approvedOperation.toolId != attemptedOperation.toolId &&
                    approvedOperation.toolDescriptorDigest == attemptedOperation.toolDescriptorDigest &&
                    approvedOperation.toolArgumentsDigest == attemptedOperation.toolArgumentsDigest &&
                    approvedOperation.messageId == attemptedOperation.messageId &&
                    approvedOperation.messageDigest == attemptedOperation.messageDigest &&
                    approvedOperation.remoteTaskId == attemptedOperation.remoteTaskId,
            ) { "MCP tool identity fixture requires a changed tool binding." }
            AgentProtocolSecurityAttack.MCP_TOOL_DESCRIPTOR_MISMATCH -> require(
                sameAuthorityAndOperation(approvedOperation, attemptedOperation) &&
                    approvedOperation.operation == AgentProtocolOperationKind.TOOL_CALL &&
                    approvedOperation.toolId == attemptedOperation.toolId &&
                    approvedOperation.toolDescriptorDigest != attemptedOperation.toolDescriptorDigest &&
                    approvedOperation.toolArgumentsDigest == attemptedOperation.toolArgumentsDigest &&
                    approvedOperation.messageId == attemptedOperation.messageId &&
                    approvedOperation.messageDigest == attemptedOperation.messageDigest &&
                    approvedOperation.remoteTaskId == attemptedOperation.remoteTaskId,
            ) { "MCP tool descriptor fixture requires only a changed descriptor binding." }
            AgentProtocolSecurityAttack.MCP_TOOL_ARGUMENT_MISMATCH -> require(
                sameAuthorityAndOperation(approvedOperation, attemptedOperation) &&
                    approvedOperation.operation == AgentProtocolOperationKind.TOOL_CALL &&
                    approvedOperation.toolId == attemptedOperation.toolId &&
                    approvedOperation.toolDescriptorDigest == attemptedOperation.toolDescriptorDigest &&
                    approvedOperation.toolArgumentsDigest != attemptedOperation.toolArgumentsDigest &&
                    approvedOperation.messageId == attemptedOperation.messageId &&
                    approvedOperation.messageDigest == attemptedOperation.messageDigest &&
                    approvedOperation.remoteTaskId == attemptedOperation.remoteTaskId,
            ) { "MCP tool arguments fixture requires only changed canonical arguments." }
            AgentProtocolSecurityAttack.A2A_MESSAGE_IDENTITY_MISMATCH -> require(
                sameAuthorityAndOperation(approvedOperation, attemptedOperation) &&
                    approvedOperation.operation == AgentProtocolOperationKind.SEND_MESSAGE &&
                    approvedOperation.messageId != attemptedOperation.messageId &&
                    approvedOperation.messageDigest == attemptedOperation.messageDigest &&
                    approvedOperation.toolId == attemptedOperation.toolId &&
                    approvedOperation.toolDescriptorDigest == attemptedOperation.toolDescriptorDigest &&
                    approvedOperation.toolArgumentsDigest == attemptedOperation.toolArgumentsDigest &&
                    approvedOperation.remoteTaskId == attemptedOperation.remoteTaskId,
            ) { "A2A message identity fixture requires a changed message binding." }
            AgentProtocolSecurityAttack.A2A_MESSAGE_DIGEST_MISMATCH -> require(
                sameAuthorityAndOperation(approvedOperation, attemptedOperation) &&
                    approvedOperation.operation == AgentProtocolOperationKind.SEND_MESSAGE &&
                    approvedOperation.messageId == attemptedOperation.messageId &&
                    approvedOperation.messageDigest != attemptedOperation.messageDigest &&
                    approvedOperation.toolId == attemptedOperation.toolId &&
                    approvedOperation.toolDescriptorDigest == attemptedOperation.toolDescriptorDigest &&
                    approvedOperation.toolArgumentsDigest == attemptedOperation.toolArgumentsDigest &&
                    approvedOperation.remoteTaskId == attemptedOperation.remoteTaskId,
            ) { "A2A message digest fixture requires only changed canonical message content." }
            AgentProtocolSecurityAttack.A2A_BOUND_CANCELLATION -> require(
                approvedOperation.operation == AgentProtocolOperationKind.CANCEL_TASK &&
                    approvedOperation.bindingDigest == attemptedOperation.bindingDigest &&
                    expectedDisposition == AgentProtocolExpectedDisposition.ALLOW_BOUND_OPERATION,
            ) { "A2A bound cancellation fixture is invalid." }
            AgentProtocolSecurityAttack.A2A_CANCELLATION_CROSS_SUBJECT -> require(
                approvedOperation.operation == AgentProtocolOperationKind.CANCEL_TASK &&
                    approvedOperation.principalId != attemptedOperation.principalId,
            ) { "A2A cancellation replay fixture requires a changed principal." }
        }
        val expected = when (attack) {
            AgentProtocolSecurityAttack.MCP_BASELINE,
            AgentProtocolSecurityAttack.A2A_BASELINE,
            AgentProtocolSecurityAttack.A2A_BOUND_CANCELLATION ->
                AgentProtocolExpectedDisposition.ALLOW_BOUND_OPERATION
            else -> AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH
        }
        require(expectedDisposition == expected) { "Protocol attack disposition is not fail closed." }
    }

    private fun sameAuthorityAndOperation(
        approved: AgentProtocolOperationBinding,
        attempted: AgentProtocolOperationBinding,
    ): Boolean = approved.tenantId == attempted.tenantId &&
        approved.principalId == attempted.principalId &&
        approved.principalType == attempted.principalType &&
        approved.operation == attempted.operation &&
        approved.peerId == attempted.peerId

    override fun toString(): String = "AgentProtocolConformanceScenario(attack=$attack)"
}

class AgentProtocolFixtureCatalog(scenarios: Collection<AgentProtocolConformanceScenario>) {
    val scenarios: List<AgentProtocolConformanceScenario> = protocolList(
        scenarios,
        "Protocol fixture catalog contains too many scenarios.",
    )

    init {
        require(this.scenarios.map { scenario -> scenario.attack }.toSet().size == this.scenarios.size) {
            "Protocol fixture catalog must contain one scenario per attack."
        }
    }

    fun scenario(attack: AgentProtocolSecurityAttack): AgentProtocolConformanceScenario =
        scenarios.singleOrNull { scenario -> scenario.attack == attack }
            ?: throw IllegalArgumentException("Protocol fixture catalog is missing $attack.")
}

object AgentProtocolConformanceFixtures {
    private const val MCP_RESOURCE = "https://mcp.example/mcp"
    private const val OTHER_MCP_RESOURCE = "https://mcp.other/mcp"
    private const val A2A_RESOURCE = "https://a2a.example/agent"
    private val tenant = Identifier("protocol-tenant")
    private val principal = Identifier("protocol-principal")
    private val otherPrincipal = Identifier("protocol-other-principal")
    private val mcpPeer = ProviderId("mcp.example")
    private val otherMcpPeer = ProviderId("mcp.other")
    private val a2aPeer = ProviderId("a2a.example")
    private val tlsIdentity = digest("tls.example")
    private val securityScheme = digest("oauth2-prm-pkce")
    private val messageDigest = digest("a2a-message")
    private val toolDescriptorDigest = digest("mcp-tool-descriptor")
    private val toolArgumentsDigest = digest("mcp-tool-arguments")

    @JvmStatic
    fun standard(): AgentProtocolFixtureCatalog = AgentProtocolFixtureCatalog(
        listOf(
            mcpBaseline(),
            a2aBaseline(),
            tlsDowngrade(),
            tlsIdentityMismatch(),
            privateAddressSsrf(),
            redirectToPrivateAddress(),
            oauthAudienceMismatch(),
            credentialCrossPeerReuse(),
            capabilityDigestDrift(),
            unknownRequiredCapability(),
            mcpVersionMismatch(),
            a2aVersionMismatch(),
            mcpToolIdentityMismatch(),
            mcpToolDescriptorMismatch(),
            mcpToolArgumentMismatch(),
            a2aMessageIdentityMismatch(),
            a2aMessageDigestMismatch(),
            a2aBoundCancellation(),
            a2aCancellationCrossSubject(),
        ),
    )

    @JvmStatic fun mcpBaseline(): AgentProtocolConformanceScenario = scenario(
        "mcp-baseline", AgentProtocolSecurityAttack.MCP_BASELINE, mcpProfile(), mcpProfile(), publicNetwork(),
        credential(mcpPeer, MCP_RESOURCE), mcpInitialize(), mcpInitialize(), "mcp.tools",
        AgentProtocolExpectedDisposition.ALLOW_BOUND_OPERATION,
    )

    @JvmStatic fun a2aBaseline(): AgentProtocolConformanceScenario = scenario(
        "a2a-baseline", AgentProtocolSecurityAttack.A2A_BASELINE, a2aProfile(), a2aProfile(), publicNetwork(a2aPeer),
        credential(a2aPeer, A2A_RESOURCE), a2aMessage(), a2aMessage(), "a2a.message.send",
        AgentProtocolExpectedDisposition.ALLOW_BOUND_OPERATION,
    )

    @JvmStatic fun tlsDowngrade(): AgentProtocolConformanceScenario = scenario(
        "tls-downgrade", AgentProtocolSecurityAttack.TLS_DOWNGRADE, mcpProfile(), mcpProfile(),
        network("http://mcp.example/mcp", listOf("93.184.216.34")), credential(mcpPeer, MCP_RESOURCE),
        mcpInitialize(), mcpInitialize(), "mcp.tools", AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
    )

    @JvmStatic fun tlsIdentityMismatch(): AgentProtocolConformanceScenario = scenario(
        "tls-identity", AgentProtocolSecurityAttack.TLS_IDENTITY_MISMATCH, mcpProfile(), mcpProfile(),
        network(
            "https://mcp.example/mcp",
            listOf("93.184.216.34"),
            presentedTlsIdentity = digest("unapproved-tls-identity"),
        ),
        credential(mcpPeer, MCP_RESOURCE), mcpInitialize(), mcpInitialize(), "mcp.tools",
        AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
    )

    @JvmStatic fun privateAddressSsrf(): AgentProtocolConformanceScenario = scenario(
        "private-address-ssrf", AgentProtocolSecurityAttack.PRIVATE_ADDRESS_SSRF, mcpProfile(), mcpProfile(),
        network("https://metadata.invalid/token", listOf("169.254.169.254")), credential(mcpPeer, MCP_RESOURCE),
        mcpInitialize(), mcpInitialize(), "mcp.tools", AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
    )

    @JvmStatic fun redirectToPrivateAddress(): AgentProtocolConformanceScenario = scenario(
        "redirect-private", AgentProtocolSecurityAttack.REDIRECT_TO_PRIVATE_ADDRESS, mcpProfile(), mcpProfile(),
        network(
            "https://mcp.example/mcp", listOf("93.184.216.34"),
            listOf(URI.create("http://169.254.169.254/latest/meta-data/")),
        ), credential(mcpPeer, MCP_RESOURCE), mcpInitialize(), mcpInitialize(), "mcp.tools",
        AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
    )

    @JvmStatic fun oauthAudienceMismatch(): AgentProtocolConformanceScenario = scenario(
        "oauth-audience", AgentProtocolSecurityAttack.OAUTH_AUDIENCE_MISMATCH, mcpProfile(), mcpProfile(),
        publicNetwork(), credential(mcpPeer, "https://downstream.example/api"),
        mcpInitialize(), mcpInitialize(), "mcp.tools",
        AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
    )

    @JvmStatic fun credentialCrossPeerReuse(): AgentProtocolConformanceScenario = scenario(
        "credential-cross-peer", AgentProtocolSecurityAttack.CREDENTIAL_CROSS_PEER_REUSE, mcpProfile(), mcpProfile(),
        publicNetwork(), credential(otherMcpPeer, OTHER_MCP_RESOURCE),
        mcpInitialize(), mcpInitialize(), "mcp.tools",
        AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
    )

    @JvmStatic fun capabilityDigestDrift(): AgentProtocolConformanceScenario {
        val observed = profile(AgentProtocolKind.MCP, AgentProtocolBaselines.MCP_VERSION, mcpPeer,
            setOf("mcp.tools", "mcp.resources", "mcp.unreviewed"), "mcp-descriptor-drift")
        return scenario(
            "capability-drift", AgentProtocolSecurityAttack.CAPABILITY_DIGEST_DRIFT, mcpProfile(), observed,
            publicNetwork(), credential(mcpPeer, MCP_RESOURCE), mcpInitialize(), mcpInitialize(), "mcp.tools",
            AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
        )
    }

    @JvmStatic fun unknownRequiredCapability(): AgentProtocolConformanceScenario = scenario(
        "unknown-capability", AgentProtocolSecurityAttack.UNKNOWN_REQUIRED_CAPABILITY, mcpProfile(), mcpProfile(),
        publicNetwork(), credential(mcpPeer, MCP_RESOURCE),
        mcpInitialize(), mcpInitialize(), "mcp.tasks.experimental",
        AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
    )

    @JvmStatic fun mcpVersionMismatch(): AgentProtocolConformanceScenario = scenario(
        "mcp-version", AgentProtocolSecurityAttack.MCP_VERSION_MISMATCH, mcpProfile(),
        profile(AgentProtocolKind.MCP, "unknown-version", mcpPeer, setOf("mcp.tools"), "mcp-unknown"),
        publicNetwork(), credential(mcpPeer, MCP_RESOURCE), mcpInitialize(), mcpInitialize(), "mcp.tools",
        AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
    )

    @JvmStatic fun a2aVersionMismatch(): AgentProtocolConformanceScenario = scenario(
        "a2a-version", AgentProtocolSecurityAttack.A2A_VERSION_MISMATCH, a2aProfile(),
        profile(AgentProtocolKind.A2A, "0.3", a2aPeer, setOf("a2a.message.send"), "a2a-legacy"),
        publicNetwork(a2aPeer), credential(a2aPeer, A2A_RESOURCE),
        a2aMessage(), a2aMessage(), "a2a.message.send",
        AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
    )

    @JvmStatic fun mcpToolIdentityMismatch(): AgentProtocolConformanceScenario {
        val approved = mcpTool()
        val attempted = AgentProtocolOperationBinding(
            tenant, principal, "USER", AgentProtocolOperationKind.TOOL_CALL, mcpPeer,
            toolId = ToolId("document.delete"), toolDescriptorDigest = toolDescriptorDigest,
            toolArgumentsDigest = toolArgumentsDigest,
        )
        return scenario(
            "mcp-tool-identity", AgentProtocolSecurityAttack.MCP_TOOL_IDENTITY_MISMATCH, mcpProfile(), mcpProfile(),
            publicNetwork(), credential(mcpPeer, MCP_RESOURCE), approved, attempted, "mcp.tools",
            AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
        )
    }

    @JvmStatic fun mcpToolDescriptorMismatch(): AgentProtocolConformanceScenario {
        val approved = mcpTool()
        val attempted = AgentProtocolOperationBinding(
            tenant, principal, "USER", AgentProtocolOperationKind.TOOL_CALL, mcpPeer,
            toolId = ToolId("document.read"), toolDescriptorDigest = digest("changed-tool-descriptor"),
            toolArgumentsDigest = toolArgumentsDigest,
        )
        return scenario(
            "mcp-tool-descriptor", AgentProtocolSecurityAttack.MCP_TOOL_DESCRIPTOR_MISMATCH,
            mcpProfile(), mcpProfile(), publicNetwork(), credential(mcpPeer, MCP_RESOURCE), approved, attempted,
            "mcp.tools", AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
        )
    }

    @JvmStatic fun mcpToolArgumentMismatch(): AgentProtocolConformanceScenario {
        val approved = mcpTool()
        val attempted = AgentProtocolOperationBinding(
            tenant, principal, "USER", AgentProtocolOperationKind.TOOL_CALL, mcpPeer,
            toolId = ToolId("document.read"), toolDescriptorDigest = toolDescriptorDigest,
            toolArgumentsDigest = digest("changed-tool-arguments"),
        )
        return scenario(
            "mcp-tool-arguments", AgentProtocolSecurityAttack.MCP_TOOL_ARGUMENT_MISMATCH,
            mcpProfile(), mcpProfile(), publicNetwork(), credential(mcpPeer, MCP_RESOURCE), approved, attempted,
            "mcp.tools", AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
        )
    }

    @JvmStatic fun a2aMessageIdentityMismatch(): AgentProtocolConformanceScenario {
        val approved = a2aMessage()
        val attempted = AgentProtocolOperationBinding(
            tenant, principal, "USER", AgentProtocolOperationKind.SEND_MESSAGE, a2aPeer,
            messageId = Identifier("a2a-message-forged"), messageDigest = messageDigest,
        )
        return scenario(
            "a2a-message-identity", AgentProtocolSecurityAttack.A2A_MESSAGE_IDENTITY_MISMATCH,
            a2aProfile(), a2aProfile(), publicNetwork(a2aPeer), credential(a2aPeer, A2A_RESOURCE), approved, attempted,
            "a2a.message.send", AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
        )
    }

    @JvmStatic fun a2aMessageDigestMismatch(): AgentProtocolConformanceScenario {
        val approved = a2aMessage()
        val attempted = AgentProtocolOperationBinding(
            tenant, principal, "USER", AgentProtocolOperationKind.SEND_MESSAGE, a2aPeer,
            messageId = Identifier("a2a-message-1"), messageDigest = digest("changed-message"),
        )
        return scenario(
            "a2a-message-digest", AgentProtocolSecurityAttack.A2A_MESSAGE_DIGEST_MISMATCH,
            a2aProfile(), a2aProfile(), publicNetwork(a2aPeer), credential(a2aPeer, A2A_RESOURCE), approved, attempted,
            "a2a.message.send", AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
        )
    }

    @JvmStatic fun a2aBoundCancellation(): AgentProtocolConformanceScenario = scenario(
        "a2a-cancel-bound", AgentProtocolSecurityAttack.A2A_BOUND_CANCELLATION, a2aProfile(), a2aProfile(),
        publicNetwork(a2aPeer), credential(a2aPeer, A2A_RESOURCE), a2aCancel(principal), a2aCancel(principal),
        "a2a.task.cancel", AgentProtocolExpectedDisposition.ALLOW_BOUND_OPERATION,
    )

    @JvmStatic fun a2aCancellationCrossSubject(): AgentProtocolConformanceScenario = scenario(
        "a2a-cancel-subject", AgentProtocolSecurityAttack.A2A_CANCELLATION_CROSS_SUBJECT,
        a2aProfile(), a2aProfile(), publicNetwork(a2aPeer), credential(a2aPeer, A2A_RESOURCE),
        a2aCancel(principal), a2aCancel(otherPrincipal), "a2a.task.cancel",
        AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH,
    )

    private fun mcpProfile(): AgentProtocolPeerProfile = profile(
        AgentProtocolKind.MCP, AgentProtocolBaselines.MCP_VERSION, mcpPeer,
        setOf("mcp.tools", "mcp.resources"), "mcp-descriptor",
    )

    private fun a2aProfile(): AgentProtocolPeerProfile = profile(
        AgentProtocolKind.A2A, AgentProtocolBaselines.A2A_VERSION, a2aPeer,
        setOf("a2a.message.send", "a2a.task.cancel"), "a2a-agent-card",
    )

    private fun profile(
        protocol: AgentProtocolKind,
        version: String,
        peer: ProviderId,
        capabilities: Set<String>,
        descriptorSeed: String,
    ): AgentProtocolPeerProfile = AgentProtocolPeerProfile(
        protocol, version, peer, "1.0.0", digest(descriptorSeed), capabilities, securityScheme,
    )

    private fun publicNetwork(peer: ProviderId = mcpPeer): AgentProtocolNetworkFixture = network(
        if (peer == mcpPeer) MCP_RESOURCE else A2A_RESOURCE,
        listOf("93.184.216.34"),
        emptyList(),
    )

    private fun network(
        endpoint: String,
        addresses: List<String>,
        redirects: List<URI> = emptyList(),
        presentedTlsIdentity: String = tlsIdentity,
        approvedTlsIdentity: String = tlsIdentity,
    ): AgentProtocolNetworkFixture = AgentProtocolNetworkFixture(
        URI.create(endpoint), addresses, redirects, presentedTlsIdentity, approvedTlsIdentity, true,
    )

    private fun credential(owner: ProviderId, audience: String): AgentProtocolCredentialFixture =
        AgentProtocolCredentialFixture(
            Identifier("credential-reference"), AgentProtocolAuthenticationScheme.OAUTH2_BEARER,
            owner, audience, setOf("agent.invoke"), digest("synthetic-credential-material"),
        )

    private fun mcpInitialize(): AgentProtocolOperationBinding = AgentProtocolOperationBinding(
        tenant, principal, "USER", AgentProtocolOperationKind.INITIALIZE, mcpPeer,
    )

    private fun mcpTool(): AgentProtocolOperationBinding = AgentProtocolOperationBinding(
        tenant, principal, "USER", AgentProtocolOperationKind.TOOL_CALL, mcpPeer,
        toolId = ToolId("document.read"), toolDescriptorDigest = toolDescriptorDigest,
        toolArgumentsDigest = toolArgumentsDigest,
    )

    private fun a2aMessage(): AgentProtocolOperationBinding = AgentProtocolOperationBinding(
        tenant, principal, "USER", AgentProtocolOperationKind.SEND_MESSAGE, a2aPeer,
        messageId = Identifier("a2a-message-1"), messageDigest = messageDigest,
    )

    private fun a2aCancel(subject: Identifier): AgentProtocolOperationBinding = AgentProtocolOperationBinding(
        tenant, subject, "USER", AgentProtocolOperationKind.CANCEL_TASK, a2aPeer,
        remoteTaskId = "remote-task-opaque-1",
    )

    private fun scenario(
        id: String,
        attack: AgentProtocolSecurityAttack,
        approved: AgentProtocolPeerProfile,
        observed: AgentProtocolPeerProfile,
        network: AgentProtocolNetworkFixture,
        credential: AgentProtocolCredentialFixture?,
        approvedOperation: AgentProtocolOperationBinding,
        attemptedOperation: AgentProtocolOperationBinding,
        capability: String,
        disposition: AgentProtocolExpectedDisposition,
    ): AgentProtocolConformanceScenario = AgentProtocolConformanceScenario(
        Identifier("protocol-$id"), attack, approved, observed, network, credential,
        approvedOperation, attemptedOperation, capability, disposition,
    )

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private const val MAX_PROTOCOL_ITEMS = 128
private const val MAX_PROTOCOL_TOKEN_CODE_POINTS = 512
private val protocolCodePattern = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")

private fun protocolIdentifier(value: Identifier, message: String): Identifier {
    protocolToken(value.value, message)
    return value
}

private fun protocolCode(value: String, message: String): String {
    protocolToken(value, message)
    require(protocolCodePattern.matches(value)) { message }
    return value
}

private fun protocolToken(value: String, message: String): String {
    require(value.isNotBlank() && value == value.trim() &&
        value.codePointCount(0, value.length) <= MAX_PROTOCOL_TOKEN_CODE_POINTS
    ) { message }
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        require(!Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT.toInt()) {
            message
        }
        offset += Character.charCount(codePoint)
    }
    return value
}

private fun protocolDigest(value: String, message: String): String {
    require(value.length == 64 && value.all { character -> character in '0'..'9' || character in 'a'..'f' }) { message }
    return value
}

private fun protocolUri(value: URI, message: String): URI {
    require(
        value.isAbsolute && value.host != null && value.fragment == null && value.userInfo == null &&
            (value.scheme.equals("https", ignoreCase = true) || value.scheme.equals("http", ignoreCase = true)),
    ) { message }
    return value.normalize()
}

private fun <T> protocolList(values: Collection<T>, message: String): List<T> {
    require(values.size <= MAX_PROTOCOL_ITEMS) { message }
    return Collections.unmodifiableList(ArrayList(values))
}

private fun <T> protocolSet(values: Collection<T>, message: String): Set<T> {
    require(values.size <= MAX_PROTOCOL_ITEMS && values.toSet().size == values.size) { message }
    return Collections.unmodifiableSet(LinkedHashSet(values))
}

private class ProtocolDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(protocolCode(domain, "Protocol digest domain is invalid."))
    }

    fun add(value: String): ProtocolDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
        return this
    }

    fun add(value: Int): ProtocolDigest = add(value.toString())
    fun add(value: Boolean): ProtocolDigest = add(if (value) "1" else "0")

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
