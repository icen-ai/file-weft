package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowInstanceRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.api.WorkflowWorkItemRef
import java.util.concurrent.CompletionStage

/** Versioned host-selected policy. It contains no certificate, key, or provider credential. */
class WorkflowAttestationProfileRef private constructor(
    providerId: String,
    profileId: String,
    version: String,
    digest: String,
) {
    val providerId: String = WorkflowSpiContractSupport.requireMachineCode(
        providerId, "Workflow attestation provider is invalid.",
    )
    val profileId: String = WorkflowSpiContractSupport.requireMachineCode(
        profileId, "Workflow attestation profile is invalid.",
    )
    val version: String = WorkflowSpiContractSupport.requireText(
        version, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow attestation profile version is invalid.",
    )
    val digest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        digest, "Workflow attestation profile digest is invalid.",
    )

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowAttestationProfileRef && providerId == other.providerId && profileId == other.profileId &&
        version == other.version && digest == other.digest

    override fun hashCode(): Int {
        var result = providerId.hashCode()
        result = 31 * result + profileId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + digest.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowAttestationProfileRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(providerId: String, profileId: String, version: String, digest: String): WorkflowAttestationProfileRef =
            WorkflowAttestationProfileRef(providerId, profileId, version, digest)
    }
}

/** Exact decision statement to attest. It deliberately carries only digests and trusted references. */
class WorkflowAttestationStatement private constructor(
    val definition: WorkflowDefinitionRef,
    val instance: WorkflowInstanceRef,
    val workItem: WorkflowWorkItemRef?,
    val subject: WorkflowSubjectSnapshot,
    val actor: WorkflowPrincipalRef,
    decisionDigest: String,
    idempotencyKey: String,
    challengeDigest: String,
) {
    val decisionDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        decisionDigest, "Workflow attestation decision digest is invalid.",
    )
    val idempotencyKey: String = WorkflowSpiContractSupport.requireText(
        idempotencyKey, WorkflowSpiContractSupport.MAX_ID_UTF8_BYTES, "Workflow attestation idempotency key is invalid.",
    )
    val challengeDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        challengeDigest, "Workflow attestation challenge digest is invalid.",
    )
    val statementDigest: String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-statement-v1")
        .text(definition.key)
        .text(definition.version)
        .text(definition.digest)
        .text(instance.id)
        .longValue(instance.expectedVersion)
        .optionalText(workItem?.id)
        .longValue(workItem?.expectedVersion ?: -1L)
        .text(subject.ref.type)
        .text(subject.ref.id)
        .text(subject.revision)
        .text(subject.digest)
        .text(actor.type)
        .text(actor.id)
        .text(this.decisionDigest)
        .text(this.idempotencyKey)
        .text(this.challengeDigest)
        .finish()

    override fun toString(): String = "WorkflowAttestationStatement(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            definition: WorkflowDefinitionRef,
            instance: WorkflowInstanceRef,
            workItem: WorkflowWorkItemRef?,
            subject: WorkflowSubjectSnapshot,
            actor: WorkflowPrincipalRef,
            decisionDigest: String,
            idempotencyKey: String,
            challengeDigest: String,
        ): WorkflowAttestationStatement = WorkflowAttestationStatement(
            definition, instance, workItem, subject, actor, decisionDigest, idempotencyKey, challengeDigest,
        )
    }
}

/** Reference to immutable evidence in host-controlled storage, never inline key or certificate material. */
class WorkflowAttestationArtifactRef private constructor(
    artifactId: String,
    mediaType: String,
    digest: String,
    val sizeBytes: Long,
) {
    val artifactId: String = WorkflowSpiContractSupport.requireOpaqueReference(
        artifactId, "Workflow attestation artifact identifier is invalid.",
    )
    val mediaType: String = WorkflowSpiContractSupport.requireMachineCode(
        mediaType, "Workflow attestation artifact media type is invalid.",
    )
    val digest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        digest, "Workflow attestation artifact digest is invalid.",
    )

    init {
        require(sizeBytes in 1L..WorkflowSpiContractSupport.MAX_PAYLOAD_BYTES.toLong()) {
            "Workflow attestation artifact size is invalid."
        }
    }

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowAttestationArtifactRef && artifactId == other.artifactId && mediaType == other.mediaType &&
        digest == other.digest && sizeBytes == other.sizeBytes

    override fun hashCode(): Int {
        var result = artifactId.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + digest.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowAttestationArtifactRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(artifactId: String, mediaType: String, digest: String, sizeBytes: Long): WorkflowAttestationArtifactRef =
            WorkflowAttestationArtifactRef(artifactId, mediaType, digest, sizeBytes)
    }
}

class WorkflowAttestationEvidence private constructor(
    val artifact: WorkflowAttestationArtifactRef,
    val attestor: WorkflowPrincipalRef,
    providerEvidenceRef: String,
    val attestedAtEpochMilli: Long,
) {
    val providerEvidenceRef: String = WorkflowSpiContractSupport.requireOpaqueReference(
        providerEvidenceRef, "Workflow attestation provider evidence reference is invalid.",
    )
    val evidenceDigest: String

    init {
        require(attestedAtEpochMilli >= 0L) { "Workflow attestation time is invalid." }
        evidenceDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-evidence-v1")
            .text(artifact.artifactId)
            .text(artifact.mediaType)
            .text(artifact.digest)
            .longValue(artifact.sizeBytes)
            .text(attestor.type)
            .text(attestor.id)
            .text(this.providerEvidenceRef)
            .longValue(attestedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "WorkflowAttestationEvidence(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            artifact: WorkflowAttestationArtifactRef,
            attestor: WorkflowPrincipalRef,
            providerEvidenceRef: String,
            attestedAtEpochMilli: Long,
        ): WorkflowAttestationEvidence = WorkflowAttestationEvidence(
            artifact, attestor, providerEvidenceRef, attestedAtEpochMilli,
        )
    }
}

class WorkflowElectronicSignatureRequest private constructor(
    val context: WorkflowProviderCallContext,
    val profile: WorkflowAttestationProfileRef,
    val statement: WorkflowAttestationStatement,
) {
    val requestDigest: String

    init {
        require(context.providerId == profile.providerId) {
            "Workflow electronic-signature profile does not match the provider context."
        }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-electronic-signature-request-v1")
            .text(context.contextDigest)
            .text(profile.providerId)
            .text(profile.profileId)
            .text(profile.version)
            .text(profile.digest)
            .text(statement.statementDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowElectronicSignatureRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            profile: WorkflowAttestationProfileRef,
            statement: WorkflowAttestationStatement,
        ): WorkflowElectronicSignatureRequest = WorkflowElectronicSignatureRequest(context, profile, statement)
    }
}

class WorkflowWitnessRequest private constructor(
    val context: WorkflowProviderCallContext,
    val profile: WorkflowAttestationProfileRef,
    val statement: WorkflowAttestationStatement,
) {
    val requestDigest: String

    init {
        require(context.providerId == profile.providerId) {
            "Workflow witness profile does not match the provider context."
        }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-witness-request-v1")
            .text(context.contextDigest)
            .text(profile.providerId)
            .text(profile.profileId)
            .text(profile.version)
            .text(profile.digest)
            .text(statement.statementDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowWitnessRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            profile: WorkflowAttestationProfileRef,
            statement: WorkflowAttestationStatement,
        ): WorkflowWitnessRequest = WorkflowWitnessRequest(context, profile, statement)
    }
}

class WorkflowElectronicSignatureResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val evidence: WorkflowAttestationEvidence?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (evidence != null)) {
            "Workflow electronic-signature result content does not match its outcome."
        }
    }

    override fun toString(): String = "WorkflowElectronicSignatureResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowElectronicSignatureRequest,
            evidence: WorkflowAttestationEvidence,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowElectronicSignatureResult {
            require(evidence.attestor == request.statement.actor) {
                "Workflow electronic-signature attestor must match the exact decision actor."
            }
            return WorkflowElectronicSignatureResult(
                WorkflowProviderReceipt.success(
                    request.context, request.requestDigest, evidence.evidenceDigest,
                    completedAtEpochMilli, expiresAtEpochMilli,
                ),
                evidence,
            )
        }

        @JvmStatic
        fun failure(
            request: WorkflowElectronicSignatureRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowElectronicSignatureResult = WorkflowElectronicSignatureResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                outcome,
                WorkflowSpiContractSupport.failureDigest(
                    "flowweft-workflow-spi-electronic-signature-failure-v1", failure,
                ),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

class WorkflowWitnessResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val evidence: WorkflowAttestationEvidence?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (evidence != null)) {
            "Workflow witness result content does not match its outcome."
        }
    }

    override fun toString(): String = "WorkflowWitnessResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowWitnessRequest,
            evidence: WorkflowAttestationEvidence,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowWitnessResult = WorkflowWitnessResult(
            WorkflowProviderReceipt.success(
                request.context, request.requestDigest, evidence.evidenceDigest, completedAtEpochMilli, expiresAtEpochMilli,
            ),
            evidence,
        )

        @JvmStatic
        fun failure(
            request: WorkflowWitnessRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowWitnessResult = WorkflowWitnessResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                outcome,
                WorkflowSpiContractSupport.failureDigest("flowweft-workflow-spi-witness-failure-v1", failure),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

fun interface WorkflowElectronicSignatureProvider {
    fun sign(request: WorkflowElectronicSignatureRequest): CompletionStage<WorkflowElectronicSignatureResult>
}

fun interface WorkflowWitnessProvider {
    fun witness(request: WorkflowWitnessRequest): CompletionStage<WorkflowWitnessResult>
}
