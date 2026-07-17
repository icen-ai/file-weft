package ai.icen.fw.workflow.spi

import java.util.concurrent.CompletionStage

/** Value-only capability vocabulary; it contains no endpoint, credential, or account value. */
class WorkflowAttestationCapabilityCode private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(
        code, "Workflow attestation capability code is invalid.",
    )

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowAttestationCapabilityCode && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowAttestationCapabilityCode(<redacted>)"

    companion object {
        @JvmField val ELECTRONIC_SIGNATURE = WorkflowAttestationCapabilityCode("electronic-signature")
        @JvmField val WITNESS = WorkflowAttestationCapabilityCode("witness")
        @JvmField val ASYNCHRONOUS_COMPLETION = WorkflowAttestationCapabilityCode("asynchronous-completion")
        @JvmField val RECONCILIATION_BY_OPERATION = WorkflowAttestationCapabilityCode("reconciliation-by-operation")
        @JvmField val RECONCILIATION_BY_REQUEST_DIGEST =
            WorkflowAttestationCapabilityCode("reconciliation-by-request-digest")
        @JvmField val CANCELLATION = WorkflowAttestationCapabilityCode("cancellation")
        @JvmField val DIAGNOSTICS = WorkflowAttestationCapabilityCode("diagnostics")

        @JvmStatic
        fun of(code: String): WorkflowAttestationCapabilityCode = when (code) {
            ELECTRONIC_SIGNATURE.code -> ELECTRONIC_SIGNATURE
            WITNESS.code -> WITNESS
            ASYNCHRONOUS_COMPLETION.code -> ASYNCHRONOUS_COMPLETION
            RECONCILIATION_BY_OPERATION.code -> RECONCILIATION_BY_OPERATION
            RECONCILIATION_BY_REQUEST_DIGEST.code -> RECONCILIATION_BY_REQUEST_DIGEST
            CANCELLATION.code -> CANCELLATION
            DIAGNOSTICS.code -> DIAGNOSTICS
            else -> WorkflowAttestationCapabilityCode(code)
        }
    }
}

class WorkflowAttestationCapabilityRequest private constructor(
    val context: WorkflowProviderCallContext,
    val profile: WorkflowAttestationProfileRef,
    requiredCapabilities: Collection<WorkflowAttestationCapabilityCode>,
) {
    val requiredCapabilities: List<WorkflowAttestationCapabilityCode> = WorkflowSpiContractSupport.immutableList(
        requiredCapabilities.sortedBy { capability -> capability.code },
        WorkflowSpiContractSupport.MAX_ITEMS,
        "Workflow attestation required capabilities exceed the limit.",
    )
    val requestDigest: String

    init {
        require(context.providerId == profile.providerId) {
            "Workflow attestation capability profile does not match its provider context."
        }
        require(this.requiredCapabilities.isNotEmpty() &&
            this.requiredCapabilities.toSet().size == this.requiredCapabilities.size
        ) { "Workflow attestation required capabilities must be non-empty and unique." }
        require(this.requiredCapabilities.size <= context.maximumItems) {
            "Workflow attestation required capabilities exceed the invocation item limit."
        }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-capability-request-v1")
            .text(context.contextDigest)
            .text(profile.providerId)
            .text(profile.profileId)
            .text(profile.version)
            .text(profile.digest)
            .integer(this.requiredCapabilities.size)
            .also { writer -> this.requiredCapabilities.forEach { capability -> writer.text(capability.code) } }
            .finish()
    }

    override fun toString(): String = "WorkflowAttestationCapabilityRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            profile: WorkflowAttestationProfileRef,
            requiredCapabilities: Collection<WorkflowAttestationCapabilityCode>,
        ): WorkflowAttestationCapabilityRequest = WorkflowAttestationCapabilityRequest(
            context, profile, requiredCapabilities,
        )
    }
}

/** Immutable provider capability observation for one exact profile and provider revision. */
class WorkflowAttestationCapabilitySnapshot private constructor(
    val profile: WorkflowAttestationProfileRef,
    providerRevision: String,
    supportedCapabilities: Collection<WorkflowAttestationCapabilityCode>,
    val maximumPendingOperations: Int,
    val observedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val providerId: String = profile.providerId
    val providerRevision: String = WorkflowSpiContractSupport.requireText(
        providerRevision,
        WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES,
        "Workflow attestation capability provider revision is invalid.",
    )
    val supportedCapabilities: List<WorkflowAttestationCapabilityCode> = WorkflowSpiContractSupport.immutableList(
        supportedCapabilities.sortedBy { capability -> capability.code },
        WorkflowSpiContractSupport.MAX_ITEMS,
        "Workflow attestation supported capabilities exceed the limit.",
    )
    val snapshotDigest: String

    init {
        require(this.supportedCapabilities.toSet().size == this.supportedCapabilities.size) {
            "Workflow attestation supported capabilities must be unique."
        }
        require(maximumPendingOperations >= 0) {
            "Workflow attestation pending-operation capacity is invalid."
        }
        require(observedAtEpochMilli >= 0L && expiresAtEpochMilli > observedAtEpochMilli) {
            "Workflow attestation capability snapshot expiry must follow observation."
        }
        val asynchronous = WorkflowAttestationCapabilityCode.ASYNCHRONOUS_COMPLETION in this.supportedCapabilities
        if (asynchronous) {
            require(maximumPendingOperations > 0 &&
                (WorkflowAttestationCapabilityCode.ELECTRONIC_SIGNATURE in this.supportedCapabilities ||
                    WorkflowAttestationCapabilityCode.WITNESS in this.supportedCapabilities) &&
                WorkflowAttestationCapabilityCode.RECONCILIATION_BY_OPERATION in this.supportedCapabilities &&
                WorkflowAttestationCapabilityCode.RECONCILIATION_BY_REQUEST_DIGEST in this.supportedCapabilities
            ) {
                "Asynchronous workflow attestation requires bounded capacity and both reconciliation modes."
            }
        } else {
            require(maximumPendingOperations == 0) {
                "Synchronous workflow attestation cannot advertise pending-operation capacity."
            }
        }
        if (WorkflowAttestationCapabilityCode.CANCELLATION in this.supportedCapabilities) {
            require(asynchronous &&
                WorkflowAttestationCapabilityCode.RECONCILIATION_BY_OPERATION in this.supportedCapabilities
            ) { "Workflow attestation cancellation requires asynchronous operation reconciliation." }
        }
        snapshotDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-capability-snapshot-v1")
            .text(profile.providerId)
            .text(profile.profileId)
            .text(profile.version)
            .text(profile.digest)
            .text(this.providerRevision)
            .integer(this.supportedCapabilities.size)
            .also { writer -> this.supportedCapabilities.forEach { capability -> writer.text(capability.code) } }
            .integer(maximumPendingOperations)
            .longValue(observedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "WorkflowAttestationCapabilitySnapshot(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            profile: WorkflowAttestationProfileRef,
            providerRevision: String,
            supportedCapabilities: Collection<WorkflowAttestationCapabilityCode>,
            maximumPendingOperations: Int,
            observedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationCapabilitySnapshot = WorkflowAttestationCapabilitySnapshot(
            profile,
            providerRevision,
            supportedCapabilities,
            maximumPendingOperations,
            observedAtEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

class WorkflowAttestationCapabilityStatus private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(
        code, "Workflow attestation capability status is invalid.",
    )

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowAttestationCapabilityStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowAttestationCapabilityStatus(<redacted>)"

    companion object {
        @JvmField val AVAILABLE = WorkflowAttestationCapabilityStatus("available")
        @JvmField val UNSUPPORTED = WorkflowAttestationCapabilityStatus("unsupported")
        @JvmField val UNAVAILABLE = WorkflowAttestationCapabilityStatus("unavailable")

        @JvmStatic
        fun of(code: String): WorkflowAttestationCapabilityStatus = when (code) {
            AVAILABLE.code -> AVAILABLE
            UNSUPPORTED.code -> UNSUPPORTED
            UNAVAILABLE.code -> UNAVAILABLE
            else -> WorkflowAttestationCapabilityStatus(code)
        }
    }
}

class WorkflowAttestationCapabilityResult private constructor(
    val status: WorkflowAttestationCapabilityStatus,
    val receipt: WorkflowProviderReceipt,
    val snapshot: WorkflowAttestationCapabilitySnapshot?,
) {
    init {
        when (status) {
            WorkflowAttestationCapabilityStatus.AVAILABLE -> require(
                receipt.outcome == WorkflowProviderOutcome.SUCCESS && snapshot != null,
            ) { "Available workflow attestation capabilities require a successful snapshot." }

            WorkflowAttestationCapabilityStatus.UNSUPPORTED -> require(
                receipt.outcome == WorkflowProviderOutcome.UNSUPPORTED && receipt.failure?.retryable == false && snapshot != null,
            ) { "Unsupported workflow attestation capabilities require a value-only partial snapshot." }

            WorkflowAttestationCapabilityStatus.UNAVAILABLE -> require(
                receipt.outcome != WorkflowProviderOutcome.SUCCESS && snapshot == null,
            ) { "Unavailable workflow attestation capabilities cannot carry an authoritative snapshot." }

            else -> throw IllegalArgumentException("Unknown workflow attestation capability statuses require typed support.")
        }
    }

    override fun toString(): String = "WorkflowAttestationCapabilityResult(<redacted>)"

    companion object {
        @JvmStatic
        fun available(
            request: WorkflowAttestationCapabilityRequest,
            snapshot: WorkflowAttestationCapabilitySnapshot,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationCapabilityResult {
            requireSnapshotBinding(request, snapshot, completedAtEpochMilli, expiresAtEpochMilli)
            require(snapshot.supportedCapabilities.containsAll(request.requiredCapabilities)) {
                "Available workflow attestation snapshot does not satisfy every required capability."
            }
            return WorkflowAttestationCapabilityResult(
                WorkflowAttestationCapabilityStatus.AVAILABLE,
                WorkflowProviderReceipt.success(
                    request.context,
                    request.requestDigest,
                    snapshot.snapshotDigest,
                    completedAtEpochMilli,
                    expiresAtEpochMilli,
                ),
                snapshot,
            )
        }

        @JvmStatic
        fun unsupported(
            request: WorkflowAttestationCapabilityRequest,
            snapshot: WorkflowAttestationCapabilitySnapshot,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationCapabilityResult {
            requireSnapshotBinding(request, snapshot, completedAtEpochMilli, expiresAtEpochMilli)
            require(!snapshot.supportedCapabilities.containsAll(request.requiredCapabilities)) {
                "Unsupported workflow attestation snapshot unexpectedly satisfies every required capability."
            }
            val failure = WorkflowProviderFailure.of("capability-unsupported", false)
            val digest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-capability-result-v1")
                .text(WorkflowAttestationCapabilityStatus.UNSUPPORTED.code)
                .text(snapshot.snapshotDigest)
                .text(failure.code)
                .finish()
            return WorkflowAttestationCapabilityResult(
                WorkflowAttestationCapabilityStatus.UNSUPPORTED,
                WorkflowProviderReceipt.failure(
                    request.context,
                    request.requestDigest,
                    WorkflowProviderOutcome.UNSUPPORTED,
                    digest,
                    failure,
                    completedAtEpochMilli,
                    expiresAtEpochMilli,
                ),
                snapshot,
            )
        }

        @JvmStatic
        fun unavailable(
            request: WorkflowAttestationCapabilityRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationCapabilityResult {
            require(outcome != WorkflowProviderOutcome.SUCCESS && outcome != WorkflowProviderOutcome.UNSUPPORTED) {
                "Unavailable workflow attestation capability result has an invalid provider outcome."
            }
            val digest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-capability-result-v1")
                .text(WorkflowAttestationCapabilityStatus.UNAVAILABLE.code)
                .text(failure.code)
                .booleanValue(failure.retryable)
                .finish()
            return WorkflowAttestationCapabilityResult(
                WorkflowAttestationCapabilityStatus.UNAVAILABLE,
                WorkflowProviderReceipt.failure(
                    request.context,
                    request.requestDigest,
                    outcome,
                    digest,
                    failure,
                    completedAtEpochMilli,
                    expiresAtEpochMilli,
                ),
                null,
            )
        }

        private fun requireSnapshotBinding(
            request: WorkflowAttestationCapabilityRequest,
            snapshot: WorkflowAttestationCapabilitySnapshot,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ) {
            require(snapshot.profile == request.profile &&
                snapshot.providerId == request.context.providerId &&
                snapshot.providerRevision == request.context.providerRevision &&
                snapshot.supportedCapabilities.size <= request.context.maximumItems
            ) { "Workflow attestation capability snapshot does not match the exact provider invocation." }
            require(completedAtEpochMilli in snapshot.observedAtEpochMilli until snapshot.expiresAtEpochMilli &&
                expiresAtEpochMilli <= snapshot.expiresAtEpochMilli
            ) { "Workflow attestation capability snapshot is stale for this result." }
        }
    }
}

fun interface WorkflowAttestationCapabilityProvider {
    fun capabilities(
        request: WorkflowAttestationCapabilityRequest,
    ): CompletionStage<WorkflowAttestationCapabilityResult>
}
