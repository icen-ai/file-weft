package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.web.api.AgentWebApplicationResult
import ai.icen.fw.agent.web.api.AgentWebErrorCode
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.core.id.Identifier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AgentWebPersistenceIntegrityTest {
    @Test
    fun `malicious reservation acknowledgement fails closed as unavailable`() {
        val context = context()
        val scope = scope(context, AgentWebAuthorizationAction.RUN_CANCEL, "agent.run", id("run-1"))
        val mutation = AgentWebMutationScope.bind(
            context,
            "raw-key-never-persisted",
            AgentWebAuthorizationAction.RUN_CANCEL,
            id("run-1"),
            SHA_A,
        )
        val journal = object : AgentWebMutationJournal {
            override fun reserve(
                scope: AgentWebAuthorizedPersistenceScope,
                mutation: AgentWebMutationScope,
                operationId: Identifier,
                requestedAt: Long,
            ): AgentWebMutationReserveResult = AgentWebMutationReserveResult(
                AgentWebMutationReserveStatus.CREATED,
                AgentWebMutationRecord(
                    mutation,
                    id("wrong-operation"),
                    AgentWebMutationStatus.RESERVED,
                    null,
                    null,
                    null,
                    requestedAt,
                    requestedAt,
                ),
            )

            override fun compareAndSet(
                scope: AgentWebAuthorizedPersistenceScope,
                transition: AgentWebMutationTransition,
            ): AgentWebMutationRecord = error("unused")
        }

        val result: AgentWebApplicationResult<String> = agentWebApplicationCall {
            journal.reserveBound(scope, mutation, id("proposed-operation"), 100L)
            AgentWebApplicationResult.success("must-not-succeed")
        }

        assertEquals(AgentWebErrorCode.FEATURE_UNAVAILABLE, result.code)
        assertNotEquals(AgentWebErrorCode.INVALID_REQUEST, result.code)
        assertEquals(null, result.value)
    }

    @Test
    fun `malicious CAS acknowledgement cannot complete another status`() {
        val context = context()
        val scope = scope(context, AgentWebAuthorizationAction.RUN_CANCEL, "agent.run", id("run-1"))
        val mutation = AgentWebMutationScope.bind(
            context,
            "cancel-key",
            AgentWebAuthorizationAction.RUN_CANCEL,
            id("run-1"),
            SHA_A,
        )
        val transition = AgentWebMutationTransition(
            mutation,
            id("operation-1"),
            AgentWebMutationStatus.RESERVED,
            AgentWebMutationStatus.SUCCEEDED,
            id("run-1"),
            2L,
            null,
            120L,
        )
        val journal = object : AgentWebMutationJournal {
            override fun reserve(
                scope: AgentWebAuthorizedPersistenceScope,
                mutation: AgentWebMutationScope,
                operationId: Identifier,
                requestedAt: Long,
            ): AgentWebMutationReserveResult = error("unused")

            override fun compareAndSet(
                scope: AgentWebAuthorizedPersistenceScope,
                transition: AgentWebMutationTransition,
            ): AgentWebMutationRecord = AgentWebMutationRecord(
                transition.scope,
                transition.operationId,
                AgentWebMutationStatus.FAILED,
                null,
                null,
                "malicious.different-status",
                100L,
                transition.transitionedAt,
            )
        }

        val result: AgentWebApplicationResult<String> = agentWebApplicationCall {
            journal.transitionBound(scope, transition)
            AgentWebApplicationResult.success("must-not-succeed")
        }

        assertEquals(AgentWebErrorCode.FEATURE_UNAVAILABLE, result.code)
        assertNotEquals(AgentWebErrorCode.INVALID_REQUEST, result.code)
    }

    @Test
    fun `same idempotency key with another command is an exact conflict not a replay`() {
        val context = context()
        val scope = scope(context, AgentWebAuthorizationAction.RUN_CANCEL, "agent.run", id("run-1"))
        val requested = AgentWebMutationScope.bind(
            context,
            "same-key",
            AgentWebAuthorizationAction.RUN_CANCEL,
            id("run-1"),
            SHA_A,
        )
        val existing = AgentWebMutationScope.bind(
            context,
            "same-key",
            AgentWebAuthorizationAction.RUN_CANCEL,
            id("run-1"),
            SHA_B,
        )
        assertEquals(requested.scopeDigest, existing.scopeDigest)
        assertNotEquals(requested.commandDigest, existing.commandDigest)
        val journal = object : AgentWebMutationJournal {
            override fun reserve(
                scope: AgentWebAuthorizedPersistenceScope,
                mutation: AgentWebMutationScope,
                operationId: Identifier,
                requestedAt: Long,
            ): AgentWebMutationReserveResult = AgentWebMutationReserveResult(
                AgentWebMutationReserveStatus.CONFLICT,
                AgentWebMutationRecord(
                    existing,
                    id("existing-operation"),
                    AgentWebMutationStatus.SUCCEEDED,
                    id("run-1"),
                    1L,
                    null,
                    90L,
                    95L,
                ),
            )

            override fun compareAndSet(
                scope: AgentWebAuthorizedPersistenceScope,
                transition: AgentWebMutationTransition,
            ): AgentWebMutationRecord = error("unused")
        }

        val result = journal.reserveBound(scope, requested, id("new-operation"), 100L)

        assertEquals(AgentWebMutationReserveStatus.CONFLICT, result.status)
        assertEquals(existing.commandDigest, result.record.scope.commandDigest)
    }

    @Test
    fun `wrong repository record is never reported as invalid client input or success`() {
        val expected = conversation("Expected")
        val wrong = conversation("Substituted")
        val write = AgentWebConversationWriteResult(AgentWebRepositoryWriteStatus.APPLIED, wrong)

        val result: AgentWebApplicationResult<String> = agentWebApplicationCall {
            write.requireExact(expected)
            AgentWebApplicationResult.success("must-not-succeed")
        }

        assertEquals(AgentWebErrorCode.FEATURE_UNAVAILABLE, result.code)
        assertNotEquals(AgentWebErrorCode.INVALID_REQUEST, result.code)
        assertEquals(null, result.value)
    }

    @Test
    fun `downstream idempotency token is stable scoped and contains no raw key`() {
        val context = context()
        val raw = "browser-secret-idempotency-key"
        val runToken = agentWebDerivedIdempotencyToken(
            context,
            raw,
            AgentWebAuthorizationAction.RUN_CREATE,
            id("conversation-1"),
        )
        val same = agentWebDerivedIdempotencyToken(
            context,
            raw,
            AgentWebAuthorizationAction.RUN_CREATE,
            id("conversation-1"),
        )
        val differentTarget = agentWebDerivedIdempotencyToken(
            context,
            raw,
            AgentWebAuthorizationAction.RUN_CREATE,
            id("conversation-2"),
        )

        assertEquals(64, runToken.length)
        assertEquals(same, runToken)
        assertNotEquals(differentTarget, runToken)
        assertEquals(false, runToken.contains(raw))
    }

    @Test
    fun `reauthorization revision and request changes replay once while another principal is isolated`() {
        val firstContext = context("revision-1", "principal-1", "request-1")
        val reauthorizedContext = context("revision-2", "principal-1", "request-2")
        val otherPrincipal = context("revision-2", "principal-2", "request-3")
        val raw = "stable-browser-key"
        val first = AgentWebMutationScope.bind(
            firstContext, raw, AgentWebAuthorizationAction.RUN_CANCEL, id("run-1"), SHA_A,
        )
        val reauthorized = AgentWebMutationScope.bind(
            reauthorizedContext, raw, AgentWebAuthorizationAction.RUN_CANCEL, id("run-1"), SHA_A,
        )
        val isolated = AgentWebMutationScope.bind(
            otherPrincipal, raw, AgentWebAuthorizationAction.RUN_CANCEL, id("run-1"), SHA_A,
        )
        assertEquals(first.scopeDigest, reauthorized.scopeDigest)
        assertNotEquals(first.scopeDigest, isolated.scopeDigest)

        var stored: AgentWebMutationRecord? = null
        var dispatches = 0
        val journal = object : AgentWebMutationJournal {
            override fun reserve(
                scope: AgentWebAuthorizedPersistenceScope,
                mutation: AgentWebMutationScope,
                operationId: Identifier,
                requestedAt: Long,
            ): AgentWebMutationReserveResult {
                val existing = stored
                if (existing != null) return AgentWebMutationReserveResult(AgentWebMutationReserveStatus.REPLAY, existing)
                val created = AgentWebMutationRecord(
                    mutation, operationId, AgentWebMutationStatus.RESERVED,
                    null, null, null, requestedAt, requestedAt,
                )
                stored = created
                return AgentWebMutationReserveResult(AgentWebMutationReserveStatus.CREATED, created)
            }

            override fun compareAndSet(
                scope: AgentWebAuthorizedPersistenceScope,
                transition: AgentWebMutationTransition,
            ): AgentWebMutationRecord = error("unused")
        }
        listOf(firstContext, reauthorizedContext).forEachIndexed { index, trusted ->
            val mutation = if (index == 0) first else reauthorized
            val reserved = journal.reserveBound(
                scope(trusted, AgentWebAuthorizationAction.RUN_CANCEL, "agent.run", id("run-1")),
                mutation,
                id("operation-${index + 1}"),
                100L + index,
            )
            if (reserved.status == AgentWebMutationReserveStatus.CREATED) dispatches++
        }
        assertEquals(1, dispatches)
    }

    private fun conversation(title: String): AgentWebConversationRecord = AgentWebConversationRecord(
        id("tenant-1"),
        id("principal-1"),
        "USER",
        id("conversation-1"),
        title,
        AgentCapabilityId("agent.chat"),
        AgentBudget(10L, 10L, 1, 0, 1_000L),
        null,
        0L,
        100L,
        100L,
    )

    private fun scope(
        context: AgentWebTrustedContext,
        action: AgentWebAuthorizationAction,
        resourceType: String,
        resourceId: Identifier,
    ): AgentWebAuthorizedPersistenceScope = AgentWebApplicationSecurity(
        allowAuthorization(),
        AgentWebRuntimeClock { 100L },
        ids(),
    ).authorize(context, action, AgentWebAuthorizationTarget(resourceType, resourceId)).scope

    private fun allowAuthorization(): AgentWebAuthoritativeAuthorizationPort =
        object : AgentWebAuthoritativeAuthorizationPort {
            private val provider = ProviderId("host-authz")
            override fun providerId(): ProviderId = provider
            override fun authorize(request: AgentWebAuthorizationRequest): AgentWebAuthorizationDecision =
                AgentWebAuthorizationDecision.allow(
                    id("authorization-${request.action.value}"),
                    provider,
                    request,
                    request.context.authorizationRevision,
                    SHA_A,
                    request.requestedAt,
                    request.expiresAt,
                )
        }

    private fun context(
        revision: String = "revision-1",
        principal: String = "principal-1",
        request: String = "request-1",
    ): AgentWebTrustedContext = AgentWebTrustedContext.authenticated(
        AgentRunContext(id("tenant-1"), id(principal), "USER", id(request), 100L),
        id("authentication-1"),
        revision,
        1_000L,
        SHA_A,
    )

    private fun ids(): AgentWebRuntimeIdGenerator {
        val sequence = AtomicInteger()
        return AgentWebRuntimeIdGenerator { purpose -> id("$purpose-${sequence.incrementAndGet()}") }
    }

    private fun id(value: String): Identifier = Identifier(value)

    private companion object {
        val SHA_A: String = "a".repeat(64)
        val SHA_B: String = "b".repeat(64)
    }
}
