package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentRunRequest
import ai.icen.fw.agent.api.AgentRunSnapshot
import ai.icen.fw.agent.api.AgentRunStatus
import ai.icen.fw.agent.api.AgentRunStatusChangedEvent
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.runtime.AgentRunCommandContext
import ai.icen.fw.agent.web.api.AgentWebConversationCreateCommand
import ai.icen.fw.agent.web.api.AgentWebCursor
import ai.icen.fw.agent.web.api.AgentWebErrorCode
import ai.icen.fw.agent.web.api.AgentWebPageQuery
import ai.icen.fw.agent.web.api.AgentWebRunCreateCommand
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.agent.web.api.AgentWebUserMessageCommand
import ai.icen.fw.agent.web.api.AgentWebWritePreconditions
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.core.id.Identifier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentWebConversationOrchestrationTest {
    @Test
    fun `run start commits durable intent before invoking use case outside transaction`() {
        val inTransaction = ThreadLocal.withInitial { false }
        val events = ArrayList<String>()
        val context = context()
        val budget = AgentBudget(100, 100, 2, 1, 1_000L)
        val capability = AgentCapabilityId("agent.chat")
        var conversation = AgentWebConversationRecord(
            context.tenantId,
            context.principalId,
            context.principalType,
            id("conversation-1"),
            "Conversation",
            capability,
            budget,
            null,
            0L,
            100L,
            100L,
        )
        var storedRun: AgentWebRunRecord? = null
        var mutationRecord: AgentWebMutationRecord? = null
        var startIntent: AgentWebRunStartIntent? = null
        val rawIdempotencyKey = "start-key-1"

        val transactions = object : AgentWebTransactionBoundary {
            override fun <T> inTransaction(work: AgentWebTransactionWork<T>): T {
                assertFalse(inTransaction.get())
                inTransaction.set(true)
                return try {
                    work.execute()
                } finally {
                    inTransaction.set(false)
                }
            }
        }
        val mutations = object : AgentWebMutationJournal {
            override fun reserve(
                scope: AgentWebAuthorizedPersistenceScope,
                mutation: AgentWebMutationScope,
                operationId: Identifier,
                requestedAt: Long,
            ): AgentWebMutationReserveResult {
                assertTrue(inTransaction.get())
                val record = AgentWebMutationRecord(
                    mutation, operationId, AgentWebMutationStatus.RESERVED, null, null, null,
                    requestedAt, requestedAt,
                )
                mutationRecord = record
                return AgentWebMutationReserveResult(AgentWebMutationReserveStatus.CREATED, record)
            }

            override fun compareAndSet(
                scope: AgentWebAuthorizedPersistenceScope,
                transition: AgentWebMutationTransition,
            ): AgentWebMutationRecord {
                assertTrue(inTransaction.get())
                val record = AgentWebMutationRecord(
                    transition.scope,
                    transition.operationId,
                    transition.nextStatus,
                    transition.resultResourceId,
                    transition.resultVersion,
                    transition.diagnosticCode,
                    requireNotNull(mutationRecord).createdAt,
                    transition.transitionedAt,
                )
                mutationRecord = record
                return record
            }
        }
        val conversations = object : AgentWebConversationRepository {
            override fun create(
                scope: AgentWebAuthorizedPersistenceScope,
                record: AgentWebConversationRecord,
            ): AgentWebConversationWriteResult = error("unused")

            override fun find(
                scope: AgentWebAuthorizedPersistenceScope,
                conversationId: Identifier,
            ): AgentWebConversationRecord? = conversation.takeIf { it.conversationId == conversationId }

            override fun list(
                scope: AgentWebAuthorizedPersistenceScope,
                query: AgentWebPageQuery,
            ): AgentWebStoredPage<AgentWebConversationRecord> = error("unused")

            override fun compareAndSet(
                scope: AgentWebAuthorizedPersistenceScope,
                expectedStateVersion: Long,
                next: AgentWebConversationRecord,
            ): AgentWebConversationWriteResult {
                assertTrue(inTransaction.get())
                assertEquals(conversation.stateVersion, expectedStateVersion)
                conversation = next
                return AgentWebConversationWriteResult(AgentWebRepositoryWriteStatus.APPLIED, next)
            }
        }
        val runs = object : AgentWebRunProjectionRepository {
            override fun create(
                scope: AgentWebAuthorizedPersistenceScope,
                record: AgentWebRunRecord,
                initialMessage: AgentWebVisibleMessageRecord,
                initialEvents: Collection<AgentWebRunEventRecord>,
            ): AgentWebRunWriteResult {
                assertTrue(inTransaction.get())
                assertTrue(initialEvents.isNotEmpty())
                storedRun = record
                return AgentWebRunWriteResult(AgentWebRepositoryWriteStatus.APPLIED, record)
            }

            override fun find(scope: AgentWebAuthorizedPersistenceScope, runId: Identifier): AgentWebRunRecord? =
                storedRun?.takeIf { it.snapshot.runId == runId }

            override fun listByConversation(
                scope: AgentWebAuthorizedPersistenceScope,
                conversationId: Identifier,
                query: AgentWebPageQuery,
            ): AgentWebStoredPage<AgentWebRunRecord> = error("unused")

            override fun compareAndSet(
                scope: AgentWebAuthorizedPersistenceScope,
                expectedStateVersion: Long,
                next: AgentWebRunRecord,
                events: Collection<AgentWebRunEventRecord>,
            ): AgentWebRunWriteResult = error("unused")

            override fun messages(
                scope: AgentWebAuthorizedPersistenceScope,
                runId: Identifier,
                query: AgentWebPageQuery,
            ): AgentWebStoredDurablePage<AgentWebVisibleMessageRecord> = error("unused")

            override fun events(
                scope: AgentWebAuthorizedPersistenceScope,
                runId: Identifier,
                query: AgentWebPageQuery,
            ): AgentWebStoredDurablePage<AgentWebRunEventRecord> = error("unused")

            override fun citations(
                scope: AgentWebAuthorizedPersistenceScope,
                runId: Identifier,
                query: AgentWebPageQuery,
            ): AgentWebStoredPage<AgentWebCitationRecord> = error("unused")
        }
        val external = object : AgentWebExternalOperationRepository {
            override fun createStart(
                scope: AgentWebAuthorizedPersistenceScope,
                intent: AgentWebRunStartIntent,
            ): Boolean {
                assertTrue(inTransaction.get())
                assertFalse(intent.request.idempotencyKey == rawIdempotencyKey)
                assertEquals(64, intent.request.idempotencyKey.length)
                startIntent = intent
                events += "intent-prepared"
                return true
            }

            override fun updateStart(
                scope: AgentWebAuthorizedPersistenceScope,
                expectedStatus: AgentWebExternalOperationStatus,
                intent: AgentWebRunStartIntent,
            ): Boolean {
                assertTrue(inTransaction.get())
                assertEquals(startIntent?.status, expectedStatus)
                startIntent = intent
                events += "intent-completed"
                return true
            }

            override fun loadStart(
                scope: AgentWebAuthorizedPersistenceScope,
                operationId: Identifier,
            ): AgentWebRunStartIntent? = startIntent

            override fun createCancellation(
                scope: AgentWebAuthorizedPersistenceScope,
                intent: AgentWebRunCancelIntent,
            ): Boolean = error("unused")

            override fun updateCancellation(
                scope: AgentWebAuthorizedPersistenceScope,
                expectedStatus: AgentWebExternalOperationStatus,
                intent: AgentWebRunCancelIntent,
            ): Boolean = error("unused")

            override fun loadCancellation(
                scope: AgentWebAuthorizedPersistenceScope,
                operationId: Identifier,
            ): AgentWebRunCancelIntent? = error("unused")

            override fun createEvaluation(
                scope: AgentWebAuthorizedPersistenceScope,
                intent: AgentWebEvaluationTriggerIntent,
            ): Boolean = error("unused")

            override fun updateEvaluation(
                scope: AgentWebAuthorizedPersistenceScope,
                expectedStatus: AgentWebExternalOperationStatus,
                intent: AgentWebEvaluationTriggerIntent,
            ): Boolean = error("unused")

            override fun loadEvaluation(
                scope: AgentWebAuthorizedPersistenceScope,
                operationId: Identifier,
            ): AgentWebEvaluationTriggerIntent? = error("unused")
        }
        val useCases = object : AgentWebRunUseCasePort {
            override fun start(request: AgentRunRequest): AgentWebUseCaseResult<AgentWebRunUseCaseReceipt> {
                assertFalse(inTransaction.get())
                assertEquals(AgentWebExternalOperationStatus.PREPARED, startIntent?.status)
                assertFalse(request.idempotencyKey == rawIdempotencyKey)
                events += "use-case"
                val runId = id("run-1")
                val snapshot = AgentRunSnapshot(
                    runId,
                    context.tenantId,
                    request.capabilityId,
                    AgentRunStatus.QUEUED,
                    request.messages,
                    request.budget,
                    AgentUsage(),
                    0L,
                    100L,
                    100L,
                )
                val event = AgentRunStatusChangedEvent(
                    runId, context.tenantId, 1L, 100L, null, AgentRunStatus.QUEUED, "run.admitted",
                )
                return AgentWebUseCaseResult.success(
                    AgentWebRunUseCaseReceipt(snapshot, listOf(AgentWebRunUseCaseEvent(event, 0L))),
                )
            }

            override fun cancel(
                context: AgentRunCommandContext,
                runId: Identifier,
                expectedStateVersion: Long,
                cancellation: ai.icen.fw.agent.api.AgentCancellation,
            ): AgentWebUseCaseResult<AgentWebRunUseCaseReceipt> = error("unused")
        }
        val outbox = AgentWebOutboxPort { _, event ->
            assertTrue(inTransaction.get())
            events += event.eventType
        }
        val security = AgentWebApplicationSecurity(
            allowAuthorization(),
            AgentWebRuntimeClock { 100L },
            ids(),
        )
        val application = DefaultAgentConversationWebApplication(
            security, transactions, mutations, outbox, conversations, runs, external, useCases,
        )

        val result = application.startRun(
            context,
            conversation.conversationId,
            AgentWebWritePreconditions.parse(rawIdempotencyKey, "\"fw-agent-0\""),
            AgentWebRunCreateCommand(
                capability,
                AgentWebUserMessageCommand(id("message-1"), "hello"),
                budget,
                500L,
            ),
        )

        assertEquals(AgentWebErrorCode.OK, result.code)
        assertEquals(id("run-1"), result.value?.runId)
        assertEquals(AgentWebExternalOperationStatus.SUCCEEDED, startIntent?.status)
        assertEquals(AgentWebMutationStatus.SUCCEEDED, mutationRecord?.status)
        assertFalse(requireNotNull(startIntent).request.idempotencyKey.contains(rawIdempotencyKey))
        assertNotNull(storedRun)
        assertTrue(events.indexOf("intent-prepared") < events.indexOf("use-case"))
        assertTrue(events.contains("agent.run.start.requested"))
        assertTrue(events.contains("agent.run.started"))
    }

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
                    SHA,
                    request.requestedAt,
                    request.expiresAt,
                )
        }

    private fun context(): AgentWebTrustedContext = AgentWebTrustedContext.authenticated(
        AgentRunContext(id("tenant-1"), id("principal-1"), "USER", id("request-1"), 100L),
        id("authentication-1"), "revision-1", 1_000L, SHA,
    )

    private fun ids(): AgentWebRuntimeIdGenerator {
        val sequence = AtomicInteger()
        return AgentWebRuntimeIdGenerator { purpose -> id("$purpose-${sequence.incrementAndGet()}") }
    }

    private fun id(value: String): Identifier = Identifier(value)

    private companion object {
        val SHA: String = "b".repeat(64)
    }
}
