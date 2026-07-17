package ai.icen.fw.reliability.runtime

import ai.icen.fw.reliability.api.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong

internal const val TENANT = "tenant-a"
internal fun digest(character: Char): String = character.toString().repeat(64)

internal class MutableClock(var now: Long) : ReliabilityRuntimeClock {
    override fun nowEpochMilli(): Long = now
}

internal class SequenceIds : ReliabilityRuntimeIdPort {
    private val sequence = AtomicLong()
    override fun nextId(request: ReliabilityRuntimeIdRequest): String =
        "${request.kind.name.lowercase()}-${sequence.incrementAndGet()}"
}

internal class FakeAuthorization : ReliabilityRuntimeAuthorizationPort {
    var revoked = false
    var calls = 0

    override fun authorize(request: ReliabilityRuntimeAuthorizationRequest): ReliabilityAuthorizationSnapshot {
        calls++
        check(!revoked) { "revoked" }
        val invocation = request.invocation
        return ReliabilityAuthorizationSnapshot.of(
            "auth-$calls",
            invocation.tenantId,
            invocation.principal,
            invocation.purpose,
            invocation.action,
            invocation.resource,
            "host-authz",
            "1",
            calls.toString(),
            digest('a'),
            invocation.requestedAtEpochMilli - 10L,
            invocation.deadlineEpochMilli + 10L,
        )
    }
}

internal class FakeRunRepository : ReliabilityRunRepository {
    private val runs = LinkedHashMap<String, ReliabilityRun>()
    private val idempotency = LinkedHashMap<String, String>()
    val outboxes = ArrayList<ReliabilityOutboxRecord>()
    var inTransaction = false
    var conflictNextCompare = false
    var unknownNextCompare = false
    var unknownNextCreate = false
    private var fence = 0L

    override fun createOrLoad(run: ReliabilityRun, outbox: ReliabilityOutboxRecord): ReliabilityStoreResult = tx {
        val key = "${run.tenantId}:${run.intent.idempotencyDigest}"
        val existingId = idempotency[key]
        if (existingId != null) return@tx ReliabilityStoreResult.of(
            ReliabilityStoreCode.REPLAY, runs[existingId],
        )
        runs[run.runId] = run
        idempotency[key] = run.runId
        outboxes.add(outbox)
        val outcomeUnknown = unknownNextCreate
        unknownNextCreate = false
        ReliabilityStoreResult.of(
            if (outcomeUnknown) {
                ReliabilityStoreCode.OUTCOME_UNKNOWN
            } else {
                ReliabilityStoreCode.STORED
            },
            if (outcomeUnknown) null else run,
        )
    }

    override fun load(tenantId: String, runId: String): ReliabilityRun? = tx {
        runs[runId]?.takeIf { it.tenantId == tenantId }
    }

    override fun findByIdempotency(tenantId: String, idempotencyDigest: String): ReliabilityRun? = tx {
        idempotency["$tenantId:$idempotencyDigest"]?.let { runs[it] }
    }

    override fun claim(
        tenantId: String,
        runId: String,
        expectedVersion: Long,
        ownerId: String,
        nowEpochMilli: Long,
        leaseUntilEpochMilli: Long,
    ): ReliabilityStoreResult = tx {
        val current = runs[runId]
        if (current == null || current.tenantId != tenantId || current.version != expectedVersion) {
            ReliabilityStoreResult.of(ReliabilityStoreCode.CONFLICT, current)
        } else {
            val claimed = ReliabilityRun.claimed(
                current, ownerId, nowEpochMilli, leaseUntilEpochMilli, ++fence,
            )
            runs[runId] = claimed
            ReliabilityStoreResult.of(ReliabilityStoreCode.STORED, claimed)
        }
    }

    override fun compareAndSet(
        tenantId: String,
        runId: String,
        expectedVersion: Long,
        expectedFencingToken: Long,
        candidate: ReliabilityRun,
        outbox: ReliabilityOutboxRecord,
    ): ReliabilityStoreResult = tx {
        val current = runs[runId]
        if (conflictNextCompare.also { conflictNextCompare = false }) {
            return@tx ReliabilityStoreResult.of(ReliabilityStoreCode.CONFLICT, current)
        }
        if (current == null || current.tenantId != tenantId || current.version != expectedVersion ||
            current.lease?.fencingToken != expectedFencingToken || candidate.version != expectedVersion + 1L ||
            candidate.tenantId != tenantId || outbox.aggregateStateDigest != candidate.stateDigest
        ) return@tx ReliabilityStoreResult.of(ReliabilityStoreCode.CONFLICT, current)
        runs[runId] = candidate
        outboxes.add(outbox)
        if (unknownNextCompare.also { unknownNextCompare = false }) {
            ReliabilityStoreResult.of(ReliabilityStoreCode.OUTCOME_UNKNOWN, null)
        } else {
            ReliabilityStoreResult.of(ReliabilityStoreCode.STORED, candidate)
        }
    }

    fun latest(runId: String): ReliabilityRun = requireNotNull(runs[runId])

    private fun <T> tx(block: () -> T): T {
        check(!inTransaction)
        inTransaction = true
        return try {
            block()
        } finally {
            inTransaction = false
        }
    }
}

internal class FakeProvider(
    private val repository: FakeRunRepository,
) : ReliabilityProviderSpi {
    lateinit var descriptorValue: ReliabilityProviderDescriptor
    var mutationCount = 0
    var verificationCount = 0
    var reconciliationCount = 0
    var completionOffsetMillis = 1_000L
    var reconcileStatus = ReliabilityReconciliationStatus.SUCCEEDED

    override fun capabilities(request: ReliabilityCapabilityRequest): CompletionStage<ReliabilityCapabilityResult> =
        CompletableFuture.completedFuture(
            ReliabilityCapabilityResult.available(request, descriptorValue, request.context.requestedAtEpochMilli),
        )

    override fun createBackup(
        request: ReliabilityBackupCreateRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityBackupCreationReceipt>> {
        check(!repository.inTransaction)
        mutationCount++
        val cutAt = request.startedAtEpochMilli
        val cut = ReliabilityConsistentCut.of(
            "cut-$mutationCount", request.objectives.environment, request.objectives.topologyDigest, cutAt,
        )
        val key = ReliabilityKeyReference.of("kms", "key-1", "1", digest('b'))
        val encryption = ReliabilityEncryptionDescriptor.of(
            "aes-256-gcm", key, digest('c'), digest('d'),
        )
        val artifacts = request.objectives.objectives.mapIndexed { index, objective ->
            ReliabilityBackupArtifact.immutableEncrypted(
                objective.scope,
                "snapshot-${mutationCount}-$index",
                descriptorValue.providerId,
                descriptorValue.providerRevision,
                cut.cutDigest,
                cutAt - minOf(500L, objective.maximumDataLossMillis),
                cutAt + 10L,
                1_024L,
                digest('e'),
                encryption,
            )
        }
        val content = ReliabilityBackupManifestContent.of(
            "manifest-$mutationCount",
            "1",
            request.objectives,
            cut,
            artifacts,
            cutAt + 20L,
        )
        val seal = ReliabilityImmutableManifestSeal.of(
            content, "1", "ed25519", key, digest('f'), cutAt + 30L,
        )
        val manifest = ReliabilityBackupManifest.of(content, seal)
        val completedAt = request.startedAtEpochMilli + completionOffsetMillis
        val receipt = ReliabilityBackupCreationReceipt.of(
            request,
            manifest,
            descriptorValue.providerId,
            descriptorValue.providerRevision,
            digest('1'),
            completedAt,
        )
        return CompletableFuture.completedFuture(ReliabilityProviderResult.success(receipt))
    }

    override fun verifyBackup(
        request: ReliabilityBackupVerifyRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityManifestVerificationReceipt>> {
        check(!repository.inTransaction)
        verificationCount++
        val receipt = ReliabilityManifestVerificationReceipt.of(
            request,
            descriptorValue.providerId,
            descriptorValue.providerRevision,
            ReliabilityManifestVerificationStatus.VALID,
            true,
            true,
            true,
            true,
            true,
            digest('2'),
            request.startedAtEpochMilli,
            request.startedAtEpochMilli + 60_000L,
        )
        return CompletableFuture.completedFuture(ReliabilityProviderResult.success(receipt))
    }

    override fun restore(
        request: ReliabilityRestoreRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityRestoreReceipt>> {
        check(!repository.inTransaction)
        mutationCount++
        val receipt = ReliabilityRestoreReceipt.of(
            request,
            descriptorValue.providerId,
            descriptorValue.providerRevision,
            digest('3'),
            request.startedAtEpochMilli + completionOffsetMillis,
        )
        return CompletableFuture.completedFuture(ReliabilityProviderResult.success(receipt))
    }

    override fun reconcile(
        request: ReliabilityReconciliationRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityReconciliationReceipt>> {
        check(!repository.inTransaction)
        reconciliationCount++
        val receipt = ReliabilityReconciliationReceipt.of(
            request, reconcileStatus, digest('4'), request.startedAtEpochMilli,
        )
        return CompletableFuture.completedFuture(ReliabilityProviderResult.success(receipt))
    }

    override fun runDrill(
        request: ReliabilityDrillRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityDrillReport>> {
        check(!repository.inTransaction)
        mutationCount++
        val report = ReliabilityDrillReport.of(
            request,
            descriptorValue.providerId,
            descriptorValue.providerRevision,
            digest('5'),
            request.startedAtEpochMilli + completionOffsetMillis,
        )
        return CompletableFuture.completedFuture(ReliabilityProviderResult.success(report))
    }

    override fun doctor(
        request: ReliabilityDoctorRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityDoctorReport>> {
        check(!repository.inTransaction)
        val finding = ReliabilityDoctorFinding.of(
            ReliabilityDoctorFindingCode.CONFIGURATION_READY,
            ReliabilityDoctorSeverity.INFO,
            ReliabilityMetricComponentClass.OTHER,
        )
        val report = ReliabilityDoctorReport.of(
            request,
            descriptorValue.providerId,
            descriptorValue.providerRevision,
            ReliabilityDoctorStatus.READY,
            listOf(finding),
            request.context.requestedAtEpochMilli,
            request.context.deadlineEpochMilli + 1_000L,
        )
        return CompletableFuture.completedFuture(ReliabilityProviderResult.success(report))
    }
}

internal class MutableRegistry(var provider: ReliabilityRegisteredProvider?) : ReliabilityProviderRegistry {
    override fun find(providerId: String): ReliabilityRegisteredProvider? =
        provider?.takeIf { it.descriptor.providerId == providerId }
}

internal class CrashFaults : ReliabilityRuntimeFaultHook {
    var afterIntent = false
    var afterStarted = false
    var afterReturned = false

    override fun afterIntentStored(run: ReliabilityRun) {
        if (afterIntent.also { afterIntent = false }) error("simulated crash after intent")
    }

    override fun afterCallStarted(run: ReliabilityRun) {
        if (afterStarted.also { afterStarted = false }) error("simulated crash after call-started")
    }

    override fun afterProviderReturned(run: ReliabilityRun) {
        if (afterReturned.also { afterReturned = false }) error("simulated crash after provider return")
    }
}

internal data class RuntimeFixture(
    val clock: MutableClock,
    val ids: SequenceIds,
    val authorization: FakeAuthorization,
    val repository: FakeRunRepository,
    val provider: FakeProvider,
    val registry: MutableRegistry,
    val topology: ReliabilityTopologySnapshot,
    val objectives: ReliabilityRecoveryObjectiveSet,
    val source: ReliabilityEnvironmentRef,
    val target: ReliabilityEnvironmentRef,
    val manifest: ReliabilityBackupManifest,
    val verification: ReliabilityManifestVerificationReceipt,
    val proof: ReliabilityCleanTargetProof,
    val calls: ReliabilityAuthorizedCallFactory,
    val submission: ReliabilitySubmissionService,
    val faults: CrashFaults,
) {
    fun worker(): ReliabilityWorker = ReliabilityWorker(
        calls,
        ids,
        clock,
        ReliabilityTopologySource { request ->
            if (request.environment == source) topology else ReliabilityTopologySnapshot.of(
                target,
                topology.components,
                topology.sourceRevision,
                topology.sourceDigest,
                topology.observedAtEpochMilli,
                topology.expiresAtEpochMilli,
            )
        },
        ReliabilityRecoveryPolicySource { objectives },
        registry,
        repository,
        ReliabilityRuntimeMetrics.NOOP,
        faults,
    )
}

internal fun runtimeFixture(): RuntimeFixture {
    val clock = MutableClock(100_000L)
    val ids = SequenceIds()
    val authorization = FakeAuthorization()
    val repository = FakeRunRepository()
    val provider = FakeProvider(repository)
    val principal = ReliabilityPrincipalRef.of("user", "operator")
    val sourceResource = ReliabilityResourceRef.of("environment", "prod", "1", digest('6'))
    val source = ReliabilityEnvironmentRef.of(
        TENANT, "prod", ReliabilityEnvironmentKind.PRODUCTION, sourceResource, digest('7'),
    )
    val targetResource = ReliabilityResourceRef.of("environment", "recovery", "1", digest('8'))
    val target = ReliabilityEnvironmentRef.of(
        TENANT, "recovery", ReliabilityEnvironmentKind.RECOVERY, targetResource, digest('7'),
    )
    val scope = ReliabilityComponentScope.of(
        ReliabilityComponentKind.DATABASE, "workflow-db", "1", digest('9'),
    )
    val objectives = ReliabilityRecoveryObjectiveSet.of(
        "recovery", "1", digest('a'), source,
        listOf(ReliabilityRecoveryObjective.of(scope, 2_000L, 1_000_000L)),
        0L, 2_000_000L,
    )
    val topology = ReliabilityTopologySnapshot.of(
        source, listOf(scope), "1", digest('b'), 0L, 2_000_000L,
    )
    val descriptor = ReliabilityProviderDescriptor.of(
        "provider",
        "1",
        "1",
        digest('c'),
        ReliabilityCapability.values().toList(),
        listOf(ReliabilityComponentKind.DATABASE),
        8,
        0L,
        2_000_000L,
    )
    provider.descriptorValue = descriptor
    val registry = MutableRegistry(ReliabilityRegisteredProvider.of(descriptor, provider))
    val key = ReliabilityKeyReference.of("kms", "key-1", "1", digest('d'))
    val encryption = ReliabilityEncryptionDescriptor.of("aes-256-gcm", key, digest('e'), digest('f'))
    val cut = ReliabilityConsistentCut.of("cut-old", source, objectives.topologyDigest, 80_000L)
    val artifact = ReliabilityBackupArtifact.immutableEncrypted(
        scope, "snapshot-old", "provider", "1", cut.cutDigest,
        79_500L, 80_010L, 1_024L, digest('1'), encryption,
    )
    val content = ReliabilityBackupManifestContent.of(
        "manifest-old", "1", objectives, cut, listOf(artifact), 80_020L,
    )
    val seal = ReliabilityImmutableManifestSeal.of(content, "1", "ed25519", key, digest('2'), 80_030L)
    val manifest = ReliabilityBackupManifest.of(content, seal)
    val verifyContext = directContext(
        principal,
        ReliabilityPurpose.VERIFY_BACKUP,
        ReliabilityAction.VERIFY_BACKUP,
        manifest.resource,
        90_000L,
        91_000L,
    )
    val verifyRequest = ReliabilityBackupVerifyRequest.of(
        verifyContext,
        manifest,
        ReliabilityVersionFence.of(manifest.resource, 1L, digest('3')),
        90_000L,
    )
    val verification = ReliabilityManifestVerificationReceipt.of(
        verifyRequest, "provider", "1", ReliabilityManifestVerificationStatus.VALID,
        true, true, true, true, true, digest('4'), 90_000L, 500_000L,
    )
    val proof = ReliabilityCleanTargetProof.clean(
        "proof-1", target, "verifier", "1", digest('5'), 90_000L, 500_000L,
    )
    val calls = ReliabilityAuthorizedCallFactory(authorization, ids)
    val faults = CrashFaults()
    val submission = ReliabilitySubmissionService(
        calls,
        ids,
        ReliabilityTopologySource { request ->
            if (request.environment == source) topology else ReliabilityTopologySnapshot.of(
                target, listOf(scope), "1", digest('b'), 0L, 2_000_000L,
            )
        },
        ReliabilityRecoveryPolicySource { objectives },
        registry,
        repository,
        ReliabilityRuntimeMetrics.NOOP,
        faults,
    )
    return RuntimeFixture(
        clock,
        ids,
        authorization,
        repository,
        provider,
        registry,
        topology,
        objectives,
        source,
        target,
        manifest,
        verification,
        proof,
        calls,
        submission,
        faults,
    )
}

internal fun invocation(
    purpose: ReliabilityPurpose,
    action: ReliabilityAction,
    resource: ReliabilityResourceRef,
    tenant: String = TENANT,
    principal: ReliabilityPrincipalRef = ReliabilityPrincipalRef.of("user", "operator"),
    rawIdempotencyKey: String = "idempotency-1",
    requestedAt: Long,
): ReliabilityTrustedInvocation = ReliabilityTrustedInvocation.of(
    tenant,
    principal,
    purpose,
    action,
    resource,
    rawIdempotencyKey,
    requestedAt,
    requestedAt + 1_000L,
)

private fun directContext(
    principal: ReliabilityPrincipalRef,
    purpose: ReliabilityPurpose,
    action: ReliabilityAction,
    resource: ReliabilityResourceRef,
    requestedAt: Long,
    deadline: Long,
): ReliabilityCallContext {
    val auth = ReliabilityAuthorizationSnapshot.of(
        "fixture-auth-$requestedAt", TENANT, principal, purpose, action, resource,
        "host-authz", "1", "1", digest('6'), requestedAt - 10L, deadline + 10L,
    )
    return ReliabilityCallContext.of(
        "fixture-request-$requestedAt", TENANT, principal, purpose, action, resource,
        auth, digest('7'), requestedAt, deadline,
    )
}
