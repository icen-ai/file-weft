package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.core.id.Identifier
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentWebRuntimeSecurityTest {
    @Test
    fun `stale trusted context is rejected before authoritative authorization`() {
        val calls = AtomicInteger()
        val provider = object : AgentWebAuthoritativeAuthorizationPort {
            override fun providerId(): ProviderId = ProviderId("host-authz")

            override fun authorize(request: AgentWebAuthorizationRequest): AgentWebAuthorizationDecision {
                calls.incrementAndGet()
                error("stale requests must not reach authorization")
            }
        }
        val security = AgentWebApplicationSecurity(provider, AgentWebRuntimeClock { 201L }, ids())

        assertFailsWith<AgentWebUnauthenticatedException> {
            security.authorize(
                context(expiresAt = 200L),
                AgentWebAuthorizationAction.RUN_READ,
                AgentWebAuthorizationTarget("agent.run", id("run-1")),
            )
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun `allowed decision yields bound persistence scope and denial is hidden`() {
        val allowed = AgentWebApplicationSecurity(
            authorization(outcome = AgentWebAuthorizationOutcome.ALLOW),
            AgentWebRuntimeClock { 120L },
            ids(),
        ).authorize(
            context(),
            AgentWebAuthorizationAction.CONVERSATION_READ,
            AgentWebAuthorizationTarget("agent.conversation", id("conversation-1"), "7"),
        )
        assertEquals(id("tenant-1"), allowed.scope.tenantId)
        assertEquals(id("principal-1"), allowed.scope.principalId)
        assertEquals("revision-1", allowed.scope.authorizationRevision)
        assertEquals(AgentWebAuthorizationAction.CONVERSATION_READ, allowed.scope.action)
        assertEquals("agent.conversation", allowed.scope.target.resourceType)
        assertEquals(id("conversation-1"), allowed.scope.target.resourceId)
        assertEquals("7", allowed.scope.target.resourceRevision)
        assertEquals("interactive-agent-web", allowed.scope.target.purpose)
        assertEquals(64, allowed.scope.requestBindingDigest.length)
        allowed.scope.requireExact(
            AgentWebAuthorizationAction.CONVERSATION_READ,
            AgentWebAuthorizationTarget("agent.conversation", id("conversation-1"), "7"),
        )
        assertFailsWith<IllegalArgumentException> {
            allowed.scope.requireExact(
                AgentWebAuthorizationAction.CONVERSATION_READ,
                AgentWebAuthorizationTarget("agent.conversation", id("conversation-2"), "7"),
            )
        }

        val denied = AgentWebApplicationSecurity(
            authorization(outcome = AgentWebAuthorizationOutcome.DENY),
            AgentWebRuntimeClock { 120L },
            ids(),
        )
        assertFailsWith<AgentWebHiddenException> {
            denied.authorize(
                context(),
                AgentWebAuthorizationAction.CONVERSATION_READ,
                AgentWebAuthorizationTarget("agent.conversation", id("conversation-1")),
            )
        }
    }

    @Test
    fun `provider completion time validates a decision made after request creation`() {
        val ticks = ArrayDeque(listOf(100L, 110L))
        val provider = object : AgentWebAuthoritativeAuthorizationPort {
            override fun providerId(): ProviderId = ProviderId("host-authz")

            override fun authorize(request: AgentWebAuthorizationRequest): AgentWebAuthorizationDecision =
                AgentWebAuthorizationDecision.allow(
                    id("authorization-decision"),
                    providerId(),
                    request,
                    request.context.authorizationRevision,
                    SHA,
                    105L,
                    request.expiresAt,
                )
        }
        val authorized = AgentWebApplicationSecurity(
            provider,
            AgentWebRuntimeClock { ticks.removeFirst() },
            ids(),
        ).authorize(
            context(),
            AgentWebAuthorizationAction.RUN_READ,
            AgentWebAuthorizationTarget("agent.run", id("run-1")),
        )

        assertEquals(110L, authorized.authorizedAt)
        assertEquals(110L, authorized.scope.authorizedAt)
        assertTrue(authorized.scope.authorizationExpiresAt > authorized.authorizedAt)
    }

    @Test
    fun `persistence scope factory rejects denial and a different trusted context`() {
        val original = context()
        val request = AgentWebAuthorizationRequest(
            id("authorization-request"),
            original,
            AgentWebAuthorizationAction.RUN_READ,
            AgentWebAuthorizationTarget("agent.run", id("run-1")),
            120L,
            200L,
        )
        val denial = AgentWebAuthorizationDecision.deny(
            id("authorization-denial"),
            ProviderId("host-authz"),
            request,
            original.authorizationRevision,
            SHA,
            120L,
            200L,
            "authorization.denied",
        )
        assertFailsWith<AgentWebHiddenException> {
            AgentWebAuthorizedPersistenceScope.authorized(original, request, denial, 120L)
        }

        val allowed = AgentWebAuthorizationDecision.allow(
            id("authorization-allow"),
            ProviderId("host-authz"),
            request,
            original.authorizationRevision,
            SHA,
            120L,
            200L,
        )
        val other = AgentWebTrustedContext.authenticated(
            AgentRunContext(id("tenant-1"), id("principal-2"), "USER", id("request-2"), 100L),
            id("authentication-2"),
            "revision-1",
            1_000L,
            SHA,
        )
        assertFailsWith<IllegalArgumentException> {
            AgentWebAuthorizedPersistenceScope.authorized(other, request, allowed, 120L)
        }
        assertNotEquals(other.trustedContextDigest, original.trustedContextDigest)
    }

    private fun authorization(outcome: AgentWebAuthorizationOutcome): AgentWebAuthoritativeAuthorizationPort =
        object : AgentWebAuthoritativeAuthorizationPort {
            private val provider = ProviderId("host-authz")

            override fun providerId(): ProviderId = provider

            override fun authorize(request: AgentWebAuthorizationRequest): AgentWebAuthorizationDecision =
                if (outcome == AgentWebAuthorizationOutcome.ALLOW) {
                    AgentWebAuthorizationDecision.allow(
                        id("authorization-decision"),
                        provider,
                        request,
                        request.context.authorizationRevision,
                        SHA,
                        request.requestedAt,
                        request.expiresAt,
                    )
                } else {
                    AgentWebAuthorizationDecision.deny(
                        id("authorization-decision"),
                        provider,
                        request,
                        request.context.authorizationRevision,
                        SHA,
                        request.requestedAt,
                        request.expiresAt,
                        "authorization.denied",
                    )
                }
        }

    private fun context(expiresAt: Long = 1_000L): AgentWebTrustedContext = AgentWebTrustedContext.authenticated(
        AgentRunContext(id("tenant-1"), id("principal-1"), "USER", id("request-1"), 100L),
        id("authentication-1"),
        "revision-1",
        expiresAt,
        SHA,
    )

    private fun ids(): AgentWebRuntimeIdGenerator {
        val sequence = AtomicInteger()
        return AgentWebRuntimeIdGenerator { purpose -> id("$purpose-${sequence.incrementAndGet()}") }
    }

    private fun id(value: String): Identifier = Identifier(value)

    private companion object {
        val SHA: String = "a".repeat(64)
    }
}
