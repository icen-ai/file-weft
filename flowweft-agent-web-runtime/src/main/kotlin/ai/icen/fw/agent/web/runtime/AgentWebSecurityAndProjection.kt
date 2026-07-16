package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.api.AgentContentOrigin
import ai.icen.fw.agent.api.AgentMessageRole
import ai.icen.fw.agent.api.AgentRunApprovalRequiredEvent
import ai.icen.fw.agent.api.AgentRunMessageEvent
import ai.icen.fw.agent.api.AgentRunStatusChangedEvent
import ai.icen.fw.agent.api.AgentRunUsageEvent
import ai.icen.fw.agent.api.AgentTextContentBlock
import ai.icen.fw.agent.web.api.AgentWebCitationEvidenceDto
import ai.icen.fw.agent.web.api.AgentWebDurableCursor
import ai.icen.fw.agent.web.api.AgentWebRunEventDto
import ai.icen.fw.agent.web.api.AgentWebRunEventType
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.agent.web.api.AgentWebVisibleMessageDto
import ai.icen.fw.core.id.Identifier

/** Shared gate: freshness is checked before the authoritative provider is called. */
class AgentWebApplicationSecurity @JvmOverloads constructor(
    private val authorization: AgentWebAuthoritativeAuthorizationPort,
    private val clock: AgentWebRuntimeClock,
    private val ids: AgentWebRuntimeIdGenerator,
    private val decisionLifetimeMillis: Long = 5_000L,
) {
    init {
        require(decisionLifetimeMillis in 1L..60_000L) { "Agent Web authorization decision lifetime is invalid." }
    }

    fun authorize(
        context: AgentWebTrustedContext,
        action: AgentWebAuthorizationAction,
        target: AgentWebAuthorizationTarget,
    ): AgentWebAuthorizedCall {
        val now = clock.currentTimeMillis()
        try {
            context.requireFresh(now)
        } catch (_: IllegalArgumentException) {
            throw AgentWebUnauthenticatedException()
        }
        val expiresAt = minOf(
            context.authorizationExpiresAt,
            if (now > Long.MAX_VALUE - decisionLifetimeMillis) Long.MAX_VALUE else now + decisionLifetimeMillis,
        )
        if (expiresAt <= now) throw AgentWebUnauthenticatedException()
        val request = AgentWebAuthorizationRequest(
            ids.nextId("agent-web-authorization-request"),
            context,
            action,
            target,
            now,
            expiresAt,
        )
        val decision = try {
            authorization.authorize(request)
        } catch (_: RuntimeException) {
            throw AgentWebUnavailableException()
        }
        if (decision.providerId != authorization.providerId()) throw AgentWebUnavailableException()
        val completedAt = clock.currentTimeMillis()
        try {
            context.requireFresh(completedAt)
        } catch (_: IllegalArgumentException) {
            throw AgentWebUnauthenticatedException()
        }
        try {
            decision.requireAllowedFor(request, completedAt)
        } catch (hidden: AgentWebHiddenException) {
            throw hidden
        } catch (_: IllegalArgumentException) {
            throw AgentWebUnavailableException()
        }
        return AgentWebAuthorizedCall(
            AgentWebAuthorizedPersistenceScope.authorized(context, request, decision, completedAt),
            decision,
            completedAt,
        )
    }

    fun currentTimeMillis(): Long = clock.currentTimeMillis()
    fun nextId(purpose: String): Identifier = ids.nextId(purpose)
}

class AgentWebAuthorizedCall internal constructor(
    val scope: AgentWebAuthorizedPersistenceScope,
    val decision: AgentWebAuthorizationDecision,
    val authorizedAt: Long,
)

/** Strict default: only canonical text from the USER or MODEL/A2A role is displayable. */
class StrictAgentWebVisibleMessageProjector {
    fun project(
        context: AgentWebTrustedContext,
        record: AgentWebVisibleMessageRecord,
        citations: Collection<AgentWebCitationEvidenceDto>,
    ): AgentWebVisibleMessageDto {
        record.message.requireBindingIntact()
        val expectedOrigins = when (record.message.role) {
            AgentMessageRole.USER -> setOf(AgentContentOrigin.USER)
            AgentMessageRole.ASSISTANT -> setOf(AgentContentOrigin.MODEL, AgentContentOrigin.A2A)
            else -> throw AgentWebHiddenException()
        }
        val text = record.message.blocks.map { block ->
            val canonical = block as? AgentTextContentBlock ?: throw AgentWebHiddenException()
            if (canonical.origin() !in expectedOrigins) throw AgentWebHiddenException()
            canonical.text
        }.joinToString("\n")
        return AgentWebVisibleMessageDto(
            record.message.id,
            record.runId,
            record.sequence,
            record.message.role,
            text,
            citations,
            record.message.createdAt,
        )
    }
}

class StrictAgentWebRunEventProjector {
    fun project(record: AgentWebRunEventRecord): AgentWebRunEventDto = when (val event = record.event) {
        is AgentRunStatusChangedEvent -> AgentWebRunEventDto(
            event.runId,
            event.sequence,
            event.occurredAt,
            AgentWebRunEventType.STATUS,
            record.stateVersion,
            status = event.currentStatus,
            safeCode = event.reasonCode,
        )
        is AgentRunMessageEvent -> AgentWebRunEventDto(
            event.runId,
            event.sequence,
            event.occurredAt,
            AgentWebRunEventType.MESSAGE_AVAILABLE,
            record.stateVersion,
            messageId = event.message.id,
        )
        is AgentRunUsageEvent -> AgentWebRunEventDto(
            event.runId,
            event.sequence,
            event.occurredAt,
            AgentWebRunEventType.USAGE,
            record.stateVersion,
        )
        is AgentRunApprovalRequiredEvent -> AgentWebRunEventDto(
            event.runId,
            event.sequence,
            event.occurredAt,
            AgentWebRunEventType.CONFIRMATION_REQUIRED,
            record.stateVersion,
            approvalRequestId = event.approvalRequest.requestId,
        )
        else -> throw AgentWebHiddenException()
    }
}

internal fun <T> durableCursor(
    runId: Identifier,
    page: AgentWebStoredDurablePage<T>,
): AgentWebDurableCursor? = page.nextSequence?.let { sequence ->
    AgentWebDurableCursor(
        runId,
        sequence,
        requireNotNull(page.nextCursor),
        requireNotNull(page.cursorIssuedAt),
        requireNotNull(page.cursorExpiresAt),
    )
}
