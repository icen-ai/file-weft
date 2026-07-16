package ai.icen.fw.reliability.api

class ReliabilityBackupCreationReceipt private constructor(
    requestDigest: String,
    val manifest: ReliabilityBackupManifest,
    providerId: String,
    providerRevision: String,
    providerEvidenceDigest: String,
    val completedAtEpochMilli: Long,
) {
    val requestDigest: String = ReliabilityContractSupport.sha256(
        requestDigest, "Reliability backup request digest is invalid.",
    )
    val providerId: String = ReliabilityContractSupport.code(providerId, "Reliability backup provider is invalid.")
    val providerRevision: String = ReliabilityContractSupport.text(
        providerRevision, ReliabilityContractSupport.MAX_REVISION_BYTES, "Reliability provider revision is invalid.",
    )
    val providerEvidenceDigest: String = ReliabilityContractSupport.sha256(
        providerEvidenceDigest, "Reliability backup provider evidence digest is invalid.",
    )
    val receiptDigest: String

    init {
        require(completedAtEpochMilli >= 0L) { "Reliability backup completion time is invalid." }
        receiptDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-backup-create-receipt-v1")
            .text(this.requestDigest)
            .text(manifest.manifestDigest)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.providerEvidenceDigest)
            .longValue(completedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityBackupCreationReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            request: ReliabilityBackupCreateRequest,
            manifest: ReliabilityBackupManifest,
            providerId: String,
            providerRevision: String,
            providerEvidenceDigest: String,
            completedAtEpochMilli: Long,
        ): ReliabilityBackupCreationReceipt {
            require(manifest.content.objectives.objectiveSetDigest == request.objectives.objectiveSetDigest &&
                manifest.content.sourceEnvironment == request.objectives.environment
            ) { "Reliability backup receipt manifest does not implement the exact requested policy and source." }
            require(completedAtEpochMilli >= request.startedAtEpochMilli &&
                completedAtEpochMilli < request.executionDeadlineEpochMilli
            ) { "Reliability backup completion is outside its authorized deadline." }
            return ReliabilityBackupCreationReceipt(
                request.requestDigest,
                manifest,
                providerId,
                providerRevision,
                providerEvidenceDigest,
                completedAtEpochMilli,
            )
        }

        internal fun rehydrate(
            requestDigest: String,
            manifest: ReliabilityBackupManifest,
            providerId: String,
            providerRevision: String,
            providerEvidenceDigest: String,
            completedAtEpochMilli: Long,
        ): ReliabilityBackupCreationReceipt = ReliabilityBackupCreationReceipt(
            requestDigest,
            manifest,
            providerId,
            providerRevision,
            providerEvidenceDigest,
            completedAtEpochMilli,
        )
    }
}

/** Per-component recovery evidence; the same operation start/completion pair is used for every RTO. */
class ReliabilityRecoveryObjectiveEvaluation private constructor(
    val objective: ReliabilityRecoveryObjective,
    val artifact: ReliabilityBackupArtifact,
    val dataLossMillis: Long,
    val recoveryMillis: Long,
    val rpoMet: Boolean,
    val rtoMet: Boolean,
) {
    val evaluationDigest: String

    init {
        require(objective.scope == artifact.scope && dataLossMillis >= 0L && recoveryMillis >= 0L) {
            "Reliability recovery evaluation is not bound to one valid component."
        }
        require(rpoMet == (dataLossMillis <= objective.maximumDataLossMillis) &&
            rtoMet == (recoveryMillis <= objective.maximumRecoveryMillis)
        ) { "Reliability RPO/RTO flags do not match the measured durations." }
        evaluationDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-recovery-evaluation-v1")
            .text(objective.objectiveDigest)
            .text(artifact.artifactDigest)
            .longValue(dataLossMillis)
            .longValue(recoveryMillis)
            .bool(rpoMet)
            .bool(rtoMet)
            .finish()
    }

    override fun toString(): String =
        "ReliabilityRecoveryObjectiveEvaluation(kind=${objective.scope.kind}, rpoMet=$rpoMet, rtoMet=$rtoMet)"

    companion object {
        internal fun evaluate(
            objective: ReliabilityRecoveryObjective,
            artifact: ReliabilityBackupArtifact,
            cutAtEpochMilli: Long,
            recoveryReferenceEpochMilli: Long,
            operationCompletedAtEpochMilli: Long,
        ): ReliabilityRecoveryObjectiveEvaluation {
            require(artifact.recoveryPointEpochMilli <= cutAtEpochMilli &&
                cutAtEpochMilli <= recoveryReferenceEpochMilli &&
                operationCompletedAtEpochMilli >= recoveryReferenceEpochMilli
            ) { "Reliability recovery timing evidence is invalid." }
            val dataLoss = recoveryReferenceEpochMilli - artifact.recoveryPointEpochMilli
            val recovery = operationCompletedAtEpochMilli - recoveryReferenceEpochMilli
            return ReliabilityRecoveryObjectiveEvaluation(
                objective,
                artifact,
                dataLoss,
                recovery,
                dataLoss <= objective.maximumDataLossMillis,
                recovery <= objective.maximumRecoveryMillis,
            )
        }
    }
}

class ReliabilityRecoveryAssessment private constructor(
    manifest: ReliabilityBackupManifest,
    evaluations: Collection<ReliabilityRecoveryObjectiveEvaluation>,
    val recoveryReferenceEpochMilli: Long,
    val operationCompletedAtEpochMilli: Long,
) {
    val manifestDigest: String = manifest.manifestDigest
    val evaluations: List<ReliabilityRecoveryObjectiveEvaluation> = ReliabilityContractSupport.immutable(
        evaluations.sortedBy { it.objective.scope.scopeDigest },
        ReliabilityRecoveryObjectiveSet.MAX_COMPONENTS,
        "Reliability recovery evaluations are invalid.",
    )
    val rpoMet: Boolean = this.evaluations.all { it.rpoMet }
    val rtoMet: Boolean = this.evaluations.all { it.rtoMet }
    val assessmentDigest: String

    init {
        require(recoveryReferenceEpochMilli >= manifest.content.consistentCut.cutAtEpochMilli &&
            operationCompletedAtEpochMilli >= recoveryReferenceEpochMilli
        ) {
            "Reliability recovery assessment timing is invalid."
        }
        require(this.evaluations.isNotEmpty() &&
            this.evaluations.map { it.objective.scope.scopeDigest }.toSet() ==
            manifest.content.objectives.objectives.map { it.scope.scopeDigest }.toSet() &&
            this.evaluations.map { it.artifact.scope.scopeDigest }.toSet() ==
            manifest.content.artifacts.map { it.scope.scopeDigest }.toSet()
        ) { "Reliability recovery assessment must cover every manifest component exactly once." }
        val writer = ReliabilityContractSupport.digest("flowweft-reliability-api-recovery-assessment-v1")
            .text(manifest.manifestDigest)
            .longValue(recoveryReferenceEpochMilli)
            .longValue(operationCompletedAtEpochMilli)
            .bool(rpoMet)
            .bool(rtoMet)
            .integer(this.evaluations.size)
        this.evaluations.forEach { writer.text(it.evaluationDigest) }
        assessmentDigest = writer.finish()
    }

    override fun toString(): String =
        "ReliabilityRecoveryAssessment(components=${evaluations.size}, rpoMet=$rpoMet, rtoMet=$rtoMet)"

    companion object {
        @JvmStatic
        fun evaluate(
            manifest: ReliabilityBackupManifest,
            recoveryReferenceEpochMilli: Long,
            operationCompletedAtEpochMilli: Long,
        ): ReliabilityRecoveryAssessment {
            val artifactByScope = manifest.content.artifacts.associateBy { it.scope.scopeDigest }
            val evaluations = manifest.content.objectives.objectives.map { objective ->
                ReliabilityRecoveryObjectiveEvaluation.evaluate(
                    objective,
                    requireNotNull(artifactByScope[objective.scope.scopeDigest]),
                    manifest.content.consistentCut.cutAtEpochMilli,
                    recoveryReferenceEpochMilli,
                    operationCompletedAtEpochMilli,
                )
            }
            return ReliabilityRecoveryAssessment(
                manifest, evaluations, recoveryReferenceEpochMilli, operationCompletedAtEpochMilli,
            )
        }
    }
}

class ReliabilityRestoreReceipt private constructor(
    requestDigest: String,
    targetBindingDigest: String,
    providerId: String,
    providerRevision: String,
    providerEvidenceDigest: String,
    val completedAtEpochMilli: Long,
    val assessment: ReliabilityRecoveryAssessment,
) {
    val requestDigest: String = ReliabilityContractSupport.sha256(
        requestDigest, "Reliability restore request digest is invalid.",
    )
    val targetBindingDigest: String = ReliabilityContractSupport.sha256(
        targetBindingDigest, "Reliability restore target binding is invalid.",
    )
    val providerId: String = ReliabilityContractSupport.code(providerId, "Reliability restore provider is invalid.")
    val providerRevision: String = ReliabilityContractSupport.text(
        providerRevision, ReliabilityContractSupport.MAX_REVISION_BYTES, "Reliability provider revision is invalid.",
    )
    val providerEvidenceDigest: String = ReliabilityContractSupport.sha256(
        providerEvidenceDigest, "Reliability restore provider evidence digest is invalid.",
    )
    val receiptDigest: String

    init {
        require(completedAtEpochMilli >= 0L && assessment.operationCompletedAtEpochMilli == completedAtEpochMilli) {
            "Reliability restore completion and assessment time are inconsistent."
        }
        receiptDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-restore-receipt-v1")
            .text(this.requestDigest)
            .text(this.targetBindingDigest)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.providerEvidenceDigest)
            .text(assessment.assessmentDigest)
            .longValue(completedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityRestoreReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            request: ReliabilityRestoreRequest,
            providerId: String,
            providerRevision: String,
            providerEvidenceDigest: String,
            completedAtEpochMilli: Long,
        ): ReliabilityRestoreReceipt {
            require(completedAtEpochMilli >= request.startedAtEpochMilli &&
                completedAtEpochMilli < request.executionDeadlineEpochMilli
            ) { "Reliability restore completion is outside its authorized deadline." }
            return ReliabilityRestoreReceipt(
                request.requestDigest,
                request.target.bindingDigest,
                providerId,
                providerRevision,
                providerEvidenceDigest,
                completedAtEpochMilli,
                ReliabilityRecoveryAssessment.evaluate(
                    request.manifest,
                    request.recoveryReferenceEpochMilli,
                    completedAtEpochMilli,
                ),
            )
        }

        internal fun rehydrate(
            requestDigest: String,
            targetBindingDigest: String,
            providerId: String,
            providerRevision: String,
            providerEvidenceDigest: String,
            completedAtEpochMilli: Long,
            assessment: ReliabilityRecoveryAssessment,
        ): ReliabilityRestoreReceipt = ReliabilityRestoreReceipt(
            requestDigest,
            targetBindingDigest,
            providerId,
            providerRevision,
            providerEvidenceDigest,
            completedAtEpochMilli,
            assessment,
        )
    }
}

class ReliabilityDrillReport private constructor(
    requestDigest: String,
    drillId: String,
    targetBindingDigest: String,
    providerId: String,
    providerRevision: String,
    providerEvidenceDigest: String,
    val completedAtEpochMilli: Long,
    val assessment: ReliabilityRecoveryAssessment,
) {
    val requestDigest: String = ReliabilityContractSupport.sha256(
        requestDigest, "Reliability drill request digest is invalid.",
    )
    val drillId: String = ReliabilityContractSupport.opaque(drillId, "Reliability drill id is invalid.")
    val targetBindingDigest: String = ReliabilityContractSupport.sha256(
        targetBindingDigest, "Reliability drill target binding is invalid.",
    )
    val providerId: String = ReliabilityContractSupport.code(providerId, "Reliability drill provider is invalid.")
    val providerRevision: String = ReliabilityContractSupport.text(
        providerRevision, ReliabilityContractSupport.MAX_REVISION_BYTES, "Reliability provider revision is invalid.",
    )
    val providerEvidenceDigest: String = ReliabilityContractSupport.sha256(
        providerEvidenceDigest, "Reliability drill provider evidence digest is invalid.",
    )
    val reportDigest: String

    init {
        require(completedAtEpochMilli >= 0L && assessment.operationCompletedAtEpochMilli == completedAtEpochMilli) {
            "Reliability drill completion and assessment time are inconsistent."
        }
        reportDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-drill-report-v1")
            .text(this.requestDigest)
            .text(this.drillId)
            .text(this.targetBindingDigest)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.providerEvidenceDigest)
            .text(assessment.assessmentDigest)
            .longValue(completedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityDrillReport(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            request: ReliabilityDrillRequest,
            providerId: String,
            providerRevision: String,
            providerEvidenceDigest: String,
            completedAtEpochMilli: Long,
        ): ReliabilityDrillReport {
            require(completedAtEpochMilli >= request.startedAtEpochMilli &&
                completedAtEpochMilli < request.executionDeadlineEpochMilli
            ) { "Reliability drill completion is outside its authorized deadline." }
            return ReliabilityDrillReport(
                request.requestDigest,
                request.drillId,
                request.target.bindingDigest,
                providerId,
                providerRevision,
                providerEvidenceDigest,
                completedAtEpochMilli,
                ReliabilityRecoveryAssessment.evaluate(
                    request.manifest,
                    request.simulatedFailureEpochMilli,
                    completedAtEpochMilli,
                ),
            )
        }

        internal fun rehydrate(
            requestDigest: String,
            drillId: String,
            targetBindingDigest: String,
            providerId: String,
            providerRevision: String,
            providerEvidenceDigest: String,
            completedAtEpochMilli: Long,
            assessment: ReliabilityRecoveryAssessment,
        ): ReliabilityDrillReport = ReliabilityDrillReport(
            requestDigest,
            drillId,
            targetBindingDigest,
            providerId,
            providerRevision,
            providerEvidenceDigest,
            completedAtEpochMilli,
            assessment,
        )
    }
}

enum class ReliabilityReconciliationStatus { SUCCEEDED, FAILED, STILL_UNKNOWN }

class ReliabilityReconciliationReceipt private constructor(
    requestDigest: String,
    originalReferenceDigest: String,
    val status: ReliabilityReconciliationStatus,
    providerEvidenceDigest: String,
    val reconciledAtEpochMilli: Long,
) {
    val requestDigest: String = ReliabilityContractSupport.sha256(
        requestDigest, "Reliability reconciliation request digest is invalid.",
    )
    val originalReferenceDigest: String = ReliabilityContractSupport.sha256(
        originalReferenceDigest, "Reliability reconciliation original reference is invalid.",
    )
    val providerEvidenceDigest: String = ReliabilityContractSupport.sha256(
        providerEvidenceDigest, "Reliability reconciliation evidence digest is invalid.",
    )
    val receiptDigest: String

    init {
        require(reconciledAtEpochMilli >= 0L) { "Reliability reconciliation time is invalid." }
        receiptDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-reconcile-receipt-v1")
            .text(this.requestDigest)
            .text(this.originalReferenceDigest)
            .text(status.name)
            .text(this.providerEvidenceDigest)
            .longValue(reconciledAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityReconciliationReceipt(status=$status, <redacted>)"

    companion object {
        @JvmStatic
        fun of(
            request: ReliabilityReconciliationRequest,
            status: ReliabilityReconciliationStatus,
            providerEvidenceDigest: String,
            reconciledAtEpochMilli: Long,
        ): ReliabilityReconciliationReceipt {
            require(reconciledAtEpochMilli >= request.startedAtEpochMilli &&
                reconciledAtEpochMilli < request.context.deadlineEpochMilli
            ) { "Reliability reconciliation result is outside its authorized deadline." }
            return ReliabilityReconciliationReceipt(
                request.requestDigest,
                request.outcomeUnknown.referenceDigest,
                status,
                providerEvidenceDigest,
                reconciledAtEpochMilli,
            )
        }

        internal fun rehydrate(
            requestDigest: String,
            originalReferenceDigest: String,
            status: ReliabilityReconciliationStatus,
            providerEvidenceDigest: String,
            reconciledAtEpochMilli: Long,
        ): ReliabilityReconciliationReceipt = ReliabilityReconciliationReceipt(
            requestDigest,
            originalReferenceDigest,
            status,
            providerEvidenceDigest,
            reconciledAtEpochMilli,
        )
    }
}

enum class ReliabilityProviderResultStatus { SUCCESS, FAILURE, OUTCOME_UNKNOWN }

/** Provider failures contain no Throwable, vendor message, endpoint, credential, or raw payload. */
class ReliabilityProviderResult<T> private constructor(
    val status: ReliabilityProviderResultStatus,
    val value: T?,
    val failure: ReliabilityFailure?,
    val outcomeUnknown: ReliabilityOutcomeUnknownReference?,
    val replayed: Boolean,
) {
    init {
        when (status) {
            ReliabilityProviderResultStatus.SUCCESS -> require(
                value != null && failure == null && outcomeUnknown == null,
            ) { "Reliability provider success requires exactly one non-null value." }
            ReliabilityProviderResultStatus.FAILURE -> require(
                value == null && failure != null &&
                    failure.classification != ReliabilityFailureClass.OUTCOME_UNKNOWN && outcomeUnknown == null,
            ) { "Reliability provider failure requires one closed non-unknown failure." }
            ReliabilityProviderResultStatus.OUTCOME_UNKNOWN -> require(
                value == null && failure?.classification == ReliabilityFailureClass.OUTCOME_UNKNOWN &&
                    failure.reconciliationRequired && outcomeUnknown != null,
            ) { "Reliability outcome unknown requires its exact original attempt reference." }
        }
        require(!replayed || status == ReliabilityProviderResultStatus.SUCCESS) {
            "Only a successful reliability result may be an idempotent replay."
        }
    }

    override fun toString(): String = "ReliabilityProviderResult(status=$status, replayed=$replayed)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun <T> success(value: T, replayed: Boolean = false): ReliabilityProviderResult<T> =
            ReliabilityProviderResult(ReliabilityProviderResultStatus.SUCCESS, value, null, null, replayed)

        @JvmStatic
        fun <T> failure(failure: ReliabilityFailure): ReliabilityProviderResult<T> =
            ReliabilityProviderResult(ReliabilityProviderResultStatus.FAILURE, null, failure, null, false)

        @JvmStatic
        fun <T> outcomeUnknown(reference: ReliabilityOutcomeUnknownReference): ReliabilityProviderResult<T> =
            ReliabilityProviderResult(
                ReliabilityProviderResultStatus.OUTCOME_UNKNOWN,
                null,
                ReliabilityFailure.of(
                    ReliabilityFailureClass.OUTCOME_UNKNOWN,
                    ReliabilityFailureCode.OPERATION_OUTCOME_UNKNOWN,
                    false,
                    true,
                ),
                reference,
                false,
            )
    }
}
