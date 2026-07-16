package ai.icen.fw.testkit.reliability

import ai.icen.fw.reliability.api.ReliabilityBackupArtifact
import ai.icen.fw.reliability.api.ReliabilityBackupCreateRequest
import ai.icen.fw.reliability.api.ReliabilityBackupCreationReceipt
import ai.icen.fw.reliability.api.ReliabilityBackupManifest
import ai.icen.fw.reliability.api.ReliabilityBackupManifestContent
import ai.icen.fw.reliability.api.ReliabilityBackupVerifyRequest
import ai.icen.fw.reliability.api.ReliabilityCapabilityRequest
import ai.icen.fw.reliability.api.ReliabilityCapabilityResult
import ai.icen.fw.reliability.api.ReliabilityConsistentCut
import ai.icen.fw.reliability.api.ReliabilityDoctorFinding
import ai.icen.fw.reliability.api.ReliabilityDoctorFindingCode
import ai.icen.fw.reliability.api.ReliabilityDoctorReport
import ai.icen.fw.reliability.api.ReliabilityDoctorRequest
import ai.icen.fw.reliability.api.ReliabilityDoctorSeverity
import ai.icen.fw.reliability.api.ReliabilityDoctorStatus
import ai.icen.fw.reliability.api.ReliabilityDrillReport
import ai.icen.fw.reliability.api.ReliabilityDrillRequest
import ai.icen.fw.reliability.api.ReliabilityEncryptionDescriptor
import ai.icen.fw.reliability.api.ReliabilityImmutableManifestSeal
import ai.icen.fw.reliability.api.ReliabilityKeyReference
import ai.icen.fw.reliability.api.ReliabilityManifestVerificationReceipt
import ai.icen.fw.reliability.api.ReliabilityManifestVerificationStatus
import ai.icen.fw.reliability.api.ReliabilityMetricComponentClass
import ai.icen.fw.reliability.api.ReliabilityProviderDescriptor
import ai.icen.fw.reliability.api.ReliabilityProviderResult
import ai.icen.fw.reliability.api.ReliabilityProviderSpi
import ai.icen.fw.reliability.api.ReliabilityReconciliationReceipt
import ai.icen.fw.reliability.api.ReliabilityReconciliationRequest
import ai.icen.fw.reliability.api.ReliabilityReconciliationStatus
import ai.icen.fw.reliability.api.ReliabilityRestoreReceipt
import ai.icen.fw.reliability.api.ReliabilityRestoreRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

/**
 * Counts all mutating provider dispatches and captures the exact original reference passed to the
 * read-only reconciliation call. No request values are rendered or logged.
 */
class ReliabilityProviderProbe private constructor(
    private val delegate: ReliabilityProviderSpi,
    private val transactionActive: BooleanSupplier,
) : ReliabilityProviderSpi {
    private val mutations = AtomicInteger()
    private val reconciliations = AtomicInteger()
    private val verifications = AtomicInteger()
    @Volatile private var mutationRequestDigest: String? = null
    @Volatile private var reconciliationOriginalRequestDigest: String? = null
    @Volatile private var reconciliationOriginalAttemptDigest: String? = null

    override fun capabilities(request: ReliabilityCapabilityRequest): CompletionStage<ReliabilityCapabilityResult> {
        requireOutsideTransaction()
        return delegate.capabilities(request)
    }

    override fun createBackup(
        request: ReliabilityBackupCreateRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityBackupCreationReceipt>> {
        requireOutsideTransaction()
        mutationRequestDigest = request.requestDigest
        mutations.incrementAndGet()
        return delegate.createBackup(request)
    }

    override fun verifyBackup(
        request: ReliabilityBackupVerifyRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityManifestVerificationReceipt>> {
        requireOutsideTransaction()
        verifications.incrementAndGet()
        return delegate.verifyBackup(request)
    }

    override fun restore(
        request: ReliabilityRestoreRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityRestoreReceipt>> {
        requireOutsideTransaction()
        mutationRequestDigest = request.requestDigest
        mutations.incrementAndGet()
        return delegate.restore(request)
    }

    override fun reconcile(
        request: ReliabilityReconciliationRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityReconciliationReceipt>> {
        requireOutsideTransaction()
        reconciliations.incrementAndGet()
        reconciliationOriginalRequestDigest = request.outcomeUnknown.originalAttempt.requestDigest
        reconciliationOriginalAttemptDigest = request.outcomeUnknown.originalAttempt.attemptDigest
        return delegate.reconcile(request)
    }

    override fun runDrill(
        request: ReliabilityDrillRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityDrillReport>> {
        requireOutsideTransaction()
        mutationRequestDigest = request.requestDigest
        mutations.incrementAndGet()
        return delegate.runDrill(request)
    }

    override fun doctor(
        request: ReliabilityDoctorRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityDoctorReport>> {
        requireOutsideTransaction()
        return delegate.doctor(request)
    }

    fun mutationCount(): Int = mutations.get()
    fun reconciliationCount(): Int = reconciliations.get()
    fun verificationCount(): Int = verifications.get()
    fun lastMutationRequestDigest(): String? = mutationRequestDigest
    fun lastReconciliationOriginalRequestDigest(): String? = reconciliationOriginalRequestDigest
    fun lastReconciliationOriginalAttemptDigest(): String? = reconciliationOriginalAttemptDigest

    private fun requireOutsideTransaction() {
        check(!transactionActive.asBoolean) {
            "Reliability provider was invoked inside a repository transaction."
        }
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun wrapping(
            delegate: ReliabilityProviderSpi,
            transactionActive: BooleanSupplier = BooleanSupplier { false },
        ): ReliabilityProviderProbe = ReliabilityProviderProbe(delegate, transactionActive)
    }
}

/** Synthetic successful provider used to prove the contract suite itself. */
class DeterministicReliabilityProvider private constructor(
    private val descriptor: ReliabilityProviderDescriptor,
) : ReliabilityProviderSpi {
    override fun capabilities(request: ReliabilityCapabilityRequest): CompletionStage<ReliabilityCapabilityResult> =
        completed(ReliabilityCapabilityResult.available(request, descriptor, request.context.requestedAtEpochMilli))

    override fun createBackup(
        request: ReliabilityBackupCreateRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityBackupCreationReceipt>> {
        val cutAt = request.startedAtEpochMilli
        val cut = ReliabilityConsistentCut.of(
            "contract-cut-${request.context.requestId}",
            request.objectives.environment,
            request.objectives.topologyDigest,
            cutAt,
        )
        val encryption = encryption()
        val artifacts = request.objectives.objectives.mapIndexed { index, objective ->
            ReliabilityBackupArtifact.immutableEncrypted(
                objective.scope,
                "contract-snapshot-${request.context.requestId}-$index",
                descriptor.providerId,
                descriptor.providerRevision,
                cut.cutDigest,
                cutAt - minOf(500L, objective.maximumDataLossMillis),
                cutAt + 10L,
                1_024L + index,
                ReliabilityContractAssertions.sha256("artifact:${request.requestDigest}:$index"),
                encryption,
            )
        }
        val content = ReliabilityBackupManifestContent.of(
            "contract-manifest-${request.context.requestId}",
            "1",
            request.objectives,
            cut,
            artifacts,
            cutAt + 20L,
        )
        val seal = ReliabilityImmutableManifestSeal.of(
            content,
            "1",
            "ed25519",
            encryption.keyReference,
            ReliabilityContractAssertions.sha256("seal:${content.contentDigest}"),
            cutAt + 30L,
        )
        val manifest = ReliabilityBackupManifest.of(content, seal)
        val receipt = ReliabilityBackupCreationReceipt.of(
            request,
            manifest,
            descriptor.providerId,
            descriptor.providerRevision,
            ReliabilityContractAssertions.sha256("create-receipt:${request.requestDigest}"),
            cutAt + 1_000L,
        )
        return completed(ReliabilityProviderResult.success(receipt))
    }

    override fun verifyBackup(
        request: ReliabilityBackupVerifyRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityManifestVerificationReceipt>> {
        val receipt = ReliabilityManifestVerificationReceipt.of(
            request,
            descriptor.providerId,
            descriptor.providerRevision,
            ReliabilityManifestVerificationStatus.VALID,
            true,
            true,
            true,
            true,
            true,
            ReliabilityContractAssertions.sha256("verification:${request.requestDigest}"),
            request.startedAtEpochMilli,
            request.startedAtEpochMilli + 60_000L,
        )
        return completed(ReliabilityProviderResult.success(receipt))
    }

    override fun restore(
        request: ReliabilityRestoreRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityRestoreReceipt>> {
        val receipt = ReliabilityRestoreReceipt.of(
            request,
            descriptor.providerId,
            descriptor.providerRevision,
            ReliabilityContractAssertions.sha256("restore:${request.requestDigest}"),
            request.startedAtEpochMilli + 1_000L,
        )
        return completed(ReliabilityProviderResult.success(receipt))
    }

    override fun reconcile(
        request: ReliabilityReconciliationRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityReconciliationReceipt>> {
        val receipt = ReliabilityReconciliationReceipt.of(
            request,
            ReliabilityReconciliationStatus.SUCCEEDED,
            ReliabilityContractAssertions.sha256(
                "reconcile:${request.outcomeUnknown.originalAttempt.attemptDigest}",
            ),
            request.startedAtEpochMilli,
        )
        return completed(ReliabilityProviderResult.success(receipt))
    }

    override fun runDrill(
        request: ReliabilityDrillRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityDrillReport>> {
        val report = ReliabilityDrillReport.of(
            request,
            descriptor.providerId,
            descriptor.providerRevision,
            ReliabilityContractAssertions.sha256("drill:${request.requestDigest}"),
            request.startedAtEpochMilli + 1_000L,
        )
        return completed(ReliabilityProviderResult.success(report))
    }

    override fun doctor(
        request: ReliabilityDoctorRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityDoctorReport>> {
        val finding = ReliabilityDoctorFinding.of(
            ReliabilityDoctorFindingCode.CONFIGURATION_READY,
            ReliabilityDoctorSeverity.INFO,
            ReliabilityMetricComponentClass.OTHER,
        )
        val report = ReliabilityDoctorReport.of(
            request,
            descriptor.providerId,
            descriptor.providerRevision,
            ReliabilityDoctorStatus.READY,
            listOf(finding),
            request.context.requestedAtEpochMilli,
            request.context.deadlineEpochMilli + 1_000L,
        )
        return completed(ReliabilityProviderResult.success(report))
    }

    private fun encryption(): ReliabilityEncryptionDescriptor {
        val key = ReliabilityKeyReference.of(
            "contract-kms",
            "contract-key",
            "1",
            ReliabilityContractAssertions.digest('9'),
        )
        return ReliabilityEncryptionDescriptor.of(
            "aes-256-gcm",
            key,
            ReliabilityContractAssertions.digest('a'),
            ReliabilityContractAssertions.digest('b'),
        )
    }

    companion object {
        @JvmStatic
        fun forDescriptor(descriptor: ReliabilityProviderDescriptor): DeterministicReliabilityProvider =
            DeterministicReliabilityProvider(descriptor)
    }
}

private fun <T> completed(value: T): CompletionStage<T> = CompletableFuture.completedFuture(value)
