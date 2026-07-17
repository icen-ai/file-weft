package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.*
import ai.icen.fw.core.id.Identifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Java 8 durable Agent coordinator. Every remote call is preceded by a successful checkpoint/claim
 * commit and is started only after the store method has returned.
 */
class DurableAgentRunCoordinator @JvmOverloads constructor(
    private val store: AgentDurableRunStore,
    private val admissionPort: AgentRunAdmissionPort,
    private val continuationAuthorizationPort: AgentRunContinuationAuthorizationPort,
    private val commandAuthorizationPort: AgentRunCommandAuthorizationPort,
    private val contentSecurityPort: AgentContentSecurityPort,
    private val modelSelection: AgentModelSelectionPort,
    private val modelProviders: AgentLanguageModelProviderRegistry,
    private val toolPlanResolver: AgentToolPlanResolver,
    private val authorizationProviders: AgentAuthorizationProviderRegistry,
    private val policyProviders: AgentPolicyProviderRegistry,
    private val executionContextConsumer: AgentExecutionContextConsumer,
    private val toolExecutors: AgentToolExecutorRegistry,
    private val providerFailureMapper: AgentProviderFailureMapper,
    private val clock: AgentRuntimeClock,
    private val ids: AgentRuntimeIdGenerator,
    private val executor: Executor,
    private val workerId: ProviderId,
    private val configuration: AgentRuntimeConfiguration = AgentRuntimeConfiguration(),
) : AgentRunService {
    private val calls = ConcurrentHashMap<AgentRunKey, CopyOnWriteArrayList<DurableRunCall>>()
    private val activeCalls = ConcurrentHashMap<AgentRunKey, ActiveExternalCall>()

    override fun start(request: AgentRunRequest, observer: AgentRunObserver): AgentRunCall {
        request.requireBindingIntact()
        val scope = AgentRunIdempotencyScope.from(request)
        val replayDigest = runtimeIdempotencyReplayDigest(request, scope)
        val now = clock.currentTimeMillis()
        request.cancellationToken.cancellation()?.let { cancellation ->
            throw AgentCancellationException(cancellation)
        }
        val admissionRequest = AgentRunAdmissionRequest.create(ids.nextId("agent-admission-request"), request, now)
        val admission = try {
            admissionPort.admit(admissionRequest)
        } catch (failure: AgentRunAdmissionException) {
            throw failure
        } catch (_: RuntimeException) {
            throw AgentRunAdmissionException("admission.provider-failed")
        }
        require(admission.providerId == admissionPort.providerId()) {
            "Agent admission decision came from another provider."
        }
        if (admission.outcome != AgentRunAdmissionOutcome.ALLOW) {
            throw AgentRunAdmissionException(admission.reasonCode ?: "admission.denied")
        }
        admission.requireAllowedFor(admissionRequest, now)

        // Idempotent replay is still a new access to an existing run. Authorize the current
        // trusted request first so a revoked principal cannot use an old key as a read/resume
        // capability and so existence is not disclosed before authorization succeeds.
        val existing = store.findByIdempotency(scope)
        if (existing != null) {
            require(existing.idempotencyScope == scope && runtimeIdempotencyReplayDigest(existing) == replayDigest) {
                "Agent idempotency key was reused with a different admitted request."
            }
            val replay = register(existing, observer)
            if (!existing.status.isTerminal()) schedule(existing.tenantId, existing.runId)
            return replay
        }

        val runId = ids.nextId("agent-run")
        val initial = AgentDurableRunState.initial(runId, request, admissionRequest, admission, now)
        val initialEvent = AgentRunStatusChangedEvent(
            runId,
            request.context.tenantId,
            1L,
            now,
            null,
            AgentRunStatus.QUEUED,
            "run.admitted",
        )
        val created = store.create(AgentRunCreateCommit(initial, initialEvent))
        require(created.state.idempotencyScope == scope) {
            "Agent idempotency replay returned a different trusted scope."
        }
        require(runtimeIdempotencyReplayDigest(created.state) == replayDigest) {
            "Agent idempotency key was concurrently reused with a different admitted request."
        }
        val call = register(created.state, observer)
        if (created.status == AgentRunCreateStatus.CREATED) observer.onEvent(initialEvent)
        if (!created.state.status.isTerminal()) schedule(created.state.tenantId, created.state.runId)
        return call
    }

    fun replay(context: AgentRunCommandContext, runId: Identifier, observer: AgentRunObserver): AgentRunCall {
        authorizeCommand(context, runId, AgentRunCommandAction.REPLAY, null, null)
        val key = AgentRunKey(context.tenantId, runId)
        val state = requireNotNull(store.load(key)) { "Durable Agent run does not exist." }
        val call = register(state, observer, context)
        if (!state.status.isTerminal()) schedule(context.tenantId, runId)
        return call
    }

    /** Schedules every currently recoverable run; claimed external side effects remain fail-closed. */
    @JvmOverloads
    fun recover(limit: Int = 100, observer: AgentRunObserver = AgentRunObserver.NOOP): List<AgentRunCall> {
        require(limit in 1..MAX_RUNTIME_ITEMS) { "Agent recovery limit is invalid." }
        val states = store.recoverable(clock.currentTimeMillis(), limit)
        return states.map { state ->
            register(state, observer).also { if (!state.status.isTerminal()) schedule(state.tenantId, state.runId) }
        }
    }

    fun confirmApproval(
        context: AgentRunCommandContext,
        runId: Identifier,
        expectedStateVersion: Long,
        decision: AgentApprovalDecision,
    ): AgentApprovalConfirmationResult {
        require(decision.tenantId == context.tenantId && decision.runId == runId &&
            decision.operatorId == context.principalId && decision.operatorType == context.principalType
        ) { "Agent approval command does not match its trusted operator context." }
        authorizeCommand(
            context,
            runId,
            AgentRunCommandAction.APPROVE,
            expectedStateVersion,
            approvalEvidenceDigest(decision),
        )
        val key = AgentRunKey(context.tenantId, runId)
        val state = store.load(key) ?: return AgentApprovalConfirmationResult(AgentApprovalConfirmationStatus.MISSING, null)
        val pending = state.pendingOperation as? AgentPendingToolOperation
            ?: return AgentApprovalConfirmationResult(AgentApprovalConfirmationStatus.NOT_WAITING, state.snapshot())
        if (pending.phase != AgentPendingToolPhase.WAITING_APPROVAL || state.status != AgentRunStatus.WAITING_APPROVAL) {
            if (pending.approvalDecision?.decisionId == decision.decisionId) {
                return AgentApprovalConfirmationResult(AgentApprovalConfirmationStatus.REPLAYED, state.snapshot())
            }
            return AgentApprovalConfirmationResult(AgentApprovalConfirmationStatus.NOT_WAITING, state.snapshot())
        }
        if (state.stateVersion != expectedStateVersion) {
            return AgentApprovalConfirmationResult(AgentApprovalConfirmationStatus.VERSION_CONFLICT, state.snapshot())
        }
        val request = requireNotNull(pending.approvalRequest)
        val proposal = requireNotNull(pending.proposal)
        val policy = requireNotNull(pending.policyDecision)
        val now = clock.currentTimeMillis()
        request.requireValidFor(proposal, policy, now)
        decision.requireValidFor(request, now)
        if (decision.outcome == AgentApprovalOutcome.REJECTED) {
            val sequence = state.eventSequence + 1L
            val event = AgentRunStatusChangedEvent(
                state.runId,
                state.tenantId,
                sequence,
                now,
                state.status,
                AgentRunStatus.FAILED,
                decision.reasonCode ?: "approval.rejected",
            )
            val rejected = state.evolve(
                status = AgentRunStatus.FAILED,
                eventSequence = sequence,
                updatedAt = now,
                steps = replaceStep(
                    state,
                    pending.stepId,
                    AgentRuntimeStepStatus.FAILED,
                    pending.attempt,
                    now,
                ),
                currentStepId = null,
                pendingOperation = null,
                lease = null,
                failure = terminalFailure(
                    AgentFailureCategory.AUTHORIZATION,
                    decision.reasonCode ?: "approval.rejected",
                ),
            )
            val rejectedResult = store.commit(
                AgentStoreCommit(
                    key,
                    state.stateVersion,
                    state.eventSequence,
                    AgentStoreCommitAuthority.TRUSTED_COMMAND,
                    null,
                    now,
                    rejected,
                    listOf(event),
                ),
            )
            return when (rejectedResult.status) {
                AgentStoreCommitStatus.APPLIED -> {
                    publish(key, rejectedResult.state!!, listOf(event))
                    AgentApprovalConfirmationResult(
                        AgentApprovalConfirmationStatus.APPLIED,
                        rejectedResult.state.snapshot(),
                    )
                }
                AgentStoreCommitStatus.MISSING ->
                    AgentApprovalConfirmationResult(AgentApprovalConfirmationStatus.MISSING, null)
                AgentStoreCommitStatus.VERSION_CONFLICT, AgentStoreCommitStatus.LEASE_LOST ->
                    AgentApprovalConfirmationResult(
                        AgentApprovalConfirmationStatus.VERSION_CONFLICT,
                        rejectedResult.state?.snapshot(),
                    )
            }
        }
        decision.requireApprovedFor(request, proposal, policy, now)
        val recheckExpiry = minOf(pending.plan.deadlineAt, policy.expiresAt, request.expiresAt)
        require(recheckExpiry > now) { "Agent approval expired before execution authorization recheck." }
        val recheck = AgentAuthorizationRequest.executionRecheck(
            ids.nextId("agent-authorization-execution-request"),
            pending.preflightRequest,
            now,
            recheckExpiry,
        )
        val checkpointId = ids.nextId("agent-runtime-checkpoint")
        val nextPending = pending.with(
            phase = AgentPendingToolPhase.EXECUTION_RECHECK_CHECKPOINTED,
            checkpointId = checkpointId,
            claimedLeaseId = null,
            approvalDecision = decision,
            executionRecheck = recheck,
            updatedAt = now,
        )
        val checkpoint = AgentRuntimeCheckpoint(
            checkpointId,
            state.runId,
            state.tenantId,
            nextPending.stepId,
            nextPending.operationId,
            "tool.execution-recheck.checkpointed",
            nextPending.operationDigest,
            state.checkpointSequence + 1L,
            now,
        )
        val steps = replaceStep(
            state,
            pending.stepId,
            AgentRuntimeStepStatus.CHECKPOINTED,
            pending.attempt,
            now,
        )
        val events = ArrayList<AgentRunEvent>(2)
        var sequence = state.eventSequence
        events += AgentRunStatusChangedEvent(
            state.runId,
            state.tenantId,
            ++sequence,
            now,
            state.status,
            AgentRunStatus.RUNNING,
            "approval.confirmed",
        )
        events += AgentRuntimeCheckpointEvent(state.runId, state.tenantId, ++sequence, now, checkpoint)
        val next = state.evolve(
            status = AgentRunStatus.RUNNING,
            eventSequence = sequence,
            checkpointSequence = state.checkpointSequence + 1L,
            updatedAt = now,
            steps = steps,
            checkpoints = state.checkpoints + checkpoint,
            pendingOperation = nextPending,
            lease = null,
        )
        val result = store.commit(
            AgentStoreCommit(
                key,
                state.stateVersion,
                state.eventSequence,
                AgentStoreCommitAuthority.TRUSTED_COMMAND,
                null,
                now,
                next,
                events,
            ),
        )
        return when (result.status) {
            AgentStoreCommitStatus.APPLIED -> {
                publish(key, result.state!!, events)
                schedule(context.tenantId, runId)
                AgentApprovalConfirmationResult(AgentApprovalConfirmationStatus.APPLIED, result.state.snapshot())
            }
            AgentStoreCommitStatus.VERSION_CONFLICT -> {
                val current = result.state!!
                val currentPending = current.pendingOperation as? AgentPendingToolOperation
                val status = if (currentPending?.approvalDecision?.decisionId == decision.decisionId) {
                    AgentApprovalConfirmationStatus.REPLAYED
                } else {
                    AgentApprovalConfirmationStatus.VERSION_CONFLICT
                }
                AgentApprovalConfirmationResult(status, current.snapshot())
            }
            AgentStoreCommitStatus.MISSING -> AgentApprovalConfirmationResult(AgentApprovalConfirmationStatus.MISSING, null)
            AgentStoreCommitStatus.LEASE_LOST ->
                AgentApprovalConfirmationResult(AgentApprovalConfirmationStatus.VERSION_CONFLICT, result.state!!.snapshot())
        }
    }

    fun reconcile(
        context: AgentRunCommandContext,
        runId: Identifier,
        expectedStateVersion: Long,
        decision: AgentReconciliationDecision,
    ): AgentReconciliationApplyResult {
        require(decision.tenantId == context.tenantId && decision.runId == runId) {
            "Agent reconciliation command does not match its trusted context."
        }
        authorizeCommand(
            context,
            runId,
            AgentRunCommandAction.RECONCILE,
            expectedStateVersion,
            reconciliationEvidenceDigest(decision),
        )
        val key = AgentRunKey(context.tenantId, runId)
        val state = store.load(key) ?: return AgentReconciliationApplyResult(AgentReconciliationApplyStatus.MISSING, null)
        val pending = state.pendingOperation
            ?: return AgentReconciliationApplyResult(AgentReconciliationApplyStatus.NOT_WAITING, state.snapshot())
        if (state.status != AgentRunStatus.WAITING_TOOL || state.stateVersion != expectedStateVersion) {
            return AgentReconciliationApplyResult(AgentReconciliationApplyStatus.VERSION_CONFLICT, state.snapshot())
        }
        require(decision.runId == state.runId && decision.tenantId == state.tenantId &&
            decision.stepId == pending.stepId && decision.operationDigest == pending.operationDigest
        ) { "Agent reconciliation decision does not match the pending operation." }
        if (decision.outcome == AgentReconciliationOutcome.STILL_UNKNOWN) {
            return AgentReconciliationApplyResult(AgentReconciliationApplyStatus.STILL_UNKNOWN, state.snapshot())
        }
        val now = clock.currentTimeMillis()
        require(decision.decidedAt <= now) { "Agent reconciliation decision is from the future." }
        val resolvedIncidents = state.incidents.map { incident ->
            if (incident.status == AgentRuntimeIncidentStatus.OPEN && incident.stepId == pending.stepId) {
                AgentRuntimeIncident(
                    incident.incidentId,
                    incident.runId,
                    incident.tenantId,
                    incident.stepId,
                    incident.code,
                    AgentRuntimeIncidentStatus.RESOLVED,
                    incident.retryable,
                    incident.createdAt,
                    now,
                )
            } else incident
        }
        val nextStatus = if (decision.outcome == AgentReconciliationOutcome.SUCCEEDED) {
            AgentRunStatus.RUNNING
        } else {
            AgentRunStatus.FAILED
        }
        val failure = if (nextStatus == AgentRunStatus.FAILED) {
            terminalFailure(AgentFailureCategory.PERMANENT, "reconciliation.failed")
        } else null
        val nextSteps = replaceStep(
            state,
            pending.stepId,
            if (nextStatus == AgentRunStatus.RUNNING) AgentRuntimeStepStatus.COMPLETED else AgentRuntimeStepStatus.FAILED,
            pending.attempt,
            now,
        )
        val messages = ArrayList(state.messages)
        val events = ArrayList<AgentRunEvent>(2)
        var sequence = state.eventSequence
        if (nextStatus == AgentRunStatus.RUNNING && pending is AgentPendingToolOperation) {
            val reconciledBlock = AgentToolResultContentBlock(
                pending.plan.call.callId,
                pending.plan.descriptor.toolId,
                AgentToolResultStatus.SUCCEEDED,
                listOf(
                    AgentTextContentBlock(
                        AgentContentOrigin.TOOL,
                        "Tool outcome was reconciled as succeeded.",
                    ),
                ),
            )
            val reconciledMessage = AgentMessage(
                ids.nextId("agent-tool-reconciliation-message"),
                AgentMessageRole.TOOL,
                listOf(reconciledBlock),
                now,
            )
            messages += reconciledMessage
            events += AgentRunMessageEvent(
                state.runId,
                state.tenantId,
                ++sequence,
                now,
                reconciledMessage,
            )
        }
        events += AgentRunStatusChangedEvent(
            state.runId,
            state.tenantId,
            ++sequence,
            now,
            state.status,
            nextStatus,
            "reconciliation.applied",
        )
        val next = state.evolve(
            messages = messages,
            status = nextStatus,
            eventSequence = sequence,
            updatedAt = now,
            steps = nextSteps,
            currentStepId = null,
            pendingOperation = null,
            lease = null,
            failure = failure,
            incidents = resolvedIncidents,
        )
        val result = store.commit(
            AgentStoreCommit(
                key,
                state.stateVersion,
                state.eventSequence,
                AgentStoreCommitAuthority.TRUSTED_COMMAND,
                null,
                now,
                next,
                events,
            ),
        )
        if (result.status != AgentStoreCommitStatus.APPLIED) {
            return AgentReconciliationApplyResult(AgentReconciliationApplyStatus.VERSION_CONFLICT, result.state?.snapshot())
        }
        publish(key, result.state!!, events)
        if (nextStatus == AgentRunStatus.RUNNING) schedule(context.tenantId, runId)
        return AgentReconciliationApplyResult(AgentReconciliationApplyStatus.APPLIED, result.state.snapshot())
    }

    internal fun cancel(key: AgentRunKey, cancellation: AgentCancellation): Boolean {
        activeCalls[key]?.cancel(cancellation)
        repeat(3) {
            val state = store.load(key) ?: return false
            if (state.status.isTerminal()) return false
            val now = clock.currentTimeMillis()
            val dispatched = (state.pendingOperation as? AgentPendingToolOperation)
                ?.takeIf { pending ->
                    pending.dispatchFenceConsumption != null
                }
            if (dispatched != null) {
                if (state.cancellation != null) return false
                val pending = dispatched.with(
                    phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED,
                    updatedAt = now,
                )
                val incident = AgentRuntimeIncident(
                    ids.nextId("agent-incident"),
                    state.runId,
                    state.tenantId,
                    pending.stepId,
                    "tool.cancellation-outcome-unknown",
                    AgentRuntimeIncidentStatus.OPEN,
                    false,
                    now,
                )
                var sequence = state.eventSequence
                val events = ArrayList<AgentRunEvent>(2)
                if (state.status != AgentRunStatus.WAITING_TOOL) {
                    events += AgentRunStatusChangedEvent(
                        state.runId,
                        state.tenantId,
                        ++sequence,
                        now,
                        state.status,
                        AgentRunStatus.WAITING_TOOL,
                        "tool.cancellation-outcome-unknown",
                    )
                }
                events += AgentRuntimeIncidentEvent(state.runId, state.tenantId, ++sequence, now, incident)
                val next = state.evolve(
                    status = AgentRunStatus.WAITING_TOOL,
                    eventSequence = sequence,
                    updatedAt = now,
                    steps = replaceStep(
                        state,
                        pending.stepId,
                        AgentRuntimeStepStatus.WAITING_RECONCILIATION,
                        pending.attempt,
                        now,
                    ),
                    pendingOperation = pending,
                    lease = null,
                    cancellation = cancellation,
                    incidents = state.incidents + incident,
                )
                val result = store.commit(
                    AgentStoreCommit(
                        key,
                        state.stateVersion,
                        state.eventSequence,
                        AgentStoreCommitAuthority.TRUSTED_COMMAND,
                        null,
                        now,
                        next,
                        events,
                    ),
                )
                if (result.status == AgentStoreCommitStatus.APPLIED) {
                    publish(key, result.state!!, events)
                    return true
                }
                if (result.status == AgentStoreCommitStatus.MISSING) return false
                return@repeat
            }
            val sequence = state.eventSequence + 1L
            val event = AgentRunStatusChangedEvent(
                state.runId,
                state.tenantId,
                sequence,
                now,
                state.status,
                AgentRunStatus.CANCELLED,
                cancellation.reasonCode,
            )
            val next = state.evolve(
                messages = securitySafeMessages(state),
                status = AgentRunStatus.CANCELLED,
                eventSequence = sequence,
                updatedAt = now,
                steps = state.steps.map { step ->
                    if (step.stepId == state.currentStepId) {
                        step.transition(AgentRuntimeStepStatus.FAILED, step.attempt, now)
                    } else step
                },
                currentStepId = null,
                pendingOperation = null,
                lease = null,
                cancellation = cancellation,
                failure = null,
            )
            val result = store.commit(
                AgentStoreCommit(
                    key,
                    state.stateVersion,
                    state.eventSequence,
                    AgentStoreCommitAuthority.TRUSTED_COMMAND,
                    null,
                    now,
                    next,
                    listOf(event),
                ),
            )
            if (result.status == AgentStoreCommitStatus.APPLIED) {
                publish(key, result.state!!, listOf(event))
                return true
            }
            if (result.status == AgentStoreCommitStatus.MISSING) return false
        }
        return false
    }

    private fun cancelAuthorized(
        context: AgentRunCommandContext,
        key: AgentRunKey,
        cancellation: AgentCancellation,
    ): Boolean {
        require(context.tenantId == key.tenantId) { "Agent cancellation context does not match the run tenant." }
        authorizeCommand(
            context,
            key.runId,
            AgentRunCommandAction.CANCEL,
            null,
            AgentRuntimeDigest("flowweft.agent.runtime.cancellation-command-evidence.v1")
                .add(cancellation.reasonCode)
                .add(cancellation.requestedAt)
                .finish(),
        )
        return cancel(key, cancellation)
    }

    private fun resume(key: AgentRunKey) {
        val now = clock.currentTimeMillis()
        val claim = store.claimLease(
            AgentRunLeaseClaim(
                key,
                workerId,
                ids.nextId("agent-run-lease"),
                now,
                configuration.leaseDurationMillis,
            ),
        )
        if (claim.status != AgentRunLeaseClaimStatus.ACQUIRED) {
            claim.state?.let { completeIfTerminal(key, it) }
            return
        }
        processClaimedState(claim.state!!, now)
    }

    private fun processClaimedState(state: AgentDurableRunState, now: Long) {
        if (state.status.isTerminal()) {
            completeIfTerminal(AgentRunKey(state.tenantId, state.runId), state)
            return
        }
        if (!pendingCapabilityMatches(state)) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, "capability.binding-invalid", now)
            return
        }
        val dispatchedTool = (state.pendingOperation as? AgentPendingToolOperation)
            ?.takeIf { it.toolDispatchedAt != null && it.dispatchFenceConsumption != null }
        if (state.cancellation != null) {
            if (dispatchedTool != null) {
                if (dispatchedTool.phase == AgentPendingToolPhase.TOOL_DISPATCHED) {
                    markReconciliation(
                        state,
                        dispatchedTool.with(
                            phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED,
                            updatedAt = now,
                        ),
                        "tool.cancellation-outcome-unknown",
                        now,
                        state.usage,
                    )
                }
            } else {
                cancel(AgentRunKey(state.tenantId, state.runId), state.cancellation)
            }
            return
        }
        if (now >= state.deadlineAt || state.usage.durationMillis >= state.budget.maximumDurationMillis) {
            if (dispatchedTool != null) {
                if (dispatchedTool.phase == AgentPendingToolPhase.TOOL_DISPATCHED) {
                    markReconciliation(
                        state,
                        dispatchedTool.with(
                            phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED,
                            updatedAt = now,
                        ),
                        "tool.deadline-outcome-unknown",
                        now,
                        state.usage,
                    )
                }
            } else {
                expireWorker(state, "run.deadline-exceeded", now)
            }
            return
        }
        val continuationFailure = authorizeContinuation(state, now)
        if (continuationFailure != null) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, continuationFailure, now)
            return
        }
        when (val pending = state.pendingOperation) {
            null -> checkpointModel(state, now)
            is AgentPendingModelOperation -> resumeModel(state, pending, now)
            is AgentPendingToolOperation -> resumeTool(state, pending, now)
            else -> failWorker(state, AgentFailureCategory.PROTOCOL, "operation.unsupported", now)
        }
    }

    /** Continues a state already fenced by this worker without attempting to acquire a second lease. */
    private fun continueOwned(committed: AgentDurableRunState) {
        val expectedLease = committed.lease ?: return
        val now = clock.currentTimeMillis()
        if (!expectedLease.isCurrent(now) || expectedLease.ownerId != workerId) return
        val current = store.load(AgentRunKey(committed.tenantId, committed.runId)) ?: return
        if (current.stateVersion != committed.stateVersion || current.lease?.matches(expectedLease) != true) return
        processClaimedState(current, now)
    }

    private fun checkpointModel(state: AgentDurableRunState, now: Long) {
        if (!state.budget.allowsAnotherModelCall(state.usage)) {
            failWorker(state, AgentFailureCategory.QUOTA, "budget.model-exhausted", now)
            return
        }
        val selection = try {
            modelSelection.select(state)
        } catch (_: RuntimeException) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "model.selection-failed", now)
            return
        }
        try {
            selection.requireCapability(state.capabilityId)
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "model.capability-unsupported", now)
            return
        }
        val remainingOutput = state.budget.remainingOutputTokens(state.usage)
        val remainingInput = state.budget.remainingInputTokens(state.usage)
        val remainingCost = state.budget.remainingCostMicros(state.usage)
        val remainingDuration = state.budget.remainingDurationMillis(state.usage)
        val remainingModelCalls = state.budget.maximumModelCalls - state.usage.modelCalls
        val deadlineRemaining = state.deadlineAt - now
        if (selection.descriptor.maximumCostMicros > 0L && remainingCost == 0L) {
            failWorker(state, AgentFailureCategory.QUOTA, "budget.cost-exhausted", now)
            return
        }
        val maximumInput = minOf(
            selection.descriptor.maximumInputTokens,
            conservativeShare(remainingInput, remainingModelCalls),
        )
        val maximumOutput = minOf(
            selection.descriptor.maximumOutputTokens,
            conservativeShare(remainingOutput, remainingModelCalls),
        )
        val maximumCost = minOf(
            selection.descriptor.maximumCostMicros,
            conservativeShareAllowingZero(remainingCost, remainingModelCalls),
        )
        val maximumDuration = minOf(
            selection.descriptor.maximumDurationMillis,
            conservativeShare(remainingDuration, remainingModelCalls),
            deadlineRemaining,
        )
        if (maximumOutput <= 0L || maximumInput <= 0L || maximumCost < 0L || maximumDuration <= 0L) {
            val code = when {
                maximumInput <= 0L -> "budget.input-exhausted"
                maximumCost < 0L -> "budget.cost-exhausted"
                maximumDuration <= 0L -> "budget.duration-exhausted"
                else -> "budget.output-exhausted"
            }
            failWorker(state, AgentFailureCategory.QUOTA, code, now)
            return
        }
        val operationId = ids.nextId("agent-model-operation")
        val stepId = ids.nextId("agent-model-step")
        val checkpointId = ids.nextId("agent-model-checkpoint")
        val pending = AgentPendingModelOperation(
            operationId,
            stepId,
            ids.nextId("agent-model-request"),
            selection.descriptor,
            selection.tools,
            maximumInput,
            maximumOutput,
            maximumCost,
            maximumDuration,
            now + maximumDuration,
            1,
            AgentPendingModelPhase.CHECKPOINTED,
            checkpointId,
            null,
            now,
            now,
        )
        val checkpoint = AgentRuntimeCheckpoint(
            checkpointId,
            state.runId,
            state.tenantId,
            stepId,
            operationId,
            "model.checkpointed",
            pending.operationDigest,
            state.checkpointSequence + 1L,
            now,
        )
        val step = AgentRuntimeStep(
            stepId,
            AgentRuntimeStepKind.MODEL,
            AgentRuntimeStepStatus.CHECKPOINTED,
            operationId,
            1,
            now,
            now,
        )
        val events = ArrayList<AgentRunEvent>(2)
        var sequence = state.eventSequence
        if (state.status != AgentRunStatus.RUNNING) {
            events += AgentRunStatusChangedEvent(
                state.runId,
                state.tenantId,
                ++sequence,
                now,
                state.status,
                AgentRunStatus.RUNNING,
                "model.checkpointed",
            )
        }
        events += AgentRuntimeCheckpointEvent(state.runId, state.tenantId, ++sequence, now, checkpoint)
        val next = state.evolve(
            status = AgentRunStatus.RUNNING,
            eventSequence = sequence,
            checkpointSequence = state.checkpointSequence + 1L,
            updatedAt = now,
            steps = state.steps + step,
            checkpoints = state.checkpoints + checkpoint,
            currentStepId = stepId,
            pendingOperation = pending,
        )
        applyWorker(state, next, events)?.let(::continueOwned)
    }

    private fun resumeModel(state: AgentDurableRunState, pending: AgentPendingModelOperation, now: Long) {
        when (pending.phase) {
            AgentPendingModelPhase.CHECKPOINTED -> claimAndDispatchModel(state, pending, now)
            AgentPendingModelPhase.CLAIMED -> {
                if (pending.claimedLeaseId == state.lease?.leaseId) {
                    dispatchModel(state, pending)
                } else {
                    markReconciliation(state, pending.reconciliation(now), "model.outcome-unknown", now, state.usage)
                }
            }
            AgentPendingModelPhase.RECONCILIATION_REQUIRED -> Unit
        }
    }

    private fun claimAndDispatchModel(
        state: AgentDurableRunState,
        pending: AgentPendingModelOperation,
        now: Long,
    ) {
        if (!state.budget.allowsAnotherModelCall(state.usage)) {
            failWorker(state, AgentFailureCategory.QUOTA, "budget.model-exhausted", now)
            return
        }
        val lease = requireNotNull(state.lease)
        val checkpointId = ids.nextId("agent-model-claim-checkpoint")
        val claimed = pending.claimed(lease, checkpointId, now)
        val checkpoint = AgentRuntimeCheckpoint(
            checkpointId,
            state.runId,
            state.tenantId,
            pending.stepId,
            pending.operationId,
            "model.claimed",
            claimed.operationDigest,
            state.checkpointSequence + 1L,
            now,
        )
        val steps = replaceStep(state, pending.stepId, AgentRuntimeStepStatus.CLAIMED, pending.attempt, now)
        val usage = try {
            usageAfterModelDispatch(state.usage, pending)
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.QUOTA, "budget.usage-overflow", now)
            return
        }
        var sequence = state.eventSequence
        val events = listOf(
            AgentRunUsageEvent(state.runId, state.tenantId, ++sequence, now, usage),
            AgentRuntimeCheckpointEvent(state.runId, state.tenantId, ++sequence, now, checkpoint),
        )
        val next = state.evolve(
            usage = usage,
            eventSequence = sequence,
            checkpointSequence = state.checkpointSequence + 1L,
            updatedAt = now,
            steps = steps,
            checkpoints = state.checkpoints + checkpoint,
            pendingOperation = claimed,
        )
        applyWorker(state, next, events)?.let { committed ->
            dispatchModel(committed, committed.pendingOperation as AgentPendingModelOperation)
        }
    }

    private fun dispatchModel(state: AgentDurableRunState, pending: AgentPendingModelOperation) {
        val key = AgentRunKey(state.tenantId, state.runId)
        val token = StoreCancellationToken(store, key)
        val dispatchedAt = clock.currentTimeMillis()
        val reservedDeadline = minOf(
            pending.deadlineAt,
            if (dispatchedAt > Long.MAX_VALUE - pending.maximumDurationMillis) {
                Long.MAX_VALUE
            } else {
                dispatchedAt + pending.maximumDurationMillis
            },
        )
        if (reservedDeadline <= dispatchedAt) {
            expireWorker(state, "model.dispatch-expired", dispatchedAt)
            return
        }
        val request = LanguageModelRequest(
            pending.requestId,
            state.tenantId,
            pending.descriptor.providerId,
            pending.descriptor.modelId,
            state.messages,
            pending.tools,
            pending.maximumOutputTokens,
            dispatchedAt,
            reservedDeadline,
            token,
            0.0,
            pending.maximumInputTokens,
            pending.maximumCostMicros,
        )
        val provider = modelProviders.find(pending.descriptor.providerId, pending.descriptor.modelId)
        if (provider == null || !sameModelDescriptor(pending.descriptor, provider.descriptor())) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "model.descriptor-changed", clock.currentTimeMillis())
            return
        }
        request.requireSupportedBy(provider.descriptor())
        val contentFailure = authorizeContent(
            state,
            AgentContentSecurityBoundary.MODEL_INPUT,
            pending.descriptor.providerId,
            pending.descriptor.descriptorDigest,
            pending.operationDigest,
            state.messages,
            emptyList(),
            pending.tools,
            clock.currentTimeMillis(),
        )
        if (contentFailure != null) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, contentFailure, clock.currentTimeMillis())
            return
        }
        val live = store.load(key)
        if (live?.stateVersion != state.stateVersion || live.cancellation != null || live.status.isTerminal() ||
            token.cancellation() != null
        ) {
            return
        }
        if (clock.currentTimeMillis() >= pending.deadlineAt) {
            expireWorker(state, "model.dispatch-expired", clock.currentTimeMillis())
            return
        }
        val call = try {
            provider.start(request, LanguageModelObserver.NOOP)
        } catch (failure: Throwable) {
            handleModelFailure(state, pending, normalizeProviderFailure(
                pending.descriptor.providerId,
                AgentProviderOperationId.MODEL,
                failure,
            ))
            return
        }
        activeCalls[key] = ActiveExternalCall { cancellation -> call.cancel(cancellation) }
        call.completion().whenComplete { response, failure ->
            executor.execute {
                activeCalls.remove(key)
                if (failure != null) {
                    handleModelFailure(
                        state,
                        pending,
                        normalizeProviderFailure(
                            pending.descriptor.providerId,
                            AgentProviderOperationId.MODEL,
                            failure,
                        ),
                    )
                } else if (response == null) {
                    handleModelFailure(
                        state,
                        pending,
                        AgentProviderException(
                            pending.descriptor.providerId,
                            AgentFailureCategory.PROTOCOL,
                            "model.null-response",
                        ),
                    )
                } else {
                    handleModelResponse(state, pending, request, response)
                }
            }
        }
    }

    private fun handleModelFailure(
        state: AgentDurableRunState,
        pending: AgentPendingModelOperation,
        failure: AgentProviderException,
    ) {
        val now = clock.currentTimeMillis()
        if (failure.category == AgentFailureCategory.CANCELLED) {
            cancelWorker(state, AgentCancellation(failure.code, now), state.usage, now)
            return
        }
        val retryable = failure.category == AgentFailureCategory.RETRYABLE ||
            failure.category == AgentFailureCategory.RATE_LIMITED
        if (retryable && pending.attempt < configuration.maximumProviderAttempts && now < pending.deadlineAt &&
            state.budget.allowsAnotherModelCall(state.usage)
        ) {
            val checkpointId = ids.nextId("agent-model-retry-checkpoint")
            val retry = pending.retry(checkpointId, pending.attempt + 1, now)
            val checkpoint = AgentRuntimeCheckpoint(
                checkpointId,
                state.runId,
                state.tenantId,
                pending.stepId,
                pending.operationId,
                "model.retry-checkpointed",
                retry.operationDigest,
                state.checkpointSequence + 1L,
                now,
            )
            val sequence = state.eventSequence + 1L
            val event = AgentRuntimeCheckpointEvent(state.runId, state.tenantId, sequence, now, checkpoint)
            val next = state.evolve(
                eventSequence = sequence,
                checkpointSequence = state.checkpointSequence + 1L,
                updatedAt = now,
                steps = replaceStep(
                    state,
                    pending.stepId,
                    AgentRuntimeStepStatus.CHECKPOINTED,
                    retry.attempt,
                    now,
                ),
                checkpoints = state.checkpoints + checkpoint,
                pendingOperation = retry,
            )
            applyWorker(state, next, listOf(event))?.let(::continueOwned)
        } else {
            failWorker(state, failure.category, failure.code, now)
        }
    }

    private fun handleModelResponse(
        state: AgentDurableRunState,
        pending: AgentPendingModelOperation,
        request: LanguageModelRequest,
        response: LanguageModelResponse,
    ) {
        val now = clock.currentTimeMillis()
        try {
            response.requireValidFor(request, pending.descriptor)
            require(response.usage.modelCalls == 1 && response.usage.toolCalls == 0) {
                "Language model usage must report exactly one model call and no tool calls."
            }
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "model.response-invalid", now)
            return
        }
        val usage = try {
            retainConservativeModelReservation(state.usage)
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.QUOTA, "budget.usage-overflow", now)
            return
        }
        if (!state.budget.allows(usage)) {
            failWorker(state, AgentFailureCategory.QUOTA, "budget.exceeded", now, usage)
            return
        }
        val contentBoundary = when (response.finishReason) {
            LanguageModelFinishReason.TOOL_CALLS -> AgentContentSecurityBoundary.MODEL_TOOL_ARGUMENTS
            LanguageModelFinishReason.STOP -> AgentContentSecurityBoundary.MODEL_OUTPUT
            LanguageModelFinishReason.LENGTH,
            LanguageModelFinishReason.CONTENT_FILTER,
            LanguageModelFinishReason.CANCELLED,
            -> null
        }
        if (contentBoundary != null) {
            val message = requireNotNull(response.message)
            val contentFailure = authorizeContent(
                state,
                contentBoundary,
                pending.descriptor.providerId,
                pending.descriptor.descriptorDigest,
                response.bindingDigest,
                listOf(message),
                emptyList(),
                emptyList(),
                now,
            )
            if (contentFailure != null) {
                failWorker(state, AgentFailureCategory.AUTHORIZATION, contentFailure, now, usage)
                return
            }
        }
        when (response.finishReason) {
            LanguageModelFinishReason.STOP -> completeModelRun(state, pending, response, usage, now)
            LanguageModelFinishReason.TOOL_CALLS -> checkpointToolFromModel(state, pending, response, usage, now)
            LanguageModelFinishReason.CANCELLED -> cancel(
                AgentRunKey(state.tenantId, state.runId),
                AgentCancellation("model.cancelled", now),
            )
            LanguageModelFinishReason.LENGTH -> failWorker(
                state,
                AgentFailureCategory.PERMANENT,
                "model.output-truncated",
                now,
                usage,
            )
            LanguageModelFinishReason.CONTENT_FILTER -> failWorker(
                state,
                AgentFailureCategory.PERMANENT,
                "model.content-filtered",
                now,
                usage,
            )
        }
    }

    private fun completeModelRun(
        state: AgentDurableRunState,
        pending: AgentPendingModelOperation,
        response: LanguageModelResponse,
        usage: AgentUsage,
        now: Long,
    ) {
        val events = ArrayList<AgentRunEvent>(3)
        var sequence = state.eventSequence
        val messages = ArrayList(state.messages)
        response.message?.let { message ->
            messages += message
            events += AgentRunMessageEvent(state.runId, state.tenantId, ++sequence, response.completedAt, message)
        }
        events += AgentRunUsageEvent(state.runId, state.tenantId, ++sequence, now, usage)
        events += AgentRunStatusChangedEvent(
            state.runId,
            state.tenantId,
            ++sequence,
            now,
            state.status,
            AgentRunStatus.COMPLETED,
            "model.completed",
        )
        val next = state.evolve(
            messages = messages,
            usage = usage,
            status = AgentRunStatus.COMPLETED,
            eventSequence = sequence,
            updatedAt = now,
            steps = replaceStep(
                state,
                pending.stepId,
                AgentRuntimeStepStatus.COMPLETED,
                pending.attempt,
                now,
            ),
            currentStepId = null,
            pendingOperation = null,
            lease = null,
            failure = null,
        )
        applyWorker(state, next, events)
    }

    private fun checkpointToolFromModel(
        state: AgentDurableRunState,
        pending: AgentPendingModelOperation,
        response: LanguageModelResponse,
        usage: AgentUsage,
        now: Long,
    ) {
        val message = response.message
        val calls = message?.blocks?.filterIsInstance<AgentToolCallContentBlock>() ?: emptyList()
        if (message == null || calls.size != 1) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "model.tool-call-invalid", now, usage)
            return
        }
        if (!state.budget.allowsToolInvocation(usage)) {
            failWorker(state, AgentFailureCategory.QUOTA, "budget.tool-exhausted", now, usage)
            return
        }
        val plan = try {
            toolPlanResolver.resolve(state, calls.single(), pending.tools, state.deadlineAt)
        } catch (_: RuntimeException) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "tool.plan-failed", now, usage)
            return
        }
        if (state.capabilityId !in plan.descriptor.capabilities) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, "tool.capability-unsupported", now, usage)
            return
        }
        if (plan.deadlineAt <= now || plan.deadlineAt > state.deadlineAt ||
            pending.tools.none { it.descriptorDigest == plan.descriptor.descriptorDigest }
        ) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "tool.plan-invalid", now, usage)
            return
        }
        val operationId = ids.nextId("agent-tool-operation")
        val stepId = ids.nextId("agent-tool-step")
        val checkpointId = ids.nextId("agent-tool-checkpoint")
        val preflight = createPreflight(state, stepId, plan, now)
        val toolPending = AgentPendingToolOperation(
            operationId = operationId,
            stepId = stepId,
            plan = plan,
            attempt = 1,
            phase = AgentPendingToolPhase.PREFLIGHT_CHECKPOINTED,
            preflightRequest = preflight,
            initialAuthorization = null,
            proposal = null,
            policyDecision = null,
            approvalRequest = null,
            approvalDecision = null,
            executionRecheck = null,
            executionAuthorization = null,
            consumption = null,
            finalExecutionRecheck = null,
            finalExecutionAuthorization = null,
            dispatchFenceRequest = null,
            dispatchFenceConsumption = null,
            invocationId = null,
            invocationStartedAt = null,
            invocationDeadlineAt = null,
            toolDispatchedAt = null,
            reservedCostMicros = null,
            reservedDurationMillis = null,
            checkpointId = checkpointId,
            claimedLeaseId = null,
            createdAt = now,
            updatedAt = now,
        )
        val checkpoint = AgentRuntimeCheckpoint(
            checkpointId,
            state.runId,
            state.tenantId,
            stepId,
            operationId,
            "tool.preflight.checkpointed",
            toolPending.operationDigest,
            state.checkpointSequence + 1L,
            now,
        )
        val messages = state.messages + message
        val events = ArrayList<AgentRunEvent>(3)
        var sequence = state.eventSequence
        events += AgentRunMessageEvent(state.runId, state.tenantId, ++sequence, response.completedAt, message)
        events += AgentRunUsageEvent(state.runId, state.tenantId, ++sequence, now, usage)
        events += AgentRuntimeCheckpointEvent(state.runId, state.tenantId, ++sequence, now, checkpoint)
        val modelSteps = replaceStep(
            state,
            pending.stepId,
            AgentRuntimeStepStatus.COMPLETED,
            pending.attempt,
            now,
        )
        val toolStep = AgentRuntimeStep(
            stepId,
            AgentRuntimeStepKind.TOOL,
            AgentRuntimeStepStatus.CHECKPOINTED,
            operationId,
            1,
            now,
            now,
        )
        val next = state.evolve(
            messages = messages,
            usage = usage,
            eventSequence = sequence,
            checkpointSequence = state.checkpointSequence + 1L,
            updatedAt = now,
            steps = modelSteps + toolStep,
            checkpoints = state.checkpoints + checkpoint,
            currentStepId = stepId,
            pendingOperation = toolPending,
        )
        applyWorker(state, next, events)?.let(::continueOwned)
    }

    private fun resumeToolPhase(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        now: Long,
    ) {
        if (now >= pending.plan.deadlineAt) {
            if (pending.phase == AgentPendingToolPhase.TOOL_DISPATCHED) {
                val unknown = pending.with(
                    phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED,
                    updatedAt = now,
                )
                markReconciliation(state, unknown, "tool.deadline-outcome-unknown", now, state.usage)
            } else {
                expireWorker(state, "tool.deadline-exceeded", now)
            }
            return
        }
        if (pending.phase == AgentPendingToolPhase.WAITING_APPROVAL &&
            now >= requireNotNull(pending.approvalRequest).expiresAt
        ) {
            expireWorker(state, "approval.expired", now)
            return
        }
        when (pending.phase) {
            AgentPendingToolPhase.PREFLIGHT_CHECKPOINTED -> claimToolPreflight(state, pending, now)
            AgentPendingToolPhase.PREFLIGHT_CLAIMED -> {
                if (pending.claimedLeaseId == state.lease?.leaseId) dispatchToolPreflight(state, pending)
                else claimToolPreflight(state, pending, now)
            }
            AgentPendingToolPhase.POLICY_CHECKPOINTED -> claimToolPolicy(state, pending, now)
            AgentPendingToolPhase.POLICY_CLAIMED -> {
                if (pending.claimedLeaseId == state.lease?.leaseId) dispatchToolPolicy(state, pending)
                else claimToolPolicy(state, pending, now)
            }
            AgentPendingToolPhase.WAITING_APPROVAL -> Unit
            AgentPendingToolPhase.EXECUTION_RECHECK_CHECKPOINTED -> claimExecutionRecheck(state, pending, now)
            AgentPendingToolPhase.EXECUTION_RECHECK_CLAIMED -> {
                if (pending.claimedLeaseId == state.lease?.leaseId) dispatchExecutionRecheck(state, pending)
                else claimExecutionRecheck(state, pending, now)
            }
            AgentPendingToolPhase.CONSUMPTION_CHECKPOINTED -> claimExecutionContextConsumption(state, pending, now)
            AgentPendingToolPhase.CONSUMPTION_CLAIMED -> {
                if (pending.claimedLeaseId == state.lease?.leaseId) dispatchExecutionContextConsumption(state, pending)
                else claimExecutionContextConsumption(state, pending, now)
            }
            AgentPendingToolPhase.EXECUTION_CLAIMED -> checkpointFinalExecutionRecheck(state, pending, now)
            AgentPendingToolPhase.FINAL_EXECUTION_RECHECK_CHECKPOINTED ->
                claimFinalExecutionRecheck(state, pending, now)
            AgentPendingToolPhase.FINAL_EXECUTION_RECHECK_CLAIMED -> {
                if (pending.claimedLeaseId == state.lease?.leaseId) dispatchFinalExecutionRecheck(state, pending)
                else claimFinalExecutionRecheck(state, pending, now)
            }
            AgentPendingToolPhase.DISPATCH_FENCE_CHECKPOINTED -> claimDispatchAuthorizationFence(state, pending, now)
            AgentPendingToolPhase.DISPATCH_FENCE_CLAIMED -> {
                if (pending.claimedLeaseId == state.lease?.leaseId) {
                    dispatchAuthorizationFence(state, pending)
                } else {
                    val unknown = pending.with(
                        phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED,
                        updatedAt = now,
                    )
                    markReconciliation(
                        state,
                        unknown,
                        "authorization.dispatch-fence-outcome-unknown",
                        now,
                        state.usage,
                    )
                }
            }
            AgentPendingToolPhase.TOOL_DISPATCHED -> {
                if (pending.claimedLeaseId == state.lease?.leaseId) {
                    dispatchTool(state, pending)
                } else {
                    val unknown = pending.with(
                        phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED,
                        updatedAt = now,
                    )
                    markReconciliation(
                        state,
                        unknown,
                        "tool.outcome-unknown",
                        now,
                        state.usage,
                    )
                }
            }
            AgentPendingToolPhase.RECONCILIATION_REQUIRED -> Unit
        }
    }

    private fun claimToolPreflight(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        now: Long,
    ) = claimToolRemotePhase(
        state,
        pending,
        AgentPendingToolPhase.PREFLIGHT_CLAIMED,
        "tool.preflight.claimed",
        now,
        ::dispatchToolPreflight,
    )

    private fun dispatchToolPreflight(state: AgentDurableRunState, pending: AgentPendingToolOperation) {
        val provider = authorizationProviders.find(pending.plan.authorizationProviderId)
        if (provider == null || provider.providerId() != pending.plan.authorizationProviderId) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "authorization.provider-missing", clock.currentTimeMillis())
            return
        }
        val key = AgentRunKey(state.tenantId, state.runId)
        val call = try {
            provider.start(pending.preflightRequest)
        } catch (failure: Throwable) {
            handleToolProviderFailure(
                state,
                pending,
                normalizeProviderFailure(provider.providerId(), AgentProviderOperationId.AUTHORIZATION, failure),
            )
            return
        }
        activeCalls[key] = ActiveExternalCall { cancellation -> call.cancel(cancellation) }
        call.completion().whenComplete { decision, failure ->
            executor.execute {
                activeCalls.remove(key)
                if (failure != null) {
                    handleToolProviderFailure(
                        state,
                        pending,
                        normalizeProviderFailure(provider.providerId(), AgentProviderOperationId.AUTHORIZATION, failure),
                    )
                } else if (decision == null) {
                    handleToolProviderFailure(
                        state,
                        pending,
                        AgentProviderException(provider.providerId(), AgentFailureCategory.PROTOCOL, "authorization.null-decision"),
                    )
                } else {
                    handlePreflightDecision(state, pending, decision)
                }
            }
        }
    }

    private fun handlePreflightDecision(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        decision: AgentAuthorizationDecision,
    ) {
        val now = clock.currentTimeMillis()
        try {
            decision.requireValidFor(pending.preflightRequest, now)
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "authorization.preflight-invalid", now)
            return
        }
        if (decision.outcome != AgentAuthorizationOutcome.ALLOW) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, decision.reasonCode ?: "authorization.denied", now)
            return
        }
        val expiresAt = minOf(pending.plan.deadlineAt, decision.expiresAt)
        if (expiresAt <= now) {
            expireWorker(state, "authorization.preflight-expired", now)
            return
        }
        val proposal = try {
            AgentPolicyProposal.create(
                ids.nextId("agent-policy-proposal"),
                pending.plan.policyProviderId,
                pending.preflightRequest,
                decision,
                pending.plan.descriptor.risk,
                state.budget,
                state.usage,
                now,
                expiresAt,
            )
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "policy.proposal-invalid", now)
            return
        }
        val checkpointId = ids.nextId("agent-policy-checkpoint")
        val nextPending = pending.with(
            phase = AgentPendingToolPhase.POLICY_CHECKPOINTED,
            checkpointId = checkpointId,
            claimedLeaseId = null,
            initialAuthorization = decision,
            proposal = proposal,
            updatedAt = now,
        )
        commitToolCheckpoint(
            state,
            nextPending,
            "tool.policy.checkpointed",
            AgentRuntimeStepStatus.CHECKPOINTED,
            now,
        )?.let(::continueOwned)
    }

    private fun claimToolPolicy(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        now: Long,
    ) = claimToolRemotePhase(
        state,
        pending,
        AgentPendingToolPhase.POLICY_CLAIMED,
        "tool.policy.claimed",
        now,
        ::dispatchToolPolicy,
    )

    private fun dispatchToolPolicy(state: AgentDurableRunState, pending: AgentPendingToolOperation) {
        val proposal = requireNotNull(pending.proposal)
        val provider = policyProviders.find(pending.plan.policyProviderId)
        if (provider == null || provider.providerId() != pending.plan.policyProviderId) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "policy.provider-missing", clock.currentTimeMillis())
            return
        }
        val key = AgentRunKey(state.tenantId, state.runId)
        val call = try {
            provider.start(proposal)
        } catch (failure: Throwable) {
            handleToolProviderFailure(
                state,
                pending,
                normalizeProviderFailure(provider.providerId(), AgentProviderOperationId.POLICY, failure),
            )
            return
        }
        activeCalls[key] = ActiveExternalCall { cancellation -> call.cancel(cancellation) }
        call.completion().whenComplete { decision, failure ->
            executor.execute {
                activeCalls.remove(key)
                if (failure != null) {
                    handleToolProviderFailure(
                        state,
                        pending,
                        normalizeProviderFailure(provider.providerId(), AgentProviderOperationId.POLICY, failure),
                    )
                } else if (decision == null) {
                    handleToolProviderFailure(
                        state,
                        pending,
                        AgentProviderException(provider.providerId(), AgentFailureCategory.PROTOCOL, "policy.null-decision"),
                    )
                } else {
                    handlePolicyDecision(state, pending, decision)
                }
            }
        }
    }

    private fun handlePolicyDecision(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        decision: AgentPolicyDecision,
    ) {
        val proposal = requireNotNull(pending.proposal)
        val now = clock.currentTimeMillis()
        try {
            decision.requireValidFor(proposal, now)
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "policy.decision-invalid", now)
            return
        }
        when (decision.outcome) {
            AgentPolicyOutcome.DENY ->
                failWorker(state, AgentFailureCategory.AUTHORIZATION, decision.reasonCode ?: "policy.denied", now)
            AgentPolicyOutcome.ALLOW -> checkpointExecutionRecheck(state, pending, decision, null, now)
            AgentPolicyOutcome.REQUIRE_APPROVAL -> checkpointApprovalWait(state, pending, decision, now)
        }
    }

    private fun checkpointApprovalWait(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        decision: AgentPolicyDecision,
        now: Long,
    ) {
        val operatorId = pending.plan.operatorId
        val operatorType = pending.plan.operatorType
        if (operatorId == null || operatorType == null) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "approval.operator-unconfigured", now)
            return
        }
        val proposal = requireNotNull(pending.proposal)
        val expiresAt = minOf(
            pending.plan.deadlineAt,
            requireNotNull(pending.initialAuthorization).expiresAt,
            decision.expiresAt,
        )
        if (expiresAt <= now) {
            expireWorker(state, "approval.window-expired", now)
            return
        }
        val approval = try {
            AgentApprovalRequest.create(
                ids.nextId("agent-approval-request"),
                proposal,
                decision,
                operatorId,
                operatorType,
                ids.nextId("agent-approval-nonce").value,
                now,
                expiresAt,
            )
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "approval.request-invalid", now)
            return
        }
        val checkpointId = ids.nextId("agent-approval-checkpoint")
        val waiting = pending.with(
            phase = AgentPendingToolPhase.WAITING_APPROVAL,
            checkpointId = checkpointId,
            claimedLeaseId = null,
            policyDecision = decision,
            approvalRequest = approval,
            updatedAt = now,
        )
        commitToolCheckpoint(
            state,
            waiting,
            "tool.approval.waiting",
            AgentRuntimeStepStatus.WAITING_APPROVAL,
            now,
            AgentRunStatus.WAITING_APPROVAL,
            releaseLease = true,
        )
    }

    private fun checkpointExecutionRecheck(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        policyDecision: AgentPolicyDecision,
        approvalDecision: AgentApprovalDecision?,
        now: Long,
    ) {
        val expiry = minOf(
            pending.plan.deadlineAt,
            requireNotNull(pending.initialAuthorization).expiresAt,
            policyDecision.expiresAt,
            pending.approvalRequest?.expiresAt ?: Long.MAX_VALUE,
        )
        if (expiry <= now) {
            expireWorker(state, "authorization.recheck-window-expired", now)
            return
        }
        val request = try {
            AgentAuthorizationRequest.executionRecheck(
                ids.nextId("agent-authorization-execution-request"),
                pending.preflightRequest,
                now,
                expiry,
            )
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "authorization.recheck-invalid", now)
            return
        }
        val checkpointId = ids.nextId("agent-execution-recheck-checkpoint")
        val nextPending = pending.with(
            phase = AgentPendingToolPhase.EXECUTION_RECHECK_CHECKPOINTED,
            checkpointId = checkpointId,
            claimedLeaseId = null,
            policyDecision = policyDecision,
            approvalDecision = approvalDecision,
            executionRecheck = request,
            updatedAt = now,
        )
        commitToolCheckpoint(
            state,
            nextPending,
            "tool.execution-recheck.checkpointed",
            AgentRuntimeStepStatus.CHECKPOINTED,
            now,
        )?.let(::continueOwned)
    }

    private fun claimExecutionRecheck(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        now: Long,
    ) = claimToolRemotePhase(
        state,
        pending,
        AgentPendingToolPhase.EXECUTION_RECHECK_CLAIMED,
        "tool.execution-recheck.claimed",
        now,
        ::dispatchExecutionRecheck,
    )

    private fun dispatchExecutionRecheck(state: AgentDurableRunState, pending: AgentPendingToolOperation) {
        val request = requireNotNull(pending.executionRecheck)
        val provider = authorizationProviders.find(pending.plan.authorizationProviderId)
        if (provider == null || provider.providerId() != pending.plan.authorizationProviderId) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "authorization.provider-missing", clock.currentTimeMillis())
            return
        }
        val key = AgentRunKey(state.tenantId, state.runId)
        val call = try {
            provider.start(request)
        } catch (failure: Throwable) {
            handleToolProviderFailure(
                state,
                pending,
                normalizeProviderFailure(provider.providerId(), AgentProviderOperationId.AUTHORIZATION, failure),
            )
            return
        }
        activeCalls[key] = ActiveExternalCall { cancellation -> call.cancel(cancellation) }
        call.completion().whenComplete { decision, failure ->
            executor.execute {
                activeCalls.remove(key)
                if (failure != null) {
                    handleToolProviderFailure(
                        state,
                        pending,
                        normalizeProviderFailure(provider.providerId(), AgentProviderOperationId.AUTHORIZATION, failure),
                    )
                } else if (decision == null) {
                    handleToolProviderFailure(
                        state,
                        pending,
                        AgentProviderException(provider.providerId(), AgentFailureCategory.PROTOCOL, "authorization.null-decision"),
                    )
                } else {
                    handleExecutionAuthorization(state, pending, decision)
                }
            }
        }
    }

    private fun handleExecutionAuthorization(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        decision: AgentAuthorizationDecision,
    ) {
        val request = requireNotNull(pending.executionRecheck)
        val initial = requireNotNull(pending.initialAuthorization)
        val now = clock.currentTimeMillis()
        try {
            decision.requireValidFor(request, now)
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "authorization.execution-invalid", now)
            return
        }
        if (decision.outcome != AgentAuthorizationOutcome.ALLOW ||
            decision.authorizationRevision != initial.authorizationRevision
        ) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, "authorization.revoked", now)
            return
        }
        val executorResolution = resolveToolExecutor(pending.plan.descriptor)
        if (executorResolution.failureCode != null) {
            failWorker(state, AgentFailureCategory.PROTOCOL, executorResolution.failureCode, now)
            return
        }
        val toolCallMessage = exactToolCallMessage(state, pending)
        if (toolCallMessage == null) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "tool.call-message-missing", now)
            return
        }
        val contentFailure = authorizeContent(
            state,
            AgentContentSecurityBoundary.MODEL_TOOL_ARGUMENTS,
            pending.plan.descriptor.providerId,
            pending.plan.descriptor.descriptorDigest,
            pending.plan.planDigest,
            listOf(toolCallMessage),
            emptyList(),
            emptyList(),
            now,
        )
        if (contentFailure != null) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, contentFailure, now)
            return
        }
        val deadlineAt = minOf(
            pending.plan.deadlineAt,
            requireNotNull(pending.proposal).expiresAt,
            requireNotNull(pending.policyDecision).expiresAt,
            request.expiresAt,
            decision.expiresAt,
            pending.approvalRequest?.expiresAt ?: Long.MAX_VALUE,
        )
        if (deadlineAt <= now) {
            expireWorker(state, "tool.authorization-expired", now)
            return
        }
        val invocationId = ids.nextId("agent-tool-invocation")
        val checkpointId = ids.nextId("agent-consumption-checkpoint")
        val nextPending = pending.with(
            phase = AgentPendingToolPhase.CONSUMPTION_CHECKPOINTED,
            checkpointId = checkpointId,
            claimedLeaseId = null,
            executionAuthorization = decision,
            invocationId = invocationId,
            invocationStartedAt = now,
            invocationDeadlineAt = deadlineAt,
            updatedAt = now,
        )
        try {
            authorizedInvocation(state, nextPending)
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, "tool.authorization-chain-invalid", now)
            return
        } catch (cancelled: AgentCancellationException) {
            cancel(AgentRunKey(state.tenantId, state.runId), cancelled.cancellation)
            return
        }
        commitToolCheckpoint(
            state,
            nextPending,
            "tool.consumption.checkpointed",
            AgentRuntimeStepStatus.CHECKPOINTED,
            now,
        )?.let(::continueOwned)
    }

    private fun claimExecutionContextConsumption(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        now: Long,
    ) = claimToolRemotePhase(
        state,
        pending,
        AgentPendingToolPhase.CONSUMPTION_CLAIMED,
        "tool.consumption.claimed",
        now,
        ::dispatchExecutionContextConsumption,
    )

    private fun dispatchExecutionContextConsumption(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
    ) {
        val now = clock.currentTimeMillis()
        val invocation = try {
            authorizedInvocation(state, pending)
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, "tool.authorization-chain-invalid", now)
            return
        } catch (cancelled: AgentCancellationException) {
            cancel(AgentRunKey(state.tenantId, state.runId), cancelled.cancellation)
            return
        }
        val stage = try {
            executionContextConsumer.consume(invocation, now)
        } catch (failure: Throwable) {
            handleConsumptionFailure(state, pending, failure)
            return
        }
        stage.whenComplete { consumption, failure ->
            executor.execute {
                if (failure != null) {
                    handleConsumptionFailure(state, pending, failure)
                } else if (consumption == null) {
                    handleConsumptionFailure(
                        state,
                        pending,
                        AgentExecutionContextException(AgentExecutionContextFailureCode.PROTOCOL),
                    )
                } else {
                    handleConsumption(state, pending, invocation, consumption)
                }
            }
        }
    }

    private fun handleConsumption(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        invocation: AuthorizedToolInvocation,
        consumption: AgentExecutionContextConsumption,
    ) {
        val now = clock.currentTimeMillis()
        try {
            require(consumption.consumerId == executionContextConsumer.consumerId())
            consumption.requireMatches(invocation, now)
        } catch (_: IllegalArgumentException) {
            val unknown = pending.with(
                phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED,
                updatedAt = now,
            )
            markReconciliation(state, unknown, "execution-context.binding-unknown", now, state.usage)
            return
        }
        if (consumption.status != AgentExecutionContextConsumptionStatus.CLAIMED) {
            val unknown = pending.with(
                phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED,
                consumption = consumption,
                updatedAt = now,
            )
            markReconciliation(state, unknown, "execution-context.replayed", now, state.usage)
            return
        }
        val checkpointId = ids.nextId("agent-execution-claim-checkpoint")
        val claimed = pending.with(
            phase = AgentPendingToolPhase.EXECUTION_CLAIMED,
            checkpointId = checkpointId,
            claimedLeaseId = state.lease?.leaseId,
            consumption = consumption,
            updatedAt = now,
        )
        commitToolCheckpoint(
            state,
            claimed,
            "tool.execution-context.claimed",
            AgentRuntimeStepStatus.CLAIMED,
            now,
        )?.let(::continueOwned)
    }

    /** Runs content policy first, then checkpoints the exact post-consumption authorization request. */
    private fun checkpointFinalExecutionRecheck(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        now: Long,
    ) {
        val toolCallMessage = exactToolCallMessage(state, pending)
        if (toolCallMessage == null) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "tool.call-message-missing", now)
            return
        }
        val contentFailure = authorizeContent(
            state,
            AgentContentSecurityBoundary.MODEL_TOOL_ARGUMENTS,
            pending.plan.descriptor.providerId,
            pending.plan.descriptor.descriptorDigest,
            pending.plan.planDigest,
            listOf(toolCallMessage),
            emptyList(),
            emptyList(),
            now,
        )
        if (contentFailure != null) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, contentFailure, clock.currentTimeMillis())
            return
        }
        val requestedAt = clock.currentTimeMillis()
        val expiresAt = requireNotNull(pending.invocationDeadlineAt)
        if (expiresAt <= requestedAt) {
            expireWorker(state, "tool.final-authorization-expired", requestedAt)
            return
        }
        val request = try {
            AgentAuthorizationRequest.finalExecutionRecheck(
                ids.nextId("agent-authorization-final-execution-request"),
                requireNotNull(pending.executionRecheck),
                requestedAt,
                expiresAt,
            )
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "authorization.final-recheck-invalid", requestedAt)
            return
        }
        val checkpointId = ids.nextId("agent-final-execution-recheck-checkpoint")
        val nextPending = pending.with(
            phase = AgentPendingToolPhase.FINAL_EXECUTION_RECHECK_CHECKPOINTED,
            checkpointId = checkpointId,
            claimedLeaseId = null,
            finalExecutionRecheck = request,
            updatedAt = requestedAt,
        )
        commitToolCheckpoint(
            state,
            nextPending,
            "tool.final-execution-recheck.checkpointed",
            AgentRuntimeStepStatus.CHECKPOINTED,
            requestedAt,
        )?.let(::continueOwned)
    }

    private fun claimFinalExecutionRecheck(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        now: Long,
    ) = claimToolRemotePhase(
        state,
        pending,
        AgentPendingToolPhase.FINAL_EXECUTION_RECHECK_CLAIMED,
        "tool.final-execution-recheck.claimed",
        now,
        ::dispatchFinalExecutionRecheck,
    )

    private fun dispatchFinalExecutionRecheck(state: AgentDurableRunState, pending: AgentPendingToolOperation) {
        val request = requireNotNull(pending.finalExecutionRecheck)
        val provider = authorizationProviders.find(pending.plan.authorizationProviderId)
        if (provider == null || provider.providerId() != pending.plan.authorizationProviderId) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "authorization.provider-missing", clock.currentTimeMillis())
            return
        }
        val key = AgentRunKey(state.tenantId, state.runId)
        val call = try {
            provider.start(request)
        } catch (failure: Throwable) {
            handleToolProviderFailure(
                state,
                pending,
                normalizeProviderFailure(provider.providerId(), AgentProviderOperationId.AUTHORIZATION, failure),
            )
            return
        }
        activeCalls[key] = ActiveExternalCall { cancellation -> call.cancel(cancellation) }
        call.completion().whenComplete { decision, failure ->
            executor.execute {
                activeCalls.remove(key)
                if (failure != null) {
                    handleToolProviderFailure(
                        state,
                        pending,
                        normalizeProviderFailure(provider.providerId(), AgentProviderOperationId.AUTHORIZATION, failure),
                    )
                } else if (decision == null) {
                    handleToolProviderFailure(
                        state,
                        pending,
                        AgentProviderException(provider.providerId(), AgentFailureCategory.PROTOCOL, "authorization.null-decision"),
                    )
                } else {
                    handleFinalExecutionAuthorization(state, pending, decision)
                }
            }
        }
    }

    private fun handleFinalExecutionAuthorization(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        decision: AgentAuthorizationDecision,
    ) {
        val request = requireNotNull(pending.finalExecutionRecheck)
        val now = clock.currentTimeMillis()
        try {
            request.requireFinalExecutionRecheckOf(requireNotNull(pending.executionRecheck))
            decision.requireValidFor(request, now)
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.PROTOCOL, "authorization.final-execution-invalid", now)
            return
        }
        val initial = requireNotNull(pending.initialAuthorization)
        if (decision.outcome != AgentAuthorizationOutcome.ALLOW ||
            decision.authorizationRevision != initial.authorizationRevision
        ) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, "authorization.revoked-before-side-effect", now)
            return
        }
        checkpointDispatchAuthorizationFence(state, pending, decision, now)
    }

    private fun checkpointDispatchAuthorizationFence(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        finalAuthorization: AgentAuthorizationDecision,
        now: Long,
    ) {
        val invocation = try {
            authorizedInvocation(state, pending)
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, "tool.authorization-chain-invalid", now)
            return
        } catch (cancelled: AgentCancellationException) {
            cancel(AgentRunKey(state.tenantId, state.runId), cancelled.cancellation)
            return
        }
        val expiresAt = minOf(
            invocation.deadlineAt,
            finalAuthorization.expiresAt,
            requireNotNull(pending.finalExecutionRecheck).expiresAt,
        )
        val request = try {
            AgentDispatchAuthorizationFenceRequest(
                ids.nextId("agent-dispatch-authorization-fence"),
                workerId,
                invocation,
                requireNotNull(pending.finalExecutionRecheck),
                finalAuthorization,
                now,
                expiresAt,
            )
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, "authorization.dispatch-fence-invalid", now)
            return
        }
        val checkpointId = ids.nextId("agent-dispatch-authorization-fence-checkpoint")
        val fenced = pending.with(
            phase = AgentPendingToolPhase.DISPATCH_FENCE_CHECKPOINTED,
            checkpointId = checkpointId,
            claimedLeaseId = null,
            finalExecutionAuthorization = finalAuthorization,
            dispatchFenceRequest = request,
            updatedAt = now,
        )
        commitToolCheckpoint(
            state,
            fenced,
            "tool.dispatch-authorization-fence.checkpointed",
            AgentRuntimeStepStatus.CHECKPOINTED,
            now,
        )?.let(::continueOwned)
    }

    private fun claimDispatchAuthorizationFence(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        now: Long,
    ) = claimToolRemotePhase(
        state,
        pending,
        AgentPendingToolPhase.DISPATCH_FENCE_CLAIMED,
        "tool.dispatch-authorization-fence.claimed",
        now,
        ::dispatchAuthorizationFence,
    )

    private fun dispatchAuthorizationFence(state: AgentDurableRunState, pending: AgentPendingToolOperation) {
        val provider = authorizationProviders.find(pending.plan.authorizationProviderId)
            as? AgentAtomicDispatchAuthorizationProvider
        if (provider == null || provider.providerId() != pending.plan.authorizationProviderId) {
            failWorker(
                state,
                AgentFailureCategory.PROTOCOL,
                "authorization.atomic-dispatch-provider-missing",
                clock.currentTimeMillis(),
            )
            return
        }
        val request = requireNotNull(pending.dispatchFenceRequest)
        val now = clock.currentTimeMillis()
        try {
            request.requireCurrent(now)
        } catch (_: IllegalArgumentException) {
            failWorker(state, AgentFailureCategory.AUTHORIZATION, "authorization.dispatch-fence-expired", now)
            return
        }
        val completion = try {
            provider.consumeDispatchFence(request)
        } catch (failure: Throwable) {
            handleDispatchAuthorizationFenceFailure(state, pending, failure)
            return
        }
        completion.whenComplete { receipt, failure ->
            executor.execute {
                if (failure != null) {
                    handleDispatchAuthorizationFenceFailure(state, pending, failure)
                } else if (receipt == null) {
                    handleDispatchAuthorizationFenceFailure(
                        state,
                        pending,
                        AgentDispatchAuthorizationFenceException(AgentExecutionContextFailureCode.PROTOCOL),
                    )
                } else {
                    handleDispatchAuthorizationFenceConsumption(state, pending, receipt)
                }
            }
        }
    }

    private fun handleDispatchAuthorizationFenceConsumption(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        receipt: AgentDispatchAuthorizationFenceConsumption,
    ) {
        val now = clock.currentTimeMillis()
        try {
            receipt.requireMatches(requireNotNull(pending.dispatchFenceRequest), now)
        } catch (_: IllegalArgumentException) {
            val unknown = pending.with(phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED, updatedAt = now)
            markReconciliation(state, unknown, "authorization.dispatch-fence-receipt-invalid", now, state.usage)
            return
        }
        if (receipt.status != AgentDispatchAuthorizationFenceStatus.CONSUMED) {
            val unknown = pending.with(
                phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED,
                dispatchFenceConsumption = receipt,
                updatedAt = now,
            )
            markReconciliation(state, unknown, "authorization.dispatch-fence-replayed", now, state.usage)
            return
        }
        checkpointAndDispatchTool(state, pending, receipt, now)
    }

    private fun handleDispatchAuthorizationFenceFailure(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        failure: Throwable,
    ) {
        val now = clock.currentTimeMillis()
        val fenceFailure = unwrapCompletionFailure(failure) as? AgentDispatchAuthorizationFenceException
        val code = when (fenceFailure?.code) {
            AgentExecutionContextFailureCode.REVOKED -> "authorization.dispatch-fence-revocation-outcome-unknown"
            AgentExecutionContextFailureCode.EXPIRED -> "authorization.dispatch-fence-expiry-outcome-unknown"
            AgentExecutionContextFailureCode.BINDING_MISMATCH ->
                "authorization.dispatch-fence-binding-outcome-unknown"
            else -> "authorization.dispatch-fence-outcome-unknown"
        }
        // An exception is not an atomic, structured denial receipt. The provider may have consumed
        // the fence before its response was lost, so retain the exact request for reconciliation.
        val unknown = pending.with(phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED, updatedAt = now)
        markReconciliation(state, unknown, code, now, state.usage)
    }

    private fun handleConsumptionFailure(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        failure: Throwable,
    ) {
        val now = clock.currentTimeMillis()
        val contextFailure = unwrapCompletionFailure(failure) as? AgentExecutionContextException
        val retryable = contextFailure?.retryable == true
        if (retryable && canRetryToolAttempt(state, pending, now, false)) {
            retryToolAttempt(state, pending, state.usage, "execution-context.retry", now)
            return
        }
        val code = when (contextFailure?.code) {
            AgentExecutionContextFailureCode.REVOKED -> "execution-context.revoked"
            AgentExecutionContextFailureCode.EXPIRED -> "execution-context.expired"
            AgentExecutionContextFailureCode.BINDING_MISMATCH -> "execution-context.binding-mismatch"
            else -> "execution-context.outcome-unknown"
        }
        val unknown = pending.with(phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED, updatedAt = now)
        markReconciliation(state, unknown, code, now, state.usage)
    }

    private fun checkpointAndDispatchTool(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        dispatchFenceConsumption: AgentDispatchAuthorizationFenceConsumption,
        now: Long,
    ) {
        if (!state.budget.allowsToolInvocation(state.usage)) {
            reconcileConsumedDispatchFence(
                state,
                pending,
                dispatchFenceConsumption,
                "budget.tool-exhausted",
                now,
            )
            return
        }
        val reservedCost = pending.plan.descriptor.maximumCostMicros
        val remainingCost = state.budget.remainingCostMicros(state.usage)
        val remainingDuration = state.budget.remainingDurationMillis(state.usage)
        val deadlineRemaining = requireNotNull(pending.invocationDeadlineAt) - now
        val reservedDuration = minOf(
            pending.plan.descriptor.maximumDurationMillis,
            remainingDuration,
            deadlineRemaining,
        )
        if (reservedCost > remainingCost || reservedDuration <= 0L) {
            reconcileConsumedDispatchFence(
                state,
                pending,
                dispatchFenceConsumption,
                "budget.tool-reservation-exhausted",
                now,
            )
            return
        }
        val usage = try {
            usageAfterToolDispatch(state.usage, reservedCost, reservedDuration)
        } catch (_: IllegalArgumentException) {
            reconcileConsumedDispatchFence(
                state,
                pending,
                dispatchFenceConsumption,
                "budget.usage-overflow",
                now,
            )
            return
        }
        if (!state.budget.allows(usage)) {
            reconcileConsumedDispatchFence(
                state,
                pending,
                dispatchFenceConsumption,
                "budget.tool-reservation-exhausted",
                now,
            )
            return
        }
        val checkpointId = ids.nextId("agent-tool-dispatch-checkpoint")
        val dispatched = pending.with(
            phase = AgentPendingToolPhase.TOOL_DISPATCHED,
            checkpointId = checkpointId,
            claimedLeaseId = state.lease?.leaseId,
            dispatchFenceConsumption = dispatchFenceConsumption,
            toolDispatchedAt = now,
            reservedCostMicros = reservedCost,
            reservedDurationMillis = reservedDuration,
            updatedAt = now,
        )
        commitToolCheckpoint(
            state,
            dispatched,
            "tool.dispatched",
            AgentRuntimeStepStatus.CLAIMED,
            now,
            usage = usage,
        )?.let { committed -> dispatchTool(committed, committed.pendingOperation as AgentPendingToolOperation) }
    }

    /** A consumed fence is durable evidence even when no tool budget reservation was committed. */
    private fun reconcileConsumedDispatchFence(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        consumption: AgentDispatchAuthorizationFenceConsumption,
        code: String,
        now: Long,
    ) {
        val unknown = pending.with(
            phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED,
            dispatchFenceConsumption = consumption,
            updatedAt = now,
        )
        markReconciliation(state, unknown, code, now, state.usage)
    }

    private fun dispatchTool(state: AgentDurableRunState, pending: AgentPendingToolOperation) {
        val now = clock.currentTimeMillis()
        if (now >= requireNotNull(pending.invocationDeadlineAt) || !state.budget.allows(state.usage)) {
            val unknown = pending.with(phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED, updatedAt = now)
            markReconciliation(state, unknown, "tool.deadline-outcome-unknown", now, state.usage)
            return
        }
        if (state.capabilityId !in pending.plan.descriptor.capabilities) {
            reconcileDispatchedTool(state, pending, "tool.capability-outcome-unknown", now)
            return
        }
        val executorResolution = resolveToolExecutor(pending.plan.descriptor)
        val provider = executorResolution.executor
        if (executorResolution.failureCode != null || provider == null) {
            reconcileDispatchedTool(
                state,
                pending,
                executorResolution.failureCode ?: "tool.executor-missing",
                now,
            )
            return
        }
        val invocation = try {
            authorizedInvocation(state, pending)
        } catch (_: IllegalArgumentException) {
            reconcileDispatchedTool(state, pending, "tool.authorization-chain-outcome-unknown", now)
            return
        } catch (cancelled: AgentCancellationException) {
            cancel(AgentRunKey(state.tenantId, state.runId), cancelled.cancellation)
            return
        }
        val preparedAt = clock.currentTimeMillis()
        if (preparedAt >= requireNotNull(pending.invocationDeadlineAt)) {
            reconcileDispatchedTool(state, pending, "tool.dispatch-expiry-outcome-unknown", preparedAt)
            return
        }
        val executableDuration = minOf(
            requireNotNull(pending.reservedDurationMillis),
            requireNotNull(pending.invocationDeadlineAt) - preparedAt,
            requireNotNull(pending.finalExecutionAuthorization).expiresAt - preparedAt,
        )
        if (executableDuration <= 0L) {
            val unknown = pending.with(
                phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED,
                updatedAt = preparedAt,
            )
            markReconciliation(state, unknown, "tool.deadline-outcome-unknown", preparedAt, state.usage)
            return
        }
        val executable = try {
            AgentExecutableToolInvocation.create(
                invocation,
                requireNotNull(pending.consumption),
                requireNotNull(pending.finalExecutionRecheck),
                requireNotNull(pending.finalExecutionAuthorization),
                requireNotNull(pending.dispatchFenceRequest),
                requireNotNull(pending.dispatchFenceConsumption),
                executionContextConsumer.consumerId(),
                workerId,
                requireNotNull(pending.reservedCostMicros),
                executableDuration,
                preparedAt,
            )
        } catch (_: IllegalArgumentException) {
            val unknown = pending.with(phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED, updatedAt = now)
            markReconciliation(state, unknown, "tool.execution-receipt-invalid", now, state.usage)
            return
        }
        try {
            executable.requireExecutor(provider.providerId(), provider.toolId())
        } catch (_: IllegalArgumentException) {
            reconcileDispatchedTool(state, pending, "tool.executor-mismatch-outcome-unknown", now)
            return
        }
        val key = AgentRunKey(state.tenantId, state.runId)
        val live = store.load(key)
        if (live?.stateVersion != state.stateVersion || live.cancellation != null || live.status.isTerminal()) {
            return
        }
        val call = try {
            provider.start(executable, AgentToolObserver.NOOP)
        } catch (failure: Throwable) {
            handleToolExecutionFailure(
                state,
                pending,
                normalizeProviderFailure(provider.providerId(), AgentProviderOperationId.TOOL, failure),
            )
            return
        }
        val providerInvocationId = try {
            call.invocationId()
        } catch (failure: Throwable) {
            handleToolExecutionFailure(
                state,
                pending,
                normalizeProviderFailure(provider.providerId(), AgentProviderOperationId.TOOL, failure),
            )
            return
        }
        if (providerInvocationId != invocation.invocationId) {
            val unknown = pending.with(phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED, updatedAt = now)
            markReconciliation(state, unknown, "tool.call-identity-unknown", now, state.usage)
            return
        }
        activeCalls[key] = ActiveExternalCall { cancellation -> call.cancel(cancellation) }
        val completion = try {
            call.completion()
        } catch (failure: Throwable) {
            activeCalls.remove(key)
            handleToolExecutionFailure(
                state,
                pending,
                normalizeProviderFailure(provider.providerId(), AgentProviderOperationId.TOOL, failure),
            )
            return
        }
        try {
            completion.whenComplete { result, failure ->
                executor.execute {
                    activeCalls.remove(key)
                    val live = store.load(key)
                    if (live?.stateVersion != state.stateVersion ||
                        live.pendingOperation?.operationDigest != pending.operationDigest
                    ) {
                        return@execute
                    }
                    if (failure != null) {
                        handleToolExecutionFailure(
                            state,
                            pending,
                            normalizeProviderFailure(provider.providerId(), AgentProviderOperationId.TOOL, failure),
                        )
                    } else if (result == null) {
                        handleToolExecutionFailure(
                            state,
                            pending,
                            AgentProviderException(
                                provider.providerId(),
                                AgentFailureCategory.PROTOCOL,
                                "tool.null-result",
                            ),
                        )
                    } else {
                        handleToolResult(state, pending, executable, result)
                    }
                }
            }
        } catch (failure: Throwable) {
            activeCalls.remove(key)
            handleToolExecutionFailure(
                state,
                pending,
                normalizeProviderFailure(provider.providerId(), AgentProviderOperationId.TOOL, failure),
            )
        }
    }

    private fun handleToolExecutionFailure(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        failure: AgentProviderException,
    ) {
        val now = clock.currentTimeMillis()
        val code = if (failure.category == AgentFailureCategory.CANCELLED) {
            "tool.cancelled-outcome-unknown"
        } else {
            "tool.provider-outcome-unknown"
        }
        // Neither an exception nor retryability proves that provider.start had no side effect.
        // Retrying even an idempotent descriptor requires a trusted structured receipt, which the
        // current tool call contract does not expose.
        reconcileDispatchedTool(state, pending, code, now)
    }

    private fun handleToolResult(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        executable: AgentExecutableToolInvocation,
        result: AgentToolResult,
    ) {
        val now = maxOf(clock.currentTimeMillis(), state.updatedAt, pending.updatedAt)
        if (result.invocationId != pending.invocationId ||
            result.completedAt < requireNotNull(pending.toolDispatchedAt) || result.completedAt > now
        ) {
            reconcileDispatchedTool(state, pending, "tool.result-binding-unknown", now)
            return
        }
        val usage = try {
            val wallClockDuration = elapsedMillis(requireNotNull(pending.toolDispatchedAt), now)
            result.requireBindingIntact()
            require(result.usage.costMicros <= executable.maximumCostMicros &&
                result.usage.durationMillis <= executable.maximumDurationMillis &&
                wallClockDuration <= executable.maximumDurationMillis
            ) { "Agent tool result exceeded its reserved cost or duration." }
            retainConservativeToolReservation(state.usage, pending, result.usage, wallClockDuration)
        } catch (_: IllegalArgumentException) {
            reconcileDispatchedTool(state, pending, "tool.result-usage-invalid", now)
            return
        } catch (_: ArithmeticException) {
            reconcileDispatchedTool(state, pending, "tool.result-usage-invalid", now)
            return
        }
        try {
            require(result.canonicalPayloadSizeBytes <= pending.plan.descriptor.maximumResultBytes.toLong()) {
                "Agent tool result exceeds its descriptor limit."
            }
        } catch (_: IllegalArgumentException) {
            reconcileDispatchedTool(state, pending, "tool.result-content-invalid", now, usage)
            return
        }
        val contentFailure = authorizeContent(
            state,
            AgentContentSecurityBoundary.TOOL_OUTPUT,
            pending.plan.descriptor.providerId,
            pending.plan.descriptor.descriptorDigest,
            result.bindingDigest,
            emptyList(),
            result.blocks,
            emptyList(),
            now,
        )
        if (contentFailure != null) {
            reconcileDispatchedTool(state, pending, contentFailure, now, usage)
            return
        }
        if (!state.budget.allows(usage)) {
            reconcileDispatchedTool(state, pending, "budget.exceeded", now, usage)
            return
        }
        when (result.status) {
            AgentToolResultStatus.OUTCOME_UNKNOWN -> {
                reconcileDispatchedTool(state, pending, result.safeErrorCode ?: "tool.outcome-unknown", now, usage)
            }
            AgentToolResultStatus.CANCELLED -> {
                reconcileDispatchedTool(state, pending, "tool.cancelled-outcome-unknown", now, usage)
            }
            AgentToolResultStatus.SUCCEEDED, AgentToolResultStatus.FAILED -> completeToolAttempt(
                state,
                pending,
                result,
                usage,
                now,
            )
        }
    }

    private fun reconcileDispatchedTool(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        code: String,
        now: Long,
        usage: AgentUsage = state.usage,
    ) {
        val reconciledAt = maxOf(now, state.updatedAt, pending.updatedAt)
        val unknown = pending.with(
            phase = AgentPendingToolPhase.RECONCILIATION_REQUIRED,
            updatedAt = reconciledAt,
        )
        markReconciliation(state, unknown, code, reconciledAt, usage)
    }

    private fun completeToolAttempt(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        result: AgentToolResult,
        usage: AgentUsage,
        now: Long,
    ) {
        val block = AgentToolResultContentBlock(
            pending.plan.call.callId,
            pending.plan.descriptor.toolId,
            result.status,
            result.blocks,
            result.safeErrorCode,
        )
        val message = AgentMessage(
            ids.nextId("agent-tool-message"),
            AgentMessageRole.TOOL,
            listOf(block),
            result.completedAt,
        )
        var sequence = state.eventSequence
        val events = listOf(
            AgentRunMessageEvent(state.runId, state.tenantId, ++sequence, result.completedAt, message),
            AgentRunUsageEvent(state.runId, state.tenantId, ++sequence, now, usage),
        )
        val next = state.evolve(
            messages = state.messages + message,
            usage = usage,
            eventSequence = sequence,
            updatedAt = now,
            steps = replaceStep(
                state,
                pending.stepId,
                AgentRuntimeStepStatus.COMPLETED,
                pending.attempt,
                now,
            ),
            currentStepId = null,
            pendingOperation = null,
        )
        applyWorker(state, next, events)?.let(::continueOwned)
    }

    private fun handleToolProviderFailure(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        failure: AgentProviderException,
    ) {
        val now = clock.currentTimeMillis()
        if (failure.category == AgentFailureCategory.CANCELLED) {
            cancelWorker(state, AgentCancellation(failure.code, now), state.usage, now)
            return
        }
        val retryable = failure.category == AgentFailureCategory.RETRYABLE ||
            failure.category == AgentFailureCategory.RATE_LIMITED
        if (retryable && canRetryToolAttempt(state, pending, now, false)) {
            retryToolAttempt(state, pending, state.usage, "tool.provider-retry", now)
        } else {
            failWorker(state, failure.category, failure.code, now)
        }
    }

    private fun canRetryToolAttempt(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        now: Long,
        sideEffectWasDispatched: Boolean,
    ): Boolean = pending.attempt < configuration.maximumProviderAttempts &&
        pending.attempt < MAX_RUNTIME_ATTEMPTS && now < pending.plan.deadlineAt && now < state.deadlineAt &&
        (!sideEffectWasDispatched || pending.plan.descriptor.idempotent)

    private fun retryToolAttempt(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        usage: AgentUsage,
        code: String,
        now: Long,
    ) {
        val checkpointId = ids.nextId("agent-tool-retry-checkpoint")
        val preflight = createPreflight(state, pending.stepId, pending.plan, now)
        val retry = pending.retry(preflight, checkpointId, now)
        commitToolCheckpoint(
            state,
            retry,
            code,
            AgentRuntimeStepStatus.CHECKPOINTED,
            now,
            usage = usage,
        )?.let(::continueOwned)
    }

    private fun claimToolRemotePhase(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        phase: AgentPendingToolPhase,
        code: String,
        now: Long,
        afterCommit: (AgentDurableRunState, AgentPendingToolOperation) -> Unit,
    ) {
        val checkpointId = ids.nextId("agent-tool-claim-checkpoint")
        val claimed = pending.with(
            phase = phase,
            checkpointId = checkpointId,
            claimedLeaseId = requireNotNull(state.lease).leaseId,
            updatedAt = now,
        )
        commitToolCheckpoint(
            state,
            claimed,
            code,
            AgentRuntimeStepStatus.CLAIMED,
            now,
        )?.let { committed -> afterCommit(committed, committed.pendingOperation as AgentPendingToolOperation) }
    }

    private fun commitToolCheckpoint(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
        code: String,
        stepStatus: AgentRuntimeStepStatus,
        now: Long,
        status: AgentRunStatus = state.status,
        releaseLease: Boolean = false,
        usage: AgentUsage = state.usage,
    ): AgentDurableRunState? {
        val checkpoint = AgentRuntimeCheckpoint(
            pending.checkpointId,
            state.runId,
            state.tenantId,
            pending.stepId,
            pending.operationId,
            code,
            pending.operationDigest,
            state.checkpointSequence + 1L,
            now,
        )
        val events = ArrayList<AgentRunEvent>(3)
        var sequence = state.eventSequence
        if (status != state.status) {
            events += AgentRunStatusChangedEvent(
                state.runId,
                state.tenantId,
                ++sequence,
                now,
                state.status,
                status,
                code,
            )
        }
        if (!runtimeUsageEquals(usage, state.usage)) {
            events += AgentRunUsageEvent(state.runId, state.tenantId, ++sequence, now, usage)
        }
        events += AgentRuntimeCheckpointEvent(state.runId, state.tenantId, ++sequence, now, checkpoint)
        val next = state.evolve(
            usage = usage,
            status = status,
            eventSequence = sequence,
            checkpointSequence = state.checkpointSequence + 1L,
            updatedAt = now,
            steps = replaceStep(state, pending.stepId, stepStatus, pending.attempt, now),
            checkpoints = state.checkpoints + checkpoint,
            pendingOperation = pending,
            lease = if (releaseLease) null else state.lease,
        )
        return applyWorker(state, next, events)
    }

    private fun authorizedInvocation(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
    ): AuthorizedToolInvocation = AuthorizedToolInvocation.authorize(
        requireNotNull(pending.invocationId),
        requireNotNull(pending.proposal),
        pending.plan.descriptor,
        requireNotNull(pending.policyDecision),
        requireNotNull(pending.executionRecheck),
        requireNotNull(pending.executionAuthorization),
        pending.approvalRequest,
        pending.approvalDecision,
        pending.plan.arguments,
        pending.plan.idempotencyKey,
        pending.attempt,
        requireNotNull(pending.invocationStartedAt),
        requireNotNull(pending.invocationDeadlineAt),
        StoreCancellationToken(store, AgentRunKey(state.tenantId, state.runId)),
    )

    private fun usageAfterToolDispatch(
        usage: AgentUsage,
        reservedCostMicros: Long,
        reservedDurationMillis: Long,
    ): AgentUsage = safeAddUsage(
        usage,
        AgentUsage(0L, 0L, 0, 1, reservedDurationMillis, reservedCostMicros),
    )

    /** Provider self-reporting is not a trusted meter, so the full durable reservation remains charged. */
    private fun retainConservativeToolReservation(
        reservedUsage: AgentUsage,
        pending: AgentPendingToolOperation,
        providerUsage: AgentUsage,
        wallClockDurationMillis: Long,
    ): AgentUsage {
        val reservedCost = requireNotNull(pending.reservedCostMicros)
        val reservedDuration = requireNotNull(pending.reservedDurationMillis)
        val actualDuration = maxOf(providerUsage.durationMillis, wallClockDurationMillis)
        require(providerUsage.costMicros <= reservedCost && actualDuration <= reservedDuration) {
            "Agent tool usage exceeded its durable reservation."
        }
        return reservedUsage
    }

    /** Charge every conservative model reservation before provider.start, including unknown outcomes. */
    private fun usageAfterModelDispatch(
        usage: AgentUsage,
        pending: AgentPendingModelOperation,
    ): AgentUsage = safeAddUsage(
        usage,
        AgentUsage(
            pending.maximumInputTokens,
            pending.maximumOutputTokens,
            1,
            0,
            pending.maximumDurationMillis,
            pending.maximumCostMicros,
        ),
    )

    /** Provider usage is bounds evidence only; without a host-verifiable receipt no reservation is refunded. */
    private fun retainConservativeModelReservation(usage: AgentUsage): AgentUsage = usage

    private fun conservativeShare(remaining: Long, slots: Int): Long {
        require(remaining > 0L && slots > 0) { "Agent budget has no remaining conservative reservation." }
        return 1L + (remaining - 1L) / slots.toLong()
    }

    private fun conservativeShareAllowingZero(remaining: Long, slots: Int): Long {
        require(remaining >= 0L && slots > 0) { "Agent budget has no remaining conservative reservation." }
        return if (remaining == 0L) 0L else conservativeShare(remaining, slots)
    }

    private fun cancelWorker(
        state: AgentDurableRunState,
        cancellation: AgentCancellation,
        usage: AgentUsage,
        now: Long,
    ) {
        if (state.status.isTerminal()) return
        val sequence = state.eventSequence + 1L
        val event = AgentRunStatusChangedEvent(
            state.runId,
            state.tenantId,
            sequence,
            now,
            state.status,
            AgentRunStatus.CANCELLED,
            cancellation.reasonCode,
        )
        val next = state.evolve(
            messages = securitySafeMessages(state),
            usage = usage,
            status = AgentRunStatus.CANCELLED,
            eventSequence = sequence,
            updatedAt = now,
            steps = state.steps.map { step ->
                if (step.stepId == state.currentStepId) {
                    step.transition(AgentRuntimeStepStatus.FAILED, step.attempt, now)
                } else step
            },
            currentStepId = null,
            pendingOperation = null,
            lease = null,
            cancellation = cancellation,
            failure = null,
        )
        applyWorker(state, next, listOf(event))
    }

    private fun schedule(tenantId: Identifier, runId: Identifier) {
        val key = AgentRunKey(tenantId, runId)
        executor.execute {
            try {
                resume(key)
            } catch (_: RuntimeException) {
                store.load(key)?.let { state ->
                    if (!state.status.isTerminal() && state.lease?.ownerId == workerId) {
                        failWorker(state, AgentFailureCategory.PROTOCOL, "runtime.coordinator-failed", clock.currentTimeMillis())
                    }
                }
            }
        }
    }

    private fun applyWorker(
        current: AgentDurableRunState,
        next: AgentDurableRunState,
        events: List<AgentRunEvent>,
    ): AgentDurableRunState? {
        val lease = requireNotNull(current.lease) { "Agent worker transition requires a claimed lease." }
        val now = clock.currentTimeMillis()
        if (!lease.isCurrent(now)) return null
        val result = store.commit(
            AgentStoreCommit(
                AgentRunKey(current.tenantId, current.runId),
                current.stateVersion,
                current.eventSequence,
                AgentStoreCommitAuthority.WORKER,
                lease,
                now,
                next,
                events,
            ),
        )
        if (result.status == AgentStoreCommitStatus.APPLIED) {
            val state = result.state!!
            publish(AgentRunKey(state.tenantId, state.runId), state, events)
            return state
        }
        result.state?.let { completeIfTerminal(AgentRunKey(it.tenantId, it.runId), it) }
        return null
    }

    private fun failWorker(
        state: AgentDurableRunState,
        category: AgentFailureCategory,
        code: String,
        now: Long,
        usage: AgentUsage = state.usage,
    ) {
        if (state.status.isTerminal()) return
        val pendingTool = state.pendingOperation as? AgentPendingToolOperation
        if (pendingTool != null &&
            (pendingTool.dispatchFenceConsumption != null || pendingTool.toolDispatchedAt != null)
        ) {
            // Terminal failure clears pendingOperation. Once atomic fence consumption or a durable
            // dispatch exists, that would destroy the only evidence needed to reconcile effects.
            reconcileDispatchedTool(state, pendingTool, code, now, usage)
            return
        }
        val sequence = state.eventSequence + 1L
        val event = AgentRunStatusChangedEvent(
            state.runId,
            state.tenantId,
            sequence,
            now,
            state.status,
            AgentRunStatus.FAILED,
            code,
        )
        val steps = state.steps.map { step ->
            if (step.stepId == state.currentStepId) {
                step.transition(AgentRuntimeStepStatus.FAILED, step.attempt, now)
            } else step
        }
        val next = state.evolve(
            messages = securitySafeMessages(state),
            usage = usage,
            status = AgentRunStatus.FAILED,
            eventSequence = sequence,
            updatedAt = now,
            steps = steps,
            currentStepId = null,
            pendingOperation = null,
            lease = null,
            failure = terminalFailure(category, code),
        )
        applyWorker(state, next, listOf(event))
    }

    private fun securitySafeMessages(state: AgentDurableRunState): List<AgentMessage> = try {
        state.messages.forEach(AgentMessage::requireBindingIntact)
        state.messages
    } catch (_: IllegalArgumentException) {
        // A mutable extension violated its binding. Do not retain or expose any possibly changed payload.
        emptyList()
    }

    private fun expireWorker(state: AgentDurableRunState, code: String, now: Long) {
        val pendingTool = state.pendingOperation as? AgentPendingToolOperation
        if (pendingTool != null &&
            (pendingTool.dispatchFenceConsumption != null || pendingTool.toolDispatchedAt != null)
        ) {
            reconcileDispatchedTool(state, pendingTool, code, now)
            return
        }
        val sequence = state.eventSequence + 1L
        val event = AgentRunStatusChangedEvent(
            state.runId,
            state.tenantId,
            sequence,
            now,
            state.status,
            AgentRunStatus.EXPIRED,
            code,
        )
        val next = state.evolve(
            messages = securitySafeMessages(state),
            status = AgentRunStatus.EXPIRED,
            eventSequence = sequence,
            updatedAt = now,
            steps = state.steps.map { step ->
                if (step.stepId == state.currentStepId) {
                    step.transition(AgentRuntimeStepStatus.FAILED, step.attempt, now)
                } else step
            },
            currentStepId = null,
            pendingOperation = null,
            lease = null,
            failure = null,
        )
        applyWorker(state, next, listOf(event))
    }

    private fun markReconciliation(
        state: AgentDurableRunState,
        pending: AgentPendingOperation,
        code: String,
        now: Long,
        usage: AgentUsage,
    ) {
        val incident = AgentRuntimeIncident(
            ids.nextId("agent-incident"),
            state.runId,
            state.tenantId,
            pending.stepId,
            code,
            AgentRuntimeIncidentStatus.OPEN,
            false,
            now,
        )
        val events = ArrayList<AgentRunEvent>(2)
        var sequence = state.eventSequence
        if (state.status != AgentRunStatus.WAITING_TOOL) {
            events += AgentRunStatusChangedEvent(
                state.runId,
                state.tenantId,
                ++sequence,
                now,
                state.status,
                AgentRunStatus.WAITING_TOOL,
                code,
            )
        }
        events += AgentRuntimeIncidentEvent(state.runId, state.tenantId, ++sequence, now, incident)
        val next = state.evolve(
            usage = usage,
            status = AgentRunStatus.WAITING_TOOL,
            eventSequence = sequence,
            updatedAt = now,
            steps = replaceStep(
                state,
                pending.stepId,
                AgentRuntimeStepStatus.WAITING_RECONCILIATION,
                pending.attempt,
                now,
            ),
            pendingOperation = pending,
            lease = null,
            incidents = state.incidents + incident,
        )
        applyWorker(state, next, events)
    }

    private fun createPreflight(
        state: AgentDurableRunState,
        stepId: Identifier,
        plan: AgentToolExecutionPlan,
        now: Long,
    ): AgentAuthorizationRequest = AgentAuthorizationRequest.preflight(
        ids.nextId("agent-authorization-preflight-request"),
        ids.nextId("agent-execution-context"),
        state.tenantId,
        state.context.principalId,
        state.context.principalType,
        state.runId,
        stepId,
        plan.authorizationProviderId,
        plan.descriptor,
        plan.arguments,
        plan.idempotencyKey,
        plan.action,
        plan.resourceType,
        plan.resourceId,
        plan.resourceRevision,
        plan.purpose,
        now,
        plan.deadlineAt,
    )

    private fun authorizeCommand(
        context: AgentRunCommandContext,
        runId: Identifier,
        action: AgentRunCommandAction,
        expectedStateVersion: Long?,
        evidenceDigest: String?,
    ) {
        val now = clock.currentTimeMillis()
        val request = AgentRunCommandAuthorizationRequest(
            ids.nextId("agent-run-command-authorization-request"),
            context,
            runId,
            action,
            expectedStateVersion,
            evidenceDigest,
            now,
            if (now > Long.MAX_VALUE - configuration.leaseDurationMillis) Long.MAX_VALUE
            else now + configuration.leaseDurationMillis,
        )
        val decision = try {
            commandAuthorizationPort.authorize(request)
        } catch (failure: AgentRunCommandAuthorizationException) {
            throw failure
        } catch (_: RuntimeException) {
            throw AgentRunCommandAuthorizationException("command.authorization-failed")
        }
        if (decision.providerId != commandAuthorizationPort.providerId() ||
            decision.outcome != AgentRunCommandAuthorizationOutcome.ALLOW
        ) {
            throw AgentRunCommandAuthorizationException(decision.reasonCode ?: "command.authorization-denied")
        }
        try {
            decision.requireAllowedFor(request, now)
        } catch (_: IllegalArgumentException) {
            throw AgentRunCommandAuthorizationException("command.authorization-invalid")
        }
    }

    /** Rechecks dynamic permission for model-only work as well as every tool phase and recovery. */
    private fun authorizeContinuation(state: AgentDurableRunState, now: Long): String? =
        authorizeContinuationResult(state, now).reasonCode

    private fun authorizeContinuationResult(
        state: AgentDurableRunState,
        now: Long,
    ): AgentContinuationAuthorizationResult {
        val expiresAt = minOf(
            state.deadlineAt,
            if (now > Long.MAX_VALUE - configuration.leaseDurationMillis) Long.MAX_VALUE
            else now + configuration.leaseDurationMillis,
        )
        if (expiresAt <= now) return AgentContinuationAuthorizationResult(null, "continuation.expired")
        val request = try {
            AgentRunContinuationAuthorizationRequest(
                ids.nextId("agent-run-continuation-authorization-request"),
                state.tenantId,
                state.context.principalId,
                state.context.principalType,
                state.runId,
                state.capabilityId,
                state.status,
                state.stateVersion,
                continuationStateDigest(state),
                state.admission.authorizationRevision,
                now,
                expiresAt,
            )
        } catch (_: IllegalArgumentException) {
            return AgentContinuationAuthorizationResult(null, "content.binding-invalid")
        }
        val decision = try {
            continuationAuthorizationPort.authorize(request)
        } catch (failure: AgentRunContinuationAuthorizationException) {
            return AgentContinuationAuthorizationResult(null, failure.reasonCode)
        } catch (_: RuntimeException) {
            return AgentContinuationAuthorizationResult(null, "continuation.authorization-failed")
        }
        if (decision.providerId != continuationAuthorizationPort.providerId()) {
            return AgentContinuationAuthorizationResult(null, "continuation.provider-mismatch")
        }
        if (decision.outcome != AgentRunContinuationAuthorizationOutcome.ALLOW) {
            return AgentContinuationAuthorizationResult(null, decision.reasonCode ?: "continuation.denied")
        }
        return try {
            decision.requireAllowedFor(request, clock.currentTimeMillis())
            AgentContinuationAuthorizationResult(decision, null)
        } catch (_: IllegalArgumentException) {
            AgentContinuationAuthorizationResult(null, "continuation.authorization-invalid")
        }
    }

    /**
     * Runs current ACL authorization and the host's exact content/DLP policy before any payload can
     * cross a provider boundary or enter durable state. No prompt or result is copied into errors.
     */
    private fun authorizeContent(
        state: AgentDurableRunState,
        boundary: AgentContentSecurityBoundary,
        contentProviderId: ProviderId,
        contentProviderBindingDigest: String,
        operationBindingDigest: String,
        messages: Collection<AgentMessage>,
        blocks: Collection<AgentContentBlock>,
        tools: Collection<AgentToolDescriptor>,
        now: Long,
    ): String? {
        val continuation = authorizeContinuationResult(state, now)
        if (continuation.reasonCode != null) return continuation.reasonCode
        val continuationDecision = requireNotNull(continuation.decision)
        structuralContentFailure(state, messages, blocks)?.let { return it }
        val contentRequestedAt = clock.currentTimeMillis()
        val expiresAt = minOf(state.deadlineAt, continuationDecision.expiresAt)
        if (expiresAt <= contentRequestedAt) return "content.authorization-expired"
        val request = try {
            AgentContentSecurityRequest(
                ids.nextId("agent-content-security-request"),
                state.tenantId,
                state.context.principalId,
                state.context.principalType,
                state.runId,
                state.capabilityId,
                state.stateVersion,
                boundary,
                contentProviderId,
                contentProviderBindingDigest,
                operationBindingDigest,
                continuationStateDigest(state),
                continuationDecision.authorizationRevision,
                messages,
                blocks,
                tools,
                contentRequestedAt,
                expiresAt,
            )
        } catch (_: IllegalArgumentException) {
            return "content.binding-invalid"
        }
        val decision = try {
            contentSecurityPort.evaluate(request)
        } catch (_: RuntimeException) {
            return "content.security-failed"
        }
        if (decision.providerId != contentSecurityPort.providerId()) return "content.provider-mismatch"
        if (decision.outcome != AgentContentSecurityOutcome.ALLOW) {
            return decision.reasonCode ?: "content.denied"
        }
        return try {
            decision.requireAllowedFor(request, clock.currentTimeMillis())
            null
        } catch (_: IllegalArgumentException) {
            "content.decision-invalid"
        }
    }

    /** Structural tenant checks are mandatory even when a configured semantic policy allows data. */
    private fun structuralContentFailure(
        state: AgentDurableRunState,
        messages: Collection<AgentMessage>,
        blocks: Collection<AgentContentBlock>,
    ): String? {
        fun inspect(block: AgentContentBlock): String? = when (block) {
            is AgentCitationContentBlock -> if (block.citation.tenantId == state.tenantId) null
            else "content.citation-tenant-mismatch"
            is AgentToolResultContentBlock -> block.blocks.asSequence().mapNotNull(::inspect).firstOrNull()
            else -> null
        }
        return try {
            messages.forEach(AgentMessage::requireBindingIntact)
            messages.asSequence()
                .flatMap { message -> message.blocks.asSequence() }
                .mapNotNull(::inspect)
                .firstOrNull()
                ?: blocks.asSequence().mapNotNull(::inspect).firstOrNull()
        } catch (_: IllegalArgumentException) {
            "content.binding-invalid"
        }
    }

    private fun continuationStateDigest(state: AgentDurableRunState): String {
        val digest = AgentRuntimeDigest("flowweft.agent.runtime.continuation-state.v1")
            .add(state.tenantId.value)
            .add(state.runId.value)
            .add(state.context.principalType)
            .add(state.context.principalId.value)
            .add(state.capabilityId.value)
            .add(state.status.name)
            .add(state.stateVersion)
            .add(state.eventSequence)
            .add(state.checkpointSequence)
            .add(state.usage.inputTokens)
            .add(state.usage.outputTokens)
            .add(state.usage.modelCalls)
            .add(state.usage.toolCalls)
            .add(state.usage.durationMillis)
            .add(state.usage.costMicros)
            .add(state.messages.size)
        state.messages.forEach { message ->
            message.requireBindingIntact()
            digest.add(message.bindingDigest)
        }
        digest.add(state.pendingOperation?.operationDigest ?: "-")
        digest.add(state.cancellation?.reasonCode ?: "-")
        return digest.finish()
    }

    private fun approvalEvidenceDigest(decision: AgentApprovalDecision): String =
        AgentRuntimeDigest("flowweft.agent.runtime.approval-command-evidence.v1")
            .add(decision.decisionId.value)
            .add(decision.requestId.value)
            .add(decision.proposalId.value)
            .add(decision.policyDecisionId.value)
            .add(decision.operatorType)
            .add(decision.operatorId.value)
            .add(decision.nonce)
            .add(decision.outcome.name)
            .add(decision.decidedAt)
            .finish()

    private fun reconciliationEvidenceDigest(decision: AgentReconciliationDecision): String =
        AgentRuntimeDigest("flowweft.agent.runtime.reconciliation-command-evidence.v1")
            .add(decision.decisionId.value)
            .add(decision.stepId.value)
            .add(decision.operationDigest)
            .add(decision.outcome.name)
            .add(decision.evidenceDigest)
            .add(decision.decidedAt)
            .finish()

    private fun checkpoint(
        state: AgentDurableRunState,
        pending: AgentPendingOperation,
        code: String,
        now: Long,
    ): AgentRuntimeCheckpoint = AgentRuntimeCheckpoint(
        ids.nextId("agent-runtime-checkpoint"),
        state.runId,
        state.tenantId,
        pending.stepId,
        pending.operationId,
        code,
        pending.operationDigest,
        state.checkpointSequence + 1L,
        now,
    )

    private fun replaceStep(
        state: AgentDurableRunState,
        stepId: Identifier,
        status: AgentRuntimeStepStatus,
        attempt: Int,
        now: Long,
    ): List<AgentRuntimeStep> = state.steps.map { step ->
        if (step.stepId == stepId) step.transition(status, attempt, now) else step
    }

    private fun resolveToolExecutor(descriptor: AgentToolDescriptor): AgentToolExecutorResolution {
        val candidate = toolExecutors.find(descriptor.providerId, descriptor.toolId)
        if (candidate == null || candidate.providerId() != descriptor.providerId ||
            candidate.toolId() != descriptor.toolId
        ) {
            return AgentToolExecutorResolution(null, "tool.executor-missing")
        }
        val bound = candidate as? AgentDescriptorBoundToolExecutor
        if (bound == null || bound.descriptorDigest() != descriptor.descriptorDigest) {
            return AgentToolExecutorResolution(null, "tool.executor-descriptor-changed")
        }
        return AgentToolExecutorResolution(bound, null)
    }

    private fun exactToolCallMessage(
        state: AgentDurableRunState,
        pending: AgentPendingToolOperation,
    ): AgentMessage? = state.messages.lastOrNull { message ->
        val block = message.blocks.singleOrNull() as? AgentToolCallContentBlock
        message.role == AgentMessageRole.ASSISTANT &&
            block?.bindingDigest() == pending.plan.call.bindingDigest()
    }

    /** Restore-time capability fence: persisted model/tool state may never widen the admitted run. */
    private fun pendingCapabilityMatches(state: AgentDurableRunState): Boolean = when (val pending = state.pendingOperation) {
        null -> true
        is AgentPendingModelOperation ->
            state.capabilityId in pending.descriptor.capabilities &&
                pending.tools.all { descriptor -> state.capabilityId in descriptor.capabilities }
        is AgentPendingToolOperation -> state.capabilityId in pending.plan.descriptor.capabilities
        else -> false
    }

    private fun sameModelDescriptor(first: LanguageModelDescriptor, second: LanguageModelDescriptor): Boolean =
        first.providerId == second.providerId && first.modelId == second.modelId &&
            first.descriptorDigest == second.descriptorDigest

    private fun normalizeProviderFailure(
        providerId: ProviderId,
        operation: AgentProviderOperationId,
        failure: Throwable,
    ): AgentProviderException = AgentProviderFailures.normalize(
        providerId,
        operation,
        providerFailureMapper,
        failure,
    )

    private fun unwrapCompletionFailure(failure: Throwable): Throwable {
        var current = failure
        var depth = 0
        while (depth < 8 &&
            (current is java.util.concurrent.CompletionException || current is java.util.concurrent.ExecutionException)
        ) {
            val cause = current.cause ?: break
            if (cause === current) break
            current = cause
            depth++
        }
        return current
    }

    private fun register(
        state: AgentDurableRunState,
        observer: AgentRunObserver,
        commandContext: AgentRunCommandContext = AgentRunCommandContext(
            state.tenantId,
            state.context.principalId,
            state.context.principalType,
            state.context.requestId,
            state.context.initiatedAt,
        ),
    ): DurableRunCall {
        val key = AgentRunKey(state.tenantId, state.runId)
        val call = DurableRunCall(key, commandContext, observer, this)
        calls.computeIfAbsent(key) { CopyOnWriteArrayList() }.add(call)
        if (state.status.isTerminal()) call.complete(state.snapshot())
        return call
    }

    private fun publish(key: AgentRunKey, state: AgentDurableRunState, events: List<AgentRunEvent>) {
        calls[key]?.forEach { call ->
            events.forEach { event ->
                try {
                    call.observer.onEvent(event)
                } catch (_: RuntimeException) {
                    // Observers are diagnostics only and cannot roll back durable work.
                }
            }
            if (state.status.isTerminal()) call.complete(state.snapshot())
        }
        if (state.status.isTerminal()) activeCalls.remove(key)
    }

    private fun completeIfTerminal(key: AgentRunKey, state: AgentDurableRunState) {
        if (state.status.isTerminal()) calls[key]?.forEach { it.complete(state.snapshot()) }
    }

    private fun resumeTool(state: AgentDurableRunState, pending: AgentPendingToolOperation, now: Long) {
        resumeToolPhase(state, pending, now)
    }

    private class AgentContinuationAuthorizationResult(
        val decision: AgentRunContinuationAuthorizationDecision?,
        val reasonCode: String?,
    ) {
        init {
            require((decision == null) != (reasonCode == null)) {
                "Agent continuation authorization result must contain either a decision or a failure."
            }
        }
    }

    private class AgentToolExecutorResolution(
        val executor: AgentDescriptorBoundToolExecutor?,
        val failureCode: String?,
    ) {
        init {
            require((executor == null) != (failureCode == null)) {
                "Agent tool executor resolution must contain either an executor or a failure."
            }
        }
    }

    private fun interface ActiveExternalCall {
        fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean>
    }

    private class StoreCancellationToken(
        private val store: AgentDurableRunStore,
        private val key: AgentRunKey,
    ) : AgentCancellationToken {
        override fun cancellation(): AgentCancellation? = store.load(key)?.cancellation
    }

    private class DurableRunCall(
        private val key: AgentRunKey,
        private val commandContext: AgentRunCommandContext,
        val observer: AgentRunObserver,
        private val coordinator: DurableAgentRunCoordinator,
    ) : AgentRunCall {
        private val result = CompletableFuture<AgentRunSnapshot>()

        override fun runId(): Identifier = key.runId
        override fun completion(): CompletionStage<AgentRunSnapshot> = result
        override fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean> =
            CompletableFuture.completedFuture(coordinator.cancelAuthorized(commandContext, key, cancellation))

        fun complete(snapshot: AgentRunSnapshot) {
            result.complete(snapshot)
        }
    }
}

enum class AgentApprovalConfirmationStatus {
    APPLIED,
    REPLAYED,
    VERSION_CONFLICT,
    NOT_WAITING,
    MISSING,
}

class AgentApprovalConfirmationResult(
    val status: AgentApprovalConfirmationStatus,
    val snapshot: AgentRunSnapshot?,
)

enum class AgentReconciliationApplyStatus {
    APPLIED,
    STILL_UNKNOWN,
    VERSION_CONFLICT,
    NOT_WAITING,
    MISSING,
}

class AgentReconciliationApplyResult(
    val status: AgentReconciliationApplyStatus,
    val snapshot: AgentRunSnapshot?,
)
