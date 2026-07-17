package ai.icen.fw.capacity.runtime

import ai.icen.fw.capacity.api.CapacityAdmissionDecision
import ai.icen.fw.capacity.api.CapacityLeaseReleaseReceipt
import ai.icen.fw.capacity.api.CapacityLeaseRenewalReceipt
import ai.icen.fw.capacity.api.CapacityPurpose
import ai.icen.fw.capacity.api.CapacityTrustedContext
import ai.icen.fw.core.id.Identifier

object CapacityRuntimePurposes {
    /** Dedicated operations authority; it never inherits admission or lease-mutation authority. */
    @JvmField val RECONCILIATION: CapacityPurpose = CapacityPurpose("capacity.reconcile")
}

class CapacityOutcomeReconcileCommand(
    val reference: CapacityUnknownOutcomeReference,
    maximumDurationMillis: Long,
) {
    val maximumDurationMillis: Long = requireRuntimeDuration(maximumDurationMillis)

    override fun toString(): String = "CapacityOutcomeReconcileCommand(<redacted>)"
}

class CapacityOutcomeReconciliationRequest(
    operationId: Identifier,
    val context: CapacityTrustedContext,
    val reference: CapacityUnknownOutcomeReference,
    val requestedAt: Long,
    val deadlineAt: Long,
) {
    val operationId: Identifier = requireRuntimeIdentifier(
        operationId,
        "Capacity outcome-reconciliation operation identifier",
    )
    val bindingDigest: String

    init {
        context.requirePurpose(CapacityRuntimePurposes.RECONCILIATION)
        context.requireFresh(requestedAt)
        require(reference.isAuthorizedReconciliationTarget(context)) {
            "Capacity outcome reconciliation is outside the current trusted authorization."
        }
        require(deadlineAt > requestedAt && deadlineAt <= context.authorizationExpiresAt) {
            "Capacity outcome-reconciliation lifetime is invalid."
        }
        bindingDigest = CapacityRuntimeDigest("flowweft.capacity.runtime.reconciliation-request.v1")
            .add(operationId.value)
            .add(context.bindingDigest)
            .add(reference.referenceDigest)
            .add(requestedAt)
            .add(deadlineAt)
            .finish()
    }

    override fun toString(): String = "CapacityOutcomeReconciliationRequest(<redacted>)"
}

enum class CapacityOutcomeReconciliationStatus {
    APPLIED,
    CONFIRMED_NOT_APPLIED,
    STILL_UNKNOWN,
}

/**
 * Canonical provider-side lookup result. APPLIED carries exactly the original API decision or
 * receipt. Its transient request binding may predate the latest attempt, but the stable
 * idempotency scope and argument binding must match. Other states carry only provider proof.
 */
class CapacityOutcomeReconciliationEvidence private constructor(
    val request: CapacityOutcomeReconciliationRequest,
    val status: CapacityOutcomeReconciliationStatus,
    val admissionDecision: CapacityAdmissionDecision?,
    val renewalReceipt: CapacityLeaseRenewalReceipt?,
    val releaseReceipt: CapacityLeaseReleaseReceipt?,
    providerEvidenceDigest: String,
    val observedAt: Long,
    val expiresAt: Long,
) {
    val providerEvidenceDigest: String = requireRuntimeDigest(
        providerEvidenceDigest,
        "Capacity outcome-reconciliation provider evidence",
    )
    val evidenceDigest: String

    init {
        require(observedAt in request.requestedAt until request.deadlineAt &&
            expiresAt > observedAt && expiresAt <= request.context.authorizationExpiresAt
        ) { "Capacity outcome-reconciliation evidence lifetime is invalid." }
        val outcomes = listOfNotNull(admissionDecision, renewalReceipt, releaseReceipt)
        require((status == CapacityOutcomeReconciliationStatus.APPLIED) == (outcomes.size == 1)) {
            "Capacity outcome-reconciliation status and canonical outcome disagree."
        }
        if (status == CapacityOutcomeReconciliationStatus.APPLIED) validateCanonicalOutcome()
        evidenceDigest = CapacityRuntimeDigest("flowweft.capacity.runtime.reconciliation-evidence.v1")
            .add(request.bindingDigest)
            .add(status.name)
            .add(admissionDecision?.decisionDigest ?: "-")
            .add(renewalReceipt?.receiptDigest ?: "-")
            .add(releaseReceipt?.receiptDigest ?: "-")
            .add(this.providerEvidenceDigest)
            .add(observedAt)
            .add(expiresAt)
            .finish()
    }

    private fun validateCanonicalOutcome() {
        val reference = request.reference
        when (reference.operation) {
            CapacityUnknownOutcomeReference.ADMIT -> {
                val decision = requireNotNull(admissionDecision)
                require(renewalReceipt == null && releaseReceipt == null)
                require(decision.providerId == reference.providerId)
                require(decision.request.idempotencyScope.scopeDigest == reference.idempotencyScopeDigest)
                require(decision.request.idempotencyBindingDigest == reference.idempotencyBindingDigest)
                require(decision.request.target == reference.target && decision.request.workload == reference.workload)
                requireOriginalPrincipal(decision.request.context, reference)
            }

            CapacityUnknownOutcomeReference.RENEW -> {
                val receipt = requireNotNull(renewalReceipt)
                require(admissionDecision == null && releaseReceipt == null)
                require(receipt.providerId == reference.providerId)
                require(receipt.request.idempotencyScope.scopeDigest == reference.idempotencyScopeDigest)
                require(receipt.request.idempotencyBindingDigest == reference.idempotencyBindingDigest)
                require(receipt.request.lease.target == reference.target &&
                    receipt.request.lease.workload == reference.workload
                )
                requireOriginalPrincipal(receipt.request.context, reference)
            }

            CapacityUnknownOutcomeReference.RELEASE -> {
                val receipt = requireNotNull(releaseReceipt)
                require(admissionDecision == null && renewalReceipt == null)
                require(receipt.providerId == reference.providerId)
                require(receipt.request.idempotencyScope.scopeDigest == reference.idempotencyScopeDigest)
                require(receipt.request.idempotencyBindingDigest == reference.idempotencyBindingDigest)
                require(receipt.request.lease.target == reference.target &&
                    receipt.request.lease.workload == reference.workload
                )
                requireOriginalPrincipal(receipt.request.context, reference)
            }

            else -> error("Unsupported capacity outcome-reconciliation operation.")
        }
    }

    private fun requireOriginalPrincipal(
        context: CapacityTrustedContext,
        reference: CapacityUnknownOutcomeReference,
    ) {
        require(context.tenantId == reference.tenantId &&
            context.principalId == reference.principalId &&
            context.principalType == reference.principalType
        ) { "Canonical capacity outcome does not belong to the original principal." }
    }

    override fun toString(): String = "CapacityOutcomeReconciliationEvidence(status=$status, <redacted>)"

    companion object {
        @JvmStatic
        fun appliedAdmission(
            request: CapacityOutcomeReconciliationRequest,
            decision: CapacityAdmissionDecision,
            providerEvidenceDigest: String,
            observedAt: Long,
            expiresAt: Long,
        ): CapacityOutcomeReconciliationEvidence = CapacityOutcomeReconciliationEvidence(
            request,
            CapacityOutcomeReconciliationStatus.APPLIED,
            decision,
            null,
            null,
            providerEvidenceDigest,
            observedAt,
            expiresAt,
        )

        @JvmStatic
        fun appliedRenewal(
            request: CapacityOutcomeReconciliationRequest,
            receipt: CapacityLeaseRenewalReceipt,
            providerEvidenceDigest: String,
            observedAt: Long,
            expiresAt: Long,
        ): CapacityOutcomeReconciliationEvidence = CapacityOutcomeReconciliationEvidence(
            request,
            CapacityOutcomeReconciliationStatus.APPLIED,
            null,
            receipt,
            null,
            providerEvidenceDigest,
            observedAt,
            expiresAt,
        )

        @JvmStatic
        fun appliedRelease(
            request: CapacityOutcomeReconciliationRequest,
            receipt: CapacityLeaseReleaseReceipt,
            providerEvidenceDigest: String,
            observedAt: Long,
            expiresAt: Long,
        ): CapacityOutcomeReconciliationEvidence = CapacityOutcomeReconciliationEvidence(
            request,
            CapacityOutcomeReconciliationStatus.APPLIED,
            null,
            null,
            receipt,
            providerEvidenceDigest,
            observedAt,
            expiresAt,
        )

        @JvmStatic
        fun confirmedNotApplied(
            request: CapacityOutcomeReconciliationRequest,
            providerEvidenceDigest: String,
            observedAt: Long,
            expiresAt: Long,
        ): CapacityOutcomeReconciliationEvidence = CapacityOutcomeReconciliationEvidence(
            request,
            CapacityOutcomeReconciliationStatus.CONFIRMED_NOT_APPLIED,
            null,
            null,
            null,
            providerEvidenceDigest,
            observedAt,
            expiresAt,
        )

        @JvmStatic
        fun stillUnknown(
            request: CapacityOutcomeReconciliationRequest,
            providerEvidenceDigest: String,
            observedAt: Long,
            expiresAt: Long,
        ): CapacityOutcomeReconciliationEvidence = CapacityOutcomeReconciliationEvidence(
            request,
            CapacityOutcomeReconciliationStatus.STILL_UNKNOWN,
            null,
            null,
            null,
            providerEvidenceDigest,
            observedAt,
            expiresAt,
        )
    }
}

fun interface CapacityOutcomeReconciliationPort {
    /** Read-only exact lookup. Implementations must never call admit, renew or release. */
    fun reconcile(request: CapacityOutcomeReconciliationRequest): CapacityOutcomeReconciliationEvidence
}

class CapacityOutcomeReconciliationReceipt internal constructor(
    val evidence: CapacityOutcomeReconciliationEvidence,
    providerDescriptorDigest: String,
    val completedAt: Long,
) {
    val providerDescriptorDigest: String = requireRuntimeDigest(
        providerDescriptorDigest,
        "Capacity outcome-reconciliation provider descriptor",
    )

    init {
        require(completedAt >= evidence.observedAt && completedAt < evidence.expiresAt) {
            "Capacity outcome-reconciliation evidence is stale at completion."
        }
    }

    override fun toString(): String =
        "CapacityOutcomeReconciliationReceipt(status=${evidence.status}, <redacted>)"
}
