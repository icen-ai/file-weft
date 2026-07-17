package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.spi.WorkflowAttestationEvidence
import ai.icen.fw.workflow.spi.WorkflowAttestationProfileRef
import ai.icen.fw.workflow.spi.WorkflowAttestationStatement
import ai.icen.fw.workflow.spi.WorkflowElectronicSignatureProvider
import ai.icen.fw.workflow.spi.WorkflowElectronicSignatureRequest
import ai.icen.fw.workflow.spi.WorkflowElectronicSignatureResult
import ai.icen.fw.workflow.spi.WorkflowProviderCallContext
import ai.icen.fw.workflow.spi.WorkflowProviderOutcome
import ai.icen.fw.workflow.spi.WorkflowProviderReceipt
import ai.icen.fw.workflow.spi.WorkflowWitnessProvider
import ai.icen.fw.workflow.spi.WorkflowWitnessRequest
import ai.icen.fw.workflow.spi.WorkflowWitnessResult
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

class WorkflowRuntimeAttestationOperation private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow attestation operation is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowRuntimeAttestationOperation && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowRuntimeAttestationOperation(<redacted>)"

    companion object {
        @JvmField val ELECTRONIC_SIGNATURE = WorkflowRuntimeAttestationOperation("electronic-signature")
        @JvmField val WITNESS = WorkflowRuntimeAttestationOperation("witness")
    }
}

class WorkflowAttestationResultCode private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow attestation result code is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowAttestationResultCode && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowAttestationResultCode(<redacted>)"

    companion object {
        @JvmField val SUCCEEDED = WorkflowAttestationResultCode("succeeded")
        @JvmField val AUTHORIZATION_DENIED = WorkflowAttestationResultCode("authorization-denied")
        @JvmField val PROVIDER_UNAVAILABLE = WorkflowAttestationResultCode("provider-unavailable")
        @JvmField val PROVIDER_REJECTED = WorkflowAttestationResultCode("provider-rejected")
        @JvmField val RECEIPT_INVALID = WorkflowAttestationResultCode("receipt-invalid")
        @JvmField val OUTCOME_UNKNOWN = WorkflowAttestationResultCode("outcome-unknown")
        @JvmField val INVALID = WorkflowAttestationResultCode("invalid")
    }
}

/** Content-free diagnostic; certificates, provider errors, challenge data and artifact ids are excluded. */
class WorkflowAttestationDiagnostic private constructor(
    code: String,
    val retryable: Boolean,
) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow attestation diagnostic is invalid.")
    val digest: String = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-attestation-diagnostic-v1")
        .text(this.code)
        .bool(retryable)
        .finish()

    override fun toString(): String = "WorkflowAttestationDiagnostic(<redacted>)"

    companion object {
        @JvmStatic
        fun of(code: String, retryable: Boolean): WorkflowAttestationDiagnostic =
            WorkflowAttestationDiagnostic(code, retryable)
    }
}

/** Host-selected provider identity and hard call/output limits. No credential or key material is accepted. */
class WorkflowAttestationProviderProfile private constructor(
    providerId: String,
    providerRevision: String,
    val callWindowMillis: Long,
    val maximumInputBytes: Int,
    val maximumOutputBytes: Int,
) {
    val providerId: String = WorkflowRuntimeSupport.code(providerId, "Workflow attestation provider id is invalid.")
    val providerRevision: String = WorkflowRuntimeSupport.text(
        providerRevision,
        WorkflowRuntimeSupport.MAX_TEXT_BYTES,
        "Workflow attestation provider revision is invalid.",
    )

    init {
        require(callWindowMillis in 1L..MAX_CALL_WINDOW_MILLIS) {
            "Workflow attestation provider call window is invalid."
        }
        require(maximumInputBytes in 1..MAX_BYTES && maximumOutputBytes in 1..MAX_BYTES) {
            "Workflow attestation provider byte budget is invalid."
        }
    }

    override fun toString(): String = "WorkflowAttestationProviderProfile(<redacted>)"

    companion object {
        const val MAX_CALL_WINDOW_MILLIS: Long = 300_000L
        const val MAX_BYTES: Int = 4 * 1024 * 1024

        @JvmStatic
        fun of(
            providerId: String,
            providerRevision: String,
            callWindowMillis: Long,
            maximumInputBytes: Int,
            maximumOutputBytes: Int,
        ): WorkflowAttestationProviderProfile = WorkflowAttestationProviderProfile(
            providerId,
            providerRevision,
            callWindowMillis,
            maximumInputBytes,
            maximumOutputBytes,
        )
    }
}

/** Exact attestation command created after authentication; the statement contains only references and digests. */
class WorkflowAttestationCommand private constructor(
    val callContext: WorkflowTrustedCallContext,
    requestId: String,
    val operation: WorkflowRuntimeAttestationOperation,
    val profile: WorkflowAttestationProfileRef,
    val statement: WorkflowAttestationStatement,
) {
    val requestId: String = WorkflowRuntimeSupport.text(
        requestId,
        WorkflowRuntimeSupport.MAX_ID_BYTES,
        "Workflow attestation request id is invalid.",
    )
    val requestDigest: String

    init {
        require(statement.actor == callContext.actor) {
            "Workflow attestation actor must match the authenticated caller."
        }
        require(operation == WorkflowRuntimeAttestationOperation.ELECTRONIC_SIGNATURE ||
            operation == WorkflowRuntimeAttestationOperation.WITNESS
        ) { "Unsupported workflow attestation operation." }
        requestDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-attestation-command-v1")
            .text(callContext.contextDigest)
            .text(this.requestId)
            .text(operation.code)
            .text(profile.providerId)
            .text(profile.profileId)
            .text(profile.version)
            .text(profile.digest)
            .text(statement.statementDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowAttestationCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun electronicSignature(
            callContext: WorkflowTrustedCallContext,
            requestId: String,
            profile: WorkflowAttestationProfileRef,
            statement: WorkflowAttestationStatement,
        ): WorkflowAttestationCommand = WorkflowAttestationCommand(
            callContext,
            requestId,
            WorkflowRuntimeAttestationOperation.ELECTRONIC_SIGNATURE,
            profile,
            statement,
        )

        @JvmStatic
        fun witness(
            callContext: WorkflowTrustedCallContext,
            requestId: String,
            profile: WorkflowAttestationProfileRef,
            statement: WorkflowAttestationStatement,
        ): WorkflowAttestationCommand = WorkflowAttestationCommand(
            callContext,
            requestId,
            WorkflowRuntimeAttestationOperation.WITNESS,
            profile,
            statement,
        )
    }
}

/** Immutable evidence reference suitable for durable workflow history; it is not a claim of legal validity. */
class WorkflowAttestationEvaluation private constructor(
    val operation: WorkflowRuntimeAttestationOperation,
    val profile: WorkflowAttestationProfileRef,
    val statement: WorkflowAttestationStatement,
    val evidence: WorkflowAttestationEvidence,
    val providerReceipt: WorkflowProviderReceipt,
    authorizationDigest: String,
) {
    val authorizationDigest: String = WorkflowRuntimeSupport.sha256(
        authorizationDigest,
        "Workflow attestation authorization digest is invalid.",
    )
    val evaluationDigest: String

    init {
        require(providerReceipt.outcome == WorkflowProviderOutcome.SUCCESS &&
            providerReceipt.resultDigest == evidence.evidenceDigest
        ) { "Workflow attestation provider receipt does not match its evidence." }
        if (operation == WorkflowRuntimeAttestationOperation.ELECTRONIC_SIGNATURE) {
            require(evidence.attestor == statement.actor) {
                "Workflow electronic signature must be attested by the exact actor."
            }
        }
        evaluationDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-attestation-evaluation-v1")
            .text(operation.code)
            .text(profile.providerId)
            .text(profile.profileId)
            .text(profile.version)
            .text(profile.digest)
            .text(statement.statementDigest)
            .text(evidence.evidenceDigest)
            .text(providerReceipt.receiptDigest)
            .text(this.authorizationDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowAttestationEvaluation(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            operation: WorkflowRuntimeAttestationOperation,
            profile: WorkflowAttestationProfileRef,
            statement: WorkflowAttestationStatement,
            evidence: WorkflowAttestationEvidence,
            providerReceipt: WorkflowProviderReceipt,
            authorizationDigest: String,
        ): WorkflowAttestationEvaluation = WorkflowAttestationEvaluation(
            operation,
            profile,
            statement,
            evidence,
            providerReceipt,
            authorizationDigest,
        )
    }
}

class WorkflowAttestationRuntimeResult private constructor(
    val code: WorkflowAttestationResultCode,
    val evaluation: WorkflowAttestationEvaluation?,
    val diagnostic: WorkflowAttestationDiagnostic?,
) {
    init {
        require((code == WorkflowAttestationResultCode.SUCCEEDED) == (evaluation != null)) {
            "Workflow attestation result and evaluation are inconsistent."
        }
        require((evaluation == null) == (diagnostic != null)) {
            "Workflow attestation result requires exactly one payload shape."
        }
    }

    override fun toString(): String = "WorkflowAttestationRuntimeResult(code=${code.code})"

    companion object {
        @JvmStatic
        fun success(evaluation: WorkflowAttestationEvaluation): WorkflowAttestationRuntimeResult =
            WorkflowAttestationRuntimeResult(WorkflowAttestationResultCode.SUCCEEDED, evaluation, null)

        @JvmStatic
        fun failed(
            code: WorkflowAttestationResultCode,
            diagnostic: WorkflowAttestationDiagnostic,
        ): WorkflowAttestationRuntimeResult = WorkflowAttestationRuntimeResult(code, null, diagnostic)
    }
}

/**
 * Authorization-first boundary for optional electronic-signature and witness providers.
 * Provider exceptions are outcome-unknown because an external attestation may already exist.
 */
class WorkflowAttestationRuntime(
    private val authorizationPort: WorkflowRuntimeAuthorizationPort,
    private val signatureProvider: WorkflowElectronicSignatureProvider,
    private val witnessProvider: WorkflowWitnessProvider,
    private val providerProfile: WorkflowAttestationProviderProfile,
    private val clock: WorkflowWorkerClock,
) {
    fun attest(command: WorkflowAttestationCommand): WorkflowAttestationRuntimeResult {
        if (command.profile.providerId != providerProfile.providerId) {
            return failure(WorkflowAttestationResultCode.INVALID, "attestation-profile-mismatch", false)
        }
        val startedAt = safeTime()
            ?: return failure(WorkflowAttestationResultCode.PROVIDER_UNAVAILABLE, "clock-unavailable", true)
        val deadline = safeDeadline(startedAt, providerProfile.callWindowMillis)
            ?: return failure(WorkflowAttestationResultCode.INVALID, "clock-overflow", false)
        val authorizationRequest = try {
            WorkflowRuntimeAuthorizationRequest.of(
                command.callContext,
                WorkflowRuntimeAction.ATTEST_HUMAN_DECISION,
                command.statement.instance.id,
                command.statement.definition.key,
                command.statement.definition,
                command.statement.subject,
                command.requestDigest,
                startedAt,
            )
        } catch (_: RuntimeException) {
            return failure(WorkflowAttestationResultCode.INVALID, "authorization-request-invalid", false)
        }
        if (authorize(authorizationRequest, startedAt) == null) {
            return failure(WorkflowAttestationResultCode.AUTHORIZATION_DENIED, "authorization-denied", false)
        }
        val providerContext = try {
            WorkflowProviderCallContext.of(
                command.requestId,
                command.callContext.tenantId,
                providerProfile.providerId,
                providerProfile.providerRevision,
                command.operation.code,
                startedAt,
                deadline,
                providerProfile.maximumInputBytes,
                providerProfile.maximumOutputBytes,
                1,
            )
        } catch (_: RuntimeException) {
            return failure(WorkflowAttestationResultCode.INVALID, "provider-context-invalid", false)
        }
        val providerInvocation = try {
            invoke(command, providerContext)
        } catch (_: RuntimeException) {
            return failure(WorkflowAttestationResultCode.OUTCOME_UNKNOWN, "provider-outcome-unknown", false)
        }
        val providerResult = try {
            await(providerInvocation.stage, deadline)
        } catch (error: Exception) {
            restoreInterrupt(error)
            return failure(WorkflowAttestationResultCode.OUTCOME_UNKNOWN, "provider-outcome-unknown", false)
        }
        val completedAt = safeTime()
            ?: return failure(WorkflowAttestationResultCode.OUTCOME_UNKNOWN, "clock-outcome-unknown", false)
        val finalAuthorization = authorize(authorizationRequest, completedAt)
            ?: return failure(WorkflowAttestationResultCode.AUTHORIZATION_DENIED, "authorization-revoked", false)
        if (!receiptMatches(providerResult, providerInvocation.requestDigest, providerContext, completedAt)) {
            return failure(WorkflowAttestationResultCode.RECEIPT_INVALID, "provider-receipt-invalid", false)
        }
        if (providerResult.receipt.outcome != WorkflowProviderOutcome.SUCCESS) {
            val providerFailure = providerResult.receipt.failure
            val retryable = providerFailure?.retryable ?: false
            return failure(
                if (retryable || providerResult.receipt.outcome == WorkflowProviderOutcome.UNAVAILABLE) {
                    WorkflowAttestationResultCode.PROVIDER_UNAVAILABLE
                } else {
                    WorkflowAttestationResultCode.PROVIDER_REJECTED
                },
                providerFailure?.code ?: "provider-rejected",
                retryable,
            )
        }
        val evidence = providerResult.evidence
            ?: return failure(WorkflowAttestationResultCode.RECEIPT_INVALID, "provider-evidence-missing", false)
        if (evidence.attestedAtEpochMilli !in providerContext.requestedAtEpochMilli..completedAt ||
            evidence.artifact.sizeBytes > providerProfile.maximumOutputBytes.toLong()
        ) {
            return failure(WorkflowAttestationResultCode.RECEIPT_INVALID, "provider-evidence-invalid", false)
        }
        val evaluation = try {
            WorkflowAttestationEvaluation.of(
                command.operation,
                command.profile,
                command.statement,
                evidence,
                providerResult.receipt,
                finalAuthorization.authorityDigest,
            )
        } catch (_: RuntimeException) {
            return failure(WorkflowAttestationResultCode.RECEIPT_INVALID, "provider-evidence-invalid", false)
        }
        return WorkflowAttestationRuntimeResult.success(evaluation)
    }

    private fun invoke(
        command: WorkflowAttestationCommand,
        context: WorkflowProviderCallContext,
    ): PendingProviderResult = when (command.operation) {
        WorkflowRuntimeAttestationOperation.ELECTRONIC_SIGNATURE -> {
            val request = WorkflowElectronicSignatureRequest.of(context, command.profile, command.statement)
            PendingProviderResult(
                request.requestDigest,
                signatureProvider.sign(request).thenApply { result -> ProviderResult.of(result) },
            )
        }
        WorkflowRuntimeAttestationOperation.WITNESS -> {
            val request = WorkflowWitnessRequest.of(context, command.profile, command.statement)
            PendingProviderResult(
                request.requestDigest,
                witnessProvider.witness(request).thenApply { result -> ProviderResult.of(result) },
            )
        }
        else -> throw IllegalArgumentException("Unsupported workflow attestation operation.")
    }

    private fun authorize(
        request: WorkflowRuntimeAuthorizationRequest,
        now: Long,
    ): WorkflowRuntimeAuthorizationDecision? {
        val decision = try {
            authorizationPort.authorize(request)
        } catch (_: RuntimeException) {
            return null
        }
        return decision.takeIf {
            it.status == WorkflowRuntimeAuthorizationStatus.AUTHORIZED && it.matches(request, now)
        }
    }

    private fun receiptMatches(
        result: ProviderResult,
        requestDigest: String,
        context: WorkflowProviderCallContext,
        now: Long,
    ): Boolean {
        val receipt = result.receipt
        return receipt.contextDigest == context.contextDigest &&
            receipt.requestId == context.requestId &&
            receipt.tenantId == context.tenantId &&
            receipt.providerId == context.providerId &&
            receipt.providerRevision == context.providerRevision &&
            receipt.purpose == context.purpose &&
            receipt.requestDigest == requestDigest &&
            receipt.completedAtEpochMilli in context.requestedAtEpochMilli..now &&
            receipt.expiresAtEpochMilli in now..context.deadlineEpochMilli &&
            ((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (result.evidence != null))
    }

    private fun <T> await(stage: CompletionStage<T>?, deadline: Long): T {
        val safeStage = requireNotNull(stage) { "Workflow attestation provider returned no completion stage." }
        val remaining = deadline - clock.currentTimeMillis()
        require(remaining > 0L) { "Workflow attestation provider deadline expired." }
        return requireNotNull(safeStage.toCompletableFuture().get(remaining, TimeUnit.MILLISECONDS)) {
            "Workflow attestation provider returned no result."
        }
    }

    private fun safeTime(): Long? = try {
        clock.currentTimeMillis().takeIf { it >= 0L }
    } catch (_: RuntimeException) {
        null
    }

    private fun safeDeadline(now: Long, window: Long): Long? =
        if (now <= Long.MAX_VALUE - window) now + window else null

    private fun restoreInterrupt(failure: Exception) {
        if (failure is InterruptedException || failure.cause is InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun failure(
        code: WorkflowAttestationResultCode,
        diagnostic: String,
        retryable: Boolean,
    ): WorkflowAttestationRuntimeResult = WorkflowAttestationRuntimeResult.failed(
        code,
        WorkflowAttestationDiagnostic.of(diagnostic, retryable),
    )

    private class PendingProviderResult(
        val requestDigest: String,
        val stage: CompletionStage<ProviderResult>,
    )

    private class ProviderResult(
        val receipt: WorkflowProviderReceipt,
        val evidence: WorkflowAttestationEvidence?,
    ) {
        companion object {
            fun of(result: WorkflowElectronicSignatureResult): ProviderResult =
                ProviderResult(result.receipt, result.evidence)

            fun of(result: WorkflowWitnessResult): ProviderResult =
                ProviderResult(result.receipt, result.evidence)
        }
    }
}
