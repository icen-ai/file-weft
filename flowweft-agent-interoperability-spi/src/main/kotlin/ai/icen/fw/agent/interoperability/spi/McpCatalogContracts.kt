package ai.icen.fw.agent.interoperability.spi

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentRemotePeerObservation
import ai.icen.fw.agent.api.AgentRemotePeerProfile
import ai.icen.fw.agent.api.AgentRemoteProtocolBaselines
import ai.icen.fw.agent.api.AgentRemoteProtocolBindingId
import ai.icen.fw.agent.api.AgentRemoteProtocolCapabilities
import ai.icen.fw.agent.api.AgentRemoteProtocolKind
import ai.icen.fw.agent.api.ProviderId

object AgentInteroperabilityContractVersions {
    const val V1: String = "1"
}

/** Additive capability identifiers accepted by the existing extensible [AgentCapabilityId] model. */
object AgentInteroperabilityCapabilities {
    @JvmField val MCP_RESOURCES_LIST = AgentCapabilityId("remote.mcp.resources.list")
    @JvmField val MCP_RESOURCES_READ = AgentCapabilityId("remote.mcp.resources.read")
    @JvmField val MCP_PROMPTS_LIST = AgentCapabilityId("remote.mcp.prompts.list")
    @JvmField val MCP_PROMPTS_GET = AgentCapabilityId("remote.mcp.prompts.get")
    @JvmField val MCP_CATALOG_SNAPSHOT = AgentCapabilityId("remote.mcp.catalog.snapshot")
    @JvmField val INTEROPERABILITY_DIAGNOSTICS = AgentCapabilityId("remote.interoperability.diagnostics")
}

class McpResourceId(value: String) {
    val value: String = InteroperabilityContractSupport.requireOpaqueReference(
        value,
        "MCP resource identifier is invalid.",
    )

    override fun equals(other: Any?): Boolean = other is McpResourceId && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "McpResourceId(<redacted>)"
}

class McpPromptId(value: String) {
    val value: String = InteroperabilityContractSupport.requireOpaqueReference(
        value,
        "MCP prompt identifier is invalid.",
    )

    override fun equals(other: Any?): Boolean = other is McpPromptId && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "McpPromptId(<redacted>)"
}

/**
 * Administrator-reviewed MCP resource descriptor. The remote URI remains in the adapter registry;
 * this SPI carries only an opaque local identifier and a locator digest, preventing SSRF targets
 * from becoming caller-controlled model fields.
 */
class McpResourceDescriptor private constructor(
    contractVersion: String,
    val peerId: ProviderId,
    val resourceId: McpResourceId,
    resourceRevision: String,
    descriptorVersion: String,
    descriptorDigest: String,
    locatorDigest: String,
    mediaType: String,
    contentDigest: String?,
    val maximumContentBytes: Int,
) {
    val contractVersion: String = InteroperabilityContractSupport.requireText(
        contractVersion,
        InteroperabilityContractSupport.MAX_REVISION_UTF8_BYTES,
        "MCP resource contract version is invalid.",
    )
    val resourceRevision: String = InteroperabilityContractSupport.requireText(
        resourceRevision,
        InteroperabilityContractSupport.MAX_REVISION_UTF8_BYTES,
        "MCP resource revision is invalid.",
    )
    val descriptorVersion: String = InteroperabilityContractSupport.requireText(
        descriptorVersion,
        InteroperabilityContractSupport.MAX_REVISION_UTF8_BYTES,
        "MCP resource descriptor version is invalid.",
    )
    val descriptorDigest: String = InteroperabilityContractSupport.requireSha256(
        descriptorDigest,
        "MCP resource descriptor digest is invalid.",
    )
    val locatorDigest: String = InteroperabilityContractSupport.requireSha256(
        locatorDigest,
        "MCP resource locator digest is invalid.",
    )
    val mediaType: String = InteroperabilityContractSupport.requireMediaType(
        mediaType,
        "MCP resource media type is invalid.",
    )
    val contentDigest: String? = contentDigest?.let {
        InteroperabilityContractSupport.requireSha256(it, "MCP resource content digest is invalid.")
    }
    val requiredCapability: AgentCapabilityId = AgentInteroperabilityCapabilities.MCP_RESOURCES_READ
    val bindingDigest: String

    init {
        require(this.contractVersion == AgentInteroperabilityContractVersions.V1) {
            "Unsupported MCP resource contract version."
        }
        require(maximumContentBytes in 1..InteroperabilityContractSupport.MAX_PAYLOAD_BYTES) {
            "MCP resource content limit is invalid."
        }
        bindingDigest = InteroperabilityContractSupport.digest(
            "flowweft.agent.interoperability.mcp-resource.v1",
        )
            .text(this.contractVersion)
            .text(peerId.value)
            .text(resourceId.value)
            .text(this.resourceRevision)
            .text(this.descriptorVersion)
            .text(this.descriptorDigest)
            .text(this.locatorDigest)
            .text(this.mediaType)
            .optionalText(this.contentDigest)
            .integer(maximumContentBytes)
            .text(requiredCapability.value)
            .finish()
    }

    override fun toString(): String =
        "McpResourceDescriptor(peerId=$peerId, resourceId=<redacted>, locator=<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            contractVersion: String,
            peerId: ProviderId,
            resourceId: McpResourceId,
            resourceRevision: String,
            descriptorVersion: String,
            descriptorDigest: String,
            locatorDigest: String,
            mediaType: String,
            contentDigest: String?,
            maximumContentBytes: Int,
        ): McpResourceDescriptor = McpResourceDescriptor(
            contractVersion,
            peerId,
            resourceId,
            resourceRevision,
            descriptorVersion,
            descriptorDigest,
            locatorDigest,
            mediaType,
            contentDigest,
            maximumContentBytes,
        )
    }
}

/** Reviewed MCP prompt metadata. Prompt text and rendered messages remain untrusted provider data. */
class McpPromptDescriptor private constructor(
    contractVersion: String,
    val peerId: ProviderId,
    val promptId: McpPromptId,
    promptRevision: String,
    descriptorVersion: String,
    descriptorDigest: String,
    argumentsSchemaVersion: String,
    argumentsSchemaDigest: String,
    resultMessageSchemaDigest: String,
    val maximumArgumentBytes: Int,
    val maximumResultBytes: Int,
) {
    val contractVersion: String = InteroperabilityContractSupport.requireText(
        contractVersion,
        InteroperabilityContractSupport.MAX_REVISION_UTF8_BYTES,
        "MCP prompt contract version is invalid.",
    )
    val promptRevision: String = InteroperabilityContractSupport.requireText(
        promptRevision,
        InteroperabilityContractSupport.MAX_REVISION_UTF8_BYTES,
        "MCP prompt revision is invalid.",
    )
    val descriptorVersion: String = InteroperabilityContractSupport.requireText(
        descriptorVersion,
        InteroperabilityContractSupport.MAX_REVISION_UTF8_BYTES,
        "MCP prompt descriptor version is invalid.",
    )
    val descriptorDigest: String = InteroperabilityContractSupport.requireSha256(
        descriptorDigest,
        "MCP prompt descriptor digest is invalid.",
    )
    val argumentsSchemaVersion: String = InteroperabilityContractSupport.requireText(
        argumentsSchemaVersion,
        InteroperabilityContractSupport.MAX_REVISION_UTF8_BYTES,
        "MCP prompt arguments schema version is invalid.",
    )
    val argumentsSchemaDigest: String = InteroperabilityContractSupport.requireSha256(
        argumentsSchemaDigest,
        "MCP prompt arguments schema digest is invalid.",
    )
    val resultMessageSchemaDigest: String = InteroperabilityContractSupport.requireSha256(
        resultMessageSchemaDigest,
        "MCP prompt result message schema digest is invalid.",
    )
    val requiredCapability: AgentCapabilityId = AgentInteroperabilityCapabilities.MCP_PROMPTS_GET
    val bindingDigest: String

    init {
        require(this.contractVersion == AgentInteroperabilityContractVersions.V1) {
            "Unsupported MCP prompt contract version."
        }
        require(maximumArgumentBytes in 1..InteroperabilityContractSupport.MAX_PAYLOAD_BYTES) {
            "MCP prompt argument limit is invalid."
        }
        require(maximumResultBytes in 1..InteroperabilityContractSupport.MAX_PAYLOAD_BYTES) {
            "MCP prompt result limit is invalid."
        }
        bindingDigest = InteroperabilityContractSupport.digest(
            "flowweft.agent.interoperability.mcp-prompt.v1",
        )
            .text(this.contractVersion)
            .text(peerId.value)
            .text(promptId.value)
            .text(this.promptRevision)
            .text(this.descriptorVersion)
            .text(this.descriptorDigest)
            .text(this.argumentsSchemaVersion)
            .text(this.argumentsSchemaDigest)
            .text(this.resultMessageSchemaDigest)
            .integer(maximumArgumentBytes)
            .integer(maximumResultBytes)
            .text(requiredCapability.value)
            .finish()
    }

    override fun toString(): String =
        "McpPromptDescriptor(peerId=$peerId, promptId=<redacted>, prompt=<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            contractVersion: String,
            peerId: ProviderId,
            promptId: McpPromptId,
            promptRevision: String,
            descriptorVersion: String,
            descriptorDigest: String,
            argumentsSchemaVersion: String,
            argumentsSchemaDigest: String,
            resultMessageSchemaDigest: String,
            maximumArgumentBytes: Int,
            maximumResultBytes: Int,
        ): McpPromptDescriptor = McpPromptDescriptor(
            contractVersion,
            peerId,
            promptId,
            promptRevision,
            descriptorVersion,
            descriptorDigest,
            argumentsSchemaVersion,
            argumentsSchemaDigest,
            resultMessageSchemaDigest,
            maximumArgumentBytes,
            maximumResultBytes,
        )
    }
}

/**
 * Immutable extension catalog bound to the already-reviewed peer profile and its exact observation.
 * The existing profile remains authoritative for protocol, transport, TLS, credentials, and tools.
 */
class McpCatalogSnapshot private constructor(
    contractVersion: String,
    val profile: AgentRemotePeerProfile,
    val observation: AgentRemotePeerObservation,
    providerRevision: String,
    resources: Collection<McpResourceDescriptor>,
    prompts: Collection<McpPromptDescriptor>,
    val observedAt: Long,
    val expiresAt: Long,
) {
    val contractVersion: String = InteroperabilityContractSupport.requireText(
        contractVersion,
        InteroperabilityContractSupport.MAX_REVISION_UTF8_BYTES,
        "MCP catalog contract version is invalid.",
    )
    val providerRevision: String = InteroperabilityContractSupport.requireText(
        providerRevision,
        InteroperabilityContractSupport.MAX_REVISION_UTF8_BYTES,
        "MCP catalog provider revision is invalid.",
    )
    val resources: List<McpResourceDescriptor>
    val prompts: List<McpPromptDescriptor>
    val resourceCatalogDigest: String
    val promptCatalogDigest: String
    val catalogDigest: String

    init {
        require(this.contractVersion == AgentInteroperabilityContractVersions.V1) {
            "Unsupported MCP catalog contract version."
        }
        require(profile.protocol == AgentRemoteProtocolKind.MCP &&
            profile.protocolVersion == AgentRemoteProtocolBaselines.MCP_2025_11_25 &&
            profile.bindingId == AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP
        ) { "MCP catalog requires the frozen 2025-11-25 Streamable HTTP profile." }
        profile.requireCapability(AgentRemoteProtocolCapabilities.MCP_INITIALIZE)
        profile.requireCapability(AgentInteroperabilityCapabilities.MCP_CATALOG_SNAPSHOT)
        observation.requireMatches(profile)
        require(observation.observedAt <= observedAt && observedAt >= 0L && expiresAt > observedAt) {
            "MCP catalog observation window is invalid."
        }
        val resourceSnapshot = resources.sortedBy { it.resourceId.value }
        val promptSnapshot = prompts.sortedBy { it.promptId.value }
        require(resourceSnapshot.map { it.resourceId }.toSet().size == resourceSnapshot.size) {
            "MCP resource identifiers must be unique."
        }
        require(promptSnapshot.map { it.promptId }.toSet().size == promptSnapshot.size) {
            "MCP prompt identifiers must be unique."
        }
        require(resourceSnapshot.all { it.peerId == profile.peerId }) {
            "MCP resource descriptor belongs to another peer."
        }
        require(promptSnapshot.all { it.peerId == profile.peerId }) {
            "MCP prompt descriptor belongs to another peer."
        }
        if (resourceSnapshot.isNotEmpty()) {
            profile.requireCapability(AgentInteroperabilityCapabilities.MCP_RESOURCES_LIST)
            profile.requireCapability(AgentInteroperabilityCapabilities.MCP_RESOURCES_READ)
        }
        if (promptSnapshot.isNotEmpty()) {
            profile.requireCapability(AgentInteroperabilityCapabilities.MCP_PROMPTS_LIST)
            profile.requireCapability(AgentInteroperabilityCapabilities.MCP_PROMPTS_GET)
        }
        this.resources = InteroperabilityContractSupport.immutableList(
            resourceSnapshot,
            InteroperabilityContractSupport.MAX_DESCRIPTORS,
            "MCP resources are invalid.",
        )
        this.prompts = InteroperabilityContractSupport.immutableList(
            promptSnapshot,
            InteroperabilityContractSupport.MAX_DESCRIPTORS,
            "MCP prompts are invalid.",
        )
        val resourceDigest = InteroperabilityContractSupport.digest(
            "flowweft.agent.interoperability.mcp-resource-catalog.v1",
        )
            .integer(this.resources.size)
        this.resources.forEach { descriptor -> resourceDigest.text(descriptor.bindingDigest) }
        resourceCatalogDigest = resourceDigest.finish()
        val promptDigest = InteroperabilityContractSupport.digest(
            "flowweft.agent.interoperability.mcp-prompt-catalog.v1",
        )
            .integer(this.prompts.size)
        this.prompts.forEach { descriptor -> promptDigest.text(descriptor.bindingDigest) }
        promptCatalogDigest = promptDigest.finish()
        catalogDigest = InteroperabilityContractSupport.digest(
            "flowweft.agent.interoperability.mcp-catalog-snapshot.v1",
        )
            .text(this.contractVersion)
            .text(profile.profileDigest)
            .text(observation.bindingDigest)
            .text(profile.toolCatalogDigest)
            .text(this.providerRevision)
            .text(resourceCatalogDigest)
            .text(promptCatalogDigest)
            .longValue(observedAt)
            .longValue(expiresAt)
            .finish()
    }

    fun resource(resourceId: McpResourceId): McpResourceDescriptor? =
        resources.firstOrNull { it.resourceId == resourceId }

    fun prompt(promptId: McpPromptId): McpPromptDescriptor? = prompts.firstOrNull { it.promptId == promptId }

    fun requireCurrentFor(
        expectedProfile: AgentRemotePeerProfile,
        expectedObservation: AgentRemotePeerObservation,
        atTime: Long,
    ) {
        require(expectedProfile.profileDigest == profile.profileDigest) { "MCP catalog profile changed." }
        expectedObservation.requireMatches(expectedProfile)
        require(expectedObservation.bindingDigest == observation.bindingDigest) { "MCP catalog observation changed." }
        require(atTime in observedAt until expiresAt) { "MCP catalog is stale." }
    }

    override fun toString(): String =
        "McpCatalogSnapshot(peerId=${profile.peerId}, resources=${resources.size}, prompts=${prompts.size}, values=<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            contractVersion: String,
            profile: AgentRemotePeerProfile,
            observation: AgentRemotePeerObservation,
            providerRevision: String,
            resources: Collection<McpResourceDescriptor>,
            prompts: Collection<McpPromptDescriptor>,
            observedAt: Long,
            expiresAt: Long,
        ): McpCatalogSnapshot = McpCatalogSnapshot(
            contractVersion,
            profile,
            observation,
            providerRevision,
            resources,
            prompts,
            observedAt,
            expiresAt,
        )
    }
}
