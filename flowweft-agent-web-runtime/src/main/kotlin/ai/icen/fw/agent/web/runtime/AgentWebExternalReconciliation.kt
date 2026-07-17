package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.api.AgentMessageRole
import ai.icen.fw.agent.web.api.AgentWebApplicationResult
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.core.id.Identifier

/** A reconciliation provider may only query the exact original operation; it cannot dispatch it. */
interface AgentWebExternalOutcomeReconciliationPort {
    fun queryStart(intent: AgentWebRunStartIntent): AgentWebExternalOutcome<AgentWebRunUseCaseReceipt>

    fun queryCancellation(intent: AgentWebRunCancelIntent): AgentWebExternalOutcome<AgentWebRunUseCaseReceipt>

    fun queryEvaluation(
        intent: AgentWebEvaluationTriggerIntent,
    ): AgentWebExternalOutcome<ai.icen.fw.agent.runtime.AgentEvaluationRunState>
}

enum class AgentWebExternalOutcomeStatus {
    SUCCEEDED,
    REJECTED,
    CONFIRMED_NOT_APPLIED,
    OUTCOME_UNKNOWN,
}

/** Payload-free query evidence plus a canonical value only for an authoritative success. */
class AgentWebExternalOutcome<T> private constructor(
    val status: AgentWebExternalOutcomeStatus,
    val value: T?,
    diagnosticCode: String?,
    evidenceDigest: String,
    val observedAt: Long,
) {
    val diagnosticCode: String? = diagnosticCode?.let { webRuntimeCode(it, "Agent Web reconciliation diagnostic") }
    val evidenceDigest: String = webRuntimeDigest(evidenceDigest, "Agent Web reconciliation evidence")

    init {
        require(observedAt >= 0L) { "Agent Web reconciliation observation time is invalid." }
        require((status == AgentWebExternalOutcomeStatus.SUCCEEDED) == (value != null)) {
            "Agent Web reconciliation value does not match its status."
        }
        require((status == AgentWebExternalOutcomeStatus.SUCCEEDED) == (this.diagnosticCode == null)) {
            "Agent Web reconciliation diagnostic does not match its status."
        }
    }

    companion object {
        @JvmStatic
        fun <T> succeeded(value: T, evidenceDigest: String, observedAt: Long): AgentWebExternalOutcome<T> =
            AgentWebExternalOutcome(AgentWebExternalOutcomeStatus.SUCCEEDED, value, null, evidenceDigest, observedAt)

        @JvmStatic
        fun <T> rejected(code: String, evidenceDigest: String, observedAt: Long): AgentWebExternalOutcome<T> =
            AgentWebExternalOutcome(AgentWebExternalOutcomeStatus.REJECTED, null, code, evidenceDigest, observedAt)

        @JvmStatic
        fun <T> notApplied(code: String, evidenceDigest: String, observedAt: Long): AgentWebExternalOutcome<T> =
            AgentWebExternalOutcome(
                AgentWebExternalOutcomeStatus.CONFIRMED_NOT_APPLIED,
                null,
                code,
                evidenceDigest,
                observedAt,
            )

        @JvmStatic
        fun <T> unknown(code: String, evidenceDigest: String, observedAt: Long): AgentWebExternalOutcome<T> =
            AgentWebExternalOutcome(AgentWebExternalOutcomeStatus.OUTCOME_UNKNOWN, null, code, evidenceDigest, observedAt)
    }
}

enum class AgentWebReconciliationKind {
    RUN_START,
    RUN_CANCELLATION,
    EVALUATION_TRIGGER,
}

class AgentWebReconciliationReceipt(
    val operationId: Identifier,
    val kind: AgentWebReconciliationKind,
    val terminalStatus: AgentWebExternalOperationStatus,
    val resourceId: Identifier?,
    val resourceVersion: Long?,
    evidenceDigest: String,
    val reconciledAt: Long,
) {
    val evidenceDigest: String = webRuntimeDigest(evidenceDigest, "Agent Web reconciliation receipt evidence")

    init {
        require(terminalStatus == AgentWebExternalOperationStatus.SUCCEEDED ||
            terminalStatus == AgentWebExternalOperationStatus.REJECTED
        ) { "Agent Web reconciliation receipt is not terminal." }
        require((terminalStatus == AgentWebExternalOperationStatus.SUCCEEDED) == (resourceId != null)) {
            "Agent Web reconciliation resource does not match its status."
        }
        require((terminalStatus == AgentWebExternalOperationStatus.SUCCEEDED) == (resourceVersion != null)) {
            "Agent Web reconciliation resource version does not match its status."
        }
        require(resourceVersion == null || resourceVersion >= 0L) {
            "Agent Web reconciliation resource version is invalid."
        }
        require(reconciledAt >= 0L) { "Agent Web reconciliation time is invalid." }
    }
}

/**
 * One-at-a-time recovery of an already dispatched ambiguous operation. Querying is always outside
 * a database transaction. This class has no start/cancel/evaluation mutation use-case dependency,
 * so a reconciliation pass cannot accidentally redispatch the original command.
 */
class AgentWebExternalOutcomeReconciler(
    private val security: AgentWebApplicationSecurity,
    private val transactions: AgentWebTransactionBoundary,
    private val mutations: AgentWebMutationJournal,
    private val outbox: AgentWebOutboxPort,
    private val externalOperations: AgentWebExternalOperationRepository,
    private val conversations: AgentWebConversationRepository,
    private val runs: AgentWebRunProjectionRepository,
    private val evaluations: AgentWebEvaluationRepository,
    private val reconciliation: AgentWebExternalOutcomeReconciliationPort,
) {
    fun reconcileStart(
        context: AgentWebTrustedContext,
        operationId: Identifier,
        conversationId: Identifier,
        expectedConversationVersion: Long,
    ): AgentWebApplicationResult<AgentWebReconciliationReceipt> = agentWebApplicationCall {
        if (expectedConversationVersion < 0L) throw AgentWebInvalidRequestException()
        val target = AgentWebAuthorizationTarget(
            "agent.conversation",
            conversationId,
            expectedConversationVersion.toString(),
            RECONCILIATION_PURPOSE,
        )
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.RUN_CREATE,
            target,
        )
        val intent = externalOperations.loadStart(authorized.scope, operationId)
            ?: throw AgentWebHiddenException()
        if (intent.operationId != operationId || intent.conversationId != conversationId ||
            intent.expectedConversationVersion != expectedConversationVersion ||
            intent.request.context.tenantId != context.tenantId ||
            intent.mutation.aggregateId != conversationId ||
            intent.mutation.action != AgentWebAuthorizationAction.RUN_CREATE
        ) throw AgentWebHiddenException()
        if (intent.status != AgentWebExternalOperationStatus.OUTCOME_UNKNOWN) {
            throw AgentWebOutcomeUnknownException()
        }
        val outcome = query { reconciliation.queryStart(intent) }
        validateObservation(intent.updatedAt, outcome)
        when (outcome.status) {
            AgentWebExternalOutcomeStatus.OUTCOME_UNKNOWN -> throw AgentWebOutcomeUnknownException()
            AgentWebExternalOutcomeStatus.REJECTED,
            AgentWebExternalOutcomeStatus.CONFIRMED_NOT_APPLIED,
            AgentWebExternalOutcomeStatus.SUCCEEDED -> {
                val completion = security.authorize(context, AgentWebAuthorizationAction.RUN_CREATE, target)
                when (outcome.status) {
                    AgentWebExternalOutcomeStatus.REJECTED,
                    AgentWebExternalOutcomeStatus.CONFIRMED_NOT_APPLIED -> closeRejectedStart(
                        completion.scope, intent, outcome, completion.authorizedAt,
                    )
                    AgentWebExternalOutcomeStatus.SUCCEEDED -> closeSuccessfulStart(
                        context, completion.scope, intent, requireNotNull(outcome.value),
                        outcome.evidenceDigest, completion.authorizedAt,
                    )
                    AgentWebExternalOutcomeStatus.OUTCOME_UNKNOWN -> throw AgentWebOutcomeUnknownException()
                }
            }
        }
    }

    fun reconcileCancellation(
        context: AgentWebTrustedContext,
        operationId: Identifier,
        runId: Identifier,
        expectedRunVersion: Long,
    ): AgentWebApplicationResult<AgentWebReconciliationReceipt> = agentWebApplicationCall {
        if (expectedRunVersion < 0L) throw AgentWebInvalidRequestException()
        val target = AgentWebAuthorizationTarget(
            "agent.run",
            runId,
            expectedRunVersion.toString(),
            RECONCILIATION_PURPOSE,
        )
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.RUN_CANCEL,
            target,
        )
        val intent = externalOperations.loadCancellation(authorized.scope, operationId)
            ?: throw AgentWebHiddenException()
        if (intent.operationId != operationId || intent.runId != runId ||
            intent.expectedRunVersion != expectedRunVersion || intent.context.tenantId != context.tenantId ||
            intent.mutation.aggregateId != runId || intent.mutation.action != AgentWebAuthorizationAction.RUN_CANCEL
        ) throw AgentWebHiddenException()
        if (intent.status != AgentWebExternalOperationStatus.OUTCOME_UNKNOWN) {
            throw AgentWebOutcomeUnknownException()
        }
        val outcome = query { reconciliation.queryCancellation(intent) }
        validateObservation(intent.updatedAt, outcome)
        when (outcome.status) {
            AgentWebExternalOutcomeStatus.OUTCOME_UNKNOWN -> throw AgentWebOutcomeUnknownException()
            AgentWebExternalOutcomeStatus.REJECTED,
            AgentWebExternalOutcomeStatus.CONFIRMED_NOT_APPLIED,
            AgentWebExternalOutcomeStatus.SUCCEEDED -> {
                val completion = security.authorize(context, AgentWebAuthorizationAction.RUN_CANCEL, target)
                when (outcome.status) {
                    AgentWebExternalOutcomeStatus.REJECTED,
                    AgentWebExternalOutcomeStatus.CONFIRMED_NOT_APPLIED -> closeRejectedCancellation(
                        completion.scope, intent, outcome, completion.authorizedAt,
                    )
                    AgentWebExternalOutcomeStatus.SUCCEEDED -> closeSuccessfulCancellation(
                        context, completion.scope, intent, requireNotNull(outcome.value),
                        outcome.evidenceDigest, completion.authorizedAt,
                    )
                    AgentWebExternalOutcomeStatus.OUTCOME_UNKNOWN -> throw AgentWebOutcomeUnknownException()
                }
            }
        }
    }

    fun reconcileEvaluation(
        context: AgentWebTrustedContext,
        operationId: Identifier,
    ): AgentWebApplicationResult<AgentWebReconciliationReceipt> = agentWebApplicationCall {
        val target = AgentWebAuthorizationTarget(
            "agent.evaluation.collection",
            context.tenantId,
            "current",
            RECONCILIATION_PURPOSE,
        )
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.EVALUATION_TRIGGER,
            target,
        )
        val intent = externalOperations.loadEvaluation(authorized.scope, operationId)
            ?: throw AgentWebHiddenException()
        if (intent.operationId != operationId || intent.request.tenantId != context.tenantId ||
            intent.mutation.aggregateId != context.tenantId ||
            intent.mutation.action != AgentWebAuthorizationAction.EVALUATION_TRIGGER
        ) throw AgentWebHiddenException()
        if (intent.status != AgentWebExternalOperationStatus.OUTCOME_UNKNOWN) {
            throw AgentWebOutcomeUnknownException()
        }
        val outcome = query { reconciliation.queryEvaluation(intent) }
        validateObservation(intent.updatedAt, outcome)
        when (outcome.status) {
            AgentWebExternalOutcomeStatus.OUTCOME_UNKNOWN -> throw AgentWebOutcomeUnknownException()
            AgentWebExternalOutcomeStatus.REJECTED,
            AgentWebExternalOutcomeStatus.CONFIRMED_NOT_APPLIED,
            AgentWebExternalOutcomeStatus.SUCCEEDED -> {
                val completion = security.authorize(context, AgentWebAuthorizationAction.EVALUATION_TRIGGER, target)
                when (outcome.status) {
                    AgentWebExternalOutcomeStatus.REJECTED,
                    AgentWebExternalOutcomeStatus.CONFIRMED_NOT_APPLIED -> closeRejectedEvaluation(
                        completion.scope, intent, outcome, completion.authorizedAt,
                    )
                    AgentWebExternalOutcomeStatus.SUCCEEDED -> closeSuccessfulEvaluation(
                        context, completion.scope, intent, requireNotNull(outcome.value),
                        outcome.evidenceDigest, completion.authorizedAt,
                    )
                    AgentWebExternalOutcomeStatus.OUTCOME_UNKNOWN -> throw AgentWebOutcomeUnknownException()
                }
            }
        }
    }

    private fun closeSuccessfulStart(
        context: AgentWebTrustedContext,
        scope: AgentWebAuthorizedPersistenceScope,
        intent: AgentWebRunStartIntent,
        receipt: AgentWebRunUseCaseReceipt,
        evidenceDigest: String,
        reconciledAt: Long,
    ): AgentWebApplicationResult<AgentWebReconciliationReceipt> {
        if (receipt.snapshot.tenantId != context.tenantId ||
            receipt.snapshot.capabilityId != intent.request.capabilityId ||
            intent.request.messages.size != 1 || intent.request.messages.single().role != AgentMessageRole.USER ||
            receipt.snapshot.messages.none { message ->
                message.bindingDigest == intent.request.messages.single().bindingDigest
            }
        ) throw AgentWebOutcomeUnknownException()
        val runRecord = try {
            AgentWebRunRecord(
                context.tenantId,
                intent.conversationId,
                receipt.snapshot,
                intent.request.deadlineAt,
            )
        } catch (_: RuntimeException) {
            throw AgentWebOutcomeUnknownException()
        }
        val message = AgentWebVisibleMessageRecord(
            receipt.snapshot.runId,
            1L,
            intent.request.messages.single(),
            emptyList(),
        )
        val events = receipt.events.map { AgentWebRunEventRecord(it.event, it.stateVersion) }
        try {
            transactions.inTransaction(AgentWebTransactionWork {
                val conversation = conversations.find(scope, intent.conversationId)
                    ?: throw AgentWebOutcomeUnknownException()
                if (conversation.tenantId != context.tenantId ||
                    conversation.stateVersion != intent.expectedConversationVersion
                ) throw AgentWebOutcomeUnknownException()
                runs.create(scope, runRecord, message, events).requireExact(runRecord)
                val nextConversation = conversation.withRun(receipt.snapshot.status, reconciledAt)
                conversations.compareAndSet(
                    scope,
                    intent.expectedConversationVersion,
                    nextConversation,
                ).requireExact(nextConversation)
                externalOperations.updateStartBound(
                    scope,
                    AgentWebExternalOperationStatus.OUTCOME_UNKNOWN,
                    intent.completed(reconciledAt),
                )
                mutations.transitionBound(
                    scope,
                    AgentWebMutationTransition(
                        intent.mutation,
                        intent.operationId,
                        AgentWebMutationStatus.OUTCOME_UNKNOWN,
                        AgentWebMutationStatus.SUCCEEDED,
                        receipt.snapshot.runId,
                        receipt.snapshot.stateVersion,
                        null,
                        reconciledAt,
                    ),
                )
                appendOutbox(scope, intent.operationId, receipt.snapshot.runId,
                    "agent.run.start.reconciled", evidenceDigest, reconciledAt)
            })
        } catch (_: RuntimeException) {
            throw AgentWebOutcomeUnknownException()
        }
        return successReceipt(
            intent.operationId,
            AgentWebReconciliationKind.RUN_START,
            receipt.snapshot.runId,
            receipt.snapshot.stateVersion,
            evidenceDigest,
            reconciledAt,
        )
    }

    private fun closeSuccessfulCancellation(
        context: AgentWebTrustedContext,
        scope: AgentWebAuthorizedPersistenceScope,
        intent: AgentWebRunCancelIntent,
        receipt: AgentWebRunUseCaseReceipt,
        evidenceDigest: String,
        reconciledAt: Long,
    ): AgentWebApplicationResult<AgentWebReconciliationReceipt> {
        if (receipt.snapshot.runId != intent.runId || receipt.snapshot.tenantId != context.tenantId ||
            receipt.snapshot.status != ai.icen.fw.agent.api.AgentRunStatus.CANCELLED ||
            receipt.snapshot.stateVersion <= intent.expectedRunVersion
        ) throw AgentWebOutcomeUnknownException()
        try {
            transactions.inTransaction(AgentWebTransactionWork {
                val current = runs.find(scope, intent.runId) ?: throw AgentWebOutcomeUnknownException()
                if (current.tenantId != context.tenantId) throw AgentWebOutcomeUnknownException()
                val next = AgentWebRunRecord(context.tenantId, current.conversationId, receipt.snapshot, current.deadlineAt)
                runs.compareAndSet(
                    scope,
                    intent.expectedRunVersion,
                    next,
                    receipt.events.map { AgentWebRunEventRecord(it.event, it.stateVersion) },
                ).requireExact(next)
                externalOperations.updateCancellationBound(
                    scope,
                    AgentWebExternalOperationStatus.OUTCOME_UNKNOWN,
                    intent.completed(reconciledAt),
                )
                mutations.transitionBound(
                    scope,
                    AgentWebMutationTransition(
                        intent.mutation,
                        intent.operationId,
                        AgentWebMutationStatus.OUTCOME_UNKNOWN,
                        AgentWebMutationStatus.SUCCEEDED,
                        intent.runId,
                        receipt.snapshot.stateVersion,
                        null,
                        reconciledAt,
                    ),
                )
                appendOutbox(scope, intent.operationId, intent.runId,
                    "agent.run.cancel.reconciled", evidenceDigest, reconciledAt)
            })
        } catch (_: RuntimeException) {
            throw AgentWebOutcomeUnknownException()
        }
        return successReceipt(
            intent.operationId,
            AgentWebReconciliationKind.RUN_CANCELLATION,
            intent.runId,
            receipt.snapshot.stateVersion,
            evidenceDigest,
            reconciledAt,
        )
    }

    private fun closeSuccessfulEvaluation(
        context: AgentWebTrustedContext,
        scope: AgentWebAuthorizedPersistenceScope,
        intent: AgentWebEvaluationTriggerIntent,
        state: ai.icen.fw.agent.runtime.AgentEvaluationRunState,
        evidenceDigest: String,
        reconciledAt: Long,
    ): AgentWebApplicationResult<AgentWebReconciliationReceipt> {
        if (state.tenantId != context.tenantId || state.principalId != intent.request.principalId ||
            state.principalType != intent.request.principalType ||
            state.authorizationRevision != intent.request.authorizationRevision ||
            state.requestBindingDigest != intent.request.requestBindingDigest
        ) throw AgentWebOutcomeUnknownException()
        val record = try {
            AgentWebEvaluationRecord(state, intent.evaluator)
        } catch (_: RuntimeException) {
            throw AgentWebOutcomeUnknownException()
        }
        try {
            transactions.inTransaction(AgentWebTransactionWork {
                evaluations.create(scope, record).requireExact(record)
                externalOperations.updateEvaluationBound(
                    scope,
                    AgentWebExternalOperationStatus.OUTCOME_UNKNOWN,
                    intent.completed(reconciledAt),
                )
                mutations.transitionBound(
                    scope,
                    AgentWebMutationTransition(
                        intent.mutation,
                        intent.operationId,
                        AgentWebMutationStatus.OUTCOME_UNKNOWN,
                        AgentWebMutationStatus.SUCCEEDED,
                        state.evaluationId,
                        state.stateVersion,
                        null,
                        reconciledAt,
                    ),
                )
                appendOutbox(scope, intent.operationId, state.evaluationId,
                    "agent.evaluation.reconciled", evidenceDigest, reconciledAt)
            })
        } catch (_: RuntimeException) {
            throw AgentWebOutcomeUnknownException()
        }
        return successReceipt(
            intent.operationId,
            AgentWebReconciliationKind.EVALUATION_TRIGGER,
            state.evaluationId,
            state.stateVersion,
            evidenceDigest,
            reconciledAt,
        )
    }

    private fun closeRejectedStart(
        scope: AgentWebAuthorizedPersistenceScope,
        intent: AgentWebRunStartIntent,
        outcome: AgentWebExternalOutcome<AgentWebRunUseCaseReceipt>,
        reconciledAt: Long,
    ): AgentWebApplicationResult<AgentWebReconciliationReceipt> = closeRejected(
        scope,
        intent.operationId,
        intent.mutation,
        intent.conversationId,
        AgentWebReconciliationKind.RUN_START,
        requireNotNull(outcome.diagnosticCode),
        outcome.evidenceDigest,
        reconciledAt,
    ) {
        externalOperations.updateStartBound(
            scope,
            AgentWebExternalOperationStatus.OUTCOME_UNKNOWN,
            intent.failed(requireNotNull(outcome.diagnosticCode), false, reconciledAt),
        )
    }

    private fun closeRejectedCancellation(
        scope: AgentWebAuthorizedPersistenceScope,
        intent: AgentWebRunCancelIntent,
        outcome: AgentWebExternalOutcome<AgentWebRunUseCaseReceipt>,
        reconciledAt: Long,
    ): AgentWebApplicationResult<AgentWebReconciliationReceipt> = closeRejected(
        scope,
        intent.operationId,
        intent.mutation,
        intent.runId,
        AgentWebReconciliationKind.RUN_CANCELLATION,
        requireNotNull(outcome.diagnosticCode),
        outcome.evidenceDigest,
        reconciledAt,
    ) {
        externalOperations.updateCancellationBound(
            scope,
            AgentWebExternalOperationStatus.OUTCOME_UNKNOWN,
            intent.failed(requireNotNull(outcome.diagnosticCode), false, reconciledAt),
        )
    }

    private fun closeRejectedEvaluation(
        scope: AgentWebAuthorizedPersistenceScope,
        intent: AgentWebEvaluationTriggerIntent,
        outcome: AgentWebExternalOutcome<ai.icen.fw.agent.runtime.AgentEvaluationRunState>,
        reconciledAt: Long,
    ): AgentWebApplicationResult<AgentWebReconciliationReceipt> = closeRejected(
        scope,
        intent.operationId,
        intent.mutation,
        intent.request.suite.suiteId,
        AgentWebReconciliationKind.EVALUATION_TRIGGER,
        requireNotNull(outcome.diagnosticCode),
        outcome.evidenceDigest,
        reconciledAt,
    ) {
        externalOperations.updateEvaluationBound(
            scope,
            AgentWebExternalOperationStatus.OUTCOME_UNKNOWN,
            intent.failed(requireNotNull(outcome.diagnosticCode), false, reconciledAt),
        )
    }

    private fun closeRejected(
        scope: AgentWebAuthorizedPersistenceScope,
        operationId: Identifier,
        mutation: AgentWebMutationScope,
        aggregateId: Identifier,
        kind: AgentWebReconciliationKind,
        diagnosticCode: String,
        evidenceDigest: String,
        reconciledAt: Long,
        updateIntent: () -> Unit,
    ): AgentWebApplicationResult<AgentWebReconciliationReceipt> {
        try {
            transactions.inTransaction(AgentWebTransactionWork {
                updateIntent()
                mutations.transitionBound(
                    scope,
                    AgentWebMutationTransition(
                        mutation,
                        operationId,
                        AgentWebMutationStatus.OUTCOME_UNKNOWN,
                        AgentWebMutationStatus.FAILED,
                        null,
                        null,
                        diagnosticCode,
                        reconciledAt,
                    ),
                )
                appendOutbox(scope, operationId, aggregateId,
                    "agent.external-operation.reconciled-rejected", evidenceDigest, reconciledAt)
            })
        } catch (_: RuntimeException) {
            throw AgentWebOutcomeUnknownException()
        }
        return AgentWebApplicationResult.success(
            AgentWebReconciliationReceipt(
                operationId,
                kind,
                AgentWebExternalOperationStatus.REJECTED,
                null,
                null,
                evidenceDigest,
                reconciledAt,
            ),
        )
    }

    private fun <T> query(work: () -> AgentWebExternalOutcome<T>): AgentWebExternalOutcome<T> = try {
        work()
    } catch (_: RuntimeException) {
        throw AgentWebOutcomeUnknownException()
    }

    private fun <T> validateObservation(
        intentUpdatedAt: Long,
        outcome: AgentWebExternalOutcome<T>,
    ) {
        val completedAt = security.currentTimeMillis()
        if (outcome.observedAt < intentUpdatedAt || completedAt < outcome.observedAt) {
            throw AgentWebOutcomeUnknownException()
        }
    }

    private fun successReceipt(
        operationId: Identifier,
        kind: AgentWebReconciliationKind,
        resourceId: Identifier,
        resourceVersion: Long,
        evidenceDigest: String,
        reconciledAt: Long,
    ): AgentWebApplicationResult<AgentWebReconciliationReceipt> = AgentWebApplicationResult.success(
        AgentWebReconciliationReceipt(
            operationId,
            kind,
            AgentWebExternalOperationStatus.SUCCEEDED,
            resourceId,
            resourceVersion,
            evidenceDigest,
            reconciledAt,
        ),
    )

    private fun appendOutbox(
        scope: AgentWebAuthorizedPersistenceScope,
        operationId: Identifier,
        aggregateId: Identifier,
        eventType: String,
        payloadDigest: String,
        atTime: Long,
    ) {
        outbox.append(
            scope,
            AgentWebOutboxEvent(
                security.nextId("agent-web-reconciliation-outbox"),
                scope.tenantId,
                operationId,
                aggregateId,
                eventType,
                payloadDigest,
                atTime,
            ),
        )
    }

    private companion object {
        const val RECONCILIATION_PURPOSE: String = "agent-web-outcome-reconciliation"
    }
}
