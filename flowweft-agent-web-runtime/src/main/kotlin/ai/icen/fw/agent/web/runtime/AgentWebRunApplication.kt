package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.web.api.AgentRunWebApplicationPort
import ai.icen.fw.agent.web.api.AgentWebApplicationResult
import ai.icen.fw.agent.web.api.AgentWebCitationEvidenceDto
import ai.icen.fw.agent.web.api.AgentWebCommandReceiptDto
import ai.icen.fw.agent.web.api.AgentWebDurablePage
import ai.icen.fw.agent.web.api.AgentWebPage
import ai.icen.fw.agent.web.api.AgentWebPageQuery
import ai.icen.fw.agent.web.api.AgentWebRunCancelCommand
import ai.icen.fw.agent.web.api.AgentWebRunDto
import ai.icen.fw.agent.web.api.AgentWebRunEventDto
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.agent.web.api.AgentWebVisibleMessageDto
import ai.icen.fw.agent.web.api.AgentWebWritePreconditions
import ai.icen.fw.agent.runtime.AgentRunCommandContext
import ai.icen.fw.core.id.Identifier

class DefaultAgentRunWebApplication(
    private val security: AgentWebApplicationSecurity,
    private val transactions: AgentWebTransactionBoundary,
    private val mutations: AgentWebMutationJournal,
    private val outbox: AgentWebOutboxPort,
    private val runs: AgentWebRunProjectionRepository,
    private val externalOperations: AgentWebExternalOperationRepository,
    private val runUseCases: AgentWebRunUseCasePort,
    private val messageProjector: StrictAgentWebVisibleMessageProjector = StrictAgentWebVisibleMessageProjector(),
    private val eventProjector: StrictAgentWebRunEventProjector = StrictAgentWebRunEventProjector(),
) : AgentRunWebApplicationPort {

    override fun get(
        context: AgentWebTrustedContext,
        runId: Identifier,
    ): AgentWebApplicationResult<AgentWebRunDto> = agentWebApplicationCall {
        val authorized = authorizeRun(context, AgentWebAuthorizationAction.RUN_READ, runId)
        val record = requireRun(authorized.scope, context, runId)
        AgentWebApplicationResult.success(record.projection())
    }

    override fun listMessages(
        context: AgentWebTrustedContext,
        runId: Identifier,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebDurablePage<AgentWebVisibleMessageDto>> = agentWebApplicationCall {
        val authorized = authorizeRun(context, AgentWebAuthorizationAction.MESSAGE_READ, runId)
        requireRun(authorized.scope, context, runId)
        val page = runs.messages(authorized.scope, runId, query)
        val projected = page.items.map { record ->
            if (record.runId != runId) throw AgentWebHiddenException()
            val citations = record.citations.map { citation -> authorizeCitation(context, runId, citation) }
            messageProjector.project(context, record, citations)
        }
        AgentWebApplicationResult.success(
            AgentWebDurablePage(runId, projected, durableCursor(runId, page)),
        )
    }

    override fun listEvents(
        context: AgentWebTrustedContext,
        runId: Identifier,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebDurablePage<AgentWebRunEventDto>> = agentWebApplicationCall {
        val authorized = authorizeRun(context, AgentWebAuthorizationAction.EVENT_READ, runId)
        requireRun(authorized.scope, context, runId)
        val page = runs.events(authorized.scope, runId, query)
        val projected = page.items.map { record ->
            if (record.event.runId != runId || record.event.tenantId != context.tenantId) {
                throw AgentWebHiddenException()
            }
            eventProjector.project(record)
        }
        AgentWebApplicationResult.success(
            AgentWebDurablePage(runId, projected, durableCursor(runId, page)),
        )
    }

    override fun cancel(
        context: AgentWebTrustedContext,
        runId: Identifier,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebRunCancelCommand,
    ): AgentWebApplicationResult<AgentWebCommandReceiptDto> = agentWebApplicationCall {
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.RUN_CANCEL,
            AgentWebAuthorizationTarget(
                "agent.run",
                runId,
                preconditions.versionTag.expectedVersion.toString(),
            ),
        )
        val cancellation = AgentCancellation(command.reasonCode, authorized.authorizedAt)
        val commandContext = AgentRunCommandContext(
            context.tenantId,
            context.principalId,
            context.principalType,
            context.requestId,
            authorized.authorizedAt,
        )
        val commandDigest = AgentWebRuntimeDigest("flowweft.agent.web.runtime.run-cancel.v1")
            .add(runId.value)
            .add(preconditions.versionTag.expectedVersion)
            .add(cancellation.reasonCode)
            .finish()
        val mutation = AgentWebMutationScope.bind(
            context,
            preconditions.idempotencyKey,
            AgentWebAuthorizationAction.RUN_CANCEL,
            runId,
            commandDigest,
        )
        val operationId = security.nextId("agent-web-run-cancel-operation")
        val intent = AgentWebRunCancelIntent(
            operationId,
            mutation,
            runId,
            preconditions.versionTag.expectedVersion,
            commandContext,
            cancellation,
            AgentWebExternalOperationStatus.PREPARED,
            null,
            authorized.authorizedAt,
            authorized.authorizedAt,
        )
        val reservation = transactions.inTransaction(
            AgentWebTransactionWork<Pair<AgentWebMutationRecord?, AgentWebRunRecord?>> {
            val reserved = mutations.reserveBound(authorized.scope, mutation, operationId, authorized.authorizedAt)
            when (reserved.status) {
                AgentWebMutationReserveStatus.CONFLICT -> throw AgentWebConflictException()
                AgentWebMutationReserveStatus.REPLAY -> Pair(reserved.record, null)
                AgentWebMutationReserveStatus.CREATED -> {
                    val authoritative = requireRun(authorized.scope, context, runId)
                    if (authoritative.snapshot.stateVersion != preconditions.versionTag.expectedVersion) {
                        throw AgentWebPreconditionException()
                    }
                    externalOperations.createCancellationBound(authorized.scope, intent)
                    appendOutbox(
                        authorized.scope,
                        operationId,
                        runId,
                        "agent.run.cancel.requested",
                        commandDigest,
                        authorized.authorizedAt,
                    )
                    Pair(null, authoritative)
                }
            }
        })
        val replay = reservation.first
        if (replay != null) return@agentWebApplicationCall replayCancellation(authorized.scope, replay)
        val current = requireNotNull(reservation.second)

        val useCaseResult = try {
            runUseCases.cancel(commandContext, runId, current.snapshot.stateVersion, cancellation)
        } catch (_: RuntimeException) {
            AgentWebUseCaseResult.outcomeUnknown<AgentWebRunUseCaseReceipt>("agent.run.cancel.outcome-unknown")
        }
        when (useCaseResult.outcome) {
            AgentWebUseCaseOutcome.SUCCEEDED -> {
                val receipt = requireNotNull(useCaseResult.value)
                if (!(receipt.snapshot.runId == runId && receipt.snapshot.tenantId == context.tenantId &&
                    receipt.snapshot.status == ai.icen.fw.agent.api.AgentRunStatus.CANCELLED &&
                    receipt.snapshot.stateVersion > current.snapshot.stateVersion
                )) {
                    markCancellationUnknown(authorized.scope, mutation, intent, "agent.run.cancel.receipt-mismatch")
                    throw AgentWebOutcomeUnknownException()
                }
                val projection = try {
                    Pair(
                        AgentWebRunRecord(
                            context.tenantId,
                            current.conversationId,
                            receipt.snapshot,
                            current.deadlineAt,
                        ),
                        receipt.events.map { AgentWebRunEventRecord(it.event, it.stateVersion) },
                    )
                } catch (_: RuntimeException) {
                    markCancellationUnknown(authorized.scope, mutation, intent, "agent.run.cancel.receipt-invalid")
                    throw AgentWebOutcomeUnknownException()
                }
                val next = projection.first
                val events = projection.second
                try {
                    transactions.inTransaction(AgentWebTransactionWork {
                        val write = runs.compareAndSet(
                            authorized.scope,
                            current.snapshot.stateVersion,
                            next,
                            events,
                        )
                        if (write.status != AgentWebRepositoryWriteStatus.APPLIED &&
                            write.status != AgentWebRepositoryWriteStatus.REPLAYED
                        ) throw AgentWebConflictException()
                        write.requireExact(next)
                        val atTime = security.currentTimeMillis()
                        externalOperations.updateCancellationBound(
                            authorized.scope,
                            AgentWebExternalOperationStatus.PREPARED,
                            intent.completed(atTime),
                        )
                        mutations.transitionBound(
                            authorized.scope,
                            AgentWebMutationTransition(
                                mutation, operationId, AgentWebMutationStatus.RESERVED,
                                AgentWebMutationStatus.SUCCEEDED, runId, receipt.snapshot.stateVersion,
                                null, atTime,
                            ),
                        )
                        appendOutbox(
                            authorized.scope,
                            operationId,
                            runId,
                            "agent.run.cancelled",
                            commandDigest,
                            atTime,
                        )
                    })
                } catch (_: RuntimeException) {
                    markCancellationUnknown(authorized.scope, mutation, intent, "agent.run.cancel.projection-unknown")
                    throw AgentWebOutcomeUnknownException()
                }
                AgentWebApplicationResult.success(
                    AgentWebCommandReceiptDto("agent.run", runId, receipt.snapshot.stateVersion, "CANCELLED"),
                )
            }
            AgentWebUseCaseOutcome.OUTCOME_UNKNOWN -> {
                markCancellationUnknown(
                    authorized.scope,
                    mutation,
                    intent,
                    useCaseResult.diagnosticCode ?: "agent.run.cancel.outcome-unknown",
                )
                throw AgentWebOutcomeUnknownException()
            }
            AgentWebUseCaseOutcome.NOT_FOUND -> {
                closeCancellationFailure(authorized.scope, mutation, intent, requireNotNull(useCaseResult.diagnosticCode))
                throw AgentWebHiddenException()
            }
            AgentWebUseCaseOutcome.CONFLICT -> {
                closeCancellationFailure(authorized.scope, mutation, intent, requireNotNull(useCaseResult.diagnosticCode))
                throw AgentWebConflictException()
            }
            AgentWebUseCaseOutcome.REJECTED -> {
                closeCancellationFailure(authorized.scope, mutation, intent, requireNotNull(useCaseResult.diagnosticCode))
                throw AgentWebDeniedException()
            }
        }
    }

    override fun listCitations(
        context: AgentWebTrustedContext,
        runId: Identifier,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebCitationEvidenceDto>> = agentWebApplicationCall {
        val authorized = authorizeRun(context, AgentWebAuthorizationAction.CITATION_READ, runId)
        requireRun(authorized.scope, context, runId)
        val page = runs.citations(authorized.scope, runId, query)
        val projected = page.items.map { citation -> authorizeCitation(context, runId, citation) }
        AgentWebApplicationResult.success(AgentWebPage(projected, page.nextCursor))
    }

    private fun authorizeCitation(
        context: AgentWebTrustedContext,
        runId: Identifier,
        record: AgentWebCitationRecord,
    ): AgentWebCitationEvidenceDto {
        if (record.runId != runId || record.citation.tenantId != context.tenantId) throw AgentWebHiddenException()
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.CITATION_READ,
            AgentWebAuthorizationTarget(
                "document.version",
                record.citation.documentVersionId,
                record.citation.contentDigest,
                "agent-citation-display",
            ),
        )
        return AgentWebCitationEvidenceDto.authorized(
            record.citation,
            context,
            record.securityFilterReceiptDigest,
            authorized.decision.decisionId,
            authorized.authorizedAt,
        )
    }

    private fun authorizeRun(
        context: AgentWebTrustedContext,
        action: AgentWebAuthorizationAction,
        runId: Identifier,
    ): AgentWebAuthorizedCall = security.authorize(
        context,
        action,
        AgentWebAuthorizationTarget("agent.run", runId),
    )

    private fun requireRun(
        scope: AgentWebAuthorizedPersistenceScope,
        context: AgentWebTrustedContext,
        runId: Identifier,
    ): AgentWebRunRecord {
        val record = runs.find(scope, runId) ?: throw AgentWebHiddenException()
        if (record.tenantId != context.tenantId || record.snapshot.runId != runId) throw AgentWebHiddenException()
        return record
    }

    private fun replayCancellation(
        scope: AgentWebAuthorizedPersistenceScope,
        record: AgentWebMutationRecord,
    ): AgentWebApplicationResult<AgentWebCommandReceiptDto> = when (record.status) {
        AgentWebMutationStatus.SUCCEEDED -> {
            val runId = requireNotNull(record.resultResourceId)
            if (runId != record.scope.aggregateId) throw AgentWebOutcomeUnknownException()
            val run = runs.find(scope, runId) ?: throw AgentWebOutcomeUnknownException()
            if (run.snapshot.stateVersion != record.resultVersion) throw AgentWebOutcomeUnknownException()
            AgentWebApplicationResult.success(
                AgentWebCommandReceiptDto("agent.run", runId, run.snapshot.stateVersion, run.snapshot.status.name),
                true,
            )
        }
        AgentWebMutationStatus.RESERVED,
        AgentWebMutationStatus.OUTCOME_UNKNOWN -> throw AgentWebOutcomeUnknownException()
        AgentWebMutationStatus.FAILED -> throw AgentWebConflictException()
    }

    private fun markCancellationUnknown(
        scope: AgentWebAuthorizedPersistenceScope,
        mutation: AgentWebMutationScope,
        intent: AgentWebRunCancelIntent,
        code: String,
    ) {
        val atTime = security.currentTimeMillis()
        try {
            transactions.inTransaction(AgentWebTransactionWork {
                externalOperations.updateCancellationBound(
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
                appendOutbox(scope, intent.operationId, intent.runId, "agent.run.cancel.unknown", mutation.commandDigest, atTime)
            })
        } catch (_: RuntimeException) {
            // Preserve unknown; a failed diagnostic write never permits a blind retry.
        }
    }

    private fun closeCancellationFailure(
        scope: AgentWebAuthorizedPersistenceScope,
        mutation: AgentWebMutationScope,
        intent: AgentWebRunCancelIntent,
        code: String,
    ) {
        val atTime = security.currentTimeMillis()
        try {
            transactions.inTransaction(AgentWebTransactionWork {
                externalOperations.updateCancellationBound(
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
                appendOutbox(scope, intent.operationId, intent.runId,
                    "agent.run.cancel.rejected", mutation.commandDigest, atTime)
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
