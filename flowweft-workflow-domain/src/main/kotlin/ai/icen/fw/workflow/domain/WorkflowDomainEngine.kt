package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowApprovalMode
import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowHumanTaskParticipantRule
import ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction
import ai.icen.fw.workflow.api.WorkflowNodeDefinition
import ai.icen.fw.workflow.api.WorkflowNodeKind
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.api.WorkflowTransitionDefinition
import ai.icen.fw.workflow.api.WorkflowTransitionTrigger

/**
 * Pure deterministic workflow state machine.
 *
 * Every method consumes only explicit definitions, immutable state, command inputs, ids, times and
 * trusted receipts. It performs no persistence, organization lookup, authorization call, clock or
 * external node execution. Returned effects must be durably persisted before dispatch.
 */
class WorkflowDomainEngine private constructor() {
    companion object {
        @JvmStatic
        fun start(
            index: WorkflowDefinitionIndex,
            command: WorkflowStartCommand,
        ): WorkflowDomainResult {
            val failure = validateStart(index, command)
            if (failure != null) return rejected(command.code, command.commandDigest, command.context, null, failure)
            return try {
                val machine = DomainMachine.start(index, command)
                machine.advance()
                machine.result()
            } catch (failureException: DomainFailure) {
                rejected(command.code, command.commandDigest, command.context, null, failureException.code)
            }
        }

        @JvmStatic
        fun activateHumanRule(
            index: WorkflowDefinitionIndex,
            state: WorkflowInstanceState,
            command: WorkflowActivateHumanRuleCommand,
        ): WorkflowDomainResult {
            val checked = precheck(index, state, command.code, command.commandDigest, command.context)
            if (checked != null) return checked
            val workItem = state.humanWorkItems.firstOrNull { item -> item.workItemId == command.workItemId }
                ?: return rejected(command.code, command.commandDigest, command.context, state, FAILURE_WORK_ITEM_MISSING)
            if (workItem.status != WorkflowHumanWorkItemStatus.WAITING_PARTICIPANTS) {
                return rejected(command.code, command.commandDigest, command.context, state, FAILURE_WORK_ITEM_STATE)
            }
            val execution = state.nodeExecutions.first { item -> item.executionId == workItem.nodeExecutionId }
            val token = state.tokens.first { item -> item.tokenId == workItem.tokenId }
            val node = index.node(workItem.nodeId)
            val policy = node.humanTaskPolicy
                ?: return rejected(command.code, command.commandDigest, command.context, state, FAILURE_STATE_BINDING)
            val rule = policy.participantRules.getOrNull(workItem.activeRuleIndex)
                ?: return rejected(command.code, command.commandDigest, command.context, state, FAILURE_STATE_BINDING)
            val receipt = command.receipt
            if (!participantReceiptMatches(state, workItem, execution, token, rule, receipt) ||
                receipt.resolvedAt < state.updatedAt ||
                command.context.now < receipt.resolvedAt || command.context.now > receipt.validUntil
            ) {
                return rejected(command.code, command.commandDigest, command.context, state, FAILURE_RECEIPT_MISMATCH)
            }

            return try {
                val machine = DomainMachine.fromState(index, state, command.code, command.commandDigest, command.context)
                val denominator = receipt.candidates.size
                val required = requiredApprovals(rule, denominator)
                if (denominator == 0 || required == null || required > denominator) {
                    machine.raiseIncident(
                        token.tokenId,
                        execution.executionId,
                        workItem.workItemId,
                        FAILURE_QUORUM_IMPOSSIBLE,
                    )
                } else {
                    val snapshot = if (receipt.evidenceVersion >= 3) {
                        WorkflowHumanRuleSnapshot.organizationDoubleChecked(
                            workItem.activeRuleIndex,
                            rule.contentDigest,
                            rule.selector.digest,
                            rule.approvalPolicy.mode,
                            denominator,
                            required,
                            receipt.candidates,
                            receipt.resolutionDigest,
                            receipt.receiptDigest,
                            requireNotNull(receipt.organizationAuthority),
                            requireNotNull(receipt.organizationSnapshotRevision),
                            requireNotNull(receipt.resolutionRequestDigest),
                            requireNotNull(receipt.organizationProviderRevision),
                            requireNotNull(receipt.organizationSnapshotDigest),
                            requireNotNull(receipt.organizationSnapshotReceiptDigest),
                            requireNotNull(receipt.organizationConfirmationRevision),
                            requireNotNull(receipt.organizationConfirmationSnapshotDigest),
                            requireNotNull(receipt.organizationConfirmationRequestDigest),
                            requireNotNull(receipt.organizationConfirmationReceiptDigest),
                            receipt.resolvedAt,
                        )
                    } else if (receipt.hasOrganizationEvidence) {
                        WorkflowHumanRuleSnapshot.organizationBound(
                            workItem.activeRuleIndex,
                            rule.contentDigest,
                            rule.selector.digest,
                            rule.approvalPolicy.mode,
                            denominator,
                            required,
                            receipt.candidates,
                            receipt.resolutionDigest,
                            receipt.receiptDigest,
                            requireNotNull(receipt.organizationAuthority),
                            requireNotNull(receipt.organizationSnapshotRevision),
                            requireNotNull(receipt.resolutionRequestDigest),
                            receipt.resolvedAt,
                        )
                    } else {
                        WorkflowHumanRuleSnapshot.of(
                            workItem.activeRuleIndex,
                            rule.contentDigest,
                            rule.selector.digest,
                            rule.approvalPolicy.mode,
                            denominator,
                            required,
                            receipt.candidates,
                            receipt.resolutionDigest,
                            receipt.receiptDigest,
                            receipt.resolvedAt,
                        )
                    }
                    machine.activateHumanRule(workItem, execution, snapshot, receipt.receiptDigest)
                }
                machine.result()
            } catch (failureException: DomainFailure) {
                rejected(command.code, command.commandDigest, command.context, state, failureException.code)
            }
        }

        @JvmStatic
        fun decideHumanTask(
            index: WorkflowDefinitionIndex,
            state: WorkflowInstanceState,
            command: WorkflowHumanDecisionCommand,
        ): WorkflowDomainResult {
            val checked = precheck(index, state, command.code, command.commandDigest, command.context)
            if (checked != null) return checked
            val workItem = state.humanWorkItems.firstOrNull { item -> item.workItemId == command.workItemId }
                ?: return rejected(command.code, command.commandDigest, command.context, state, FAILURE_WORK_ITEM_MISSING)
            if (workItem.revision != command.expectedWorkItemVersion) {
                return versionConflict(command.code, command.commandDigest, command.context, state)
            }
            if (workItem.status != WorkflowHumanWorkItemStatus.ACTIVE) {
                return rejected(command.code, command.commandDigest, command.context, state, FAILURE_WORK_ITEM_STATE)
            }
            val snapshot = workItem.ruleSnapshots.getOrNull(workItem.activeRuleIndex)
                ?: return rejected(command.code, command.commandDigest, command.context, state, FAILURE_STATE_BINDING)
            val receipt = command.authorizationReceipt
            if (!decisionReceiptMatches(state, workItem, snapshot, command, receipt) ||
                receipt.evaluatedAt < state.updatedAt ||
                command.context.now < receipt.evaluatedAt || command.context.now > receipt.validUntil ||
                receipt.status != WorkflowAuthorizationStatus.AUTHORIZED
            ) {
                return rejected(command.code, command.commandDigest, command.context, state, FAILURE_AUTHORIZATION)
            }
            if (!snapshot.candidates.contains(command.actor)) {
                return rejected(command.code, command.commandDigest, command.context, state, FAILURE_NOT_CANDIDATE)
            }
            if (workItem.collaboration.effectiveActor != null &&
                workItem.collaboration.effectiveActor != command.actor
            ) {
                return rejected(command.code, command.commandDigest, command.context, state, FAILURE_NOT_CLAIM_OWNER)
            }
            if (workItem.decisions.any { decision ->
                decision.ruleIndex == workItem.activeRuleIndex && decision.actor == command.actor
            }) {
                return rejected(command.code, command.commandDigest, command.context, state, FAILURE_DUPLICATE_DECISION)
            }
            return try {
                val machine = DomainMachine.fromState(index, state, command.code, command.commandDigest, command.context)
                machine.recordHumanDecision(workItem, snapshot, command)
                machine.result()
            } catch (failureException: DomainFailure) {
                rejected(command.code, command.commandDigest, command.context, state, failureException.code)
            }
        }

        /** Applies one authorization-bound human-task claim/delegation transition. */
        @JvmStatic
        fun collaborateHumanTask(
            index: WorkflowDefinitionIndex,
            state: WorkflowInstanceState,
            command: WorkflowHumanCollaborationCommand,
        ): WorkflowDomainResult {
            val checked = precheck(index, state, command.code, command.commandDigest, command.context)
            if (checked != null) return checked
            val workItem = state.humanWorkItems.firstOrNull { it.workItemId == command.workItemId }
                ?: return rejected(command.code, command.commandDigest, command.context, state, FAILURE_WORK_ITEM_MISSING)
            if (workItem.revision != command.expectedWorkItemVersion) {
                return versionConflict(command.code, command.commandDigest, command.context, state)
            }
            if (workItem.status != WorkflowHumanWorkItemStatus.ACTIVE) {
                return rejected(command.code, command.commandDigest, command.context, state, FAILURE_WORK_ITEM_STATE)
            }
            val node = index.findNode(workItem.nodeId)
                ?: return rejected(command.code, command.commandDigest, command.context, state, FAILURE_STATE_BINDING)
            val policy = node.humanTaskPolicy
                ?: return rejected(command.code, command.commandDigest, command.context, state, FAILURE_STATE_BINDING)
            val snapshot = workItem.ruleSnapshots.getOrNull(workItem.activeRuleIndex)
                ?: return rejected(command.code, command.commandDigest, command.context, state, FAILURE_STATE_BINDING)
            val receipt = command.authorizationReceipt
            if (!collaborationReceiptMatches(state, workItem, snapshot, policy.evidenceBinding, command, receipt) ||
                receipt.evaluatedAt < state.updatedAt ||
                command.context.now < receipt.evaluatedAt || command.context.now > receipt.validUntil ||
                receipt.status != WorkflowAuthorizationStatus.AUTHORIZED ||
                workItem.collaboration.hasConsumedNonce(command.executionNonce)
            ) {
                return rejected(command.code, command.commandDigest, command.context, state, FAILURE_AUTHORIZATION)
            }
            val actorEligible = snapshot.candidates.contains(command.actor) &&
                satisfiesSeparationOfDuties(state, workItem, policy.separationOfDuties, command.actor)
            val targetEligible = command.target?.let { target ->
                snapshot.candidates.contains(target) &&
                    satisfiesSeparationOfDuties(state, workItem, policy.separationOfDuties, target)
            } ?: false
            val semanticFailure = when (command.action) {
                WorkflowHumanCollaborationAction.CLAIM -> when {
                    !policy.capabilities.claimEnabled -> FAILURE_COLLABORATION_DISABLED
                    workItem.collaboration.claimOwner != null -> FAILURE_ALREADY_CLAIMED
                    !actorEligible || !receipt.actorCurrentlyEligible || !receipt.separationOfDutiesSatisfied ->
                        FAILURE_NOT_CANDIDATE
                    else -> null
                }
                WorkflowHumanCollaborationAction.UNCLAIM -> when {
                    !policy.capabilities.claimEnabled -> FAILURE_COLLABORATION_DISABLED
                    workItem.collaboration.claimOwner == null -> FAILURE_NOT_CLAIMED
                    command.actor != workItem.collaboration.claimOwner && !receipt.privilegedUnclaim ->
                        FAILURE_NOT_CLAIM_OWNER
                    else -> null
                }
                WorkflowHumanCollaborationAction.DELEGATE -> when {
                    !policy.capabilities.delegationEnabled -> FAILURE_COLLABORATION_DISABLED
                    workItem.collaboration.effectiveActor != command.actor -> FAILURE_NOT_CLAIM_OWNER
                    !actorEligible || !targetEligible || !receipt.actorCurrentlyEligible ||
                        !receipt.targetCurrentlyEligible || !receipt.separationOfDutiesSatisfied -> FAILURE_NOT_CANDIDATE
                    else -> null
                }
                WorkflowHumanCollaborationAction.TRANSFER -> when {
                    !policy.capabilities.transferEnabled -> FAILURE_COLLABORATION_DISABLED
                    workItem.collaboration.effectiveActor != command.actor -> FAILURE_NOT_CLAIM_OWNER
                    !actorEligible || !targetEligible || !receipt.actorCurrentlyEligible ||
                    !receipt.targetCurrentlyEligible || !receipt.separationOfDutiesSatisfied -> FAILURE_NOT_CANDIDATE
                    else -> null
                }
                WorkflowHumanCollaborationAction.ADD_SIGN -> when {
                    !policy.capabilities.addSignEnabled -> FAILURE_COLLABORATION_DISABLED
                    workItem.collaboration.claimOwner == null -> FAILURE_NOT_CLAIMED
                    workItem.collaboration.effectiveActor != command.actor -> FAILURE_NOT_CLAIM_OWNER
                    !actorEligible || !targetEligible || !receipt.actorCurrentlyEligible ||
                        !receipt.targetCurrentlyEligible || !receipt.separationOfDutiesSatisfied -> FAILURE_NOT_CANDIDATE
                    workItem.decisions.any { it.ruleIndex == workItem.activeRuleIndex &&
                        (it.actor == command.actor || it.actor == command.target)
                    } -> FAILURE_ADD_SIGN_DECISION_CONFLICT
                    else -> null
                }
                WorkflowHumanCollaborationAction.RETURN -> {
                    val frame = workItem.collaboration.addSignFrames.lastOrNull()
                    when {
                        !policy.capabilities.addSignEnabled -> FAILURE_COLLABORATION_DISABLED
                        frame == null -> FAILURE_RETURN_WITHOUT_ADD_SIGN
                        workItem.collaboration.effectiveActor != command.actor || frame.signer != command.actor ||
                            frame.inviter != command.target -> FAILURE_NOT_CLAIM_OWNER
                        !actorEligible || !targetEligible || !receipt.actorCurrentlyEligible ||
                            !receipt.targetCurrentlyEligible || !receipt.separationOfDutiesSatisfied ->
                            FAILURE_NOT_CANDIDATE
                        workItem.decisions.none { it.ruleIndex == workItem.activeRuleIndex &&
                            it.actor == command.actor && it.decision == WorkflowHumanDecisionCode.APPROVE &&
                            it.decidedAt >= frame.addedAt
                        } -> FAILURE_ADD_SIGN_DECISION_REQUIRED
                        else -> null
                    }
                }
                else -> FAILURE_COLLABORATION_UNSUPPORTED
            }
            if (semanticFailure != null) {
                return rejected(command.code, command.commandDigest, command.context, state, semanticFailure)
            }
            val eventId = command.context.ids.eventIds.firstOrNull()
                ?: return rejected(command.code, command.commandDigest, command.context, state, FAILURE_IDENTIFIER_BUDGET)
            val collaboration = try {
                workItem.collaboration.transition(
                    eventId,
                    command.action,
                    command.actor,
                    command.target,
                    receipt.receiptDigest,
                    command.executionNonce,
                    command.context.now,
                )
            } catch (_: IllegalArgumentException) {
                return rejected(command.code, command.commandDigest, command.context, state, FAILURE_COLLABORATION_CONFLICT)
            }
            val updatedItem = WorkflowHumanWorkItemState.restore(
                workItem.workItemId,
                workItem.nodeExecutionId,
                workItem.tokenId,
                workItem.nodeId,
                workItem.policyDigest,
                workItem.status,
                workItem.activeRuleIndex,
                workItem.ruleSnapshots,
                workItem.decisions,
                collaboration,
                workItem.revision + 1L,
                workItem.createdAt,
                command.context.now,
            )
            val updatedState = WorkflowInstanceState.restore(
                state.tenantId,
                state.instanceId,
                state.definitionId,
                state.definitionRef,
                state.subject,
                state.initiator,
                state.status,
                state.version + 1L,
                state.createdAt,
                command.context.now,
                state.tokens,
                state.nodeExecutions,
                state.humanWorkItems.map { if (it.workItemId == updatedItem.workItemId) updatedItem else it },
                state.pendingContinuationEffectId,
                state.pendingContinuationRequestDigest,
            )
            val eventCode = when (command.action) {
                WorkflowHumanCollaborationAction.CLAIM -> WorkflowEventCode.HUMAN_TASK_CLAIMED
                WorkflowHumanCollaborationAction.UNCLAIM -> WorkflowEventCode.HUMAN_TASK_UNCLAIMED
                WorkflowHumanCollaborationAction.DELEGATE -> WorkflowEventCode.HUMAN_TASK_DELEGATED
                WorkflowHumanCollaborationAction.TRANSFER -> WorkflowEventCode.HUMAN_TASK_TRANSFERRED
                WorkflowHumanCollaborationAction.ADD_SIGN -> WorkflowEventCode.HUMAN_TASK_ADD_SIGNED
                WorkflowHumanCollaborationAction.RETURN -> WorkflowEventCode.HUMAN_TASK_RETURNED
                else -> return rejected(
                    command.code,
                    command.commandDigest,
                    command.context,
                    state,
                    FAILURE_COLLABORATION_UNSUPPORTED,
                )
            }
            val event = WorkflowDomainEvent.of(
                eventId,
                eventCode,
                state.tenantId,
                state.instanceId,
                state.definitionId,
                state.definitionRef,
                state.subject,
                workItem.tokenId,
                workItem.nodeExecutionId,
                workItem.workItemId,
                workItem.nodeId,
                collaboration.records.last().contentDigest,
                command.context.now,
                updatedState.version,
            )
            return WorkflowDomainResult.of(
                WorkflowResultCode.APPLIED,
                command.code,
                command.commandDigest,
                command.context.idempotencyKey,
                updatedState,
                listOf(event),
                emptyList(),
                null,
            )
        }

        @JvmStatic
        fun completeEffect(
            index: WorkflowDefinitionIndex,
            state: WorkflowInstanceState,
            command: WorkflowCompleteEffectCommand,
        ): WorkflowDomainResult {
            val checked = precheck(index, state, command.code, command.commandDigest, command.context)
            if (checked != null) return checked
            val receipt = command.receipt
            val execution = state.nodeExecutions.firstOrNull { item -> item.executionId == receipt.nodeExecutionId }
                ?: return rejected(command.code, command.commandDigest, command.context, state, FAILURE_EXECUTION_MISSING)
            val token = state.tokens.firstOrNull { item -> item.tokenId == receipt.tokenId }
                ?: return rejected(command.code, command.commandDigest, command.context, state, FAILURE_STATE_BINDING)
            if (!effectReceiptMatches(state, execution, token, receipt) ||
                receipt.completedAt < state.updatedAt || command.context.now < receipt.completedAt
            ) {
                return rejected(command.code, command.commandDigest, command.context, state, FAILURE_RECEIPT_MISMATCH)
            }
            if (receipt.effectCode == WorkflowEffectCode.PARTICIPANT_RESOLUTION ||
                receipt.effectCode == WorkflowEffectCode.CONTINUE_EXECUTION
            ) {
                return rejected(command.code, command.commandDigest, command.context, state, FAILURE_EFFECT_KIND)
            }
            return try {
                val machine = DomainMachine.fromState(index, state, command.code, command.commandDigest, command.context)
                machine.completeEffect(execution, token, receipt)
                machine.result()
            } catch (failureException: DomainFailure) {
                rejected(command.code, command.commandDigest, command.context, state, failureException.code)
            }
        }

        @JvmStatic
        fun continueExecution(
            index: WorkflowDefinitionIndex,
            state: WorkflowInstanceState,
            command: WorkflowContinueCommand,
        ): WorkflowDomainResult {
            val checked = precheck(index, state, command.code, command.commandDigest, command.context)
            if (checked != null) return checked
            val receipt = command.receipt
            if (state.pendingContinuationEffectId == null ||
                receipt.effectId != state.pendingContinuationEffectId ||
                receipt.requestDigest != state.pendingContinuationRequestDigest ||
                !commonReceiptMatches(
                    state,
                    receipt.tenantId,
                    receipt.instanceId,
                    receipt.definitionId,
                    receipt.definitionRef,
                    receipt.subject,
                ) ||
                receipt.completedAt < state.updatedAt ||
                command.context.now < receipt.completedAt
            ) {
                return rejected(command.code, command.commandDigest, command.context, state, FAILURE_RECEIPT_MISMATCH)
            }
            return try {
                val machine = DomainMachine.fromState(index, state, command.code, command.commandDigest, command.context)
                machine.completeContinuation(receipt)
                machine.advance()
                machine.result()
            } catch (failureException: DomainFailure) {
                rejected(command.code, command.commandDigest, command.context, state, failureException.code)
            }
        }

        private fun validateStart(index: WorkflowDefinitionIndex, command: WorkflowStartCommand): String? {
            val definition = index.definition
            val receipt = command.executionReceipt
            if (definition.schemaVersion != 1) return FAILURE_UNSUPPORTED_SCHEMA
            if (command.context.expectedInstanceVersion != 0L ||
                command.context.idempotencyReceipt.status != WorkflowIdempotencyStatus.FRESH
            ) return FAILURE_START_VERSION
            if (command.context.idempotencyReceipt.tenantId != command.tenantId ||
                command.context.idempotencyReceipt.instanceId != command.instanceId
            ) return FAILURE_IDEMPOTENCY_BINDING
            if (command.tenantId != definition.tenantId ||
                command.definitionId != definition.definitionId ||
                command.definitionRef != definition.ref
            ) return FAILURE_DEFINITION_BINDING
            if (receipt.tenantId != command.tenantId ||
                receipt.definitionId != command.definitionId ||
                receipt.definitionRef != command.definitionRef ||
                receipt.schemaVersion != definition.schemaVersion ||
                command.context.now < receipt.acceptedAt ||
                command.context.now > receipt.validUntil
            ) return FAILURE_DEPLOYMENT_RECEIPT
            return null
        }

        private fun precheck(
            index: WorkflowDefinitionIndex,
            state: WorkflowInstanceState,
            commandCode: WorkflowCommandCode,
            commandDigest: String,
            context: WorkflowCommandContext,
        ): WorkflowDomainResult? {
            val bindingFailure = validateStateBinding(index, state)
            if (bindingFailure != null) {
                return rejected(commandCode, commandDigest, context, state, bindingFailure)
            }
            val receipt = context.idempotencyReceipt
            if (receipt.tenantId != state.tenantId ||
                receipt.instanceId != state.instanceId ||
                receipt.idempotencyKey != context.idempotencyKey
            ) return rejected(commandCode, commandDigest, context, state, FAILURE_IDEMPOTENCY_BINDING)
            if (context.now < state.updatedAt || receipt.checkedAt < state.updatedAt) {
                return rejected(commandCode, commandDigest, context, state, FAILURE_COMMAND_TIME)
            }
            if (receipt.status == WorkflowIdempotencyStatus.APPLIED) {
                return if (receipt.commandCode == commandCode &&
                    receipt.commandDigest == commandDigest &&
                    receipt.resultVersion != null && receipt.resultVersion <= state.version
                ) {
                    WorkflowDomainResult.of(
                        WorkflowResultCode.REPLAYED,
                        commandCode,
                        commandDigest,
                        context.idempotencyKey,
                        state,
                        emptyList(),
                        emptyList(),
                        FAILURE_IDEMPOTENT_REPLAY,
                    )
                } else {
                    rejected(commandCode, commandDigest, context, state, FAILURE_IDEMPOTENCY_CONFLICT)
                }
            }
            if (receipt.status != WorkflowIdempotencyStatus.FRESH) {
                return rejected(commandCode, commandDigest, context, state, FAILURE_IDEMPOTENCY_BINDING)
            }
            if (context.expectedInstanceVersion != state.version) {
                return versionConflict(commandCode, commandDigest, context, state)
            }
            if (state.status == WorkflowInstanceStatus.COMPLETED || state.status == WorkflowInstanceStatus.INCIDENT) {
                return rejected(commandCode, commandDigest, context, state, FAILURE_INSTANCE_TERMINAL)
            }
            return null
        }

        private fun validateStateBinding(
            index: WorkflowDefinitionIndex,
            state: WorkflowInstanceState,
        ): String? {
            val definition = index.definition
            if (definition.schemaVersion != 1 ||
                state.tenantId != definition.tenantId ||
                state.definitionId != definition.definitionId ||
                state.definitionRef != definition.ref
            ) return FAILURE_DEFINITION_BINDING
            if (state.tokens.any { token -> index.findNode(token.nodeId) == null } ||
                state.nodeExecutions.any { execution -> index.findNode(execution.nodeId) == null }
            ) return FAILURE_STATE_BINDING

            val tokensById = state.tokens.associateBy { token -> token.tokenId }
            val executionsById = state.nodeExecutions.associateBy { execution -> execution.executionId }
            val liveWorkItemsByExecution = state.humanWorkItems
                .filter { item ->
                    item.status == WorkflowHumanWorkItemStatus.WAITING_PARTICIPANTS ||
                        item.status == WorkflowHumanWorkItemStatus.ACTIVE
                }
                .groupBy { item -> item.nodeExecutionId }
            if (liveWorkItemsByExecution.values.any { items -> items.size != 1 }) return FAILURE_STATE_BINDING

            if (state.nodeExecutions.any { execution ->
                execution.startedAt < state.createdAt || execution.startedAt > state.updatedAt ||
                    (execution.completedAt != null && execution.completedAt > state.updatedAt) ||
                    (execution.status == WorkflowNodeExecutionStatus.WAITING &&
                        tokensById[execution.tokenId]?.waitingExecutionId != execution.executionId)
            }) return FAILURE_STATE_BINDING
            if (state.humanWorkItems.any { item ->
                val node = index.findNode(item.nodeId)
                val policy = node?.humanTaskPolicy
                val execution = executionsById[item.nodeExecutionId]
                val token = tokensById[item.tokenId]
                node?.kind != WorkflowNodeKind.HUMAN_TASK ||
                    policy?.contentDigest != item.policyDigest ||
                    execution?.nodeId != item.nodeId ||
                    execution.tokenId != item.tokenId ||
                    item.createdAt < state.createdAt || item.updatedAt > state.updatedAt ||
                    !humanStateMatches(item, execution, token) ||
                    !collaborationStateMatches(state, item, policy)
            }) return FAILURE_STATE_BINDING

            if (state.tokens.any { token ->
                token.parallelFrames.any { frame -> !parallelFrameMatches(index, frame) } ||
                    !waitingTokenMatches(index, token, executionsById, liveWorkItemsByExecution)
            }) return FAILURE_STATE_BINDING

            val derivedStatus = when {
                state.nodeExecutions.any { execution -> execution.status == WorkflowNodeExecutionStatus.INCIDENT } ->
                    WorkflowInstanceStatus.INCIDENT
                state.pendingContinuationEffectId != null -> WorkflowInstanceStatus.WAITING
                state.tokens.all { token ->
                    token.status == WorkflowTokenStatus.COMPLETED || token.status == WorkflowTokenStatus.CONSUMED
                } -> WorkflowInstanceStatus.COMPLETED
                state.tokens.any { token -> token.status == WorkflowTokenStatus.ACTIVE } -> WorkflowInstanceStatus.RUNNING
                else -> WorkflowInstanceStatus.WAITING
            }
            if (state.status != derivedStatus ||
                (state.pendingContinuationEffectId != null &&
                    state.tokens.none { token -> token.status == WorkflowTokenStatus.ACTIVE })
            ) return FAILURE_STATE_BINDING
            return null
        }

        private fun humanStateMatches(
            item: WorkflowHumanWorkItemState,
            execution: WorkflowNodeExecutionState,
            token: WorkflowTokenState?,
        ): Boolean = when (item.status) {
            WorkflowHumanWorkItemStatus.WAITING_PARTICIPANTS ->
                token?.status == WorkflowTokenStatus.WAITING_HUMAN &&
                    token.waitingExecutionId == execution.executionId &&
                    execution.status == WorkflowNodeExecutionStatus.WAITING &&
                    execution.pendingEffectCode == WorkflowEffectCode.PARTICIPANT_RESOLUTION

            WorkflowHumanWorkItemStatus.ACTIVE ->
                token?.status == WorkflowTokenStatus.WAITING_HUMAN &&
                    token.waitingExecutionId == execution.executionId &&
                    execution.status == WorkflowNodeExecutionStatus.WAITING &&
                    execution.pendingEffectCode == null

            WorkflowHumanWorkItemStatus.APPROVED,
            WorkflowHumanWorkItemStatus.REJECTED -> execution.status == WorkflowNodeExecutionStatus.COMPLETED
            WorkflowHumanWorkItemStatus.INCIDENT -> execution.status == WorkflowNodeExecutionStatus.INCIDENT
            else -> false
        }

        private fun collaborationStateMatches(
            state: WorkflowInstanceState,
            item: WorkflowHumanWorkItemState,
            policy: ai.icen.fw.workflow.api.WorkflowHumanTaskPolicy,
        ): Boolean {
            val collaboration = item.collaboration
            if (collaboration.claimOwner == null) {
                return collaboration.activeDelegate == null && collaboration.assignmentPath.isEmpty()
            }
            if (item.status == WorkflowHumanWorkItemStatus.WAITING_PARTICIPANTS) return false
            val snapshot = item.ruleSnapshots.getOrNull(item.activeRuleIndex) ?: return false
            return collaboration.assignmentPath.all { principal -> snapshot.candidates.contains(principal) } &&
                satisfiesSeparationOfDuties(state, item, policy.separationOfDuties, collaboration.claimOwner) &&
                (collaboration.activeDelegate == null ||
                    satisfiesSeparationOfDuties(state, item, policy.separationOfDuties, collaboration.activeDelegate))
        }

        private fun waitingTokenMatches(
            index: WorkflowDefinitionIndex,
            token: WorkflowTokenState,
            executionsById: Map<String, WorkflowNodeExecutionState>,
            liveWorkItemsByExecution: Map<String, List<WorkflowHumanWorkItemState>>,
        ): Boolean {
            val waitingId = token.waitingExecutionId ?: return token.status == WorkflowTokenStatus.ACTIVE ||
                token.status == WorkflowTokenStatus.COMPLETED || token.status == WorkflowTokenStatus.CONSUMED
            val execution = executionsById[waitingId] ?: return false
            if (execution.nodeId != token.nodeId || execution.status != WorkflowNodeExecutionStatus.WAITING) return false
            val node = index.node(token.nodeId)
            return when (token.status) {
                WorkflowTokenStatus.WAITING_EFFECT ->
                    liveWorkItemsByExecution[execution.executionId].isNullOrEmpty() &&
                        execution.pendingEffectCode == expectedNodeEffect(node)
                WorkflowTokenStatus.WAITING_HUMAN ->
                    node.kind == WorkflowNodeKind.HUMAN_TASK &&
                        liveWorkItemsByExecution[execution.executionId]?.size == 1 &&
                        (execution.pendingEffectCode == null ||
                            execution.pendingEffectCode == WorkflowEffectCode.PARTICIPANT_RESOLUTION)
                WorkflowTokenStatus.WAITING_JOIN ->
                    node.kind == WorkflowNodeKind.PARALLEL_JOIN && execution.pendingEffectCode == null
                else -> false
            }
        }

        private fun expectedNodeEffect(node: WorkflowNodeDefinition): WorkflowEffectCode? = when (node.kind) {
            WorkflowNodeKind.EXCLUSIVE_GATEWAY -> WorkflowEffectCode.EXCLUSIVE_EVALUATION
            WorkflowNodeKind.SERVICE_TASK -> WorkflowEffectCode.SERVICE_TASK
            WorkflowNodeKind.DECISION -> WorkflowEffectCode.DECISION_TASK
            WorkflowNodeKind.TIMER_WAIT -> WorkflowEffectCode.TIMER_WAIT
            WorkflowNodeKind.SUBPROCESS -> WorkflowEffectCode.SUBPROCESS
            WorkflowNodeKind.EXTENSION -> WorkflowEffectCode.EXTENSION
            WorkflowNodeKind.START,
            WorkflowNodeKind.END,
            WorkflowNodeKind.HUMAN_TASK,
            WorkflowNodeKind.PARALLEL_SPLIT,
            WorkflowNodeKind.PARALLEL_JOIN -> null
            else -> WorkflowEffectCode.EXTENSION
        }

        private fun parallelFrameMatches(
            index: WorkflowDefinitionIndex,
            frame: WorkflowParallelFrame,
        ): Boolean {
            val split = index.findNode(frame.splitNodeId) ?: return false
            val join = index.findNode(frame.joinNodeId) ?: return false
            return split.kind == WorkflowNodeKind.PARALLEL_SPLIT &&
                join.kind == WorkflowNodeKind.PARALLEL_JOIN &&
                split.parallelPairNodeId == join.nodeId &&
                join.parallelPairNodeId == split.nodeId &&
                frame.branchCount == index.outgoing(split.nodeId).size
        }

        private fun participantReceiptMatches(
            state: WorkflowInstanceState,
            workItem: WorkflowHumanWorkItemState,
            execution: WorkflowNodeExecutionState,
            token: WorkflowTokenState,
            rule: WorkflowHumanTaskParticipantRule,
            receipt: WorkflowParticipantActivationReceipt,
        ): Boolean = commonReceiptMatches(
            state,
            receipt.tenantId,
            receipt.instanceId,
            receipt.definitionId,
            receipt.definitionRef,
            receipt.subject,
        ) &&
            receipt.effectId == execution.pendingEffectId &&
            execution.pendingEffectCode == WorkflowEffectCode.PARTICIPANT_RESOLUTION &&
            receipt.effectRequestDigest == execution.effectRequestDigest &&
            receipt.tokenId == token.tokenId &&
            receipt.nodeExecutionId == execution.executionId &&
            receipt.workItemId == workItem.workItemId &&
            receipt.nodeId == workItem.nodeId &&
            receipt.ruleIndex == workItem.activeRuleIndex &&
            receipt.ruleDigest == rule.contentDigest &&
            (!receipt.hasOrganizationEvidence || receipt.selectorDigest == rule.selector.digest) &&
            token.status == WorkflowTokenStatus.WAITING_HUMAN

        private fun decisionReceiptMatches(
            state: WorkflowInstanceState,
            workItem: WorkflowHumanWorkItemState,
            snapshot: WorkflowHumanRuleSnapshot,
            command: WorkflowHumanDecisionCommand,
            receipt: WorkflowHumanDecisionAuthorizationReceipt,
        ): Boolean = commonReceiptMatches(
            state,
            receipt.tenantId,
            receipt.instanceId,
            receipt.definitionId,
            receipt.definitionRef,
            receipt.subject,
        ) &&
            receipt.workItemId == workItem.workItemId &&
            receipt.ruleIndex == workItem.activeRuleIndex &&
            receipt.actor == command.actor &&
            receipt.decision == command.decision &&
            receipt.activationDigest == snapshot.activationDigest &&
            receipt.authorizationRequestDigest == command.authorizationRequestDigest

        private fun collaborationReceiptMatches(
            state: WorkflowInstanceState,
            workItem: WorkflowHumanWorkItemState,
            snapshot: WorkflowHumanRuleSnapshot,
            evidenceBinding: ai.icen.fw.workflow.api.WorkflowHumanTaskEvidenceBinding,
            command: WorkflowHumanCollaborationCommand,
            receipt: WorkflowHumanCollaborationAuthorizationReceipt,
        ): Boolean = commonReceiptMatches(
            state,
            receipt.tenantId,
            receipt.instanceId,
            receipt.definitionId,
            receipt.definitionRef,
            receipt.subject,
        ) &&
            receipt.workItemId == workItem.workItemId &&
            receipt.nodeId == workItem.nodeId &&
            receipt.policyDigest == workItem.policyDigest &&
            receipt.evidenceBinding == evidenceBinding &&
            receipt.activeRuleIndex == workItem.activeRuleIndex &&
            receipt.activeRuleDigest == snapshot.ruleDigest &&
            receipt.activationDigest == snapshot.activationDigest &&
            receipt.action == command.action &&
            receipt.actor == command.actor &&
            receipt.target == command.target &&
            receipt.currentClaimOwner == workItem.collaboration.claimOwner &&
            receipt.currentActiveDelegate == workItem.collaboration.activeDelegate &&
            receipt.collaborationStateDigest == workItem.collaboration.contentDigest &&
            receipt.expectedWorkItemVersion == command.expectedWorkItemVersion &&
            receipt.executionNonce == command.executionNonce &&
            receipt.authorizationRequestDigest == command.authorizationRequestDigest

        private fun satisfiesSeparationOfDuties(
            state: WorkflowInstanceState,
            workItem: WorkflowHumanWorkItemState,
            policy: ai.icen.fw.workflow.api.WorkflowSeparationOfDutiesPolicy,
            principal: WorkflowPrincipalRef,
        ): Boolean = (!policy.initiatorExcluded || principal != state.initiator) &&
            (!policy.priorApproversExcluded || workItem.decisions.none { decision ->
                decision.ruleIndex < workItem.activeRuleIndex && decision.actor == principal
            })

        private fun effectReceiptMatches(
            state: WorkflowInstanceState,
            execution: WorkflowNodeExecutionState,
            token: WorkflowTokenState,
            receipt: WorkflowEffectCompletionReceipt,
        ): Boolean = commonReceiptMatches(
            state,
            receipt.tenantId,
            receipt.instanceId,
            receipt.definitionId,
            receipt.definitionRef,
            receipt.subject,
        ) &&
            receipt.effectId == execution.pendingEffectId &&
            receipt.effectCode == execution.pendingEffectCode &&
            receipt.effectRequestDigest == execution.effectRequestDigest &&
            receipt.tokenId == token.tokenId &&
            receipt.nodeExecutionId == execution.executionId &&
            receipt.nodeId == execution.nodeId &&
            execution.status == WorkflowNodeExecutionStatus.WAITING &&
            token.status == WorkflowTokenStatus.WAITING_EFFECT

        private fun commonReceiptMatches(
            state: WorkflowInstanceState,
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
        ): Boolean = tenantId == state.tenantId &&
            instanceId == state.instanceId &&
            definitionId == state.definitionId &&
            definitionRef == state.definitionRef &&
            subject == state.subject

        private fun requiredApprovals(rule: WorkflowHumanTaskParticipantRule, denominator: Int): Int? =
            when (rule.approvalPolicy.mode) {
                WorkflowApprovalMode.ONE -> 1
                WorkflowApprovalMode.ALL -> denominator
                WorkflowApprovalMode.QUORUM -> rule.approvalPolicy.requiredApprovals
                else -> null
            }

        private fun rejected(
            commandCode: WorkflowCommandCode,
            commandDigest: String,
            context: WorkflowCommandContext,
            state: WorkflowInstanceState?,
            failureCode: String,
        ): WorkflowDomainResult = WorkflowDomainResult.of(
            WorkflowResultCode.REJECTED,
            commandCode,
            commandDigest,
            context.idempotencyKey,
            state,
            emptyList(),
            emptyList(),
            failureCode,
        )

        private fun versionConflict(
            commandCode: WorkflowCommandCode,
            commandDigest: String,
            context: WorkflowCommandContext,
            state: WorkflowInstanceState,
        ): WorkflowDomainResult = WorkflowDomainResult.of(
            WorkflowResultCode.VERSION_CONFLICT,
            commandCode,
            commandDigest,
            context.idempotencyKey,
            state,
            emptyList(),
            emptyList(),
            FAILURE_VERSION_CONFLICT,
        )
    }
}

private class DomainMachine private constructor(
    private val index: WorkflowDefinitionIndex,
    private val commandCode: WorkflowCommandCode,
    private val commandDigest: String,
    private val context: WorkflowCommandContext,
    private val tenantId: String,
    private val instanceId: String,
    private val definitionId: String,
    private val definitionRef: WorkflowDefinitionRef,
    private val subject: WorkflowSubjectSnapshot,
    private val initiator: WorkflowPrincipalRef,
    private val resultVersion: Long,
    private val createdAt: Long,
    private val tokens: MutableList<WorkflowTokenState>,
    private val executions: MutableList<WorkflowNodeExecutionState>,
    private val workItems: MutableList<WorkflowHumanWorkItemState>,
    private var pendingContinuationEffectId: String?,
    private var pendingContinuationRequestDigest: String?,
) {
    private val ids = IdCursor(context.ids)
    private val events = ArrayList<WorkflowDomainEvent>()
    private val effects = ArrayList<WorkflowEffectIntent>()
    private var incidentCode: String? = null
    private var budgetExhausted = false
    private var completionEmitted = false

    fun advance() {
        var remaining = context.iterationBudget
        while (incidentCode == null) {
            val active = tokens.firstOrNull { token -> token.status == WorkflowTokenStatus.ACTIVE } ?: break
            if (remaining == 0) {
                requestContinuation()
                budgetExhausted = true
                break
            }
            remaining -= 1
            enter(active)
        }
    }

    fun activateHumanRule(
        workItem: WorkflowHumanWorkItemState,
        execution: WorkflowNodeExecutionState,
        snapshot: WorkflowHumanRuleSnapshot,
        receiptDigest: String,
    ) {
        replaceWorkItem(
            WorkflowHumanWorkItemState.restore(
                workItem.workItemId,
                workItem.nodeExecutionId,
                workItem.tokenId,
                workItem.nodeId,
                workItem.policyDigest,
                WorkflowHumanWorkItemStatus.ACTIVE,
                workItem.activeRuleIndex,
                workItem.ruleSnapshots + snapshot,
                workItem.decisions,
                workItem.collaboration,
                workItem.revision + 1L,
                workItem.createdAt,
                context.now,
            ),
        )
        replaceExecution(
            WorkflowNodeExecutionState.restore(
                execution.executionId,
                execution.tokenId,
                execution.nodeId,
                WorkflowNodeExecutionStatus.WAITING,
                execution.revision + 1L,
                execution.startedAt,
                null,
                null,
                null,
                null,
            ),
        )
        emit(
            WorkflowEventCode.EFFECT_COMPLETED,
            workItem.tokenId,
            execution.executionId,
            workItem.workItemId,
            workItem.nodeId,
            receiptDigest,
        )
        emit(
            WorkflowEventCode.HUMAN_RULE_ACTIVATED,
            workItem.tokenId,
            execution.executionId,
            workItem.workItemId,
            workItem.nodeId,
            snapshot.activationDigest,
        )
    }

    fun recordHumanDecision(
        workItem: WorkflowHumanWorkItemState,
        snapshot: WorkflowHumanRuleSnapshot,
        command: WorkflowHumanDecisionCommand,
    ) {
        val decision = WorkflowHumanDecision.of(
            context.commandId,
            workItem.activeRuleIndex,
            command.actor,
            command.decision,
            command.authorizationReceipt.receiptDigest,
            context.now,
        )
        val decisions = workItem.decisions + decision
        emit(
            WorkflowEventCode.HUMAN_DECISION_RECORDED,
            workItem.tokenId,
            workItem.nodeExecutionId,
            workItem.workItemId,
            workItem.nodeId,
            decision.contentDigest,
        )
        if (command.decision == WorkflowHumanDecisionCode.REJECT) {
            completeHuman(workItem, decisions, WorkflowHumanWorkItemStatus.REJECTED)
            advance()
            return
        }
        // A before-sign approval is mandatory evidence but cannot complete the base rule. The
        // signer must explicitly RETURN through the same authorization/CAS/nonce path; nested
        // frames therefore unwind deterministically one level at a time.
        if (workItem.collaboration.addSignFrames.isNotEmpty()) {
            replaceWorkItem(
                WorkflowHumanWorkItemState.restore(
                    workItem.workItemId,
                    workItem.nodeExecutionId,
                    workItem.tokenId,
                    workItem.nodeId,
                    workItem.policyDigest,
                    WorkflowHumanWorkItemStatus.ACTIVE,
                    workItem.activeRuleIndex,
                    workItem.ruleSnapshots,
                    decisions,
                    workItem.collaboration,
                    workItem.revision + 1L,
                    workItem.createdAt,
                    context.now,
                ),
            )
            return
        }
        val approvals = decisions.count { existing ->
            existing.ruleIndex == workItem.activeRuleIndex &&
                existing.decision == WorkflowHumanDecisionCode.APPROVE
        }
        if (approvals < snapshot.requiredApprovals) {
            replaceWorkItem(
                WorkflowHumanWorkItemState.restore(
                    workItem.workItemId,
                    workItem.nodeExecutionId,
                    workItem.tokenId,
                    workItem.nodeId,
                    workItem.policyDigest,
                    WorkflowHumanWorkItemStatus.ACTIVE,
                    workItem.activeRuleIndex,
                    workItem.ruleSnapshots,
                    decisions,
                    workItem.collaboration,
                    workItem.revision + 1L,
                    workItem.createdAt,
                    context.now,
                ),
            )
            return
        }
        val policy = index.node(workItem.nodeId).humanTaskPolicy
            ?: throw DomainFailure(FAILURE_STATE_BINDING)
        if (workItem.activeRuleIndex + 1 < policy.participantRules.size) {
            val nextIndex = workItem.activeRuleIndex + 1
            val waiting = WorkflowHumanWorkItemState.restore(
                workItem.workItemId,
                workItem.nodeExecutionId,
                workItem.tokenId,
                workItem.nodeId,
                workItem.policyDigest,
                WorkflowHumanWorkItemStatus.WAITING_PARTICIPANTS,
                nextIndex,
                workItem.ruleSnapshots,
                decisions,
                workItem.collaboration.clearAssignment(),
                workItem.revision + 1L,
                workItem.createdAt,
                context.now,
            )
            replaceWorkItem(waiting)
            val execution = execution(workItem.nodeExecutionId)
            requestParticipants(waiting, execution, policy.participantRules[nextIndex])
            return
        }
        completeHuman(workItem, decisions, WorkflowHumanWorkItemStatus.APPROVED)
        advance()
    }

    fun completeEffect(
        execution: WorkflowNodeExecutionState,
        token: WorkflowTokenState,
        receipt: WorkflowEffectCompletionReceipt,
    ) {
        emit(
            WorkflowEventCode.EFFECT_COMPLETED,
            token.tokenId,
            execution.executionId,
            null,
            execution.nodeId,
            receipt.receiptDigest,
        )
        if (receipt.outcome == WorkflowEffectOutcomeCode.FAILURE) {
            raiseIncident(token.tokenId, execution.executionId, null, FAILURE_EFFECT_FAILED)
            return
        }
        val node = index.node(execution.nodeId)
        val outgoing = index.outgoing(node.nodeId)
        val selected = if (node.kind == WorkflowNodeKind.EXCLUSIVE_GATEWAY) {
            val selectedId = receipt.selectedTransitionId ?: throw DomainFailure(FAILURE_TRANSITION_SELECTION)
            outgoing.firstOrNull { transition -> transition.transitionId == selectedId }
                ?: throw DomainFailure(FAILURE_TRANSITION_SELECTION)
        } else {
            if (receipt.selectedTransitionId != null || outgoing.size != 1) {
                throw DomainFailure(FAILURE_TRANSITION_SELECTION)
            }
            outgoing.single()
        }
        completeAndMove(token, execution, selected)
        advance()
    }

    fun completeContinuation(receipt: WorkflowContinuationReceipt) {
        emit(
            WorkflowEventCode.EFFECT_COMPLETED,
            null,
            null,
            null,
            null,
            receipt.receiptDigest,
        )
        pendingContinuationEffectId = null
        pendingContinuationRequestDigest = null
    }

    fun raiseIncident(
        tokenId: String,
        executionId: String,
        workItemId: String?,
        failureCode: String,
    ) {
        val token = token(tokenId)
        val execution = execution(executionId)
        replaceToken(
            WorkflowTokenState.restore(
                token.tokenId,
                token.nodeId,
                WorkflowTokenStatus.CONSUMED,
                token.parallelFrames,
                null,
                token.revision + 1L,
            ),
        )
        replaceExecution(
            WorkflowNodeExecutionState.restore(
                execution.executionId,
                execution.tokenId,
                execution.nodeId,
                WorkflowNodeExecutionStatus.INCIDENT,
                execution.revision + 1L,
                execution.startedAt,
                context.now,
                null,
                null,
                null,
            ),
        )
        if (workItemId != null) {
            val item = workItem(workItemId)
            replaceWorkItem(
                WorkflowHumanWorkItemState.restore(
                    item.workItemId,
                    item.nodeExecutionId,
                    item.tokenId,
                    item.nodeId,
                    item.policyDigest,
                    WorkflowHumanWorkItemStatus.INCIDENT,
                    item.activeRuleIndex,
                    item.ruleSnapshots,
                    item.decisions,
                    item.collaboration,
                    item.revision + 1L,
                    item.createdAt,
                    context.now,
                ),
            )
        }
        pendingContinuationEffectId = null
        pendingContinuationRequestDigest = null
        incidentCode = failureCode
        emit(
            WorkflowEventCode.INCIDENT_RAISED,
            tokenId,
            executionId,
            workItemId,
            execution.nodeId,
            payload("incident-v1", failureCode),
        )
    }

    fun result(): WorkflowDomainResult {
        val status = when {
            incidentCode != null -> WorkflowInstanceStatus.INCIDENT
            pendingContinuationEffectId != null -> WorkflowInstanceStatus.WAITING
            tokens.all { token ->
                token.status == WorkflowTokenStatus.COMPLETED || token.status == WorkflowTokenStatus.CONSUMED
            } -> WorkflowInstanceStatus.COMPLETED
            tokens.any { token -> token.status == WorkflowTokenStatus.ACTIVE } -> WorkflowInstanceStatus.RUNNING
            else -> WorkflowInstanceStatus.WAITING
        }
        if (status == WorkflowInstanceStatus.COMPLETED && !completionEmitted) {
            emit(
                WorkflowEventCode.INSTANCE_COMPLETED,
                null,
                null,
                null,
                null,
                payload("instance-completed-v1", instanceId, resultVersion.toString()),
            )
            completionEmitted = true
        }
        val state = WorkflowInstanceState.restore(
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            initiator,
            status,
            resultVersion,
            createdAt,
            context.now,
            tokens,
            executions,
            workItems,
            pendingContinuationEffectId,
            pendingContinuationRequestDigest,
        )
        val resultCode = when {
            incidentCode != null -> WorkflowResultCode.INCIDENT
            budgetExhausted -> WorkflowResultCode.BUDGET_EXHAUSTED
            else -> WorkflowResultCode.APPLIED
        }
        return WorkflowDomainResult.of(
            resultCode,
            commandCode,
            commandDigest,
            context.idempotencyKey,
            state,
            events,
            effects,
            incidentCode,
        )
    }

    private fun enter(token: WorkflowTokenState) {
        val node = index.node(token.nodeId)
        val execution = WorkflowNodeExecutionState.restore(
            ids.execution(),
            token.tokenId,
            node.nodeId,
            WorkflowNodeExecutionStatus.ACTIVE,
            0L,
            context.now,
            null,
            null,
            null,
            null,
        )
        executions.add(execution)
        emit(
            WorkflowEventCode.NODE_ENTERED,
            token.tokenId,
            execution.executionId,
            null,
            node.nodeId,
            node.contentDigest,
        )
        when (node.kind) {
            WorkflowNodeKind.START -> completeAndMove(token, execution, index.outgoing(node.nodeId).single())
            WorkflowNodeKind.END -> completeEnd(token, execution)
            WorkflowNodeKind.HUMAN_TASK -> enterHuman(token, execution, node)
            WorkflowNodeKind.EXCLUSIVE_GATEWAY -> requestNodeEffect(
                token,
                execution,
                node,
                WorkflowEffectCode.EXCLUSIVE_EVALUATION,
                exclusivePayload(node),
            )

            WorkflowNodeKind.PARALLEL_SPLIT -> split(token, execution, node)
            WorkflowNodeKind.PARALLEL_JOIN -> join(token, execution, node)
            else -> requestNodeEffect(token, execution, node, effectCode(node), externalPayload(node))
        }
    }

    private fun completeAndMove(
        token: WorkflowTokenState,
        execution: WorkflowNodeExecutionState,
        transition: WorkflowTransitionDefinition,
    ) {
        replaceExecution(completedExecution(execution))
        replaceToken(
            WorkflowTokenState.restore(
                token.tokenId,
                transition.toNodeId,
                WorkflowTokenStatus.ACTIVE,
                token.parallelFrames,
                null,
                token.revision + 1L,
            ),
        )
        emit(
            WorkflowEventCode.NODE_COMPLETED,
            token.tokenId,
            execution.executionId,
            null,
            execution.nodeId,
            transition.contentDigest,
        )
        emit(
            WorkflowEventCode.TOKEN_MOVED,
            token.tokenId,
            execution.executionId,
            null,
            transition.toNodeId,
            transition.contentDigest,
        )
    }

    private fun completeEnd(token: WorkflowTokenState, execution: WorkflowNodeExecutionState) {
        replaceExecution(completedExecution(execution))
        replaceToken(
            WorkflowTokenState.restore(
                token.tokenId,
                token.nodeId,
                WorkflowTokenStatus.COMPLETED,
                token.parallelFrames,
                null,
                token.revision + 1L,
            ),
        )
        emit(
            WorkflowEventCode.NODE_COMPLETED,
            token.tokenId,
            execution.executionId,
            null,
            execution.nodeId,
            execution.contentDigest,
        )
    }

    private fun enterHuman(
        token: WorkflowTokenState,
        execution: WorkflowNodeExecutionState,
        node: WorkflowNodeDefinition,
    ) {
        val policy = node.humanTaskPolicy ?: throw DomainFailure(FAILURE_STATE_BINDING)
        val workItem = WorkflowHumanWorkItemState.restore(
            ids.workItem(),
            execution.executionId,
            token.tokenId,
            node.nodeId,
            policy.contentDigest,
            WorkflowHumanWorkItemStatus.WAITING_PARTICIPANTS,
            0,
            emptyList(),
            emptyList(),
            0L,
            context.now,
            context.now,
        )
        workItems.add(workItem)
        emit(
            WorkflowEventCode.HUMAN_WORK_ITEM_CREATED,
            token.tokenId,
            execution.executionId,
            workItem.workItemId,
            node.nodeId,
            workItem.contentDigest,
        )
        requestParticipants(workItem, execution, policy.participantRules.first())
    }

    private fun requestParticipants(
        workItem: WorkflowHumanWorkItemState,
        execution: WorkflowNodeExecutionState,
        rule: WorkflowHumanTaskParticipantRule,
    ) {
        val effect = effect(
            WorkflowEffectCode.PARTICIPANT_RESOLUTION,
            workItem.tokenId,
            execution.executionId,
            workItem.workItemId,
            workItem.nodeId,
            workItem.activeRuleIndex,
            WorkflowEffectIntent.participantResolutionPayloadDigest(
                workItem.contentDigest,
                workItem.policyDigest,
                workItem.activeRuleIndex,
                rule.contentDigest,
                rule.selector.digest,
                rule.approvalPolicy.contentDigest,
            ),
        )
        replaceExecution(
            WorkflowNodeExecutionState.restore(
                execution.executionId,
                execution.tokenId,
                execution.nodeId,
                WorkflowNodeExecutionStatus.WAITING,
                execution.revision + 1L,
                execution.startedAt,
                null,
                effect.effectId,
                effect.code,
                effect.requestDigest,
            ),
        )
        val token = token(workItem.tokenId)
        replaceToken(
            WorkflowTokenState.restore(
                token.tokenId,
                token.nodeId,
                WorkflowTokenStatus.WAITING_HUMAN,
                token.parallelFrames,
                execution.executionId,
                token.revision + 1L,
            ),
        )
    }

    private fun completeHuman(
        workItem: WorkflowHumanWorkItemState,
        decisions: List<WorkflowHumanDecision>,
        terminalStatus: WorkflowHumanWorkItemStatus,
    ) {
        val transitionTrigger = when (terminalStatus) {
            WorkflowHumanWorkItemStatus.APPROVED -> WorkflowTransitionTrigger.APPROVED
            WorkflowHumanWorkItemStatus.REJECTED -> WorkflowTransitionTrigger.REJECTED
            else -> throw DomainFailure(FAILURE_TRANSITION_SELECTION)
        }
        val matchingTransitions = index.outgoing(workItem.nodeId).filter { transition ->
            transition.trigger == transitionTrigger
        }
        if (matchingTransitions.size != 1) {
            throw DomainFailure(FAILURE_TRANSITION_SELECTION)
        }
        val completedItem = WorkflowHumanWorkItemState.restore(
            workItem.workItemId,
            workItem.nodeExecutionId,
            workItem.tokenId,
            workItem.nodeId,
            workItem.policyDigest,
            terminalStatus,
            workItem.activeRuleIndex,
            workItem.ruleSnapshots,
            decisions,
            workItem.collaboration,
            workItem.revision + 1L,
            workItem.createdAt,
            context.now,
        )
        replaceWorkItem(completedItem)
        val execution = execution(workItem.nodeExecutionId)
        val token = token(workItem.tokenId)
        emit(
            WorkflowEventCode.HUMAN_WORK_ITEM_COMPLETED,
            token.tokenId,
            execution.executionId,
            workItem.workItemId,
            workItem.nodeId,
            completedItem.contentDigest,
        )
        completeAndMove(token, execution, matchingTransitions.single())
    }

    private fun requestNodeEffect(
        token: WorkflowTokenState,
        execution: WorkflowNodeExecutionState,
        node: WorkflowNodeDefinition,
        code: WorkflowEffectCode,
        payloadDigest: String,
    ) {
        val effect = effect(
            code,
            token.tokenId,
            execution.executionId,
            null,
            node.nodeId,
            null,
            payloadDigest,
        )
        replaceExecution(
            WorkflowNodeExecutionState.restore(
                execution.executionId,
                execution.tokenId,
                execution.nodeId,
                WorkflowNodeExecutionStatus.WAITING,
                execution.revision + 1L,
                execution.startedAt,
                null,
                effect.effectId,
                effect.code,
                effect.requestDigest,
            ),
        )
        replaceToken(
            WorkflowTokenState.restore(
                token.tokenId,
                token.nodeId,
                WorkflowTokenStatus.WAITING_EFFECT,
                token.parallelFrames,
                execution.executionId,
                token.revision + 1L,
            ),
        )
    }

    private fun split(
        token: WorkflowTokenState,
        execution: WorkflowNodeExecutionState,
        node: WorkflowNodeDefinition,
    ) {
        val transitions = index.outgoing(node.nodeId)
        val joinId = node.parallelPairNodeId ?: throw DomainFailure(FAILURE_STATE_BINDING)
        val scopeId = ids.parallelScope()
        replaceExecution(completedExecution(execution))
        replaceToken(
            WorkflowTokenState.restore(
                token.tokenId,
                token.nodeId,
                WorkflowTokenStatus.CONSUMED,
                token.parallelFrames,
                null,
                token.revision + 1L,
            ),
        )
        transitions.forEachIndexed { branchIndex, transition ->
            val frame = WorkflowParallelFrame.of(
                scopeId,
                node.nodeId,
                joinId,
                branchIndex,
                transitions.size,
            )
            tokens.add(
                WorkflowTokenState.restore(
                    ids.token(),
                    transition.toNodeId,
                    WorkflowTokenStatus.ACTIVE,
                    token.parallelFrames + frame,
                    null,
                    0L,
                ),
            )
        }
        emit(
            WorkflowEventCode.NODE_COMPLETED,
            token.tokenId,
            execution.executionId,
            null,
            node.nodeId,
            node.contentDigest,
        )
        emit(
            WorkflowEventCode.TOKEN_SPLIT,
            token.tokenId,
            execution.executionId,
            null,
            node.nodeId,
            payload("parallel-split-v1", scopeId, node.nodeId, joinId, transitions.size.toString()),
        )
    }

    private fun join(
        token: WorkflowTokenState,
        execution: WorkflowNodeExecutionState,
        node: WorkflowNodeDefinition,
    ) {
        val frame = token.parallelFrames.lastOrNull()
        if (frame == null || frame.joinNodeId != node.nodeId) {
            raiseIncident(token.tokenId, execution.executionId, null, FAILURE_PARALLEL_CORRELATION)
            return
        }
        replaceExecution(
            WorkflowNodeExecutionState.restore(
                execution.executionId,
                execution.tokenId,
                execution.nodeId,
                WorkflowNodeExecutionStatus.WAITING,
                execution.revision + 1L,
                execution.startedAt,
                null,
                null,
                null,
                null,
            ),
        )
        replaceToken(
            WorkflowTokenState.restore(
                token.tokenId,
                token.nodeId,
                WorkflowTokenStatus.WAITING_JOIN,
                token.parallelFrames,
                execution.executionId,
                token.revision + 1L,
            ),
        )
        val arrivals = tokens.filter { candidate ->
            candidate.status == WorkflowTokenStatus.WAITING_JOIN &&
                candidate.nodeId == node.nodeId &&
                candidate.parallelFrames.lastOrNull()?.scopeId == frame.scopeId
        }
        if (arrivals.size < frame.branchCount) return
        val indexes = arrivals.map { arrival -> arrival.parallelFrames.last().branchIndex }
        if (arrivals.size != frame.branchCount ||
            arrivals.any { arrival ->
                val candidate = arrival.parallelFrames.last()
                candidate.splitNodeId != frame.splitNodeId ||
                    candidate.joinNodeId != frame.joinNodeId ||
                    candidate.branchCount != frame.branchCount
            } ||
            indexes.toSet() != (0 until frame.branchCount).toSet()
        ) {
            raiseIncident(token.tokenId, execution.executionId, null, FAILURE_PARALLEL_CORRELATION)
            return
        }
        val outerFrames = arrivals.first().parallelFrames.dropLast(1)
        if (arrivals.any { arrival -> arrival.parallelFrames.dropLast(1) != outerFrames }) {
            raiseIncident(token.tokenId, execution.executionId, null, FAILURE_PARALLEL_CORRELATION)
            return
        }
        arrivals.forEach { arrival ->
            val arrivalExecution = execution(arrival.waitingExecutionId!!)
            replaceExecution(completedExecution(arrivalExecution))
            replaceToken(
                WorkflowTokenState.restore(
                    arrival.tokenId,
                    arrival.nodeId,
                    WorkflowTokenStatus.CONSUMED,
                    arrival.parallelFrames,
                    null,
                    arrival.revision + 1L,
                ),
            )
            emit(
                WorkflowEventCode.NODE_COMPLETED,
                arrival.tokenId,
                arrivalExecution.executionId,
                null,
                node.nodeId,
                frame.contentDigest,
            )
        }
        val transition = index.outgoing(node.nodeId).single()
        val merged = WorkflowTokenState.restore(
            ids.token(),
            transition.toNodeId,
            WorkflowTokenStatus.ACTIVE,
            outerFrames,
            null,
            0L,
        )
        tokens.add(merged)
        emit(
            WorkflowEventCode.TOKEN_JOINED,
            merged.tokenId,
            null,
            null,
            node.nodeId,
            payload("parallel-join-v1", frame.scopeId, node.nodeId, merged.tokenId),
        )
    }

    private fun requestContinuation() {
        val activeDigests = tokens.filter { token -> token.status == WorkflowTokenStatus.ACTIVE }
            .map { token -> token.contentDigest }
        val payloadDigest = payload(
            "continuation-v1",
            definitionRef.digest,
            resultVersion.toString(),
            *activeDigests.toTypedArray(),
        )
        val effect = effect(
            WorkflowEffectCode.CONTINUE_EXECUTION,
            null,
            null,
            null,
            null,
            null,
            payloadDigest,
        )
        pendingContinuationEffectId = effect.effectId
        pendingContinuationRequestDigest = effect.requestDigest
        emit(
            WorkflowEventCode.CONTINUATION_REQUESTED,
            null,
            null,
            null,
            null,
            effect.requestDigest,
        )
    }

    private fun effect(
        code: WorkflowEffectCode,
        tokenId: String?,
        executionId: String?,
        workItemId: String?,
        nodeId: String?,
        ruleIndex: Int?,
        payloadDigest: String,
    ): WorkflowEffectIntent {
        val effect = WorkflowEffectIntent.of(
            ids.effect(),
            code,
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            tokenId,
            executionId,
            workItemId,
            nodeId,
            ruleIndex,
            payloadDigest,
            context.now,
        )
        effects.add(effect)
        emit(
            WorkflowEventCode.EFFECT_REQUESTED,
            tokenId,
            executionId,
            workItemId,
            nodeId,
            effect.requestDigest,
        )
        return effect
    }

    private fun emit(
        code: WorkflowEventCode,
        tokenId: String?,
        executionId: String?,
        workItemId: String?,
        nodeId: String?,
        payloadDigest: String,
    ) {
        events.add(
            WorkflowDomainEvent.of(
                ids.event(),
                code,
                tenantId,
                instanceId,
                definitionId,
                definitionRef,
                subject,
                tokenId,
                executionId,
                workItemId,
                nodeId,
                payloadDigest,
                context.now,
                resultVersion,
            ),
        )
    }

    private fun completedExecution(execution: WorkflowNodeExecutionState): WorkflowNodeExecutionState =
        WorkflowNodeExecutionState.restore(
            execution.executionId,
            execution.tokenId,
            execution.nodeId,
            WorkflowNodeExecutionStatus.COMPLETED,
            execution.revision + 1L,
            execution.startedAt,
            context.now,
            null,
            null,
            null,
        )

    private fun exclusivePayload(node: WorkflowNodeDefinition): String {
        val writer = WorkflowDomainSupport.digest("flowweft-workflow-domain-exclusive-request-v1")
            .text(node.contentDigest)
        val outgoing = index.outgoing(node.nodeId)
        writer.integer(outgoing.size)
        outgoing.forEach { transition ->
            writer.text(transition.transitionId)
                .text(transition.contentDigest)
                .optionalText(transition.predicate?.bindingDigest)
        }
        return writer.finish()
    }

    private fun externalPayload(node: WorkflowNodeDefinition): String = payload(
        "external-node-request-v1",
        node.nodeId,
        node.kind.code,
        node.descriptorDigest,
        node.payloadDigest,
        node.contentDigest,
    )

    private fun effectCode(node: WorkflowNodeDefinition): WorkflowEffectCode = when (node.kind) {
        WorkflowNodeKind.SERVICE_TASK -> WorkflowEffectCode.SERVICE_TASK
        WorkflowNodeKind.DECISION -> WorkflowEffectCode.DECISION_TASK
        WorkflowNodeKind.TIMER_WAIT -> WorkflowEffectCode.TIMER_WAIT
        WorkflowNodeKind.SUBPROCESS -> WorkflowEffectCode.SUBPROCESS
        else -> WorkflowEffectCode.EXTENSION
    }

    private fun token(tokenId: String): WorkflowTokenState = tokens.first { item -> item.tokenId == tokenId }
    private fun execution(executionId: String): WorkflowNodeExecutionState =
        executions.first { item -> item.executionId == executionId }
    private fun workItem(workItemId: String): WorkflowHumanWorkItemState =
        workItems.first { item -> item.workItemId == workItemId }

    private fun replaceToken(value: WorkflowTokenState) {
        val index = tokens.indexOfFirst { token -> token.tokenId == value.tokenId }
        if (index < 0) throw DomainFailure(FAILURE_STATE_BINDING)
        tokens[index] = value
    }

    private fun replaceExecution(value: WorkflowNodeExecutionState) {
        val index = executions.indexOfFirst { execution -> execution.executionId == value.executionId }
        if (index < 0) throw DomainFailure(FAILURE_STATE_BINDING)
        executions[index] = value
    }

    private fun replaceWorkItem(value: WorkflowHumanWorkItemState) {
        val index = workItems.indexOfFirst { item -> item.workItemId == value.workItemId }
        if (index < 0) throw DomainFailure(FAILURE_STATE_BINDING)
        workItems[index] = value
    }

    companion object {
        fun start(index: WorkflowDefinitionIndex, command: WorkflowStartCommand): DomainMachine {
            val ids = IdCursor(command.context.ids)
            val token = WorkflowTokenState.restore(
                ids.token(),
                index.startNode.nodeId,
                WorkflowTokenStatus.ACTIVE,
                emptyList(),
                null,
                0L,
            )
            val machine = DomainMachine(
                index,
                command.code,
                command.commandDigest,
                command.context,
                command.tenantId,
                command.instanceId,
                command.definitionId,
                command.definitionRef,
                command.subject,
                command.initiator,
                1L,
                command.context.now,
                arrayListOf(token),
                ArrayList(),
                ArrayList(),
                null,
                null,
            )
            machine.ids.tokenIndex = ids.tokenIndex
            machine.emit(
                WorkflowEventCode.INSTANCE_STARTED,
                token.tokenId,
                null,
                null,
                index.startNode.nodeId,
                payload(
                    "instance-start-v1",
                    command.executionReceipt.receiptDigest,
                    command.definitionRef.digest,
                    command.subject.digest,
                ),
            )
            return machine
        }

        fun fromState(
            index: WorkflowDefinitionIndex,
            state: WorkflowInstanceState,
            commandCode: WorkflowCommandCode,
            commandDigest: String,
            context: WorkflowCommandContext,
        ): DomainMachine = DomainMachine(
            index,
            commandCode,
            commandDigest,
            context,
            state.tenantId,
            state.instanceId,
            state.definitionId,
            state.definitionRef,
            state.subject,
            state.initiator,
            state.version + 1L,
            state.createdAt,
            ArrayList(state.tokens),
            ArrayList(state.nodeExecutions),
            ArrayList(state.humanWorkItems),
            state.pendingContinuationEffectId,
            state.pendingContinuationRequestDigest,
        )
    }
}

private class IdCursor(private val ids: WorkflowExecutionIds) {
    var tokenIndex: Int = 0
    private var executionIndex: Int = 0
    private var workItemIndex: Int = 0
    private var effectIndex: Int = 0
    private var eventIndex: Int = 0
    private var parallelScopeIndex: Int = 0

    fun token(): String = next(ids.tokenIds, tokenIndex++ )
    fun execution(): String = next(ids.nodeExecutionIds, executionIndex++)
    fun workItem(): String = next(ids.workItemIds, workItemIndex++)
    fun effect(): String = next(ids.effectIds, effectIndex++)
    fun event(): String = next(ids.eventIds, eventIndex++)
    fun parallelScope(): String = next(ids.parallelScopeIds, parallelScopeIndex++)

    private fun next(values: List<String>, index: Int): String = values.getOrNull(index)
        ?: throw DomainFailure(FAILURE_INSUFFICIENT_IDS)
}

private class DomainFailure(val code: String) : RuntimeException(code)

private fun payload(domainSuffix: String, vararg values: String): String {
    val writer = WorkflowDomainSupport.digest("flowweft-workflow-domain-$domainSuffix")
        .integer(values.size)
    values.forEach(writer::text)
    return writer.finish()
}

private const val FAILURE_AUTHORIZATION = "authorization-denied"
private const val FAILURE_COMMAND_TIME = "command-time-before-state"
private const val FAILURE_ALREADY_CLAIMED = "work-item-already-claimed"
private const val FAILURE_COLLABORATION_CONFLICT = "human-collaboration-conflict"
private const val FAILURE_COLLABORATION_DISABLED = "human-collaboration-disabled"
private const val FAILURE_COLLABORATION_UNSUPPORTED = "human-collaboration-unsupported"
private const val FAILURE_ADD_SIGN_DECISION_CONFLICT = "add-sign-decision-conflict"
private const val FAILURE_ADD_SIGN_DECISION_REQUIRED = "add-sign-decision-required"
private const val FAILURE_RETURN_WITHOUT_ADD_SIGN = "return-without-add-sign"
private const val FAILURE_DEFINITION_BINDING = "definition-binding-mismatch"
private const val FAILURE_DEPLOYMENT_RECEIPT = "deployment-receipt-mismatch"
private const val FAILURE_DUPLICATE_DECISION = "duplicate-decision"
private const val FAILURE_EFFECT_FAILED = "effect-failed"
private const val FAILURE_EFFECT_KIND = "effect-kind-mismatch"
private const val FAILURE_EXECUTION_MISSING = "execution-missing"
private const val FAILURE_IDEMPOTENCY_BINDING = "idempotency-binding-mismatch"
private const val FAILURE_IDEMPOTENCY_CONFLICT = "idempotency-conflict"
private const val FAILURE_IDEMPOTENT_REPLAY = "idempotent-replay"
private const val FAILURE_INSUFFICIENT_IDS = "insufficient-command-ids"
private const val FAILURE_IDENTIFIER_BUDGET = "insufficient-command-ids"
private const val FAILURE_INSTANCE_TERMINAL = "instance-terminal"
private const val FAILURE_NOT_CANDIDATE = "actor-not-candidate"
private const val FAILURE_NOT_CLAIMED = "work-item-not-claimed"
private const val FAILURE_NOT_CLAIM_OWNER = "actor-not-claim-owner"
private const val FAILURE_PARALLEL_CORRELATION = "parallel-correlation-mismatch"
private const val FAILURE_QUORUM_IMPOSSIBLE = "quorum-impossible"
private const val FAILURE_RECEIPT_MISMATCH = "receipt-binding-mismatch"
private const val FAILURE_START_VERSION = "start-version-mismatch"
private const val FAILURE_STATE_BINDING = "state-binding-mismatch"
private const val FAILURE_TRANSITION_SELECTION = "transition-selection-mismatch"
private const val FAILURE_UNSUPPORTED_SCHEMA = "unsupported-schema-version"
private const val FAILURE_VERSION_CONFLICT = "version-conflict"
private const val FAILURE_WORK_ITEM_MISSING = "work-item-missing"
private const val FAILURE_WORK_ITEM_STATE = "work-item-state-mismatch"
