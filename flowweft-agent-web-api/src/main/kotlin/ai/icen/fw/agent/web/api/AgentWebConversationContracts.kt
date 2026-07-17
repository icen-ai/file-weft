package ai.icen.fw.agent.web.api

import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentCitation
import ai.icen.fw.agent.api.AgentMessageRole
import ai.icen.fw.agent.api.AgentRunFailure
import ai.icen.fw.agent.api.AgentRunStatus
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.core.id.Identifier

/** Only an authorized end-user message may enter through the public conversation boundary. */
class AgentWebUserMessageCommand(
    clientMessageId: Identifier,
    authorizedDisplayText: String,
) {
    val clientMessageId: Identifier = agentWebIdentifier(clientMessageId, "Agent Web client message identifier")
    val authorizedDisplayText: String = agentWebText(
        authorizedDisplayText,
        AGENT_WEB_MAX_TEXT_BYTES,
        "Agent Web user message",
        allowLineBreaks = true,
    )

    override fun toString(): String = "AgentWebUserMessageCommand(<redacted>)"
}

class AgentWebConversationCreateCommand @JvmOverloads constructor(
    val capabilityId: AgentCapabilityId,
    val defaultBudget: AgentBudget,
    title: String? = null,
) {
    val title: String? = agentWebOptionalText(title, AGENT_WEB_MAX_NAME_BYTES, "Agent Web conversation title")

    override fun toString(): String = "AgentWebConversationCreateCommand(<redacted>)"
}

class AgentWebConversationSummaryDto(
    conversationId: Identifier,
    title: String,
    val latestRunStatus: AgentRunStatus?,
    val stateVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val conversationId: Identifier = agentWebIdentifier(conversationId, "Agent Web conversation identifier")
    val title: String = agentWebText(title, AGENT_WEB_MAX_NAME_BYTES, "Agent Web conversation title")

    init {
        require(stateVersion >= 0L && createdAt >= 0L && updatedAt >= createdAt) {
            "Agent Web conversation version or timestamps are invalid."
        }
    }
}

class AgentWebConversationDto(
    val summary: AgentWebConversationSummaryDto,
    val defaultCapabilityId: AgentCapabilityId,
    val defaultBudget: AgentBudget,
) {
    override fun toString(): String = "AgentWebConversationDto(<redacted>)"
}

class AgentWebRunCreateCommand(
    val capabilityId: AgentCapabilityId,
    val message: AgentWebUserMessageCommand,
    val budget: AgentBudget,
    val deadlineAt: Long,
) {
    init {
        require(deadlineAt > 0L) { "Agent Web run deadline is invalid." }
    }

    override fun toString(): String = "AgentWebRunCreateCommand(<redacted>)"
}

/** Durable cursor used by polling, long-poll or SSE adapters without exposing Kotlin Flow. */
class AgentWebDurableCursor(
    runId: Identifier,
    val nextSequence: Long,
    val cursor: AgentWebCursor,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    val runId: Identifier = agentWebIdentifier(runId, "Agent Web cursor run identifier")

    init {
        require(nextSequence > 0L && issuedAt >= 0L && expiresAt > issuedAt) {
            "Agent Web durable cursor sequence or lifetime is invalid."
        }
    }

    override fun toString(): String = "AgentWebDurableCursor(<redacted>)"
}

class AgentWebDurablePage<T>(
    runId: Identifier,
    items: Collection<T>,
    val nextCursor: AgentWebDurableCursor?,
) {
    val runId: Identifier = agentWebIdentifier(runId, "Agent Web durable page run identifier")
    val items: List<T> = agentWebList(items, AGENT_WEB_MAX_PAGE_SIZE, "Agent Web durable page")

    init {
        require(nextCursor == null || nextCursor.runId == this.runId) {
            "Agent Web durable page cursor belongs to another run."
        }
    }
}

/** Public run projection reuses the canonical Agent lifecycle, budget, usage and safe failure ABI. */
class AgentWebRunDto @JvmOverloads constructor(
    runId: Identifier,
    conversationId: Identifier,
    val capabilityId: AgentCapabilityId,
    val status: AgentRunStatus,
    val budget: AgentBudget,
    val usage: AgentUsage,
    val stateVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deadlineAt: Long,
    val messageCursor: AgentWebDurableCursor? = null,
    val eventCursor: AgentWebDurableCursor? = null,
    val failure: AgentRunFailure? = null,
) {
    val runId: Identifier = agentWebIdentifier(runId, "Agent Web run identifier")
    val conversationId: Identifier = agentWebIdentifier(conversationId, "Agent Web run conversation identifier")

    init {
        require(stateVersion >= 0L && createdAt >= 0L && updatedAt >= createdAt && deadlineAt > createdAt) {
            "Agent Web run version or timestamps are invalid."
        }
        require(messageCursor == null || messageCursor.runId == this.runId) {
            "Agent Web message cursor belongs to another run."
        }
        require(eventCursor == null || eventCursor.runId == this.runId) {
            "Agent Web event cursor belongs to another run."
        }
        require((status == AgentRunStatus.FAILED) == (failure != null)) {
            "Agent Web failed run and safe failure detail do not agree."
        }
    }

    override fun toString(): String = "AgentWebRunDto(status=$status, <redacted>)"
}

/**
 * Permission-filtered UI message. System/developer prompts, tool arguments/results, retrieved body
 * text and provider payloads are intentionally not representable here.
 */
class AgentWebVisibleMessageDto(
    messageId: Identifier,
    runId: Identifier,
    val sequence: Long,
    val role: AgentMessageRole,
    authorizedDisplayText: String,
    citations: Collection<AgentWebCitationEvidenceDto>,
    val createdAt: Long,
) {
    val messageId: Identifier = agentWebIdentifier(messageId, "Agent Web message identifier")
    val runId: Identifier = agentWebIdentifier(runId, "Agent Web message run identifier")
    val authorizedDisplayText: String = agentWebText(
        authorizedDisplayText,
        AGENT_WEB_MAX_TEXT_BYTES,
        "Agent Web visible message",
        allowLineBreaks = true,
    )
    val citations: List<AgentWebCitationEvidenceDto> = agentWebList(
        citations,
        AGENT_WEB_MAX_CITATIONS,
        "Agent Web message citations",
    )

    init {
        require(sequence > 0L && createdAt >= 0L) { "Agent Web message sequence or time is invalid." }
        require(role == AgentMessageRole.USER || role == AgentMessageRole.ASSISTANT) {
            "Agent Web exposes only authorized user and assistant display messages."
        }
        require(this.citations.map { evidence -> evidence.citation.citationId }.toSet().size == this.citations.size) {
            "Agent Web message citation identifiers must be unique."
        }
        require(role == AgentMessageRole.ASSISTANT || this.citations.isEmpty()) {
            "Only an Agent assistant display message may contain citations."
        }
    }

    override fun toString(): String = "AgentWebVisibleMessageDto(role=$role, <redacted>)"
}

/** Open event type for durable recovery and streaming protocol evolution. */
class AgentWebRunEventType(value: String) {
    val value: String = agentWebCode(value, "Agent Web run event type")

    override fun equals(other: Any?): Boolean = other is AgentWebRunEventType && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val STATUS = AgentWebRunEventType("STATUS")
        @JvmField val MESSAGE_AVAILABLE = AgentWebRunEventType("MESSAGE_AVAILABLE")
        @JvmField val USAGE = AgentWebRunEventType("USAGE")
        @JvmField val CONFIRMATION_REQUIRED = AgentWebRunEventType("CONFIRMATION_REQUIRED")
        @JvmField val HEARTBEAT = AgentWebRunEventType("HEARTBEAT")
    }
}

/** Payload-free durable event frame suitable for polling or SSE serialization. */
class AgentWebRunEventDto @JvmOverloads constructor(
    runId: Identifier,
    val sequence: Long,
    val occurredAt: Long,
    val type: AgentWebRunEventType,
    val stateVersion: Long,
    val status: AgentRunStatus? = null,
    messageId: Identifier? = null,
    approvalRequestId: Identifier? = null,
    safeCode: String? = null,
) {
    val runId: Identifier = agentWebIdentifier(runId, "Agent Web event run identifier")
    val messageId: Identifier? = messageId?.let { id -> agentWebIdentifier(id, "Agent Web event message identifier") }
    val approvalRequestId: Identifier? = approvalRequestId?.let { id ->
        agentWebIdentifier(id, "Agent Web event approval identifier")
    }
    val safeCode: String? = safeCode?.let { code -> agentWebCode(code, "Agent Web event code") }

    init {
        require(sequence > 0L && occurredAt >= 0L && stateVersion >= 0L) {
            "Agent Web event sequence, time or version is invalid."
        }
        require(type != AgentWebRunEventType.STATUS || status != null) {
            "Agent Web status event requires a status."
        }
        require(type != AgentWebRunEventType.MESSAGE_AVAILABLE || this.messageId != null) {
            "Agent Web message event requires a message reference."
        }
        require(type != AgentWebRunEventType.CONFIRMATION_REQUIRED || this.approvalRequestId != null) {
            "Agent Web confirmation event requires an approval reference."
        }
    }
}

/** Citation metadata plus both pre-filter and current authoritative authorization evidence. */
class AgentWebCitationEvidenceDto private constructor(
    val citation: AgentCitation,
    securityFilterReceiptDigest: String,
    authorizationDecisionId: Identifier,
    context: AgentWebTrustedContext,
    val filteredAt: Long,
) {
    val securityFilterReceiptDigest: String = agentWebSha256(
        securityFilterReceiptDigest,
        "Agent Web citation filter receipt",
    )
    val authorizationDecisionId: Identifier = agentWebIdentifier(
        authorizationDecisionId,
        "Agent Web citation authorization decision identifier",
    )
    val authorizationRevision: String = context.authorizationRevision
    val authorizationExpiresAt: Long = context.authorizationExpiresAt
    val evidenceDigest: String

    init {
        context.requireFresh(filteredAt)
        require(citation.tenantId == context.tenantId) {
            "Agent Web citation does not belong to the current tenant."
        }
        evidenceDigest = AgentWebDigest("flowweft.agent.web.citation-evidence.v1")
            .add(citation.citationId.value)
            .add(citation.tenantId.value)
            .add(citation.documentId.value)
            .add(citation.documentVersionId.value)
            .add(citation.evidenceId.value)
            .add(citation.contentDigest)
            .add(citation.startOffset?.toString() ?: "-")
            .add(citation.endOffset?.toString() ?: "-")
            .add(citation.pageNumber?.toString() ?: "-")
            .add(context.tenantId.value)
            .add(context.principalType)
            .add(context.principalId.value)
            .add(context.authenticationId.value)
            .add(context.authorizationRevision)
            .add(context.authorizationExpiresAt)
            .add(context.trustedContextDigest)
            .add(this.securityFilterReceiptDigest)
            .add(this.authorizationDecisionId.value)
            .add(filteredAt)
            .finish()
    }

    override fun toString(): String = "AgentWebCitationEvidenceDto(<redacted>)"

    companion object {
        @JvmStatic
        fun authorized(
            citation: AgentCitation,
            context: AgentWebTrustedContext,
            securityFilterReceiptDigest: String,
            authorizationDecisionId: Identifier,
            filteredAt: Long,
        ): AgentWebCitationEvidenceDto = AgentWebCitationEvidenceDto(
            citation,
            securityFilterReceiptDigest,
            authorizationDecisionId,
            context,
            filteredAt,
        )
    }
}

class AgentWebRunCancelCommand(reasonCode: String) {
    val reasonCode: String = agentWebCode(reasonCode, "Agent Web cancellation reason")
}
