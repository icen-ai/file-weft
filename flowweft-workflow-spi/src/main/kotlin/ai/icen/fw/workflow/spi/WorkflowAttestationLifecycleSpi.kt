package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import java.util.concurrent.CompletionStage

/** Closed lifecycle families. New attestation mechanisms require a new typed request boundary. */
class WorkflowAttestationKind private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow attestation kind is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowAttestationKind && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowAttestationKind(<redacted>)"

    companion object {
        @JvmField val ELECTRONIC_SIGNATURE = WorkflowAttestationKind("electronic-signature")
        @JvmField val WITNESS = WorkflowAttestationKind("witness")

        @JvmStatic
        fun of(code: String): WorkflowAttestationKind = when (code) {
            ELECTRONIC_SIGNATURE.code -> ELECTRONIC_SIGNATURE
            WITNESS.code -> WITNESS
            else -> WorkflowAttestationKind(code)
        }
    }
}

/**
 * Durable, non-authorizing reference to one provider operation.
 *
 * The opaque reference is never a URL, bearer token, certificate, or private key. The digest binds
 * it to the exact original request, tenant, provider revision, profile, statement, and actor.
 */
class WorkflowAttestationOperationRef private constructor(
    val kind: WorkflowAttestationKind,
    originalRequestDigest: String,
    tenantId: String,
    providerId: String,
    providerRevision: String,
    val profile: WorkflowAttestationProfileRef,
    statementDigest: String,
    val actor: WorkflowPrincipalRef,
    externalOperationRef: String,
    val acceptedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val originalRequestDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        originalRequestDigest, "Workflow attestation original request digest is invalid.",
    )
    val tenantId: String = WorkflowSpiContractSupport.requireText(
        tenantId, WorkflowSpiContractSupport.MAX_ID_UTF8_BYTES, "Workflow attestation tenant is invalid.",
    )
    val providerId: String = WorkflowSpiContractSupport.requireMachineCode(
        providerId, "Workflow attestation operation provider is invalid.",
    )
    val providerRevision: String = WorkflowSpiContractSupport.requireText(
        providerRevision,
        WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES,
        "Workflow attestation operation provider revision is invalid.",
    )
    val statementDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        statementDigest, "Workflow attestation statement digest is invalid.",
    )
    val externalOperationRef: String = WorkflowSpiContractSupport.requireOpaqueReference(
        externalOperationRef, "Workflow attestation external operation reference is invalid.",
    )
    val operationDigest: String

    init {
        require(profile.providerId == this.providerId) {
            "Workflow attestation operation profile does not match its provider."
        }
        require(acceptedAtEpochMilli >= 0L && expiresAtEpochMilli > acceptedAtEpochMilli) {
            "Workflow attestation operation expiry must follow acceptance."
        }
        operationDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-operation-v1")
            .text(kind.code)
            .text(this.originalRequestDigest)
            .text(this.tenantId)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(profile.providerId)
            .text(profile.profileId)
            .text(profile.version)
            .text(profile.digest)
            .text(this.statementDigest)
            .text(actor.type)
            .text(actor.id)
            .text(this.externalOperationRef)
            .longValue(acceptedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .finish()
    }

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowAttestationOperationRef && operationDigest == other.operationDigest

    override fun hashCode(): Int = operationDigest.hashCode()
    override fun toString(): String = "WorkflowAttestationOperationRef(<redacted>)"

    companion object {
        @JvmStatic
        fun forElectronicSignature(
            request: WorkflowElectronicSignatureRequest,
            externalOperationRef: String,
            acceptedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationOperationRef = create(
            WorkflowAttestationKind.ELECTRONIC_SIGNATURE,
            request.context,
            request.profile,
            request.statement,
            request.requestDigest,
            externalOperationRef,
            acceptedAtEpochMilli,
            expiresAtEpochMilli,
        )

        @JvmStatic
        fun forWitness(
            request: WorkflowWitnessRequest,
            externalOperationRef: String,
            acceptedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationOperationRef = create(
            WorkflowAttestationKind.WITNESS,
            request.context,
            request.profile,
            request.statement,
            request.requestDigest,
            externalOperationRef,
            acceptedAtEpochMilli,
            expiresAtEpochMilli,
        )

        private fun create(
            kind: WorkflowAttestationKind,
            context: WorkflowProviderCallContext,
            profile: WorkflowAttestationProfileRef,
            statement: WorkflowAttestationStatement,
            requestDigest: String,
            externalOperationRef: String,
            acceptedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationOperationRef {
            require(acceptedAtEpochMilli in context.requestedAtEpochMilli..context.deadlineEpochMilli) {
                "Workflow attestation acceptance time is outside its original dispatch window."
            }
            return WorkflowAttestationOperationRef(
                kind,
                requestDigest,
                context.tenantId,
                context.providerId,
                context.providerRevision,
                profile,
                statement.statementDigest,
                statement.actor,
                externalOperationRef,
                acceptedAtEpochMilli,
                expiresAtEpochMilli,
            )
        }
    }
}

class WorkflowAttestationReconciliationMode private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(
        code, "Workflow attestation reconciliation mode is invalid.",
    )

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowAttestationReconciliationMode && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowAttestationReconciliationMode(<redacted>)"

    companion object {
        @JvmField val ORIGINAL_REQUEST_DIGEST = WorkflowAttestationReconciliationMode("original-request-digest")
        @JvmField val OPERATION_REFERENCE = WorkflowAttestationReconciliationMode("operation-reference")

        @JvmStatic
        fun of(code: String): WorkflowAttestationReconciliationMode = when (code) {
            ORIGINAL_REQUEST_DIGEST.code -> ORIGINAL_REQUEST_DIGEST
            OPERATION_REFERENCE.code -> OPERATION_REFERENCE
            else -> WorkflowAttestationReconciliationMode(code)
        }
    }
}

/**
 * A fresh read-side call tied to the exact original typed request. The request is intentionally
 * reused instead of copying statement, profile, or evidence fields into a second hierarchy.
 */
class WorkflowAttestationReconciliationRequest private constructor(
    val context: WorkflowProviderCallContext,
    val kind: WorkflowAttestationKind,
    val electronicSignatureRequest: WorkflowElectronicSignatureRequest?,
    val witnessRequest: WorkflowWitnessRequest?,
    val operation: WorkflowAttestationOperationRef?,
) {
    val originalRequestDigest: String
    val mode: WorkflowAttestationReconciliationMode = if (operation == null) {
        WorkflowAttestationReconciliationMode.ORIGINAL_REQUEST_DIGEST
    } else {
        WorkflowAttestationReconciliationMode.OPERATION_REFERENCE
    }
    val requestDigest: String

    init {
        require((electronicSignatureRequest == null) != (witnessRequest == null)) {
            "Workflow attestation reconciliation requires exactly one original typed request."
        }
        val binding = workflowBinding()
        require(kind == binding.kind) { "Workflow attestation reconciliation kind is inconsistent." }
        binding.requireFreshContext(context)
        operation?.let {
            binding.requireOperation(it)
            require(context.requestedAtEpochMilli >= it.acceptedAtEpochMilli) {
                "Workflow attestation reconciliation cannot precede operation acceptance."
            }
            require(context.deadlineEpochMilli <= it.expiresAtEpochMilli) {
                "Expired workflow attestation operation references cannot be reconciled by operation."
            }
        }
        originalRequestDigest = binding.requestDigest
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-reconciliation-request-v1")
            .text(context.contextDigest)
            .text(kind.code)
            .text(originalRequestDigest)
            .text(mode.code)
            .optionalText(operation?.operationDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowAttestationReconciliationRequest(<redacted>)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun forElectronicSignature(
            context: WorkflowProviderCallContext,
            originalRequest: WorkflowElectronicSignatureRequest,
            operation: WorkflowAttestationOperationRef? = null,
        ): WorkflowAttestationReconciliationRequest = WorkflowAttestationReconciliationRequest(
            context,
            WorkflowAttestationKind.ELECTRONIC_SIGNATURE,
            originalRequest,
            null,
            operation,
        )

        @JvmStatic
        @JvmOverloads
        fun forWitness(
            context: WorkflowProviderCallContext,
            originalRequest: WorkflowWitnessRequest,
            operation: WorkflowAttestationOperationRef? = null,
        ): WorkflowAttestationReconciliationRequest = WorkflowAttestationReconciliationRequest(
            context,
            WorkflowAttestationKind.WITNESS,
            null,
            originalRequest,
            operation,
        )
    }
}

class WorkflowAttestationLifecycleStatus private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(
        code, "Workflow attestation lifecycle status is invalid.",
    )

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowAttestationLifecycleStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowAttestationLifecycleStatus(<redacted>)"

    companion object {
        @JvmField val ACCEPTED = WorkflowAttestationLifecycleStatus("accepted")
        @JvmField val PENDING = WorkflowAttestationLifecycleStatus("pending")
        @JvmField val COMPLETED = WorkflowAttestationLifecycleStatus("completed")
        @JvmField val FAILED = WorkflowAttestationLifecycleStatus("failed")
        @JvmField val OUTCOME_UNKNOWN = WorkflowAttestationLifecycleStatus("outcome-unknown")

        @JvmStatic
        fun of(code: String): WorkflowAttestationLifecycleStatus = when (code) {
            ACCEPTED.code -> ACCEPTED
            PENDING.code -> PENDING
            COMPLETED.code -> COMPLETED
            FAILED.code -> FAILED
            OUTCOME_UNKNOWN.code -> OUTCOME_UNKNOWN
            else -> WorkflowAttestationLifecycleStatus(code)
        }
    }
}

/**
 * Result of either the initial dispatch or a later reconciliation call.
 *
 * [receipt] always belongs to the current invocation. [terminalReceipt] is the historical terminal
 * receipt for the exact original request and is present only when the remote outcome is known.
 */
class WorkflowAttestationLifecycleResult private constructor(
    val kind: WorkflowAttestationKind,
    val status: WorkflowAttestationLifecycleStatus,
    val receipt: WorkflowProviderReceipt,
    val operation: WorkflowAttestationOperationRef?,
    val terminalReceipt: WorkflowProviderReceipt?,
    val evidence: WorkflowAttestationEvidence?,
) {
    val requiresReconciliation: Boolean = status == WorkflowAttestationLifecycleStatus.ACCEPTED ||
        status == WorkflowAttestationLifecycleStatus.PENDING ||
        status == WorkflowAttestationLifecycleStatus.OUTCOME_UNKNOWN

    /** Once dispatch may have crossed the provider boundary, the original request is never blindly resubmitted. */
    val originalRequestResubmissionAllowed: Boolean = false

    init {
        when (status) {
            WorkflowAttestationLifecycleStatus.ACCEPTED,
            WorkflowAttestationLifecycleStatus.PENDING -> require(
                receipt.outcome == WorkflowProviderOutcome.SUCCESS && operation != null &&
                    terminalReceipt == null && evidence == null,
            ) { "Pending workflow attestation results require an operation and no terminal evidence." }

            WorkflowAttestationLifecycleStatus.COMPLETED -> require(
                receipt.outcome == WorkflowProviderOutcome.SUCCESS && terminalReceipt?.outcome == WorkflowProviderOutcome.SUCCESS &&
                    evidence != null,
            ) { "Completed workflow attestation results require exact successful terminal evidence." }

            WorkflowAttestationLifecycleStatus.FAILED -> require(
                terminalReceipt?.outcome != null && terminalReceipt.outcome != WorkflowProviderOutcome.SUCCESS && evidence == null,
            ) { "Failed workflow attestation results require an exact failed terminal receipt." }

            WorkflowAttestationLifecycleStatus.OUTCOME_UNKNOWN -> require(
                receipt.outcome != WorkflowProviderOutcome.SUCCESS && receipt.failure?.code == OUTCOME_UNKNOWN_FAILURE_CODE &&
                    receipt.failure?.retryable == false && terminalReceipt == null && evidence == null,
            ) { "Unknown workflow attestation outcomes require non-retryable reconciliation metadata." }

            else -> throw IllegalArgumentException("Unknown workflow attestation lifecycle statuses require typed support.")
        }
    }

    override fun toString(): String = "WorkflowAttestationLifecycleResult(<redacted>)"

    companion object {
        const val OUTCOME_UNKNOWN_FAILURE_CODE: String = "outcome-unknown"

        @JvmStatic
        fun acceptedElectronicSignature(
            request: WorkflowElectronicSignatureRequest,
            externalOperationRef: String,
            acceptedAtEpochMilli: Long,
            operationExpiresAtEpochMilli: Long,
            completedAtEpochMilli: Long,
            receiptExpiresAtEpochMilli: Long,
        ): WorkflowAttestationLifecycleResult = accepted(
            WorkflowAttestationBinding.fromSignature(request),
            externalOperationRef,
            acceptedAtEpochMilli,
            operationExpiresAtEpochMilli,
            completedAtEpochMilli,
            receiptExpiresAtEpochMilli,
        )

        @JvmStatic
        fun acceptedWitness(
            request: WorkflowWitnessRequest,
            externalOperationRef: String,
            acceptedAtEpochMilli: Long,
            operationExpiresAtEpochMilli: Long,
            completedAtEpochMilli: Long,
            receiptExpiresAtEpochMilli: Long,
        ): WorkflowAttestationLifecycleResult = accepted(
            WorkflowAttestationBinding.fromWitness(request),
            externalOperationRef,
            acceptedAtEpochMilli,
            operationExpiresAtEpochMilli,
            completedAtEpochMilli,
            receiptExpiresAtEpochMilli,
        )

        @JvmStatic
        fun immediateElectronicSignature(
            request: WorkflowElectronicSignatureRequest,
            result: WorkflowElectronicSignatureResult,
        ): WorkflowAttestationLifecycleResult = terminal(
            WorkflowAttestationBinding.fromSignature(request), result.receipt, result.evidence, result.receipt, null,
        )

        @JvmStatic
        fun immediateWitness(
            request: WorkflowWitnessRequest,
            result: WorkflowWitnessResult,
        ): WorkflowAttestationLifecycleResult = terminal(
            WorkflowAttestationBinding.fromWitness(request), result.receipt, result.evidence, result.receipt, null,
        )

        @JvmStatic
        fun outcomeUnknownElectronicSignature(
            request: WorkflowElectronicSignatureRequest,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationLifecycleResult = outcomeUnknown(
            WorkflowAttestationBinding.fromSignature(request), request.context, request.requestDigest, null,
            completedAtEpochMilli, expiresAtEpochMilli,
        )

        @JvmStatic
        fun outcomeUnknownWitness(
            request: WorkflowWitnessRequest,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationLifecycleResult = outcomeUnknown(
            WorkflowAttestationBinding.fromWitness(request), request.context, request.requestDigest, null,
            completedAtEpochMilli, expiresAtEpochMilli,
        )

        @JvmStatic
        fun pending(
            request: WorkflowAttestationReconciliationRequest,
            operation: WorkflowAttestationOperationRef,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationLifecycleResult {
            val binding = request.workflowBinding()
            binding.requireOperation(operation)
            request.operation?.let { known ->
                require(known.operationDigest == operation.operationDigest) {
                    "Workflow attestation reconciliation returned a different operation."
                }
            }
            val digest = lifecycleDigest(WorkflowAttestationLifecycleStatus.PENDING, operation, null, null, null)
            return WorkflowAttestationLifecycleResult(
                binding.kind,
                WorkflowAttestationLifecycleStatus.PENDING,
                WorkflowProviderReceipt.success(
                    request.context, request.requestDigest, digest, completedAtEpochMilli, expiresAtEpochMilli,
                ),
                operation,
                null,
                null,
            )
        }

        @JvmStatic
        fun reconciledElectronicSignature(
            request: WorkflowAttestationReconciliationRequest,
            terminalResult: WorkflowElectronicSignatureResult,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationLifecycleResult {
            val binding = request.workflowBinding()
            require(binding.kind == WorkflowAttestationKind.ELECTRONIC_SIGNATURE) {
                "Electronic-signature terminal evidence cannot complete another attestation kind."
            }
            binding.requireTerminal(terminalResult.receipt, terminalResult.evidence)
            val digest = lifecycleDigest(
                terminalStatus(terminalResult.receipt),
                request.operation,
                terminalResult.receipt,
                terminalResult.evidence,
                null,
            )
            val invocationReceipt = WorkflowProviderReceipt.success(
                request.context, request.requestDigest, digest, completedAtEpochMilli, expiresAtEpochMilli,
            )
            return terminal(binding, terminalResult.receipt, terminalResult.evidence, invocationReceipt, request.operation)
        }

        @JvmStatic
        fun reconciledWitness(
            request: WorkflowAttestationReconciliationRequest,
            terminalResult: WorkflowWitnessResult,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationLifecycleResult {
            val binding = request.workflowBinding()
            require(binding.kind == WorkflowAttestationKind.WITNESS) {
                "Witness terminal evidence cannot complete another attestation kind."
            }
            binding.requireTerminal(terminalResult.receipt, terminalResult.evidence)
            val digest = lifecycleDigest(
                terminalStatus(terminalResult.receipt),
                request.operation,
                terminalResult.receipt,
                terminalResult.evidence,
                null,
            )
            val invocationReceipt = WorkflowProviderReceipt.success(
                request.context, request.requestDigest, digest, completedAtEpochMilli, expiresAtEpochMilli,
            )
            return terminal(binding, terminalResult.receipt, terminalResult.evidence, invocationReceipt, request.operation)
        }

        @JvmStatic
        fun reconciliationOutcomeUnknown(
            request: WorkflowAttestationReconciliationRequest,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationLifecycleResult = outcomeUnknown(
            request.workflowBinding(), request.context, request.requestDigest, request.operation,
            completedAtEpochMilli, expiresAtEpochMilli,
        )

        private fun accepted(
            binding: WorkflowAttestationBinding,
            externalOperationRef: String,
            acceptedAtEpochMilli: Long,
            operationExpiresAtEpochMilli: Long,
            completedAtEpochMilli: Long,
            receiptExpiresAtEpochMilli: Long,
        ): WorkflowAttestationLifecycleResult {
            require(acceptedAtEpochMilli in binding.context.requestedAtEpochMilli..binding.context.deadlineEpochMilli) {
                "Workflow attestation acceptance time is outside its dispatch window."
            }
            val operation = binding.operation(
                externalOperationRef, acceptedAtEpochMilli, operationExpiresAtEpochMilli,
            )
            val digest = lifecycleDigest(WorkflowAttestationLifecycleStatus.ACCEPTED, operation, null, null, null)
            return WorkflowAttestationLifecycleResult(
                binding.kind,
                WorkflowAttestationLifecycleStatus.ACCEPTED,
                WorkflowProviderReceipt.success(
                    binding.context,
                    binding.requestDigest,
                    digest,
                    completedAtEpochMilli,
                    receiptExpiresAtEpochMilli,
                ),
                operation,
                null,
                null,
            )
        }

        private fun terminal(
            binding: WorkflowAttestationBinding,
            terminalReceipt: WorkflowProviderReceipt,
            evidence: WorkflowAttestationEvidence?,
            invocationReceipt: WorkflowProviderReceipt,
            operation: WorkflowAttestationOperationRef?,
        ): WorkflowAttestationLifecycleResult {
            binding.requireTerminal(terminalReceipt, evidence)
            operation?.let { binding.requireOperation(it) }
            return WorkflowAttestationLifecycleResult(
                binding.kind,
                terminalStatus(terminalReceipt),
                invocationReceipt,
                operation,
                terminalReceipt,
                evidence,
            )
        }

        private fun outcomeUnknown(
            binding: WorkflowAttestationBinding,
            context: WorkflowProviderCallContext,
            currentRequestDigest: String,
            operation: WorkflowAttestationOperationRef?,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowAttestationLifecycleResult {
            operation?.let { binding.requireOperation(it) }
            val failure = WorkflowProviderFailure.of(OUTCOME_UNKNOWN_FAILURE_CODE, false)
            val digest = lifecycleDigest(
                WorkflowAttestationLifecycleStatus.OUTCOME_UNKNOWN, operation, null, null, failure,
            )
            return WorkflowAttestationLifecycleResult(
                binding.kind,
                WorkflowAttestationLifecycleStatus.OUTCOME_UNKNOWN,
                WorkflowProviderReceipt.failure(
                    context,
                    currentRequestDigest,
                    WorkflowProviderOutcome.FAILED,
                    digest,
                    failure,
                    completedAtEpochMilli,
                    expiresAtEpochMilli,
                ),
                operation,
                null,
                null,
            )
        }

        private fun terminalStatus(receipt: WorkflowProviderReceipt): WorkflowAttestationLifecycleStatus =
            if (receipt.outcome == WorkflowProviderOutcome.SUCCESS) {
                WorkflowAttestationLifecycleStatus.COMPLETED
            } else {
                WorkflowAttestationLifecycleStatus.FAILED
            }

        private fun lifecycleDigest(
            status: WorkflowAttestationLifecycleStatus,
            operation: WorkflowAttestationOperationRef?,
            terminalReceipt: WorkflowProviderReceipt?,
            evidence: WorkflowAttestationEvidence?,
            failure: WorkflowProviderFailure?,
        ): String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-attestation-lifecycle-result-v1")
            .text(status.code)
            .optionalText(operation?.operationDigest)
            .optionalText(terminalReceipt?.receiptDigest)
            .optionalText(evidence?.evidenceDigest)
            .optionalText(failure?.code)
            .booleanValue(failure?.retryable ?: false)
            .finish()
    }
}

/** Async dispatch is additive; the historical one-shot provider SPI remains unchanged. */
fun interface WorkflowElectronicSignatureLifecycleProvider {
    fun dispatch(request: WorkflowElectronicSignatureRequest): CompletionStage<WorkflowAttestationLifecycleResult>
}

/** Async dispatch is additive; the historical one-shot provider SPI remains unchanged. */
fun interface WorkflowWitnessLifecycleProvider {
    fun dispatch(request: WorkflowWitnessRequest): CompletionStage<WorkflowAttestationLifecycleResult>
}

fun interface WorkflowAttestationReconciliationProvider {
    fun reconcile(request: WorkflowAttestationReconciliationRequest): CompletionStage<WorkflowAttestationLifecycleResult>
}

private class WorkflowAttestationBinding private constructor(
    val kind: WorkflowAttestationKind,
    val context: WorkflowProviderCallContext,
    val profile: WorkflowAttestationProfileRef,
    val statement: WorkflowAttestationStatement,
    val requestDigest: String,
    private val electronicSignatureRequest: WorkflowElectronicSignatureRequest?,
    private val witnessRequest: WorkflowWitnessRequest?,
) {
    fun operation(
        externalOperationRef: String,
        acceptedAtEpochMilli: Long,
        expiresAtEpochMilli: Long,
    ): WorkflowAttestationOperationRef {
        require(acceptedAtEpochMilli in context.requestedAtEpochMilli..context.deadlineEpochMilli) {
            "Workflow attestation acceptance time is outside its original dispatch window."
        }
        return electronicSignatureRequest?.let { request ->
            WorkflowAttestationOperationRef.forElectronicSignature(
                request, externalOperationRef, acceptedAtEpochMilli, expiresAtEpochMilli,
            )
        } ?: WorkflowAttestationOperationRef.forWitness(
            requireNotNull(witnessRequest), externalOperationRef, acceptedAtEpochMilli, expiresAtEpochMilli,
        )
    }

    fun requireFreshContext(freshContext: WorkflowProviderCallContext) {
        require(freshContext.tenantId == context.tenantId &&
            freshContext.providerId == context.providerId &&
            freshContext.providerRevision == context.providerRevision &&
            freshContext.requestId != context.requestId &&
            freshContext.requestedAtEpochMilli >= context.requestedAtEpochMilli
        ) {
            "Workflow attestation lifecycle context must be fresh and preserve tenant, provider, and revision."
        }
    }

    fun requireOperation(operation: WorkflowAttestationOperationRef) {
        require(operation.kind == kind &&
            operation.originalRequestDigest == requestDigest &&
            operation.tenantId == context.tenantId &&
            operation.providerId == context.providerId &&
            operation.providerRevision == context.providerRevision &&
            operation.profile == profile &&
            operation.statementDigest == statement.statementDigest &&
            operation.actor == statement.actor
        ) {
            "Workflow attestation operation does not match the exact original request."
        }
    }

    fun requireTerminal(receipt: WorkflowProviderReceipt, evidence: WorkflowAttestationEvidence?) {
        require(receipt.requestDigest == requestDigest &&
            receipt.contextDigest == context.contextDigest &&
            receipt.tenantId == context.tenantId &&
            receipt.providerId == context.providerId &&
            receipt.providerRevision == context.providerRevision
        ) {
            "Workflow attestation terminal receipt does not match the exact original request."
        }
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (evidence != null)) {
            "Workflow attestation terminal evidence does not match its outcome."
        }
        if (kind == WorkflowAttestationKind.ELECTRONIC_SIGNATURE && evidence != null) {
            require(evidence.attestor == statement.actor) {
                "Workflow electronic-signature evidence must remain bound to the exact actor."
            }
        }
    }

    companion object {
        fun fromSignature(request: WorkflowElectronicSignatureRequest): WorkflowAttestationBinding =
            WorkflowAttestationBinding(
                WorkflowAttestationKind.ELECTRONIC_SIGNATURE,
                request.context,
                request.profile,
                request.statement,
                request.requestDigest,
                request,
                null,
            )

        fun fromWitness(request: WorkflowWitnessRequest): WorkflowAttestationBinding =
            WorkflowAttestationBinding(
                WorkflowAttestationKind.WITNESS,
                request.context,
                request.profile,
                request.statement,
                request.requestDigest,
                null,
                request,
            )
    }
}

private fun WorkflowAttestationReconciliationRequest.workflowBinding(): WorkflowAttestationBinding =
    electronicSignatureRequest?.let { request ->
        WorkflowAttestationBinding.fromSignature(request)
    } ?: WorkflowAttestationBinding.fromWitness(requireNotNull(witnessRequest))
