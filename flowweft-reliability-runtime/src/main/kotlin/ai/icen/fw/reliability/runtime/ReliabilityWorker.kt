package ai.icen.fw.reliability.runtime

import ai.icen.fw.reliability.api.ReliabilityBackupCreationReceipt
import ai.icen.fw.reliability.api.ReliabilityCapability
import ai.icen.fw.reliability.api.ReliabilityDrillReport
import ai.icen.fw.reliability.api.ReliabilityFailure
import ai.icen.fw.reliability.api.ReliabilityFailureClass
import ai.icen.fw.reliability.api.ReliabilityFailureCode
import ai.icen.fw.reliability.api.ReliabilityManifestVerificationReceipt
import ai.icen.fw.reliability.api.ReliabilityOperationKind
import ai.icen.fw.reliability.api.ReliabilityOutcomeUnknownReference
import ai.icen.fw.reliability.api.ReliabilityProviderResult
import ai.icen.fw.reliability.api.ReliabilityProviderResultStatus
import ai.icen.fw.reliability.api.ReliabilityPurpose
import ai.icen.fw.reliability.api.ReliabilityReconciliationReceipt
import ai.icen.fw.reliability.api.ReliabilityReconciliationRequest
import ai.icen.fw.reliability.api.ReliabilityReconciliationStatus
import ai.icen.fw.reliability.api.ReliabilityRestoreReceipt
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

enum class ReliabilityWorkerMode { ADVANCE, CANCEL }

class ReliabilityWorkerCommand private constructor(
    val invocation: ReliabilityTrustedInvocation,
    runId: String,
    ownerId: String,
    val mode: ReliabilityWorkerMode,
    val leaseMillis: Long,
) {
    val runId: String = ReliabilityRuntimeSupport.opaque(runId, "Reliability worker run id is invalid.")
    val ownerId: String = ReliabilityRuntimeSupport.opaque(ownerId, "Reliability worker owner is invalid.")

    init {
        require(leaseMillis in 1L..MAX_LEASE_MILLIS) { "Reliability worker lease duration is invalid." }
    }

    companion object {
        const val MAX_LEASE_MILLIS: Long = 10L * 60L * 1000L

        @JvmStatic
        @JvmOverloads
        fun of(
            invocation: ReliabilityTrustedInvocation,
            runId: String,
            ownerId: String,
            mode: ReliabilityWorkerMode = ReliabilityWorkerMode.ADVANCE,
            leaseMillis: Long = 60_000L,
        ): ReliabilityWorkerCommand = ReliabilityWorkerCommand(
            invocation, runId, ownerId, mode, leaseMillis,
        )
    }
}

enum class ReliabilityWorkerStatus {
    ADVANCED,
    COMPLETED,
    RECONCILIATION_REQUIRED,
    CANCELLED,
    TIMED_OUT,
    TERMINAL,
    AUTHORIZATION_DENIED,
    PROVIDER_DRIFT,
    CONFLICT,
    NOT_FOUND,
    STORE_OUTCOME_UNKNOWN,
    FAILED,
}

class ReliabilityWorkerResult private constructor(
    val status: ReliabilityWorkerStatus,
    val run: ReliabilityRun?,
    val failureCode: ReliabilityRunFailureCode?,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            status: ReliabilityWorkerStatus,
            run: ReliabilityRun? = null,
            failureCode: ReliabilityRunFailureCode? = null,
        ): ReliabilityWorkerResult = ReliabilityWorkerResult(status, run, failureCode)
    }
}

class ReliabilityWorker(
    private val calls: ReliabilityAuthorizedCallFactory,
    private val identifiers: ReliabilityRuntimeIdPort,
    private val clock: ReliabilityRuntimeClock,
    private val topology: ReliabilityTopologySource,
    private val policies: ReliabilityRecoveryPolicySource,
    private val providers: ReliabilityProviderRegistry,
    private val repository: ReliabilityRunRepository,
    private val metrics: ReliabilityRuntimeMetrics = ReliabilityRuntimeMetrics.NOOP,
    private val faults: ReliabilityRuntimeFaultHook = ReliabilityRuntimeFaultHook.NOOP,
) {
    fun runOne(command: ReliabilityWorkerCommand): CompletionStage<ReliabilityWorkerResult> {
        val loaded = try {
            repository.load(command.invocation.tenantId, command.runId)
        } catch (_: RuntimeException) {
            null
        } ?: return completed(ReliabilityWorkerResult.of(ReliabilityWorkerStatus.NOT_FOUND))
        if (loaded.tenantId != command.invocation.tenantId) {
            return completed(ReliabilityWorkerResult.of(ReliabilityWorkerStatus.NOT_FOUND))
        }
        if (!invocationMayOperate(command, loaded)) {
            return completed(
                ReliabilityWorkerResult.of(
                    ReliabilityWorkerStatus.AUTHORIZATION_DENIED,
                    null,
                    ReliabilityRunFailureCode.AUTHORIZATION_DENIED,
                ),
            )
        }
        val now = nowOr(command.invocation.requestedAtEpochMilli).coerceAtLeast(loaded.updatedAtEpochMilli)
        if (now >= command.invocation.deadlineEpochMilli) {
            return completed(
                ReliabilityWorkerResult.of(
                    ReliabilityWorkerStatus.AUTHORIZATION_DENIED,
                    null,
                    ReliabilityRunFailureCode.AUTHORIZATION_DENIED,
                ),
            )
        }
        if (loaded.isTerminal()) {
            return completed(ReliabilityWorkerResult.of(ReliabilityWorkerStatus.TERMINAL, loaded))
        }
        val leaseUntil = safeAdd(now, command.leaseMillis)
        val claimedResult = try {
            repository.claim(
                loaded.tenantId, loaded.runId, loaded.version, command.ownerId, now, leaseUntil,
            )
        } catch (_: RuntimeException) {
            ReliabilityStoreResult.of(ReliabilityStoreCode.OUTCOME_UNKNOWN, null)
        }
        val claimed = claimedResult.run
        if (claimedResult.code != ReliabilityStoreCode.STORED || claimed == null ||
            claimed.tenantId != loaded.tenantId || claimed.runId != loaded.runId ||
            claimed.version <= loaded.version || claimed.lease?.fencingToken == null ||
            !claimed.hasCurrentLease(command.ownerId, now)
        ) {
            metric(ReliabilityRuntimeMetricCode.LEASE_CONFLICT, loaded.intent.kind)
            return completed(
                ReliabilityWorkerResult.of(
                    if (claimedResult.code == ReliabilityStoreCode.OUTCOME_UNKNOWN) {
                        ReliabilityWorkerStatus.STORE_OUTCOME_UNKNOWN
                    } else {
                        ReliabilityWorkerStatus.CONFLICT
                    },
                    loaded,
                ),
            )
        }
        if (command.mode == ReliabilityWorkerMode.CANCEL) return cancel(command, claimed, now)
        return when (claimed.status) {
            ReliabilityRunStatus.READY -> dispatch(command, claimed, now)
            ReliabilityRunStatus.PROVIDER_CALL_STARTED -> recoverStarted(command, claimed, now)
            ReliabilityRunStatus.RECONCILIATION_REQUIRED -> reconcile(command, claimed, now)
            else -> completed(ReliabilityWorkerResult.of(ReliabilityWorkerStatus.TERMINAL, claimed))
        }
    }

    private fun dispatch(
        command: ReliabilityWorkerCommand,
        run: ReliabilityRun,
        now: Long,
    ): CompletionStage<ReliabilityWorkerResult> {
        val intent = run.intent
        if (now >= intent.executionDeadlineEpochMilli) {
            val candidate = ReliabilityRun.timedOut(run, now)
            metric(ReliabilityRuntimeMetricCode.TIMED_OUT, intent.kind)
            return completed(store(run, candidate, ReliabilityOutboxType.RUN_TIMED_OUT, now))
        }
        if (!matchesOriginalAuthority(command.invocation, intent)) {
            return completed(fail(run, ReliabilityRunFailureCode.AUTHORIZATION_DENIED, now))
        }
        val provider = providers.find(intent.providerId)
        if (provider == null || provider.descriptor.providerId != intent.providerId ||
            provider.descriptor.providerRevision != intent.providerRevision ||
            reliabilityProviderBindingDigest(provider.descriptor) != intent.providerDescriptorDigest ||
            !provider.descriptor.isCurrent(now)
        ) {
            metric(ReliabilityRuntimeMetricCode.PROVIDER_DRIFT, intent.kind)
            return completed(fail(run, ReliabilityRunFailureCode.PROVIDER_DRIFT, now))
        }
        if (!providerStillSupports(intent, provider)) {
            return completed(fail(run, ReliabilityRunFailureCode.CAPABILITY_UNSUPPORTED, now))
        }
        val context = try {
            calls.create(
                command.invocation,
                "dispatch-${intent.kind.name.lowercase()}",
                intent.argumentDigest,
                intent.idempotencyDigest,
            )
        } catch (_: RuntimeException) {
            metric(ReliabilityRuntimeMetricCode.AUTHORIZATION_DENIED, intent.kind)
            return completed(fail(run, ReliabilityRunFailureCode.AUTHORIZATION_DENIED, now))
        }
        if (!refreshTopologyAndPolicy(intent, context, command.invocation.deadlineEpochMilli, now)) {
            return completed(fail(run, ReliabilityRunFailureCode.TOPOLOGY_DRIFT, now))
        }
        val request = try {
            intent.rebuildRequest(context, now)
        } catch (_: RuntimeException) {
            return completed(fail(run, ReliabilityRunFailureCode.EVIDENCE_STALE, now))
        }
        val operationId = try {
            val seed = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-provider-operation-seed-v1")
                .text(run.stateDigest).text(intent.argumentDigest).finish()
            ReliabilityRuntimeSupport.opaque(
                identifiers.nextId(
                    ReliabilityRuntimeIdRequest.of(
                        ReliabilityRuntimeIdKind.PROVIDER_OPERATION, intent.tenantId, seed, 0,
                    ),
                ),
                "Reliability provider operation id is invalid.",
            )
        } catch (_: RuntimeException) {
            return completed(fail(run, ReliabilityRunFailureCode.PROVIDER_FAILURE, now))
        }
        val dispatch = try {
            ReliabilityDispatch.of(intent, request, operationId)
        } catch (_: RuntimeException) {
            return completed(fail(run, ReliabilityRunFailureCode.MALFORMED_PROVIDER_RESULT, now))
        }
        val startedCandidate = ReliabilityRun.callStarted(run, dispatch, now)
        val persisted = persist(run, startedCandidate, ReliabilityOutboxType.CALL_STARTED, now)
        val started = persisted.run
        if (persisted.status != ReliabilityWorkerStatus.ADVANCED || started == null ||
            started.stateDigest != startedCandidate.stateDigest
        ) return completed(persisted)

        // The call-started state and outbox are committed; every repository transaction has returned.
        faults.afterCallStarted(started)
        metric(ReliabilityRuntimeMetricCode.DISPATCH_STARTED, intent.kind)
        val handled = try {
            callProvider(provider.spi, dispatch).handle { result, throwable ->
                if (throwable != null || result == null) null else result
            }
        } catch (_: RuntimeException) {
            return recordUnknown(started, unknown(started, now), now)
        }
        return handled.thenCompose { result ->
            faults.afterProviderReturned(started)
            val observed = nowOr(now)
            if (result == null) recordUnknown(started, unknown(started, observed), observed)
            else recordProviderResult(started, result, observed)
        }
    }

    private fun recoverStarted(
        command: ReliabilityWorkerCommand,
        run: ReliabilityRun,
        now: Long,
    ): CompletionStage<ReliabilityWorkerResult> {
        val dispatch = requireNotNull(run.dispatch)
        try {
            calls.create(
                command.invocation,
                "recover-call-started",
                dispatch.originalAttempt.attemptDigest,
                run.intent.idempotencyDigest,
            )
        } catch (_: RuntimeException) {
            metric(ReliabilityRuntimeMetricCode.AUTHORIZATION_DENIED, run.intent.kind)
            return completed(
                ReliabilityWorkerResult.of(
                    ReliabilityWorkerStatus.AUTHORIZATION_DENIED,
                    run,
                    ReliabilityRunFailureCode.AUTHORIZATION_DENIED,
                ),
            )
        }
        // A call may already have happened. Never invoke the mutation from this state.
        return recordUnknown(run, unknown(run, now), now)
    }

    private fun reconcile(
        command: ReliabilityWorkerCommand,
        run: ReliabilityRun,
        now: Long,
    ): CompletionStage<ReliabilityWorkerResult> {
        val unknown = requireNotNull(run.outcomeUnknown)
        val intent = run.intent
        if (command.invocation.purpose != ReliabilityPurpose.RECONCILE ||
            command.invocation.action != ai.icen.fw.reliability.api.ReliabilityAction.RECONCILE_OPERATION ||
            command.invocation.tenantId != intent.tenantId || command.invocation.resource != intent.resource
        ) {
            return completed(
                ReliabilityWorkerResult.of(
                    ReliabilityWorkerStatus.AUTHORIZATION_DENIED, run, ReliabilityRunFailureCode.AUTHORIZATION_DENIED,
                ),
            )
        }
        val provider = providers.find(intent.providerId)
        if (provider == null || provider.descriptor.providerId != intent.providerId ||
            provider.descriptor.providerRevision != intent.providerRevision ||
            reliabilityProviderBindingDigest(provider.descriptor) != intent.providerDescriptorDigest ||
            !provider.descriptor.isCurrent(now) ||
            !provider.descriptor.supports(ReliabilityCapability.EXACT_OUTCOME_RECONCILIATION)
        ) {
            metric(ReliabilityRuntimeMetricCode.PROVIDER_DRIFT, intent.kind)
            return completed(
                ReliabilityWorkerResult.of(
                    ReliabilityWorkerStatus.PROVIDER_DRIFT, run, ReliabilityRunFailureCode.PROVIDER_DRIFT,
                ),
            )
        }
        val context = try {
            calls.create(
                command.invocation,
                "reconcile-exact-original",
                unknown.referenceDigest,
                command.invocation.idempotencyDigest,
            )
        } catch (_: RuntimeException) {
            return completed(
                ReliabilityWorkerResult.of(
                    ReliabilityWorkerStatus.AUTHORIZATION_DENIED, run, ReliabilityRunFailureCode.AUTHORIZATION_DENIED,
                ),
            )
        }
        val request = try {
            ReliabilityReconciliationRequest.exactOriginal(context, unknown, now)
        } catch (_: RuntimeException) {
            return completed(
                ReliabilityWorkerResult.of(
                    ReliabilityWorkerStatus.AUTHORIZATION_DENIED, run, ReliabilityRunFailureCode.AUTHORIZATION_DENIED,
                ),
            )
        }
        metric(ReliabilityRuntimeMetricCode.RECONCILIATION_STARTED, intent.kind)
        // This is the only provider call from reconciliation state; SPI contract makes it read-only.
        val handled = try {
            provider.spi.reconcile(request).handle { result, throwable ->
                if (throwable != null) null else result
            }
        } catch (_: RuntimeException) {
            return completed(ReliabilityWorkerResult.of(ReliabilityWorkerStatus.RECONCILIATION_REQUIRED, run))
        }
        return handled.thenApply { result ->
            if (result == null || result.status != ReliabilityProviderResultStatus.SUCCESS || result.value == null) {
                ReliabilityWorkerResult.of(ReliabilityWorkerStatus.RECONCILIATION_REQUIRED, run)
            } else {
                val receipt = requireNotNull(result.value)
                if (receipt.requestDigest != request.requestDigest ||
                    receipt.originalReferenceDigest != unknown.referenceDigest
                ) {
                    ReliabilityWorkerResult.of(ReliabilityWorkerStatus.RECONCILIATION_REQUIRED, run)
                } else when (receipt.status) {
                    ReliabilityReconciliationStatus.STILL_UNKNOWN ->
                        ReliabilityWorkerResult.of(ReliabilityWorkerStatus.RECONCILIATION_REQUIRED, run)
                    ReliabilityReconciliationStatus.SUCCEEDED -> {
                        val candidate = ReliabilityRun.succeeded(
                            run, ReliabilityRunOutcome.reconciliation(receipt), nowOr(now),
                        )
                        metric(ReliabilityRuntimeMetricCode.RECONCILED, intent.kind)
                        store(run, candidate, ReliabilityOutboxType.RUN_SUCCEEDED, nowOr(now))
                    }
                    ReliabilityReconciliationStatus.FAILED -> {
                        val candidate = ReliabilityRun.failed(
                            run, ReliabilityRunFailure.of(ReliabilityRunFailureCode.PROVIDER_FAILURE), nowOr(now),
                        )
                        metric(ReliabilityRuntimeMetricCode.RECONCILED, intent.kind)
                        store(run, candidate, ReliabilityOutboxType.RUN_FAILED, nowOr(now))
                    }
                }
            }
        }
    }

    private fun cancel(
        command: ReliabilityWorkerCommand,
        run: ReliabilityRun,
        now: Long,
    ): CompletionStage<ReliabilityWorkerResult> {
        val intent = run.intent
        if (!matchesOriginalAuthority(command.invocation, intent)) {
            return completed(
                ReliabilityWorkerResult.of(
                    ReliabilityWorkerStatus.AUTHORIZATION_DENIED, run, ReliabilityRunFailureCode.AUTHORIZATION_DENIED,
                ),
            )
        }
        try {
            calls.create(command.invocation, "cancel-intent", intent.argumentDigest, intent.idempotencyDigest)
        } catch (_: RuntimeException) {
            return completed(
                ReliabilityWorkerResult.of(
                    ReliabilityWorkerStatus.AUTHORIZATION_DENIED, run, ReliabilityRunFailureCode.AUTHORIZATION_DENIED,
                ),
            )
        }
        return when (run.status) {
            ReliabilityRunStatus.READY -> {
                val candidate = ReliabilityRun.cancelled(run, now)
                metric(ReliabilityRuntimeMetricCode.CANCELLED, intent.kind)
                completed(store(run, candidate, ReliabilityOutboxType.RUN_CANCELLED, now))
            }
            ReliabilityRunStatus.PROVIDER_CALL_STARTED -> {
                val candidate = ReliabilityRun.reconciliationRequired(run, unknown(run, now), true, now)
                completed(store(run, candidate, ReliabilityOutboxType.RECONCILIATION_REQUIRED, now))
            }
            ReliabilityRunStatus.RECONCILIATION_REQUIRED -> {
                if (run.cancellationRequested) {
                    completed(ReliabilityWorkerResult.of(ReliabilityWorkerStatus.RECONCILIATION_REQUIRED, run))
                } else {
                    val candidate = ReliabilityRun.cancellationRequested(run, now)
                    completed(store(run, candidate, ReliabilityOutboxType.RECONCILIATION_REQUIRED, now))
                }
            }
            else -> completed(ReliabilityWorkerResult.of(ReliabilityWorkerStatus.TERMINAL, run))
        }
    }

    private fun callProvider(
        provider: ai.icen.fw.reliability.api.ReliabilityProviderSpi,
        dispatch: ReliabilityDispatch,
    ): CompletionStage<out ReliabilityProviderResult<*>> = when (dispatch.kind) {
        ReliabilityOperationKind.CREATE_BACKUP -> provider.createBackup(requireNotNull(dispatch.createRequest))
        ReliabilityOperationKind.VERIFY_BACKUP -> provider.verifyBackup(requireNotNull(dispatch.verifyRequest))
        ReliabilityOperationKind.RESTORE -> provider.restore(requireNotNull(dispatch.restoreRequest))
        ReliabilityOperationKind.DRILL -> provider.runDrill(requireNotNull(dispatch.drillRequest))
    }

    private fun recordProviderResult(
        run: ReliabilityRun,
        result: ReliabilityProviderResult<*>,
        now: Long,
    ): CompletionStage<ReliabilityWorkerResult> {
        val dispatch = requireNotNull(run.dispatch)
        return when (result.status) {
            ReliabilityProviderResultStatus.SUCCESS -> {
                val outcome = outcome(dispatch, result.value)
                    ?: return recordUnknown(run, unknown(run, now), now)
                val candidate = ReliabilityRun.succeeded(run, outcome, now)
                metric(ReliabilityRuntimeMetricCode.OPERATION_SUCCEEDED, run.intent.kind)
                completed(store(run, candidate, ReliabilityOutboxType.RUN_SUCCEEDED, now))
            }
            ReliabilityProviderResultStatus.FAILURE -> {
                val failure = result.failure
                if (failure == null) return recordUnknown(run, unknown(run, now), now)
                val candidate = ReliabilityRun.failed(
                    run,
                    ReliabilityRunFailure.of(ReliabilityRunFailureCode.PROVIDER_FAILURE, failure),
                    now,
                )
                metric(ReliabilityRuntimeMetricCode.OPERATION_FAILED, run.intent.kind)
                completed(store(run, candidate, ReliabilityOutboxType.RUN_FAILED, now))
            }
            ReliabilityProviderResultStatus.OUTCOME_UNKNOWN -> {
                val reference = result.outcomeUnknown
                if (reference == null || reference.originalAttempt.attemptDigest !=
                    dispatch.originalAttempt.attemptDigest
                ) {
                    recordUnknown(run, unknown(run, now), now)
                } else {
                    recordUnknown(run, reference, now)
                }
            }
        }
    }

    private fun outcome(dispatch: ReliabilityDispatch, value: Any?): ReliabilityRunOutcome? = when (dispatch.kind) {
        ReliabilityOperationKind.CREATE_BACKUP -> (value as? ReliabilityBackupCreationReceipt)
            ?.takeIf { it.requestDigest == dispatch.requestDigest }?.let { ReliabilityRunOutcome.backup(it) }
        ReliabilityOperationKind.VERIFY_BACKUP -> (value as? ReliabilityManifestVerificationReceipt)
            ?.takeIf { it.requestDigest == dispatch.requestDigest }?.let { ReliabilityRunOutcome.verification(it) }
        ReliabilityOperationKind.RESTORE -> (value as? ReliabilityRestoreReceipt)
            ?.takeIf { it.requestDigest == dispatch.requestDigest }?.let { ReliabilityRunOutcome.restore(it) }
        ReliabilityOperationKind.DRILL -> (value as? ReliabilityDrillReport)
            ?.takeIf { it.requestDigest == dispatch.requestDigest }?.let { ReliabilityRunOutcome.drill(it) }
    }

    private fun recordUnknown(
        run: ReliabilityRun,
        unknown: ReliabilityOutcomeUnknownReference,
        now: Long,
    ): CompletionStage<ReliabilityWorkerResult> {
        val candidate = ReliabilityRun.reconciliationRequired(
            run, unknown, run.cancellationRequested, now,
        )
        metric(ReliabilityRuntimeMetricCode.OUTCOME_UNKNOWN, run.intent.kind)
        return completed(store(run, candidate, ReliabilityOutboxType.RECONCILIATION_REQUIRED, now))
    }

    private fun unknown(run: ReliabilityRun, now: Long): ReliabilityOutcomeUnknownReference {
        val dispatch = requireNotNull(run.dispatch)
        val evidence = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-unknown-evidence-v1")
            .text(dispatch.dispatchDigest)
            .text(dispatch.originalAttempt.attemptDigest)
            .finish()
        return ReliabilityOutcomeUnknownReference.of(dispatch.originalAttempt, evidence, now)
    }

    private fun refreshTopologyAndPolicy(
        intent: ReliabilityOperationIntent,
        context: ai.icen.fw.reliability.api.ReliabilityCallContext,
        requiredUntilEpochMilli: Long,
        now: Long,
    ): Boolean {
        if (intent.topologySnapshotDigest == null) return true
        return try {
            val snapshot = topology.load(
                ReliabilityTopologyRequest.of(context, intent.environment, requiredUntilEpochMilli),
            )
            if (reliabilityTopologyBindingDigest(snapshot) != intent.topologySnapshotDigest ||
                snapshot.environment != intent.environment || !snapshot.isFreshAt(now)
            ) return false
            val expectedScopes = if (intent.kind == ReliabilityOperationKind.CREATE_BACKUP) {
                val objectiveSet = policies.load(
                    ReliabilityRecoveryPolicyRequest.of(context, intent.environment, snapshot.snapshotDigest),
                )
                if (objectiveSet.objectiveSetDigest != intent.objectives?.objectiveSetDigest) return false
                objectiveSet.objectives.map { it.scope.scopeDigest }.toSet()
            } else {
                requireNotNull(intent.manifest).content.objectives.objectives.map { it.scope.scopeDigest }.toSet()
            }
            snapshot.components.map { it.scopeDigest }.toSet() == expectedScopes
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun providerStillSupports(
        intent: ReliabilityOperationIntent,
        provider: ReliabilityRegisteredProvider,
    ): Boolean {
        val descriptor = provider.descriptor
        val capability = when (intent.kind) {
            ReliabilityOperationKind.CREATE_BACKUP -> ReliabilityCapability.CREATE_CONSISTENT_BACKUP
            ReliabilityOperationKind.VERIFY_BACKUP -> ReliabilityCapability.VERIFY_IMMUTABLE_MANIFEST
            ReliabilityOperationKind.RESTORE -> ReliabilityCapability.RESTORE_CLEAN_TARGET
            ReliabilityOperationKind.DRILL -> ReliabilityCapability.ISOLATED_RECOVERY_DRILL
        }
        val scopes = intent.objectives?.objectives?.map { it.scope }
            ?: requireNotNull(intent.manifest).content.artifacts.map { it.scope }
        return descriptor.supports(capability) && scopes.all { descriptor.supports(it.kind) } &&
            scopes.size <= descriptor.maximumComponentsPerManifest
    }

    private fun matchesOriginalAuthority(
        invocation: ReliabilityTrustedInvocation,
        intent: ReliabilityOperationIntent,
    ): Boolean = invocation.tenantId == intent.tenantId && invocation.principal == intent.principal &&
        invocation.purpose == intent.purpose && invocation.action == intent.action &&
        invocation.resource == intent.resource

    private fun invocationMayOperate(
        command: ReliabilityWorkerCommand,
        run: ReliabilityRun,
    ): Boolean {
        if (command.mode == ReliabilityWorkerMode.CANCEL) {
            return matchesOriginalAuthority(command.invocation, run.intent)
        }
        if (run.status != ReliabilityRunStatus.RECONCILIATION_REQUIRED) {
            return matchesOriginalAuthority(command.invocation, run.intent)
        }
        return command.invocation.purpose == ReliabilityPurpose.RECONCILE &&
            command.invocation.action ==
            ai.icen.fw.reliability.api.ReliabilityAction.RECONCILE_OPERATION &&
            command.invocation.tenantId == run.intent.tenantId &&
            command.invocation.resource == run.intent.resource
    }

    private fun fail(
        current: ReliabilityRun,
        code: ReliabilityRunFailureCode,
        now: Long,
    ): ReliabilityWorkerResult {
        if (code == ReliabilityRunFailureCode.AUTHORIZATION_DENIED) {
            metric(ReliabilityRuntimeMetricCode.AUTHORIZATION_DENIED, current.intent.kind)
        }
        val candidate = ReliabilityRun.failed(current, ReliabilityRunFailure.of(code), now)
        return store(current, candidate, ReliabilityOutboxType.RUN_FAILED, now)
    }

    private fun persist(
        current: ReliabilityRun,
        candidate: ReliabilityRun,
        type: ReliabilityOutboxType,
        now: Long,
    ): ReliabilityWorkerResult {
        val lease = requireNotNull(current.lease)
        val outbox = outbox(candidate, type, now)
        val stored = try {
            repository.compareAndSet(
                candidate.tenantId,
                candidate.runId,
                current.version,
                lease.fencingToken,
                candidate,
                outbox,
            )
        } catch (_: RuntimeException) {
            ReliabilityStoreResult.of(ReliabilityStoreCode.OUTCOME_UNKNOWN, null)
        }
        stored.run?.takeIf { it.stateDigest == candidate.stateDigest }?.let {
            return resultFor(it)
        }
        if (stored.code == ReliabilityStoreCode.OUTCOME_UNKNOWN) {
            val reread = try {
                repository.load(candidate.tenantId, candidate.runId)
            } catch (_: RuntimeException) {
                null
            }
            if (reread?.stateDigest == candidate.stateDigest) return resultFor(reread)
            metric(ReliabilityRuntimeMetricCode.STORE_OUTCOME_UNKNOWN, candidate.intent.kind)
            return ReliabilityWorkerResult.of(ReliabilityWorkerStatus.STORE_OUTCOME_UNKNOWN, current)
        }
        return ReliabilityWorkerResult.of(ReliabilityWorkerStatus.CONFLICT, current)
    }

    private fun store(
        current: ReliabilityRun,
        candidate: ReliabilityRun,
        type: ReliabilityOutboxType,
        now: Long,
    ): ReliabilityWorkerResult = persist(current, candidate, type, now)

    private fun resultFor(run: ReliabilityRun): ReliabilityWorkerResult = when (run.status) {
        ReliabilityRunStatus.SUCCEEDED -> ReliabilityWorkerResult.of(ReliabilityWorkerStatus.COMPLETED, run)
        ReliabilityRunStatus.FAILED -> ReliabilityWorkerResult.of(
            ReliabilityWorkerStatus.FAILED, run, run.failure?.code,
        )
        ReliabilityRunStatus.CANCELLED -> ReliabilityWorkerResult.of(ReliabilityWorkerStatus.CANCELLED, run)
        ReliabilityRunStatus.TIMED_OUT -> ReliabilityWorkerResult.of(ReliabilityWorkerStatus.TIMED_OUT, run)
        ReliabilityRunStatus.RECONCILIATION_REQUIRED -> ReliabilityWorkerResult.of(
            ReliabilityWorkerStatus.RECONCILIATION_REQUIRED, run,
        )
        else -> ReliabilityWorkerResult.of(ReliabilityWorkerStatus.ADVANCED, run)
    }

    private fun outbox(run: ReliabilityRun, type: ReliabilityOutboxType, now: Long): ReliabilityOutboxRecord {
        val seed = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-worker-outbox-seed-v1")
            .text(run.stateDigest).text(type.name).finish()
        val id = ReliabilityRuntimeSupport.opaque(
            identifiers.nextId(
                ReliabilityRuntimeIdRequest.of(
                    ReliabilityRuntimeIdKind.OUTBOX,
                    run.tenantId,
                    seed,
                    (run.version % Int.MAX_VALUE.toLong()).toInt(),
                ),
            ),
            "Reliability runtime id provider returned an invalid outbox id.",
        )
        return ReliabilityOutboxRecord.forRun(id, type, run, now)
    }

    private fun metric(code: ReliabilityRuntimeMetricCode, kind: ReliabilityOperationKind?) {
        try {
            metrics.record(ReliabilityRuntimeMetric.of(code, kind))
        } catch (_: RuntimeException) {
            // Metrics never affect reliability state.
        }
    }

    private fun nowOr(fallback: Long): Long = try {
        clock.nowEpochMilli().coerceAtLeast(fallback)
    } catch (_: RuntimeException) {
        fallback
    }

    private fun safeAdd(value: Long, delta: Long): Long =
        if (Long.MAX_VALUE - value < delta) Long.MAX_VALUE else value + delta
}

private fun <T> completed(value: T): CompletionStage<T> = CompletableFuture.completedFuture(value)
