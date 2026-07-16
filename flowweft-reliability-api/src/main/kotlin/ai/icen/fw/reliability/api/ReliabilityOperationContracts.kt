package ai.icen.fw.reliability.api

/** Mutating and read-only operation identities used by provider attempts and reconciliation. */
enum class ReliabilityOperationKind {
    CREATE_BACKUP,
    VERIFY_BACKUP,
    RESTORE,
    DRILL,
}

class ReliabilityBackupCreateRequest private constructor(
    val context: ReliabilityCallContext,
    val objectives: ReliabilityRecoveryObjectiveSet,
    val versionFence: ReliabilityVersionFence,
    val startedAtEpochMilli: Long,
    val executionDeadlineEpochMilli: Long,
) {
    val requestDigest: String

    init {
        context.requireFresh(startedAtEpochMilli)
        require(context.purpose == ReliabilityPurpose.CREATE_BACKUP &&
            context.action == ReliabilityAction.CREATE_BACKUP &&
            context.resource == objectives.environment.resource &&
            context.tenantId == objectives.environment.tenantId
        ) { "Reliability backup creation is not authorized for the exact source environment." }
        require(versionFence.resource == context.resource) {
            "Reliability backup creation fence does not bind the authorized source revision."
        }
        require(startedAtEpochMilli >= context.requestedAtEpochMilli && startedAtEpochMilli < context.deadlineEpochMilli &&
            startedAtEpochMilli >= objectives.effectiveFromEpochMilli &&
            context.deadlineEpochMilli <= objectives.expiresAtEpochMilli
        ) { "Reliability backup creation is outside its authorized policy window." }
        require(executionDeadlineEpochMilli > startedAtEpochMilli &&
            executionDeadlineEpochMilli - startedAtEpochMilli <= MAX_ASYNC_EXECUTION_MILLIS
        ) { "Reliability backup execution deadline is invalid." }
        requestDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-backup-create-request-v1")
            .text(context.contextDigest)
            .text(objectives.objectiveSetDigest)
            .text(versionFence.fenceDigest)
            .longValue(startedAtEpochMilli)
            .longValue(executionDeadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityBackupCreateRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: ReliabilityCallContext,
            objectives: ReliabilityRecoveryObjectiveSet,
            versionFence: ReliabilityVersionFence,
            startedAtEpochMilli: Long,
            executionDeadlineEpochMilli: Long,
        ): ReliabilityBackupCreateRequest = ReliabilityBackupCreateRequest(
            context, objectives, versionFence, startedAtEpochMilli, executionDeadlineEpochMilli,
        )

        const val MAX_ASYNC_EXECUTION_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L
    }
}

class ReliabilityBackupVerifyRequest private constructor(
    val context: ReliabilityCallContext,
    val manifest: ReliabilityBackupManifest,
    val versionFence: ReliabilityVersionFence,
    val startedAtEpochMilli: Long,
) {
    val requestDigest: String

    init {
        context.requireFresh(startedAtEpochMilli)
        require(context.purpose == ReliabilityPurpose.VERIFY_BACKUP &&
            context.action == ReliabilityAction.VERIFY_BACKUP &&
            context.resource == manifest.resource &&
            context.tenantId == manifest.content.sourceEnvironment.tenantId
        ) { "Reliability verification is not authorized for the exact immutable manifest." }
        require(versionFence.resource == manifest.resource) {
            "Reliability verification fence does not bind the exact manifest revision."
        }
        require(startedAtEpochMilli >= context.requestedAtEpochMilli && startedAtEpochMilli < context.deadlineEpochMilli) {
            "Reliability verification starts outside its authorized call window."
        }
        requestDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-backup-verify-request-v1")
            .text(context.contextDigest)
            .text(manifest.manifestDigest)
            .text(versionFence.fenceDigest)
            .longValue(startedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityBackupVerifyRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: ReliabilityCallContext,
            manifest: ReliabilityBackupManifest,
            versionFence: ReliabilityVersionFence,
            startedAtEpochMilli: Long,
        ): ReliabilityBackupVerifyRequest = ReliabilityBackupVerifyRequest(
            context, manifest, versionFence, startedAtEpochMilli,
        )
    }
}

enum class ReliabilityManifestVerificationStatus { VALID, INVALID }

/**
 * Bounded immutable-manifest validation evidence. VALID means every named check succeeded; a
 * restore cannot accept an INVALID receipt or one that is stale at the authorized dispatch.
 */
class ReliabilityManifestVerificationReceipt private constructor(
    request: ReliabilityBackupVerifyRequest,
    providerId: String,
    providerRevision: String,
    val status: ReliabilityManifestVerificationStatus,
    val sealVerified: Boolean,
    val artifactDigestsVerified: Boolean,
    val encryptionReferencesVerified: Boolean,
    val consistentCutVerified: Boolean,
    val recoveryObjectivesVerified: Boolean,
    evidenceDigest: String,
    val verifiedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val requestDigest: String = request.requestDigest
    val manifestDigest: String = request.manifest.manifestDigest
    val providerId: String = ReliabilityContractSupport.code(
        providerId, "Reliability verification provider is invalid.",
    )
    val providerRevision: String = ReliabilityContractSupport.text(
        providerRevision,
        ReliabilityContractSupport.MAX_REVISION_BYTES,
        "Reliability verification provider revision is invalid.",
    )
    val evidenceDigest: String = ReliabilityContractSupport.sha256(
        evidenceDigest, "Reliability verification evidence digest is invalid.",
    )
    val receiptDigest: String

    init {
        val allChecks = sealVerified && artifactDigestsVerified && encryptionReferencesVerified &&
            consistentCutVerified && recoveryObjectivesVerified
        require((status == ReliabilityManifestVerificationStatus.VALID) == allChecks) {
            "Reliability manifest validity must exactly reflect all mandatory checks."
        }
        require(verifiedAtEpochMilli >= 0L && expiresAtEpochMilli > verifiedAtEpochMilli &&
            expiresAtEpochMilli - verifiedAtEpochMilli <= MAX_VERIFICATION_TTL_MILLIS
        ) { "Reliability manifest verification lifetime is invalid." }
        require(verifiedAtEpochMilli >= request.startedAtEpochMilli &&
            verifiedAtEpochMilli < request.context.deadlineEpochMilli
        ) { "Reliability manifest verification completed outside its authorized call window." }
        receiptDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-verification-receipt-v1")
            .text(this.requestDigest)
            .text(this.manifestDigest)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(status.name)
            .bool(sealVerified)
            .bool(artifactDigestsVerified)
            .bool(encryptionReferencesVerified)
            .bool(consistentCutVerified)
            .bool(recoveryObjectivesVerified)
            .text(this.evidenceDigest)
            .longValue(verifiedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .finish()
    }

    fun isValidAt(manifest: ReliabilityBackupManifest, dispatchAtEpochMilli: Long): Boolean =
        status == ReliabilityManifestVerificationStatus.VALID && manifestDigest == manifest.manifestDigest &&
            verifiedAtEpochMilli <= dispatchAtEpochMilli && dispatchAtEpochMilli < expiresAtEpochMilli

    override fun toString(): String = "ReliabilityManifestVerificationReceipt(status=$status, <redacted>)"

    companion object {
        const val MAX_VERIFICATION_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L

        @JvmStatic
        fun of(
            request: ReliabilityBackupVerifyRequest,
            providerId: String,
            providerRevision: String,
            status: ReliabilityManifestVerificationStatus,
            sealVerified: Boolean,
            artifactDigestsVerified: Boolean,
            encryptionReferencesVerified: Boolean,
            consistentCutVerified: Boolean,
            recoveryObjectivesVerified: Boolean,
            evidenceDigest: String,
            verifiedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): ReliabilityManifestVerificationReceipt = ReliabilityManifestVerificationReceipt(
            request,
            providerId,
            providerRevision,
            status,
            sealVerified,
            artifactDigestsVerified,
            encryptionReferencesVerified,
            consistentCutVerified,
            recoveryObjectivesVerified,
            evidenceDigest,
            verifiedAtEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

/** Restore is deliberately restricted to a distinct, proven-clean RECOVERY environment. */
class ReliabilityRestoreRequest private constructor(
    val context: ReliabilityCallContext,
    val manifest: ReliabilityBackupManifest,
    val verification: ReliabilityManifestVerificationReceipt,
    val target: ReliabilityEnvironmentRef,
    val cleanTargetProof: ReliabilityCleanTargetProof,
    val targetVersionFence: ReliabilityVersionFence,
    val recoveryReferenceEpochMilli: Long,
    val startedAtEpochMilli: Long,
    val executionDeadlineEpochMilli: Long,
) {
    val requestDigest: String

    init {
        val source = manifest.content.sourceEnvironment
        context.requireFresh(startedAtEpochMilli)
        require(context.purpose == ReliabilityPurpose.RESTORE &&
            context.action == ReliabilityAction.RESTORE_CLEAN_TARGET &&
            context.resource == target.resource && context.tenantId == target.tenantId &&
            source.tenantId == target.tenantId
        ) { "Reliability restore is not authorized for the exact tenant and target." }
        require(target.kind == ReliabilityEnvironmentKind.RECOVERY && !source.sameLogicalEnvironment(target) &&
            target.topologyDigest == source.topologyDigest
        ) {
            "Reliability restore requires a distinct recovery environment; in-place restore is forbidden."
        }
        require(targetVersionFence.resource == target.resource && cleanTargetProof.target == target) {
            "Reliability restore target evidence or CAS fence is not bound to the exact target."
        }
        require(recoveryReferenceEpochMilli >= manifest.content.consistentCut.cutAtEpochMilli &&
            recoveryReferenceEpochMilli <= startedAtEpochMilli &&
            startedAtEpochMilli >= context.requestedAtEpochMilli && startedAtEpochMilli < context.deadlineEpochMilli &&
            cleanTargetProof.isFreshAt(startedAtEpochMilli) &&
            verification.isValidAt(manifest, startedAtEpochMilli)
        ) { "Reliability restore target or manifest evidence is stale, future-dated, or incomplete." }
        require(executionDeadlineEpochMilli > startedAtEpochMilli &&
            executionDeadlineEpochMilli - startedAtEpochMilli <= ReliabilityBackupCreateRequest.MAX_ASYNC_EXECUTION_MILLIS
        ) { "Reliability restore execution deadline is invalid." }
        requestDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-restore-request-v1")
            .text(context.contextDigest)
            .text(manifest.manifestDigest)
            .text(verification.receiptDigest)
            .text(target.bindingDigest)
            .text(cleanTargetProof.proofDigest)
            .text(targetVersionFence.fenceDigest)
            .longValue(recoveryReferenceEpochMilli)
            .longValue(startedAtEpochMilli)
            .longValue(executionDeadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityRestoreRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun toCleanTarget(
            context: ReliabilityCallContext,
            manifest: ReliabilityBackupManifest,
            verification: ReliabilityManifestVerificationReceipt,
            target: ReliabilityEnvironmentRef,
            cleanTargetProof: ReliabilityCleanTargetProof,
            targetVersionFence: ReliabilityVersionFence,
            recoveryReferenceEpochMilli: Long,
            startedAtEpochMilli: Long,
            executionDeadlineEpochMilli: Long,
        ): ReliabilityRestoreRequest = ReliabilityRestoreRequest(
            context,
            manifest,
            verification,
            target,
            cleanTargetProof,
            targetVersionFence,
            recoveryReferenceEpochMilli,
            startedAtEpochMilli,
            executionDeadlineEpochMilli,
        )
    }
}

/** A recovery drill always runs in a separate, proven-clean DRILL environment. */
class ReliabilityDrillRequest private constructor(
    val context: ReliabilityCallContext,
    val drillId: String,
    val manifest: ReliabilityBackupManifest,
    val verification: ReliabilityManifestVerificationReceipt,
    val target: ReliabilityEnvironmentRef,
    val cleanTargetProof: ReliabilityCleanTargetProof,
    val targetVersionFence: ReliabilityVersionFence,
    val simulatedFailureEpochMilli: Long,
    val startedAtEpochMilli: Long,
    val executionDeadlineEpochMilli: Long,
) {
    val requestDigest: String

    init {
        val source = manifest.content.sourceEnvironment
        context.requireFresh(startedAtEpochMilli)
        ReliabilityContractSupport.opaque(drillId, "Reliability drill id is invalid.")
        require(context.purpose == ReliabilityPurpose.RUN_DRILL &&
            context.action == ReliabilityAction.RUN_DRILL && context.resource == target.resource &&
            context.tenantId == target.tenantId && source.tenantId == target.tenantId
        ) { "Reliability drill is not authorized for the exact tenant and target." }
        require(target.kind == ReliabilityEnvironmentKind.DRILL && !source.sameLogicalEnvironment(target) &&
            target.topologyDigest == source.topologyDigest
        ) {
            "Reliability drill requires a distinct isolated drill environment."
        }
        require(targetVersionFence.resource == target.resource && cleanTargetProof.target == target) {
            "Reliability drill target evidence or CAS fence is not bound to the exact target."
        }
        require(simulatedFailureEpochMilli >= manifest.content.consistentCut.cutAtEpochMilli &&
            simulatedFailureEpochMilli <= startedAtEpochMilli &&
            startedAtEpochMilli >= context.requestedAtEpochMilli && startedAtEpochMilli < context.deadlineEpochMilli &&
            cleanTargetProof.isFreshAt(startedAtEpochMilli) &&
            verification.isValidAt(manifest, startedAtEpochMilli)
        ) { "Reliability drill target or manifest evidence is stale, future-dated, or incomplete." }
        require(executionDeadlineEpochMilli > startedAtEpochMilli &&
            executionDeadlineEpochMilli - startedAtEpochMilli <= ReliabilityBackupCreateRequest.MAX_ASYNC_EXECUTION_MILLIS
        ) { "Reliability drill execution deadline is invalid." }
        requestDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-drill-request-v1")
            .text(context.contextDigest)
            .text(drillId)
            .text(manifest.manifestDigest)
            .text(verification.receiptDigest)
            .text(target.bindingDigest)
            .text(cleanTargetProof.proofDigest)
            .text(targetVersionFence.fenceDigest)
            .longValue(simulatedFailureEpochMilli)
            .longValue(startedAtEpochMilli)
            .longValue(executionDeadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityDrillRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun isolated(
            context: ReliabilityCallContext,
            drillId: String,
            manifest: ReliabilityBackupManifest,
            verification: ReliabilityManifestVerificationReceipt,
            target: ReliabilityEnvironmentRef,
            cleanTargetProof: ReliabilityCleanTargetProof,
            targetVersionFence: ReliabilityVersionFence,
            simulatedFailureEpochMilli: Long,
            startedAtEpochMilli: Long,
            executionDeadlineEpochMilli: Long,
        ): ReliabilityDrillRequest = ReliabilityDrillRequest(
            context,
            drillId,
            manifest,
            verification,
            target,
            cleanTargetProof,
            targetVersionFence,
            simulatedFailureEpochMilli,
            startedAtEpochMilli,
            executionDeadlineEpochMilli,
        )
    }
}

/**
 * Exact identity of the one provider call that may have taken effect. It contains no replacement
 * payload; reconciliation can only query this original reference.
 */
class ReliabilityOperationAttemptReference private constructor(
    val kind: ReliabilityOperationKind,
    val context: ReliabilityCallContext,
    requestDigest: String,
    val versionFence: ReliabilityVersionFence,
    providerId: String,
    providerRevision: String,
    providerOperationId: String,
    val startedAtEpochMilli: Long,
    val executionDeadlineEpochMilli: Long,
) {
    val requestDigest: String = ReliabilityContractSupport.sha256(
        requestDigest, "Reliability original request digest is invalid.",
    )
    val providerId: String = ReliabilityContractSupport.code(providerId, "Reliability provider id is invalid.")
    val providerRevision: String = ReliabilityContractSupport.text(
        providerRevision, ReliabilityContractSupport.MAX_REVISION_BYTES, "Reliability provider revision is invalid.",
    )
    val providerOperationId: String = ReliabilityContractSupport.opaque(
        providerOperationId, "Reliability provider operation id is invalid.",
    )
    val attemptDigest: String

    init {
        context.requireFresh(startedAtEpochMilli)
        require(kind.matches(context.purpose, context.action) && versionFence.resource == context.resource) {
            "Reliability provider attempt is not bound to the exact authorized operation and fence."
        }
        require(startedAtEpochMilli >= context.requestedAtEpochMilli && startedAtEpochMilli < context.deadlineEpochMilli) {
            "Reliability provider attempt starts outside its call window."
        }
        require(executionDeadlineEpochMilli > startedAtEpochMilli &&
            executionDeadlineEpochMilli - startedAtEpochMilli <= ReliabilityBackupCreateRequest.MAX_ASYNC_EXECUTION_MILLIS
        ) { "Reliability provider attempt execution deadline is invalid." }
        attemptDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-operation-attempt-v1")
            .text(kind.name)
            .text(context.contextDigest)
            .text(this.requestDigest)
            .text(versionFence.fenceDigest)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.providerOperationId)
            .longValue(startedAtEpochMilli)
            .longValue(executionDeadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityOperationAttemptReference(kind=$kind, <redacted>)"

    companion object {
        @JvmStatic
        fun forBackup(
            request: ReliabilityBackupCreateRequest,
            providerId: String,
            providerRevision: String,
            providerOperationId: String,
        ): ReliabilityOperationAttemptReference = ReliabilityOperationAttemptReference(
            ReliabilityOperationKind.CREATE_BACKUP,
            request.context,
            request.requestDigest,
            request.versionFence,
            providerId,
            providerRevision,
            providerOperationId,
            request.startedAtEpochMilli,
            request.executionDeadlineEpochMilli,
        )

        @JvmStatic
        fun forVerification(
            request: ReliabilityBackupVerifyRequest,
            providerId: String,
            providerRevision: String,
            providerOperationId: String,
        ): ReliabilityOperationAttemptReference = ReliabilityOperationAttemptReference(
            ReliabilityOperationKind.VERIFY_BACKUP,
            request.context,
            request.requestDigest,
            request.versionFence,
            providerId,
            providerRevision,
            providerOperationId,
            request.startedAtEpochMilli,
            request.context.deadlineEpochMilli,
        )

        @JvmStatic
        fun forRestore(
            request: ReliabilityRestoreRequest,
            providerId: String,
            providerRevision: String,
            providerOperationId: String,
        ): ReliabilityOperationAttemptReference = ReliabilityOperationAttemptReference(
            ReliabilityOperationKind.RESTORE,
            request.context,
            request.requestDigest,
            request.targetVersionFence,
            providerId,
            providerRevision,
            providerOperationId,
            request.startedAtEpochMilli,
            request.executionDeadlineEpochMilli,
        )

        @JvmStatic
        fun forDrill(
            request: ReliabilityDrillRequest,
            providerId: String,
            providerRevision: String,
            providerOperationId: String,
        ): ReliabilityOperationAttemptReference = ReliabilityOperationAttemptReference(
            ReliabilityOperationKind.DRILL,
            request.context,
            request.requestDigest,
            request.targetVersionFence,
            providerId,
            providerRevision,
            providerOperationId,
            request.startedAtEpochMilli,
            request.executionDeadlineEpochMilli,
        )
    }
}

class ReliabilityOutcomeUnknownReference private constructor(
    val originalAttempt: ReliabilityOperationAttemptReference,
    uncertaintyEvidenceDigest: String,
    val recordedAtEpochMilli: Long,
) {
    val uncertaintyEvidenceDigest: String = ReliabilityContractSupport.sha256(
        uncertaintyEvidenceDigest, "Reliability outcome-unknown evidence digest is invalid.",
    )
    val referenceDigest: String

    init {
        require(recordedAtEpochMilli >= originalAttempt.startedAtEpochMilli) {
            "Reliability outcome-unknown evidence predates the original attempt."
        }
        referenceDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-outcome-unknown-v1")
            .text(originalAttempt.attemptDigest)
            .text(this.uncertaintyEvidenceDigest)
            .longValue(recordedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityOutcomeUnknownReference(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            originalAttempt: ReliabilityOperationAttemptReference,
            uncertaintyEvidenceDigest: String,
            recordedAtEpochMilli: Long,
        ): ReliabilityOutcomeUnknownReference = ReliabilityOutcomeUnknownReference(
            originalAttempt, uncertaintyEvidenceDigest, recordedAtEpochMilli,
        )
    }
}

class ReliabilityReconciliationRequest private constructor(
    val context: ReliabilityCallContext,
    val outcomeUnknown: ReliabilityOutcomeUnknownReference,
    val startedAtEpochMilli: Long,
) {
    val originalVersionFence: ReliabilityVersionFence = outcomeUnknown.originalAttempt.versionFence
    val requestDigest: String

    init {
        val original = outcomeUnknown.originalAttempt
        context.requireFresh(startedAtEpochMilli)
        require(context.purpose == ReliabilityPurpose.RECONCILE &&
            context.action == ReliabilityAction.RECONCILE_OPERATION &&
            context.tenantId == original.context.tenantId &&
            context.resource == original.context.resource
        ) { "Reliability reconciliation is not authorized for the exact original tenant and resource." }
        require(startedAtEpochMilli >= context.requestedAtEpochMilli && startedAtEpochMilli < context.deadlineEpochMilli) {
            "Reliability reconciliation starts outside its fresh authorization window."
        }
        requestDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-reconcile-request-v1")
            .text(context.contextDigest)
            .text(outcomeUnknown.referenceDigest)
            .text(originalVersionFence.fenceDigest)
            .longValue(startedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityReconciliationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun exactOriginal(
            context: ReliabilityCallContext,
            outcomeUnknown: ReliabilityOutcomeUnknownReference,
            startedAtEpochMilli: Long,
        ): ReliabilityReconciliationRequest = ReliabilityReconciliationRequest(
            context, outcomeUnknown, startedAtEpochMilli,
        )
    }
}

private fun ReliabilityOperationKind.matches(purpose: ReliabilityPurpose, action: ReliabilityAction): Boolean =
    when (this) {
        ReliabilityOperationKind.CREATE_BACKUP ->
            purpose == ReliabilityPurpose.CREATE_BACKUP && action == ReliabilityAction.CREATE_BACKUP
        ReliabilityOperationKind.VERIFY_BACKUP ->
            purpose == ReliabilityPurpose.VERIFY_BACKUP && action == ReliabilityAction.VERIFY_BACKUP
        ReliabilityOperationKind.RESTORE ->
            purpose == ReliabilityPurpose.RESTORE && action == ReliabilityAction.RESTORE_CLEAN_TARGET
        ReliabilityOperationKind.DRILL ->
            purpose == ReliabilityPurpose.RUN_DRILL && action == ReliabilityAction.RUN_DRILL
    }
