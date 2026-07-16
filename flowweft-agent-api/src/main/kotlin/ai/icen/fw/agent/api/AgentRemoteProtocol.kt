package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CompletionStage

/** Protocol baselines frozen for the FlowWeft 1.0 adapter contracts. */
object AgentRemoteProtocolBaselines {
    const val MCP_2025_11_25: String = "2025-11-25"
    const val A2A_1_0: String = "1.0"
}

/** Stable local capability names used to prevent a benign capability from authorizing another operation. */
object AgentRemoteProtocolCapabilities {
    @JvmField val MCP_INITIALIZE = AgentCapabilityId("remote.mcp.initialize")
    @JvmField val MCP_TOOL_CALL = AgentCapabilityId("remote.mcp.tools.call")
    @JvmField val MCP_TASKS_EXPERIMENTAL = AgentCapabilityId("remote.mcp.tasks.experimental")
    @JvmField val A2A_INITIALIZE = AgentCapabilityId("remote.a2a.initialize")
    @JvmField val A2A_SEND_MESSAGE = AgentCapabilityId("remote.a2a.message.send")
    @JvmField val A2A_CANCEL_TASK = AgentCapabilityId("remote.a2a.task.cancel")

    @JvmStatic
    fun requiredFor(
        protocol: AgentRemoteProtocolKind,
        operation: AgentRemoteOperationKind,
    ): AgentCapabilityId = when (operation) {
        AgentRemoteOperationKind.INITIALIZE -> when (protocol) {
            AgentRemoteProtocolKind.MCP -> MCP_INITIALIZE
            AgentRemoteProtocolKind.A2A -> A2A_INITIALIZE
        }
        AgentRemoteOperationKind.MCP_TOOL_CALL -> {
            require(protocol == AgentRemoteProtocolKind.MCP) { "MCP tool calls require the MCP protocol." }
            MCP_TOOL_CALL
        }
        AgentRemoteOperationKind.A2A_SEND_MESSAGE -> {
            require(protocol == AgentRemoteProtocolKind.A2A) { "A2A messages require the A2A protocol." }
            A2A_SEND_MESSAGE
        }
        AgentRemoteOperationKind.A2A_CANCEL_TASK -> {
            require(protocol == AgentRemoteProtocolKind.A2A) { "A2A cancellation requires the A2A protocol." }
            A2A_CANCEL_TASK
        }
    }
}

/** Extensible transport binding identifier without importing an HTTP, gRPC, JSON-RPC or protocol SDK type. */
class AgentRemoteProtocolBindingId(value: String) {
    val value: String = requireStableAgentId(value, "Agent remote protocol binding is invalid.")

    override fun equals(other: Any?): Boolean = other is AgentRemoteProtocolBindingId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        @JvmField val MCP_STREAMABLE_HTTP = AgentRemoteProtocolBindingId("mcp.streamable-http")
        @JvmField val A2A_JSON_RPC_HTTP = AgentRemoteProtocolBindingId("a2a.json-rpc-http")
        @JvmField val A2A_GRPC = AgentRemoteProtocolBindingId("a2a.grpc")
        @JvmField val A2A_REST_HTTP = AgentRemoteProtocolBindingId("a2a.rest-http")
    }
}

enum class AgentRemoteProtocolKind {
    MCP,
    A2A,
}

enum class AgentRemoteOperationKind {
    INITIALIZE,
    MCP_TOOL_CALL,
    A2A_SEND_MESSAGE,
    A2A_CANCEL_TASK,
}

enum class AgentRemoteAuthenticationScheme {
    OAUTH2_BEARER,
    MUTUAL_TLS,
}

enum class AgentRemoteTaskSupport {
    NONE,
    OPTIONAL,
    REQUIRED,
}

/** One administrator-reviewed MCP tool descriptor. Arguments remain bound per invocation. */
class AgentRemoteToolBinding @JvmOverloads constructor(
    val toolProviderId: ProviderId,
    val toolId: ToolId,
    descriptorDigest: String,
    val taskSupport: AgentRemoteTaskSupport = AgentRemoteTaskSupport.NONE,
) {
    val descriptorDigest: String = requireSha256(
        descriptorDigest,
        "Agent remote tool descriptor digest is invalid.",
    )
    val bindingDigest: String = AgentDigestBuilder("flowweft.agent.remote.tool-binding.v1")
        .add(toolProviderId.value)
        .add(toolId.value)
        .add(this.descriptorDigest)
        .add(taskSupport.name)
        .finish()

    fun matches(operation: AgentRemoteOperationBinding): Boolean =
        toolProviderId == operation.toolProviderId &&
            toolId == operation.toolId &&
            descriptorDigest == operation.toolDescriptorDigest

    override fun toString(): String = "AgentRemoteToolBinding(toolId=${toolId.value}, descriptor=<redacted>)"
}

/**
 * Reference-only credential binding. Secret material, bearer values and private keys never cross
 * the Agent API. A credential is owned by one reviewed peer and one exact protected resource.
 */
class AgentRemoteCredentialBinding(
    credentialReference: Identifier,
    val ownerPeerId: ProviderId,
    val scheme: AgentRemoteAuthenticationScheme,
    protectedResourceAudience: URI,
    scopes: Collection<String>,
    credentialRevision: String,
) {
    val credentialReference: Identifier = requireOpaqueIdentifier(
        credentialReference,
        "Agent remote credential reference is invalid.",
    )
    val protectedResourceAudience: URI = requireAgentRemoteHttpsUri(
        protectedResourceAudience,
        "Agent remote credential audience is invalid.",
    )
    val scopes: Set<String>
    val credentialRevision: String = requireAgentToken(
        credentialRevision,
        AgentContractLimits.MAX_CODE_CODE_POINTS,
        "Agent remote credential revision is invalid.",
    )
    val bindingDigest: String

    init {
        val scopeSnapshot = scopes.map { scope ->
            requireStableAgentId(scope, "Agent remote credential scope is invalid.")
        }
        require(scopeSnapshot.isNotEmpty()) { "Agent remote credential requires at least one scope." }
        require(scopeSnapshot.size <= AgentContractLimits.MAX_CAPABILITIES) {
            "Agent remote credential declares too many scopes."
        }
        require(scopeSnapshot.toSet().size == scopeSnapshot.size) {
            "Agent remote credential scopes must be unique."
        }
        this.scopes = immutableAgentSet(scopeSnapshot)
        val digest = AgentDigestBuilder("flowweft.agent.remote.credential-binding.v1")
            .add(this.credentialReference.value)
            .add(ownerPeerId.value)
            .add(scheme.name)
            .add(this.protectedResourceAudience.toASCIIString())
            .add(this.credentialRevision)
            .add(this.scopes.size)
        this.scopes.map { it }.sorted().forEach(digest::add)
        bindingDigest = digest.finish()
    }

    fun requireOwnedBy(peerId: ProviderId, audience: URI) {
        require(ownerPeerId == peerId) { "Agent remote credential belongs to another peer." }
        require(protectedResourceAudience == requireAgentRemoteHttpsUri(audience, "Agent remote audience is invalid.")) {
            "Agent remote credential audience does not match the protected resource."
        }
    }

    override fun toString(): String =
        "AgentRemoteCredentialBinding(peerId=$ownerPeerId, reference=<redacted>, audience=<redacted>)"
}

/** Administrator-reviewed peer profile; remote discovery may only prove this exact snapshot. */
class AgentRemotePeerProfile @JvmOverloads constructor(
    val peerId: ProviderId,
    val protocol: AgentRemoteProtocolKind,
    protocolVersion: String,
    val bindingId: AgentRemoteProtocolBindingId,
    val protocolProviderId: ProviderId,
    val reconciliationProviderId: ProviderId,
    resourceUri: URI,
    descriptorVersion: String,
    descriptorDigest: String,
    capabilities: Collection<AgentCapabilityId>,
    securitySchemeDigest: String,
    approvedTlsPeerIdentityDigest: String,
    val credential: AgentRemoteCredentialBinding,
    profileRevision: String,
    val maximumRedirects: Int = 0,
    toolBindings: Collection<AgentRemoteToolBinding> = emptyList(),
) {
    val protocolVersion: String = requireAgentToken(
        protocolVersion,
        AgentContractLimits.MAX_CODE_CODE_POINTS,
        "Agent remote protocol version is invalid.",
    )
    val resourceUri: URI = requireAgentRemoteHttpsUri(resourceUri, "Agent remote protected resource URI is invalid.")
    val descriptorVersion: String = requireAgentToken(
        descriptorVersion,
        AgentContractLimits.MAX_CODE_CODE_POINTS,
        "Agent remote descriptor version is invalid.",
    )
    val descriptorDigest: String = requireSha256(descriptorDigest, "Agent remote descriptor digest is invalid.")
    val capabilities: Set<AgentCapabilityId>
    val toolBindings: List<AgentRemoteToolBinding>
    val securitySchemeDigest: String = requireSha256(
        securitySchemeDigest,
        "Agent remote security-scheme digest is invalid.",
    )
    val approvedTlsPeerIdentityDigest: String = requireSha256(
        approvedTlsPeerIdentityDigest,
        "Agent remote TLS peer identity digest is invalid.",
    )
    val profileRevision: String = requireAgentToken(
        profileRevision,
        AgentContractLimits.MAX_CODE_CODE_POINTS,
        "Agent remote peer profile revision is invalid.",
    )
    val capabilityDigest: String
    val toolCatalogDigest: String
    val profileDigest: String

    init {
        val capabilitySnapshot = immutableAgentList(capabilities)
        require(capabilitySnapshot.isNotEmpty()) { "Agent remote peer profile requires a capability." }
        require(capabilitySnapshot.size <= AgentContractLimits.MAX_CAPABILITIES) {
            "Agent remote peer profile declares too many capabilities."
        }
        require(capabilitySnapshot.toSet().size == capabilitySnapshot.size) {
            "Agent remote peer capabilities must be unique."
        }
        require(maximumRedirects == 0) {
            "FlowWeft 1.0 remote profiles must disable automatic redirects."
        }
        credential.requireOwnedBy(peerId, this.resourceUri)
        this.capabilities = immutableAgentSet(capabilitySnapshot)
        val toolSnapshot = immutableAgentList(toolBindings)
        require(toolSnapshot.size <= AgentContractLimits.MAX_TOOLS) {
            "Agent remote peer profile declares too many tools."
        }
        require(toolSnapshot.map { binding -> binding.toolProviderId to binding.toolId }.toSet().size ==
            toolSnapshot.size
        ) { "Agent remote tool bindings must be unique by provider and tool identifier." }
        require(protocol == AgentRemoteProtocolKind.MCP || toolSnapshot.isEmpty()) {
            "A2A Agent Card profiles cannot contain MCP tool bindings."
        }
        require(toolSnapshot.none { binding -> binding.taskSupport != AgentRemoteTaskSupport.NONE } ||
            AgentRemoteProtocolCapabilities.MCP_TASKS_EXPERIMENTAL in this.capabilities
        ) { "MCP Tasks require explicit profile capability and tool-level negotiation." }
        this.toolBindings = toolSnapshot
        val capabilityHasher = AgentDigestBuilder("flowweft.agent.remote.capabilities.v1")
            .add(protocol.name)
            .add(this.protocolVersion)
            .add(this.capabilities.size)
        this.capabilities.map { capability -> capability.value }.sorted().forEach(capabilityHasher::add)
        capabilityDigest = capabilityHasher.finish()
        val toolCatalogHasher = AgentDigestBuilder("flowweft.agent.remote.tool-catalog.v1")
            .add(protocol.name)
            .add(this.protocolVersion)
            .add(this.toolBindings.size)
        this.toolBindings.sortedWith(
            compareBy<AgentRemoteToolBinding> { binding -> binding.toolProviderId.value }
                .thenBy { binding -> binding.toolId.value },
        )
            .forEach { binding -> toolCatalogHasher.add(binding.bindingDigest) }
        toolCatalogDigest = toolCatalogHasher.finish()
        profileDigest = AgentDigestBuilder("flowweft.agent.remote.peer-profile.v1")
            .add(peerId.value)
            .add(protocol.name)
            .add(this.protocolVersion)
            .add(bindingId.value)
            .add(protocolProviderId.value)
            .add(reconciliationProviderId.value)
            .add(this.resourceUri.toASCIIString())
            .add(this.descriptorVersion)
            .add(this.descriptorDigest)
            .add(capabilityDigest)
            .add(toolCatalogDigest)
            .add(this.securitySchemeDigest)
            .add(this.approvedTlsPeerIdentityDigest)
            .add(credential.bindingDigest)
            .add(this.profileRevision)
            .add(maximumRedirects)
            .finish()
    }

    fun requireCapability(capabilityId: AgentCapabilityId) {
        require(capabilityId in capabilities) { "Agent remote peer does not expose the required capability." }
    }

    fun requireOperation(operation: AgentRemoteOperationBinding) {
        require(peerId == operation.peerId && protocol == operation.protocol) {
            "Agent remote operation belongs to another peer profile."
        }
        if (operation.operation == AgentRemoteOperationKind.MCP_TOOL_CALL) {
            require(toolBindings.any { binding -> binding.matches(operation) }) {
                "Agent remote MCP tool descriptor is not in the reviewed catalog."
            }
        }
    }

    fun toolBindingFor(operation: AgentRemoteOperationBinding): AgentRemoteToolBinding? =
        toolBindings.firstOrNull { binding -> binding.matches(operation) }

    override fun toString(): String =
        "AgentRemotePeerProfile(protocol=$protocol, peerId=$peerId, version=$protocolVersion, binding=$bindingId, endpoint=<redacted>)"
}

/** Remote descriptor observation. Local credentials, endpoints and policy revisions are excluded. */
class AgentRemotePeerObservation(
    val peerId: ProviderId,
    val protocol: AgentRemoteProtocolKind,
    protocolVersion: String,
    val bindingId: AgentRemoteProtocolBindingId,
    descriptorVersion: String,
    descriptorDigest: String,
    capabilityDigest: String,
    toolCatalogDigest: String,
    securitySchemeDigest: String,
    val observedAt: Long,
) {
    val protocolVersion: String = requireAgentToken(
        protocolVersion,
        AgentContractLimits.MAX_CODE_CODE_POINTS,
        "Agent remote observed protocol version is invalid.",
    )
    val descriptorVersion: String = requireAgentToken(
        descriptorVersion,
        AgentContractLimits.MAX_CODE_CODE_POINTS,
        "Agent remote observed descriptor version is invalid.",
    )
    val descriptorDigest: String = requireSha256(
        descriptorDigest,
        "Agent remote observed descriptor digest is invalid.",
    )
    val capabilityDigest: String = requireSha256(
        capabilityDigest,
        "Agent remote observed capability digest is invalid.",
    )
    val toolCatalogDigest: String = requireSha256(
        toolCatalogDigest,
        "Agent remote observed tool-catalog digest is invalid.",
    )
    val securitySchemeDigest: String = requireSha256(
        securitySchemeDigest,
        "Agent remote observed security-scheme digest is invalid.",
    )
    val bindingDigest: String

    init {
        requireNonNegativeTime(observedAt, "Agent remote observation time must not be negative.")
        bindingDigest = AgentDigestBuilder("flowweft.agent.remote.peer-observation.v1")
            .add(peerId.value)
            .add(protocol.name)
            .add(this.protocolVersion)
            .add(bindingId.value)
            .add(this.descriptorVersion)
            .add(this.descriptorDigest)
            .add(this.capabilityDigest)
            .add(this.toolCatalogDigest)
            .add(this.securitySchemeDigest)
            .add(observedAt)
            .finish()
    }

    fun requireMatches(profile: AgentRemotePeerProfile) {
        require(peerId == profile.peerId && protocol == profile.protocol) {
            "Agent remote observation belongs to another peer."
        }
        require(protocolVersion == profile.protocolVersion) { "Agent remote protocol version drifted." }
        require(bindingId == profile.bindingId) { "Agent remote protocol binding drifted." }
        require(descriptorVersion == profile.descriptorVersion && descriptorDigest == profile.descriptorDigest) {
            "Agent remote descriptor drifted."
        }
        require(capabilityDigest == profile.capabilityDigest) { "Agent remote capability digest drifted." }
        require(toolCatalogDigest == profile.toolCatalogDigest) { "Agent remote tool catalog drifted." }
        require(securitySchemeDigest == profile.securitySchemeDigest) {
            "Agent remote security scheme drifted."
        }
    }

    override fun toString(): String = "AgentRemotePeerObservation(protocol=$protocol, peerId=$peerId)"
}

/** Canonical adapter payload. The digest is bound separately so mutable or recoded payloads fail closed. */
class AgentRemoteProtocolPayload(
    mediaType: String,
    bytes: ByteArray,
    digest: String,
) {
    val mediaType: String = requireMediaType(mediaType, "Agent remote payload media type is invalid.")
    val digest: String = requireSha256(digest, "Agent remote payload digest is invalid.")
    private val snapshot: ByteArray = immutableAgentBytes(bytes)
    val sizeBytes: Int
        get() = snapshot.size

    init {
        require(snapshot.size <= AgentContractLimits.MAX_BINARY_BYTES) { "Agent remote payload is too large." }
        requireDigestMatches(snapshot, this.digest, "Agent remote payload digest does not match its bytes.")
    }

    fun bytes(): ByteArray = snapshot.copyOf()

    override fun toString(): String = "AgentRemoteProtocolPayload(mediaType=$mediaType, size=$sizeBytes, bytes=<redacted>)"
}

/** Exact subject, peer, action and message/tool/task binding produced from a trusted caller context. */
class AgentRemoteOperationBinding @JvmOverloads constructor(
    val context: AgentRunContext,
    runId: Identifier,
    stepId: Identifier,
    val peerId: ProviderId,
    val protocol: AgentRemoteProtocolKind,
    val operation: AgentRemoteOperationKind,
    val payloadDigest: String,
    action: String,
    resourceType: String,
    resourceId: Identifier,
    resourceRevision: String,
    purpose: String,
    messageId: Identifier? = null,
    messageDigest: String? = null,
    toolProviderId: ProviderId? = null,
    toolId: ToolId? = null,
    toolDescriptorDigest: String? = null,
    toolArgumentsDigest: String? = null,
    remoteTaskId: String? = null,
    parentInvocationId: Identifier? = null,
    parentOperationDigest: String? = null,
    val executorProviderId: ProviderId? = null,
    val executorToolId: ToolId? = null,
) {
    val runId: Identifier = requireOpaqueIdentifier(runId, "Agent remote run identifier is invalid.")
    val stepId: Identifier = requireOpaqueIdentifier(stepId, "Agent remote step identifier is invalid.")
    val action: String = requireStableAgentId(action, "Agent remote authorization action is invalid.")
    val resourceType: String = requireStableAgentId(
        resourceType,
        "Agent remote authorization resource type is invalid.",
    )
    val resourceId: Identifier = requireOpaqueIdentifier(resourceId, "Agent remote authorization resource is invalid.")
    val resourceRevision: String = requireAgentToken(
        resourceRevision,
        AgentContractLimits.MAX_CODE_CODE_POINTS,
        "Agent remote authorization resource revision is invalid.",
    )
    val purpose: String = requireAgentToken(
        purpose,
        AgentContractLimits.MAX_DESCRIPTION_CODE_POINTS,
        "Agent remote authorization purpose is invalid.",
    )
    val messageId: Identifier? = messageId?.let {
        requireOpaqueIdentifier(it, "Agent remote message identifier is invalid.")
    }
    val messageDigest: String? = messageDigest?.let {
        requireSha256(it, "Agent remote message digest is invalid.")
    }
    val toolProviderId: ProviderId? = toolProviderId
    val toolId: ToolId? = toolId
    val toolDescriptorDigest: String? = toolDescriptorDigest?.let {
        requireSha256(it, "Agent remote tool descriptor digest is invalid.")
    }
    val toolArgumentsDigest: String? = toolArgumentsDigest?.let {
        requireSha256(it, "Agent remote tool arguments digest is invalid.")
    }
    val remoteTaskId: String? = remoteTaskId?.let {
        requireAgentToken(it, AgentContractLimits.MAX_ID_CODE_POINTS, "Agent remote task identifier is invalid.")
    }
    val parentInvocationId: Identifier? = parentInvocationId?.let {
        requireOpaqueIdentifier(it, "Agent remote parent invocation identifier is invalid.")
    }
    val parentOperationDigest: String? = parentOperationDigest?.let {
        requireSha256(it, "Agent remote parent operation digest is invalid.")
    }
    val bindingDigest: String

    init {
        requireSha256(payloadDigest, "Agent remote operation payload digest is invalid.")
        val completeMessage = this.messageId != null && this.messageDigest != null
        require((this.messageId == null) == (this.messageDigest == null)) {
            "Agent remote message identity and digest must be supplied together."
        }
        val completeTool = this.toolProviderId != null && this.toolId != null &&
            this.toolDescriptorDigest != null && this.toolArgumentsDigest != null
        val completeExecutor = this.executorProviderId != null && this.executorToolId != null
        require(
            listOf(this.toolProviderId, this.toolId, this.toolDescriptorDigest, this.toolArgumentsDigest)
                .all { it == null } || completeTool,
        ) { "Agent remote tool identity, descriptor and arguments must be supplied together." }
        require((this.executorProviderId == null) == (this.executorToolId == null)) {
            "Agent remote local executor provider and tool must be supplied together."
        }
        when (operation) {
            AgentRemoteOperationKind.INITIALIZE -> require(
                !completeMessage && !completeTool && this.remoteTaskId == null &&
                    this.parentInvocationId == null && this.parentOperationDigest == null && !completeExecutor,
            ) { "Agent remote initialization contains another operation binding." }
            AgentRemoteOperationKind.MCP_TOOL_CALL -> require(
                protocol == AgentRemoteProtocolKind.MCP && completeTool && !completeMessage &&
                    completeExecutor &&
                    this.remoteTaskId == null && this.parentInvocationId == null && this.parentOperationDigest == null &&
                    this.toolArgumentsDigest == payloadDigest,
            ) { "Agent remote MCP tool binding is incomplete or inconsistent." }
            AgentRemoteOperationKind.A2A_SEND_MESSAGE -> require(
                protocol == AgentRemoteProtocolKind.A2A && completeMessage && !completeTool && completeExecutor &&
                    this.remoteTaskId == null && this.parentInvocationId == null &&
                    this.parentOperationDigest == null && this.messageDigest == payloadDigest,
            ) { "Agent remote A2A message binding is incomplete or inconsistent." }
            AgentRemoteOperationKind.A2A_CANCEL_TASK -> require(
                protocol == AgentRemoteProtocolKind.A2A && completeMessage && !completeTool && completeExecutor &&
                    this.remoteTaskId != null && this.parentInvocationId != null &&
                    this.parentOperationDigest != null && this.messageDigest == payloadDigest,
            ) { "Agent remote A2A cancellation binding is incomplete or inconsistent." }
        }
        bindingDigest = AgentDigestBuilder("flowweft.agent.remote.operation-binding.v1")
            .add(context.tenantId.value)
            .add(context.principalType)
            .add(context.principalId.value)
            .add(context.requestId.value)
            .add(context.initiatedAt)
            .add(context.locale ?: "-")
            .add(runId.value)
            .add(stepId.value)
            .add(peerId.value)
            .add(protocol.name)
            .add(operation.name)
            .add(payloadDigest)
            .add(this.action)
            .add(this.resourceType)
            .add(this.resourceId.value)
            .add(this.resourceRevision)
            .add(this.purpose)
            .add(this.messageId?.value ?: "-")
            .add(this.messageDigest ?: "-")
            .add(this.toolProviderId?.value ?: "-")
            .add(this.toolId?.value ?: "-")
            .add(this.toolDescriptorDigest ?: "-")
            .add(this.toolArgumentsDigest ?: "-")
            .add(this.remoteTaskId ?: "-")
            .add(this.parentInvocationId?.value ?: "-")
            .add(this.parentOperationDigest ?: "-")
            .add(this.executorProviderId?.value ?: "-")
            .add(this.executorToolId?.value ?: "-")
            .finish()
    }

    override fun toString(): String =
        "AgentRemoteOperationBinding(protocol=$protocol, operation=$operation, values=<redacted>)"
}

/**
 * Immutable request admitted by the remote-protocol runtime. Every non-initialization operation
 * must carry an [AgentExecutableToolInvocation], proving policy, optional approval, fresh
 * authorization, a consumed one-time execution context and the final dispatch fence. Only its
 * non-secret identifiers and digests are retained in this request.
 */
class AgentRemoteProtocolInvocationRequest(
    requestId: Identifier,
    val operation: AgentRemoteOperationBinding,
    val payload: AgentRemoteProtocolPayload,
    val requiredCapability: AgentCapabilityId,
    approvedProfileDigest: String,
    val authorizationProviderId: ProviderId,
    val budget: AgentBudget,
    val usageBeforeDispatch: AgentUsage,
    idempotencyKey: String,
    val requestedAt: Long,
    val deadlineAt: Long,
    val reconciliationDeadlineAt: Long,
    val maximumResponseBytes: Int,
    val cancellationToken: AgentCancellationToken,
    executableToolInvocation: AgentExecutableToolInvocation?,
) {
    val requestId: Identifier = requireOpaqueIdentifier(requestId, "Agent remote invocation identifier is invalid.")
    val approvedProfileDigest: String = requireSha256(
        approvedProfileDigest,
        "Agent remote approved profile digest is invalid.",
    )
    val idempotencyKey: String = requireAgentToken(
        idempotencyKey,
        AgentContractLimits.MAX_IDEMPOTENCY_KEY_CODE_POINTS,
        "Agent remote idempotency key is invalid.",
    )
    val idempotencyKeyDigest: String = sha256Domain(
        "flowweft.agent.remote.idempotency-key.v1",
        this.idempotencyKey.toByteArray(Charsets.UTF_8),
    )
    val executionContextId: Identifier? = executableToolInvocation?.invocation?.executionContextId
    val executionBindingDigest: String? = executableToolInvocation?.executionBindingDigest
    val executionAuthorizationRevision: String? =
        executableToolInvocation?.finalAuthorizationDecision?.authorizationRevision
    val approvalDecisionId: Identifier? = executableToolInvocation?.invocation?.approvalDecision?.decisionId
    val bindingDigest: String

    init {
        require(payload.digest == operation.payloadDigest) { "Agent remote invocation payload changed after binding." }
        require(payload.sizeBytes <= AgentContractLimits.MAX_ARGUMENT_BYTES) {
            "Agent remote invocation payload exceeds the canonical argument bound."
        }
        require(requiredCapability == AgentRemoteProtocolCapabilities.requiredFor(operation.protocol, operation.operation)) {
            "Agent remote invocation capability does not authorize this protocol operation."
        }
        require(requestedAt >= operation.context.initiatedAt) {
            "Agent remote invocation predates its trusted caller context."
        }
        require(deadlineAt > requestedAt) { "Agent remote invocation deadline must follow its request time." }
        require(reconciliationDeadlineAt >= deadlineAt &&
            reconciliationDeadlineAt - deadlineAt <= MAX_AGENT_REMOTE_RECONCILIATION_WINDOW_MILLIS
        ) { "Agent remote reconciliation deadline is invalid." }
        require(maximumResponseBytes in 1..AgentContractLimits.MAX_BINARY_BYTES) {
            "Agent remote response limit is invalid."
        }
        val reservedUsage = try {
            usageBeforeDispatch.plus(AgentUsage(toolCalls = 1))
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Agent remote usage reservation overflowed.")
        }
        require(budget.allows(reservedUsage)) { "Agent remote invocation exceeds its budget." }
        require(deadlineAt - requestedAt <= budget.maximumDurationMillis - usageBeforeDispatch.durationMillis) {
            "Agent remote invocation deadline exceeds its remaining duration budget."
        }
        when (operation.operation) {
            AgentRemoteOperationKind.INITIALIZE -> require(executableToolInvocation == null) {
                "Agent remote initialization must not consume an unrelated tool execution context."
            }
            AgentRemoteOperationKind.MCP_TOOL_CALL,
            AgentRemoteOperationKind.A2A_SEND_MESSAGE,
            AgentRemoteOperationKind.A2A_CANCEL_TASK -> {
                val executable = requireNotNull(executableToolInvocation) {
                    "Agent remote side effects require an approved one-time executable tool context."
                }
                val authorized = executable.invocation
                require(authorized.tenantId == operation.context.tenantId &&
                    authorized.principalId == operation.context.principalId &&
                    authorized.principalType == operation.context.principalType &&
                    authorized.runId == operation.runId && authorized.stepId == operation.stepId
                ) { "Agent remote executable context belongs to another subject or run." }
                require(authorized.argumentsDigest == payload.digest && authorized.idempotencyKey == idempotencyKey) {
                    "Agent remote executable context changed its canonical payload or idempotency identity."
                }
                require(authorized.authorizationProviderId == authorizationProviderId &&
                    authorized.authorizationAction == operation.action &&
                    authorized.authorizationResourceType == operation.resourceType &&
                    authorized.authorizationResourceId == operation.resourceId &&
                    authorized.authorizationResourceRevision == operation.resourceRevision &&
                    authorized.authorizationPurpose == operation.purpose
                ) { "Agent remote executable context changed its authorization scope." }
                require(requestedAt >= executable.preparedAt && deadlineAt <= authorized.deadlineAt &&
                    deadlineAt - requestedAt <= executable.maximumDurationMillis &&
                    budget.maximumCostMicros <= executable.maximumCostMicros &&
                    maximumResponseBytes <= authorized.descriptor.maximumResultBytes
                ) { "Agent remote invocation exceeds its executable authorization window or reservation." }
                executable.requireExecutor(
                    requireNotNull(operation.executorProviderId),
                    requireNotNull(operation.executorToolId),
                )
                if (operation.operation == AgentRemoteOperationKind.MCP_TOOL_CALL) {
                    require(authorized.descriptorDigest == operation.toolDescriptorDigest) {
                        "Agent remote executable context changed the reviewed MCP tool descriptor."
                    }
                }
            }
        }
        val requestDigest = AgentDigestBuilder("flowweft.agent.remote.invocation-request.v1")
            .add(this.requestId.value)
            .add(operation.bindingDigest)
            .add(payload.mediaType)
            .add(payload.digest)
            .add(payload.sizeBytes)
            .add(requiredCapability.value)
            .add(this.approvedProfileDigest)
            .add(authorizationProviderId.value)
            .add(budget.maximumInputTokens)
            .add(budget.maximumOutputTokens)
            .add(budget.maximumModelCalls)
            .add(budget.maximumToolCalls)
            .add(budget.maximumDurationMillis)
            .add(budget.maximumCostMicros)
            .add(usageBeforeDispatch.inputTokens)
            .add(usageBeforeDispatch.outputTokens)
            .add(usageBeforeDispatch.modelCalls)
            .add(usageBeforeDispatch.toolCalls)
            .add(usageBeforeDispatch.durationMillis)
            .add(usageBeforeDispatch.costMicros)
            .add(usageBeforeDispatch.additionalUnits.size)
        usageBeforeDispatch.additionalUnits.toSortedMap().forEach { (name, value) ->
            requestDigest.add(name).add(value)
        }
        bindingDigest = requestDigest
            .add(this.idempotencyKeyDigest)
            .add(requestedAt)
            .add(deadlineAt)
            .add(reconciliationDeadlineAt)
            .add(maximumResponseBytes)
            .add(this.executionContextId?.value ?: "-")
            .add(this.executionBindingDigest ?: "-")
            .add(this.executionAuthorizationRevision ?: "-")
            .add(this.approvalDecisionId?.value ?: "-")
            .finish()
    }

    fun requireCurrent(atTime: Long) {
        require(atTime in requestedAt until deadlineAt) { "Agent remote invocation is not current." }
        cancellationToken.cancellation()?.let { throw AgentCancellationException(it) }
    }

    override fun toString(): String =
        "AgentRemoteProtocolInvocationRequest(protocol=${operation.protocol}, operation=${operation.operation}, payload=<redacted>)"
}

enum class AgentRemoteAuthorizationPhase {
    PREFLIGHT,
    FINAL_DISPATCH,
    RECONCILIATION,
}

class AgentRemoteAuthorizationRequest(
    requestId: Identifier,
    val providerId: ProviderId,
    val phase: AgentRemoteAuthorizationPhase,
    parentRequestId: Identifier?,
    val invocation: AgentRemoteProtocolInvocationRequest,
    val callerContext: AgentRunContext,
    val peerProfile: AgentRemotePeerProfile,
    targetUri: URI,
    networkBindingDigest: String,
    credentialBindingDigest: String,
    val hopIndex: Int,
    val requestedAt: Long,
    val expiresAt: Long,
) {
    val requestId: Identifier = requireOpaqueIdentifier(requestId, "Agent remote authorization request is invalid.")
    val parentRequestId: Identifier? = parentRequestId?.let {
        requireOpaqueIdentifier(it, "Agent remote parent authorization request is invalid.")
    }
    val targetUri: URI = requireAgentRemoteHttpsUri(targetUri, "Agent remote authorization target is invalid.")
    val networkBindingDigest: String = requireSha256(
        networkBindingDigest,
        "Agent remote authorization network binding is invalid.",
    )
    val credentialBindingDigest: String = requireSha256(
        credentialBindingDigest,
        "Agent remote authorization credential binding is invalid.",
    )
    val bindingDigest: String

    init {
        require(providerId == invocation.authorizationProviderId) {
            "Agent remote authorization provider differs from the invocation."
        }
        require(callerContext.tenantId == invocation.operation.context.tenantId &&
            callerContext.principalId == invocation.operation.context.principalId &&
            callerContext.principalType == invocation.operation.context.principalType &&
            requestedAt >= callerContext.initiatedAt
        ) { "Agent remote authorization caller differs from the trusted invocation subject." }
        require(peerProfile.peerId == invocation.operation.peerId &&
            peerProfile.protocol == invocation.operation.protocol &&
            peerProfile.profileDigest == invocation.approvedProfileDigest
        ) { "Agent remote authorization profile differs from the invocation." }
        require(peerProfile.credential.bindingDigest == this.credentialBindingDigest) {
            "Agent remote authorization uses another credential binding."
        }
        require(hopIndex >= 0) { "Agent remote authorization hop is invalid." }
        val phaseDeadline = if (phase == AgentRemoteAuthorizationPhase.RECONCILIATION) {
            invocation.reconciliationDeadlineAt
        } else {
            invocation.deadlineAt
        }
        require(requestedAt >= invocation.requestedAt && requestedAt < expiresAt && expiresAt <= phaseDeadline) {
            "Agent remote authorization validity window is invalid."
        }
        require((phase == AgentRemoteAuthorizationPhase.PREFLIGHT) == (this.parentRequestId == null)) {
            "Agent remote preflight must be the only root authorization request."
        }
        bindingDigest = AgentDigestBuilder("flowweft.agent.remote.authorization-request.v1")
            .add(this.requestId.value)
            .add(providerId.value)
            .add(phase.name)
            .add(this.parentRequestId?.value ?: "-")
            .add(invocation.bindingDigest)
            .add(callerContext.tenantId.value)
            .add(callerContext.principalType)
            .add(callerContext.principalId.value)
            .add(callerContext.requestId.value)
            .add(callerContext.initiatedAt)
            .add(callerContext.locale ?: "-")
            .add(peerProfile.profileDigest)
            .add(this.targetUri.toASCIIString())
            .add(this.networkBindingDigest)
            .add(this.credentialBindingDigest)
            .add(hopIndex)
            .add(requestedAt)
            .add(expiresAt)
            .finish()
    }

    fun requireChildOf(parent: AgentRemoteAuthorizationRequest) {
        require(phase != AgentRemoteAuthorizationPhase.PREFLIGHT && parentRequestId == parent.requestId) {
            "Agent remote authorization is not a child of the required request."
        }
        require(providerId == parent.providerId && invocation.bindingDigest == parent.invocation.bindingDigest &&
            peerProfile.profileDigest == parent.peerProfile.profileDigest &&
            credentialBindingDigest == parent.credentialBindingDigest
        ) { "Agent remote authorization changed its trusted binding." }
        if (phase == AgentRemoteAuthorizationPhase.FINAL_DISPATCH) {
            require(callerContext.requestId == parent.callerContext.requestId &&
                callerContext.initiatedAt == parent.callerContext.initiatedAt &&
                callerContext.locale == parent.callerContext.locale
            ) { "Agent remote final authorization changed its trusted caller request." }
        }
    }

    override fun toString(): String = "AgentRemoteAuthorizationRequest(phase=$phase, target=<redacted>)"
}

enum class AgentRemoteAuthorizationOutcome {
    ALLOW,
    DENY,
}

class AgentRemoteAuthorizationDecision private constructor(
    decisionId: Identifier,
    val providerId: ProviderId,
    request: AgentRemoteAuthorizationRequest,
    val outcome: AgentRemoteAuthorizationOutcome,
    authorizationRevision: String,
    val decidedAt: Long,
    val expiresAt: Long,
    reasonCode: String?,
) {
    val decisionId: Identifier = requireOpaqueIdentifier(decisionId, "Agent remote authorization decision is invalid.")
    val requestId: Identifier = request.requestId
    val requestBindingDigest: String = request.bindingDigest
    val authorizationRevision: String = requireAgentToken(
        authorizationRevision,
        AgentContractLimits.MAX_CODE_CODE_POINTS,
        "Agent remote authorization revision is invalid.",
    )
    val reasonCode: String? = reasonCode?.let {
        requireAgentCode(it, "Agent remote authorization reason code is invalid.")
    }
    val decisionDigest: String

    init {
        require(providerId == request.providerId) { "Agent remote authorization decision came from another provider." }
        require(decidedAt >= request.requestedAt && decidedAt < expiresAt && expiresAt <= request.expiresAt) {
            "Agent remote authorization decision validity window is invalid."
        }
        require(outcome != AgentRemoteAuthorizationOutcome.DENY || this.reasonCode != null) {
            "Denied Agent remote authorization requires a reason code."
        }
        decisionDigest = AgentDigestBuilder("flowweft.agent.remote.authorization-decision.v1")
            .add(this.decisionId.value)
            .add(providerId.value)
            .add(this.requestId.value)
            .add(this.requestBindingDigest)
            .add(outcome.name)
            .add(this.authorizationRevision)
            .add(decidedAt)
            .add(expiresAt)
            .add(this.reasonCode ?: "-")
            .finish()
    }

    fun requireAllowedFor(request: AgentRemoteAuthorizationRequest, atTime: Long) {
        require(providerId == request.providerId && requestId == request.requestId &&
            requestBindingDigest == request.bindingDigest
        ) { "Agent remote authorization decision does not match its request." }
        require(outcome == AgentRemoteAuthorizationOutcome.ALLOW) { "Agent remote operation is not authorized." }
        require(atTime in decidedAt until expiresAt) { "Agent remote authorization decision is not current." }
    }

    override fun toString(): String = "AgentRemoteAuthorizationDecision(outcome=$outcome)"

    companion object {
        @JvmStatic
        fun allow(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentRemoteAuthorizationRequest,
            authorizationRevision: String,
            decidedAt: Long,
            expiresAt: Long,
        ): AgentRemoteAuthorizationDecision = AgentRemoteAuthorizationDecision(
            decisionId,
            providerId,
            request,
            AgentRemoteAuthorizationOutcome.ALLOW,
            authorizationRevision,
            decidedAt,
            expiresAt,
            null,
        )

        @JvmStatic
        fun deny(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentRemoteAuthorizationRequest,
            authorizationRevision: String,
            decidedAt: Long,
            expiresAt: Long,
            reasonCode: String,
        ): AgentRemoteAuthorizationDecision = AgentRemoteAuthorizationDecision(
            decisionId,
            providerId,
            request,
            AgentRemoteAuthorizationOutcome.DENY,
            authorizationRevision,
            decidedAt,
            expiresAt,
            reasonCode,
        )
    }
}

interface AgentRemoteAuthorizationProvider {
    fun providerId(): ProviderId

    fun authorize(request: AgentRemoteAuthorizationRequest): CompletionStage<AgentRemoteAuthorizationDecision>
}

/** Address bytes come from a configured resolver; constructing this type never performs DNS. */
class AgentRemoteResolvedAddress(address: ByteArray) {
    private val snapshot: ByteArray = address.copyOf()
    val addressDigest: String

    init {
        require(snapshot.size == 4 || snapshot.size == 16) { "Agent remote resolved address is invalid." }
        addressDigest = sha256Domain("flowweft.agent.remote.resolved-address.v1", snapshot)
    }

    fun bytes(): ByteArray = snapshot.copyOf()

    fun isPubliclyRoutable(): Boolean = isPublicAgentRemoteAddress(snapshot)

    override fun toString(): String = "AgentRemoteResolvedAddress(<redacted>)"
}

class AgentRemoteNetworkResolutionRequest(
    requestId: Identifier,
    val peerId: ProviderId,
    val profileDigest: String,
    targetUri: URI,
    previousUri: URI?,
    val hopIndex: Int,
    val requestedAt: Long,
    val deadlineAt: Long,
) {
    val requestId: Identifier = requireOpaqueIdentifier(requestId, "Agent remote resolution request is invalid.")
    val targetUri: URI = requireAgentRemoteHttpsUri(targetUri, "Agent remote resolution target is invalid.")
    val previousUri: URI? = previousUri?.let {
        requireAgentRemoteHttpsUri(it, "Agent remote previous target is invalid.")
    }
    val bindingDigest: String

    init {
        requireSha256(profileDigest, "Agent remote resolution profile digest is invalid.")
        require(hopIndex >= 0) { "Agent remote resolution hop is invalid." }
        require((hopIndex == 0) == (this.previousUri == null)) {
            "Agent remote redirect chain is not contiguous."
        }
        require(requestedAt >= 0L && requestedAt < deadlineAt) { "Agent remote resolution window is invalid." }
        bindingDigest = AgentDigestBuilder("flowweft.agent.remote.network-resolution-request.v1")
            .add(this.requestId.value)
            .add(peerId.value)
            .add(profileDigest)
            .add(this.targetUri.toASCIIString())
            .add(this.previousUri?.toASCIIString() ?: "-")
            .add(hopIndex)
            .add(requestedAt)
            .add(deadlineAt)
            .finish()
    }

    override fun toString(): String = "AgentRemoteNetworkResolutionRequest(hop=$hopIndex, target=<redacted>)"
}

class AgentRemoteNetworkResolution(
    resolutionId: Identifier,
    val providerId: ProviderId,
    request: AgentRemoteNetworkResolutionRequest,
    addresses: Collection<AgentRemoteResolvedAddress>,
    val resolvedAt: Long,
    val expiresAt: Long,
) {
    val resolutionId: Identifier = requireOpaqueIdentifier(resolutionId, "Agent remote resolution identifier is invalid.")
    val requestId: Identifier = request.requestId
    val requestBindingDigest: String = request.bindingDigest
    val targetUri: URI = request.targetUri
    val hopIndex: Int = request.hopIndex
    val addresses: List<AgentRemoteResolvedAddress>
    val addressSetDigest: String
    val bindingDigest: String

    init {
        val addressSnapshot = immutableAgentList(addresses)
        require(addressSnapshot.isNotEmpty() && addressSnapshot.size <= 32) {
            "Agent remote resolution address count is invalid."
        }
        require(addressSnapshot.map { it.addressDigest }.toSet().size == addressSnapshot.size) {
            "Agent remote resolution contains duplicate addresses."
        }
        require(resolvedAt >= request.requestedAt && resolvedAt < expiresAt && expiresAt <= request.deadlineAt) {
            "Agent remote resolution validity window is invalid."
        }
        this.addresses = addressSnapshot
        val addressHasher = AgentDigestBuilder("flowweft.agent.remote.address-set.v1")
            .add(addressSnapshot.size)
        addressSnapshot.map { it.addressDigest }.sorted().forEach(addressHasher::add)
        addressSetDigest = addressHasher.finish()
        bindingDigest = AgentDigestBuilder("flowweft.agent.remote.network-resolution.v1")
            .add(this.resolutionId.value)
            .add(providerId.value)
            .add(this.requestId.value)
            .add(this.requestBindingDigest)
            .add(addressSetDigest)
            .add(resolvedAt)
            .add(expiresAt)
            .finish()
    }

    fun requirePublicAndCurrent(request: AgentRemoteNetworkResolutionRequest, atTime: Long) {
        require(requestId == request.requestId && requestBindingDigest == request.bindingDigest) {
            "Agent remote resolution does not match its request."
        }
        require(atTime in resolvedAt until expiresAt) { "Agent remote resolution is not current." }
        require(addresses.all(AgentRemoteResolvedAddress::isPubliclyRoutable)) {
            "Agent remote resolution contains a non-public address."
        }
    }

    override fun toString(): String = "AgentRemoteNetworkResolution(hop=$hopIndex, addresses=<redacted>)"
}

interface AgentRemoteNetworkResolver {
    fun providerId(): ProviderId

    fun resolve(request: AgentRemoteNetworkResolutionRequest): CompletionStage<AgentRemoteNetworkResolution>
}

class AgentRemoteCredentialLeaseRequest(
    requestId: Identifier,
    val profile: AgentRemotePeerProfile,
    val invocationBindingDigest: String,
    val authorizationRequest: AgentRemoteAuthorizationRequest,
    val authorizationDecision: AgentRemoteAuthorizationDecision,
    val requestedAt: Long,
    val expiresAt: Long,
) {
    val requestId: Identifier = requireOpaqueIdentifier(requestId, "Agent remote credential lease request is invalid.")
    val bindingDigest: String

    init {
        requireSha256(invocationBindingDigest, "Agent remote credential lease invocation binding is invalid.")
        require(authorizationRequest.phase != AgentRemoteAuthorizationPhase.PREFLIGHT) {
            "Agent remote credentials require a final dispatch or reconciliation authorization recheck."
        }
        require(profile.profileDigest == authorizationRequest.peerProfile.profileDigest &&
            invocationBindingDigest == authorizationRequest.invocation.bindingDigest &&
            profile.credential.bindingDigest == authorizationRequest.credentialBindingDigest &&
            authorizationRequest.targetUri == profile.resourceUri
        ) { "Agent remote credential lease changed its authorized subject, profile or invocation." }
        authorizationDecision.requireAllowedFor(authorizationRequest, requestedAt)
        require(requestedAt >= authorizationDecision.decidedAt && requestedAt < expiresAt &&
            expiresAt <= authorizationDecision.expiresAt
        ) { "Agent remote credential lease validity window is invalid." }
        bindingDigest = AgentDigestBuilder("flowweft.agent.remote.credential-lease-request.v1")
            .add(this.requestId.value)
            .add(profile.profileDigest)
            .add(invocationBindingDigest)
            .add(authorizationRequest.bindingDigest)
            .add(authorizationDecision.decisionDigest)
            .add(requestedAt)
            .add(expiresAt)
            .finish()
    }

    override fun toString(): String = "AgentRemoteCredentialLeaseRequest(credential=<redacted>)"
}

/** Opaque server-side lease; it is useful only to a trusted transport sharing the credential broker. */
class AgentRemoteCredentialLease(
    leaseId: Identifier,
    val brokerId: ProviderId,
    request: AgentRemoteCredentialLeaseRequest,
    credentialReference: Identifier,
    val ownerPeerId: ProviderId,
    protectedResourceAudience: URI,
    credentialRevision: String,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    val leaseId: Identifier = requireOpaqueIdentifier(leaseId, "Agent remote credential lease identifier is invalid.")
    val requestId: Identifier = request.requestId
    val requestBindingDigest: String = request.bindingDigest
    val credentialReference: Identifier = requireOpaqueIdentifier(
        credentialReference,
        "Agent remote leased credential reference is invalid.",
    )
    val protectedResourceAudience: URI = requireAgentRemoteHttpsUri(
        protectedResourceAudience,
        "Agent remote leased credential audience is invalid.",
    )
    val credentialRevision: String = requireAgentToken(
        credentialRevision,
        AgentContractLimits.MAX_CODE_CODE_POINTS,
        "Agent remote leased credential revision is invalid.",
    )
    val bindingDigest: String

    init {
        val expected = request.profile.credential
        require(this.credentialReference == expected.credentialReference && ownerPeerId == expected.ownerPeerId &&
            this.protectedResourceAudience == expected.protectedResourceAudience &&
            this.credentialRevision == expected.credentialRevision
        ) { "Agent remote credential broker leased another peer credential." }
        require(issuedAt >= request.requestedAt && issuedAt < expiresAt && expiresAt <= request.expiresAt) {
            "Agent remote credential lease validity window is invalid."
        }
        bindingDigest = AgentDigestBuilder("flowweft.agent.remote.credential-lease.v1")
            .add(this.leaseId.value)
            .add(brokerId.value)
            .add(this.requestId.value)
            .add(this.requestBindingDigest)
            .add(this.credentialReference.value)
            .add(ownerPeerId.value)
            .add(this.protectedResourceAudience.toASCIIString())
            .add(this.credentialRevision)
            .add(issuedAt)
            .add(expiresAt)
            .finish()
    }

    fun requireCurrentFor(request: AgentRemoteCredentialLeaseRequest, atTime: Long) {
        require(requestId == request.requestId && requestBindingDigest == request.bindingDigest) {
            "Agent remote credential lease does not match its request."
        }
        require(atTime in issuedAt until expiresAt) { "Agent remote credential lease is not current." }
    }

    override fun toString(): String = "AgentRemoteCredentialLease(peerId=$ownerPeerId, lease=<redacted>)"
}

interface AgentRemoteCredentialBroker {
    fun brokerId(): ProviderId

    fun lease(request: AgentRemoteCredentialLeaseRequest): CompletionStage<AgentRemoteCredentialLease>
}

class AgentRemoteProtocolDispatchRequest(
    requestId: Identifier,
    val invocation: AgentRemoteProtocolInvocationRequest,
    val profile: AgentRemotePeerProfile,
    val authorizationRequest: AgentRemoteAuthorizationRequest,
    val authorizationDecision: AgentRemoteAuthorizationDecision,
    val networkRequest: AgentRemoteNetworkResolutionRequest,
    val networkResolution: AgentRemoteNetworkResolution,
    val credentialRequest: AgentRemoteCredentialLeaseRequest,
    val credentialLease: AgentRemoteCredentialLease,
    val requestedAt: Long,
) {
    val requestId: Identifier = requireOpaqueIdentifier(requestId, "Agent remote dispatch request is invalid.")
    val hopIndex: Int = networkRequest.hopIndex
    val bindingDigest: String

    init {
        require(profile.profileDigest == invocation.approvedProfileDigest && profile.peerId == invocation.operation.peerId) {
            "Agent remote dispatch profile differs from the invocation."
        }
        require(networkRequest.targetUri == profile.resourceUri) {
            "FlowWeft 1.0 remote dispatch must use the exact approved protected resource."
        }
        require(authorizationRequest.phase == AgentRemoteAuthorizationPhase.FINAL_DISPATCH) {
            "Agent remote dispatch requires a final authorization recheck."
        }
        authorizationDecision.requireAllowedFor(authorizationRequest, requestedAt)
        require(authorizationRequest.invocation.bindingDigest == invocation.bindingDigest &&
            authorizationRequest.peerProfile.profileDigest == profile.profileDigest &&
            authorizationRequest.targetUri == networkRequest.targetUri &&
            authorizationRequest.networkBindingDigest == networkResolution.bindingDigest &&
            networkRequest.peerId == profile.peerId && networkRequest.profileDigest == profile.profileDigest
        ) { "Agent remote dispatch authorization does not bind its exact target." }
        networkResolution.requirePublicAndCurrent(networkRequest, requestedAt)
        require(credentialRequest.profile.profileDigest == profile.profileDigest &&
            credentialRequest.invocationBindingDigest == invocation.bindingDigest &&
            credentialRequest.authorizationDecision.decisionDigest == authorizationDecision.decisionDigest
        ) { "Agent remote dispatch credential request changed its binding." }
        credentialLease.requireCurrentFor(credentialRequest, requestedAt)
        require(requestedAt < invocation.deadlineAt) { "Agent remote dispatch is past its deadline." }
        bindingDigest = AgentDigestBuilder("flowweft.agent.remote.protocol-dispatch-request.v1")
            .add(this.requestId.value)
            .add(invocation.bindingDigest)
            .add(profile.profileDigest)
            .add(authorizationRequest.bindingDigest)
            .add(authorizationDecision.decisionDigest)
            .add(networkRequest.bindingDigest)
            .add(networkResolution.bindingDigest)
            .add(credentialRequest.bindingDigest)
            .add(credentialLease.bindingDigest)
            .add(requestedAt)
            .finish()
    }

    override fun toString(): String =
        "AgentRemoteProtocolDispatchRequest(protocol=${profile.protocol}, hop=$hopIndex, payload=<redacted>)"
}

class AgentRemoteTransportReceipt(
    receiptId: Identifier,
    request: AgentRemoteProtocolDispatchRequest,
    connectedAddressDigest: String,
    tlsPeerIdentityDigest: String,
    val tlsVerified: Boolean,
    val completedAt: Long,
) {
    val receiptId: Identifier = requireOpaqueIdentifier(receiptId, "Agent remote transport receipt is invalid.")
    val dispatchRequestId: Identifier = request.requestId
    val dispatchBindingDigest: String = request.bindingDigest
    val connectedAddressDigest: String = requireSha256(
        connectedAddressDigest,
        "Agent remote connected address digest is invalid.",
    )
    val tlsPeerIdentityDigest: String = requireSha256(
        tlsPeerIdentityDigest,
        "Agent remote TLS receipt identity is invalid.",
    )
    val bindingDigest: String

    init {
        require(this.connectedAddressDigest in request.networkResolution.addresses.map { it.addressDigest }) {
            "Agent remote transport connected outside the approved resolution."
        }
        require(tlsVerified && this.tlsPeerIdentityDigest == request.profile.approvedTlsPeerIdentityDigest) {
            "Agent remote transport did not verify the approved TLS peer."
        }
        require(completedAt >= request.requestedAt && completedAt <= request.invocation.deadlineAt) {
            "Agent remote transport receipt time is invalid."
        }
        bindingDigest = AgentDigestBuilder("flowweft.agent.remote.transport-receipt.v1")
            .add(this.receiptId.value)
            .add(this.dispatchRequestId.value)
            .add(this.dispatchBindingDigest)
            .add(this.connectedAddressDigest)
            .add(this.tlsPeerIdentityDigest)
            .add(tlsVerified)
            .add(completedAt)
            .finish()
    }

    override fun toString(): String = "AgentRemoteTransportReceipt(tlsVerified=$tlsVerified, peer=<redacted>)"
}

enum class AgentRemoteProtocolResultStatus {
    SUCCEEDED,
    FAILED,
    REDIRECT,
    OUTCOME_UNKNOWN,
    CANCELLATION_CONFIRMED,
    CANCELLATION_REJECTED,
}

/** Safe, bounded one-hop result. The provider must never follow a redirect internally. */
class AgentRemoteProtocolDispatchResult(
    resultId: Identifier,
    request: AgentRemoteProtocolDispatchRequest,
    val status: AgentRemoteProtocolResultStatus,
    val transportReceipt: AgentRemoteTransportReceipt,
    val observation: AgentRemotePeerObservation?,
    response: AgentRemoteProtocolPayload?,
    redirectUri: URI?,
    remoteTaskId: String?,
    val usage: AgentUsage,
    evidenceDigest: String,
    safeFailureCode: String?,
    val completedAt: Long,
) {
    val resultId: Identifier = requireOpaqueIdentifier(resultId, "Agent remote dispatch result is invalid.")
    val dispatchRequestId: Identifier = request.requestId
    val dispatchBindingDigest: String = request.bindingDigest
    val response: AgentRemoteProtocolPayload? = response
    val redirectUri: URI? = redirectUri?.let {
        requireAgentRemoteHttpsUri(it, "Agent remote redirect target is invalid.")
    }
    val remoteTaskId: String? = remoteTaskId?.let {
        requireAgentToken(it, AgentContractLimits.MAX_ID_CODE_POINTS, "Agent remote result task identifier is invalid.")
    }
    val evidenceDigest: String = requireSha256(evidenceDigest, "Agent remote result evidence digest is invalid.")
    val safeFailureCode: String? = safeFailureCode?.let {
        requireAgentCode(it, "Agent remote result failure code is invalid.")
    }
    val responseOrigin: AgentContentOrigin = when (request.profile.protocol) {
        AgentRemoteProtocolKind.MCP -> AgentContentOrigin.TOOL
        AgentRemoteProtocolKind.A2A -> AgentContentOrigin.A2A
    }
    val resultDigest: String

    init {
        require(transportReceipt.dispatchRequestId == request.requestId &&
            transportReceipt.dispatchBindingDigest == request.bindingDigest
        ) { "Agent remote transport receipt belongs to another dispatch." }
        require(completedAt == transportReceipt.completedAt) {
            "Agent remote result and transport receipt times differ."
        }
        require(completedAt <= request.invocation.deadlineAt) { "Agent remote result exceeded its deadline." }
        require(this.response == null || this.response.sizeBytes <= request.invocation.maximumResponseBytes) {
            "Agent remote response exceeded its bound."
        }
        observation?.let { observed ->
            observed.requireMatches(request.profile)
            require(observed.observedAt in request.requestedAt..completedAt) {
                "Agent remote peer observation is stale or postdates its result."
            }
        }
        val requiresObservation = status == AgentRemoteProtocolResultStatus.SUCCEEDED ||
            status == AgentRemoteProtocolResultStatus.REDIRECT ||
            status == AgentRemoteProtocolResultStatus.CANCELLATION_CONFIRMED ||
            status == AgentRemoteProtocolResultStatus.CANCELLATION_REJECTED
        require(!requiresObservation || observation != null) {
            "Successful Agent remote protocol evidence requires a pinned peer observation."
        }
        val requiresFailureCode = status == AgentRemoteProtocolResultStatus.FAILED ||
            status == AgentRemoteProtocolResultStatus.OUTCOME_UNKNOWN
        require(requiresFailureCode == (this.safeFailureCode != null)) {
            "Failed or outcome-unknown Agent remote result requires a safe failure code."
        }
        val cancellationOperation = request.invocation.operation.operation == AgentRemoteOperationKind.A2A_CANCEL_TASK
        val cancellationStatus = status == AgentRemoteProtocolResultStatus.CANCELLATION_CONFIRMED ||
            status == AgentRemoteProtocolResultStatus.CANCELLATION_REJECTED
        require(if (cancellationOperation) {
            cancellationStatus || status == AgentRemoteProtocolResultStatus.FAILED ||
                status == AgentRemoteProtocolResultStatus.OUTCOME_UNKNOWN
        } else {
            !cancellationStatus
        }) { "Agent remote cancellation returned an operation-confused status." }
        require(request.invocation.operation.operation != AgentRemoteOperationKind.INITIALIZE || this.remoteTaskId == null) {
            "Agent remote initialization cannot create an unbound remote task."
        }
        if (request.invocation.operation.operation == AgentRemoteOperationKind.MCP_TOOL_CALL) {
            val taskSupport = requireNotNull(request.profile.toolBindingFor(request.invocation.operation)).taskSupport
            require(taskSupport != AgentRemoteTaskSupport.NONE || this.remoteTaskId == null) {
                "MCP tool returned a Task without explicit profile and tool-level negotiation."
            }
            require(taskSupport != AgentRemoteTaskSupport.REQUIRED ||
                status != AgentRemoteProtocolResultStatus.SUCCEEDED || this.remoteTaskId != null
            ) { "MCP tool requiring Tasks completed without a bound remote Task identifier." }
        }
        if (cancellationOperation && this.remoteTaskId != null) {
            require(this.remoteTaskId == request.invocation.operation.remoteTaskId) {
                "Agent remote cancellation returned another task identifier."
            }
        }
        when (status) {
            AgentRemoteProtocolResultStatus.REDIRECT -> require(
                this.redirectUri != null && this.response == null && this.remoteTaskId == null,
            ) { "Agent remote redirect result is invalid." }
            AgentRemoteProtocolResultStatus.SUCCEEDED -> require(this.redirectUri == null) {
                "Agent remote success cannot also redirect."
            }
            AgentRemoteProtocolResultStatus.CANCELLATION_CONFIRMED,
            AgentRemoteProtocolResultStatus.CANCELLATION_REJECTED -> require(
                request.invocation.operation.operation == AgentRemoteOperationKind.A2A_CANCEL_TASK &&
                    this.redirectUri == null,
            ) { "Agent remote cancellation outcome belongs to another operation." }
            AgentRemoteProtocolResultStatus.FAILED,
            AgentRemoteProtocolResultStatus.OUTCOME_UNKNOWN -> require(this.redirectUri == null) {
                "Agent remote failure cannot also redirect."
            }
        }
        val dispatchResultDigest = AgentDigestBuilder("flowweft.agent.remote.protocol-dispatch-result.v1")
            .add(this.resultId.value)
            .add(this.dispatchRequestId.value)
            .add(this.dispatchBindingDigest)
            .add(status.name)
            .add(transportReceipt.bindingDigest)
            .add(observation?.bindingDigest ?: "-")
            .add(this.response?.mediaType ?: "-")
            .add(this.response?.digest ?: "-")
            .add(this.response?.sizeBytes ?: 0)
            .add(responseOrigin.name)
            .add(this.redirectUri?.toASCIIString() ?: "-")
            .add(this.remoteTaskId ?: "-")
            .add(usage.inputTokens)
            .add(usage.outputTokens)
            .add(usage.modelCalls)
            .add(usage.toolCalls)
            .add(usage.durationMillis)
            .add(usage.costMicros)
            .add(usage.additionalUnits.size)
        usage.additionalUnits.toSortedMap().forEach { (name, value) ->
            dispatchResultDigest.add(name).add(value)
        }
        resultDigest = dispatchResultDigest
            .add(this.evidenceDigest)
            .add(this.safeFailureCode ?: "-")
            .add(completedAt)
            .finish()
    }

    override fun toString(): String =
        "AgentRemoteProtocolDispatchResult(status=$status, evidence=<redacted>, payload=<redacted>)"
}

interface AgentRemoteProtocolCall {
    fun completion(): CompletionStage<AgentRemoteProtocolDispatchResult>

    /** Advisory transport cancellation only; true is never evidence that a remote side effect stopped. */
    fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean>
}

/**
 * One-hop protocol adapter. It must connect only to [AgentRemoteNetworkResolution], consume only
 * [AgentRemoteCredentialLease], never resolve/follow redirects itself, and never expose secrets.
 * Before emitting a tool/message/cancellation frame it must freshly negotiate and validate the
 * exact version, binding, descriptor/Agent Card, capabilities, tool catalog and security scheme in
 * [AgentRemotePeerProfile]. Drift fails before that operation frame is sent; the returned
 * [AgentRemotePeerObservation] is evidence of that pre-operation check, not post-hoc discovery.
 */
interface AgentRemoteProtocolProvider {
    fun providerId(): ProviderId

    fun peerId(): ProviderId

    fun protocol(): AgentRemoteProtocolKind

    fun bindingId(): AgentRemoteProtocolBindingId

    fun start(request: AgentRemoteProtocolDispatchRequest): AgentRemoteProtocolCall
}

/** Exact query for an operation whose first dispatch may or may not have taken effect. */
class AgentRemoteProtocolReconciliationRequest(
    requestId: Identifier,
    val originalDispatch: AgentRemoteProtocolDispatchRequest,
    val unknownResult: AgentRemoteProtocolDispatchResult?,
    unknownEvidenceDigest: String,
    val authorizationRequest: AgentRemoteAuthorizationRequest,
    val authorizationDecision: AgentRemoteAuthorizationDecision,
    val networkRequest: AgentRemoteNetworkResolutionRequest,
    val networkResolution: AgentRemoteNetworkResolution,
    val credentialRequest: AgentRemoteCredentialLeaseRequest,
    val credentialLease: AgentRemoteCredentialLease,
    val requestedAt: Long,
) {
    val requestId: Identifier = requireOpaqueIdentifier(
        requestId,
        "Agent remote reconciliation request is invalid.",
    )
    val unknownEvidenceDigest: String = requireSha256(
        unknownEvidenceDigest,
        "Agent remote reconciliation unknown evidence is invalid.",
    )
    val bindingDigest: String

    init {
        unknownResult?.let { result ->
            require(result.status == AgentRemoteProtocolResultStatus.OUTCOME_UNKNOWN &&
                result.dispatchRequestId == originalDispatch.requestId &&
                result.dispatchBindingDigest == originalDispatch.bindingDigest &&
                result.evidenceDigest == this.unknownEvidenceDigest
            ) { "Agent remote reconciliation requires the exact outcome-unknown dispatch." }
        }
        require(authorizationRequest.phase == AgentRemoteAuthorizationPhase.RECONCILIATION) {
            "Agent remote reconciliation requires fresh reconciliation authorization."
        }
        authorizationRequest.requireChildOf(originalDispatch.authorizationRequest)
        authorizationDecision.requireAllowedFor(authorizationRequest, requestedAt)
        require(authorizationRequest.invocation.bindingDigest == originalDispatch.invocation.bindingDigest &&
            authorizationRequest.peerProfile.profileDigest == originalDispatch.profile.profileDigest &&
            authorizationRequest.targetUri == networkRequest.targetUri &&
            authorizationRequest.networkBindingDigest == networkResolution.bindingDigest &&
            networkRequest.peerId == originalDispatch.profile.peerId &&
            networkRequest.profileDigest == originalDispatch.profile.profileDigest &&
            networkRequest.targetUri == originalDispatch.networkRequest.targetUri
        ) { "Agent remote reconciliation authorization changed the original binding." }
        networkResolution.requirePublicAndCurrent(networkRequest, requestedAt)
        require(credentialRequest.profile.profileDigest == originalDispatch.profile.profileDigest &&
            credentialRequest.invocationBindingDigest == originalDispatch.invocation.bindingDigest &&
            credentialRequest.authorizationDecision.decisionDigest == authorizationDecision.decisionDigest
        ) { "Agent remote reconciliation credential request changed its binding." }
        credentialLease.requireCurrentFor(credentialRequest, requestedAt)
        require(requestedAt < originalDispatch.invocation.reconciliationDeadlineAt) {
            "Agent remote reconciliation is past its bounded reconciliation deadline."
        }
        bindingDigest = AgentDigestBuilder("flowweft.agent.remote.protocol-reconciliation-request.v1")
            .add(this.requestId.value)
            .add(originalDispatch.bindingDigest)
            .add(unknownResult?.resultDigest ?: "-")
            .add(this.unknownEvidenceDigest)
            .add(authorizationRequest.bindingDigest)
            .add(authorizationDecision.decisionDigest)
            .add(networkRequest.bindingDigest)
            .add(networkResolution.bindingDigest)
            .add(credentialRequest.bindingDigest)
            .add(credentialLease.bindingDigest)
            .add(requestedAt)
            .finish()
    }

    override fun toString(): String = "AgentRemoteProtocolReconciliationRequest(values=<redacted>)"
}

enum class AgentRemoteProtocolReconciliationOutcome {
    SUCCEEDED,
    FAILED,
    CANCELLATION_CONFIRMED,
    CANCELLATION_REJECTED,
    STILL_UNKNOWN,
}

class AgentRemoteProtocolReconciliationResult(
    resultId: Identifier,
    request: AgentRemoteProtocolReconciliationRequest,
    val outcome: AgentRemoteProtocolReconciliationOutcome,
    connectedAddressDigest: String,
    tlsPeerIdentityDigest: String,
    val tlsVerified: Boolean,
    val observation: AgentRemotePeerObservation?,
    response: AgentRemoteProtocolPayload?,
    remoteTaskId: String?,
    val usage: AgentUsage,
    evidenceDigest: String,
    safeFailureCode: String?,
    val completedAt: Long,
) {
    val resultId: Identifier = requireOpaqueIdentifier(
        resultId,
        "Agent remote reconciliation result is invalid.",
    )
    val requestId: Identifier = request.requestId
    val requestBindingDigest: String = request.bindingDigest
    val connectedAddressDigest: String = requireSha256(
        connectedAddressDigest,
        "Agent remote reconciliation address digest is invalid.",
    )
    val tlsPeerIdentityDigest: String = requireSha256(
        tlsPeerIdentityDigest,
        "Agent remote reconciliation TLS identity is invalid.",
    )
    val evidenceDigest: String = requireSha256(
        evidenceDigest,
        "Agent remote reconciliation evidence digest is invalid.",
    )
    val response: AgentRemoteProtocolPayload? = response
    val remoteTaskId: String? = remoteTaskId?.let {
        requireAgentToken(it, AgentContractLimits.MAX_ID_CODE_POINTS, "Agent remote reconciled task identifier is invalid.")
    }
    val safeFailureCode: String? = safeFailureCode?.let {
        requireAgentCode(it, "Agent remote reconciliation failure code is invalid.")
    }
    val responseOrigin: AgentContentOrigin = when (request.originalDispatch.profile.protocol) {
        AgentRemoteProtocolKind.MCP -> AgentContentOrigin.TOOL
        AgentRemoteProtocolKind.A2A -> AgentContentOrigin.A2A
    }
    val resultDigest: String

    init {
        require(this.connectedAddressDigest in request.networkResolution.addresses.map { it.addressDigest }) {
            "Agent remote reconciliation connected outside the approved resolution."
        }
        require(tlsVerified &&
            this.tlsPeerIdentityDigest == request.originalDispatch.profile.approvedTlsPeerIdentityDigest
        ) { "Agent remote reconciliation did not verify the approved TLS peer." }
        observation?.let { observed ->
            observed.requireMatches(request.originalDispatch.profile)
            require(observed.observedAt in request.requestedAt..completedAt) {
                "Agent remote reconciliation peer observation is stale or postdates its result."
            }
        }
        require(
            outcome == AgentRemoteProtocolReconciliationOutcome.FAILED ||
                outcome == AgentRemoteProtocolReconciliationOutcome.STILL_UNKNOWN || observation != null,
        ) { "Known Agent remote reconciliation outcomes require a pinned peer observation." }
        require(this.response == null ||
            this.response.sizeBytes <= request.originalDispatch.invocation.maximumResponseBytes
        ) { "Agent remote reconciled response exceeded its bound." }
        require(completedAt >= request.requestedAt &&
            completedAt <= request.originalDispatch.invocation.reconciliationDeadlineAt
        ) { "Agent remote reconciliation completion time is invalid." }
        val requiresFailureCode = outcome == AgentRemoteProtocolReconciliationOutcome.FAILED ||
            outcome == AgentRemoteProtocolReconciliationOutcome.STILL_UNKNOWN
        require(requiresFailureCode == (this.safeFailureCode != null)) {
            "Failed or outcome-unknown reconciliation requires a safe failure code."
        }
        require(
            outcome != AgentRemoteProtocolReconciliationOutcome.CANCELLATION_CONFIRMED &&
                outcome != AgentRemoteProtocolReconciliationOutcome.CANCELLATION_REJECTED ||
                request.originalDispatch.invocation.operation.operation == AgentRemoteOperationKind.A2A_CANCEL_TASK,
        ) { "Agent remote cancellation reconciliation belongs to another operation." }
        require(
            request.originalDispatch.invocation.operation.operation != AgentRemoteOperationKind.A2A_CANCEL_TASK ||
                outcome == AgentRemoteProtocolReconciliationOutcome.CANCELLATION_CONFIRMED ||
                outcome == AgentRemoteProtocolReconciliationOutcome.CANCELLATION_REJECTED ||
                outcome == AgentRemoteProtocolReconciliationOutcome.FAILED ||
                outcome == AgentRemoteProtocolReconciliationOutcome.STILL_UNKNOWN,
        ) { "Agent remote cancellation reconciliation returned an operation-confused success." }
        val operation = request.originalDispatch.invocation.operation
        require(operation.operation != AgentRemoteOperationKind.INITIALIZE || this.remoteTaskId == null) {
            "Agent remote initialization reconciliation cannot create a remote task."
        }
        if (operation.operation == AgentRemoteOperationKind.MCP_TOOL_CALL) {
            val taskSupport = requireNotNull(
                request.originalDispatch.profile.toolBindingFor(operation),
            ).taskSupport
            require(taskSupport != AgentRemoteTaskSupport.NONE || this.remoteTaskId == null) {
                "MCP reconciliation returned a Task without explicit negotiation."
            }
            require(taskSupport != AgentRemoteTaskSupport.REQUIRED ||
                outcome != AgentRemoteProtocolReconciliationOutcome.SUCCEEDED || this.remoteTaskId != null
            ) { "MCP reconciliation requiring Tasks lacks a bound Task identifier." }
        }
        if (operation.operation == AgentRemoteOperationKind.A2A_CANCEL_TASK && this.remoteTaskId != null) {
            require(this.remoteTaskId == operation.remoteTaskId) {
                "Agent remote cancellation reconciliation returned another task identifier."
            }
        }
        val reconciliationResultDigest = AgentDigestBuilder("flowweft.agent.remote.protocol-reconciliation-result.v1")
            .add(this.resultId.value)
            .add(this.requestId.value)
            .add(this.requestBindingDigest)
            .add(outcome.name)
            .add(this.connectedAddressDigest)
            .add(this.tlsPeerIdentityDigest)
            .add(tlsVerified)
            .add(observation?.bindingDigest ?: "-")
            .add(this.response?.mediaType ?: "-")
            .add(this.response?.digest ?: "-")
            .add(this.response?.sizeBytes ?: 0)
            .add(this.remoteTaskId ?: "-")
            .add(responseOrigin.name)
            .add(usage.inputTokens)
            .add(usage.outputTokens)
            .add(usage.modelCalls)
            .add(usage.toolCalls)
            .add(usage.durationMillis)
            .add(usage.costMicros)
            .add(usage.additionalUnits.size)
        usage.additionalUnits.toSortedMap().forEach { (name, value) ->
            reconciliationResultDigest.add(name).add(value)
        }
        resultDigest = reconciliationResultDigest
            .add(this.evidenceDigest)
            .add(this.safeFailureCode ?: "-")
            .add(completedAt)
            .finish()
    }

    override fun toString(): String =
        "AgentRemoteProtocolReconciliationResult(outcome=$outcome, evidence=<redacted>)"
}

interface AgentRemoteProtocolReconciliationCall {
    fun completion(): CompletionStage<AgentRemoteProtocolReconciliationResult>

    /** Advisory cancellation of this reconciliation query, not of the original remote operation. */
    fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean>
}

/** Queries the original idempotency identity; implementations must never redispatch the operation. */
interface AgentRemoteProtocolOutcomeReconciler {
    fun providerId(): ProviderId

    fun peerId(): ProviderId

    fun protocol(): AgentRemoteProtocolKind

    fun bindingId(): AgentRemoteProtocolBindingId

    fun reconcile(request: AgentRemoteProtocolReconciliationRequest): AgentRemoteProtocolReconciliationCall
}

private fun requireAgentRemoteHttpsUri(value: URI, message: String): URI {
    require(value.isAbsolute && value.scheme.equals("https", ignoreCase = true)) { message }
    require(value.rawUserInfo == null && value.host != null && value.rawFragment == null) { message }
    require(value.rawQuery == null) { message }
    val port = if (value.port == 443) -1 else value.port
    require(port == -1 || port in 1..65535) { message }
    val path = if (value.rawPath.isNullOrEmpty()) "/" else value.rawPath
    return URI("https", null, value.host.lowercase(), port, path, null, null).normalize()
}

private fun isPublicAgentRemoteAddress(address: ByteArray): Boolean {
    if (address.size == 4) return isPublicAgentRemoteIpv4(address)
    val parsed = InetAddress.getByAddress(address)
    if (parsed.isAnyLocalAddress || parsed.isLoopbackAddress || parsed.isLinkLocalAddress ||
        parsed.isSiteLocalAddress || parsed.isMulticastAddress
    ) return false
    if (address.all { it.toInt() == 0 }) return false
    val first = address[0].toInt() and 0xff
    val second = address[1].toInt() and 0xff
    if (first == 0xff || first and 0xfe == 0xfc || first == 0xfe && second and 0xc0 == 0x80) return false
    if (address.dropLast(1).all { it.toInt() == 0 } && address.last().toInt() == 1) return false
    if (first == 0x00 && second == 0x64 && (address[2].toInt() and 0xff) == 0xff &&
        (address[3].toInt() and 0xff) == 0x9b
    ) return false
    if (first == 0x20 && second == 0x02) return false
    if (first == 0x3f && (second and 0xf0) == 0xf0) return false
    if (first == 0x5f) return false
    if (first == 0x20 && second == 0x01 && (address[2].toInt() and 0xff) == 0x0d &&
        (address[3].toInt() and 0xff) == 0xb8
    ) return false
    if (first == 0x20 && second == 0x01 && (address[2].toInt() and 0xff) == 0x00 &&
        ((address[3].toInt() and 0xff) == 0x00 || (address[3].toInt() and 0xf0) == 0x10 ||
            (address[3].toInt() and 0xf0) == 0x20)
    ) return false
    val ipv4Mapped = address.take(10).all { it.toInt() == 0 } &&
        (address[10].toInt() and 0xff) == 0xff && (address[11].toInt() and 0xff) == 0xff
    val ipv4Compatible = address.take(12).all { it.toInt() == 0 }
    return when {
        ipv4Mapped -> isPublicAgentRemoteIpv4(address.copyOfRange(12, 16))
        ipv4Compatible -> false
        else -> true
    }
}

private fun isPublicAgentRemoteIpv4(address: ByteArray): Boolean {
    val a = address[0].toInt() and 0xff
    val b = address[1].toInt() and 0xff
    val c = address[2].toInt() and 0xff
    if (a == 0 || a == 10 || a == 127 || a >= 224) return false
    if (a == 100 && b in 64..127) return false
    if (a == 169 && b == 254) return false
    if (a == 172 && b in 16..31) return false
    if (a == 192 && b == 168) return false
    if (a == 192 && b == 0 && (c == 0 || c == 2)) return false
    if (a == 192 && b == 88 && c == 99) return false
    if (a == 198 && (b == 18 || b == 19 || b == 51)) return false
    if (a == 203 && b == 0 && c == 113) return false
    return true
}

private const val MAX_AGENT_REMOTE_RECONCILIATION_WINDOW_MILLIS: Long = 30L * 24L * 60L * 60L * 1_000L
