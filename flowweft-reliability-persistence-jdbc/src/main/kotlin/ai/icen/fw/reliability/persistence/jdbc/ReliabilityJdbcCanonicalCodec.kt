package ai.icen.fw.reliability.persistence.jdbc

import ai.icen.fw.reliability.api.*
import ai.icen.fw.reliability.runtime.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

/** Versioned and bounded canonical durable state. Java serialization is deliberately forbidden. */
internal object ReliabilityJdbcCanonicalCodec {
    private const val MAGIC = 0x4657524c // FWRL
    private const val VERSION = 1
    private const val TYPE_RUN = 1
    private const val TYPE_SCHEDULE = 2
    private const val TYPE_SLO_RECORD = 3
    internal const val MAX_MEMENTO_BYTES = 8 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 4096
    private const val MAX_ITEMS = 256

    fun encodeRun(value: ReliabilityRun): ByteArray = encode(TYPE_RUN) { run(value) }
    fun decodeRun(bytes: ByteArray): ReliabilityRun = decode(bytes, TYPE_RUN) { run() }

    fun encodeSchedule(value: ReliabilitySloSchedule): ByteArray = encode(TYPE_SCHEDULE) { schedule(value) }
    fun decodeSchedule(bytes: ByteArray): ReliabilitySloSchedule = decode(bytes, TYPE_SCHEDULE) { schedule() }

    fun encodeEvaluationRecord(value: ReliabilitySloEvaluationRecord): ByteArray =
        encode(TYPE_SLO_RECORD) { sloRecord(value) }

    fun decodeEvaluationRecord(bytes: ByteArray): ReliabilitySloEvaluationRecord =
        decode(bytes, TYPE_SLO_RECORD) { sloRecord() }

    private fun <T> encode(type: Int, block: Writer.() -> T): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(type)
            Writer(output).block()
        }
        return buffer.toByteArray().also {
            require(it.size in 1..MAX_MEMENTO_BYTES) { "Reliability JDBC memento is too large." }
        }
    }

    private fun <T> decode(bytes: ByteArray, type: Int, block: Reader.() -> T): T {
        require(bytes.size in 1..MAX_MEMENTO_BYTES) { "Reliability JDBC memento size is invalid." }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        try {
            require(input.readInt() == MAGIC && input.readInt() == VERSION && input.readInt() == type) {
                "Reliability JDBC memento header is invalid."
            }
            val value = Reader(input).block()
            require(input.read() == -1) { "Reliability JDBC memento has trailing data." }
            return value
        } catch (failure: EOFException) {
            throw IllegalArgumentException("Reliability JDBC memento is truncated.", failure)
        } finally {
            input.close()
        }
    }

    private class Writer(private val output: DataOutputStream) {
        fun text(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size in 1..MAX_TEXT_BYTES) { "Reliability JDBC memento text is invalid." }
            output.writeInt(bytes.size)
            output.write(bytes)
        }

        fun long(value: Long) = output.writeLong(value)
        fun bool(value: Boolean) = output.writeBoolean(value)
        fun optionalText(value: String?) { bool(value != null); if (value != null) text(value) }
        fun optionalLong(value: Long?) { bool(value != null); if (value != null) long(value) }
        fun <T> optional(value: T?, write: Writer.(T) -> Unit) { bool(value != null); if (value != null) write(value) }

        fun <T> items(values: Collection<T>, write: Writer.(T) -> Unit) {
            require(values.size <= MAX_ITEMS) { "Reliability JDBC memento collection is too large." }
            output.writeInt(values.size)
            values.forEach { write(it) }
        }

        fun principal(value: ReliabilityPrincipalRef) { text(value.type); text(value.id) }
        fun resource(value: ReliabilityResourceRef) {
            text(value.type); text(value.id); text(value.revision); text(value.digest)
        }

        fun authorization(value: ReliabilityAuthorizationSnapshot) {
            text(value.authorizationId)
            text(value.tenantId)
            principal(value.principal)
            text(value.purpose.name)
            text(value.action.name)
            resource(value.resource)
            text(value.authorityId)
            text(value.authorityRevision)
            text(value.authorizationRevision)
            text(value.decisionDigest)
            long(value.issuedAtEpochMilli)
            long(value.expiresAtEpochMilli)
            text(value.snapshotDigest)
        }

        fun context(value: ReliabilityCallContext) {
            text(value.requestId)
            text(value.tenantId)
            principal(value.principal)
            text(value.purpose.name)
            text(value.action.name)
            resource(value.resource)
            authorization(value.authorization)
            text(value.idempotencyDigest)
            long(value.requestedAtEpochMilli)
            long(value.deadlineEpochMilli)
            text(value.contextDigest)
        }

        fun fence(value: ReliabilityVersionFence) {
            resource(value.resource)
            long(value.expectedVersion)
            text(value.expectedStateDigest)
            text(value.fenceDigest)
        }

        fun component(value: ReliabilityComponentScope) {
            text(value.kind.code)
            text(value.componentId)
            text(value.componentRevision)
            text(value.configurationDigest)
            text(value.scopeDigest)
        }

        fun environment(value: ReliabilityEnvironmentRef) {
            text(value.tenantId)
            text(value.environmentId)
            text(value.kind.name)
            resource(value.resource)
            text(value.topologyDigest)
            text(value.bindingDigest)
        }

        fun objective(value: ReliabilityRecoveryObjective) {
            component(value.scope)
            long(value.maximumDataLossMillis)
            long(value.maximumRecoveryMillis)
            text(value.objectiveDigest)
        }

        fun objectiveSet(value: ReliabilityRecoveryObjectiveSet) {
            text(value.policyId)
            text(value.policyVersion)
            text(value.sourcePolicyDigest)
            environment(value.environment)
            items(value.objectives) { objective(it) }
            long(value.effectiveFromEpochMilli)
            long(value.expiresAtEpochMilli)
            text(value.objectiveSetDigest)
        }

        fun keyReference(value: ReliabilityKeyReference) {
            text(value.providerId); text(value.keyId); text(value.keyVersion); text(value.referenceDigest)
            text(value.keyBindingDigest)
        }

        fun encryption(value: ReliabilityEncryptionDescriptor) {
            text(value.algorithm)
            keyReference(value.keyReference)
            text(value.envelopeMetadataDigest)
            text(value.ciphertextDigest)
            text(value.descriptorDigest)
        }

        fun cut(value: ReliabilityConsistentCut) {
            text(value.cutId)
            environment(value.sourceEnvironment)
            text(value.topologyDigest)
            long(value.cutAtEpochMilli)
            text(value.cutDigest)
        }

        fun artifact(value: ReliabilityBackupArtifact) {
            component(value.scope)
            text(value.snapshotReference)
            text(value.providerId)
            text(value.providerRevision)
            text(value.consistentCutDigest)
            long(value.recoveryPointEpochMilli)
            long(value.capturedAtEpochMilli)
            long(value.byteCount)
            text(value.contentDigest)
            encryption(value.encryption)
            text(value.artifactDigest)
        }

        fun manifest(value: ReliabilityBackupManifest) {
            val content = value.content
            text(content.manifestId)
            text(content.manifestVersion)
            objectiveSet(content.objectives)
            cut(content.consistentCut)
            items(content.artifacts) { artifact(it) }
            long(content.createdAtEpochMilli)
            text(content.contentDigest)
            val seal = value.seal
            text(seal.sealRevision)
            text(seal.integrityAlgorithm)
            keyReference(seal.verificationKeyReference)
            text(seal.signatureDigest)
            long(seal.sealedAtEpochMilli)
            text(seal.sealDigest)
            text(value.manifestDigest)
        }

        fun cleanProof(value: ReliabilityCleanTargetProof) {
            text(value.proofId)
            environment(value.target)
            text(value.verifierId)
            text(value.verifierRevision)
            text(value.evidenceDigest)
            long(value.observedAtEpochMilli)
            long(value.expiresAtEpochMilli)
            text(value.proofDigest)
        }

        fun verification(value: ReliabilityManifestVerificationReceipt) {
            text(value.requestDigest)
            text(value.manifestDigest)
            text(value.providerId)
            text(value.providerRevision)
            text(value.status.name)
            bool(value.sealVerified)
            bool(value.artifactDigestsVerified)
            bool(value.encryptionReferencesVerified)
            bool(value.consistentCutVerified)
            bool(value.recoveryObjectivesVerified)
            text(value.evidenceDigest)
            long(value.verifiedAtEpochMilli)
            long(value.expiresAtEpochMilli)
            text(value.receiptDigest)
        }

        fun intent(value: ReliabilityOperationIntent) {
            text(value.kind.name)
            text(value.tenantId)
            principal(value.principal)
            text(value.purpose.name)
            text(value.action.name)
            resource(value.resource)
            text(value.idempotencyDigest)
            text(value.argumentDigest)
            text(value.providerId)
            text(value.providerRevision)
            text(value.providerDescriptorDigest)
            optionalText(value.topologySnapshotDigest)
            optional(value.objectives) { objectiveSet(it) }
            optional(value.manifest) { manifest(it) }
            optional(value.verification) { verification(it) }
            environment(value.environment)
            optional(value.cleanTargetProof) { cleanProof(it) }
            fence(value.versionFence)
            optionalLong(value.recoveryReferenceEpochMilli)
            optionalText(value.drillId)
            long(value.submittedAtEpochMilli)
            long(value.executionDeadlineEpochMilli)
            text(value.intentDigest)
        }

        fun createRequest(value: ReliabilityBackupCreateRequest) {
            context(value.context)
            objectiveSet(value.objectives)
            fence(value.versionFence)
            long(value.startedAtEpochMilli)
            long(value.executionDeadlineEpochMilli)
            text(value.requestDigest)
        }

        fun verifyRequest(value: ReliabilityBackupVerifyRequest) {
            context(value.context)
            manifest(value.manifest)
            fence(value.versionFence)
            long(value.startedAtEpochMilli)
            text(value.requestDigest)
        }

        fun restoreRequest(value: ReliabilityRestoreRequest) {
            context(value.context)
            manifest(value.manifest)
            verification(value.verification)
            environment(value.target)
            cleanProof(value.cleanTargetProof)
            fence(value.targetVersionFence)
            long(value.recoveryReferenceEpochMilli)
            long(value.startedAtEpochMilli)
            long(value.executionDeadlineEpochMilli)
            text(value.requestDigest)
        }

        fun drillRequest(value: ReliabilityDrillRequest) {
            context(value.context)
            text(value.drillId)
            manifest(value.manifest)
            verification(value.verification)
            environment(value.target)
            cleanProof(value.cleanTargetProof)
            fence(value.targetVersionFence)
            long(value.simulatedFailureEpochMilli)
            long(value.startedAtEpochMilli)
            long(value.executionDeadlineEpochMilli)
            text(value.requestDigest)
        }

        fun dispatch(value: ReliabilityDispatch) {
            text(value.kind.name)
            text(value.providerId)
            text(value.providerRevision)
            text(value.providerDescriptorDigest)
            when (value.kind) {
                ReliabilityOperationKind.CREATE_BACKUP -> createRequest(requireNotNull(value.createRequest))
                ReliabilityOperationKind.VERIFY_BACKUP -> verifyRequest(requireNotNull(value.verifyRequest))
                ReliabilityOperationKind.RESTORE -> restoreRequest(requireNotNull(value.restoreRequest))
                ReliabilityOperationKind.DRILL -> drillRequest(requireNotNull(value.drillRequest))
            }
            text(value.originalAttempt.providerOperationId)
            text(value.originalAttempt.attemptDigest)
            text(value.dispatchDigest)
        }

        fun lease(value: ReliabilityRunLease) {
            text(value.ownerId)
            long(value.fencingToken)
            long(value.acquiredAtEpochMilli)
            long(value.expiresAtEpochMilli)
            text(value.leaseDigest)
        }

        fun providerFailure(value: ReliabilityFailure) {
            text(value.classification.name)
            text(value.code.name)
            bool(value.retryable)
            bool(value.reconciliationRequired)
            text(value.failureDigest)
        }

        fun runFailure(value: ReliabilityRunFailure) {
            text(value.code.name)
            optional(value.providerFailure) { providerFailure(it) }
            text(value.failureDigest)
        }

        fun outcomeUnknown(value: ReliabilityOutcomeUnknownReference) {
            text(value.uncertaintyEvidenceDigest)
            long(value.recordedAtEpochMilli)
            text(value.referenceDigest)
        }

        fun backupReceipt(value: ReliabilityBackupCreationReceipt) {
            text(value.requestDigest)
            manifest(value.manifest)
            text(value.providerId)
            text(value.providerRevision)
            text(value.providerEvidenceDigest)
            long(value.completedAtEpochMilli)
            text(value.receiptDigest)
        }

        fun restoreReceipt(value: ReliabilityRestoreReceipt) {
            text(value.requestDigest)
            text(value.targetBindingDigest)
            text(value.assessment.manifestDigest)
            text(value.providerId)
            text(value.providerRevision)
            text(value.providerEvidenceDigest)
            long(value.completedAtEpochMilli)
            long(value.assessment.recoveryReferenceEpochMilli)
            long(value.assessment.operationCompletedAtEpochMilli)
            text(value.assessment.assessmentDigest)
            text(value.receiptDigest)
        }

        fun drillReport(value: ReliabilityDrillReport) {
            text(value.requestDigest)
            text(value.drillId)
            text(value.targetBindingDigest)
            text(value.assessment.manifestDigest)
            text(value.providerId)
            text(value.providerRevision)
            text(value.providerEvidenceDigest)
            long(value.completedAtEpochMilli)
            long(value.assessment.recoveryReferenceEpochMilli)
            long(value.assessment.operationCompletedAtEpochMilli)
            text(value.assessment.assessmentDigest)
            text(value.reportDigest)
        }

        fun reconciliationReceipt(value: ReliabilityReconciliationReceipt) {
            text(value.requestDigest)
            text(value.originalReferenceDigest)
            text(value.status.name)
            text(value.providerEvidenceDigest)
            long(value.reconciledAtEpochMilli)
            text(value.receiptDigest)
        }

        fun runOutcome(value: ReliabilityRunOutcome) {
            when {
                value.backupReceipt != null -> { output.writeByte(1); backupReceipt(value.backupReceipt!!) }
                value.verificationReceipt != null -> { output.writeByte(2); verification(value.verificationReceipt!!) }
                value.restoreReceipt != null -> { output.writeByte(3); restoreReceipt(value.restoreReceipt!!) }
                value.drillReport != null -> { output.writeByte(4); drillReport(value.drillReport!!) }
                value.reconciliationReceipt != null -> {
                    output.writeByte(5); reconciliationReceipt(value.reconciliationReceipt!!)
                }
                else -> error("Reliability outcome has no evidence.")
            }
            text(value.evidenceDigest)
        }

        fun run(value: ReliabilityRun) {
            text(value.runId)
            intent(value.intent)
            text(value.status.name)
            long(value.version)
            optional(value.lease) { lease(it) }
            optional(value.dispatch) { dispatch(it) }
            optional(value.outcomeUnknown) { outcomeUnknown(it) }
            optional(value.outcome) { runOutcome(it) }
            optional(value.failure) { runFailure(it) }
            bool(value.cancellationRequested)
            long(value.createdAtEpochMilli)
            long(value.updatedAtEpochMilli)
            text(value.stateDigest)
        }

        fun errorBudget(value: ReliabilityErrorBudgetEvaluation) {
            text(value.requestDigest)
            text(value.objectiveDigest)
            text(value.state.name)
            long(value.targetPpm)
            optionalLong(value.observedPpm)
            optionalLong(value.observedBadCount)
            long(value.allowedBadPpm)
            optionalLong(value.burnRatePpm)
            optionalLong(value.budgetConsumedPpm)
            optionalLong(value.remainingBudgetPpm)
            bool(value.satisfied)
            optional(value.failure) { providerFailure(it) }
            long(value.evaluatedAtEpochMilli)
            text(value.evaluationDigest)
        }

        fun burnAlert(value: ReliabilityBurnRateAlert) {
            text(value.policyDigest)
            text(value.evaluationDigest)
            text(value.severity.name)
            text(value.code.name)
            bool(value.triggered)
            long(value.evaluatedAtEpochMilli)
            text(value.alertDigest)
        }

        fun sloRecord(value: ReliabilitySloEvaluationRecord) {
            errorBudget(value.evaluation)
            burnAlert(value.alert)
            text(value.recordDigest)
        }

        fun schedule(value: ReliabilitySloSchedule) {
            text(value.scheduleId)
            text(value.tenantId)
            text(value.policyBindingDigest)
            resource(value.objectiveResource)
            long(value.cadenceMillis)
            long(value.nextEvaluationAtEpochMilli)
            long(value.version)
            optional(value.lease) { lease(it) }
            optional(value.lastRecord) { sloRecord(it) }
            long(value.updatedAtEpochMilli)
            text(value.stateDigest)
        }
    }

    private class Reader(private val input: DataInputStream) {
        fun text(): String {
            val size = input.readInt()
            require(size in 1..MAX_TEXT_BYTES) { "Reliability JDBC memento text length is invalid." }
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        fun long(): Long = input.readLong()
        fun bool(): Boolean = input.readBoolean()
        fun optionalText(): String? = if (bool()) text() else null
        fun optionalLong(): Long? = if (bool()) long() else null
        fun <T> optional(read: Reader.() -> T): T? = if (bool()) read() else null
        fun <T> items(read: Reader.() -> T): List<T> {
            val size = input.readInt()
            require(size in 0..MAX_ITEMS) { "Reliability JDBC memento collection length is invalid." }
            return (0 until size).map { read() }
        }

        fun principal(): ReliabilityPrincipalRef = ReliabilityPrincipalRef.of(text(), text())
        fun resource(): ReliabilityResourceRef = ReliabilityResourceRef.of(text(), text(), text(), text())

        fun authorization(): ReliabilityAuthorizationSnapshot {
            val authorizationId = text()
            val tenantId = text()
            val principal = principal()
            val purpose = enumValue<ReliabilityPurpose>(text())
            val action = enumValue<ReliabilityAction>(text())
            val resource = resource()
            val value = ReliabilityAuthorizationSnapshot.of(
                authorizationId, tenantId, principal, purpose, action, resource,
                text(), text(), text(), text(), long(), long(),
            )
            require(value.snapshotDigest == text()) { "Reliability authorization digest is invalid." }
            return value
        }

        fun context(): ReliabilityCallContext {
            val requestId = text()
            val tenantId = text()
            val principal = principal()
            val purpose = enumValue<ReliabilityPurpose>(text())
            val action = enumValue<ReliabilityAction>(text())
            val resource = resource()
            val authorization = authorization()
            val value = ReliabilityCallContext.of(
                requestId, tenantId, principal, purpose, action, resource, authorization,
                text(), long(), long(),
            )
            require(value.contextDigest == text()) { "Reliability context digest is invalid." }
            return value
        }

        fun fence(): ReliabilityVersionFence {
            val value = ReliabilityVersionFence.of(resource(), long(), text())
            require(value.fenceDigest == text()) { "Reliability fence digest is invalid." }
            return value
        }

        fun component(): ReliabilityComponentScope {
            val value = ReliabilityComponentScope.of(ReliabilityComponentKind.of(text()), text(), text(), text())
            require(value.scopeDigest == text()) { "Reliability component digest is invalid." }
            return value
        }

        fun environment(): ReliabilityEnvironmentRef {
            val value = ReliabilityEnvironmentRef.of(
                text(), text(), enumValue(text()), resource(), text(),
            )
            require(value.bindingDigest == text()) { "Reliability environment digest is invalid." }
            return value
        }

        fun objective(): ReliabilityRecoveryObjective {
            val value = ReliabilityRecoveryObjective.of(component(), long(), long())
            require(value.objectiveDigest == text()) { "Reliability recovery objective digest is invalid." }
            return value
        }

        fun objectiveSet(): ReliabilityRecoveryObjectiveSet {
            val policyId = text()
            val policyVersion = text()
            val sourceDigest = text()
            val environment = environment()
            val objectives = items { objective() }
            val value = ReliabilityRecoveryObjectiveSet.of(
                policyId, policyVersion, sourceDigest, environment, objectives, long(), long(),
            )
            require(value.objectiveSetDigest == text()) { "Reliability objective-set digest is invalid." }
            return value
        }

        fun keyReference(): ReliabilityKeyReference {
            val value = ReliabilityKeyReference.of(text(), text(), text(), text())
            require(value.keyBindingDigest == text()) { "Reliability key-reference digest is invalid." }
            return value
        }

        fun encryption(): ReliabilityEncryptionDescriptor {
            val algorithm = text()
            val key = keyReference()
            val value = ReliabilityEncryptionDescriptor.of(algorithm, key, text(), text())
            require(value.descriptorDigest == text()) { "Reliability encryption digest is invalid." }
            return value
        }

        fun cut(): ReliabilityConsistentCut {
            val value = ReliabilityConsistentCut.of(text(), environment(), text(), long())
            require(value.cutDigest == text()) { "Reliability consistent-cut digest is invalid." }
            return value
        }

        fun artifact(): ReliabilityBackupArtifact {
            val scope = component()
            val snapshot = text()
            val providerId = text()
            val providerRevision = text()
            val cutDigest = text()
            val recoveryPoint = long()
            val capturedAt = long()
            val byteCount = long()
            val contentDigest = text()
            val encryption = encryption()
            val value = ReliabilityBackupArtifact.immutableEncrypted(
                scope, snapshot, providerId, providerRevision, cutDigest, recoveryPoint,
                capturedAt, byteCount, contentDigest, encryption,
            )
            require(value.artifactDigest == text()) { "Reliability artifact digest is invalid." }
            return value
        }

        fun manifest(): ReliabilityBackupManifest {
            val manifestId = text()
            val version = text()
            val objectives = objectiveSet()
            val cut = cut()
            val artifacts = items { artifact() }
            val createdAt = long()
            val expectedContent = text()
            val content = ReliabilityBackupManifestContent.of(
                manifestId, version, objectives, cut, artifacts, createdAt,
            )
            require(content.contentDigest == expectedContent) { "Reliability manifest content digest is invalid." }
            val sealRevision = text()
            val algorithm = text()
            val key = keyReference()
            val signatureDigest = text()
            val sealedAt = long()
            val expectedSeal = text()
            val seal = ReliabilityImmutableManifestSeal.of(
                content, sealRevision, algorithm, key, signatureDigest, sealedAt,
            )
            require(seal.sealDigest == expectedSeal) { "Reliability manifest seal digest is invalid." }
            val value = ReliabilityBackupManifest.of(content, seal)
            require(value.manifestDigest == text()) { "Reliability manifest digest is invalid." }
            return value
        }

        fun cleanProof(): ReliabilityCleanTargetProof {
            val proofId = text()
            val target = environment()
            val value = ReliabilityCleanTargetProof.clean(
                proofId, target, text(), text(), text(), long(), long(),
            )
            require(value.proofDigest == text()) { "Reliability clean-target proof digest is invalid." }
            return value
        }

        fun verification(): ReliabilityManifestVerificationReceipt =
            ReliabilityDurableEvidenceFactory.rehydrateVerification(
                text(), text(), text(), text(), enumValue(text()), bool(), bool(), bool(), bool(), bool(),
                text(), long(), long(), text(),
            )

        fun intent(): ReliabilityOperationIntent = ReliabilityDurableStateFactory.rehydrateIntent(
            enumValue(text()),
            text(),
            principal(),
            enumValue(text()),
            enumValue(text()),
            resource(),
            text(),
            text(),
            text(),
            text(),
            text(),
            optionalText(),
            optional { objectiveSet() },
            optional { manifest() },
            optional { verification() },
            environment(),
            optional { cleanProof() },
            fence(),
            optionalLong(),
            optionalText(),
            long(),
            long(),
            text(),
        )

        fun createRequest(): ReliabilityBackupCreateRequest {
            val value = ReliabilityBackupCreateRequest.of(context(), objectiveSet(), fence(), long(), long())
            require(value.requestDigest == text()) { "Reliability create request digest is invalid." }
            return value
        }

        fun verifyRequest(): ReliabilityBackupVerifyRequest {
            val value = ReliabilityBackupVerifyRequest.of(context(), manifest(), fence(), long())
            require(value.requestDigest == text()) { "Reliability verify request digest is invalid." }
            return value
        }

        fun restoreRequest(): ReliabilityRestoreRequest {
            val value = ReliabilityRestoreRequest.toCleanTarget(
                context(), manifest(), verification(), environment(), cleanProof(), fence(), long(), long(), long(),
            )
            require(value.requestDigest == text()) { "Reliability restore request digest is invalid." }
            return value
        }

        fun drillRequest(): ReliabilityDrillRequest {
            val value = ReliabilityDrillRequest.isolated(
                context(), text(), manifest(), verification(), environment(), cleanProof(), fence(),
                long(), long(), long(),
            )
            require(value.requestDigest == text()) { "Reliability drill request digest is invalid." }
            return value
        }

        fun dispatch(): ReliabilityDispatch {
            val kind = enumValue<ReliabilityOperationKind>(text())
            val providerId = text()
            val providerRevision = text()
            val descriptorDigest = text()
            val request: Any = when (kind) {
                ReliabilityOperationKind.CREATE_BACKUP -> createRequest()
                ReliabilityOperationKind.VERIFY_BACKUP -> verifyRequest()
                ReliabilityOperationKind.RESTORE -> restoreRequest()
                ReliabilityOperationKind.DRILL -> drillRequest()
            }
            val operationId = text()
            val expectedAttempt = text()
            val attempt = when (request) {
                is ReliabilityBackupCreateRequest -> ReliabilityOperationAttemptReference.forBackup(
                    request, providerId, providerRevision, operationId,
                )
                is ReliabilityBackupVerifyRequest -> ReliabilityOperationAttemptReference.forVerification(
                    request, providerId, providerRevision, operationId,
                )
                is ReliabilityRestoreRequest -> ReliabilityOperationAttemptReference.forRestore(
                    request, providerId, providerRevision, operationId,
                )
                is ReliabilityDrillRequest -> ReliabilityOperationAttemptReference.forDrill(
                    request, providerId, providerRevision, operationId,
                )
                else -> error("Unsupported reliability dispatch request.")
            }
            require(attempt.attemptDigest == expectedAttempt) { "Reliability attempt digest is invalid." }
            return ReliabilityDurableStateFactory.rehydrateDispatch(
                kind,
                providerId,
                providerRevision,
                descriptorDigest,
                request as? ReliabilityBackupCreateRequest,
                request as? ReliabilityBackupVerifyRequest,
                request as? ReliabilityRestoreRequest,
                request as? ReliabilityDrillRequest,
                attempt,
                text(),
            )
        }

        fun lease(): ReliabilityRunLease {
            val value = ReliabilityRunLease.of(text(), long(), long(), long())
            require(value.leaseDigest == text()) { "Reliability lease digest is invalid." }
            return value
        }

        fun providerFailure(): ReliabilityFailure {
            val value = ReliabilityFailure.of(enumValue(text()), enumValue(text()), bool(), bool())
            require(value.failureDigest == text()) { "Reliability provider failure digest is invalid." }
            return value
        }

        fun runFailure(): ReliabilityRunFailure {
            val value = ReliabilityRunFailure.of(enumValue(text()), optional { providerFailure() })
            require(value.failureDigest == text()) { "Reliability run failure digest is invalid." }
            return value
        }

        fun outcomeUnknown(dispatch: ReliabilityDispatch): ReliabilityOutcomeUnknownReference {
            val value = ReliabilityOutcomeUnknownReference.of(dispatch.originalAttempt, text(), long())
            require(value.referenceDigest == text()) { "Reliability outcome-unknown digest is invalid." }
            return value
        }

        fun backupReceipt(): ReliabilityBackupCreationReceipt = ReliabilityDurableEvidenceFactory.rehydrateBackupReceipt(
            text(), manifest(), text(), text(), text(), long(), text(),
        )

        fun restoreReceipt(intent: ReliabilityOperationIntent): ReliabilityRestoreReceipt {
            val requestDigest = text()
            val targetDigest = text()
            val manifestDigest = text()
            val providerId = text()
            val providerRevision = text()
            val evidence = text()
            val completedAt = long()
            val recoveryReference = long()
            val assessmentCompleted = long()
            val assessmentDigest = text()
            val assessment = ReliabilityRecoveryAssessment.evaluate(
                requireNotNull(intent.manifest), recoveryReference, assessmentCompleted,
            )
            require(assessment.assessmentDigest == assessmentDigest) {
                "Reliability restore assessment digest is invalid."
            }
            return ReliabilityDurableEvidenceFactory.rehydrateRestoreReceipt(
                requestDigest, targetDigest, manifestDigest, providerId, providerRevision, evidence,
                completedAt, assessment, text(),
            )
        }

        fun drillReport(intent: ReliabilityOperationIntent): ReliabilityDrillReport {
            val requestDigest = text()
            val drillId = text()
            val targetDigest = text()
            val manifestDigest = text()
            val providerId = text()
            val providerRevision = text()
            val evidence = text()
            val completedAt = long()
            val recoveryReference = long()
            val assessmentCompleted = long()
            val assessmentDigest = text()
            val assessment = ReliabilityRecoveryAssessment.evaluate(
                requireNotNull(intent.manifest), recoveryReference, assessmentCompleted,
            )
            require(assessment.assessmentDigest == assessmentDigest) {
                "Reliability drill assessment digest is invalid."
            }
            return ReliabilityDurableEvidenceFactory.rehydrateDrillReport(
                requestDigest, drillId, targetDigest, manifestDigest, providerId, providerRevision, evidence,
                completedAt, assessment, text(),
            )
        }

        fun reconciliationReceipt(): ReliabilityReconciliationReceipt =
            ReliabilityDurableEvidenceFactory.rehydrateReconciliationReceipt(
                text(), text(), enumValue(text()), text(), long(), text(),
            )

        fun runOutcome(intent: ReliabilityOperationIntent): ReliabilityRunOutcome {
            val outcome = when (input.readUnsignedByte()) {
                1 -> ReliabilityRunOutcome.backup(backupReceipt())
                2 -> ReliabilityRunOutcome.verification(verification())
                3 -> ReliabilityRunOutcome.restore(restoreReceipt(intent))
                4 -> ReliabilityRunOutcome.drill(drillReport(intent))
                5 -> ReliabilityRunOutcome.reconciliation(reconciliationReceipt())
                else -> throw IllegalArgumentException("Reliability outcome kind is invalid.")
            }
            require(outcome.evidenceDigest == text()) { "Reliability outcome digest is invalid." }
            return outcome
        }

        fun run(): ReliabilityRun {
            val runId = text()
            val intent = intent()
            val status = enumValue<ReliabilityRunStatus>(text())
            val version = long()
            val lease = optional { lease() }
            val dispatch = optional { dispatch() }
            val unknown = optional { outcomeUnknown(requireNotNull(dispatch)) }
            val outcome = optional { runOutcome(intent) }
            val failure = optional { runFailure() }
            return ReliabilityDurableStateFactory.rehydrateRun(
                runId, intent, status, version, lease, dispatch, unknown, outcome, failure,
                bool(), long(), long(), text(),
            )
        }

        fun errorBudget(): ReliabilityErrorBudgetEvaluation =
            ReliabilityErrorBudgetEvaluation.rehydrate(
                text(), text(), enumValue(text()), long(), optionalLong(), optionalLong(), long(),
                optionalLong(), optionalLong(), optionalLong(), bool(), optional { providerFailure() },
                long(), text(),
            )

        fun burnAlert(): ReliabilityBurnRateAlert = ReliabilityBurnRateAlert.rehydrate(
            text(), text(), enumValue(text()), enumValue(text()), bool(), long(), text(),
        )

        fun sloRecord(): ReliabilitySloEvaluationRecord {
            val value = ReliabilitySloEvaluationRecord.of(errorBudget(), burnAlert())
            require(value.recordDigest == text()) { "Reliability SLO record digest is invalid." }
            return value
        }

        fun schedule(): ReliabilitySloSchedule = ReliabilityDurableStateFactory.rehydrateSloSchedule(
            text(), text(), text(), resource(), long(), long(), long(), optional { lease() },
            optional { sloRecord() }, long(), text(),
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Reliability JDBC enum value is invalid.")
}
