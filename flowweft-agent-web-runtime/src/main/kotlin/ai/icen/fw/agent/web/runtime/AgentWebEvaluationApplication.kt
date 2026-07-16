package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.evaluation.AgentEvaluationDatasetReference
import ai.icen.fw.agent.web.api.AgentEvaluationWebApplicationPort
import ai.icen.fw.agent.web.api.AgentWebApplicationResult
import ai.icen.fw.agent.web.api.AgentWebEvaluationDatasetDto
import ai.icen.fw.agent.web.api.AgentWebEvaluationDatasetSummaryDto
import ai.icen.fw.agent.web.api.AgentWebEvaluationResultDto
import ai.icen.fw.agent.web.api.AgentWebEvaluationRunDto
import ai.icen.fw.agent.web.api.AgentWebEvaluationTriggerCommand
import ai.icen.fw.agent.web.api.AgentWebPage
import ai.icen.fw.agent.web.api.AgentWebPageQuery
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.agent.web.api.AgentWebWritePreconditions
import ai.icen.fw.agent.runtime.AgentEvaluationRunRequest
import ai.icen.fw.core.id.Identifier

class DefaultAgentEvaluationWebApplication(
    private val security: AgentWebApplicationSecurity,
    private val transactions: AgentWebTransactionBoundary,
    private val mutations: AgentWebMutationJournal,
    private val outbox: AgentWebOutboxPort,
    private val externalOperations: AgentWebExternalOperationRepository,
    private val catalog: AgentWebEvaluationCatalogPort,
    private val evaluations: AgentWebEvaluationRepository,
    private val useCases: AgentWebEvaluationUseCasePort,
    private val results: AgentWebEvaluationResultProjectionPort,
) : AgentEvaluationWebApplicationPort {

    override fun listDatasets(
        context: AgentWebTrustedContext,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebEvaluationDatasetSummaryDto>> = agentWebApplicationCall {
        val authorized = authorizeRead(context, "agent.evaluation-dataset.collection", context.tenantId)
        val page = catalog.list(authorized.scope, query)
        AgentWebApplicationResult.success(
            AgentWebPage(page.items.map(AgentWebEvaluationDatasetSummaryDto::from), page.nextCursor),
        )
    }

    override fun getDataset(
        context: AgentWebTrustedContext,
        dataset: AgentEvaluationDatasetReference,
    ): AgentWebApplicationResult<AgentWebEvaluationDatasetDto> = agentWebApplicationCall {
        authorizeRead(context, "agent.evaluation-dataset", dataset.suiteId)
        val suite = catalog.dataset(dataset) ?: throw AgentWebHiddenException()
        if (!dataset.matches(suite)) throw AgentWebHiddenException()
        AgentWebApplicationResult.success(AgentWebEvaluationDatasetDto(suite))
    }

    override fun trigger(
        context: AgentWebTrustedContext,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebEvaluationTriggerCommand,
    ): AgentWebApplicationResult<AgentWebEvaluationRunDto> = agentWebApplicationCall {
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.EVALUATION_TRIGGER,
            AgentWebAuthorizationTarget("agent.evaluation.collection", context.tenantId),
        )
        if (preconditions.versionTag.expectedVersion != 0L) throw AgentWebPreconditionException()
        val suite = catalog.dataset(command.dataset) ?: throw AgentWebHiddenException()
        if (!command.dataset.matches(suite)) throw AgentWebHiddenException()
        val provider = catalog.provider(command.providerSnapshot.providerId)
            ?: throw AgentWebUnavailableException()
        if (provider.snapshotDigest != command.providerSnapshot.snapshotDigest ||
            !provider.isCurrent(authorized.authorizedAt)
        ) throw AgentWebPreconditionException()
        val evaluator = catalog.evaluator(command.evaluator) ?: throw AgentWebUnavailableException()
        if (evaluator != command.evaluator) throw AgentWebPreconditionException()

        val downstreamIdempotencyToken = agentWebDerivedIdempotencyToken(
            context,
            preconditions.idempotencyKey,
            AgentWebAuthorizationAction.EVALUATION_TRIGGER,
            context.tenantId,
        )
        val request = AgentEvaluationRunRequest(
            security.nextId("agent-web-evaluation-request"),
            context.tenantId,
            context.principalId,
            context.principalType,
            context.authorizationRevision,
            suite,
            provider,
            downstreamIdempotencyToken,
            authorized.authorizedAt,
            command.deadlineAt,
            command.maximumAttempts,
        )
        val commandDigest = AgentWebRuntimeDigest("flowweft.agent.web.runtime.evaluation-trigger.v1")
            .add(command.dataset.bindingDigest)
            .add(command.providerSnapshot.snapshotDigest)
            .add(evaluator.bindingDigest)
            .add(command.deadlineAt)
            .add(command.maximumAttempts)
            .finish()
        val mutation = AgentWebMutationScope.bind(
            context,
            preconditions.idempotencyKey,
            AgentWebAuthorizationAction.EVALUATION_TRIGGER,
            context.tenantId,
            commandDigest,
        )
        val operationId = security.nextId("agent-web-evaluation-operation")
        val intent = AgentWebEvaluationTriggerIntent(
            operationId,
            mutation,
            request,
            evaluator,
            AgentWebExternalOperationStatus.PREPARED,
            null,
            authorized.authorizedAt,
            authorized.authorizedAt,
        )
        val replay = transactions.inTransaction(AgentWebTransactionWork {
            val reserved = mutations.reserveBound(authorized.scope, mutation, operationId, authorized.authorizedAt)
            when (reserved.status) {
                AgentWebMutationReserveStatus.CONFLICT -> throw AgentWebConflictException()
                AgentWebMutationReserveStatus.REPLAY -> reserved.record
                AgentWebMutationReserveStatus.CREATED -> {
                    externalOperations.createEvaluationBound(authorized.scope, intent)
                    appendOutbox(
                        authorized.scope,
                        operationId,
                        command.dataset.suiteId,
                        "agent.evaluation.requested",
                        request.requestBindingDigest,
                        authorized.authorizedAt,
                    )
                    null
                }
            }
        })
        if (replay != null) return@agentWebApplicationCall replayEvaluation(context, replay)

        val useCaseResult = try {
            useCases.trigger(request)
        } catch (_: RuntimeException) {
            AgentWebUseCaseResult.outcomeUnknown<ai.icen.fw.agent.runtime.AgentEvaluationRunState>(
                "agent.evaluation.outcome-unknown",
            )
        }
        when (useCaseResult.outcome) {
            AgentWebUseCaseOutcome.SUCCEEDED -> {
                val state = requireNotNull(useCaseResult.value)
                if (!(state.tenantId == context.tenantId && state.principalId == context.principalId &&
                    state.principalType == context.principalType &&
                    state.authorizationRevision == context.authorizationRevision &&
                    state.requestBindingDigest == request.requestBindingDigest &&
                    state.suite.suiteDigest == suite.suiteDigest &&
                    state.providerSnapshot.snapshotDigest == provider.snapshotDigest
                )) {
                    markUnknown(authorized.scope, mutation, intent, "agent.evaluation.receipt-mismatch")
                    throw AgentWebOutcomeUnknownException()
                }
                val record = try {
                    AgentWebEvaluationRecord(state, evaluator)
                } catch (_: RuntimeException) {
                    markUnknown(authorized.scope, mutation, intent, "agent.evaluation.receipt-invalid")
                    throw AgentWebOutcomeUnknownException()
                }
                try {
                    transactions.inTransaction(AgentWebTransactionWork {
                        val write = evaluations.create(authorized.scope, record)
                        if (write.status != AgentWebRepositoryWriteStatus.APPLIED &&
                            write.status != AgentWebRepositoryWriteStatus.REPLAYED
                        ) throw AgentWebConflictException()
                        write.requireExact(record)
                        val atTime = security.currentTimeMillis()
                        externalOperations.updateEvaluationBound(
                            authorized.scope,
                            AgentWebExternalOperationStatus.PREPARED,
                            intent.completed(atTime),
                        )
                        mutations.transitionBound(
                            authorized.scope,
                            AgentWebMutationTransition(
                                mutation, operationId, AgentWebMutationStatus.RESERVED,
                                AgentWebMutationStatus.SUCCEEDED, state.evaluationId, state.stateVersion,
                                null, atTime,
                            ),
                        )
                        appendOutbox(
                            authorized.scope,
                            operationId,
                            state.evaluationId,
                            "agent.evaluation.started",
                            request.requestBindingDigest,
                            atTime,
                        )
                    })
                } catch (_: RuntimeException) {
                    markUnknown(authorized.scope, mutation, intent, "agent.evaluation.projection-unknown")
                    throw AgentWebOutcomeUnknownException()
                }
                AgentWebApplicationResult.success(record.projection())
            }
            AgentWebUseCaseOutcome.OUTCOME_UNKNOWN -> {
                markUnknown(
                    authorized.scope,
                    mutation,
                    intent,
                    useCaseResult.diagnosticCode ?: "agent.evaluation.outcome-unknown",
                )
                throw AgentWebOutcomeUnknownException()
            }
            AgentWebUseCaseOutcome.NOT_FOUND -> {
                closeFailure(authorized.scope, mutation, intent, requireNotNull(useCaseResult.diagnosticCode))
                throw AgentWebHiddenException()
            }
            AgentWebUseCaseOutcome.CONFLICT -> {
                closeFailure(authorized.scope, mutation, intent, requireNotNull(useCaseResult.diagnosticCode))
                throw AgentWebConflictException()
            }
            AgentWebUseCaseOutcome.REJECTED -> {
                closeFailure(authorized.scope, mutation, intent, requireNotNull(useCaseResult.diagnosticCode))
                throw AgentWebDeniedException()
            }
        }
    }

    override fun listRuns(
        context: AgentWebTrustedContext,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebEvaluationRunDto>> = agentWebApplicationCall {
        val authorized = authorizeRead(context, "agent.evaluation.collection", context.tenantId)
        val page = evaluations.list(authorized.scope, query)
        if (page.items.any { it.state.tenantId != context.tenantId }) throw AgentWebHiddenException()
        AgentWebApplicationResult.success(AgentWebPage(page.items.map(AgentWebEvaluationRecord::projection), page.nextCursor))
    }

    override fun getRun(
        context: AgentWebTrustedContext,
        evaluationId: Identifier,
    ): AgentWebApplicationResult<AgentWebEvaluationRunDto> = agentWebApplicationCall {
        val authorized = authorizeRead(context, "agent.evaluation", evaluationId)
        val record = requireEvaluation(authorized.scope, context, evaluationId)
        AgentWebApplicationResult.success(record.projection())
    }

    override fun getResult(
        context: AgentWebTrustedContext,
        evaluationId: Identifier,
    ): AgentWebApplicationResult<AgentWebEvaluationResultDto> = agentWebApplicationCall {
        val authorized = authorizeRead(context, "agent.evaluation-result", evaluationId)
        val record = requireEvaluation(authorized.scope, context, evaluationId)
        val report = record.result ?: results.result(context.tenantId, evaluationId)
            ?: throw AgentWebHiddenException()
        AgentWebApplicationResult.success(
            AgentWebEvaluationResultDto(evaluationId, report, record.state.stateVersion),
        )
    }

    private fun authorizeRead(
        context: AgentWebTrustedContext,
        resourceType: String,
        resourceId: Identifier,
    ): AgentWebAuthorizedCall = security.authorize(
        context,
        AgentWebAuthorizationAction.EVALUATION_READ,
        AgentWebAuthorizationTarget(resourceType, resourceId),
    )

    private fun requireEvaluation(
        scope: AgentWebAuthorizedPersistenceScope,
        context: AgentWebTrustedContext,
        evaluationId: Identifier,
    ): AgentWebEvaluationRecord {
        val record = evaluations.find(scope, evaluationId) ?: throw AgentWebHiddenException()
        if (record.state.tenantId != context.tenantId || record.state.evaluationId != evaluationId) {
            throw AgentWebHiddenException()
        }
        return record
    }

    private fun replayEvaluation(
        context: AgentWebTrustedContext,
        mutation: AgentWebMutationRecord,
    ): AgentWebApplicationResult<AgentWebEvaluationRunDto> = when (mutation.status) {
        AgentWebMutationStatus.SUCCEEDED -> {
            val evaluationId = requireNotNull(mutation.resultResourceId)
            val read = authorizeRead(context, "agent.evaluation", evaluationId)
            val record = evaluations.find(read.scope, evaluationId)
                ?: throw AgentWebOutcomeUnknownException()
            if (record.state.tenantId != context.tenantId || record.state.evaluationId != evaluationId ||
                record.state.stateVersion != mutation.resultVersion
            ) throw AgentWebOutcomeUnknownException()
            AgentWebApplicationResult.success(record.projection(), true)
        }
        AgentWebMutationStatus.RESERVED,
        AgentWebMutationStatus.OUTCOME_UNKNOWN -> throw AgentWebOutcomeUnknownException()
        AgentWebMutationStatus.FAILED -> throw AgentWebConflictException()
    }

    private fun markUnknown(
        scope: AgentWebAuthorizedPersistenceScope,
        mutation: AgentWebMutationScope,
        intent: AgentWebEvaluationTriggerIntent,
        code: String,
    ) {
        val atTime = security.currentTimeMillis()
        try {
            transactions.inTransaction(AgentWebTransactionWork {
                externalOperations.updateEvaluationBound(
                    scope,
                    AgentWebExternalOperationStatus.PREPARED,
                    intent.failed(code, true, atTime),
                )
                mutations.transitionBound(
                    scope,
                    AgentWebMutationTransition(
                        mutation, intent.operationId, AgentWebMutationStatus.RESERVED,
                        AgentWebMutationStatus.OUTCOME_UNKNOWN, null, null, code, atTime,
                    ),
                )
                appendOutbox(scope, intent.operationId, intent.request.suite.suiteId,
                    "agent.evaluation.outcome-unknown", mutation.commandDigest, atTime)
            })
        } catch (_: RuntimeException) {
            // Preserve unknown; the durable intent remains the reconciliation source.
        }
    }

    private fun closeFailure(
        scope: AgentWebAuthorizedPersistenceScope,
        mutation: AgentWebMutationScope,
        intent: AgentWebEvaluationTriggerIntent,
        code: String,
    ) {
        val atTime = security.currentTimeMillis()
        try {
            transactions.inTransaction(AgentWebTransactionWork {
                externalOperations.updateEvaluationBound(
                    scope,
                    AgentWebExternalOperationStatus.PREPARED,
                    intent.failed(code, false, atTime),
                )
                mutations.transitionBound(
                    scope,
                    AgentWebMutationTransition(
                        mutation, intent.operationId, AgentWebMutationStatus.RESERVED,
                        AgentWebMutationStatus.FAILED, null, null, code, atTime,
                    ),
                )
                appendOutbox(scope, intent.operationId, intent.request.suite.suiteId,
                    "agent.evaluation.rejected", mutation.commandDigest, atTime)
            })
        } catch (_: RuntimeException) {
            throw AgentWebOutcomeUnknownException()
        }
    }

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
                security.nextId("agent-web-outbox"), scope.tenantId, operationId, aggregateId,
                eventType, payloadDigest, atTime,
            ),
        )
    }
}
