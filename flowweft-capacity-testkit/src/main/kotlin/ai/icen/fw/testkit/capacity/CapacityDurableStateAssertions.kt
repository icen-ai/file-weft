package ai.icen.fw.testkit.capacity

import ai.icen.fw.capacity.api.CapacityAdmissionDecision
import ai.icen.fw.capacity.api.CapacityLeaseReleaseReceipt
import ai.icen.fw.capacity.api.CapacityLeaseRenewalReceipt
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationEvidence
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame

/** Canonical restart assertions use the only public reconstruction boundary: reconciliation. */
object CapacityDurableStateAssertions {
    @JvmStatic
    fun assertAdmissionRoundTrip(
        original: CapacityAdmissionDecision,
        evidence: CapacityOutcomeReconciliationEvidence,
    ) {
        assertEquals(CapacityOutcomeReconciliationStatus.APPLIED, evidence.status)
        val restored = evidence.admissionDecision
        assertNotNull(restored, "Capacity restart reconciliation omitted the admission decision.")
        assertNotSame(original, restored, "Capacity restart reconciliation reused the retained decision instance.")
        assertNotSame(original.request, requireNotNull(restored).request)
        assertEquals(original.decisionDigest, restored.decisionDigest)
        assertEquals(original.request.idempotencyBindingDigest, restored.request.idempotencyBindingDigest)
        assertEquals(original.lease?.leaseDigest, restored.lease?.leaseDigest)
        if (original.lease != null) assertNotSame(original.lease, restored.lease)
    }

    @JvmStatic
    fun assertRenewalRoundTrip(
        original: CapacityLeaseRenewalReceipt,
        evidence: CapacityOutcomeReconciliationEvidence,
    ) {
        assertEquals(CapacityOutcomeReconciliationStatus.APPLIED, evidence.status)
        val restored = evidence.renewalReceipt
        assertNotNull(restored, "Capacity restart reconciliation omitted the renewal receipt.")
        assertNotSame(original, restored, "Capacity restart reconciliation reused the retained renewal instance.")
        assertNotSame(original.request, requireNotNull(restored).request)
        assertNotSame(original.renewedLease, restored.renewedLease)
        assertEquals(original.receiptDigest, restored.receiptDigest)
        assertEquals(original.renewedLease.leaseDigest, restored.renewedLease.leaseDigest)
    }

    @JvmStatic
    fun assertReleaseRoundTrip(
        original: CapacityLeaseReleaseReceipt,
        evidence: CapacityOutcomeReconciliationEvidence,
    ) {
        assertEquals(CapacityOutcomeReconciliationStatus.APPLIED, evidence.status)
        val restored = evidence.releaseReceipt
        assertNotNull(restored, "Capacity restart reconciliation omitted the release receipt.")
        assertNotSame(original, restored, "Capacity restart reconciliation reused the retained release instance.")
        assertNotSame(original.request, requireNotNull(restored).request)
        assertNotSame(original.request.lease, restored.request.lease)
        assertEquals(original.receiptDigest, restored.receiptDigest)
        assertEquals(original.request.lease.leaseDigest, restored.request.lease.leaseDigest)
    }
}
