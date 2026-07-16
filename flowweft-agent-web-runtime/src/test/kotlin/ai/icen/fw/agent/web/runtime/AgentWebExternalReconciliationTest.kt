package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentContentOrigin
import ai.icen.fw.agent.api.AgentEvaluationCase
import ai.icen.fw.agent.api.AgentEvaluationExpectedOutcome
import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import ai.icen.fw.agent.api.AgentEvaluationRefusalExpectation
import ai.icen.fw.agent.api.AgentEvaluationSuite
import ai.icen.fw.agent.api.AgentMessage
import ai.icen.fw.agent.api.AgentMessageRole
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentRunRequest
import ai.icen.fw.agent.api.AgentTextContentBlock
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.evaluation.AgentEvaluationEvaluatorReference
import ai.icen.fw.agent.runtime.AgentEvaluationRunRequest
import ai.icen.fw.agent.runtime.AgentRunCommandContext
import ai.icen.fw.agent.web.api.AgentWebErrorCode
import ai.icen.fw.agent.web.api.AgentWebPageQuery
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.core.id.Identifier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentWebExternalReconciliationTest {
    @Test
    fun `three reconcilers only query exact operations and never redispatch unknown outcomes`() {
        val context = context()
        val start = startIntent(context)
        val cancellation = cancellationIntent(context)
        val evaluation = evaluationIntent(context)
        val queryCounts = IntArray(3)
        val transactionCalls = AtomicInteger()
        val external = unknownOperations(start, cancellation, evaluation)
        val reconciler = AgentWebExternalOutcomeReconciler(
            security(),
            object : AgentWebTransactionBoundary {
                override fun <T> inTransaction(work: AgentWebTransactionWork<T>): T {
                    transactionCalls.incrementAndGet()
                    return work.execute()
                }
            },
            unreachableMutations(),
            AgentWebOutboxPort { _, _ -> error("unknown reconciliation must not append outbox") },
            external,
            unreachableConversations(),
            unreachableRuns(),
            unreachableEvaluations(),
            object : AgentWebExternalOutcomeReconciliationPort {
                override fun queryStart(
                    intent: AgentWebRunStartIntent,
                ): AgentWebExternalOutcome<AgentWebRunUseCaseReceipt> {
                    assertEquals(start.bindingDigest, intent.bindingDigest)
                    queryCounts[0]++
                    return AgentWebExternalOutcome.unknown("provider.still-unknown", SHA_B, 190L)
                }

                override fun queryCancellation(
                    intent: AgentWebRunCancelIntent,
                ): AgentWebExternalOutcome<AgentWebRunUseCaseReceipt> {
                    assertEquals(cancellation.bindingDigest, intent.bindingDigest)
                    queryCounts[1]++
                    return AgentWebExternalOutcome.unknown("provider.still-unknown", SHA_B, 190L)
                }

                override fun queryEvaluation(
                    intent: AgentWebEvaluationTriggerIntent,
                ): AgentWebExternalOutcome<ai.icen.fw.agent.runtime.AgentEvaluationRunState> {
                    assertEquals(evaluation.bindingDigest, intent.bindingDigest)
                    queryCounts[2]++
                    return AgentWebExternalOutcome.unknown("provider.still-unknown", SHA_B, 190L)
                }
            },
        )

        val startResult = reconciler.reconcileStart(
            context,
            start.operationId,
            start.conversationId,
            start.expectedConversationVersion,
        )
        val cancelResult = reconciler.reconcileCancellation(
            context,
            cancellation.operationId,
            cancellation.runId,
            cancellation.expectedRunVersion,
        )
        val evaluationResult = reconciler.reconcileEvaluation(context, evaluation.operationId)

        assertEquals(AgentWebErrorCode.OUTCOME_UNKNOWN, startResult.code)
        assertEquals(AgentWebErrorCode.OUTCOME_UNKNOWN, cancelResult.code)
        assertEquals(AgentWebErrorCode.OUTCOME_UNKNOWN, evaluationResult.code)
        assertEquals(listOf(1, 1, 1), queryCounts.toList())
        assertEquals(0, transactionCalls.get())
    }

    private fun startIntent(context: AgentWebTrustedContext): AgentWebRunStartIntent {
        val conversationId = id("conversation-1")
        val message = AgentMessage(
            id("message-1"),
            AgentMessageRole.USER,
            listOf(AgentTextContentBlock(AgentContentOrigin.USER, "hello")),
            100L,
        )
        val request = AgentRunRequest(
            context.runContext,
            AgentCapabilityId("agent.chat"),
            listOf(message),
            AgentBudget(10L, 10L, 1, 0, 1_000L),
            agentWebDerivedIdempotencyToken(
                context,
                "raw-start-key",
                AgentWebAuthorizationAction.RUN_CREATE,
                conversationId,
            ),
            900L,
            ai.icen.fw.agent.api.AgentCancellationToken.NONE,
        )
        val mutation = AgentWebMutationScope.bind(
            context,
            "raw-start-key",
            AgentWebAuthorizationAction.RUN_CREATE,
            conversationId,
            SHA_A,
        )
        return AgentWebRunStartIntent(
            id("start-operation"),
            mutation,
            conversationId,
            0L,
            request,
            AgentWebExternalOperationStatus.PREPARED,
            null,
            100L,
            100L,
        ).failed("provider.outcome-unknown", true, 150L)
    }

    private fun cancellationIntent(context: AgentWebTrustedContext): AgentWebRunCancelIntent {
        val runId = id("run-1")
        val mutation = AgentWebMutationScope.bind(
            context,
            "raw-cancel-key",
            AgentWebAuthorizationAction.RUN_CANCEL,
            runId,
            SHA_A,
        )
        return AgentWebRunCancelIntent(
            id("cancel-operation"),
            mutation,
            runId,
            3L,
            AgentRunCommandContext(
                context.tenantId,
                context.principalId,
                context.principalType,
                context.requestId,
                100L,
            ),
            AgentCancellation("user.cancelled", 100L),
            AgentWebExternalOperationStatus.PREPARED,
            null,
            100L,
            100L,
        ).failed("provider.outcome-unknown", true, 150L)
    }

    private fun evaluationIntent(context: AgentWebTrustedContext): AgentWebEvaluationTriggerIntent {
        val capability = AgentCapabilityId("agent.answer")
        val case = AgentEvaluationCase(
            id("case-1"),
            id("fixture-1"),
            capability,
            SHA_A,
            AgentEvaluationExpectedOutcome(refusal = AgentEvaluationRefusalExpectation.MUST_REFUSE),
            setOf("security"),
        )
        val suite = AgentEvaluationSuite(id("suite-1"), "Security suite", "1", listOf(case), 100L)
        val provider = AgentEvaluationProviderSnapshot(
            ProviderId("provider.local"),
            "1",
            setOf(capability),
            SHA_B,
            90L,
            1_000L,
        )
        val request = AgentEvaluationRunRequest(
            id("evaluation-request"),
            context.tenantId,
            context.principalId,
            context.principalType,
            context.authorizationRevision,
            suite,
            provider,
            agentWebDerivedIdempotencyToken(
                context,
                "raw-evaluation-key",
                AgentWebAuthorizationAction.EVALUATION_TRIGGER,
                context.tenantId,
            ),
            100L,
            900L,
            2,
        )
        val mutation = AgentWebMutationScope.bind(
            context,
            "raw-evaluation-key",
            AgentWebAuthorizationAction.EVALUATION_TRIGGER,
            context.tenantId,
            SHA_A,
        )
        return AgentWebEvaluationTriggerIntent(
            id("evaluation-operation"),
            mutation,
            request,
            AgentEvaluationEvaluatorReference(ProviderId("evaluator.local"), "1", SHA_A),
            AgentWebExternalOperationStatus.PREPARED,
            null,
            100L,
            100L,
        ).failed("provider.outcome-unknown", true, 150L)
    }

    private fun unknownOperations(
        start: AgentWebRunStartIntent,
        cancellation: AgentWebRunCancelIntent,
        evaluation: AgentWebEvaluationTriggerIntent,
    ): AgentWebExternalOperationRepository = object : AgentWebExternalOperationRepository {
        override fun createStart(scope: AgentWebAuthorizedPersistenceScope, intent: AgentWebRunStartIntent): Boolean =
            error("reconciliation must not create")
        override fun updateStart(
            scope: AgentWebAuthorizedPersistenceScope,
            expectedStatus: AgentWebExternalOperationStatus,
            intent: AgentWebRunStartIntent,
        ): Boolean = error("unknown query must not update")
        override fun loadStart(
            scope: AgentWebAuthorizedPersistenceScope,
            operationId: Identifier,
        ): AgentWebRunStartIntent? = start.takeIf { it.operationId == operationId }

        override fun createCancellation(
            scope: AgentWebAuthorizedPersistenceScope,
            intent: AgentWebRunCancelIntent,
        ): Boolean = error("reconciliation must not create")
        override fun updateCancellation(
            scope: AgentWebAuthorizedPersistenceScope,
            expectedStatus: AgentWebExternalOperationStatus,
            intent: AgentWebRunCancelIntent,
        ): Boolean = error("unknown query must not update")
        override fun loadCancellation(
            scope: AgentWebAuthorizedPersistenceScope,
            operationId: Identifier,
        ): AgentWebRunCancelIntent? = cancellation.takeIf { it.operationId == operationId }

        override fun createEvaluation(
            scope: AgentWebAuthorizedPersistenceScope,
            intent: AgentWebEvaluationTriggerIntent,
        ): Boolean = error("reconciliation must not create")
        override fun updateEvaluation(
            scope: AgentWebAuthorizedPersistenceScope,
            expectedStatus: AgentWebExternalOperationStatus,
            intent: AgentWebEvaluationTriggerIntent,
        ): Boolean = error("unknown query must not update")
        override fun loadEvaluation(
            scope: AgentWebAuthorizedPersistenceScope,
            operationId: Identifier,
        ): AgentWebEvaluationTriggerIntent? = evaluation.takeIf { it.operationId == operationId }
    }

    private fun unreachableMutations(): AgentWebMutationJournal = object : AgentWebMutationJournal {
        override fun reserve(
            scope: AgentWebAuthorizedPersistenceScope,
            mutation: AgentWebMutationScope,
            operationId: Identifier,
            requestedAt: Long,
        ): AgentWebMutationReserveResult = error("unknown query must not reserve")
        override fun compareAndSet(
            scope: AgentWebAuthorizedPersistenceScope,
            transition: AgentWebMutationTransition,
        ): AgentWebMutationRecord = error("unknown query must not close mutation")
    }

    private fun unreachableConversations(): AgentWebConversationRepository = object : AgentWebConversationRepository {
        override fun create(
            scope: AgentWebAuthorizedPersistenceScope,
            record: AgentWebConversationRecord,
        ): AgentWebConversationWriteResult = error("unused")
        override fun find(
            scope: AgentWebAuthorizedPersistenceScope,
            conversationId: Identifier,
        ): AgentWebConversationRecord? = error("unknown query must not project")
        override fun list(
            scope: AgentWebAuthorizedPersistenceScope,
            query: AgentWebPageQuery,
        ): AgentWebStoredPage<AgentWebConversationRecord> = error("unused")
        override fun compareAndSet(
            scope: AgentWebAuthorizedPersistenceScope,
            expectedStateVersion: Long,
            next: AgentWebConversationRecord,
        ): AgentWebConversationWriteResult = error("unused")
    }

    private fun unreachableRuns(): AgentWebRunProjectionRepository = object : AgentWebRunProjectionRepository {
        override fun create(
            scope: AgentWebAuthorizedPersistenceScope,
            record: AgentWebRunRecord,
            initialMessage: AgentWebVisibleMessageRecord,
            initialEvents: Collection<AgentWebRunEventRecord>,
        ): AgentWebRunWriteResult = error("unknown query must not project")
        override fun find(scope: AgentWebAuthorizedPersistenceScope, runId: Identifier): AgentWebRunRecord? =
            error("unknown query must not project")
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
        ): AgentWebRunWriteResult = error("unknown query must not project")
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

    private fun unreachableEvaluations(): AgentWebEvaluationRepository = object : AgentWebEvaluationRepository {
        override fun create(
            scope: AgentWebAuthorizedPersistenceScope,
            record: AgentWebEvaluationRecord,
        ): AgentWebEvaluationWriteResult = error("unknown query must not project")
        override fun find(
            scope: AgentWebAuthorizedPersistenceScope,
            evaluationId: Identifier,
        ): AgentWebEvaluationRecord? = error("unused")
        override fun list(
            scope: AgentWebAuthorizedPersistenceScope,
            query: AgentWebPageQuery,
        ): AgentWebStoredPage<AgentWebEvaluationRecord> = error("unused")
    }

    private fun security(): AgentWebApplicationSecurity = AgentWebApplicationSecurity(
        object : AgentWebAuthoritativeAuthorizationPort {
            private val provider = ProviderId("host-authz")
            override fun providerId(): ProviderId = provider
            override fun authorize(request: AgentWebAuthorizationRequest): AgentWebAuthorizationDecision =
                AgentWebAuthorizationDecision.allow(
                    id("authorization-${request.action.value}-${request.target.resourceId.value}"),
                    provider,
                    request,
                    request.context.authorizationRevision,
                    SHA_A,
                    request.requestedAt,
                    request.expiresAt,
                )
        },
        AgentWebRuntimeClock { 200L },
        AgentWebRuntimeIdGenerator { purpose -> id("$purpose-id") },
    )

    private fun context(): AgentWebTrustedContext = AgentWebTrustedContext.authenticated(
        AgentRunContext(id("tenant-1"), id("principal-1"), "USER", id("request-1"), 100L),
        id("authentication-1"),
        "revision-1",
        1_000L,
        SHA_A,
    )

    private fun id(value: String): Identifier = Identifier(value)

    private companion object {
        val SHA_A: String = "a".repeat(64)
        val SHA_B: String = "b".repeat(64)
    }
}
