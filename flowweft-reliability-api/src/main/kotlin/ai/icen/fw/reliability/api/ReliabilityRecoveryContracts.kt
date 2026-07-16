package ai.icen.fw.reliability.api

class ReliabilityComponentKind private constructor(code: String) {
    val code: String = ReliabilityContractSupport.code(code, "Reliability component kind is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is ReliabilityComponentKind && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = code

    companion object {
        @JvmField val DATABASE = ReliabilityComponentKind("database")
        @JvmField val OBJECT_STORAGE = ReliabilityComponentKind("object-storage")
        @JvmField val SEARCH_INDEX = ReliabilityComponentKind("search-index")
        @JvmField val QUEUE = ReliabilityComponentKind("queue")
        @JvmField val CONFIGURATION = ReliabilityComponentKind("configuration")
        @JvmField val AUDIT_LOG = ReliabilityComponentKind("audit-log")

        @JvmStatic
        fun of(code: String): ReliabilityComponentKind = builtIns.firstOrNull { it.code == code }
            ?: ReliabilityComponentKind(code)

        private val builtIns = listOf(DATABASE, OBJECT_STORAGE, SEARCH_INDEX, QUEUE, CONFIGURATION, AUDIT_LOG)
    }
}

class ReliabilityComponentScope private constructor(
    val kind: ReliabilityComponentKind,
    componentId: String,
    componentRevision: String,
    configurationDigest: String,
) {
    val componentId: String = ReliabilityContractSupport.code(
        componentId, "Reliability component id is invalid.",
    )
    val componentRevision: String = ReliabilityContractSupport.text(
        componentRevision,
        ReliabilityContractSupport.MAX_REVISION_BYTES,
        "Reliability component revision is invalid.",
    )
    val configurationDigest: String = ReliabilityContractSupport.sha256(
        configurationDigest, "Reliability component configuration digest is invalid.",
    )
    val scopeDigest: String = ReliabilityContractSupport.digest("flowweft-reliability-api-component-scope-v1")
        .text(kind.code)
        .text(this.componentId)
        .text(this.componentRevision)
        .text(this.configurationDigest)
        .finish()

    override fun equals(other: Any?): Boolean =
        this === other || other is ReliabilityComponentScope && scopeDigest == other.scopeDigest
    override fun hashCode(): Int = scopeDigest.hashCode()
    override fun toString(): String = "ReliabilityComponentScope(kind=$kind, <redacted>)"

    companion object {
        @JvmStatic
        fun of(
            kind: ReliabilityComponentKind,
            componentId: String,
            componentRevision: String,
            configurationDigest: String,
        ): ReliabilityComponentScope = ReliabilityComponentScope(
            kind, componentId, componentRevision, configurationDigest,
        )
    }
}

enum class ReliabilityEnvironmentKind { PRODUCTION, STAGING, RECOVERY, DRILL }

class ReliabilityEnvironmentRef private constructor(
    tenantId: String,
    environmentId: String,
    val kind: ReliabilityEnvironmentKind,
    val resource: ReliabilityResourceRef,
    topologyDigest: String,
) {
    val tenantId: String = ReliabilityContractSupport.text(
        tenantId, ReliabilityContractSupport.MAX_ID_BYTES, "Reliability environment tenant is invalid.",
    )
    val environmentId: String = ReliabilityContractSupport.code(
        environmentId, "Reliability environment id is invalid.",
    )
    val topologyDigest: String = ReliabilityContractSupport.sha256(
        topologyDigest, "Reliability environment topology digest is invalid.",
    )
    val bindingDigest: String

    init {
        require(resource.type == RESOURCE_TYPE && resource.id == this.environmentId) {
            "Reliability environment resource binding is invalid."
        }
        bindingDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-environment-v1")
            .text(this.tenantId)
            .text(this.environmentId)
            .text(kind.name)
            .text(resource.referenceDigest)
            .text(this.topologyDigest)
            .finish()
    }

    fun sameLogicalEnvironment(other: ReliabilityEnvironmentRef): Boolean =
        tenantId == other.tenantId && environmentId == other.environmentId

    override fun equals(other: Any?): Boolean =
        this === other || other is ReliabilityEnvironmentRef && bindingDigest == other.bindingDigest
    override fun hashCode(): Int = bindingDigest.hashCode()
    override fun toString(): String = "ReliabilityEnvironmentRef(kind=$kind, <redacted>)"

    companion object {
        const val RESOURCE_TYPE: String = "environment"

        @JvmStatic
        fun of(
            tenantId: String,
            environmentId: String,
            kind: ReliabilityEnvironmentKind,
            resource: ReliabilityResourceRef,
            topologyDigest: String,
        ): ReliabilityEnvironmentRef = ReliabilityEnvironmentRef(
            tenantId, environmentId, kind, resource, topologyDigest,
        )
    }
}

class ReliabilityRecoveryObjective private constructor(
    val scope: ReliabilityComponentScope,
    val maximumDataLossMillis: Long,
    val maximumRecoveryMillis: Long,
) {
    val objectiveDigest: String

    init {
        require(maximumDataLossMillis in 0L..MAX_OBJECTIVE_MILLIS &&
            maximumRecoveryMillis in 1L..MAX_OBJECTIVE_MILLIS
        ) { "Reliability RPO/RTO values are invalid." }
        objectiveDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-recovery-objective-v1")
            .text(scope.scopeDigest)
            .longValue(maximumDataLossMillis)
            .longValue(maximumRecoveryMillis)
            .finish()
    }

    override fun toString(): String =
        "ReliabilityRecoveryObjective(kind=${scope.kind}, rpoMillis=$maximumDataLossMillis, " +
            "rtoMillis=$maximumRecoveryMillis)"

    companion object {
        const val MAX_OBJECTIVE_MILLIS: Long = 365L * 24L * 60L * 60L * 1000L

        @JvmStatic
        fun of(
            scope: ReliabilityComponentScope,
            maximumDataLossMillis: Long,
            maximumRecoveryMillis: Long,
        ): ReliabilityRecoveryObjective = ReliabilityRecoveryObjective(
            scope, maximumDataLossMillis, maximumRecoveryMillis,
        )
    }
}

class ReliabilityRecoveryObjectiveSet private constructor(
    policyId: String,
    policyVersion: String,
    sourcePolicyDigest: String,
    val environment: ReliabilityEnvironmentRef,
    objectives: Collection<ReliabilityRecoveryObjective>,
    val effectiveFromEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val policyId: String = ReliabilityContractSupport.code(policyId, "Reliability recovery policy id is invalid.")
    val policyVersion: String = ReliabilityContractSupport.text(
        policyVersion, ReliabilityContractSupport.MAX_REVISION_BYTES, "Reliability recovery policy version is invalid.",
    )
    val sourcePolicyDigest: String = ReliabilityContractSupport.sha256(
        sourcePolicyDigest, "Reliability recovery source policy digest is invalid.",
    )
    val objectives: List<ReliabilityRecoveryObjective> = ReliabilityContractSupport.immutable(
        objectives.sortedBy { it.scope.scopeDigest },
        MAX_COMPONENTS,
        "Reliability recovery objectives are invalid.",
    )
    val topologyDigest: String
    val objectiveSetDigest: String

    init {
        require(this.objectives.isNotEmpty() &&
            this.objectives.map { it.scope.scopeDigest }.toSet().size == this.objectives.size
        ) { "Reliability recovery objectives must be non-empty and component-unique." }
        require(effectiveFromEpochMilli >= 0L && expiresAtEpochMilli > effectiveFromEpochMilli) {
            "Reliability recovery objective validity window is invalid."
        }
        val topologyWriter = ReliabilityContractSupport.digest("flowweft-reliability-api-recovery-topology-v1")
            .text(environment.bindingDigest)
            .integer(this.objectives.size)
        this.objectives.forEach { topologyWriter.text(it.scope.scopeDigest) }
        topologyDigest = topologyWriter.finish()
        val writer = ReliabilityContractSupport.digest("flowweft-reliability-api-recovery-objective-set-v1")
            .text(this.policyId)
            .text(this.policyVersion)
            .text(this.sourcePolicyDigest)
            .text(environment.bindingDigest)
            .text(topologyDigest)
            .longValue(effectiveFromEpochMilli)
            .longValue(expiresAtEpochMilli)
            .integer(this.objectives.size)
        this.objectives.forEach { writer.text(it.objectiveDigest) }
        objectiveSetDigest = writer.finish()
    }

    fun objectiveFor(scope: ReliabilityComponentScope): ReliabilityRecoveryObjective? =
        objectives.firstOrNull { it.scope.scopeDigest == scope.scopeDigest }

    override fun toString(): String = "ReliabilityRecoveryObjectiveSet(componentCount=${objectives.size}, <redacted>)"

    companion object {
        const val MAX_COMPONENTS: Int = 64

        @JvmStatic
        fun of(
            policyId: String,
            policyVersion: String,
            sourcePolicyDigest: String,
            environment: ReliabilityEnvironmentRef,
            objectives: Collection<ReliabilityRecoveryObjective>,
            effectiveFromEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): ReliabilityRecoveryObjectiveSet = ReliabilityRecoveryObjectiveSet(
            policyId,
            policyVersion,
            sourcePolicyDigest,
            environment,
            objectives,
            effectiveFromEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

/** Logical KMS/HSM reference only. There is intentionally no key material, ciphertext key or credential field. */
class ReliabilityKeyReference private constructor(
    providerId: String,
    keyId: String,
    keyVersion: String,
    referenceDigest: String,
) {
    val providerId: String = ReliabilityContractSupport.code(providerId, "Reliability key provider is invalid.")
    val keyId: String = ReliabilityContractSupport.opaque(keyId, "Reliability key id is invalid.")
    val keyVersion: String = ReliabilityContractSupport.text(
        keyVersion, ReliabilityContractSupport.MAX_REVISION_BYTES, "Reliability key version is invalid.",
    )
    val referenceDigest: String = ReliabilityContractSupport.sha256(
        referenceDigest, "Reliability key reference digest is invalid.",
    )
    val keyBindingDigest: String = ReliabilityContractSupport.digest("flowweft-reliability-api-key-reference-v1")
        .text(this.providerId)
        .text(this.keyId)
        .text(this.keyVersion)
        .text(this.referenceDigest)
        .finish()

    override fun toString(): String = "ReliabilityKeyReference(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            keyId: String,
            keyVersion: String,
            referenceDigest: String,
        ): ReliabilityKeyReference = ReliabilityKeyReference(providerId, keyId, keyVersion, referenceDigest)
    }
}

class ReliabilityEncryptionDescriptor private constructor(
    algorithm: String,
    val keyReference: ReliabilityKeyReference,
    envelopeMetadataDigest: String,
    ciphertextDigest: String,
) {
    val algorithm: String = ReliabilityContractSupport.code(algorithm, "Reliability encryption algorithm is invalid.")
    val envelopeMetadataDigest: String = ReliabilityContractSupport.sha256(
        envelopeMetadataDigest, "Reliability encryption metadata digest is invalid.",
    )
    val ciphertextDigest: String = ReliabilityContractSupport.sha256(
        ciphertextDigest, "Reliability ciphertext digest is invalid.",
    )
    val descriptorDigest: String = ReliabilityContractSupport.digest("flowweft-reliability-api-encryption-v1")
        .text(this.algorithm)
        .text(keyReference.keyBindingDigest)
        .text(this.envelopeMetadataDigest)
        .text(this.ciphertextDigest)
        .finish()

    init {
        require(!algorithm.equals("none", ignoreCase = true) &&
            !algorithm.equals("plaintext", ignoreCase = true) &&
            !algorithm.equals("unencrypted", ignoreCase = true)
        ) { "Reliability backup artifacts must use a real encryption algorithm." }
    }

    override fun toString(): String = "ReliabilityEncryptionDescriptor(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            algorithm: String,
            keyReference: ReliabilityKeyReference,
            envelopeMetadataDigest: String,
            ciphertextDigest: String,
        ): ReliabilityEncryptionDescriptor = ReliabilityEncryptionDescriptor(
            algorithm, keyReference, envelopeMetadataDigest, ciphertextDigest,
        )
    }
}

class ReliabilityConsistentCut private constructor(
    cutId: String,
    val sourceEnvironment: ReliabilityEnvironmentRef,
    topologyDigest: String,
    val cutAtEpochMilli: Long,
) {
    val cutId: String = ReliabilityContractSupport.opaque(cutId, "Reliability consistent-cut id is invalid.")
    val topologyDigest: String = ReliabilityContractSupport.sha256(
        topologyDigest, "Reliability consistent-cut topology digest is invalid.",
    )
    val cutDigest: String

    init {
        require(cutAtEpochMilli >= 0L) { "Reliability consistent-cut time is invalid." }
        cutDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-consistent-cut-v1")
            .text(this.cutId)
            .text(sourceEnvironment.bindingDigest)
            .text(this.topologyDigest)
            .longValue(cutAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityConsistentCut(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            cutId: String,
            sourceEnvironment: ReliabilityEnvironmentRef,
            topologyDigest: String,
            cutAtEpochMilli: Long,
        ): ReliabilityConsistentCut = ReliabilityConsistentCut(
            cutId, sourceEnvironment, topologyDigest, cutAtEpochMilli,
        )
    }
}

/** Immutable encrypted snapshot for one exact component at the shared consistent cut. */
class ReliabilityBackupArtifact private constructor(
    val scope: ReliabilityComponentScope,
    snapshotReference: String,
    providerId: String,
    providerRevision: String,
    consistentCutDigest: String,
    val recoveryPointEpochMilli: Long,
    val capturedAtEpochMilli: Long,
    val byteCount: Long,
    contentDigest: String,
    val encryption: ReliabilityEncryptionDescriptor,
) {
    val snapshotReference: String = ReliabilityContractSupport.opaque(
        snapshotReference, "Reliability backup snapshot reference is invalid.",
    )
    val providerId: String = ReliabilityContractSupport.code(
        providerId, "Reliability backup artifact provider is invalid.",
    )
    val providerRevision: String = ReliabilityContractSupport.text(
        providerRevision,
        ReliabilityContractSupport.MAX_REVISION_BYTES,
        "Reliability backup artifact provider revision is invalid.",
    )
    val consistentCutDigest: String = ReliabilityContractSupport.sha256(
        consistentCutDigest, "Reliability backup artifact cut digest is invalid.",
    )
    val contentDigest: String = ReliabilityContractSupport.sha256(
        contentDigest, "Reliability backup artifact content digest is invalid.",
    )
    val artifactDigest: String

    init {
        require(recoveryPointEpochMilli >= 0L && capturedAtEpochMilli >= recoveryPointEpochMilli && byteCount >= 0L) {
            "Reliability backup artifact recovery point or size is invalid."
        }
        artifactDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-backup-artifact-v1")
            .text(scope.scopeDigest)
            .text(this.snapshotReference)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.consistentCutDigest)
            .longValue(recoveryPointEpochMilli)
            .longValue(capturedAtEpochMilli)
            .longValue(byteCount)
            .text(this.contentDigest)
            .text(encryption.descriptorDigest)
            .finish()
    }

    override fun toString(): String = "ReliabilityBackupArtifact(kind=${scope.kind}, <redacted>)"

    companion object {
        @JvmStatic
        fun immutableEncrypted(
            scope: ReliabilityComponentScope,
            snapshotReference: String,
            providerId: String,
            providerRevision: String,
            consistentCutDigest: String,
            recoveryPointEpochMilli: Long,
            capturedAtEpochMilli: Long,
            byteCount: Long,
            contentDigest: String,
            encryption: ReliabilityEncryptionDescriptor,
        ): ReliabilityBackupArtifact = ReliabilityBackupArtifact(
            scope,
            snapshotReference,
            providerId,
            providerRevision,
            consistentCutDigest,
            recoveryPointEpochMilli,
            capturedAtEpochMilli,
            byteCount,
            contentDigest,
            encryption,
        )
    }
}

class ReliabilityBackupManifestContent private constructor(
    manifestId: String,
    manifestVersion: String,
    val objectives: ReliabilityRecoveryObjectiveSet,
    val consistentCut: ReliabilityConsistentCut,
    artifacts: Collection<ReliabilityBackupArtifact>,
    val createdAtEpochMilli: Long,
) {
    val manifestId: String = ReliabilityContractSupport.opaque(manifestId, "Reliability manifest id is invalid.")
    val manifestVersion: String = ReliabilityContractSupport.text(
        manifestVersion, ReliabilityContractSupport.MAX_REVISION_BYTES, "Reliability manifest version is invalid.",
    )
    val sourceEnvironment: ReliabilityEnvironmentRef = objectives.environment
    val artifacts: List<ReliabilityBackupArtifact> = ReliabilityContractSupport.immutable(
        artifacts.sortedBy { it.scope.scopeDigest },
        ReliabilityRecoveryObjectiveSet.MAX_COMPONENTS,
        "Reliability backup artifacts are invalid.",
    )
    val contentDigest: String

    init {
        require(consistentCut.sourceEnvironment == sourceEnvironment &&
            consistentCut.topologyDigest == objectives.topologyDigest
        ) { "Reliability manifest cut is not bound to the exact source topology." }
        require(this.artifacts.isNotEmpty() &&
            this.artifacts.map { it.scope.scopeDigest }.toSet().size == this.artifacts.size &&
            this.artifacts.map { it.scope.scopeDigest }.toSet() ==
            objectives.objectives.map { it.scope.scopeDigest }.toSet()
        ) { "Reliability manifest must contain every cross-component objective exactly once." }
        require(this.artifacts.all { artifact ->
            artifact.consistentCutDigest == consistentCut.cutDigest &&
                artifact.recoveryPointEpochMilli <= consistentCut.cutAtEpochMilli &&
                artifact.capturedAtEpochMilli >= consistentCut.cutAtEpochMilli &&
                consistentCut.cutAtEpochMilli - artifact.recoveryPointEpochMilli <=
                requireNotNull(objectives.objectiveFor(artifact.scope)).maximumDataLossMillis
        }) { "Reliability manifest artifacts violate the exact consistent cut or a component RPO." }
        require(createdAtEpochMilli >= this.artifacts.maxOf { it.capturedAtEpochMilli }) {
            "Reliability manifest predates one of its artifacts."
        }
        val writer = ReliabilityContractSupport.digest("flowweft-reliability-api-manifest-content-v1")
            .text(this.manifestId)
            .text(this.manifestVersion)
            .text(sourceEnvironment.bindingDigest)
            .text(objectives.objectiveSetDigest)
            .text(consistentCut.cutDigest)
            .longValue(createdAtEpochMilli)
            .integer(this.artifacts.size)
        this.artifacts.forEach { writer.text(it.artifactDigest) }
        contentDigest = writer.finish()
    }

    override fun toString(): String = "ReliabilityBackupManifestContent(componentCount=${artifacts.size}, <redacted>)"

    companion object {
        @JvmStatic
        fun of(
            manifestId: String,
            manifestVersion: String,
            objectives: ReliabilityRecoveryObjectiveSet,
            consistentCut: ReliabilityConsistentCut,
            artifacts: Collection<ReliabilityBackupArtifact>,
            createdAtEpochMilli: Long,
        ): ReliabilityBackupManifestContent = ReliabilityBackupManifestContent(
            manifestId, manifestVersion, objectives, consistentCut, artifacts, createdAtEpochMilli,
        )
    }
}

/** Cryptographic immutable-manifest seal; it stores a verification key reference, never key material. */
class ReliabilityImmutableManifestSeal private constructor(
    contentDigest: String,
    sealRevision: String,
    integrityAlgorithm: String,
    val verificationKeyReference: ReliabilityKeyReference,
    signatureDigest: String,
    val sealedAtEpochMilli: Long,
) {
    val contentDigest: String = ReliabilityContractSupport.sha256(
        contentDigest, "Reliability manifest seal content digest is invalid.",
    )
    val sealRevision: String = ReliabilityContractSupport.text(
        sealRevision, ReliabilityContractSupport.MAX_REVISION_BYTES, "Reliability manifest seal revision is invalid.",
    )
    val integrityAlgorithm: String = ReliabilityContractSupport.code(
        integrityAlgorithm, "Reliability manifest integrity algorithm is invalid.",
    )
    val signatureDigest: String = ReliabilityContractSupport.sha256(
        signatureDigest, "Reliability manifest signature digest is invalid.",
    )
    val sealDigest: String

    init {
        require(sealedAtEpochMilli >= 0L) { "Reliability manifest seal time is invalid." }
        require(!integrityAlgorithm.equals("none", ignoreCase = true) &&
            !integrityAlgorithm.equals("unsigned", ignoreCase = true)
        ) { "Reliability immutable manifest requires a real integrity algorithm." }
        sealDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-manifest-seal-v1")
            .text(this.contentDigest)
            .text(this.sealRevision)
            .text(this.integrityAlgorithm)
            .text(verificationKeyReference.keyBindingDigest)
            .text(this.signatureDigest)
            .longValue(sealedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityImmutableManifestSeal(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            content: ReliabilityBackupManifestContent,
            sealRevision: String,
            integrityAlgorithm: String,
            verificationKeyReference: ReliabilityKeyReference,
            signatureDigest: String,
            sealedAtEpochMilli: Long,
        ): ReliabilityImmutableManifestSeal {
            require(sealedAtEpochMilli >= content.createdAtEpochMilli) {
                "Reliability manifest cannot be sealed before it is complete."
            }
            return ReliabilityImmutableManifestSeal(
                content.contentDigest,
                sealRevision,
                integrityAlgorithm,
                verificationKeyReference,
                signatureDigest,
                sealedAtEpochMilli,
            )
        }
    }
}

class ReliabilityBackupManifest private constructor(
    val content: ReliabilityBackupManifestContent,
    val seal: ReliabilityImmutableManifestSeal,
) {
    val manifestId: String = content.manifestId
    val manifestDigest: String
    val resource: ReliabilityResourceRef

    init {
        require(seal.contentDigest == content.contentDigest && seal.sealedAtEpochMilli >= content.createdAtEpochMilli) {
            "Reliability manifest seal is not bound to its immutable content."
        }
        manifestDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-backup-manifest-v1")
            .text(content.contentDigest)
            .text(seal.sealDigest)
            .finish()
        resource = ReliabilityResourceRef.of(
            MANIFEST_RESOURCE_TYPE,
            manifestId,
            content.manifestVersion,
            manifestDigest,
        )
    }

    override fun toString(): String = "ReliabilityBackupManifest(<redacted>)"

    companion object {
        const val MANIFEST_RESOURCE_TYPE: String = "backup-manifest"

        @JvmStatic
        fun of(
            content: ReliabilityBackupManifestContent,
            seal: ReliabilityImmutableManifestSeal,
        ): ReliabilityBackupManifest = ReliabilityBackupManifest(content, seal)
    }
}

/** Fresh evidence that the exact restore target has no pre-existing application state. */
class ReliabilityCleanTargetProof private constructor(
    proofId: String,
    val target: ReliabilityEnvironmentRef,
    verifierId: String,
    verifierRevision: String,
    evidenceDigest: String,
    val observedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val proofId: String = ReliabilityContractSupport.opaque(proofId, "Reliability clean-target proof id is invalid.")
    val verifierId: String = ReliabilityContractSupport.code(
        verifierId, "Reliability clean-target verifier is invalid.",
    )
    val verifierRevision: String = ReliabilityContractSupport.text(
        verifierRevision,
        ReliabilityContractSupport.MAX_REVISION_BYTES,
        "Reliability clean-target verifier revision is invalid.",
    )
    val evidenceDigest: String = ReliabilityContractSupport.sha256(
        evidenceDigest, "Reliability clean-target evidence digest is invalid.",
    )
    val proofDigest: String

    init {
        require(observedAtEpochMilli >= 0L && expiresAtEpochMilli > observedAtEpochMilli &&
            expiresAtEpochMilli - observedAtEpochMilli <= MAX_PROOF_TTL_MILLIS
        ) { "Reliability clean-target proof window is invalid." }
        proofDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-clean-target-proof-v1")
            .text(this.proofId)
            .text(target.bindingDigest)
            .text(this.verifierId)
            .text(this.verifierRevision)
            .text(this.evidenceDigest)
            .longValue(observedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .finish()
    }

    fun isFreshAt(operationStartEpochMilli: Long): Boolean =
        observedAtEpochMilli <= operationStartEpochMilli && operationStartEpochMilli < expiresAtEpochMilli
    override fun toString(): String = "ReliabilityCleanTargetProof(<redacted>)"

    companion object {
        const val MAX_PROOF_TTL_MILLIS: Long = 15L * 60L * 1000L

        @JvmStatic
        fun clean(
            proofId: String,
            target: ReliabilityEnvironmentRef,
            verifierId: String,
            verifierRevision: String,
            evidenceDigest: String,
            observedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): ReliabilityCleanTargetProof = ReliabilityCleanTargetProof(
            proofId,
            target,
            verifierId,
            verifierRevision,
            evidenceDigest,
            observedAtEpochMilli,
            expiresAtEpochMilli,
        )
    }
}
