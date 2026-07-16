package ai.icen.fw.testkit.capacity

import ai.icen.fw.capacity.api.CapacityAdmissionOutcome
import ai.icen.fw.capacity.api.CapacityLeaseRenewalReceipt
import ai.icen.fw.capacity.api.CapacityProviderErrorCode
import ai.icen.fw.capacity.api.CapacityProviderResult
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationStatus
import ai.icen.fw.capacity.runtime.CapacityRuntimeErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable

/** Reusable two-phase intent, canonical outcome, outbox, restart, CAS, and fencing contract. */
abstract class CapacityPersistenceContractTest {
    protected abstract fun newHarness(): CapacityProviderContractHarness

    protected open fun concurrentTimeout(): Duration = Duration.ofSeconds(30)

    @Test
    fun `applied intent stores one canonical outcome and outbox then survives restart`() {
        val harness = newHarness()
        val inspection = requireNotNull(harness.inspection) {
            "Capacity persistence contract requires a read-only inspection port."
        }
        val snapshot = requireNotNull(harness.snapshot("durable-admit").value)
        val decision = requireNotNull(
            harness.probe.admit(
                harness.admissionRequest(
                    snapshot,
                    harness.normalDemand(snapshot),
                    "durable-admit-key",
                    suffix = "durable-admit",
                ),
            ).value,
        )
        val key = CapacityMutationEvidenceKey.admission(decision)

        assertEquals(CapacityTestIntentStatus.APPLIED, inspection.intentStatus(key))
        assertEquals(decision.decisionDigest, inspection.canonicalOutcomeDigest(key))
        assertEquals(1L, inspection.outboxEvidenceCount(key))
        val fingerprintBeforeReplay = inspection.persistenceFingerprint()
        val replay = harness.probe.admit(
            harness.admissionRequest(
                snapshot,
                harness.normalDemand(snapshot),
                "durable-admit-key",
                suffix = "durable-admit-replay",
            ),
        )
        assertTrue(replay.replayed)
        assertEquals(decision.decisionDigest, replay.value?.decisionDigest)
        assertEquals(fingerprintBeforeReplay, inspection.persistenceFingerprint())
        assertEquals(1L, inspection.outboxEvidenceCount(key))

        val restarted = harness.restart()
        val evidence = restarted.reconcile(restarted.reference(decision), "restart-admit")
        CapacityDurableStateAssertions.assertAdmissionRoundTrip(decision, evidence)
        assertEquals(1L, requireNotNull(restarted.inspection).outboxEvidenceCount(key))
    }

    @Test
    fun `prepared unknown remains read only and emits no outbox`() {
        val harness = newHarness()
        val inspection = requireNotNull(harness.inspection)
        requireNotNull(harness.faults).leaveNextMutationPrepared()
        val runtime = CapacityRuntimeContractHarness(harness)

        val unknown = runtime.admit("prepared-unknown-key")
        assertEquals(CapacityRuntimeErrorCode.OUTCOME_UNKNOWN, unknown.errorCode)
        val reference = requireNotNull(unknown.unknownOutcomeReference)
        val key = CapacityMutationEvidenceKey(
            reference.operation,
            reference.tenantId,
            reference.idempotencyScopeDigest,
        )
        assertEquals(CapacityTestIntentStatus.PREPARED, inspection.intentStatus(key))
        assertNull(inspection.canonicalOutcomeDigest(key))
        assertEquals(0L, inspection.outboxEvidenceCount(key))
        val fingerprintBeforeReconcile = inspection.persistenceFingerprint()
        val mutationsBeforeReconcile = inspection.mutationInvocationCount()

        val reconciled = runtime.reconcile(reference)
        assertEquals(CapacityOutcomeReconciliationStatus.STILL_UNKNOWN, reconciled.value?.evidence?.status)
        assertEquals(CapacityTestIntentStatus.PREPARED, inspection.intentStatus(key))
        assertNull(inspection.canonicalOutcomeDigest(key))
        assertEquals(0L, inspection.outboxEvidenceCount(key))
        assertEquals(fingerprintBeforeReconcile, inspection.persistenceFingerprint())
        assertEquals(mutationsBeforeReconcile, inspection.mutationInvocationCount())
        assertEquals(1, harness.probe.mutationCount())
        assertEquals(1, harness.probe.reconciliationCount())
    }

    @Test
    fun `policy change is confirmed not applied and reconciliation performs zero writes`() {
        val harness = newHarness()
        val inspection = requireNotNull(harness.inspection)
        val snapshot = requireNotNull(harness.snapshot("policy-changed").value)
        val request = harness.admissionRequest(
            snapshot,
            harness.normalDemand(snapshot),
            "policy-changed-key",
            suffix = "policy-changed",
            expectedPolicyResolutionDigest = CapacityContractAssertions.digest('f'),
        )
        val failed = harness.probe.admit(request)
        assertEquals(CapacityProviderErrorCode.POLICY_CHANGED, failed.errorCode)
        val key = CapacityMutationEvidenceKey(
            CapacityMutationEvidenceKey.ADMIT,
            request.context.tenantId,
            request.idempotencyScope.scopeDigest,
        )
        assertEquals(CapacityTestIntentStatus.NOT_APPLIED, inspection.intentStatus(key))
        assertNull(inspection.canonicalOutcomeDigest(key))
        assertEquals(0L, inspection.outboxEvidenceCount(key))
        val fingerprintBeforeReconcile = inspection.persistenceFingerprint()
        val mutationsBeforeReconcile = inspection.mutationInvocationCount()

        val evidence = harness.reconcile(harness.reference(request), "policy-changed")
        assertEquals(CapacityOutcomeReconciliationStatus.CONFIRMED_NOT_APPLIED, evidence.status)
        assertEquals(fingerprintBeforeReconcile, inspection.persistenceFingerprint())
        assertEquals(mutationsBeforeReconcile, inspection.mutationInvocationCount())
        assertEquals(CapacityTestIntentStatus.NOT_APPLIED, inspection.intentStatus(key))
        assertEquals(0L, inspection.outboxEvidenceCount(key))
    }

    @Test
    fun `renewal and release canonical outcomes survive restart by value reconstruction`() {
        val renewalHarness = newHarness()
        val renewalSnapshot = requireNotNull(renewalHarness.snapshot("restart-renew-seed").value)
        val renewalLease = requireNotNull(renewalHarness.probe.admit(
            renewalHarness.admissionRequest(
                renewalSnapshot, renewalHarness.normalDemand(renewalSnapshot), "restart-renew-seed-key",
            ),
        ).value?.lease)
        val renewal = requireNotNull(renewalHarness.probe.renew(
            renewalHarness.renewalRequest(renewalLease, "restart-renew-key"),
        ).value)
        val renewalKey = CapacityMutationEvidenceKey.renewal(renewal)
        val restartedRenewal = renewalHarness.restart()
        CapacityDurableStateAssertions.assertRenewalRoundTrip(
            renewal,
            restartedRenewal.reconcile(restartedRenewal.reference(renewal), "restart-renew"),
        )
        assertEquals(1L, requireNotNull(restartedRenewal.inspection).outboxEvidenceCount(renewalKey))

        val releaseHarness = newHarness()
        val releaseSnapshot = requireNotNull(releaseHarness.snapshot("restart-release-seed").value)
        val releaseLease = requireNotNull(releaseHarness.probe.admit(
            releaseHarness.admissionRequest(
                releaseSnapshot, releaseHarness.normalDemand(releaseSnapshot), "restart-release-seed-key",
            ),
        ).value?.lease)
        val release = requireNotNull(releaseHarness.probe.release(
            releaseHarness.releaseRequest(releaseLease, "restart-release-key"),
        ).value)
        val releaseKey = CapacityMutationEvidenceKey.release(release)
        val restartedRelease = releaseHarness.restart()
        CapacityDurableStateAssertions.assertReleaseRoundTrip(
            release,
            restartedRelease.reconcile(restartedRelease.reference(release), "restart-release"),
        )
        assertEquals(1L, requireNotNull(restartedRelease.inspection).outboxEvidenceCount(releaseKey))
    }

    @Test
    fun `concurrent renewal has one fence winner and stale lease cannot mutate`() {
        val harness = newHarness()
        val inspection = requireNotNull(harness.inspection)
        val snapshot = requireNotNull(harness.snapshot("lease-seed").value)
        val decision = requireNotNull(
            harness.probe.admit(
                harness.admissionRequest(
                    snapshot,
                    harness.normalDemand(snapshot),
                    "lease-seed-key",
                    suffix = "lease-seed",
                ),
            ).value,
        )
        assertEquals(CapacityAdmissionOutcome.ADMIT, decision.outcome)
        val lease = requireNotNull(decision.lease)
        val firstRequest = harness.renewalRequest(lease, "renew-race-one", "renew-race-one")
        val secondRequest = harness.renewalRequest(lease, "renew-race-two", "renew-race-two")

        val contenders = CapacityContractAssertions.race(
            Callable { firstRequest to harness.probe.renew(firstRequest) },
            Callable { secondRequest to harness.probe.renew(secondRequest) },
            concurrentTimeout(),
        )
        assertEquals(1, contenders.count { it.second.value != null })
        assertEquals(1, contenders.count { it.second.errorCode == CapacityProviderErrorCode.STATE_CONFLICT })
        val winningReceipt: CapacityLeaseRenewalReceipt = requireNotNull(
            contenders.single { it.second.value != null }.second.value,
        )
        assertTrue(winningReceipt.renewedLease.fencingToken > lease.fencingToken)
        assertTrue(winningReceipt.renewedLease.stateVersion > lease.stateVersion)
        val winningKey = CapacityMutationEvidenceKey.renewal(winningReceipt)
        assertEquals(CapacityTestIntentStatus.APPLIED, inspection.intentStatus(winningKey))
        assertEquals(winningReceipt.receiptDigest, inspection.canonicalOutcomeDigest(winningKey))
        assertEquals(1L, inspection.outboxEvidenceCount(winningKey))

        val losing = contenders.single { it.second.value == null }
        val losingKey = CapacityMutationEvidenceKey(
            CapacityMutationEvidenceKey.RENEW,
            losing.first.context.tenantId,
            losing.first.idempotencyScope.scopeDigest,
        )
        assertEquals(CapacityTestIntentStatus.NOT_APPLIED, inspection.intentStatus(losingKey))
        assertEquals(0L, inspection.outboxEvidenceCount(losingKey))

        val staleRelease = harness.releaseRequest(lease, "stale-release", "stale-release")
        val staleResult = harness.probe.release(staleRelease)
        assertEquals(CapacityProviderErrorCode.STATE_CONFLICT, staleResult.errorCode)
        val staleKey = CapacityMutationEvidenceKey(
            CapacityMutationEvidenceKey.RELEASE,
            staleRelease.context.tenantId,
            staleRelease.idempotencyScope.scopeDigest,
        )
        assertEquals(CapacityTestIntentStatus.NOT_APPLIED, inspection.intentStatus(staleKey))
        assertNull(inspection.canonicalOutcomeDigest(staleKey))
        assertEquals(0L, inspection.outboxEvidenceCount(staleKey))
    }
}
