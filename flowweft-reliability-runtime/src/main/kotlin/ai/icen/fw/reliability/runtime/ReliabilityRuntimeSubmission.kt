package ai.icen.fw.reliability.runtime

import ai.icen.fw.reliability.api.ReliabilityBackupCreateRequest
import ai.icen.fw.reliability.api.ReliabilityBackupVerifyRequest
import ai.icen.fw.reliability.api.ReliabilityCapability
import ai.icen.fw.reliability.api.ReliabilityComponentKind
import ai.icen.fw.reliability.api.ReliabilityDrillRequest
import ai.icen.fw.reliability.api.ReliabilityOperationKind
import ai.icen.fw.reliability.api.ReliabilityRestoreRequest

enum class ReliabilitySubmissionStatus { CREATED, REPLAY, CONFLICT, STORE_OUTCOME_UNKNOWN, FAILED }

class ReliabilitySubmissionResult private constructor(
    val status: ReliabilitySubmissionStatus,
    val run: ReliabilityRun?,
    val failureCode: ReliabilityRunFailureCode?,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            status: ReliabilitySubmissionStatus,
            run: ReliabilityRun? = null,
            failureCode: ReliabilityRunFailureCode? = null,
        ): ReliabilitySubmissionResult = ReliabilitySubmissionResult(status, run, failureCode)
    }
}

class ReliabilitySubmissionService(
    private val calls: ReliabilityAuthorizedCallFactory,
    private val identifiers: ReliabilityRuntimeIdPort,
    private val topology: ReliabilityTopologySource,
    private val policies: ReliabilityRecoveryPolicySource,
    private val providers: ReliabilityProviderRegistry,
    private val repository: ReliabilityRunRepository,
    private val metrics: ReliabilityRuntimeMetrics = ReliabilityRuntimeMetrics.NOOP,
    private val faults: ReliabilityRuntimeFaultHook = ReliabilityRuntimeFaultHook.NOOP,
) {
    fun submitCreate(command: ReliabilityCreateCommand): ReliabilitySubmissionResult = guarded {
        val provisional = digestCreate(command)
        val discoveryContext = calls.create(command.invocation, "resolve-create-policy", provisional)
        val topologySnapshot = topology.load(
            ReliabilityTopologyRequest.of(
                discoveryContext, command.source, command.invocation.deadlineEpochMilli,
            ),
        )
        validateTopology(topologySnapshot, command.source, command.invocation.requestedAtEpochMilli)
        val objectiveSet = policies.load(
            ReliabilityRecoveryPolicyRequest.of(
                discoveryContext, command.source, topologySnapshot.snapshotDigest,
            ),
        )
        validatePolicy(objectiveSet, topologySnapshot, command.invocation.requestedAtEpochMilli)
        val provider = requireProvider(
            command.providerId,
            ReliabilityCapability.CREATE_CONSISTENT_BACKUP,
            topologySnapshot.components.map { it.kind },
            command.invocation.requestedAtEpochMilli,
            topologySnapshot.components.size,
        )
        val exactArgument = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-create-submit-v1")
            .text(provisional)
            .text(reliabilityTopologyBindingDigest(topologySnapshot))
            .text(objectiveSet.objectiveSetDigest)
            .text(reliabilityProviderBindingDigest(provider.descriptor))
            .finish()
        val context = calls.create(command.invocation, "submit-create", exactArgument)
        val request = ReliabilityBackupCreateRequest.of(
            context,
            objectiveSet,
            command.sourceVersionFence,
            command.invocation.requestedAtEpochMilli,
            command.executionDeadlineEpochMilli,
        )
        persist(ReliabilityOperationIntent.create(request, provider.descriptor, topologySnapshot))
    }

    fun submitVerify(command: ReliabilityVerifyCommand): ReliabilitySubmissionResult = guarded {
        val provider = requireProvider(
            command.providerId,
            ReliabilityCapability.VERIFY_IMMUTABLE_MANIFEST,
            command.manifest.content.artifacts.map { it.scope.kind },
            command.invocation.requestedAtEpochMilli,
            command.manifest.content.artifacts.size,
        )
        val argument = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-verify-submit-v1")
            .text(command.manifest.manifestDigest)
            .text(command.manifestVersionFence.fenceDigest)
            .text(reliabilityProviderBindingDigest(provider.descriptor))
            .longValue(command.executionDeadlineEpochMilli)
            .finish()
        val context = calls.create(command.invocation, "submit-verify", argument)
        val request = ReliabilityBackupVerifyRequest.of(
            context,
            command.manifest,
            command.manifestVersionFence,
            command.invocation.requestedAtEpochMilli,
        )
        persist(
            ReliabilityOperationIntent.verify(
                request,
                provider.descriptor,
                command.executionDeadlineEpochMilli,
            ),
        )
    }

    fun submitRestore(command: ReliabilityRestoreCommand): ReliabilitySubmissionResult = guarded {
        val provisional = digestRestore(command)
        val discoveryContext = calls.create(command.invocation, "resolve-restore-topology", provisional)
        val topologySnapshot = topology.load(
            ReliabilityTopologyRequest.of(
                discoveryContext, command.target, command.invocation.deadlineEpochMilli,
            ),
        )
        validateRestoreTopology(topologySnapshot, command.target, command.manifest, command.invocation.requestedAtEpochMilli)
        val provider = requireProvider(
            command.providerId,
            ReliabilityCapability.RESTORE_CLEAN_TARGET,
            topologySnapshot.components.map { it.kind },
            command.invocation.requestedAtEpochMilli,
            topologySnapshot.components.size,
        )
        val exactArgument = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-restore-submit-v1")
            .text(provisional)
            .text(reliabilityTopologyBindingDigest(topologySnapshot))
            .text(reliabilityProviderBindingDigest(provider.descriptor))
            .finish()
        val context = calls.create(command.invocation, "submit-restore", exactArgument)
        val request = ReliabilityRestoreRequest.toCleanTarget(
            context,
            command.manifest,
            command.verification,
            command.target,
            command.cleanTargetProof,
            command.targetVersionFence,
            command.recoveryReferenceEpochMilli,
            command.invocation.requestedAtEpochMilli,
            command.executionDeadlineEpochMilli,
        )
        persist(ReliabilityOperationIntent.restore(request, provider.descriptor, topologySnapshot))
    }

    fun submitDrill(command: ReliabilityDrillCommand): ReliabilitySubmissionResult = guarded {
        val provisional = digestDrill(command)
        val discoveryContext = calls.create(command.invocation, "resolve-drill-topology", provisional)
        val topologySnapshot = topology.load(
            ReliabilityTopologyRequest.of(
                discoveryContext, command.target, command.invocation.deadlineEpochMilli,
            ),
        )
        validateRestoreTopology(topologySnapshot, command.target, command.manifest, command.invocation.requestedAtEpochMilli)
        val provider = requireProvider(
            command.providerId,
            ReliabilityCapability.ISOLATED_RECOVERY_DRILL,
            topologySnapshot.components.map { it.kind },
            command.invocation.requestedAtEpochMilli,
            topologySnapshot.components.size,
        )
        val exactArgument = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-drill-submit-v1")
            .text(provisional)
            .text(reliabilityTopologyBindingDigest(topologySnapshot))
            .text(reliabilityProviderBindingDigest(provider.descriptor))
            .finish()
        val context = calls.create(command.invocation, "submit-drill", exactArgument)
        val request = ReliabilityDrillRequest.isolated(
            context,
            command.drillId,
            command.manifest,
            command.verification,
            command.target,
            command.cleanTargetProof,
            command.targetVersionFence,
            command.simulatedFailureEpochMilli,
            command.invocation.requestedAtEpochMilli,
            command.executionDeadlineEpochMilli,
        )
        persist(ReliabilityOperationIntent.drill(request, provider.descriptor, topologySnapshot))
    }

    private fun persist(intent: ReliabilityOperationIntent): ReliabilitySubmissionResult {
        val seed = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-run-id-seed-v1")
            .text(intent.tenantId)
            .text(intent.idempotencyDigest)
            .finish()
        val runId = ReliabilityRuntimeSupport.opaque(
            identifiers.nextId(ReliabilityRuntimeIdRequest.of(ReliabilityRuntimeIdKind.RUN, intent.tenantId, seed, 0)),
            "Reliability runtime id provider returned an invalid run id.",
        )
        val createdAt = intent.submittedAtEpochMilli
        val run = ReliabilityRun.ready(runId, intent, createdAt)
        val outbox = outbox(run, ReliabilityOutboxType.INTENT_READY, createdAt)
        val stored = repository.createOrLoad(run, outbox)
        val exact = stored.run
        return when (stored.code) {
            ReliabilityStoreCode.STORED -> {
                require(exact?.stateDigest == run.stateDigest) { "Reliability repository stored a different run." }
                metric(ReliabilityRuntimeMetricCode.INTENT_CREATED, intent.kind)
                faults.afterIntentStored(requireNotNull(exact))
                ReliabilitySubmissionResult.of(ReliabilitySubmissionStatus.CREATED, exact)
            }
            ReliabilityStoreCode.REPLAY -> {
                if (exact?.intent?.sameIdempotentCommandAs(intent) == true) {
                    metric(ReliabilityRuntimeMetricCode.IDEMPOTENT_REPLAY, intent.kind)
                    ReliabilitySubmissionResult.of(ReliabilitySubmissionStatus.REPLAY, exact)
                } else {
                    ReliabilitySubmissionResult.of(ReliabilitySubmissionStatus.CONFLICT, exact)
                }
            }
            ReliabilityStoreCode.OUTCOME_UNKNOWN -> {
                val reread = repository.findByIdempotency(intent.tenantId, intent.idempotencyDigest)
                if (reread?.intent?.sameIdempotentCommandAs(intent) == true) {
                    ReliabilitySubmissionResult.of(ReliabilitySubmissionStatus.REPLAY, reread)
                } else {
                    metric(ReliabilityRuntimeMetricCode.STORE_OUTCOME_UNKNOWN, intent.kind)
                    ReliabilitySubmissionResult.of(ReliabilitySubmissionStatus.STORE_OUTCOME_UNKNOWN, reread)
                }
            }
            else -> ReliabilitySubmissionResult.of(ReliabilitySubmissionStatus.CONFLICT, exact)
        }
    }

    private fun validateTopology(
        snapshot: ReliabilityTopologySnapshot,
        environment: ai.icen.fw.reliability.api.ReliabilityEnvironmentRef,
        now: Long,
    ) {
        require(snapshot.environment == environment && snapshot.isFreshAt(now)) {
            "Reliability topology source returned mismatched or stale evidence."
        }
    }

    private fun validatePolicy(
        objectives: ai.icen.fw.reliability.api.ReliabilityRecoveryObjectiveSet,
        topologySnapshot: ReliabilityTopologySnapshot,
        now: Long,
    ) {
        require(objectives.environment == topologySnapshot.environment &&
            objectives.effectiveFromEpochMilli <= now && now < objectives.expiresAtEpochMilli &&
            objectives.objectives.map { it.scope.scopeDigest }.toSet() ==
            topologySnapshot.components.map { it.scopeDigest }.toSet()
        ) { "Reliability recovery policy does not match the authoritative topology." }
    }

    private fun validateRestoreTopology(
        snapshot: ReliabilityTopologySnapshot,
        environment: ai.icen.fw.reliability.api.ReliabilityEnvironmentRef,
        manifest: ai.icen.fw.reliability.api.ReliabilityBackupManifest,
        now: Long,
    ) {
        validateTopology(snapshot, environment, now)
        require(snapshot.components.map { it.scopeDigest }.toSet() ==
            manifest.content.objectives.objectives.map { it.scope.scopeDigest }.toSet()
        ) { "Reliability target topology does not match the immutable manifest." }
    }

    private fun requireProvider(
        providerId: String,
        capability: ReliabilityCapability,
        componentKinds: Collection<ReliabilityComponentKind>,
        now: Long,
        componentCount: Int,
    ): ReliabilityRegisteredProvider {
        val provider = requireNotNull(providers.find(providerId)) { "Reliability provider is unavailable." }
        val descriptor = provider.descriptor
        require(descriptor.providerId == providerId && descriptor.isCurrent(now) && descriptor.supports(capability) &&
            componentKinds.all { descriptor.supports(it) } &&
            componentCount <= descriptor.maximumComponentsPerManifest
        ) { "Reliability provider does not satisfy the exact capability and topology." }
        return provider
    }

    private fun digestCreate(command: ReliabilityCreateCommand): String = ReliabilityRuntimeSupport.digest(
        "flowweft-reliability-runtime-create-command-v1",
    )
        .text(command.source.bindingDigest)
        .text(command.sourceVersionFence.fenceDigest)
        .text(command.providerId)
        .longValue(command.executionDeadlineEpochMilli)
        .finish()

    private fun digestRestore(command: ReliabilityRestoreCommand): String = ReliabilityRuntimeSupport.digest(
        "flowweft-reliability-runtime-restore-command-v1",
    )
        .text(command.manifest.manifestDigest)
        .text(command.verification.receiptDigest)
        .text(command.target.bindingDigest)
        .text(command.cleanTargetProof.proofDigest)
        .text(command.targetVersionFence.fenceDigest)
        .longValue(command.recoveryReferenceEpochMilli)
        .text(command.providerId)
        .longValue(command.executionDeadlineEpochMilli)
        .finish()

    private fun digestDrill(command: ReliabilityDrillCommand): String = ReliabilityRuntimeSupport.digest(
        "flowweft-reliability-runtime-drill-command-v1",
    )
        .text(command.drillId)
        .text(command.manifest.manifestDigest)
        .text(command.verification.receiptDigest)
        .text(command.target.bindingDigest)
        .text(command.cleanTargetProof.proofDigest)
        .text(command.targetVersionFence.fenceDigest)
        .longValue(command.simulatedFailureEpochMilli)
        .text(command.providerId)
        .longValue(command.executionDeadlineEpochMilli)
        .finish()

    private fun outbox(run: ReliabilityRun, type: ReliabilityOutboxType, now: Long): ReliabilityOutboxRecord {
        val seed = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-submit-outbox-seed-v1")
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

    private fun guarded(block: () -> ReliabilitySubmissionResult): ReliabilitySubmissionResult = try {
        block()
    } catch (_: RuntimeException) {
        ReliabilitySubmissionResult.of(ReliabilitySubmissionStatus.FAILED)
    }
}
