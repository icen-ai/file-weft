package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowApprovalPolicy
import ai.icen.fw.workflow.api.WorkflowDefinition
import ai.icen.fw.workflow.api.WorkflowDefinitionStatus
import ai.icen.fw.workflow.api.WorkflowHumanTaskCapabilities
import ai.icen.fw.workflow.api.WorkflowHumanTaskParticipantRule
import ai.icen.fw.workflow.api.WorkflowHumanTaskPolicy
import ai.icen.fw.workflow.api.WorkflowNodeDefinition
import ai.icen.fw.workflow.api.WorkflowNodeKind
import ai.icen.fw.workflow.api.WorkflowParticipantMembershipStrategy
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionStage
import ai.icen.fw.workflow.api.WorkflowParticipantSelector
import ai.icen.fw.workflow.api.WorkflowPredicateRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSeparationOfDutiesPolicy
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.api.WorkflowTransitionDefinition
import ai.icen.fw.workflow.api.WorkflowTransitionTrigger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WorkflowDomainEngineTest {
    @Test
    fun `current membership decisions require fresh exact evidence instead of activation candidates`() {
        val participantRule = WorkflowHumanTaskParticipantRule.of(
            WorkflowParticipantSelector.group("rotating-reviewers"),
            WorkflowApprovalPolicy.one(),
            WorkflowParticipantMembershipStrategy.CURRENT_MEMBERSHIP,
        )
        val definition = humanDefinition(
            listOf(participantRule),
            listOf(
                WorkflowParticipantResolutionStage.ACTIVATION,
                WorkflowParticipantResolutionStage.QUERY,
                WorkflowParticipantResolutionStage.CLAIM,
                WorkflowParticipantResolutionStage.DECISION,
            ),
        )
        val index = WorkflowDefinitionIndex.compile(definition)
        val started = start(index, "dynamic-start", 10L)
        val activated = activate(index, started.state!!, listOf(ALICE), "dynamic-activate", 20L)
        val state = activated.state!!

        val missingFreshEvidence = decide(index, state, BOB, "dynamic-legacy", 30L).result
        assertSame(WorkflowResultCode.REJECTED, missingFreshEvidence.code)
        assertEquals("authorization-denied", missingFreshEvidence.failureCode)

        val item = state.humanWorkItems.single()
        val snapshot = item.ruleSnapshots.single()
        val requestDigest = WorkflowHumanDecisionCommand.authorizationRequestDigest(
            item.workItemId,
            BOB,
            WorkflowHumanDecisionCode.APPROVE,
            item.revision,
        )
        val receipt = WorkflowHumanDecisionAuthorizationReceipt.currentMembership(
            "dynamic-current-authorization",
            state.tenantId,
            state.instanceId,
            state.definitionId,
            state.definitionRef,
            state.subject,
            item.workItemId,
            item.activeRuleIndex,
            BOB,
            WorkflowHumanDecisionCode.APPROVE,
            snapshot.activationDigest,
            requestDigest,
            WorkflowAuthorizationStatus.AUTHORIZED,
            "authority-v2",
            DIGEST_E,
            participantRule.contentDigest,
            participantRule.selector.digest,
            requireNotNull(snapshot.organizationAuthority),
            "revision-2",
            DIGEST_A,
            DIGEST_B,
            true,
            30L,
            130L,
        )
        val command = WorkflowHumanDecisionCommand.of(
            freshContext("dynamic-current", state.version, 30L),
            item.workItemId,
            BOB,
            WorkflowHumanDecisionCode.APPROVE,
            item.revision,
            receipt,
        )
        val decided = WorkflowDomainEngine.decideHumanTask(index, state, command)
        assertSame(WorkflowResultCode.APPLIED, decided.code)
        assertSame(WorkflowInstanceStatus.COMPLETED, decided.state!!.status)
    }

    @Test
    fun `ALL human approval fixes its denominator rejects duplicate actors and replays exact command`() {
        val definition = humanDefinition(
            listOf(rule("legal-reviewers", WorkflowApprovalPolicy.all())),
        )
        val index = WorkflowDefinitionIndex.compile(definition)
        val started = start(index, "all-start", 10L)
        assertSame(WorkflowResultCode.APPLIED, started.code)
        assertSame(WorkflowEffectCode.PARTICIPANT_RESOLUTION, started.effects.single().code)

        val initialState = started.state!!
        val candidates = mutableListOf(ALICE, BOB)
        val activationReceipt = activationReceipt(index, initialState, candidates, 20L, "all-activation")
        candidates.clear()

        val stale = WorkflowDomainEngine.activateHumanRule(
            index,
            initialState,
            WorkflowActivateHumanRuleCommand.of(
                freshContext("all-stale", initialState.version - 1L, 20L),
                initialState.humanWorkItems.single().workItemId,
                activationReceipt,
            ),
        )
        assertSame(WorkflowResultCode.VERSION_CONFLICT, stale.code)
        assertEquals("version-conflict", stale.failureCode)

        val activated = WorkflowDomainEngine.activateHumanRule(
            index,
            initialState,
            WorkflowActivateHumanRuleCommand.of(
                freshContext("all-activate", initialState.version, 20L),
                initialState.humanWorkItems.single().workItemId,
                activationReceipt,
            ),
        )
        assertSame(WorkflowResultCode.APPLIED, activated.code)
        val snapshot = activated.state!!.humanWorkItems.single().ruleSnapshots.single()
        assertEquals(2, snapshot.denominator)
        assertEquals(2, snapshot.requiredApprovals)
        assertEquals(listOf(ALICE, BOB), snapshot.candidates)
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.candidates as MutableList<WorkflowPrincipalRef>).clear()
        }

        val first = decide(index, activated.state!!, ALICE, "all-alice", 30L)
        assertSame(WorkflowResultCode.APPLIED, first.result.code)
        assertSame(WorkflowHumanWorkItemStatus.ACTIVE, first.result.state!!.humanWorkItems.single().status)

        val duplicate = decide(index, first.result.state!!, ALICE, "all-alice-duplicate", 35L)
        assertSame(WorkflowResultCode.REJECTED, duplicate.result.code)
        assertEquals("duplicate-decision", duplicate.result.failureCode)
        assertEquals(first.result.state, duplicate.result.state)

        val second = decide(index, first.result.state!!, BOB, "all-bob", 40L)
        assertSame(WorkflowResultCode.APPLIED, second.result.code)
        assertSame(WorkflowInstanceStatus.COMPLETED, second.result.state!!.status)
        assertSame(WorkflowHumanWorkItemStatus.APPROVED, second.result.state!!.humanWorkItems.single().status)

        val original = second.command
        val replayContext = WorkflowCommandContext.of(
            original.context.commandId,
            original.context.idempotencyKey,
            original.context.expectedInstanceVersion,
            original.context.now,
            original.context.iterationBudget,
            original.context.ids,
            WorkflowIdempotencyReceipt.applied(
                TENANT,
                INSTANCE,
                original.context.idempotencyKey,
                original.code,
                original.commandDigest,
                second.result.state!!.version,
                original.context.now,
            ),
        )
        val replayCommand = WorkflowHumanDecisionCommand.of(
            replayContext,
            original.workItemId,
            original.actor,
            original.decision,
            original.expectedWorkItemVersion,
            original.authorizationReceipt,
        )
        assertEquals(original.commandDigest, replayCommand.commandDigest)
        val replayed = WorkflowDomainEngine.decideHumanTask(index, second.result.state!!, replayCommand)
        assertSame(WorkflowResultCode.REPLAYED, replayed.code)
        assertEquals("idempotent-replay", replayed.failureCode)
        assertTrue(replayed.events.isEmpty())
        assertTrue(replayed.effects.isEmpty())
    }

    @Test
    fun `human rejection follows only the explicit rejected transition`() {
        val definition = humanDefinition(
            listOf(rule("legal-reviewers", WorkflowApprovalPolicy.one())),
        )
        val index = WorkflowDefinitionIndex.compile(definition)
        val started = start(index, "reject-start", 10L)
        val activated = activate(index, started.state!!, listOf(ALICE), "reject-activate", 20L)

        val rejected = decide(
            index,
            activated.state!!,
            ALICE,
            "reject-alice",
            30L,
            WorkflowHumanDecisionCode.REJECT,
        ).result

        assertSame(WorkflowResultCode.APPLIED, rejected.code)
        assertSame(WorkflowInstanceStatus.COMPLETED, rejected.state!!.status)
        assertSame(
            WorkflowHumanWorkItemStatus.REJECTED,
            rejected.state!!.humanWorkItems.single().status,
        )
        assertEquals("rejected", rejected.state!!.nodeExecutions.last().nodeId)
        assertTrue(rejected.state!!.nodeExecutions.none { execution -> execution.nodeId == "approved" })
    }

    @Test
    fun `ordered ONE rules require a fresh activation before each tier`() {
        val definition = humanDefinition(
            listOf(
                rule("business-owner", WorkflowApprovalPolicy.one()),
                rule("legal-owner", WorkflowApprovalPolicy.one()),
            ),
        )
        val index = WorkflowDefinitionIndex.compile(definition)
        val started = start(index, "tiers-start", 10L)
        val firstActivation = activate(index, started.state!!, listOf(ALICE), "tiers-first", 20L)
        val firstDecision = decide(index, firstActivation.state!!, ALICE, "tiers-alice", 30L).result

        assertSame(WorkflowInstanceStatus.WAITING, firstDecision.state!!.status)
        val waiting = firstDecision.state!!.humanWorkItems.single()
        assertSame(WorkflowHumanWorkItemStatus.WAITING_PARTICIPANTS, waiting.status)
        assertEquals(1, waiting.activeRuleIndex)
        assertEquals(1, waiting.ruleSnapshots.size)
        assertEquals(1, waiting.decisions.size)
        val nextEffect = firstDecision.effects.single()
        assertSame(WorkflowEffectCode.PARTICIPANT_RESOLUTION, nextEffect.code)
        assertEquals(1, nextEffect.ruleIndex)
        assertNotEquals(started.effects.single().payloadDigest, nextEffect.payloadDigest)

        val secondActivation = activate(index, firstDecision.state!!, listOf(BOB), "tiers-second", 40L)
        val secondDecision = decide(index, secondActivation.state!!, BOB, "tiers-bob", 50L).result
        assertSame(WorkflowInstanceStatus.COMPLETED, secondDecision.state!!.status)
        assertEquals(2, secondDecision.state!!.humanWorkItems.single().ruleSnapshots.size)
        assertEquals(2, secondDecision.state!!.humanWorkItems.single().decisions.size)
    }

    @Test
    fun `impossible QUORUM raises a durable incident without clamping`() {
        val definition = humanDefinition(
            listOf(rule("executive-board", WorkflowApprovalPolicy.quorum(3))),
        )
        val index = WorkflowDefinitionIndex.compile(definition)
        val started = start(index, "quorum-start", 10L)
        val result = activate(index, started.state!!, listOf(ALICE, BOB), "quorum-activate", 20L)

        assertSame(WorkflowResultCode.INCIDENT, result.code)
        assertEquals("quorum-impossible", result.failureCode)
        assertSame(WorkflowInstanceStatus.INCIDENT, result.state!!.status)
        assertSame(WorkflowHumanWorkItemStatus.INCIDENT, result.state!!.humanWorkItems.single().status)
        assertTrue(result.events.any { event -> event.code == WorkflowEventCode.INCIDENT_RAISED })
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `external service completes only through an exact receipt and rejects tampered restored state`() {
        val definition = linearDefinition(
            WorkflowNodeDefinition.serviceTask("service", "外部服务", null, DIGEST_A, DIGEST_B),
        )
        val index = WorkflowDefinitionIndex.compile(definition)
        val started = start(index, "service-start", 10L)
        val state = started.state!!
        val effect = started.effects.single()
        assertSame(WorkflowEffectCode.SERVICE_TASK, effect.code)
        assertSame(WorkflowInstanceStatus.WAITING, state.status)

        val execution = state.nodeExecutions.first { item -> item.executionId == effect.nodeExecutionId }
        val tamperedExecution = WorkflowNodeExecutionState.restore(
            execution.executionId,
            execution.tokenId,
            "end",
            execution.status,
            execution.revision,
            execution.startedAt,
            execution.completedAt,
            execution.pendingEffectId,
            execution.pendingEffectCode,
            execution.effectRequestDigest,
        )
        val tampered = WorkflowInstanceState.restore(
            state.tenantId,
            state.instanceId,
            state.definitionId,
            state.definitionRef,
            state.subject,
            state.initiator,
            state.status,
            state.version,
            state.createdAt,
            state.updatedAt,
            state.tokens,
            state.nodeExecutions.map { item ->
                if (item.executionId == tamperedExecution.executionId) tamperedExecution else item
            },
            state.humanWorkItems,
            state.pendingContinuationEffectId,
            state.pendingContinuationRequestDigest,
        )
        val validReceipt = effectSuccess(state, effect, null, 20L, "service-success")
        val againstTampered = WorkflowDomainEngine.completeEffect(
            index,
            tampered,
            WorkflowCompleteEffectCommand.of(
                freshContext("service-tampered", tampered.version, 20L),
                validReceipt,
            ),
        )
        assertSame(WorkflowResultCode.REJECTED, againstTampered.code)
        assertEquals("state-binding-mismatch", againstTampered.failureCode)

        val backwards = WorkflowDomainEngine.completeEffect(
            index,
            state,
            WorkflowCompleteEffectCommand.of(
                freshContext("service-backwards", state.version, 9L),
                effectSuccess(state, effect, null, 9L, "service-old"),
            ),
        )
        assertSame(WorkflowResultCode.REJECTED, backwards.code)
        assertEquals("command-time-before-state", backwards.failureCode)

        val completed = WorkflowDomainEngine.completeEffect(
            index,
            state,
            WorkflowCompleteEffectCommand.of(
                freshContext("service-complete", state.version, 20L),
                validReceipt,
            ),
        )
        assertSame(WorkflowResultCode.APPLIED, completed.code)
        assertSame(WorkflowInstanceStatus.COMPLETED, completed.state!!.status)
        assertTrue(completed.events.any { event -> event.code == WorkflowEventCode.EFFECT_COMPLETED })
    }

    @Test
    fun `exclusive choice is provider-evaluated and unknown transitions fail closed`() {
        val definition = exclusiveDefinition()
        val index = WorkflowDefinitionIndex.compile(definition)
        val started = start(index, "exclusive-start", 10L)
        val effect = started.effects.single()
        assertSame(WorkflowEffectCode.EXCLUSIVE_EVALUATION, effect.code)

        val invalid = WorkflowDomainEngine.completeEffect(
            index,
            started.state!!,
            WorkflowCompleteEffectCommand.of(
                freshContext("exclusive-invalid", started.state!!.version, 20L),
                effectSuccess(started.state!!, effect, "missing-transition", 20L, "exclusive-bad"),
            ),
        )
        assertSame(WorkflowResultCode.REJECTED, invalid.code)
        assertEquals("transition-selection-mismatch", invalid.failureCode)
        assertEquals(started.state, invalid.state)

        val selected = WorkflowDomainEngine.completeEffect(
            index,
            started.state!!,
            WorkflowCompleteEffectCommand.of(
                freshContext("exclusive-selected", started.state!!.version, 30L),
                effectSuccess(started.state!!, effect, "route-accepted", 30L, "exclusive-good"),
            ),
        )
        assertSame(WorkflowInstanceStatus.COMPLETED, selected.state!!.status)
        assertEquals("accepted", selected.state!!.tokens.single { token ->
            token.status == WorkflowTokenStatus.COMPLETED
        }.nodeId)
    }

    @Test
    fun `parallel branches retain one correlation scope and join only after every effect completes`() {
        val index = WorkflowDefinitionIndex.compile(parallelDefinition())
        val started = start(index, "parallel-start", 10L)
        assertEquals(2, started.effects.size)
        assertTrue(started.effects.all { effect -> effect.code == WorkflowEffectCode.SERVICE_TASK })
        val branchTokens = started.state!!.tokens.filter { token -> token.parallelFrames.isNotEmpty() }
        assertEquals(2, branchTokens.size)
        assertEquals(1, branchTokens.map { token -> token.parallelFrames.single().scopeId }.toSet().size)
        assertEquals(setOf(0, 1), branchTokens.map { token -> token.parallelFrames.single().branchIndex }.toSet())

        val firstEffect = started.effects.first { effect -> effect.nodeId == "branch-a" }
        val first = WorkflowDomainEngine.completeEffect(
            index,
            started.state!!,
            WorkflowCompleteEffectCommand.of(
                freshContext("parallel-a", started.state!!.version, 20L),
                effectSuccess(started.state!!, firstEffect, null, 20L, "parallel-a"),
            ),
        )
        assertSame(WorkflowInstanceStatus.WAITING, first.state!!.status)
        assertEquals(1, first.state!!.tokens.count { token -> token.status == WorkflowTokenStatus.WAITING_JOIN })
        assertTrue(first.events.none { event -> event.code == WorkflowEventCode.TOKEN_JOINED })

        val secondEffectId = started.effects.first { effect -> effect.nodeId == "branch-b" }.effectId
        val secondExecution = first.state!!.nodeExecutions.single { execution ->
            execution.pendingEffectId == secondEffectId
        }
        val secondEffect = started.effects.first { effect -> effect.effectId == secondEffectId }
        assertEquals(secondExecution.effectRequestDigest, secondEffect.requestDigest)
        val second = WorkflowDomainEngine.completeEffect(
            index,
            first.state!!,
            WorkflowCompleteEffectCommand.of(
                freshContext("parallel-b", first.state!!.version, 30L),
                effectSuccess(first.state!!, secondEffect, null, 30L, "parallel-b"),
            ),
        )
        assertSame(WorkflowInstanceStatus.COMPLETED, second.state!!.status)
        assertTrue(second.events.any { event -> event.code == WorkflowEventCode.TOKEN_JOINED })
        assertEquals(2, second.state!!.tokens.count { token ->
            token.parallelFrames.isNotEmpty() && token.status == WorkflowTokenStatus.CONSUMED
        })
    }

    @Test
    fun `iteration budget persists a continuation before resuming deterministic advancement`() {
        val index = WorkflowDefinitionIndex.compile(
            linearDefinition(WorkflowNodeDefinition.serviceTask("service", "服务", null, DIGEST_A, DIGEST_B)),
        )
        val started = start(index, "budget-start", 10L, budget = 1)
        assertSame(WorkflowResultCode.BUDGET_EXHAUSTED, started.code)
        assertSame(WorkflowInstanceStatus.WAITING, started.state!!.status)
        val continuation = started.effects.single()
        assertSame(WorkflowEffectCode.CONTINUE_EXECUTION, continuation.code)
        assertEquals(continuation.effectId, started.state!!.pendingContinuationEffectId)
        assertEquals(continuation.requestDigest, started.state!!.pendingContinuationRequestDigest)

        val receipt = WorkflowContinuationReceipt.of(
            "budget-receipt",
            continuation.effectId,
            TENANT,
            INSTANCE,
            DEFINITION,
            started.state!!.definitionRef,
            SUBJECT,
            continuation.requestDigest,
            20L,
        )
        val resumed = WorkflowDomainEngine.continueExecution(
            index,
            started.state!!,
            WorkflowContinueCommand.of(
                freshContext("budget-continue", started.state!!.version, 20L),
                receipt,
            ),
        )
        assertSame(WorkflowResultCode.APPLIED, resumed.code)
        assertNull(resumed.state!!.pendingContinuationEffectId)
        assertSame(WorkflowEffectCode.SERVICE_TASK, resumed.effects.single().code)
        assertTrue(resumed.events.any { event -> event.code == WorkflowEventCode.EFFECT_COMPLETED })
    }

    @Test
    fun `unknown node kinds stay representable but emit only an extension intent`() {
        val definition = linearDefinition(
            WorkflowNodeDefinition.extension(
                "vendor-task",
                WorkflowNodeKind.of("vendor.contract-review"),
                "供应商复核",
                null,
                DIGEST_A,
                DIGEST_B,
            ),
        )
        val started = start(WorkflowDefinitionIndex.compile(definition), "extension-start", 10L)
        assertSame(WorkflowEffectCode.EXTENSION, started.effects.single().code)
        assertEquals("vendor-task", started.effects.single().nodeId)
        assertEquals("WorkflowEffectIntent(<redacted>)", started.effects.single().toString())
        assertEquals("WorkflowInstanceState(<redacted>)", started.state.toString())
    }

    private fun start(
        index: WorkflowDefinitionIndex,
        prefix: String,
        now: Long,
        budget: Int = 64,
    ): WorkflowDomainResult {
        val definition = index.definition
        val receipt = WorkflowDefinitionExecutionReceipt.of(
            "$prefix-deployment",
            TENANT,
            DEFINITION,
            definition.ref,
            definition.schemaVersion,
            DIGEST_C,
            now,
            now + 1_000L,
        )
        val command = WorkflowStartCommand.of(
            freshContext(prefix, 0L, now, budget),
            TENANT,
            INSTANCE,
            DEFINITION,
            definition.ref,
            SUBJECT,
            INITIATOR,
            receipt,
        )
        return WorkflowDomainEngine.start(index, command)
    }

    private fun activate(
        index: WorkflowDefinitionIndex,
        state: WorkflowInstanceState,
        candidates: List<WorkflowPrincipalRef>,
        prefix: String,
        now: Long,
    ): WorkflowDomainResult {
        val receipt = activationReceipt(index, state, candidates, now, prefix)
        return WorkflowDomainEngine.activateHumanRule(
            index,
            state,
            WorkflowActivateHumanRuleCommand.of(
                freshContext(prefix, state.version, now),
                state.humanWorkItems.single().workItemId,
                receipt,
            ),
        )
    }

    private fun activationReceipt(
        index: WorkflowDefinitionIndex,
        state: WorkflowInstanceState,
        candidates: Collection<WorkflowPrincipalRef>,
        now: Long,
        prefix: String,
    ): WorkflowParticipantActivationReceipt {
        val item = state.humanWorkItems.single()
        val execution = state.nodeExecutions.single { value -> value.executionId == item.nodeExecutionId }
        val rule = index.node(item.nodeId).humanTaskPolicy!!.participantRules[item.activeRuleIndex]
        return WorkflowParticipantActivationReceipt.organizationBound(
            "$prefix-receipt",
            execution.pendingEffectId!!,
            state.tenantId,
            state.instanceId,
            state.definitionId,
            state.definitionRef,
            state.subject,
            item.tokenId,
            item.nodeExecutionId,
            item.workItemId,
            item.nodeId,
            item.activeRuleIndex,
            rule.contentDigest,
            execution.effectRequestDigest!!,
            candidates,
            DIGEST_D,
            "test-directory",
            "revision-1",
            DIGEST_C,
            rule.selector.digest,
            now,
            now + 100L,
        )
    }

    private fun decide(
        index: WorkflowDefinitionIndex,
        state: WorkflowInstanceState,
        actor: WorkflowPrincipalRef,
        prefix: String,
        now: Long,
        decision: WorkflowHumanDecisionCode = WorkflowHumanDecisionCode.APPROVE,
    ): DecisionResult {
        val item = state.humanWorkItems.single()
        val requestDigest = WorkflowHumanDecisionCommand.authorizationRequestDigest(
            item.workItemId,
            actor,
            decision,
            item.revision,
        )
        val receipt = WorkflowHumanDecisionAuthorizationReceipt.of(
            "$prefix-authorization",
            state.tenantId,
            state.instanceId,
            state.definitionId,
            state.definitionRef,
            state.subject,
            item.workItemId,
            item.activeRuleIndex,
            actor,
            decision,
            item.ruleSnapshots[item.activeRuleIndex].activationDigest,
            requestDigest,
            WorkflowAuthorizationStatus.AUTHORIZED,
            "authority-v1",
            DIGEST_E,
            now,
            now + 100L,
        )
        val command = WorkflowHumanDecisionCommand.of(
            freshContext(prefix, state.version, now),
            item.workItemId,
            actor,
            decision,
            item.revision,
            receipt,
        )
        return DecisionResult(WorkflowDomainEngine.decideHumanTask(index, state, command), command)
    }

    private fun effectSuccess(
        state: WorkflowInstanceState,
        effect: WorkflowEffectIntent,
        selectedTransitionId: String?,
        now: Long,
        prefix: String,
    ): WorkflowEffectCompletionReceipt = WorkflowEffectCompletionReceipt.success(
        "$prefix-receipt",
        effect.effectId,
        effect.code,
        state.tenantId,
        state.instanceId,
        state.definitionId,
        state.definitionRef,
        state.subject,
        effect.tokenId!!,
        effect.nodeExecutionId!!,
        effect.nodeId!!,
        effect.requestDigest,
        selectedTransitionId,
        DIGEST_F,
        now,
    )

    private fun freshContext(
        prefix: String,
        expectedVersion: Long,
        now: Long,
        budget: Int = 64,
    ): WorkflowCommandContext {
        val key = "$prefix-idempotency"
        return WorkflowCommandContext.of(
            "$prefix-command",
            key,
            expectedVersion,
            now,
            budget,
            executionIds(prefix),
            WorkflowIdempotencyReceipt.fresh(TENANT, INSTANCE, key, now),
        )
    }

    private fun executionIds(prefix: String): WorkflowExecutionIds = WorkflowExecutionIds.of(
        (0 until 128).map { index -> "$prefix-token-$index" },
        (0 until 128).map { index -> "$prefix-execution-$index" },
        (0 until 32).map { index -> "$prefix-work-item-$index" },
        (0 until 128).map { index -> "$prefix-effect-$index" },
        (0 until 512).map { index -> "$prefix-event-$index" },
        (0 until 32).map { index -> "$prefix-scope-$index" },
    )

    private fun humanDefinition(
        rules: List<WorkflowHumanTaskParticipantRule>,
        resolutionStages: List<WorkflowParticipantResolutionStage> =
            listOf(WorkflowParticipantResolutionStage.ACTIVATION),
    ): WorkflowDefinition {
        val policy = WorkflowHumanTaskPolicy.of(
            rules,
            WorkflowHumanTaskCapabilities.of(false, false, false, false),
            WorkflowSeparationOfDutiesPolicy.of(true, true),
            resolutionStages,
        )
        return definition(
            listOf(
                structural("start", WorkflowNodeKind.START, "开始"),
                WorkflowNodeDefinition.humanTask("review", "审批", null, policy),
                structural("approved", WorkflowNodeKind.END, "通过"),
                structural("rejected", WorkflowNodeKind.END, "驳回"),
            ),
            listOf(
                edge("start-review", "start", "review"),
                WorkflowTransitionDefinition.unconditional(
                    "review-approved",
                    "review",
                    "approved",
                    WorkflowTransitionTrigger.APPROVED,
                ),
                WorkflowTransitionDefinition.unconditional(
                    "review-rejected",
                    "review",
                    "rejected",
                    WorkflowTransitionTrigger.REJECTED,
                ),
            ),
        )
    }

    private fun rule(group: String, approval: WorkflowApprovalPolicy): WorkflowHumanTaskParticipantRule =
        WorkflowHumanTaskParticipantRule.of(WorkflowParticipantSelector.group(group), approval)

    private fun linearDefinition(middle: WorkflowNodeDefinition): WorkflowDefinition = definition(
        listOf(
            structural("start", WorkflowNodeKind.START, "开始"),
            middle,
            structural("end", WorkflowNodeKind.END, "结束"),
        ),
        listOf(edge("start-middle", "start", middle.nodeId), edge("middle-end", middle.nodeId, "end")),
    )

    private fun exclusiveDefinition(): WorkflowDefinition {
        val predicate = WorkflowPredicateRef.of("rules", "accepted", "v1", DIGEST_A, emptyList())
        return definition(
            listOf(
                structural("start", WorkflowNodeKind.START, "开始"),
                structural("route", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "路由"),
                structural("accepted", WorkflowNodeKind.END, "通过"),
                structural("fallback", WorkflowNodeKind.END, "兜底"),
            ),
            listOf(
                edge("start-route", "start", "route"),
                WorkflowTransitionDefinition.conditional("route-accepted", "route", "accepted", predicate),
                edge("route-fallback", "route", "fallback"),
            ),
        )
    }

    private fun parallelDefinition(): WorkflowDefinition = definition(
        listOf(
            structural("start", WorkflowNodeKind.START, "开始"),
            WorkflowNodeDefinition.parallelSplit("split", "join", "拆分", null),
            WorkflowNodeDefinition.serviceTask("branch-a", "分支甲", null, DIGEST_A, DIGEST_B),
            WorkflowNodeDefinition.serviceTask("branch-b", "分支乙", null, DIGEST_A, DIGEST_C),
            WorkflowNodeDefinition.parallelJoin("join", "split", "汇聚", null),
            structural("end", WorkflowNodeKind.END, "结束"),
        ),
        listOf(
            edge("start-split", "start", "split"),
            edge("split-a", "split", "branch-a"),
            edge("split-b", "split", "branch-b"),
            edge("a-join", "branch-a", "join"),
            edge("b-join", "branch-b", "join"),
            edge("join-end", "join", "end"),
        ),
    )

    private fun definition(
        nodes: List<WorkflowNodeDefinition>,
        transitions: List<WorkflowTransitionDefinition>,
    ): WorkflowDefinition = WorkflowDefinition.of(
        TENANT,
        DEFINITION,
        "domain-engine",
        "v1",
        1,
        WorkflowDefinitionStatus.DRAFT,
        "通用审批流程",
        null,
        nodes,
        transitions,
    )

    private fun structural(id: String, kind: WorkflowNodeKind, title: String): WorkflowNodeDefinition =
        WorkflowNodeDefinition.of(id, kind, title, null)

    private fun edge(id: String, from: String, to: String): WorkflowTransitionDefinition =
        WorkflowTransitionDefinition.unconditional(id, from, to)

    private class DecisionResult(
        val result: WorkflowDomainResult,
        val command: WorkflowHumanDecisionCommand,
    )

    private companion object {
        const val TENANT = "tenant-tianjin"
        const val INSTANCE = "instance-001"
        const val DEFINITION = "definition-001"
        const val DIGEST_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DIGEST_B = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        const val DIGEST_C = "1111111111111111111111111111111111111111111111111111111111111111"
        const val DIGEST_D = "2222222222222222222222222222222222222222222222222222222222222222"
        const val DIGEST_E = "3333333333333333333333333333333333333333333333333333333333333333"
        const val DIGEST_F = "4444444444444444444444444444444444444444444444444444444444444444"
        val INITIATOR: WorkflowPrincipalRef = WorkflowPrincipalRef.of("user", "initiator")
        val ALICE: WorkflowPrincipalRef = WorkflowPrincipalRef.of("user", "alice")
        val BOB: WorkflowPrincipalRef = WorkflowPrincipalRef.of("user", "bob")
        val SUBJECT: WorkflowSubjectSnapshot = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("business-record", "record-001"),
            "revision-7",
            DIGEST_A,
        )
    }
}
