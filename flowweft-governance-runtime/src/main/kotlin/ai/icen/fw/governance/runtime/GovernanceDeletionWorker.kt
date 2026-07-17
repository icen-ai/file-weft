package ai.icen.fw.governance.runtime

import ai.icen.fw.governance.api.GovernanceDeletionExecutionRequest
import ai.icen.fw.governance.api.GovernanceDeletionReconciliationRequest
import ai.icen.fw.governance.api.GovernanceDeletionStepReceipt
import ai.icen.fw.governance.api.GovernanceDeletionStepStatus
import ai.icen.fw.governance.api.GovernanceFailure
import ai.icen.fw.governance.api.GovernanceFailureClass
import ai.icen.fw.governance.api.GovernancePurpose
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.math.max
import kotlin.math.min

class GovernanceWorkerCommand private constructor(
    val invocation: GovernanceTrustedInvocation,
    planId: String,
) {
    val planId: String = GovernanceRuntimeSupport.opaque(planId, "Governance worker plan id is invalid.")
    val commandDigest: String

    init {
        require(invocation.purpose == GovernancePurpose.EXECUTE_SECURE_DELETION ||
            invocation.purpose == GovernancePurpose.RECONCILE_SECURE_DELETION
        ) { "Governance worker command requires execute or reconcile purpose." }
        commandDigest = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-worker-command-v1")
            .text(invocation.invocationDigest)
            .text(this.planId)
            .finish()
    }

    override fun toString(): String = "GovernanceWorkerCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(invocation: GovernanceTrustedInvocation, planId: String): GovernanceWorkerCommand =
            GovernanceWorkerCommand(invocation, planId)
    }
}

class GovernanceWorkerStatus private constructor(code: String) {
    val code: String = GovernanceRuntimeSupport.code(code, "Governance worker status is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is GovernanceWorkerStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceWorkerStatus(<redacted>)"

    companion object {
        @JvmField val ADVANCED = GovernanceWorkerStatus("advanced")
        @JvmField val NO_WORK = GovernanceWorkerStatus("no-work")
        @JvmField val RETRY_WAIT = GovernanceWorkerStatus("retry-wait")
        @JvmField val RECONCILIATION_REQUIRED = GovernanceWorkerStatus("reconciliation-required")
        @JvmField val BLOCKED = GovernanceWorkerStatus("blocked")
        @JvmField val COMPLETED = GovernanceWorkerStatus("completed")
        @JvmField val FAILED = GovernanceWorkerStatus("failed")
        @JvmField val CONFLICT = GovernanceWorkerStatus("conflict")
        @JvmField val STORE_OUTCOME_UNKNOWN = GovernanceWorkerStatus("store-outcome-unknown")
    }
}

class GovernanceWorkerResult private constructor(
    val status: GovernanceWorkerStatus,
    val run: GovernanceDeletionRun?,
    failureCode: String?,
) {
    val failureCode: String? = failureCode?.let { code ->
        GovernanceRuntimeSupport.code(code, "Governance worker failure code is invalid.")
    }

    init {
        require((status == GovernanceWorkerStatus.FAILED || status == GovernanceWorkerStatus.BLOCKED) ==
            (this.failureCode != null)
        ) { "Governance worker result failure code is inconsistent." }
    }

    override fun toString(): String = "GovernanceWorkerResult(<redacted>)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            status: GovernanceWorkerStatus,
            run: GovernanceDeletionRun? = null,
            failureCode: String? = null,
        ): GovernanceWorkerResult = GovernanceWorkerResult(status, run, failureCode)
    }
}

/**
 * Provider-neutral one-step worker. Repository methods finish before an executor/reconciler call.
 * A persisted PROVIDER_CALL_STARTED checkpoint is never executed again; it becomes reconciliation.
 */
class ProviderNeutralGovernanceDeletionWorker(
    private val evidence: GovernanceDeletionEvidenceResolver,
    private val calls: GovernanceAuthorizedCallFactory,
    private val clock: GovernanceRuntimeClockPort,
    private val identifiers: GovernanceRuntimeIdPort,
    private val repository: GovernanceDeletionRepository,
    private val providers: GovernanceDeletionProviderRegistry,
    private val metrics: GovernanceMetricsPort,
    private val retryDelayMillis: Long = 30_000L,
) {
    init {
        require(retryDelayMillis > 0L) { "Governance runtime retry delay is invalid." }
    }

    fun process(command: GovernanceWorkerCommand): CompletionStage<GovernanceWorkerResult> {
        val run = try {
            repository.load(command.invocation.tenantId, command.planId)
        } catch (_: RuntimeException) {
            return completed(failed(null, "governance-repository-unavailable"))
        } ?: return completed(GovernanceWorkerResult.of(GovernanceWorkerStatus.NO_WORK))
        if (run.planId != command.planId || run.tenantId != command.invocation.tenantId ||
            run.plan.resource != command.invocation.resource) {
            return completed(failed(null, "governance-run-binding-mismatch"))
        }
        val now = try {
            clock.nowEpochMilli().also { value ->
                require(value in command.invocation.requestedAtEpochMilli..command.invocation.deadlineEpochMilli)
            }
        } catch (_: RuntimeException) {
            return completed(failed(run, "governance-clock-unavailable"))
        }
        return when (run.status) {
            GovernanceDeletionRunStatus.COMPLETED -> completed(
                GovernanceWorkerResult.of(GovernanceWorkerStatus.COMPLETED, run),
            )
            GovernanceDeletionRunStatus.FAILED -> completed(failed(run, requireNotNull(run.failure).reasonCode))
            GovernanceDeletionRunStatus.DISPATCH_STARTED -> recoverStarted(command, run, now)
            GovernanceDeletionRunStatus.DISPATCH_PREPARED -> recoverPrepared(command, run, now)
            GovernanceDeletionRunStatus.RECONCILIATION_REQUIRED -> reconcile(command, run, now)
            GovernanceDeletionRunStatus.BLOCKED,
            GovernanceDeletionRunStatus.READY,
            GovernanceDeletionRunStatus.RETRY_WAIT -> executeOrBlock(command, run, now)
            else -> completed(failed(run, "governance-run-status-unsupported"))
        }
    }

    private fun executeOrBlock(
        command: GovernanceWorkerCommand,
        run: GovernanceDeletionRun,
        now: Long,
    ): CompletionStage<GovernanceWorkerResult> {
        if (command.invocation.purpose != GovernancePurpose.EXECUTE_SECURE_DELETION) {
            return completed(failed(run, "execute-purpose-required"))
        }
        if (run.status == GovernanceDeletionRunStatus.RETRY_WAIT &&
            now < requireNotNull(run.nextActionAtEpochMilli)) {
            return completed(GovernanceWorkerResult.of(GovernanceWorkerStatus.RETRY_WAIT, run))
        }
        return evidence.resolve(command.invocation, run.plan.fence).thenCompose { resolved ->
            if (resolved.status != GovernanceEvidenceStatus.AVAILABLE) {
                val failure = requireNotNull(resolved.failure)
                if (failure.classification == GovernanceFailureClass.DENIED ||
                    failure.classification == GovernanceFailureClass.PERMANENT_FAILURE) {
                    val candidate = GovernanceDeletionRun.failed(run, failure, now)
                    return@thenCompose completed(storeResult(run, candidate, GovernanceOutboxType.RUN_FAILED, now))
                }
                if (run.status == GovernanceDeletionRunStatus.BLOCKED &&
                    run.failure?.failureDigest == failure.failureDigest) {
                    return@thenCompose completed(blocked(run, failure.reasonCode))
                }
                val candidate = GovernanceDeletionRun.blocked(run, failure, now)
                metric(GovernanceMetricCode.LEGAL_HOLD_BLOCKED, run.nextStep()?.stage)
                return@thenCompose completed(storeResult(run, candidate, GovernanceOutboxType.RUN_BLOCKED, now))
            }
            if (run.status == GovernanceDeletionRunStatus.BLOCKED) {
                val candidate = GovernanceDeletionRun.resume(run, now)
                return@thenCompose completed(storeResult(run, candidate, outboxType(candidate), now))
            }
            prepare(command, run, requireNotNull(resolved.assessment), now)
        }.handle { result, throwable ->
            if (throwable == null) result else failed(run, "governance-execution-evidence-failed")
        }
    }

    private fun prepare(
        command: GovernanceWorkerCommand,
        run: GovernanceDeletionRun,
        assessment: ai.icen.fw.governance.api.GovernanceRetentionAssessment,
        now: Long,
    ): CompletionStage<GovernanceWorkerResult> {
        val step = run.nextStep() ?: return completed(
            GovernanceWorkerResult.of(GovernanceWorkerStatus.COMPLETED, run),
        )
        val descriptor = providers.find(step.stage) ?: run {
            val failure = permanent("deletion-provider-unsupported")
            val candidate = GovernanceDeletionRun.failed(run, failure, now)
            return completed(storeResult(run, candidate, GovernanceOutboxType.RUN_FAILED, now))
        }
        val attempt = if (run.status == GovernanceDeletionRunStatus.RETRY_WAIT) {
            requireNotNull(run.pendingReceipt).attempt + 1
        } else {
            1
        }
        val context = try {
            calls.create(
                command.invocation,
                GovernancePurpose.EXECUTE_SECURE_DELETION,
                "execute-step-${step.sequence}-attempt-$attempt",
            )
        } catch (_: RuntimeException) {
            return completed(failed(run, "execution-authorization-denied"))
        }
        val request = GovernanceDeletionExecutionRequest.of(
            context,
            run.plan,
            step,
            assessment,
            attempt,
            if (attempt == 1) null else run.pendingReceipt,
            run.successfulReceipts,
            run.version,
        )
        val dispatch = GovernanceDeletionDispatch.prepared(
            request,
            descriptor.providerId,
            descriptor.providerRevision,
            context.idempotencyKey,
            now,
        )
        val candidate = GovernanceDeletionRun.prepare(run, dispatch, now)
        val persisted = persist(run, candidate, GovernanceOutboxType.STATE_CHECKPOINTED, now)
        val prepared = persisted.run ?: return completed(storeFailure(run, persisted.code))
        return startFreshlyPrepared(prepared, descriptor, now)
    }

    /**
     * A PREPARED state loaded by a later worker is never dispatched with its old authorization.
     * Reset it durably, then rebuild evidence and the exact execution request from this invocation.
     */
    private fun recoverPrepared(
        command: GovernanceWorkerCommand,
        run: GovernanceDeletionRun,
        now: Long,
    ): CompletionStage<GovernanceWorkerResult> {
        if (command.invocation.purpose != GovernancePurpose.EXECUTE_SECURE_DELETION) {
            return completed(failed(run, "execute-purpose-required"))
        }
        val dispatch = requireNotNull(run.dispatch)
        if (command.invocation.principal != dispatch.request.context.principal) {
            return completed(failed(run, "governance-run-principal-mismatch"))
        }
        val candidate = GovernanceDeletionRun.resetPreparedDispatch(run, now)
        val persisted = persist(run, candidate, outboxType(candidate), now)
        val reset = persisted.run ?: return completed(storeFailure(run, persisted.code))
        return executeOrBlock(command, reset, now)
    }

    /** Called only with the execution request created and authorized in this same worker call. */
    private fun startFreshlyPrepared(
        run: GovernanceDeletionRun,
        descriptor: GovernanceDeletionProviderDescriptor,
        now: Long,
    ): CompletionStage<GovernanceWorkerResult> {
        val dispatch = requireNotNull(run.dispatch)
        require(now <= dispatch.request.context.deadlineEpochMilli &&
            descriptor.providerId == dispatch.providerId && descriptor.providerRevision == dispatch.providerRevision
        ) { "Fresh governance deletion dispatch does not match its authorization or provider revision." }
        val startedCandidate = GovernanceDeletionRun.markProviderCallStarted(run, now)
        val persisted = persist(run, startedCandidate, GovernanceOutboxType.STATE_CHECKPOINTED, now)
        val started = persisted.run ?: return completed(storeFailure(run, persisted.code))
        metric(GovernanceMetricCode.STEP_DISPATCHED, started.dispatch?.request?.step?.stage)

        // Every repository transaction has returned before this external provider call.
        val providerStage = try {
            descriptor.executor.execute(requireNotNull(started.dispatch).request)
        } catch (_: RuntimeException) {
            return recordExecution(started, unknownReceipt(started, now), now)
        }
        return providerStage.handle { receipt, throwable ->
            if (throwable != null || !matchesDispatch(receipt, requireNotNull(started.dispatch))) {
                unknownReceipt(started, clockNowOr(now))
            } else {
                receipt
            }
        }.thenCompose { receipt -> recordExecution(started, receipt, clockNowOr(now)) }
    }

    private fun recoverStarted(
        command: GovernanceWorkerCommand,
        run: GovernanceDeletionRun,
        now: Long,
    ): CompletionStage<GovernanceWorkerResult> {
        if (command.invocation.purpose != GovernancePurpose.EXECUTE_SECURE_DELETION) {
            return completed(failed(run, "execute-purpose-required"))
        }
        try {
            calls.create(command.invocation, GovernancePurpose.EXECUTE_SECURE_DELETION, "recover-started-dispatch")
        } catch (_: RuntimeException) {
            return completed(failed(run, "execution-authorization-denied"))
        }
        // The call may already have happened. Re-execution is forbidden even if no result was stored.
        return recordExecution(run, unknownReceipt(run, now), now)
    }

    private fun recordExecution(
        current: GovernanceDeletionRun,
        receipt: GovernanceDeletionStepReceipt,
        now: Long,
    ): CompletionStage<GovernanceWorkerResult> {
        val retryAt = if (receipt.status == GovernanceDeletionStepStatus.RETRYABLE_FAILURE) {
            now + retryDelayMillis
        } else {
            null
        }
        val candidate = try {
            GovernanceDeletionRun.recordExecution(current, receipt, now, retryAt)
        } catch (_: RuntimeException) {
            GovernanceDeletionRun.recordExecution(current, unknownReceipt(current, now), now)
        }
        val type = outboxType(candidate)
        val result = storeResult(current, candidate, type, now)
        when (candidate.status) {
            GovernanceDeletionRunStatus.READY,
            GovernanceDeletionRunStatus.COMPLETED -> metric(
                GovernanceMetricCode.STEP_SUCCEEDED, current.dispatch?.request?.step?.stage,
            )
            GovernanceDeletionRunStatus.RETRY_WAIT -> metric(
                GovernanceMetricCode.RETRY_SCHEDULED, current.dispatch?.request?.step?.stage,
            )
            GovernanceDeletionRunStatus.RECONCILIATION_REQUIRED -> metric(
                GovernanceMetricCode.OUTCOME_UNKNOWN, current.dispatch?.request?.step?.stage,
            )
            GovernanceDeletionRunStatus.FAILED -> metric(
                GovernanceMetricCode.STEP_FAILED, current.dispatch?.request?.step?.stage,
            )
        }
        return completed(result)
    }

    private fun reconcile(
        command: GovernanceWorkerCommand,
        run: GovernanceDeletionRun,
        now: Long,
    ): CompletionStage<GovernanceWorkerResult> {
        if (command.invocation.purpose != GovernancePurpose.RECONCILE_SECURE_DELETION) {
            return completed(failed(run, "reconcile-purpose-required"))
        }
        return evidence.resolve(command.invocation, run.plan.fence, allowBlockedAssessment = true).thenCompose { resolved ->
            if (resolved.status != GovernanceEvidenceStatus.AVAILABLE) {
                return@thenCompose completed(blocked(run, requireNotNull(resolved.failure).reasonCode))
            }
            val previous = requireNotNull(run.pendingReceipt)
            val step = requireNotNull(run.nextStep())
            val descriptor = providers.find(step.stage)
            if (descriptor == null || descriptor.providerId != previous.providerId ||
                descriptor.providerRevision != previous.providerRevision) {
                return@thenCompose completed(blocked(run, "reconciliation-provider-unavailable"))
            }
            val context = try {
                calls.create(
                    command.invocation,
                    GovernancePurpose.RECONCILE_SECURE_DELETION,
                    "reconcile-step-${step.sequence}-attempt-${previous.attempt}",
                )
            } catch (_: RuntimeException) {
                return@thenCompose completed(failed(run, "reconciliation-authorization-denied"))
            }
            val request = GovernanceDeletionReconciliationRequest.of(
                context,
                run.plan,
                step,
                previous,
                requireNotNull(resolved.assessment),
                run.version,
            )
            // Reconciliation is read-only and uses the exact original receipt/operation reference.
            val stage = try {
                descriptor.reconciler.reconcile(request)
            } catch (_: RuntimeException) {
                return@thenCompose completed(
                    GovernanceWorkerResult.of(GovernanceWorkerStatus.RECONCILIATION_REQUIRED, run),
                )
            }
            stage.handle { receipt, throwable ->
                if (throwable != null || !matchesReconciliation(receipt, request, previous)) null else receipt
            }.thenApply { receipt ->
                if (receipt == null) {
                    GovernanceWorkerResult.of(GovernanceWorkerStatus.RECONCILIATION_REQUIRED, run)
                } else {
                    val candidate = GovernanceDeletionRun.recordReconciliation(
                        run, receipt, request.requestDigest, clockNowOr(now),
                    )
                    metric(GovernanceMetricCode.RECONCILED, step.stage)
                    storeResult(run, candidate, outboxType(candidate), clockNowOr(now))
                }
            }
        }.handle { result, throwable ->
            if (throwable == null) result
            else GovernanceWorkerResult.of(GovernanceWorkerStatus.RECONCILIATION_REQUIRED, run)
        }
    }

    private fun unknownReceipt(run: GovernanceDeletionRun, now: Long): GovernanceDeletionStepReceipt {
        val dispatch = requireNotNull(run.dispatch)
        val observedAt = min(
            dispatch.request.context.deadlineEpochMilli,
            max(dispatch.request.context.requestedAtEpochMilli, now),
        )
        val failure = GovernanceFailure.of(
            GovernanceFailureClass.OUTCOME_UNKNOWN,
            "provider-call-outcome-unknown",
            false,
            true,
        )
        val digest = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-unknown-result-v1")
            .text(dispatch.dispatchDigest)
            .text(dispatch.operationReference)
            .finish()
        return GovernanceDeletionStepReceipt.failure(
            dispatch.request,
            dispatch.providerId,
            dispatch.providerRevision,
            GovernanceDeletionStepStatus.OUTCOME_UNKNOWN,
            dispatch.operationReference,
            digest,
            failure,
            observedAt,
        )
    }

    private fun matchesDispatch(
        receipt: GovernanceDeletionStepReceipt?,
        dispatch: GovernanceDeletionDispatch,
    ): Boolean = receipt != null && receipt.planDigest == dispatch.request.plan.planDigest &&
        receipt.stepDigest == dispatch.request.step.stepDigest &&
        receipt.executionRequestDigest == dispatch.request.requestDigest &&
        receipt.attempt == dispatch.request.attempt && receipt.providerId == dispatch.providerId &&
        receipt.providerRevision == dispatch.providerRevision &&
        (receipt.status != GovernanceDeletionStepStatus.OUTCOME_UNKNOWN ||
            receipt.receiptReference == dispatch.operationReference)

    private fun matchesReconciliation(
        receipt: GovernanceDeletionStepReceipt?,
        request: GovernanceDeletionReconciliationRequest,
        previous: GovernanceDeletionStepReceipt,
    ): Boolean = receipt != null && receipt.planDigest == request.plan.planDigest &&
        receipt.stepDigest == request.step.stepDigest &&
        receipt.executionRequestDigest == previous.executionRequestDigest &&
        receipt.attempt == previous.attempt && receipt.providerId == previous.providerId &&
        receipt.providerRevision == previous.providerRevision &&
        receipt.reconciliationRequestDigest == request.requestDigest &&
        receipt.status != GovernanceDeletionStepStatus.RETRYABLE_FAILURE

    private fun persist(
        current: GovernanceDeletionRun,
        candidate: GovernanceDeletionRun,
        type: GovernanceOutboxType,
        now: Long,
    ): PersistResult {
        val outbox = outbox(candidate, type, now)
        val stored = try {
            repository.compareAndSet(
                candidate.tenantId, candidate.planId, current.version, candidate, outbox,
            )
        } catch (_: RuntimeException) {
            GovernanceStoreResult.failed(GovernanceStoreCode.OUTCOME_UNKNOWN)
        }
        stored.run?.takeIf { it.stateDigest == candidate.stateDigest }?.let {
            return PersistResult(it, stored.code)
        }
        if (stored.code == GovernanceStoreCode.OUTCOME_UNKNOWN) {
            val reread = try {
                repository.load(candidate.tenantId, candidate.planId)
            } catch (_: RuntimeException) {
                null
            }
            if (reread?.stateDigest == candidate.stateDigest) return PersistResult(reread, GovernanceStoreCode.STORED)
            metric(GovernanceMetricCode.STORE_OUTCOME_UNKNOWN, candidate.nextStep()?.stage)
        } else {
            metric(GovernanceMetricCode.CAS_CONFLICT, candidate.nextStep()?.stage)
        }
        return PersistResult(null, stored.code)
    }

    private fun storeResult(
        current: GovernanceDeletionRun,
        candidate: GovernanceDeletionRun,
        type: GovernanceOutboxType,
        now: Long,
    ): GovernanceWorkerResult {
        val persisted = persist(current, candidate, type, now)
        val exact = persisted.run ?: return storeFailure(current, persisted.code)
        return resultFor(exact)
    }

    private fun storeFailure(run: GovernanceDeletionRun, code: GovernanceStoreCode): GovernanceWorkerResult =
        if (code == GovernanceStoreCode.OUTCOME_UNKNOWN) {
            GovernanceWorkerResult.of(GovernanceWorkerStatus.STORE_OUTCOME_UNKNOWN, run)
        } else {
            GovernanceWorkerResult.of(GovernanceWorkerStatus.CONFLICT, run)
        }

    private fun resultFor(run: GovernanceDeletionRun): GovernanceWorkerResult = when (run.status) {
        GovernanceDeletionRunStatus.READY,
        GovernanceDeletionRunStatus.DISPATCH_PREPARED,
        GovernanceDeletionRunStatus.DISPATCH_STARTED -> GovernanceWorkerResult.of(GovernanceWorkerStatus.ADVANCED, run)
        GovernanceDeletionRunStatus.RETRY_WAIT -> GovernanceWorkerResult.of(GovernanceWorkerStatus.RETRY_WAIT, run)
        GovernanceDeletionRunStatus.RECONCILIATION_REQUIRED -> GovernanceWorkerResult.of(
            GovernanceWorkerStatus.RECONCILIATION_REQUIRED, run,
        )
        GovernanceDeletionRunStatus.BLOCKED -> blocked(run, requireNotNull(run.failure).reasonCode)
        GovernanceDeletionRunStatus.COMPLETED -> GovernanceWorkerResult.of(GovernanceWorkerStatus.COMPLETED, run)
        GovernanceDeletionRunStatus.FAILED -> failed(run, requireNotNull(run.failure).reasonCode)
        else -> failed(run, "governance-run-status-unsupported")
    }

    private fun outboxType(run: GovernanceDeletionRun): GovernanceOutboxType = when (run.status) {
        GovernanceDeletionRunStatus.READY -> GovernanceOutboxType.STEP_READY
        GovernanceDeletionRunStatus.RETRY_WAIT -> GovernanceOutboxType.RETRY_READY
        GovernanceDeletionRunStatus.RECONCILIATION_REQUIRED -> GovernanceOutboxType.RECONCILIATION_REQUIRED
        GovernanceDeletionRunStatus.BLOCKED -> GovernanceOutboxType.RUN_BLOCKED
        GovernanceDeletionRunStatus.COMPLETED -> GovernanceOutboxType.RUN_COMPLETED
        GovernanceDeletionRunStatus.FAILED -> GovernanceOutboxType.RUN_FAILED
        else -> GovernanceOutboxType.STATE_CHECKPOINTED
    }

    private fun outbox(run: GovernanceDeletionRun, type: GovernanceOutboxType, now: Long): GovernanceOutboxRecord {
        val seed = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-worker-outbox-seed-v1")
            .text(run.stateDigest).text(type.code).finish()
        val id = GovernanceRuntimeSupport.opaque(
            identifiers.nextId(
                GovernanceRuntimeIdRequest.of(
                    GovernanceRuntimeIdKind.OUTBOX,
                    run.tenantId,
                    seed,
                    (run.version % Int.MAX_VALUE.toLong()).toInt(),
                ),
            ),
            "Governance runtime outbox id provider returned an invalid identifier.",
        )
        return GovernanceOutboxRecord.of(id, type, run, max(now, run.updatedAtEpochMilli))
    }

    private fun metric(code: GovernanceMetricCode, stage: ai.icen.fw.governance.api.GovernanceDeletionStage?) {
        try {
            metrics.record(GovernanceMetric.of(code, stage))
        } catch (_: RuntimeException) {
            // Metrics never affect governance state.
        }
    }

    private fun clockNowOr(fallback: Long): Long = try {
        max(fallback, clock.nowEpochMilli())
    } catch (_: RuntimeException) {
        fallback
    }

    private fun blocked(run: GovernanceDeletionRun?, code: String): GovernanceWorkerResult =
        GovernanceWorkerResult.of(GovernanceWorkerStatus.BLOCKED, run, code)

    private fun failed(run: GovernanceDeletionRun?, code: String): GovernanceWorkerResult =
        GovernanceWorkerResult.of(GovernanceWorkerStatus.FAILED, run, code)

    private fun permanent(code: String): GovernanceFailure = GovernanceFailure.of(
        GovernanceFailureClass.UNSUPPORTED, code, false, false,
    )

    private class PersistResult(val run: GovernanceDeletionRun?, val code: GovernanceStoreCode)
}

private fun <T> completed(value: T): CompletionStage<T> = CompletableFuture.completedFuture(value)
