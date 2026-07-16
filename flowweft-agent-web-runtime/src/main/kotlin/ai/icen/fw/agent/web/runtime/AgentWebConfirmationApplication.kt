package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.api.AgentApprovalDecision
import ai.icen.fw.agent.api.AgentApprovalOutcome
import ai.icen.fw.agent.web.api.AgentToolConfirmationWebApplicationPort
import ai.icen.fw.agent.web.api.AgentWebApplicationResult
import ai.icen.fw.agent.web.api.AgentWebPage
import ai.icen.fw.agent.web.api.AgentWebPageQuery
import ai.icen.fw.agent.web.api.AgentWebToolConfirmationDecisionCommand
import ai.icen.fw.agent.web.api.AgentWebToolConfirmationDecisionDto
import ai.icen.fw.agent.web.api.AgentWebToolConfirmationDetailDto
import ai.icen.fw.agent.web.api.AgentWebToolConfirmationSummaryDto
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.agent.web.api.AgentWebWritePreconditions
import ai.icen.fw.agent.runtime.AgentRunCommandContext
import ai.icen.fw.core.id.Identifier

class DefaultAgentToolConfirmationWebApplication(
    private val security: AgentWebApplicationSecurity,
    private val transactions: AgentWebTransactionBoundary,
    private val mutations: AgentWebMutationJournal,
    private val outbox: AgentWebOutboxPort,
    private val confirmations: AgentWebConfirmationRepository,
    private val runs: AgentWebRunProjectionRepository,
    private val approvalUseCases: AgentWebApprovalUseCasePort,
) : AgentToolConfirmationWebApplicationPort {

    override fun inbox(
        context: AgentWebTrustedContext,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebToolConfirmationSummaryDto>> = agentWebApplicationCall {
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.CONFIRMATION_READ,
            AgentWebAuthorizationTarget("agent.confirmation.collection", context.tenantId),
        )
        val page = confirmations.listPending(authorized.scope, query)
        if (page.items.any { it.decision != null || it.request.tenantId != context.tenantId ||
                it.request.operatorId != context.principalId || it.request.operatorType != context.principalType
            }
        ) throw AgentWebHiddenException()
        AgentWebApplicationResult.success(AgentWebPage(page.items.map(AgentWebConfirmationRecord::summary), page.nextCursor))
    }

    override fun get(
        context: AgentWebTrustedContext,
        requestId: Identifier,
    ): AgentWebApplicationResult<AgentWebToolConfirmationDetailDto> = agentWebApplicationCall {
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.CONFIRMATION_READ,
            AgentWebAuthorizationTarget("agent.confirmation", requestId),
        )
        val record = confirmations.load(authorized.scope, requestId) ?: throw AgentWebHiddenException()
        if (record.request.requestId != requestId) throw AgentWebHiddenException()
        requireOwner(context, record)
        AgentWebApplicationResult.success(record.detail())
    }

    override fun approve(
        context: AgentWebTrustedContext,
        requestId: Identifier,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebToolConfirmationDecisionCommand,
    ): AgentWebApplicationResult<AgentWebToolConfirmationDecisionDto> = decide(
        context,
        requestId,
        preconditions,
        command,
        AgentApprovalOutcome.APPROVED,
        AgentWebAuthorizationAction.CONFIRMATION_APPROVE,
    )

    override fun reject(
        context: AgentWebTrustedContext,
        requestId: Identifier,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebToolConfirmationDecisionCommand,
    ): AgentWebApplicationResult<AgentWebToolConfirmationDecisionDto> = decide(
        context,
        requestId,
        preconditions,
        command,
        AgentApprovalOutcome.REJECTED,
        AgentWebAuthorizationAction.CONFIRMATION_REJECT,
    )

    private fun decide(
        context: AgentWebTrustedContext,
        requestId: Identifier,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebToolConfirmationDecisionCommand,
        expectedOutcome: AgentApprovalOutcome,
        action: AgentWebAuthorizationAction,
    ): AgentWebApplicationResult<AgentWebToolConfirmationDecisionDto> = agentWebApplicationCall {
        if (command.outcome != expectedOutcome || command.requestId != requestId) {
            throw AgentWebInvalidRequestException()
        }
        val authorized = security.authorize(
            context,
            action,
            AgentWebAuthorizationTarget(
                "agent.confirmation",
                requestId,
                preconditions.versionTag.expectedVersion.toString(),
                "agent-tool-confirmation",
            ),
        )
        val commandDigest = confirmationCommandDigest(command)
        val mutation = AgentWebMutationScope.bind(
            context,
            preconditions.idempotencyKey,
            action,
            requestId,
            commandDigest,
        )
        val operationId = security.nextId("agent-web-confirmation-operation")
        val prepared = transactions.inTransaction(AgentWebTransactionWork {
            val reserved = mutations.reserveBound(authorized.scope, mutation, operationId, authorized.authorizedAt)
            when (reserved.status) {
                AgentWebMutationReserveStatus.CONFLICT -> throw AgentWebConflictException()
                AgentWebMutationReserveStatus.REPLAY -> replayDecision(authorized.scope, reserved.record)
                AgentWebMutationReserveStatus.CREATED -> {
                    // This is the authoritative reload immediately before the atomic one-time consume.
                    val authoritative = confirmations.load(authorized.scope, requestId)
                        ?: throw AgentWebHiddenException()
                    if (authoritative.request.requestId != requestId) throw AgentWebHiddenException()
                    requireOwner(context, authoritative)
                    try {
                        command.requireCurrentFor(
                            authoritative.request,
                            context,
                            preconditions,
                            authoritative.stateVersion,
                            authorized.authorizedAt,
                        )
                    } catch (_: IllegalArgumentException) {
                        if (authorized.authorizedAt >= authoritative.request.expiresAt ||
                            authorized.authorizedAt >= authoritative.request.authorizationExpiresAt
                        ) throw AgentWebExpiredException()
                        throw AgentWebPreconditionException()
                    }
                    val decision = if (expectedOutcome == AgentApprovalOutcome.APPROVED) {
                        AgentApprovalDecision.approve(
                            security.nextId("agent-approval-decision"),
                            authoritative.request,
                            context.principalId,
                            context.principalType,
                            authorized.authorizedAt,
                            command.reasonCode,
                        )
                    } else {
                        AgentApprovalDecision.reject(
                            security.nextId("agent-approval-decision"),
                            authoritative.request,
                            context.principalId,
                            context.principalType,
                            authorized.authorizedAt,
                            requireNotNull(command.reasonCode),
                        )
                    }
                    decision.requireValidFor(authoritative.request, authorized.authorizedAt)
                    val consumed = confirmations.consume(
                        authorized.scope,
                        requestId,
                        authoritative.stateVersion,
                        mutation,
                        decision,
                        authorized.authorizedAt,
                    )
                    when (consumed.status) {
                        AgentWebConfirmationConsumeStatus.APPLIED -> {
                            val record = requireConsumedRecord(
                                consumed,
                                authoritative,
                                mutation,
                                decision,
                                exactDecision = true,
                                atTime = authorized.authorizedAt,
                            )
                            appendOutbox(
                                authorized.scope,
                                operationId,
                                requestId,
                                "agent.confirmation.decision.requested",
                                commandDigest,
                                authorized.authorizedAt,
                            )
                            PreparedDecision(record, decision, operationId, mutation)
                        }
                        AgentWebConfirmationConsumeStatus.REPLAYED -> {
                            val record = requireConsumedRecord(
                                consumed,
                                authoritative,
                                mutation,
                                decision,
                                exactDecision = false,
                                atTime = authorized.authorizedAt,
                            )
                            val projection = record.decisionProjection() ?: throw AgentWebOutcomeUnknownException()
                            ReplayDecision(AgentWebApplicationResult.success(projection, true))
                        }
                        AgentWebConfirmationConsumeStatus.ALREADY_DECIDED -> throw AgentWebAlreadyDecidedException()
                        AgentWebConfirmationConsumeStatus.VERSION_CONFLICT -> throw AgentWebPreconditionException()
                        AgentWebConfirmationConsumeStatus.MISSING -> throw AgentWebHiddenException()
                    }
                }
            }
        })
        if (prepared is ReplayDecision) return@agentWebApplicationCall prepared.result
        val decision = prepared as PreparedDecision
        val commandContext = AgentRunCommandContext(
            context.tenantId,
            context.principalId,
            context.principalType,
            context.requestId,
            authorized.authorizedAt,
        )
        val useCaseResult = try {
            approvalUseCases.decide(commandContext, decision.record.runStateVersion, decision.decision)
        } catch (_: RuntimeException) {
            AgentWebUseCaseResult.outcomeUnknown<AgentWebRunUseCaseReceipt>("agent.confirmation.outcome-unknown")
        }
        when (useCaseResult.outcome) {
            AgentWebUseCaseOutcome.SUCCEEDED -> {
                val receipt = requireNotNull(useCaseResult.value)
                if (!(receipt.snapshot.runId == decision.decision.runId &&
                    receipt.snapshot.tenantId == context.tenantId
                )) {
                    markUnknown(authorized.scope, decision, "agent.confirmation.receipt-mismatch")
                    throw AgentWebOutcomeUnknownException()
                }
                try {
                    transactions.inTransaction(AgentWebTransactionWork {
                        val currentRun = runs.find(authorized.scope, receipt.snapshot.runId)
                            ?: throw AgentWebHiddenException()
                        val next = AgentWebRunRecord(
                            context.tenantId,
                            currentRun.conversationId,
                            receipt.snapshot,
                            currentRun.deadlineAt,
                        )
                        val write = runs.compareAndSet(
                            authorized.scope,
                            currentRun.snapshot.stateVersion,
                            next,
                            receipt.events.map { AgentWebRunEventRecord(it.event, it.stateVersion) },
                        )
                        if (write.status != AgentWebRepositoryWriteStatus.APPLIED &&
                            write.status != AgentWebRepositoryWriteStatus.REPLAYED
                        ) throw AgentWebConflictException()
                        write.requireExact(next)
                        val atTime = security.currentTimeMillis()
                        mutations.transitionBound(
                            authorized.scope,
                            AgentWebMutationTransition(
                                decision.mutation,
                                decision.operationId,
                                AgentWebMutationStatus.RESERVED,
                                AgentWebMutationStatus.SUCCEEDED,
                                requestId,
                                decision.record.stateVersion,
                                null,
                                atTime,
                            ),
                        )
                        appendOutbox(
                            authorized.scope,
                            decision.operationId,
                            requestId,
                            "agent.confirmation.decided",
                            decision.mutation.commandDigest,
                            atTime,
                        )
                    })
                } catch (_: RuntimeException) {
                    markUnknown(authorized.scope, decision, "agent.confirmation.projection-unknown")
                    throw AgentWebOutcomeUnknownException()
                }
                AgentWebApplicationResult.success(requireNotNull(decision.record.decisionProjection()))
            }
            AgentWebUseCaseOutcome.OUTCOME_UNKNOWN -> {
                markUnknown(
                    authorized.scope,
                    decision,
                    useCaseResult.diagnosticCode ?: "agent.confirmation.outcome-unknown",
                )
                throw AgentWebOutcomeUnknownException()
            }
            AgentWebUseCaseOutcome.NOT_FOUND -> {
                closeFailure(authorized.scope, decision, requireNotNull(useCaseResult.diagnosticCode))
                throw AgentWebHiddenException()
            }
            AgentWebUseCaseOutcome.CONFLICT -> {
                closeFailure(authorized.scope, decision, requireNotNull(useCaseResult.diagnosticCode))
                throw AgentWebConflictException()
            }
            AgentWebUseCaseOutcome.REJECTED -> {
                closeFailure(authorized.scope, decision, requireNotNull(useCaseResult.diagnosticCode))
                throw AgentWebDeniedException()
            }
        }
    }

    private fun replayDecision(
        scope: AgentWebAuthorizedPersistenceScope,
        mutation: AgentWebMutationRecord,
    ): PreparedOrReplay = when (mutation.status) {
        AgentWebMutationStatus.SUCCEEDED -> {
            val resultId = requireNotNull(mutation.resultResourceId)
            if (resultId != mutation.scope.aggregateId) throw AgentWebOutcomeUnknownException()
            val record = confirmations.load(scope, resultId)
                ?: throw AgentWebOutcomeUnknownException()
            if (record.stateVersion != mutation.resultVersion) throw AgentWebOutcomeUnknownException()
            val projection = record.decisionProjection() ?: throw AgentWebOutcomeUnknownException()
            ReplayDecision(AgentWebApplicationResult.success(projection, true))
        }
        AgentWebMutationStatus.RESERVED,
        AgentWebMutationStatus.OUTCOME_UNKNOWN -> throw AgentWebOutcomeUnknownException()
        AgentWebMutationStatus.FAILED -> throw AgentWebAlreadyDecidedException()
    }

    private fun requireOwner(context: AgentWebTrustedContext, record: AgentWebConfirmationRecord) {
        val request = record.request
        if (request.tenantId != context.tenantId || request.principalId != context.principalId ||
            request.principalType != context.principalType || request.operatorId != context.principalId ||
            request.operatorType != context.principalType
        ) throw AgentWebHiddenException()
    }

    private fun confirmationCommandDigest(command: AgentWebToolConfirmationDecisionCommand): String =
        AgentWebRuntimeDigest("flowweft.agent.web.runtime.confirmation-decision.v1")
            .add(command.requestId.value)
            .add(command.proposalId.value)
            .add(command.argumentsDigest)
            .add(command.requestEvidenceDigest)
            .add(command.submissionNonce)
            .add(command.outcome.name)
            .add(command.reasonCode ?: "-")
            .finish()

    private fun requireConsumedRecord(
        result: AgentWebConfirmationConsumeResult,
        previous: AgentWebConfirmationRecord,
        mutation: AgentWebMutationScope,
        proposedDecision: AgentApprovalDecision,
        exactDecision: Boolean,
        atTime: Long,
    ): AgentWebConfirmationRecord {
        val record = result.record ?: throw AgentWebOutcomeUnknownException()
        val actualDecision = record.decision ?: throw AgentWebOutcomeUnknownException()
        val actualMutation = record.decisionMutationScope ?: throw AgentWebOutcomeUnknownException()
        try {
            actualDecision.requireValidFor(previous.request, atTime)
            val versionIsValid = if (exactDecision) {
                record.stateVersion == Math.addExact(previous.stateVersion, 1L)
            } else {
                record.stateVersion >= previous.stateVersion
            }
            if (record.request.evidenceDigest != previous.request.evidenceDigest ||
                record.risk != previous.risk || record.toolDisplayName != previous.toolDisplayName ||
                record.runStateVersion != previous.runStateVersion || !versionIsValid ||
                actualMutation.scopeDigest != mutation.scopeDigest ||
                actualMutation.commandDigest != mutation.commandDigest ||
                actualMutation.action != mutation.action || actualMutation.aggregateId != mutation.aggregateId ||
                actualDecision.outcome != proposedDecision.outcome ||
                actualDecision.reasonCode != proposedDecision.reasonCode ||
                (exactDecision && (actualDecision.decisionId != proposedDecision.decisionId ||
                    actualDecision.decidedAt != proposedDecision.decidedAt))
            ) throw AgentWebOutcomeUnknownException()
        } catch (unknown: AgentWebOutcomeUnknownException) {
            throw unknown
        } catch (_: IllegalArgumentException) {
            throw AgentWebOutcomeUnknownException()
        }
        return record
    }

    private fun markUnknown(
        scope: AgentWebAuthorizedPersistenceScope,
        prepared: PreparedDecision,
        code: String,
    ) {
        val atTime = security.currentTimeMillis()
        try {
            transactions.inTransaction(AgentWebTransactionWork {
                mutations.transitionBound(
                    scope,
                    AgentWebMutationTransition(
                        prepared.mutation, prepared.operationId, AgentWebMutationStatus.RESERVED,
                        AgentWebMutationStatus.OUTCOME_UNKNOWN, null, null, code, atTime,
                    ),
                )
                appendOutbox(scope, prepared.operationId, prepared.decision.requestId,
                    "agent.confirmation.outcome-unknown", prepared.mutation.commandDigest, atTime)
            })
        } catch (_: RuntimeException) {
            // The canonical decision remains consumed; no blind retry is permitted.
        }
    }

    private fun closeFailure(
        scope: AgentWebAuthorizedPersistenceScope,
        prepared: PreparedDecision,
        code: String,
    ) {
        val atTime = security.currentTimeMillis()
        try {
            transactions.inTransaction(AgentWebTransactionWork {
                mutations.transitionBound(
                    scope,
                    AgentWebMutationTransition(
                        prepared.mutation, prepared.operationId, AgentWebMutationStatus.RESERVED,
                        AgentWebMutationStatus.FAILED, null, null, code, atTime,
                    ),
                )
                appendOutbox(scope, prepared.operationId, prepared.decision.requestId,
                    "agent.confirmation.rejected", prepared.mutation.commandDigest, atTime)
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

    private interface PreparedOrReplay

    private class PreparedDecision(
        val record: AgentWebConfirmationRecord,
        val decision: AgentApprovalDecision,
        val operationId: Identifier,
        val mutation: AgentWebMutationScope,
    ) : PreparedOrReplay

    private class ReplayDecision(
        val result: AgentWebApplicationResult<AgentWebToolConfirmationDecisionDto>,
    ) : PreparedOrReplay
}
