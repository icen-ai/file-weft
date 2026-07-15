package ai.icen.fw.workflow.runtime

import java.nio.charset.StandardCharsets

class WorkflowEffectWorkerItemCode private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow worker item code is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowEffectWorkerItemCode && code == other.code
    override fun hashCode(): Int = code.hashCode()

    companion object {
        @JvmField val APPLIED = WorkflowEffectWorkerItemCode("applied")
        @JvmField val RETRY_SCHEDULED = WorkflowEffectWorkerItemCode("retry-scheduled")
        @JvmField val TERMINAL_FAILURE = WorkflowEffectWorkerItemCode("terminal-failure")
        @JvmField val OUTCOME_UNKNOWN = WorkflowEffectWorkerItemCode("outcome-unknown")
        @JvmField val DEFERRED = WorkflowEffectWorkerItemCode("deferred")
        @JvmField val LEASE_LOST = WorkflowEffectWorkerItemCode("lease-lost")
        @JvmField val STORE_OUTCOME_UNKNOWN = WorkflowEffectWorkerItemCode("store-outcome-unknown")
    }
}

class WorkflowEffectWorkerItemResult private constructor(
    val claim: WorkflowClaimedEffectJob,
    val code: WorkflowEffectWorkerItemCode,
    failureCode: String?,
) {
    val failureCode: String? = failureCode?.let { value ->
        WorkflowRuntimeSupport.code(value, "Workflow worker failure code is invalid.")
    }

    override fun toString(): String = "WorkflowEffectWorkerItemResult(<redacted>)"

    companion object {
        @JvmStatic fun of(
            claim: WorkflowClaimedEffectJob,
            code: WorkflowEffectWorkerItemCode,
            failureCode: String?,
        ): WorkflowEffectWorkerItemResult = WorkflowEffectWorkerItemResult(claim, code, failureCode)
    }
}

class WorkflowEffectWorkerBatchResult private constructor(
    val claimRequest: WorkflowReadyEffectJobClaimRequest,
    items: Collection<WorkflowEffectWorkerItemResult>,
    val claimOutcomeUnknown: Boolean,
) {
    val items: List<WorkflowEffectWorkerItemResult> = WorkflowRuntimeSupport.immutable(
        items,
        WorkflowReadyEffectJobClaimRequest.MAXIMUM_CLAIM_SIZE,
        "Workflow worker batch results exceed the limit.",
    )

    override fun toString(): String = "WorkflowEffectWorkerBatchResult(<redacted>)"

    companion object {
        @JvmStatic fun of(
            claimRequest: WorkflowReadyEffectJobClaimRequest,
            items: Collection<WorkflowEffectWorkerItemResult>,
            claimOutcomeUnknown: Boolean,
        ): WorkflowEffectWorkerBatchResult = WorkflowEffectWorkerBatchResult(
            claimRequest,
            items,
            claimOutcomeUnknown,
        )
    }
}

/**
 * Bounded crash-recoverable effect worker. Queue claims/checkpoints/results/outcomes are separate
 * short transactions. [WorkflowEffectHandler.execute] is the only provider-call site and is
 * always invoked between those transactions.
 */
class WorkflowEffectWorker constructor(
    private val queuePort: WorkflowReadyEffectJobPort,
    private val persistencePort: WorkflowRuntimePersistencePort,
    private val coordinator: WorkflowEffectCoordinator,
    private val handler: WorkflowEffectHandler,
    private val clock: WorkflowWorkerClock,
) {
    fun poll(
        callContext: WorkflowTrustedCallContext,
        workerId: String,
        claimId: String,
        now: Long,
        leaseExpiresAt: Long,
        maximumJobs: Int,
    ): WorkflowEffectWorkerBatchResult {
        val claimRequest = WorkflowReadyEffectJobClaimRequest.of(
            callContext.tenantId,
            handler.effectCode(),
            workerId,
            claimId,
            now,
            leaseExpiresAt,
            maximumJobs,
        )
        var unknown = false
        val claims = try {
            queuePort.claimReady(claimRequest)
        } catch (_: RuntimeException) {
            unknown = true
            try {
                queuePort.loadClaims(claimRequest, clock.currentTimeMillis())
            } catch (_: RuntimeException) {
                emptyList()
            }
        }
        val unique = claims.distinctBy { claim -> claim.jobId }
        return WorkflowEffectWorkerBatchResult.of(
            claimRequest,
            unique.map { claim -> process(callContext, claim) },
            unknown,
        )
    }

    private fun process(
        callContext: WorkflowTrustedCallContext,
        claim: WorkflowClaimedEffectJob,
    ): WorkflowEffectWorkerItemResult = when (claim.mode) {
        WorkflowEffectJobExecutionMode.EXECUTE_PROVIDER -> execute(callContext, claim)
        WorkflowEffectJobExecutionMode.APPLY_SUCCEEDED_RESULT -> applyStored(callContext, claim)
        WorkflowEffectJobExecutionMode.SCHEDULE_RETRY -> scheduleStoredRetry(callContext, claim)
        else -> item(claim, WorkflowEffectWorkerItemCode.TERMINAL_FAILURE, "worker-mode-unsupported")
    }

    private fun execute(
        callContext: WorkflowTrustedCallContext,
        claim: WorkflowClaimedEffectJob,
    ): WorkflowEffectWorkerItemResult {
        var now = clock.currentTimeMillis()
        if (now < claim.claimedAt || now >= claim.lease.expiresAt) {
            return item(claim, WorkflowEffectWorkerItemCode.LEASE_LOST, "job-lease-expired")
        }
        val claimed = coordinator.claim(
            callContext,
            claim.effectId,
            claim.lease.workerId,
            claim.lease.leaseId,
            claim.expectedEffectVersion,
            claim.lease.fencingToken,
            now,
            claim.lease.expiresAt,
        )
        var effect = resolveEffectMutationUnknown(callContext, claim, claimed, now) { record ->
            record.status == WorkflowEffectDeliveryStatus.LEASED && record.lease?.leaseId == claim.lease.leaseId &&
                record.lease?.fencingToken == claim.lease.fencingToken
        } ?: return operationFailure(claim, claimed.code)

        // Read and validate all local execution inputs while the effect is still PREPARED. A
        // transient state/definition read failure must not create a false "provider call may have
        // happened" incident. Stale input remains safe because apply performs fresh state,
        // version, token and definition checks before any domain mutation.
        val loaded = loadContext(callContext, claim, effect, now)
            ?: return item(claim, WorkflowEffectWorkerItemCode.DEFERRED, "worker-context-unavailable")

        now = clock.currentTimeMillis()
        val checkpointDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-worker-checkpoint-v1")
            .text(claim.claimReceiptDigest)
            .text(effect.intent.requestDigest)
            .finish()
        val checkpointed = coordinator.checkpoint(
            callContext,
            claim.effectId,
            effect.version,
            claim.lease.leaseId,
            claim.lease.fencingToken,
            effect.checkpointSequence + 1L,
            WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED,
            checkpointDigest,
            now,
        )
        effect = resolveEffectMutationUnknown(callContext, claim, checkpointed, now) { record ->
            record.status == WorkflowEffectDeliveryStatus.LEASED &&
                record.phase == WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED &&
                record.checkpointDigest == checkpointDigest && record.lease?.leaseId == claim.lease.leaseId &&
                record.lease?.fencingToken == claim.lease.fencingToken
        } ?: return operationFailure(claim, checkpointed.code)

        now = clock.currentTimeMillis()
        val handlerRequest = try {
            WorkflowEffectHandlerRequest.of(
                callContext,
                claim,
                effect,
                loaded.state,
                loaded.definition,
                now,
                claim.lease.expiresAt,
            )
        } catch (_: IllegalArgumentException) {
            return item(claim, WorkflowEffectWorkerItemCode.TERMINAL_FAILURE, "worker-context-invalid")
        }

        // Deliberately outside any queue/persistence transaction.
        val handlerResult = try {
            handler.execute(handlerRequest)
        } catch (_: RuntimeException) {
            fallbackUnknown(clock.currentTimeMillis())
        }
        val storedAt = maxOf(clock.currentTimeMillis(), handlerResult.completedAt)
        val checkpoint = try {
            WorkflowEffectJobResultCheckpoint.of(claim, effect.version, handlerResult, storedAt)
        } catch (_: IllegalArgumentException) {
            // The provider call finished after lease expiry. Without durable result evidence the
            // expired started-call recovery will mark outcome unknown rather than repeat it.
            return item(claim, WorkflowEffectWorkerItemCode.OUTCOME_UNKNOWN, "provider-result-after-lease")
        }
        val stored = try {
            queuePort.storeResult(checkpoint)
        } catch (_: RuntimeException) {
            val reread = try {
                queuePort.loadClaim(claim.tenantId, claim.jobId, storedAt)
            } catch (_: RuntimeException) {
                null
            }
            if (reread != null && claim.sameLease(reread) && reread.storedResult == handlerResult) {
                WorkflowEffectJobStoreResult.replayed(handlerResult)
            } else {
                return item(claim, WorkflowEffectWorkerItemCode.STORE_OUTCOME_UNKNOWN, "result-store-outcome-unknown")
            }
        }
        if (stored.code != WorkflowEffectJobStoreCode.STORED && stored.code != WorkflowEffectJobStoreCode.REPLAYED) {
            return item(
                claim,
                if (stored.code == WorkflowEffectJobStoreCode.LEASE_MISMATCH) {
                    WorkflowEffectWorkerItemCode.LEASE_LOST
                } else {
                    WorkflowEffectWorkerItemCode.TERMINAL_FAILURE
                },
                stored.code.code,
            )
        }

        now = maxOf(clock.currentTimeMillis(), handlerResult.completedAt)
        val outcome = coordinator.recordOutcome(
            callContext,
            claim.effectId,
            effect.version,
            claim.lease.leaseId,
            claim.lease.fencingToken,
            handlerResult.outcome,
            handlerResult.resultDigest,
            now,
        )
        effect = resolveEffectMutationUnknown(callContext, claim, outcome, now) { record ->
            record.status == expectedDeliveryStatus(handlerResult.outcome) &&
                record.outcomeDigest == handlerResult.resultDigest
        } ?: return operationFailure(claim, outcome.code)

        return when (handlerResult.outcome) {
            WorkflowEffectObservedOutcome.SUCCEEDED -> applyStored(
                callContext,
                claim,
                handlerResult,
                effect,
            )
            WorkflowEffectObservedOutcome.RETRYABLE_FAILURE -> scheduleRetry(
                callContext,
                claim,
                effect,
                handlerResult,
            )
            WorkflowEffectObservedOutcome.TERMINAL_FAILURE ->
                item(claim, WorkflowEffectWorkerItemCode.TERMINAL_FAILURE, "provider-terminal-failure")
            WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN ->
                item(claim, WorkflowEffectWorkerItemCode.OUTCOME_UNKNOWN, "provider-outcome-unknown")
            else -> item(claim, WorkflowEffectWorkerItemCode.TERMINAL_FAILURE, "provider-outcome-unsupported")
        }
    }

    private fun applyStored(
        callContext: WorkflowTrustedCallContext,
        claim: WorkflowClaimedEffectJob,
    ): WorkflowEffectWorkerItemResult {
        val result = claim.storedResult
            ?: return item(claim, WorkflowEffectWorkerItemCode.TERMINAL_FAILURE, "stored-result-missing")
        val effect = try {
            persistencePort.loadEffect(claim.tenantId, claim.effectId, clock.currentTimeMillis())
        } catch (_: RuntimeException) {
            null
        } ?: return item(claim, WorkflowEffectWorkerItemCode.DEFERRED, "effect-read-unavailable")
        return applyStored(callContext, claim, result, effect)
    }

    private fun applyStored(
        callContext: WorkflowTrustedCallContext,
        claim: WorkflowClaimedEffectJob,
        result: WorkflowEffectJobStoredResult,
        effect: WorkflowEffectRecord,
    ): WorkflowEffectWorkerItemResult {
        val now = clock.currentTimeMillis()
        val loaded = loadContext(callContext, claim, effect, now)
            ?: return item(claim, WorkflowEffectWorkerItemCode.DEFERRED, "worker-context-unavailable")
        val request = try {
            WorkflowEffectApplyRequest.of(
                callContext,
                claim,
                effect,
                loaded.state,
                loaded.definition,
                result,
                now,
            )
        } catch (_: IllegalArgumentException) {
            return item(claim, WorkflowEffectWorkerItemCode.LEASE_LOST, "apply-binding-invalid")
        }
        val applied = try {
            handler.apply(request)
        } catch (_: RuntimeException) {
            return item(claim, WorkflowEffectWorkerItemCode.DEFERRED, "apply-failed")
        }
        if (applied.committed) return item(claim, WorkflowEffectWorkerItemCode.APPLIED, null)
        if (applied.code == WorkflowRuntimeResultCode.COMMIT_OUTCOME_UNKNOWN) {
            val reread = try {
                persistencePort.loadEffect(claim.tenantId, claim.effectId, clock.currentTimeMillis())
            } catch (_: RuntimeException) {
                null
            }
            if (reread?.status == WorkflowEffectDeliveryStatus.DOMAIN_APPLIED &&
                reread.outcomeDigest == result.resultDigest
            ) return item(claim, WorkflowEffectWorkerItemCode.APPLIED, null)
            return item(claim, WorkflowEffectWorkerItemCode.STORE_OUTCOME_UNKNOWN, "apply-outcome-unknown")
        }
        return item(claim, WorkflowEffectWorkerItemCode.DEFERRED, applied.failureCode ?: applied.code.code)
    }

    private fun scheduleStoredRetry(
        callContext: WorkflowTrustedCallContext,
        claim: WorkflowClaimedEffectJob,
    ): WorkflowEffectWorkerItemResult {
        val result = claim.storedResult
            ?: return item(claim, WorkflowEffectWorkerItemCode.TERMINAL_FAILURE, "stored-result-missing")
        val effect = try {
            persistencePort.loadEffect(claim.tenantId, claim.effectId, clock.currentTimeMillis())
        } catch (_: RuntimeException) {
            null
        } ?: return item(claim, WorkflowEffectWorkerItemCode.DEFERRED, "effect-read-unavailable")
        return scheduleRetry(callContext, claim, effect, result)
    }

    private fun scheduleRetry(
        callContext: WorkflowTrustedCallContext,
        claim: WorkflowClaimedEffectJob,
        effect: WorkflowEffectRecord,
        result: WorkflowEffectJobStoredResult,
    ): WorkflowEffectWorkerItemResult {
        val now = clock.currentTimeMillis()
        val retryAt = result.retryAt
            ?: return item(claim, WorkflowEffectWorkerItemCode.TERMINAL_FAILURE, "retry-time-missing")
        val retried = coordinator.retryFenced(
            callContext,
            claim.effectId,
            effect.version,
            claim.lease.leaseId,
            claim.lease.fencingToken,
            retryAt,
            result.resultDigest,
            now,
        )
        val resolved = resolveEffectMutationUnknown(callContext, claim, retried, now) { record ->
            record.status == WorkflowEffectDeliveryStatus.RETRY_WAIT && record.nextAttemptAt == retryAt
        }
        return if (resolved != null) {
            item(claim, WorkflowEffectWorkerItemCode.RETRY_SCHEDULED, null)
        } else {
            operationFailure(claim, retried.code)
        }
    }

    private fun loadContext(
        callContext: WorkflowTrustedCallContext,
        claim: WorkflowClaimedEffectJob,
        effect: WorkflowEffectRecord,
        now: Long,
    ): LoadedContext? = try {
        val snapshot = persistencePort.loadCommandSnapshot(
            callContext.tenantId,
            claim.instanceId,
            "worker-read-${claim.effectId.take(128)}",
            now,
        )
        val state = snapshot.state ?: return null
        val definition = persistencePort.loadDefinition(
            callContext.tenantId,
            effect.intent.definitionId,
            effect.intent.definitionRef,
        ) ?: return null
        LoadedContext(state, definition)
    } catch (_: RuntimeException) {
        null
    }

    private fun resolveEffectMutationUnknown(
        callContext: WorkflowTrustedCallContext,
        claim: WorkflowClaimedEffectJob,
        operation: WorkflowEffectOperationResult,
        readAt: Long,
        matches: (WorkflowEffectRecord) -> Boolean,
    ): WorkflowEffectRecord? {
        operation.record?.let { record -> return record.takeIf(matches) }
        if (operation.code != WorkflowEffectOperationCode.STORE_OUTCOME_UNKNOWN) return null
        val reread = try {
            persistencePort.loadEffect(callContext.tenantId, claim.effectId, readAt)
        } catch (_: RuntimeException) {
            null
        }
        return reread?.takeIf(matches)
    }

    private fun expectedDeliveryStatus(outcome: WorkflowEffectObservedOutcome): WorkflowEffectDeliveryStatus =
        when (outcome) {
            WorkflowEffectObservedOutcome.SUCCEEDED -> WorkflowEffectDeliveryStatus.SUCCEEDED
            WorkflowEffectObservedOutcome.RETRYABLE_FAILURE -> WorkflowEffectDeliveryStatus.RETRYABLE_FAILURE
            WorkflowEffectObservedOutcome.TERMINAL_FAILURE -> WorkflowEffectDeliveryStatus.TERMINAL_FAILURE
            WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN -> WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN
            else -> WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT
        }

    private fun fallbackUnknown(completedAt: Long): WorkflowEffectJobStoredResult {
        val payload = "workflow-handler-outcome-unknown".toByteArray(StandardCharsets.UTF_8)
        val digest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-worker-unknown-v1")
            .text("workflow-handler-outcome-unknown")
            .longValue(completedAt)
            .finish()
        return WorkflowEffectJobStoredResult.of(
            WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN,
            "workflow-handler-outcome-unknown-v1",
            digest,
            payload,
            null,
            completedAt,
        )
    }

    private fun operationFailure(
        claim: WorkflowClaimedEffectJob,
        code: WorkflowEffectOperationCode,
    ): WorkflowEffectWorkerItemResult = item(
        claim,
        when (code) {
            WorkflowEffectOperationCode.LEASE_MISMATCH,
            WorkflowEffectOperationCode.VERSION_CONFLICT -> WorkflowEffectWorkerItemCode.LEASE_LOST
            WorkflowEffectOperationCode.STORE_OUTCOME_UNKNOWN -> WorkflowEffectWorkerItemCode.STORE_OUTCOME_UNKNOWN
            WorkflowEffectOperationCode.RECONCILIATION_REQUIRED -> WorkflowEffectWorkerItemCode.OUTCOME_UNKNOWN
            else -> WorkflowEffectWorkerItemCode.DEFERRED
        },
        code.code,
    )

    private fun item(
        claim: WorkflowClaimedEffectJob,
        code: WorkflowEffectWorkerItemCode,
        failureCode: String?,
    ): WorkflowEffectWorkerItemResult = WorkflowEffectWorkerItemResult.of(claim, code, failureCode)

    private class LoadedContext(
        val state: ai.icen.fw.workflow.domain.WorkflowInstanceState,
        val definition: WorkflowRuntimeDefinitionRecord,
    )
}
