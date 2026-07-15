package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.domain.WorkflowActivateHumanRuleCommand
import ai.icen.fw.workflow.domain.WorkflowAuthorizationStatus
import ai.icen.fw.workflow.domain.WorkflowCommandContext
import ai.icen.fw.workflow.domain.WorkflowCompleteEffectCommand
import ai.icen.fw.workflow.domain.WorkflowContinueCommand
import ai.icen.fw.workflow.domain.WorkflowDomainEngine
import ai.icen.fw.workflow.domain.WorkflowDomainResult
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionCode
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionCommand
import ai.icen.fw.workflow.domain.WorkflowHumanCollaborationCommand
import ai.icen.fw.workflow.domain.WorkflowIdempotencyReceipt
import ai.icen.fw.workflow.domain.WorkflowInstanceState
import ai.icen.fw.workflow.domain.WorkflowResultCode
import ai.icen.fw.workflow.domain.WorkflowStartCommand

/**
 * Java 8 durable application runtime. Authorization and any receipt issuance happen before the
 * atomic commit; provider dispatch is signalled only after the commit is known durable.
 */
class WorkflowDurableRuntime(
    private val authorizationPort: WorkflowRuntimeAuthorizationPort,
    private val persistencePort: WorkflowRuntimePersistencePort,
    private val dispatchPort: WorkflowEffectDispatchPort,
    private val collaborationAuthorizationPort: WorkflowRuntimeHumanCollaborationAuthorizationPort,
) {
    constructor(
        authorizationPort: WorkflowRuntimeAuthorizationPort,
        persistencePort: WorkflowRuntimePersistencePort,
        dispatchPort: WorkflowEffectDispatchPort,
    ) : this(
        authorizationPort,
        persistencePort,
        dispatchPort,
        UnsupportedHumanCollaborationAuthorizationPort,
    )

    fun start(request: WorkflowRuntimeStartRequest): WorkflowRuntimeResult {
        val authorization = authorize(
            request.callContext,
            request.action,
            request.instanceId,
            request.definitionId,
            request.definitionRef,
            request.subject,
            request.requestDigest,
            request.options.now,
        ) ?: return authorizationDenied()

        val snapshot = snapshot(request.callContext, request.instanceId, request.options)
            ?: return persistenceUnknown(null)
        replayOrConflict(snapshot, request.requestDigest)?.let { result -> return result }
        if (snapshot.state != null || request.options.expectedInstanceVersion != 0L) {
            return versionConflict(snapshot.state)
        }
        val definition = try {
            persistencePort.loadDefinition(
                request.callContext.tenantId,
                request.definitionId,
                request.definitionRef,
            )
        } catch (_: RuntimeException) {
            return persistenceUnknown(null)
        } ?: return notFound("definition-not-found")
        val domainResult = try {
            val command = WorkflowStartCommand.of(
                commandContext(request.callContext, request.instanceId, request.options),
                request.callContext.tenantId,
                request.instanceId,
                request.definitionId,
                request.definitionRef,
                request.subject,
                request.callContext.actor,
                definition.executionReceipt,
            )
            WorkflowDomainEngine.start(definition.index, command)
        } catch (_: IllegalArgumentException) {
            return domainRejected(null, "runtime-command-binding-invalid")
        }
        return commit(request.requestDigest, request.options, null, domainResult, null)
    }

    fun activateHumanRule(request: WorkflowRuntimeActivateHumanRuleRequest): WorkflowRuntimeResult {
        val prepared = prepare(
            request.callContext,
            request.options,
            request.instanceId,
            request.action,
            request.requestDigest,
        )
        prepared.terminal?.let { result -> return result }
        val loaded = prepared.loaded!!
        val domainResult = try {
            WorkflowDomainEngine.activateHumanRule(
                loaded.definition.index,
                loaded.state,
                WorkflowActivateHumanRuleCommand.of(
                    commandContext(request.callContext, request.instanceId, request.options),
                    request.workItemId,
                    request.receipt,
                ),
            )
        } catch (_: IllegalArgumentException) {
            return domainRejected(loaded.state, "runtime-command-binding-invalid")
        }
        val acknowledgement = request.deliveryLease?.let { lease ->
            WorkflowRuntimeEffectAcknowledgement.fenced(
                WorkflowEffectAcknowledgementKind.PARTICIPANT_RESOLUTION,
                request.receipt.effectId,
                request.receipt.effectRequestDigest,
                request.receipt.receiptDigest,
                lease.leaseId,
                lease.fencingToken,
            )
        } ?: WorkflowRuntimeEffectAcknowledgement.of(
            WorkflowEffectAcknowledgementKind.PARTICIPANT_RESOLUTION,
            request.receipt.effectId,
            request.receipt.effectRequestDigest,
            request.receipt.receiptDigest,
        )
        return commit(
            request.requestDigest,
            request.options,
            loaded.state,
            domainResult,
            acknowledgement,
        )
    }

    fun decideHumanTask(request: WorkflowRuntimeHumanDecisionRequest): WorkflowRuntimeResult {
        val prepared = prepare(
            request.callContext,
            request.options,
            request.instanceId,
            request.action,
            request.requestDigest,
        )
        prepared.terminal?.let { result -> return result }
        val loaded = prepared.loaded!!
        if (request.decision != WorkflowHumanDecisionCode.APPROVE &&
            request.decision != WorkflowHumanDecisionCode.REJECT
        ) return domainRejected(loaded.state, "human-decision-unsupported")
        val workItem = loaded.state.humanWorkItems.firstOrNull { item -> item.workItemId == request.workItemId }
            ?: return domainRejected(loaded.state, "work-item-missing")
        val authorizationRequestDigest = try {
            WorkflowHumanDecisionCommand.authorizationRequestDigest(
                request.workItemId,
                request.callContext.actor,
                request.decision,
                request.expectedWorkItemVersion,
            )
        } catch (_: IllegalArgumentException) {
            return domainRejected(loaded.state, "human-decision-unsupported")
        }
        val receipt = try {
            authorizationPort.issueHumanDecisionReceipt(
                WorkflowRuntimeHumanDecisionReceiptRequest.of(
                    request.callContext,
                    loaded.state,
                    workItem,
                    request.callContext.actor,
                    request.decision,
                    request.expectedWorkItemVersion,
                    authorizationRequestDigest,
                    request.options.now,
                ),
            )
        } catch (_: RuntimeException) {
            return authorizationDenied()
        }
        val domainResult = try {
            WorkflowDomainEngine.decideHumanTask(
                loaded.definition.index,
                loaded.state,
                WorkflowHumanDecisionCommand.of(
                    commandContext(request.callContext, request.instanceId, request.options),
                    request.workItemId,
                    request.callContext.actor,
                    request.decision,
                    request.expectedWorkItemVersion,
                    receipt,
                ),
            )
        } catch (_: IllegalArgumentException) {
            return authorizationDenied()
        }
        return commit(request.requestDigest, request.options, loaded.state, domainResult, null)
    }

    /**
     * Claims, unclaims, delegates, transfers, before-signs or returns one active task. Authorization is
     * deliberately evaluated before any instance existence read; every observable mismatch after
     * that point is projected as the same opaque authorization denial.
     */
    fun collaborateHumanTask(request: WorkflowRuntimeHumanCollaborationRequest): WorkflowRuntimeResult {
        authorize(
            request.callContext,
            request.action,
            request.instanceId,
            request.definitionId,
            request.definitionRef,
            request.subject,
            request.requestDigest,
            request.options.now,
        ) ?: return authorizationDenied()
        val snapshot = snapshot(request.callContext, request.instanceId, request.options)
            ?: return persistenceUnknown(null)
        val state = snapshot.state ?: return authorizationDenied()
        if (state.definitionId != request.definitionId ||
            state.definitionRef != request.definitionRef || state.subject != request.subject
        ) return authorizationDenied()
        val definition = try {
            persistencePort.loadDefinition(request.callContext.tenantId, state.definitionId, state.definitionRef)
        } catch (_: RuntimeException) {
            return persistenceUnknown(null)
        } ?: return authorizationDenied()
        val workItem = state.humanWorkItems.firstOrNull { it.workItemId == request.workItemId }
            ?: return authorizationDenied()
        val node = definition.index.findNode(workItem.nodeId) ?: return authorizationDenied()
        val policy = node.humanTaskPolicy ?: return authorizationDenied()
        val rule = workItem.ruleSnapshots.getOrNull(workItem.activeRuleIndex) ?: return authorizationDenied()
        if (workItem.nodeId != request.nodeId || workItem.policyDigest != request.policyDigest ||
            policy.evidenceBinding != request.evidenceBinding ||
            workItem.activeRuleIndex != request.activeRuleIndex ||
            rule.ruleDigest != request.activeRuleDigest || rule.activationDigest != request.activationDigest
        ) return authorizationDenied()
        val authorizationRequestDigest = try {
            WorkflowHumanCollaborationCommand.authorizationRequestDigest(
                request.workItemId,
                request.nodeId,
                request.policyDigest,
                request.evidenceBinding,
                request.activeRuleIndex,
                request.activeRuleDigest,
                request.activationDigest,
                request.collaborationAction,
                request.callContext.actor,
                request.target,
                request.expectedWorkItemVersion,
                request.executionNonce,
            )
        } catch (_: IllegalArgumentException) {
            return authorizationDenied()
        }
        val idempotency = snapshot.idempotency
        if (idempotency != null && !idempotency.matches(request.requestDigest, state)) {
            return replayOrConflict(snapshot, request.requestDigest)!!
        }
        if (idempotency == null && state.version != request.options.expectedInstanceVersion) {
            return versionConflict(state)
        }
        if (idempotency == null && workItem.revision != request.expectedWorkItemVersion) {
            return versionConflict(state)
        }
        val receipt = try {
            collaborationAuthorizationPort.issueHumanCollaborationReceipt(
                WorkflowRuntimeHumanCollaborationReceiptRequest.of(
                    request.callContext,
                    state,
                    workItem,
                    request,
                    authorizationRequestDigest,
                    request.options.now,
                ),
            )
        } catch (_: RuntimeException) {
            return authorizationDenied()
        }
        if (!collaborationReceiptIsCurrent(
                request,
                state,
                workItem,
                receipt,
                authorizationRequestDigest,
                request.options.now,
            )
        ) return authorizationDenied()
        if (idempotency != null) return replayOrConflict(snapshot, request.requestDigest)!!
        val domainResult = try {
            WorkflowDomainEngine.collaborateHumanTask(
                definition.index,
                state,
                WorkflowHumanCollaborationCommand.of(
                    commandContext(request.callContext, request.instanceId, request.options),
                    request.workItemId,
                    request.nodeId,
                    request.policyDigest,
                    request.evidenceBinding,
                    request.activeRuleIndex,
                    request.activeRuleDigest,
                    request.activationDigest,
                    request.collaborationAction,
                    request.callContext.actor,
                    request.target,
                    request.expectedWorkItemVersion,
                    request.executionNonce,
                    receipt,
                ),
            )
        } catch (_: IllegalArgumentException) {
            return authorizationDenied()
        }
        return commit(request.requestDigest, request.options, state, domainResult, null)
    }

    private fun collaborationReceiptIsCurrent(
        request: WorkflowRuntimeHumanCollaborationRequest,
        state: WorkflowInstanceState,
        workItem: ai.icen.fw.workflow.domain.WorkflowHumanWorkItemState,
        receipt: ai.icen.fw.workflow.domain.WorkflowHumanCollaborationAuthorizationReceipt,
        authorizationRequestDigest: String,
        now: Long,
    ): Boolean {
        val active = workItem.ruleSnapshots.getOrNull(workItem.activeRuleIndex) ?: return false
        val eligibilityMatches = when (request.collaborationAction) {
            ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction.UNCLAIM ->
                receipt.actorCurrentlyEligible || receipt.privilegedUnclaim
            ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction.DELEGATE,
            ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction.TRANSFER,
            ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction.ADD_SIGN,
            ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction.RETURN ->
                receipt.actorCurrentlyEligible && receipt.targetCurrentlyEligible &&
                    receipt.separationOfDutiesSatisfied
            ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction.CLAIM ->
                receipt.actorCurrentlyEligible && receipt.separationOfDutiesSatisfied
            else -> false
        }
        return receipt.status == WorkflowAuthorizationStatus.AUTHORIZED && eligibilityMatches &&
            receipt.tenantId == state.tenantId && receipt.instanceId == state.instanceId &&
            receipt.definitionId == state.definitionId && receipt.definitionRef == state.definitionRef &&
            receipt.subject == state.subject && receipt.workItemId == workItem.workItemId &&
            receipt.nodeId == workItem.nodeId && receipt.policyDigest == workItem.policyDigest &&
            receipt.evidenceBinding == request.evidenceBinding &&
            receipt.activeRuleIndex == workItem.activeRuleIndex &&
            receipt.activeRuleDigest == active.ruleDigest && receipt.activationDigest == active.activationDigest &&
            receipt.action == request.collaborationAction && receipt.actor == request.callContext.actor &&
            receipt.target == request.target &&
            receipt.currentClaimOwner == workItem.collaboration.claimOwner &&
            receipt.currentActiveDelegate == workItem.collaboration.activeDelegate &&
            receipt.collaborationStateDigest == workItem.collaboration.contentDigest &&
            receipt.expectedWorkItemVersion == request.expectedWorkItemVersion &&
            receipt.executionNonce == request.executionNonce &&
            receipt.authorizationRequestDigest == authorizationRequestDigest &&
            receipt.evaluatedAt >= state.updatedAt && receipt.evaluatedAt <= now && now <= receipt.validUntil
    }

    fun completeEffect(request: WorkflowRuntimeCompleteEffectRequest): WorkflowRuntimeResult {
        val prepared = prepare(
            request.callContext,
            request.options,
            request.instanceId,
            request.action,
            request.requestDigest,
        )
        prepared.terminal?.let { result -> return result }
        val loaded = prepared.loaded!!
        val domainResult = try {
            WorkflowDomainEngine.completeEffect(
                loaded.definition.index,
                loaded.state,
                WorkflowCompleteEffectCommand.of(
                    commandContext(request.callContext, request.instanceId, request.options),
                    request.receipt,
                ),
            )
        } catch (_: IllegalArgumentException) {
            return domainRejected(loaded.state, "runtime-command-binding-invalid")
        }
        val acknowledgement = WorkflowRuntimeEffectAcknowledgement.of(
            WorkflowEffectAcknowledgementKind.EXTERNAL_EFFECT,
            request.receipt.effectId,
            request.receipt.effectRequestDigest,
            request.receipt.receiptDigest,
        )
        return commit(
            request.requestDigest,
            request.options,
            loaded.state,
            domainResult,
            acknowledgement,
        )
    }

    fun continueExecution(request: WorkflowRuntimeContinueRequest): WorkflowRuntimeResult {
        val prepared = prepare(
            request.callContext,
            request.options,
            request.instanceId,
            request.action,
            request.requestDigest,
        )
        prepared.terminal?.let { result -> return result }
        val loaded = prepared.loaded!!
        val domainResult = try {
            WorkflowDomainEngine.continueExecution(
                loaded.definition.index,
                loaded.state,
                WorkflowContinueCommand.of(
                    commandContext(request.callContext, request.instanceId, request.options),
                    request.receipt,
                ),
            )
        } catch (_: IllegalArgumentException) {
            return domainRejected(loaded.state, "runtime-command-binding-invalid")
        }
        val acknowledgement = WorkflowRuntimeEffectAcknowledgement.of(
            WorkflowEffectAcknowledgementKind.CONTINUATION,
            request.receipt.effectId,
            request.receipt.requestDigest,
            request.receipt.receiptDigest,
        )
        return commit(
            request.requestDigest,
            request.options,
            loaded.state,
            domainResult,
            acknowledgement,
        )
    }

    private fun prepare(
        context: WorkflowTrustedCallContext,
        options: WorkflowRuntimeCommandOptions,
        instanceId: String,
        action: WorkflowRuntimeAction,
        requestDigest: String,
    ): Preparation {
        val snapshot = snapshot(context, instanceId, options)
            ?: return Preparation(null, persistenceUnknown(null))
        // The detailed authorization resource is stored with the instance. Missing instances are
        // deliberately indistinguishable from inaccessible instances to prevent identifier probes.
        val state = snapshot.state ?: return Preparation(null, authorizationDenied())
        val authorization = authorize(
            context,
            action,
            instanceId,
            state.definitionId,
            state.definitionRef,
            state.subject,
            requestDigest,
            options.now,
        ) ?: return Preparation(null, authorizationDenied())
        replayOrConflict(snapshot, requestDigest)?.let { result -> return Preparation(null, result) }
        if (state.version != options.expectedInstanceVersion) {
            return Preparation(null, versionConflict(state))
        }
        val definition = try {
            persistencePort.loadDefinition(context.tenantId, state.definitionId, state.definitionRef)
        } catch (_: RuntimeException) {
            return Preparation(null, persistenceUnknown(state))
        } ?: return Preparation(null, authorizationDenied())
        return Preparation(LoadedCommand(state, definition, authorization), null)
    }

    private fun snapshot(
        context: WorkflowTrustedCallContext,
        instanceId: String,
        options: WorkflowRuntimeCommandOptions,
    ): WorkflowRuntimeCommandSnapshot? = try {
        persistencePort.loadCommandSnapshot(
            context.tenantId,
            instanceId,
            options.idempotencyKey,
            options.now,
        )
    } catch (_: RuntimeException) {
        null
    }

    private fun authorize(
        context: WorkflowTrustedCallContext,
        action: WorkflowRuntimeAction,
        instanceId: String,
        definitionId: String,
        definitionRef: ai.icen.fw.workflow.api.WorkflowDefinitionRef,
        subject: ai.icen.fw.workflow.api.WorkflowSubjectSnapshot,
        requestDigest: String,
        now: Long,
    ): WorkflowRuntimeAuthorizationDecision? {
        val request = WorkflowRuntimeAuthorizationRequest.of(
            context,
            action,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            requestDigest,
            now,
        )
        return try {
            authorizationPort.authorize(request).takeIf { decision ->
                decision.status == WorkflowRuntimeAuthorizationStatus.AUTHORIZED && decision.matches(request, now)
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun commandContext(
        context: WorkflowTrustedCallContext,
        instanceId: String,
        options: WorkflowRuntimeCommandOptions,
    ): WorkflowCommandContext = WorkflowCommandContext.of(
        options.commandId,
        options.idempotencyKey,
        options.expectedInstanceVersion,
        options.now,
        options.iterationBudget,
        options.ids,
        WorkflowIdempotencyReceipt.fresh(
            context.tenantId,
            instanceId,
            options.idempotencyKey,
            options.now,
        ),
    )

    private fun replayOrConflict(
        snapshot: WorkflowRuntimeCommandSnapshot,
        logicalRequestDigest: String,
    ): WorkflowRuntimeResult? {
        val record = snapshot.idempotency ?: return null
        return if (record.matches(logicalRequestDigest, snapshot.state)) {
            durableWithDispatch(
                WorkflowRuntimeResultCode.REPLAYED,
                snapshot.state!!,
                record.domainResultCode,
                null,
                record.effectCount,
                WorkflowDispatchReason.REPLAY_RECOVERY,
            )
        } else {
            WorkflowRuntimeResult.failed(
                WorkflowRuntimeResultCode.IDEMPOTENCY_CONFLICT,
                snapshot.state,
                null,
                "idempotency-conflict",
            )
        }
    }

    private fun commit(
        logicalRequestDigest: String,
        options: WorkflowRuntimeCommandOptions,
        previousState: WorkflowInstanceState?,
        domainResult: WorkflowDomainResult,
        acknowledgement: WorkflowRuntimeEffectAcknowledgement?,
    ): WorkflowRuntimeResult {
        when (domainResult.code) {
            WorkflowResultCode.VERSION_CONFLICT -> return versionConflict(domainResult.state)
            WorkflowResultCode.REJECTED -> return domainRejected(
                domainResult.state,
                domainResult.failureCode ?: "domain-rejected",
            )
            WorkflowResultCode.REPLAYED -> return WorkflowRuntimeResult.failed(
                WorkflowRuntimeResultCode.IDEMPOTENCY_CONFLICT,
                domainResult.state,
                domainResult.code,
                "unexpected-domain-replay",
            )
        }
        val request = try {
            WorkflowRuntimeAtomicCommit.fromDomain(
                domainResult,
                logicalRequestDigest,
                options.expectedInstanceVersion,
                previousState?.stateDigest,
                acknowledgement,
                options.now,
            )
        } catch (_: IllegalArgumentException) {
            return domainRejected(previousState, "atomic-commit-binding-invalid")
        }
        val committed = try {
            persistencePort.commit(request)
        } catch (_: RuntimeException) {
            return WorkflowRuntimeResult.failed(
                WorkflowRuntimeResultCode.COMMIT_OUTCOME_UNKNOWN,
                previousState,
                domainResult.code,
                "commit-outcome-unknown",
            )
        }
        return when (committed.code) {
            WorkflowRuntimeCommitCode.COMMITTED -> durableWithDispatch(
                WorkflowRuntimeResultCode.COMMITTED,
                request.state,
                domainResult.code,
                domainResult.failureCode,
                request.effects.size,
                WorkflowDispatchReason.NEW_COMMIT,
            )
            WorkflowRuntimeCommitCode.VERSION_CONFLICT -> versionConflict(previousState)
            WorkflowRuntimeCommitCode.EFFECT_CONFLICT -> WorkflowRuntimeResult.failed(
                WorkflowRuntimeResultCode.EFFECT_CONFLICT,
                previousState,
                domainResult.code,
                "effect-acknowledgement-conflict",
            )
            WorkflowRuntimeCommitCode.IDEMPOTENCY_CONFLICT -> resolveCommitRace(
                request,
                logicalRequestDigest,
                options.now,
            )
            else -> WorkflowRuntimeResult.failed(
                WorkflowRuntimeResultCode.COMMIT_OUTCOME_UNKNOWN,
                previousState,
                domainResult.code,
                "unknown-commit-result",
            )
        }
    }

    private fun resolveCommitRace(
        commit: WorkflowRuntimeAtomicCommit,
        logicalRequestDigest: String,
        now: Long,
    ): WorkflowRuntimeResult {
        val snapshot = try {
            persistencePort.loadCommandSnapshot(
                commit.tenantId,
                commit.instanceId,
                commit.idempotency.idempotencyKey,
                now,
            )
        } catch (_: RuntimeException) {
            return persistenceUnknown(null)
        }
        val record = snapshot.idempotency
        return if (record != null && record.matches(logicalRequestDigest, snapshot.state)) {
            durableWithDispatch(
                WorkflowRuntimeResultCode.REPLAYED,
                snapshot.state!!,
                record.domainResultCode,
                null,
                record.effectCount,
                WorkflowDispatchReason.REPLAY_RECOVERY,
            )
        } else {
            WorkflowRuntimeResult.failed(
                WorkflowRuntimeResultCode.IDEMPOTENCY_CONFLICT,
                snapshot.state,
                null,
                "idempotency-conflict",
            )
        }
    }

    private fun durableWithDispatch(
        code: WorkflowRuntimeResultCode,
        state: WorkflowInstanceState,
        domainCode: WorkflowResultCode?,
        failureCode: String?,
        effectCount: Int,
        reason: WorkflowDispatchReason,
    ): WorkflowRuntimeResult {
        if (effectCount > 0) {
            try {
                dispatchPort.signal(
                    WorkflowEffectDispatchSignal.of(
                        state.tenantId,
                        state.instanceId,
                        state.version,
                        effectCount,
                        reason,
                    ),
                )
            } catch (_: RuntimeException) {
                return WorkflowRuntimeResult.durable(
                    WorkflowRuntimeResultCode.COMMITTED_DISPATCH_DEFERRED,
                    state,
                    domainCode,
                    "dispatch-signal-deferred",
                )
            }
        }
        return WorkflowRuntimeResult.durable(code, state, domainCode, failureCode)
    }

    private fun authorizationDenied(): WorkflowRuntimeResult = WorkflowRuntimeResult.failed(
        WorkflowRuntimeResultCode.AUTHORIZATION_DENIED,
        null,
        null,
        "authorization-denied",
    )

    private fun notFound(code: String): WorkflowRuntimeResult = WorkflowRuntimeResult.failed(
        WorkflowRuntimeResultCode.NOT_FOUND,
        null,
        null,
        code,
    )

    private fun versionConflict(state: WorkflowInstanceState?): WorkflowRuntimeResult = WorkflowRuntimeResult.failed(
        WorkflowRuntimeResultCode.VERSION_CONFLICT,
        state,
        WorkflowResultCode.VERSION_CONFLICT,
        "version-conflict",
    )

    private fun domainRejected(state: WorkflowInstanceState?, code: String): WorkflowRuntimeResult =
        WorkflowRuntimeResult.failed(
            WorkflowRuntimeResultCode.DOMAIN_REJECTED,
            state,
            WorkflowResultCode.REJECTED,
            code,
        )

    private fun persistenceUnknown(state: WorkflowInstanceState?): WorkflowRuntimeResult = WorkflowRuntimeResult.failed(
        WorkflowRuntimeResultCode.COMMIT_OUTCOME_UNKNOWN,
        state,
        null,
        "persistence-outcome-unknown",
    )

    private class LoadedCommand(
        val state: WorkflowInstanceState,
        val definition: WorkflowRuntimeDefinitionRecord,
        @Suppress("unused") val authorization: WorkflowRuntimeAuthorizationDecision,
    )

    private class Preparation(
        val loaded: LoadedCommand?,
        val terminal: WorkflowRuntimeResult?,
    )
}

private object UnsupportedHumanCollaborationAuthorizationPort :
    WorkflowRuntimeHumanCollaborationAuthorizationPort {
    override fun issueHumanCollaborationReceipt(
        request: WorkflowRuntimeHumanCollaborationReceiptRequest,
    ): ai.icen.fw.workflow.domain.WorkflowHumanCollaborationAuthorizationReceipt {
        throw UnsupportedOperationException("Human collaboration authorization is not configured.")
    }
}
