package ai.icen.fw.workflow.spi

import java.util.concurrent.CompletionStage

/**
 * Cancellation binds a fresh provider call to the exact original typed request and operation.
 * A cancellation request is not proof that remote processing stopped.
 */
class WorkflowAttestationCancellationRequest private constructor(
    val context: WorkflowProviderCallContext,
    val kind: WorkflowAttestationKind,
    val electronicSignatureRequest: WorkflowElectronicSignatureRequest?,
    val witnessRequest: WorkflowWitnessRequest?,
    val operation: WorkflowAttestationOperationRef,
    reasonCode: String,
) {
    val reasonCode: String = requireAttestationValueFreeCode(
        reasonCode, "Workflow attestation cancellation reason code is invalid.",
    )
    val originalRequestDigest: String
    val requestDigest: String

    init {
        require((electronicSignatureRequest == null) != (witnessRequest == null)) {
            "Workflow attestation cancellation requires exactly one original typed request."
        }
        val originalContext = electronicSignatureRequest?.context ?: requireNotNull(witnessRequest).context
        val profile = electronicSignatureRequest?.profile ?: requireNotNull(witnessRequest).profile
        val statement = electronicSignatureRequest?.statement ?: requireNotNull(witnessRequest).statement
        val originalDigest = electronicSignatureRequest?.requestDigest ?: requireNotNull(witnessRequest).requestDigest
        val expectedKind = if (electronicSignatureRequest != null) {
            WorkflowAttestationKind.ELECTRONIC_SIGNATURE
        } else {
            WorkflowAttestationKind.WITNESS
        }
        require(kind == expectedKind) { "Workflow attestation cancellation kind is inconsistent." }
        require(context.tenantId == originalContext.tenantId &&
            context.providerId == originalContext.providerId &&
            context.providerRevision == originalContext.providerRevision &&
            context.requestId != originalContext.requestId &&
            context.requestedAtEpochMilli >= operation.acceptedAtEpochMilli
        ) { "Workflow attestation cancellation must be fresh and preserve tenant, provider, and revision." }
        require(operation.kind == kind &&
            operation.originalRequestDigest == originalDigest &&
            operation.tenantId == originalContext.tenantId &&
            operation.providerId == originalContext.providerId &&
            operation.providerRevision == originalContext.providerRevision &&
            operation.profile == profile &&
            operation.statementDigest == statement.statementDigest &&
            operation.actor == statement.actor
        ) { "Workflow attestation cancellation operation does not match the exact original request." }
        require(context.deadlineEpochMilli <= operation.expiresAtEpochMilli) {
            "Expired workflow attestation operation references cannot be cancelled."
        }
        originalRequestDigest = originalDigest
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-cancellation-request-v1")
            .text(context.contextDigest)
            .text(kind.code)
            .text(originalRequestDigest)
            .text(operation.operationDigest)
            .text(this.reasonCode)
            .finish()
    }

    override fun toString(): String = "WorkflowAttestationCancellationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun forElectronicSignature(
            context: WorkflowProviderCallContext,
            originalRequest: WorkflowElectronicSignatureRequest,
            operation: WorkflowAttestationOperationRef,
            reasonCode: String,
        ): WorkflowAttestationCancellationRequest = WorkflowAttestationCancellationRequest(
            context,
            WorkflowAttestationKind.ELECTRONIC_SIGNATURE,
            originalRequest,
            null,
            operation,
            reasonCode,
        )

        @JvmStatic
        fun forWitness(
            context: WorkflowProviderCallContext,
            originalRequest: WorkflowWitnessRequest,
            operation: WorkflowAttestationOperationRef,
            reasonCode: String,
        ): WorkflowAttestationCancellationRequest = WorkflowAttestationCancellationRequest(
            context,
            WorkflowAttestationKind.WITNESS,
            null,
            originalRequest,
            operation,
            reasonCode,
        )
    }
}

class WorkflowAttestationCancellationStatus private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(
        code, "Workflow attestation cancellation status is invalid.",
    )

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowAttestationCancellationStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowAttestationCancellationStatus(<redacted>)"

    companion object {
        @JvmField val CANCELLED = WorkflowAttestationCancellationStatus("cancelled")
        @JvmField val ALREADY_TERMINAL = WorkflowAttestationCancellationStatus("already-terminal")
        @JvmField val UNSUPPORTED = WorkflowAttestationCancellationStatus("unsupported")
        @JvmField val FAILED = WorkflowAttestationCancellationStatus("failed")
        @JvmField val OUTCOME_UNKNOWN = WorkflowAttestationCancellationStatus("outcome-unknown")

        @JvmStatic
        fun of(code: String): WorkflowAttestationCancellationStatus = when (code) {
            CANCELLED.code -> CANCELLED
            ALREADY_TERMINAL.code -> ALREADY_TERMINAL
            UNSUPPORTED.code -> UNSUPPORTED
            FAILED.code -> FAILED
            OUTCOME_UNKNOWN.code -> OUTCOME_UNKNOWN
            else -> WorkflowAttestationCancellationStatus(code)
        }
    }
}

class WorkflowAttestationCancellationResult private constructor(
    val status: WorkflowAttestationCancellationStatus,
    val receipt: WorkflowProviderReceipt,
) {
    /** Only an explicit CANCELLED acknowledgement proves cancellation; every other state is reconciled. */
    val requiresReconciliation: Boolean = status != WorkflowAttestationCancellationStatus.CANCELLED
    val cancellationConfirmed: Boolean = status == WorkflowAttestationCancellationStatus.CANCELLED

    init {
        when (status) {
            WorkflowAttestationCancellationStatus.CANCELLED,
            WorkflowAttestationCancellationStatus.ALREADY_TERMINAL -> require(
                receipt.outcome == WorkflowProviderOutcome.SUCCESS,
            ) { "Successful workflow attestation cancellation status requires a successful invocation receipt." }

            WorkflowAttestationCancellationStatus.UNSUPPORTED -> require(
                receipt.outcome == WorkflowProviderOutcome.UNSUPPORTED && receipt.failure?.retryable == false,
            ) { "Unsupported workflow attestation cancellation must fail closed." }

            WorkflowAttestationCancellationStatus.FAILED -> require(
                receipt.outcome != WorkflowProviderOutcome.SUCCESS && receipt.outcome != WorkflowProviderOutcome.UNSUPPORTED,
            ) { "Failed workflow attestation cancellation has an invalid provider outcome." }

            WorkflowAttestationCancellationStatus.OUTCOME_UNKNOWN -> require(
                receipt.outcome != WorkflowProviderOutcome.SUCCESS &&
                    receipt.failure?.code == WorkflowAttestationLifecycleResult.OUTCOME_UNKNOWN_FAILURE_CODE &&
                    receipt.failure?.retryable == false,
            ) { "Unknown workflow attestation cancellation outcomes are reconciliation-only." }

            else -> throw IllegalArgumentException("Unknown workflow attestation cancellation statuses require typed support.")
        }
    }

    override fun toString(): String = "WorkflowAttestationCancellationResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowAttestationCancellationRequest,
            status: WorkflowAttestationCancellationStatus,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationCancellationResult {
            require(status == WorkflowAttestationCancellationStatus.CANCELLED ||
                status == WorkflowAttestationCancellationStatus.ALREADY_TERMINAL
            ) { "Successful workflow attestation cancellation requires a confirmed status." }
            val digest = cancellationDigest(request, status, null)
            return WorkflowAttestationCancellationResult(
                status,
                WorkflowProviderReceipt.success(
                    request.context, request.requestDigest, digest, completedAtEpochMilli, expiresAtEpochMilli,
                ),
            )
        }

        @JvmStatic
        fun failure(
            request: WorkflowAttestationCancellationRequest,
            status: WorkflowAttestationCancellationStatus,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationCancellationResult {
            require(status == WorkflowAttestationCancellationStatus.UNSUPPORTED ||
                status == WorkflowAttestationCancellationStatus.FAILED
            ) { "Definitive workflow attestation cancellation failure has an invalid status." }
            if (status == WorkflowAttestationCancellationStatus.UNSUPPORTED) {
                require(outcome == WorkflowProviderOutcome.UNSUPPORTED && !failure.retryable) {
                    "Unsupported workflow attestation cancellation must be non-retryable."
                }
            } else {
                require(outcome != WorkflowProviderOutcome.SUCCESS && outcome != WorkflowProviderOutcome.UNSUPPORTED) {
                    "Failed workflow attestation cancellation has an invalid provider outcome."
                }
            }
            val digest = cancellationDigest(request, status, failure)
            return WorkflowAttestationCancellationResult(
                status,
                WorkflowProviderReceipt.failure(
                    request.context,
                    request.requestDigest,
                    outcome,
                    digest,
                    failure,
                    completedAtEpochMilli,
                    expiresAtEpochMilli,
                ),
            )
        }

        @JvmStatic
        fun outcomeUnknown(
            request: WorkflowAttestationCancellationRequest,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationCancellationResult {
            val failure = WorkflowProviderFailure.of(
                WorkflowAttestationLifecycleResult.OUTCOME_UNKNOWN_FAILURE_CODE,
                false,
            )
            val status = WorkflowAttestationCancellationStatus.OUTCOME_UNKNOWN
            val digest = cancellationDigest(request, status, failure)
            return WorkflowAttestationCancellationResult(
                status,
                WorkflowProviderReceipt.failure(
                    request.context,
                    request.requestDigest,
                    WorkflowProviderOutcome.FAILED,
                    digest,
                    failure,
                    completedAtEpochMilli,
                    expiresAtEpochMilli,
                ),
            )
        }

        private fun cancellationDigest(
            request: WorkflowAttestationCancellationRequest,
            status: WorkflowAttestationCancellationStatus,
            failure: WorkflowProviderFailure?,
        ): String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-cancellation-result-v1")
            .text(request.operation.operationDigest)
            .text(status.code)
            .optionalText(failure?.code)
            .booleanValue(failure?.retryable ?: false)
            .finish()
    }
}

fun interface WorkflowAttestationCancellationProvider {
    fun cancel(
        request: WorkflowAttestationCancellationRequest,
    ): CompletionStage<WorkflowAttestationCancellationResult>
}

/** Value-free diagnostic severities; adapters must never return exception or credential text. */
class WorkflowAttestationDoctorSeverity private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(
        code, "Workflow attestation Doctor severity is invalid.",
    )

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowAttestationDoctorSeverity && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowAttestationDoctorSeverity(<redacted>)"

    companion object {
        @JvmField val INFO = WorkflowAttestationDoctorSeverity("info")
        @JvmField val WARNING = WorkflowAttestationDoctorSeverity("warning")
        @JvmField val ERROR = WorkflowAttestationDoctorSeverity("error")

        @JvmStatic
        fun of(code: String): WorkflowAttestationDoctorSeverity = when (code) {
            INFO.code -> INFO
            WARNING.code -> WARNING
            ERROR.code -> ERROR
            else -> WorkflowAttestationDoctorSeverity(code)
        }
    }
}

class WorkflowAttestationDoctorFinding private constructor(
    code: String,
    val severity: WorkflowAttestationDoctorSeverity,
    val count: Int,
) {
    val code: String = requireAttestationValueFreeCode(
        code, "Workflow attestation Doctor finding code is invalid.",
    )
    val findingDigest: String

    init {
        require(count in 1..WorkflowSpiContractSupport.MAX_ITEMS) {
            "Workflow attestation Doctor finding count is invalid."
        }
        findingDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-doctor-finding-v1")
            .text(this.code)
            .text(severity.code)
            .integer(count)
            .finish()
    }

    override fun toString(): String = "WorkflowAttestationDoctorFinding(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            code: String,
            severity: WorkflowAttestationDoctorSeverity,
            count: Int,
        ): WorkflowAttestationDoctorFinding = WorkflowAttestationDoctorFinding(code, severity, count)
    }
}

class WorkflowAttestationDoctorStatus private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(
        code, "Workflow attestation Doctor status is invalid.",
    )

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowAttestationDoctorStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowAttestationDoctorStatus(<redacted>)"

    companion object {
        @JvmField val READY = WorkflowAttestationDoctorStatus("ready")
        @JvmField val DEGRADED = WorkflowAttestationDoctorStatus("degraded")
        @JvmField val NOT_READY = WorkflowAttestationDoctorStatus("not-ready")
        @JvmField val UNSUPPORTED = WorkflowAttestationDoctorStatus("unsupported")

        @JvmStatic
        fun of(code: String): WorkflowAttestationDoctorStatus = when (code) {
            READY.code -> READY
            DEGRADED.code -> DEGRADED
            NOT_READY.code -> NOT_READY
            UNSUPPORTED.code -> UNSUPPORTED
            else -> WorkflowAttestationDoctorStatus(code)
        }
    }
}

class WorkflowAttestationDoctorRequest private constructor(
    val context: WorkflowProviderCallContext,
    val profile: WorkflowAttestationProfileRef,
    val kind: WorkflowAttestationKind,
) {
    val requestDigest: String

    init {
        require(context.providerId == profile.providerId) {
            "Workflow attestation Doctor profile does not match its provider context."
        }
        require(kind == WorkflowAttestationKind.ELECTRONIC_SIGNATURE || kind == WorkflowAttestationKind.WITNESS) {
            "Workflow attestation Doctor kind requires typed support."
        }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-doctor-request-v1")
            .text(context.contextDigest)
            .text(profile.providerId)
            .text(profile.profileId)
            .text(profile.version)
            .text(profile.digest)
            .text(kind.code)
            .finish()
    }

    override fun toString(): String = "WorkflowAttestationDoctorRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun forElectronicSignature(
            context: WorkflowProviderCallContext,
            profile: WorkflowAttestationProfileRef,
        ): WorkflowAttestationDoctorRequest = WorkflowAttestationDoctorRequest(
            context, profile, WorkflowAttestationKind.ELECTRONIC_SIGNATURE,
        )

        @JvmStatic
        fun forWitness(
            context: WorkflowProviderCallContext,
            profile: WorkflowAttestationProfileRef,
        ): WorkflowAttestationDoctorRequest = WorkflowAttestationDoctorRequest(
            context, profile, WorkflowAttestationKind.WITNESS,
        )
    }
}

class WorkflowAttestationDoctorResult private constructor(
    val status: WorkflowAttestationDoctorStatus,
    val receipt: WorkflowProviderReceipt,
    findings: Collection<WorkflowAttestationDoctorFinding>,
) {
    val findings: List<WorkflowAttestationDoctorFinding> = WorkflowSpiContractSupport.immutableList(
        findings,
        WorkflowSpiContractSupport.MAX_ISSUES,
        "Workflow attestation Doctor findings exceed the limit.",
    )

    init {
        require(this.findings.map { finding -> finding.code }.toSet().size == this.findings.size) {
            "Workflow attestation Doctor finding codes must be unique."
        }
        when (status) {
            WorkflowAttestationDoctorStatus.READY -> require(
                receipt.outcome == WorkflowProviderOutcome.SUCCESS &&
                    this.findings.none { finding -> finding.severity != WorkflowAttestationDoctorSeverity.INFO },
            ) { "Ready workflow attestation Doctor results cannot hide unhealthy findings." }

            WorkflowAttestationDoctorStatus.DEGRADED -> require(
                receipt.outcome == WorkflowProviderOutcome.SUCCESS &&
                    this.findings.any { finding -> finding.severity == WorkflowAttestationDoctorSeverity.WARNING } &&
                    this.findings.none { finding -> finding.severity == WorkflowAttestationDoctorSeverity.ERROR },
            ) { "Degraded workflow attestation Doctor results require a warning and no error." }

            WorkflowAttestationDoctorStatus.NOT_READY -> require(
                receipt.outcome == WorkflowProviderOutcome.SUCCESS &&
                    this.findings.any { finding -> finding.severity == WorkflowAttestationDoctorSeverity.ERROR },
            ) { "Not-ready workflow attestation Doctor results require an error code." }

            WorkflowAttestationDoctorStatus.UNSUPPORTED -> require(
                receipt.outcome == WorkflowProviderOutcome.UNSUPPORTED && receipt.failure?.retryable == false &&
                    this.findings.isEmpty(),
            ) { "Unsupported workflow attestation Doctor results must be value-free and fail closed." }

            else -> throw IllegalArgumentException("Unknown workflow attestation Doctor statuses require typed support.")
        }
    }

    override fun toString(): String = "WorkflowAttestationDoctorResult(<redacted>)"

    companion object {
        @JvmStatic
        fun observed(
            request: WorkflowAttestationDoctorRequest,
            status: WorkflowAttestationDoctorStatus,
            findings: Collection<WorkflowAttestationDoctorFinding>,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationDoctorResult {
            require(status != WorkflowAttestationDoctorStatus.UNSUPPORTED) {
                "Observed workflow attestation Doctor result cannot use unsupported status."
            }
            val snapshot = WorkflowSpiContractSupport.immutableList(
                findings,
                WorkflowSpiContractSupport.MAX_ISSUES,
                "Workflow attestation Doctor findings exceed the limit.",
            )
            val digest = doctorDigest(status, snapshot, null)
            return WorkflowAttestationDoctorResult(
                status,
                WorkflowProviderReceipt.success(
                    request.context, request.requestDigest, digest, completedAtEpochMilli, expiresAtEpochMilli,
                ),
                snapshot,
            )
        }

        @JvmStatic
        fun unsupported(
            request: WorkflowAttestationDoctorRequest,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationDoctorResult {
            val status = WorkflowAttestationDoctorStatus.UNSUPPORTED
            val failure = WorkflowProviderFailure.of("doctor-unsupported", false)
            val digest = doctorDigest(status, emptyList(), failure)
            return WorkflowAttestationDoctorResult(
                status,
                WorkflowProviderReceipt.failure(
                    request.context,
                    request.requestDigest,
                    WorkflowProviderOutcome.UNSUPPORTED,
                    digest,
                    failure,
                    completedAtEpochMilli,
                    expiresAtEpochMilli,
                ),
                emptyList(),
            )
        }

        private fun doctorDigest(
            status: WorkflowAttestationDoctorStatus,
            findings: Collection<WorkflowAttestationDoctorFinding>,
            failure: WorkflowProviderFailure?,
        ): String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-doctor-result-v1")
            .text(status.code)
            .integer(findings.size)
            .also { writer -> findings.forEach { finding -> writer.text(finding.findingDigest) } }
            .optionalText(failure?.code)
            .booleanValue(failure?.retryable ?: false)
            .finish()
    }
}

fun interface WorkflowAttestationDoctor {
    fun diagnose(request: WorkflowAttestationDoctorRequest): CompletionStage<WorkflowAttestationDoctorResult>
}

private fun requireAttestationValueFreeCode(value: String, message: String): String {
    val code = WorkflowSpiContractSupport.requireMachineCode(value, message)
    require(code.all { character ->
        (character in 'A'..'Z') || (character in 'a'..'z') || (character in '0'..'9') ||
            character == '.' || character == '_' || character == '-'
    }) { message }
    return code
}
