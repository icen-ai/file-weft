package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentCancellationToken
import ai.icen.fw.agent.api.AgentEvaluationCitationObservation
import ai.icen.fw.agent.api.AgentEvaluationCostObservation
import ai.icen.fw.agent.api.AgentEvaluationDiagnostic
import ai.icen.fw.agent.api.AgentEvaluationDiagnosticReason
import ai.icen.fw.agent.api.AgentEvaluationDiagnosticStatus
import ai.icen.fw.agent.api.AgentEvaluationLatencyObservation
import ai.icen.fw.agent.api.AgentEvaluationObservation
import ai.icen.fw.agent.api.AgentEvaluationObservationContext
import ai.icen.fw.agent.api.AgentEvaluationRefusalObservation
import ai.icen.fw.agent.api.AgentEvaluationRetrievalObservation
import ai.icen.fw.agent.api.AgentEvaluationToolDecisionObservation
import ai.icen.fw.agent.api.ProviderId
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Durable, provider-neutral evaluation coordinator. Store calls receive inert commands only;
 * fixture and evaluator calls happen after those calls return and therefore cannot run inside the
 * persistence transaction. The coordinator has no repository or domain mutation dependency.
 */
class DurableAgentEvaluationCoordinator(
    private val store: AgentEvaluationDurableStore,
    private val fixturePort: AgentEvaluationFixturePort,
    private val evaluatorPort: AgentEvaluationCaseEvaluatorPort,
    private val failureClassifier: AgentEvaluationFailureClassifier,
    private val clock: AgentRuntimeClock,
    private val ids: AgentRuntimeIdGenerator,
    private val configuration: AgentEvaluationRuntimeConfiguration,
) {

    fun start(request: AgentEvaluationRunRequest): AgentEvaluationRunState {
        val now = clock.currentTimeMillis()
        require(request.requestedAt <= now && now < request.deadlineAt && request.providerSnapshot.isCurrent(now)) {
            "Agent evaluation request is outside its trusted runtime window."
        }
        store.findByIdempotency(request.idempotencyScope)?.let { existing ->
            require(existing.requestBindingDigest == request.requestBindingDigest) {
                "Agent evaluation idempotency key was reused with a different request."
            }
            return existing
        }
        val initial = AgentEvaluationRunState.initial(ids.nextId("agent-evaluation"), request)
        val result = store.create(initial)
        require(result.state.idempotencyScope == request.idempotencyScope &&
            result.state.requestBindingDigest == request.requestBindingDigest
        ) { "Agent evaluation create result does not match its idempotent request." }
        return result.state
    }

    fun execute(key: AgentEvaluationRunKey, workerId: ProviderId): AgentEvaluationRunState {
        var durable = store.load(key) ?: throw AgentEvaluationRuntimeException(
            AgentEvaluationDiagnosticReason("evaluation.missing"),
        )
        if (durable.status.isTerminal()) return durable

        var now = clock.currentTimeMillis()
        if (now >= durable.deadlineAt) {
            return if (durable.status == AgentEvaluationRunStatus.QUEUED) {
                expireBeforeClaim(durable, now)
            } else {
                failWithDiagnostic(
                    durable,
                    AgentEvaluationDiagnosticStatus.EXPIRED,
                    AgentEvaluationDiagnosticReason("evaluation.deadline-exceeded"),
                    expired = true,
                )
            }
        }
        val claimDuration = minOf(configuration.leaseDurationMillis, durable.deadlineAt - now)
        val claim = store.claim(
            AgentEvaluationLeaseClaim(
                key,
                workerId,
                ids.nextId("agent-evaluation-lease"),
                now,
                claimDuration,
            ),
        )
        if (claim.status != AgentEvaluationLeaseClaimStatus.ACQUIRED) {
            return claim.state ?: throw AgentEvaluationRuntimeException(
                AgentEvaluationDiagnosticReason("evaluation.missing"),
            )
        }
        durable = requireNotNull(claim.state)

        val selectedSnapshot = evaluatorPort.snapshot()
        if (selectedSnapshot.snapshotDigest != durable.providerSnapshot.snapshotDigest ||
            selectedSnapshot.providerId != durable.providerSnapshot.providerId
        ) {
            return failWithDiagnostic(
                durable,
                AgentEvaluationDiagnosticStatus.DRIFTED,
                AgentEvaluationDiagnosticReason.SNAPSHOT_DRIFT,
                expired = false,
            )
        }
        now = clock.currentTimeMillis()
        if (!selectedSnapshot.isCurrent(now)) {
            return failWithDiagnostic(
                durable,
                AgentEvaluationDiagnosticStatus.EXPIRED,
                AgentEvaluationDiagnosticReason.SNAPSHOT_EXPIRED,
                expired = false,
            )
        }

        return try {
            for (case in durable.suite.cases) {
                if (durable.evidence.any { evidence -> evidence.caseId == case.caseId }) continue
                now = clock.currentTimeMillis()
                if (now >= durable.deadlineAt) {
                    return failWithDiagnostic(
                        durable,
                        AgentEvaluationDiagnosticStatus.EXPIRED,
                        AgentEvaluationDiagnosticReason("evaluation.deadline-exceeded"),
                        expired = true,
                    )
                }
                if (!durable.providerSnapshot.isCurrent(now)) {
                    return failWithDiagnostic(
                        durable,
                        AgentEvaluationDiagnosticStatus.EXPIRED,
                        AgentEvaluationDiagnosticReason.SNAPSHOT_EXPIRED,
                        expired = false,
                    )
                }
                val lease = requireNotNull(durable.lease)
                if (!lease.isCurrent(now)) return store.load(key) ?: durable

                val context = AgentEvaluationObservationContext(
                    durable.suite.suiteId,
                    durable.suite.suiteDigest,
                    case.caseId,
                    case.bindingDigest,
                    durable.tenantId,
                    durable.principalId,
                    durable.principalType,
                    durable.authorizationRevision,
                    durable.providerSnapshot.snapshotDigest,
                    now,
                )
                val fixtureRequest = AgentEvaluationFixtureLoadRequest(
                    ids.nextId("agent-evaluation-fixture-request"),
                    durable.evaluationId,
                    context,
                    case.fixtureId,
                    case.inputDigest,
                    now,
                    durable.deadlineAt,
                )
                val fixture = await(fixturePort.load(fixtureRequest), durable.deadlineAt)
                require(fixture.fixtureId == case.fixtureId && fixture.payloadDigest == case.inputDigest) {
                    "Agent evaluation fixture provider returned a different fixed input."
                }
                val executionRequest = AgentEvaluationCaseExecutionRequest(
                    ids.nextId("agent-evaluation-case-request"),
                    durable.evaluationId,
                    context,
                    case,
                    fixture,
                    durable.providerSnapshot,
                    clock.currentTimeMillis(),
                    durable.deadlineAt,
                    StoreCancellationToken(store, key),
                )
                val result = await(evaluatorPort.evaluate(executionRequest), durable.deadlineAt)
                result.requireValidFor(executionRequest)
                if (result.diagnostic.status != AgentEvaluationDiagnosticStatus.READY) {
                    return failReturnedDiagnostic(durable, result.diagnostic)
                }
                val evidence = AgentEvaluationCaseEvidence(
                    case.caseId,
                    case.bindingDigest,
                    observationsPass(case, result.observations),
                    result.observations.map(AgentEvaluationObservation::bindingDigest),
                    result.diagnostic,
                    result.completedAt,
                )
                now = clock.currentTimeMillis()
                if (now >= durable.deadlineAt || !lease.isCurrent(now)) {
                    return store.load(key) ?: durable
                }
                val renewedLease = renew(lease, now, durable.deadlineAt)
                val next = durable.progressed(lease, renewedLease, evidence, now)
                val committed = store.heartbeat(
                    AgentEvaluationStateCommit(key, durable.stateVersion, lease, now, next),
                )
                if (committed.status != AgentEvaluationCommitStatus.APPLIED) {
                    return committed.state ?: throw AgentEvaluationRuntimeException(
                        AgentEvaluationDiagnosticReason("evaluation.missing"),
                    )
                }
                durable = requireNotNull(committed.state)
            }

            now = clock.currentTimeMillis()
            val lease = requireNotNull(durable.lease)
            if (now >= durable.deadlineAt || !lease.isCurrent(now)) return store.load(key) ?: durable
            val completed = durable.completed(lease, now)
            val result = store.complete(
                AgentEvaluationStateCommit(key, durable.stateVersion, lease, now, completed),
            )
            result.state ?: throw AgentEvaluationRuntimeException(
                AgentEvaluationDiagnosticReason("evaluation.missing"),
            )
        } catch (failure: Throwable) {
            handleFailure(durable, unwrap(failure))
        }
    }

    fun heartbeat(key: AgentEvaluationRunKey, workerId: ProviderId): AgentEvaluationRunState {
        val current = store.load(key) ?: throw AgentEvaluationRuntimeException(
            AgentEvaluationDiagnosticReason("evaluation.missing"),
        )
        val lease = current.lease
            ?: throw AgentEvaluationRuntimeException(AgentEvaluationDiagnosticReason("evaluation.lease-missing"))
        require(lease.ownerId == workerId) { "Agent evaluation heartbeat owner does not hold the lease." }
        val now = clock.currentTimeMillis()
        require(lease.isCurrent(now) && now < current.deadlineAt) { "Agent evaluation heartbeat lease is not current." }
        val renewed = renew(lease, now, current.deadlineAt)
        val next = current.heartbeat(lease, renewed, now)
        return store.heartbeat(
            AgentEvaluationStateCommit(key, current.stateVersion, lease, now, next),
        ).state ?: throw AgentEvaluationRuntimeException(AgentEvaluationDiagnosticReason("evaluation.missing"))
    }

    fun cancel(request: AgentEvaluationCancellationRequest): AgentEvaluationRunState {
        val current = store.load(request.key) ?: throw AgentEvaluationRuntimeException(
            AgentEvaluationDiagnosticReason("evaluation.missing"),
        )
        if (current.status.isTerminal()) return current
        require(current.principalId == request.principalId && current.principalType == request.principalType &&
            current.authorizationRevision == request.authorizationRevision
        ) { "Agent evaluation cancellation does not match its trusted owner and authorization revision." }
        val now = clock.currentTimeMillis()
        require(request.requestedAt <= now) { "Agent evaluation cancellation time is in the future." }
        val cancelled = current.cancelled(request.reasonCode, now)
        return store.cancel(
            AgentEvaluationStateCommit(request.key, current.stateVersion, null, now, cancelled),
        ).state ?: throw AgentEvaluationRuntimeException(AgentEvaluationDiagnosticReason("evaluation.missing"))
    }

    private fun expireBeforeClaim(state: AgentEvaluationRunState, atTime: Long): AgentEvaluationRunState {
        val diagnostic = diagnostic(
            state,
            AgentEvaluationDiagnosticStatus.EXPIRED,
            AgentEvaluationDiagnosticReason("evaluation.deadline-exceeded"),
            atTime,
        )
        val expired = state.expiredBeforeClaim(diagnostic, atTime)
        return store.fail(
            AgentEvaluationStateCommit(state.key(), state.stateVersion, null, atTime, expired),
        ).state ?: throw AgentEvaluationRuntimeException(AgentEvaluationDiagnosticReason("evaluation.missing"))
    }

    private fun failReturnedDiagnostic(
        state: AgentEvaluationRunState,
        returned: AgentEvaluationDiagnostic,
    ): AgentEvaluationRunState {
        val reason = returned.reason ?: AgentEvaluationDiagnosticReason.EVALUATION_FAILED
        return failWithDiagnostic(state, returned.status, reason, returned.status == AgentEvaluationDiagnosticStatus.EXPIRED)
    }

    private fun failWithDiagnostic(
        state: AgentEvaluationRunState,
        status: AgentEvaluationDiagnosticStatus,
        reason: AgentEvaluationDiagnosticReason,
        expired: Boolean,
    ): AgentEvaluationRunState {
        val atTime = clock.currentTimeMillis()
        val lease = requireNotNull(state.lease)
        val next = state.failed(lease, diagnostic(state, status, reason, atTime), atTime, expired)
        return store.fail(
            AgentEvaluationStateCommit(state.key(), state.stateVersion, lease, atTime, next),
        ).state ?: throw AgentEvaluationRuntimeException(AgentEvaluationDiagnosticReason("evaluation.missing"))
    }

    private fun handleFailure(state: AgentEvaluationRunState, failure: Throwable): AgentEvaluationRunState {
        val now = clock.currentTimeMillis()
        val lease = state.lease ?: return store.load(state.key()) ?: state
        val decision = when (failure) {
            is TimeoutException -> AgentEvaluationFailureDecision(
                AgentEvaluationFailureKind.TIMEOUT,
                AgentEvaluationDiagnosticReason("evaluation.timeout"),
            )
            is AgentEvaluationRuntimeException -> AgentEvaluationFailureDecision(
                AgentEvaluationFailureKind.PERMANENT,
                failure.reason,
            )
            else -> failureClassifier.classify(failure)
        }
        if (now >= state.deadlineAt) {
            val expired = state.failed(
                lease,
                diagnostic(
                    state,
                    AgentEvaluationDiagnosticStatus.EXPIRED,
                    AgentEvaluationDiagnosticReason("evaluation.deadline-exceeded"),
                    now,
                ),
                now,
                true,
            )
            return store.fail(
                AgentEvaluationStateCommit(state.key(), state.stateVersion, lease, now, expired),
            ).state ?: state
        }
        val retryable = decision.kind == AgentEvaluationFailureKind.RETRYABLE ||
            decision.kind == AgentEvaluationFailureKind.TIMEOUT
        if (retryable && state.attempt < state.maximumAttempts && lease.isCurrent(now)) {
            val retry = state.retry(
                lease,
                diagnostic(state, AgentEvaluationDiagnosticStatus.DEGRADED, decision.reason, now),
                now,
            )
            return store.fail(
                AgentEvaluationStateCommit(state.key(), state.stateVersion, lease, now, retry),
            ).state ?: state
        }
        val failed = state.failed(
            lease,
            diagnostic(state, AgentEvaluationDiagnosticStatus.FAILED, decision.reason, now),
            now,
            false,
        )
        return store.fail(
            AgentEvaluationStateCommit(state.key(), state.stateVersion, lease, now, failed),
        ).state ?: state
    }

    private fun observationsPass(case: ai.icen.fw.agent.api.AgentEvaluationCase, observations: List<AgentEvaluationObservation>): Boolean {
        val expected = case.expected
        val expectedRetrieval = expected.retrieval
        val expectedCitations = expected.citations
        val expectedTool = expected.tool
        val maximumCostMicros = expected.maximumCostMicros
        val maximumLatencyMillis = expected.maximumLatencyMillis
        val retrieval = observations.filterIsInstance<AgentEvaluationRetrievalObservation>().singleOrNull()
        val citations = observations.filterIsInstance<AgentEvaluationCitationObservation>().singleOrNull()
        val tool = observations.filterIsInstance<AgentEvaluationToolDecisionObservation>().singleOrNull()
        val refusal = observations.filterIsInstance<AgentEvaluationRefusalObservation>().singleOrNull()
        val cost = observations.filterIsInstance<AgentEvaluationCostObservation>().singleOrNull()
        val latency = observations.filterIsInstance<AgentEvaluationLatencyObservation>().singleOrNull()
        return (expectedRetrieval == null || retrieval?.satisfies(expectedRetrieval) == true) &&
            (expectedCitations == null || citations?.satisfies(expectedCitations) == true) &&
            (expectedTool == null || tool?.satisfies(expectedTool) == true) &&
            (expected.refusal == ai.icen.fw.agent.api.AgentEvaluationRefusalExpectation.NOT_APPLICABLE ||
                refusal?.satisfies(expected.refusal) == true) &&
            (maximumCostMicros == null || cost?.actualCostMicros?.let { it <= maximumCostMicros } == true) &&
            (maximumLatencyMillis == null || latency?.latencyMillis?.let { it <= maximumLatencyMillis } == true)
    }

    private fun diagnostic(
        state: AgentEvaluationRunState,
        status: AgentEvaluationDiagnosticStatus,
        reason: AgentEvaluationDiagnosticReason,
        atTime: Long,
    ): AgentEvaluationDiagnostic = AgentEvaluationDiagnostic(
        status,
        reason,
        state.providerSnapshot.providerId,
        null,
        state.providerSnapshot.snapshotDigest,
        atTime,
    )

    private fun renew(lease: AgentEvaluationLease, atTime: Long, deadlineAt: Long): AgentEvaluationLease {
        val candidate = if (configuration.leaseDurationMillis > Long.MAX_VALUE - atTime) {
            deadlineAt
        } else {
            minOf(deadlineAt, atTime + configuration.leaseDurationMillis)
        }
        val expiresAt = maxOf(lease.expiresAt, candidate)
        return AgentEvaluationLease(lease.leaseId, lease.ownerId, lease.fencingToken, lease.acquiredAt, expiresAt)
    }

    private fun <T> await(stage: CompletionStage<T>, deadlineAt: Long): T {
        val remaining = deadlineAt - clock.currentTimeMillis()
        if (remaining <= 0L) throw TimeoutException("Agent evaluation deadline exceeded.")
        return stage.toCompletableFuture().get(remaining, TimeUnit.MILLISECONDS)
    }

    private fun unwrap(failure: Throwable): Throwable = when (failure) {
        is CompletionException -> failure.cause ?: failure
        is ExecutionException -> failure.cause ?: failure
        else -> failure
    }

    private class StoreCancellationToken(
        private val store: AgentEvaluationDurableStore,
        private val key: AgentEvaluationRunKey,
    ) : AgentCancellationToken {
        override fun cancellation(): AgentCancellation? {
            val current = store.load(key) ?: return AgentCancellation("evaluation.missing", 0L)
            if (current.status != AgentEvaluationRunStatus.CANCELLED) return null
            return AgentCancellation(requireNotNull(current.cancellationReason), current.updatedAt)
        }
    }
}
