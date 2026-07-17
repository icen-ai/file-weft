package ai.icen.fw.reliability.api

import java.util.concurrent.CompletionStage

enum class ReliabilityCapability {
    CREATE_CONSISTENT_BACKUP,
    VERIFY_IMMUTABLE_MANIFEST,
    RESTORE_CLEAN_TARGET,
    EXACT_OUTCOME_RECONCILIATION,
    ISOLATED_RECOVERY_DRILL,
    DOCTOR_EVIDENCE,
}

class ReliabilityCapabilityRequest private constructor(
    val context: ReliabilityCallContext,
    required: Collection<ReliabilityCapability>,
    requiredComponentKinds: Collection<ReliabilityComponentKind>,
) {
    val required: List<ReliabilityCapability> = ReliabilityContractSupport.immutable(
        required.toSet().sortedBy { it.name },
        ReliabilityCapability.values().size,
        "Reliability required capabilities are invalid.",
    )
    val requiredComponentKinds: List<ReliabilityComponentKind> = ReliabilityContractSupport.immutable(
        requiredComponentKinds.toSet().sortedBy { it.code },
        ReliabilityRecoveryObjectiveSet.MAX_COMPONENTS,
        "Reliability required component kinds are invalid.",
    )
    val requestDigest: String

    init {
        require(context.purpose == ReliabilityPurpose.DISCOVER_CAPABILITIES &&
            context.action == ReliabilityAction.DISCOVER_CAPABILITIES
        ) { "Reliability capability discovery requires its exact purpose and action." }
        require(this.required.size == required.size &&
            this.requiredComponentKinds.size == requiredComponentKinds.size
        ) { "Reliability capability requirements must be unique." }
        val writer = ReliabilityContractSupport.digest("flowweft-reliability-api-capability-request-v1")
            .text(context.contextDigest)
            .integer(this.required.size)
        this.required.forEach { writer.text(it.name) }
        writer.integer(this.requiredComponentKinds.size)
        this.requiredComponentKinds.forEach { writer.text(it.code) }
        requestDigest = writer.finish()
    }

    override fun toString(): String = "ReliabilityCapabilityRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: ReliabilityCallContext,
            required: Collection<ReliabilityCapability>,
            requiredComponentKinds: Collection<ReliabilityComponentKind>,
        ): ReliabilityCapabilityRequest = ReliabilityCapabilityRequest(
            context, required, requiredComponentKinds,
        )
    }
}

/** Safe descriptor: configuration is a digest and no endpoint, credential or key material exists. */
class ReliabilityProviderDescriptor private constructor(
    providerId: String,
    providerRevision: String,
    contractVersion: String,
    configurationDigest: String,
    supportedCapabilities: Collection<ReliabilityCapability>,
    supportedComponentKinds: Collection<ReliabilityComponentKind>,
    val maximumComponentsPerManifest: Int,
    val observedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val providerId: String = ReliabilityContractSupport.code(providerId, "Reliability provider id is invalid.")
    val providerRevision: String = ReliabilityContractSupport.text(
        providerRevision, ReliabilityContractSupport.MAX_REVISION_BYTES, "Reliability provider revision is invalid.",
    )
    val contractVersion: String = ReliabilityContractSupport.code(
        contractVersion, "Reliability provider contract version is invalid.",
    )
    val configurationDigest: String = ReliabilityContractSupport.sha256(
        configurationDigest, "Reliability provider configuration digest is invalid.",
    )
    val supportedCapabilities: List<ReliabilityCapability> = ReliabilityContractSupport.immutable(
        supportedCapabilities.toSet().sortedBy { it.name },
        ReliabilityCapability.values().size,
        "Reliability provider capabilities are invalid.",
    )
    val supportedComponentKinds: List<ReliabilityComponentKind> = ReliabilityContractSupport.immutable(
        supportedComponentKinds.toSet().sortedBy { it.code },
        ReliabilityRecoveryObjectiveSet.MAX_COMPONENTS,
        "Reliability provider component kinds are invalid.",
    )
    val descriptorDigest: String

    init {
        require(this.supportedCapabilities.size == supportedCapabilities.size &&
            this.supportedComponentKinds.size == supportedComponentKinds.size
        ) { "Reliability provider capabilities and component kinds must be unique." }
        require(maximumComponentsPerManifest in 1..ReliabilityRecoveryObjectiveSet.MAX_COMPONENTS) {
            "Reliability provider manifest component limit is invalid."
        }
        require(observedAtEpochMilli >= 0L && expiresAtEpochMilli > observedAtEpochMilli) {
            "Reliability provider descriptor lifetime is invalid."
        }
        val writer = ReliabilityContractSupport.digest("flowweft-reliability-api-provider-descriptor-v1")
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.contractVersion)
            .text(this.configurationDigest)
            .integer(maximumComponentsPerManifest)
            .longValue(observedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .integer(this.supportedCapabilities.size)
        this.supportedCapabilities.forEach { writer.text(it.name) }
        writer.integer(this.supportedComponentKinds.size)
        this.supportedComponentKinds.forEach { writer.text(it.code) }
        descriptorDigest = writer.finish()
    }

    fun supports(capability: ReliabilityCapability): Boolean = supportedCapabilities.contains(capability)
    fun supports(kind: ReliabilityComponentKind): Boolean = supportedComponentKinds.contains(kind)
    fun isCurrent(atEpochMilli: Long): Boolean =
        atEpochMilli >= observedAtEpochMilli && atEpochMilli < expiresAtEpochMilli

    override fun toString(): String = "ReliabilityProviderDescriptor(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            providerRevision: String,
            contractVersion: String,
            configurationDigest: String,
            supportedCapabilities: Collection<ReliabilityCapability>,
            supportedComponentKinds: Collection<ReliabilityComponentKind>,
            maximumComponentsPerManifest: Int,
            observedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): ReliabilityProviderDescriptor = ReliabilityProviderDescriptor(
            providerId,
            providerRevision,
            contractVersion,
            configurationDigest,
            supportedCapabilities,
            supportedComponentKinds,
            maximumComponentsPerManifest,
            observedAtEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

enum class ReliabilityCapabilityStatus { AVAILABLE, UNSUPPORTED, UNAVAILABLE }

class ReliabilityCapabilityResult private constructor(
    request: ReliabilityCapabilityRequest,
    val status: ReliabilityCapabilityStatus,
    val descriptor: ReliabilityProviderDescriptor?,
    val failure: ReliabilityFailure?,
    val observedAtEpochMilli: Long,
) {
    val requestDigest: String = request.requestDigest
    val resultDigest: String

    init {
        require(observedAtEpochMilli >= request.context.requestedAtEpochMilli &&
            observedAtEpochMilli < request.context.deadlineEpochMilli
        ) {
            "Reliability capability result is outside its call window."
        }
        when (status) {
            ReliabilityCapabilityStatus.AVAILABLE -> require(
                descriptor != null && descriptor.isCurrent(observedAtEpochMilli) && failure == null &&
                    request.required.all { descriptor.supports(it) } &&
                    request.requiredComponentKinds.all { descriptor.supports(it) },
            ) { "Available reliability capability result does not satisfy the exact required set." }
            ReliabilityCapabilityStatus.UNSUPPORTED -> require(
                descriptor == null && failure?.classification == ReliabilityFailureClass.UNSUPPORTED,
            ) { "Unsupported reliability capability result requires an explicit unsupported failure." }
            ReliabilityCapabilityStatus.UNAVAILABLE -> require(
                descriptor == null && failure != null &&
                    failure.classification != ReliabilityFailureClass.UNSUPPORTED,
            ) { "Unavailable reliability capability result requires a closed failure." }
        }
        resultDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-capability-result-v1")
            .text(request.requestDigest)
            .text(status.name)
            .optionalText(descriptor?.descriptorDigest)
            .optionalText(failure?.failureDigest)
            .longValue(observedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityCapabilityResult(status=$status, <redacted>)"

    companion object {
        @JvmStatic
        fun available(
            request: ReliabilityCapabilityRequest,
            descriptor: ReliabilityProviderDescriptor,
            observedAtEpochMilli: Long,
        ): ReliabilityCapabilityResult = ReliabilityCapabilityResult(
            request, ReliabilityCapabilityStatus.AVAILABLE, descriptor, null, observedAtEpochMilli,
        )

        @JvmStatic
        fun failure(
            request: ReliabilityCapabilityRequest,
            status: ReliabilityCapabilityStatus,
            failure: ReliabilityFailure,
            observedAtEpochMilli: Long,
        ): ReliabilityCapabilityResult {
            require(status != ReliabilityCapabilityStatus.AVAILABLE) {
                "Reliability capability failure cannot use available status."
            }
            return ReliabilityCapabilityResult(request, status, null, failure, observedAtEpochMilli)
        }
    }
}

/** Pure deterministic evaluator; implementations must not read ambient time or perform I/O. */
fun interface ReliabilitySloEvaluator {
    fun evaluate(request: ReliabilitySloEvaluationRequest): ReliabilityErrorBudgetEvaluation

    companion object {
        @JvmField
        val STANDARD: ReliabilitySloEvaluator = ReliabilitySloEvaluator { request ->
            ReliabilityErrorBudgetEvaluation.evaluate(request)
        }
    }
}

/**
 * Provider-neutral asynchronous boundary. Implementations revalidate fresh authorization and CAS,
 * deduplicate the digest-only idempotency key, contain vendor failures, and never call a provider
 * inside an unrelated host database transaction.
 */
interface ReliabilityProviderSpi {
    fun capabilities(request: ReliabilityCapabilityRequest): CompletionStage<ReliabilityCapabilityResult>

    fun createBackup(
        request: ReliabilityBackupCreateRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityBackupCreationReceipt>>

    fun verifyBackup(
        request: ReliabilityBackupVerifyRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityManifestVerificationReceipt>>

    fun restore(
        request: ReliabilityRestoreRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityRestoreReceipt>>

    /** Read only: query exactly [ReliabilityReconciliationRequest.outcomeUnknown], never re-execute. */
    fun reconcile(
        request: ReliabilityReconciliationRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityReconciliationReceipt>>

    fun runDrill(
        request: ReliabilityDrillRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityDrillReport>>

    fun doctor(
        request: ReliabilityDoctorRequest,
    ): CompletionStage<ReliabilityProviderResult<ReliabilityDoctorReport>>
}
