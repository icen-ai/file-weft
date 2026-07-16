package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.api.AgentCancellationToken
import ai.icen.fw.agent.api.AgentContentOrigin
import ai.icen.fw.agent.api.AgentMessage
import ai.icen.fw.agent.api.AgentMessageRole
import ai.icen.fw.agent.api.AgentRunRequest
import ai.icen.fw.agent.api.AgentRunStatus
import ai.icen.fw.agent.api.AgentTextContentBlock
import ai.icen.fw.agent.web.api.AgentConversationWebApplicationPort
import ai.icen.fw.agent.web.api.AgentWebApplicationResult
import ai.icen.fw.agent.web.api.AgentWebConversationCreateCommand
import ai.icen.fw.agent.web.api.AgentWebConversationDto
import ai.icen.fw.agent.web.api.AgentWebConversationSummaryDto
import ai.icen.fw.agent.web.api.AgentWebPage
import ai.icen.fw.agent.web.api.AgentWebPageQuery
import ai.icen.fw.agent.web.api.AgentWebRunCreateCommand
import ai.icen.fw.agent.web.api.AgentWebRunDto
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.agent.web.api.AgentWebWritePreconditions
import ai.icen.fw.core.id.Identifier

/**
 * Framework-neutral conversation/start-run orchestration. Durable intent/outbox commits happen
 * before the Agent use case is invoked, and the use case is never called from a transaction.
 */
class DefaultAgentConversationWebApplication(
    private val security: AgentWebApplicationSecurity,
    private val transactions: AgentWebTransactionBoundary,
    private val mutations: AgentWebMutationJournal,
    private val outbox: AgentWebOutboxPort,
    private val conversations: AgentWebConversationRepository,
    private val runs: AgentWebRunProjectionRepository,
    private val externalOperations: AgentWebExternalOperationRepository,
    private val runUseCases: AgentWebRunUseCasePort,
) : AgentConversationWebApplicationPort {

    override fun create(
        context: AgentWebTrustedContext,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebConversationCreateCommand,
    ): AgentWebApplicationResult<AgentWebConversationDto> = agentWebApplicationCall {
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.CONVERSATION_CREATE,
            AgentWebAuthorizationTarget("agent.conversation.collection", context.tenantId),
        )
        if (preconditions.versionTag.expectedVersion != 0L) throw AgentWebPreconditionException()
        val commandDigest = conversationCommandDigest(command)
        val conversationId = security.nextId("agent-web-conversation")
        val operationId = security.nextId("agent-web-operation")
        val mutation = AgentWebMutationScope.bind(
            context,
            preconditions.idempotencyKey,
            AgentWebAuthorizationAction.CONVERSATION_CREATE,
            context.tenantId,
            commandDigest,
        )
        val atTime = authorized.authorizedAt
        val title = command.title ?: conversationId.value
        val createdRecord = AgentWebConversationRecord(
            context.tenantId,
            context.principalId,
            context.principalType,
            conversationId,
            title,
            command.capabilityId,
            command.defaultBudget,
            null,
            0L,
            atTime,
            atTime,
        )
        val replay = transactions.inTransaction(AgentWebTransactionWork<AgentWebMutationRecord?> {
            val reserved = mutations.reserveBound(authorized.scope, mutation, operationId, atTime)
            when (reserved.status) {
                AgentWebMutationReserveStatus.CONFLICT -> throw AgentWebConflictException()
                AgentWebMutationReserveStatus.REPLAY -> reserved.record
                AgentWebMutationReserveStatus.CREATED -> {
                        val created = conversations.create(authorized.scope, createdRecord)
                        if (created.status != AgentWebRepositoryWriteStatus.APPLIED &&
                            created.status != AgentWebRepositoryWriteStatus.REPLAYED
                        ) throw AgentWebConflictException()
                        created.requireExact(createdRecord)
                        appendOutbox(
                            authorized.scope,
                            operationId,
                            conversationId,
                            "agent.conversation.created",
                            commandDigest,
                            atTime,
                        )
                        mutations.transitionBound(
                            authorized.scope,
                            AgentWebMutationTransition(
                                mutation,
                                operationId,
                                 AgentWebMutationStatus.RESERVED,
                                 AgentWebMutationStatus.SUCCEEDED,
                                 conversationId,
                                 createdRecord.stateVersion,
                                null,
                                atTime,
                            ),
                        )
                        null
                }
            }
        })
        if (replay != null) replayConversation(context, replay)
        else AgentWebApplicationResult.success(createdRecord.projection())
    }

    override fun list(
        context: AgentWebTrustedContext,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebConversationSummaryDto>> = agentWebApplicationCall {
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.CONVERSATION_READ,
            AgentWebAuthorizationTarget("agent.conversation.collection", context.tenantId),
        )
        val page = conversations.list(authorized.scope, query)
        requireTenant(page.items, context.tenantId) { it.tenantId }
        AgentWebApplicationResult.success(
            AgentWebPage(page.items.map { it.projection().summary }, page.nextCursor),
        )
    }

    override fun get(
        context: AgentWebTrustedContext,
        conversationId: Identifier,
    ): AgentWebApplicationResult<AgentWebConversationDto> = agentWebApplicationCall {
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.CONVERSATION_READ,
            AgentWebAuthorizationTarget("agent.conversation", conversationId),
        )
        val record = conversations.find(authorized.scope, conversationId) ?: throw AgentWebHiddenException()
        requireTenant(record.tenantId, context.tenantId)
        if (record.conversationId != conversationId) throw AgentWebHiddenException()
        AgentWebApplicationResult.success(record.projection())
    }

    override fun startRun(
        context: AgentWebTrustedContext,
        conversationId: Identifier,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebRunCreateCommand,
    ): AgentWebApplicationResult<AgentWebRunDto> = agentWebApplicationCall {
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.RUN_CREATE,
            AgentWebAuthorizationTarget(
                "agent.conversation",
                conversationId,
                preconditions.versionTag.expectedVersion.toString(),
            ),
        )
        val message = AgentMessage(
            command.message.clientMessageId,
            AgentMessageRole.USER,
            listOf(AgentTextContentBlock(AgentContentOrigin.USER, command.message.authorizedDisplayText)),
            authorized.authorizedAt,
        )
        val downstreamIdempotencyToken = agentWebDerivedIdempotencyToken(
            context,
            preconditions.idempotencyKey,
            AgentWebAuthorizationAction.RUN_CREATE,
            conversationId,
        )
        val request = AgentRunRequest(
            context.runContext,
            command.capabilityId,
            listOf(message),
            command.budget,
            downstreamIdempotencyToken,
            command.deadlineAt,
            AgentCancellationToken.NONE,
        )
        val commandDigest = runStartCommandDigest(conversationId, command)
        val operationId = security.nextId("agent-web-run-start-operation")
        val mutation = AgentWebMutationScope.bind(
            context,
            preconditions.idempotencyKey,
            AgentWebAuthorizationAction.RUN_CREATE,
            conversationId,
            commandDigest,
        )
        val intent = AgentWebRunStartIntent(
            operationId,
            mutation,
            conversationId,
            preconditions.versionTag.expectedVersion,
            request,
            AgentWebExternalOperationStatus.PREPARED,
            null,
            authorized.authorizedAt,
            authorized.authorizedAt,
        )

        val reservation = transactions.inTransaction(
            AgentWebTransactionWork<Pair<AgentWebMutationRecord?, AgentWebConversationRecord?>> {
            val reserved = mutations.reserveBound(authorized.scope, mutation, operationId, authorized.authorizedAt)
            when (reserved.status) {
                AgentWebMutationReserveStatus.CONFLICT -> throw AgentWebConflictException()
                AgentWebMutationReserveStatus.REPLAY -> Pair(reserved.record, null)
                AgentWebMutationReserveStatus.CREATED -> {
                    val authoritative = conversations.find(authorized.scope, conversationId)
                        ?: throw AgentWebHiddenException()
                    requireTenant(authoritative.tenantId, context.tenantId)
                    if (authoritative.conversationId != conversationId) throw AgentWebHiddenException()
                    if (authoritative.stateVersion != preconditions.versionTag.expectedVersion) {
                        throw AgentWebPreconditionException()
                    }
                    externalOperations.createStartBound(authorized.scope, intent)
                    appendOutbox(
                        authorized.scope,
                        operationId,
                        conversationId,
                        "agent.run.start.requested",
                        request.bindingDigest,
                        authorized.authorizedAt,
                    )
                    Pair(null, authoritative)
                }
            }
        })
        val replay = reservation.first
        if (replay != null) return@agentWebApplicationCall replayRun(context, replay)
        val conversation = requireNotNull(reservation.second)

        val useCaseResult = try {
            runUseCases.start(request)
        } catch (_: RuntimeException) {
            AgentWebUseCaseResult.outcomeUnknown<AgentWebRunUseCaseReceipt>("agent.run.start.outcome-unknown")
        }
        when (useCaseResult.outcome) {
            AgentWebUseCaseOutcome.SUCCEEDED -> {
                val receipt = requireNotNull(useCaseResult.value)
                if (!(receipt.snapshot.tenantId == context.tenantId &&
                    receipt.snapshot.capabilityId == command.capabilityId &&
                    receipt.snapshot.messages.any { persisted -> persisted.bindingDigest == message.bindingDigest }
                )) {
                    markStartUnknown(authorized.scope, mutation, intent, "agent.run.start.receipt-mismatch")
                    throw AgentWebOutcomeUnknownException()
                }
                val projection = try {
                    Triple(
                        AgentWebRunRecord(
                            context.tenantId,
                            conversationId,
                            receipt.snapshot,
                            command.deadlineAt,
                        ),
                        AgentWebVisibleMessageRecord(
                            receipt.snapshot.runId,
                            1L,
                            message,
                            emptyList(),
                        ),
                        receipt.events.map { item -> AgentWebRunEventRecord(item.event, item.stateVersion) },
                    )
                } catch (_: RuntimeException) {
                    markStartUnknown(authorized.scope, mutation, intent, "agent.run.start.receipt-invalid")
                    throw AgentWebOutcomeUnknownException()
                }
                val runRecord = projection.first
                val visibleMessage = projection.second
                val eventRecords = projection.third
                try {
                    transactions.inTransaction(AgentWebTransactionWork {
                        val runWrite = runs.create(authorized.scope, runRecord, visibleMessage, eventRecords)
                        if (runWrite.status != AgentWebRepositoryWriteStatus.APPLIED &&
                            runWrite.status != AgentWebRepositoryWriteStatus.REPLAYED
                        ) throw AgentWebConflictException()
                        runWrite.requireExact(runRecord)
                        val nextConversation = conversation.withRun(receipt.snapshot.status, security.currentTimeMillis())
                        val conversationWrite = conversations.compareAndSet(
                            authorized.scope,
                            conversation.stateVersion,
                            nextConversation,
                        )
                        if (conversationWrite.status != AgentWebRepositoryWriteStatus.APPLIED &&
                            conversationWrite.status != AgentWebRepositoryWriteStatus.REPLAYED
                        ) throw AgentWebConflictException()
                        conversationWrite.requireExact(nextConversation)
                        val completedAt = security.currentTimeMillis()
                        externalOperations.updateStartBound(
                            authorized.scope,
                            AgentWebExternalOperationStatus.PREPARED,
                            intent.completed(completedAt),
                        )
                        appendOutbox(
                            authorized.scope,
                            operationId,
                            receipt.snapshot.runId,
                            "agent.run.started",
                            request.bindingDigest,
                            completedAt,
                        )
                        mutations.transitionBound(
                            authorized.scope,
                            AgentWebMutationTransition(
                                mutation,
                                operationId,
                                AgentWebMutationStatus.RESERVED,
                                AgentWebMutationStatus.SUCCEEDED,
                                receipt.snapshot.runId,
                                receipt.snapshot.stateVersion,
                                null,
                                completedAt,
                            ),
                        )
                    })
                } catch (_: RuntimeException) {
                    markStartUnknown(authorized.scope, mutation, intent, "agent.run.projection-outcome-unknown")
                    throw AgentWebOutcomeUnknownException()
                }
                AgentWebApplicationResult.success(runRecord.projection())
            }
            AgentWebUseCaseOutcome.OUTCOME_UNKNOWN -> {
                markStartUnknown(
                    authorized.scope,
                    mutation,
                    intent,
                    useCaseResult.diagnosticCode ?: "agent.run.start.outcome-unknown",
                )
                throw AgentWebOutcomeUnknownException()
            }
            AgentWebUseCaseOutcome.CONFLICT -> {
                closeStartFailure(authorized.scope, mutation, intent, requireNotNull(useCaseResult.diagnosticCode))
                throw AgentWebConflictException()
            }
            AgentWebUseCaseOutcome.NOT_FOUND -> {
                closeStartFailure(authorized.scope, mutation, intent, requireNotNull(useCaseResult.diagnosticCode))
                throw AgentWebHiddenException()
            }
            AgentWebUseCaseOutcome.REJECTED -> {
                closeStartFailure(authorized.scope, mutation, intent, requireNotNull(useCaseResult.diagnosticCode))
                throw AgentWebDeniedException()
            }
        }
    }

    override fun listRuns(
        context: AgentWebTrustedContext,
        conversationId: Identifier,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebRunDto>> = agentWebApplicationCall {
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.RUN_READ,
            AgentWebAuthorizationTarget("agent.conversation", conversationId),
        )
        val conversation = conversations.find(authorized.scope, conversationId) ?: throw AgentWebHiddenException()
        requireTenant(conversation.tenantId, context.tenantId)
        val page = runs.listByConversation(authorized.scope, conversationId, query)
        requireTenant(page.items, context.tenantId) { it.tenantId }
        if (page.items.any { it.conversationId != conversationId }) throw AgentWebHiddenException()
        AgentWebApplicationResult.success(AgentWebPage(page.items.map(AgentWebRunRecord::projection), page.nextCursor))
    }

    private fun replayConversation(
        context: AgentWebTrustedContext,
        record: AgentWebMutationRecord,
    ): AgentWebApplicationResult<AgentWebConversationDto> = when (record.status) {
        AgentWebMutationStatus.SUCCEEDED -> {
            val id = requireNotNull(record.resultResourceId)
            val read = security.authorize(
                context,
                AgentWebAuthorizationAction.CONVERSATION_READ,
                AgentWebAuthorizationTarget("agent.conversation", id),
            )
            val conversation = conversations.find(read.scope, id) ?: throw AgentWebOutcomeUnknownException()
            if (conversation.tenantId != context.tenantId || conversation.conversationId != id ||
                conversation.stateVersion != record.resultVersion
            ) {
                throw AgentWebOutcomeUnknownException()
            }
            AgentWebApplicationResult.success(conversation.projection(), true)
        }
        AgentWebMutationStatus.RESERVED,
        AgentWebMutationStatus.OUTCOME_UNKNOWN -> throw AgentWebOutcomeUnknownException()
        AgentWebMutationStatus.FAILED -> throw AgentWebConflictException()
    }

    private fun replayRun(
        context: AgentWebTrustedContext,
        record: AgentWebMutationRecord,
    ): AgentWebApplicationResult<AgentWebRunDto> = when (record.status) {
        AgentWebMutationStatus.SUCCEEDED -> {
            val runId = requireNotNull(record.resultResourceId)
            val read = security.authorize(
                context,
                AgentWebAuthorizationAction.RUN_READ,
                AgentWebAuthorizationTarget("agent.run", runId),
            )
            val run = runs.find(read.scope, runId)
                ?: throw AgentWebOutcomeUnknownException()
            if (run.tenantId != context.tenantId || run.snapshot.runId != runId ||
                run.snapshot.stateVersion != record.resultVersion
            ) throw AgentWebOutcomeUnknownException()
            AgentWebApplicationResult.success(run.projection(), true)
        }
        AgentWebMutationStatus.RESERVED,
        AgentWebMutationStatus.OUTCOME_UNKNOWN -> throw AgentWebOutcomeUnknownException()
        AgentWebMutationStatus.FAILED -> throw AgentWebConflictException()
    }

    private fun markStartUnknown(
        scope: AgentWebAuthorizedPersistenceScope,
        mutation: AgentWebMutationScope,
        intent: AgentWebRunStartIntent,
        code: String,
    ) {
        val atTime = security.currentTimeMillis()
        try {
            transactions.inTransaction(AgentWebTransactionWork {
                externalOperations.updateStartBound(
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
                appendOutbox(scope, intent.operationId, intent.conversationId, "agent.run.start.unknown", mutation.commandDigest, atTime)
            })
        } catch (_: RuntimeException) {
            // The original outcome is already unknown; never manufacture success from a diagnostic write.
        }
    }

    private fun closeStartFailure(
        scope: AgentWebAuthorizedPersistenceScope,
        mutation: AgentWebMutationScope,
        intent: AgentWebRunStartIntent,
        code: String,
    ) {
        val atTime = security.currentTimeMillis()
        try {
            transactions.inTransaction(AgentWebTransactionWork {
                externalOperations.updateStartBound(
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
                appendOutbox(scope, intent.operationId, intent.conversationId,
                    "agent.run.start.rejected", mutation.commandDigest, atTime)
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
                security.nextId("agent-web-outbox"),
                scope.tenantId,
                operationId,
                aggregateId,
                eventType,
                payloadDigest,
                atTime,
            ),
        )
    }

    private fun conversationCommandDigest(command: AgentWebConversationCreateCommand): String =
        AgentWebRuntimeDigest("flowweft.agent.web.runtime.conversation-create.v1")
            .add(command.capabilityId.value)
            .add(command.defaultBudget.maximumInputTokens)
            .add(command.defaultBudget.maximumOutputTokens)
            .add(command.defaultBudget.maximumModelCalls)
            .add(command.defaultBudget.maximumToolCalls)
            .add(command.defaultBudget.maximumDurationMillis)
            .add(command.defaultBudget.maximumCostMicros)
            .add(command.title ?: "-")
            .finish()

    private fun runStartCommandDigest(conversationId: Identifier, command: AgentWebRunCreateCommand): String =
        AgentWebRuntimeDigest("flowweft.agent.web.runtime.run-start.v1")
            .add(conversationId.value)
            .add(command.capabilityId.value)
            .add(command.message.clientMessageId.value)
            .add(command.message.authorizedDisplayText)
            .add(command.budget.maximumInputTokens)
            .add(command.budget.maximumOutputTokens)
            .add(command.budget.maximumModelCalls)
            .add(command.budget.maximumToolCalls)
            .add(command.budget.maximumDurationMillis)
            .add(command.budget.maximumCostMicros)
            .add(command.deadlineAt)
            .finish()

    private fun requireTenant(actual: Identifier, expected: Identifier) {
        if (actual != expected) throw AgentWebHiddenException()
    }

    private fun <T> requireTenant(values: Collection<T>, expected: Identifier, tenant: (T) -> Identifier) {
        if (values.any { tenant(it) != expected }) throw AgentWebHiddenException()
    }
}
