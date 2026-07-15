package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentContentBlock
import ai.icen.fw.agent.api.AgentCitationContentBlock
import ai.icen.fw.agent.api.AgentMessage
import ai.icen.fw.agent.api.AgentMessageRole
import ai.icen.fw.agent.api.AgentToolResultContentBlock
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier

/** Security boundaries where untrusted content can cross into execution or durable state. */
enum class AgentContentSecurityBoundary {
    MODEL_INPUT,
    MODEL_OUTPUT,
    MODEL_TOOL_ARGUMENTS,
    TOOL_OUTPUT,
}

/**
 * Exact, short-lived content-policy question. Payload is available only to the configured policy
 * implementation for secret/DLP and prompt-injection inspection; [toString] and every digest remain
 * payload-free. The decision binds the current ACL revision, provider descriptor, durable state and
 * complete content digests, so it cannot be replayed after provider/config/content drift.
 */
class AgentContentSecurityRequest(
    requestId: Identifier,
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    runId: Identifier,
    val capabilityId: AgentCapabilityId,
    val expectedStateVersion: Long,
    val boundary: AgentContentSecurityBoundary,
    val contentProviderId: ProviderId,
    contentProviderBindingDigest: String,
    operationBindingDigest: String,
    durableStateDigest: String,
    authorizationRevision: String,
    messages: Collection<AgentMessage>,
    blocks: Collection<AgentContentBlock>,
    tools: Collection<AgentToolDescriptor>,
    val requestedAt: Long,
    val expiresAt: Long,
) {
    val requestId: Identifier = requireRuntimeIdentifier(requestId, "Agent content security request identifier is invalid.")
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Agent content security tenant identifier is invalid.")
    val principalId: Identifier = requireRuntimeIdentifier(principalId, "Agent content security principal identifier is invalid.")
    val principalType: String = requireRuntimeCode(principalType, "Agent content security principal type is invalid.")
    val runId: Identifier = requireRuntimeIdentifier(runId, "Agent content security run identifier is invalid.")
    val contentProviderBindingDigest: String = requireRuntimeDigest(
        contentProviderBindingDigest,
        "Agent content security provider binding digest is invalid.",
    )
    val operationBindingDigest: String = requireRuntimeDigest(
        operationBindingDigest,
        "Agent content security operation binding digest is invalid.",
    )
    val durableStateDigest: String = requireRuntimeDigest(
        durableStateDigest,
        "Agent content security durable-state digest is invalid.",
    )
    val authorizationRevision: String = requireRuntimeToken(
        authorizationRevision,
        MAX_RUNTIME_CODE_POINTS,
        "Agent content security authorization revision is invalid.",
    )
    val messages: List<AgentMessage> = runtimeImmutableList(messages, "Agent content security messages exceed the limit.")
    val blocks: List<AgentContentBlock> = runtimeImmutableList(blocks, "Agent content security blocks exceed the limit.")
    val tools: List<AgentToolDescriptor> = runtimeImmutableList(tools, "Agent content security tools exceed the limit.")
    val contentDigest: String
    /** Exact citation-block digests the policy must affirm after authoritative lineage verification. */
    val citationDigests: List<String>
    val bindingDigest: String

    init {
        require(expectedStateVersion >= 0L) { "Agent content security state version is invalid." }
        require(requestedAt >= 0L && expiresAt > requestedAt) { "Agent content security lifetime is invalid." }
        require(this.tools.map { tool -> tool.toolId }.toSet().size == this.tools.size) {
            "Agent content security tool identifiers must be unique."
        }
        when (boundary) {
            AgentContentSecurityBoundary.MODEL_INPUT -> require(this.messages.isNotEmpty() && this.blocks.isEmpty()) {
                "Agent model-input security requests require messages and optional tool descriptors only."
            }
            AgentContentSecurityBoundary.MODEL_OUTPUT,
            AgentContentSecurityBoundary.MODEL_TOOL_ARGUMENTS,
            -> require(
                this.messages.size == 1 &&
                    this.messages.single().role == AgentMessageRole.ASSISTANT &&
                    this.blocks.isEmpty() && this.tools.isEmpty(),
            ) { "Agent model-output security requests require one assistant message only." }
            AgentContentSecurityBoundary.TOOL_OUTPUT -> require(this.messages.isEmpty() && this.tools.isEmpty()) {
                "Agent tool-output security requests require blocks only."
            }
        }
        contentDigest = computeContentDigest()
        citationDigests = runtimeImmutableList(
            (this.messages.asSequence().flatMap { it.blocks.asSequence() } + this.blocks.asSequence())
                .flatMap(::collectCitationDigests)
                .sorted()
                .toList(),
            "Agent content security citations exceed the limit.",
        )
        bindingDigest = AgentRuntimeDigest("flowweft.agent.runtime.content-security-request.v1")
            .add(this.tenantId.value)
            .add(this.principalType)
            .add(this.principalId.value)
            .add(this.runId.value)
            .add(capabilityId.value)
            .add(expectedStateVersion)
            .add(boundary.name)
            .add(contentProviderId.value)
            .add(this.contentProviderBindingDigest)
            .add(this.operationBindingDigest)
            .add(this.durableStateDigest)
            .add(this.authorizationRevision)
            .add(contentDigest)
            .add(citationDigests.size)
            .also { digest -> citationDigests.forEach(digest::add) }
            .add(requestedAt)
            .add(expiresAt)
            .finish()
    }

    fun requireContentIntact() {
        require(computeContentDigest() == contentDigest) { "Agent content changed after its security decision." }
    }

    private fun computeContentDigest(): String {
        val digest = AgentRuntimeDigest("flowweft.agent.runtime.content-security-payload.v1")
            .add(boundary.name)
            .add(messages.size)
        messages.forEach { message ->
            message.requireBindingIntact()
            digest.add(message.bindingDigest)
        }
        digest.add(blocks.size)
        blocks.forEach { block ->
            digest
                .add(requireRuntimeCode(block.kind(), "Agent content security block kind is invalid."))
                .add(block.origin().name)
                .add(requireRuntimeDigest(block.bindingDigest(), "Agent content security block digest is invalid."))
        }
        digest.add(tools.size)
        tools.forEach { tool -> digest.add(tool.descriptorDigest) }
        return digest.finish()
    }

    private fun collectCitationDigests(block: AgentContentBlock): Sequence<String> = when (block) {
        is AgentCitationContentBlock -> sequenceOf(block.bindingDigest())
        is AgentToolResultContentBlock -> block.blocks.asSequence().flatMap(::collectCitationDigests)
        else -> emptySequence()
    }

    override fun toString(): String = "AgentContentSecurityRequest(boundary=$boundary, content=<redacted>)"
}

enum class AgentContentSecurityOutcome {
    ALLOW,
    DENY,
}

/** Payload-free decision whose authority ends with this exact request and lifetime. */
class AgentContentSecurityDecision private constructor(
    decisionId: Identifier,
    val providerId: ProviderId,
    request: AgentContentSecurityRequest,
    val outcome: AgentContentSecurityOutcome,
    policyRevision: String,
    val decidedAt: Long,
    val expiresAt: Long,
    reasonCode: String?,
    verifiedCitationDigests: Collection<String>,
) {
    val decisionId: Identifier = requireRuntimeIdentifier(decisionId, "Agent content security decision identifier is invalid.")
    val requestId: Identifier = request.requestId
    val bindingDigest: String = request.bindingDigest
    val policyRevision: String = requireRuntimeToken(
        policyRevision,
        MAX_RUNTIME_CODE_POINTS,
        "Agent content security policy revision is invalid.",
    )
    val reasonCode: String? = reasonCode?.let {
        requireRuntimeCode(it, "Agent content security denial code is invalid.")
    }
    val verifiedCitationDigests: List<String> = runtimeImmutableList(
        verifiedCitationDigests.map { digest ->
            requireRuntimeDigest(digest, "Agent verified citation digest is invalid.")
        }.sorted(),
        "Agent verified citation digests exceed the limit.",
    )

    init {
        require(
            decidedAt >= request.requestedAt && decidedAt < request.expiresAt &&
                expiresAt > decidedAt && expiresAt <= request.expiresAt,
        ) { "Agent content security decision lifetime is invalid." }
        require(outcome != AgentContentSecurityOutcome.DENY || this.reasonCode != null) {
            "Denied Agent content requires a safe reason code."
        }
        require(outcome == AgentContentSecurityOutcome.ALLOW || this.verifiedCitationDigests.isEmpty()) {
            "Denied Agent content cannot carry citation verification evidence."
        }
    }

    fun requireAllowedFor(request: AgentContentSecurityRequest, atTime: Long) {
        request.requireContentIntact()
        require(requestId == request.requestId && bindingDigest == request.bindingDigest) {
            "Agent content security decision binding does not match."
        }
        require(atTime >= decidedAt && atTime < expiresAt) { "Agent content security decision is no longer current." }
        require(outcome == AgentContentSecurityOutcome.ALLOW) { "Agent content security decision denied the payload." }
        require(verifiedCitationDigests == request.citationDigests) {
            "Agent content security decision did not verify every exact citation."
        }
    }

    override fun toString(): String = "AgentContentSecurityDecision(outcome=$outcome)"

    companion object {
        @JvmStatic
        fun allow(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentContentSecurityRequest,
            policyRevision: String,
            decidedAt: Long,
            expiresAt: Long,
        ): AgentContentSecurityDecision = AgentContentSecurityDecision(
            decisionId,
            providerId,
            request,
            AgentContentSecurityOutcome.ALLOW,
            policyRevision,
            decidedAt,
            expiresAt,
            null,
            emptyList(),
        )

        /** Allows content only after the policy verified every exact citation against current lineage/ACL evidence. */
        @JvmStatic
        fun allow(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentContentSecurityRequest,
            policyRevision: String,
            decidedAt: Long,
            expiresAt: Long,
            verifiedCitationDigests: Collection<String>,
        ): AgentContentSecurityDecision = AgentContentSecurityDecision(
            decisionId,
            providerId,
            request,
            AgentContentSecurityOutcome.ALLOW,
            policyRevision,
            decidedAt,
            expiresAt,
            null,
            verifiedCitationDigests,
        )

        @JvmStatic
        fun deny(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentContentSecurityRequest,
            policyRevision: String,
            decidedAt: Long,
            expiresAt: Long,
            reasonCode: String,
        ): AgentContentSecurityDecision = AgentContentSecurityDecision(
            decisionId,
            providerId,
            request,
            AgentContentSecurityOutcome.DENY,
            policyRevision,
            decidedAt,
            expiresAt,
            reasonCode,
            emptyList(),
        )
    }
}

/**
 * Host-owned local policy. Implementations must not log prompts, results, arguments, or secrets.
 * Every [AgentCitationContentBlock] must be checked against current authoritative lineage and ACL
 * evidence, then returned through the citation-aware [AgentContentSecurityDecision.allow] overload.
 */
interface AgentContentSecurityPort {
    fun providerId(): ProviderId

    fun evaluate(request: AgentContentSecurityRequest): AgentContentSecurityDecision
}
