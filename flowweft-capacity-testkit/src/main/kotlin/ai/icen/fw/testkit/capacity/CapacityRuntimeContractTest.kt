package ai.icen.fw.testkit.capacity

import ai.icen.fw.capacity.api.CapacityAdmissionOutcome
import ai.icen.fw.capacity.api.CapacityDegradationCapability
import ai.icen.fw.capacity.api.CapacityPurpose
import ai.icen.fw.capacity.api.CapacityTrustedContextProvider
import ai.icen.fw.capacity.api.ResourceScope
import ai.icen.fw.capacity.runtime.CapacityExternalCallBoundary
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationStatus
import ai.icen.fw.capacity.runtime.CapacityRuntimeErrorCode
import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/** Reusable runtime contract for fail-closed authorization, backpressure, and unknown outcomes. */
abstract class CapacityRuntimeContractTest {
    protected abstract fun newHarness(): CapacityRuntimeContractHarness

    @Test
    fun `runtime keeps replay digest stable and rejects changed arguments`() {
        val fixture = newHarness()
        val snapshot = requireNotNull(fixture.providerHarness.snapshot("runtime-replay").value)
        val amount = fixture.providerHarness.criticalDemand(snapshot)

        val first = fixture.admit("runtime-stable-key", amount)
        val replay = fixture.admit("runtime-stable-key", amount)
        val changed = fixture.admit("runtime-stable-key", amount + 1L)

        assertEquals(CapacityAdmissionOutcome.THROTTLE, first.value?.decision?.outcome)
        assertTrue(replay.replayed)
        assertEquals(first.value?.decision?.decisionDigest, replay.value?.decision?.decisionDigest)
        assertEquals(CapacityRuntimeErrorCode.STATE_CONFLICT, changed.errorCode)
        assertNull(changed.unknownOutcomeReference)
    }

    @Test
    fun `unknown applied outcome reconciles read only without a second mutation`() {
        val fixture = newHarness()
        requireNotNull(fixture.providerHarness.faults).failNextMutationAfterApply()

        val unknown = fixture.admit("runtime-unknown-key")
        assertEquals(CapacityRuntimeErrorCode.OUTCOME_UNKNOWN, unknown.errorCode)
        val reference = requireNotNull(unknown.unknownOutcomeReference)
        assertEquals(1, fixture.providerHarness.probe.mutationCount())
        val inspection = requireNotNull(fixture.providerHarness.inspection)
        val fingerprintBeforeReconcile = inspection.persistenceFingerprint()
        val mutationInvocationsBeforeReconcile = inspection.mutationInvocationCount()

        val reconciled = fixture.reconcile(reference)
        assertEquals(CapacityOutcomeReconciliationStatus.APPLIED, reconciled.value?.evidence?.status)
        assertNotNull(reconciled.value?.evidence?.admissionDecision)
        assertEquals(1, fixture.providerHarness.probe.mutationCount())
        assertEquals(1, fixture.providerHarness.probe.reconciliationCount())
        assertEquals(fingerprintBeforeReconcile, inspection.persistenceFingerprint())
        assertEquals(mutationInvocationsBeforeReconcile, inspection.mutationInvocationCount())
        CapacityContractAssertions.assertRedacted(reference.toString(), "runtime-unknown-key")
    }

    @Test
    fun `authorization revoked during admission refresh fails before provider mutation`() {
        val baseline = newHarness()
        val hierarchy = baseline.providerHarness.hierarchy
        val calls = AtomicInteger()
        val contexts = CapacityTrustedContextProvider { purpose ->
            val call = calls.incrementAndGet()
            hierarchy.context(
                purpose,
                "revocation-$call",
                principalId = if (call < 3) hierarchy.principalId else Identifier("revoked-principal"),
            )
        }
        val fixture = CapacityRuntimeContractHarness(
            baseline.providerHarness,
            trustedContexts = contexts,
        )
        val inspection = requireNotNull(baseline.providerHarness.inspection)

        val denied = fixture.admit("authorization-revoked-key")

        assertEquals(CapacityRuntimeErrorCode.AUTHORIZATION_REVOKED, denied.errorCode)
        assertEquals(3, calls.get())
        assertEquals(0, baseline.providerHarness.probe.mutationCount())
        assertEquals(0L, inspection.mutationInvocationCount())
    }

    @Test
    fun `runtime propagates explicit pressure decisions and forbids security degradation`() {
        val throttled = newHarness()
        val throttleSnapshot = requireNotNull(throttled.providerHarness.snapshot("runtime-throttle").value)
        val critical = throttled.providerHarness.criticalDemand(throttleSnapshot)
        assertEquals(
            CapacityAdmissionOutcome.THROTTLE,
            throttled.admit("runtime-throttle", critical).value?.decision?.outcome,
        )

        val degraded = newHarness()
        val degradeSnapshot = requireNotNull(degraded.providerHarness.snapshot("runtime-degrade").value)
        assertEquals(
            CapacityAdmissionOutcome.DEGRADE,
            degraded.admit(
                "runtime-degrade",
                degraded.providerHarness.criticalDemand(degradeSnapshot),
                setOf(CapacityDegradationCapability.DEFER_SECONDARY_INDEXING),
            ).value?.decision?.outcome,
        )

        val rejected = newHarness()
        val rejectSnapshot = requireNotNull(rejected.providerHarness.snapshot("runtime-reject").value)
        assertEquals(
            CapacityAdmissionOutcome.REJECT,
            rejected.admit(
                "runtime-reject",
                rejected.providerHarness.rejectingDemand(rejectSnapshot),
            ).value?.decision?.outcome,
        )

        val unsafe = newHarness()
        val denied = unsafe.admit(
            "runtime-unsafe",
            1L,
            setOf(CapacityDegradationCapability("skip.authorization")),
        )
        assertEquals(CapacityRuntimeErrorCode.SECURITY_DEGRADATION_FORBIDDEN, denied.errorCode)
        assertEquals(0, unsafe.providerHarness.probe.mutationCount())
    }

    @Test
    fun `cross tenant and active transaction fail before provider dispatch`() {
        val baseline = newHarness()
        val hierarchy = baseline.providerHarness.hierarchy
        val otherTenant = Identifier("tenant-other")
        val otherScope = ResourceScope.tenant(otherTenant)
        val crossTenantContexts = CapacityTrustedContextProvider { purpose ->
            hierarchy.context(
                purpose,
                "cross-tenant-${purpose.value}",
                tenantId = otherTenant,
                authorizedScope = otherScope,
            )
        }
        val crossTenant = CapacityRuntimeContractHarness(
            baseline.providerHarness,
            trustedContexts = crossTenantContexts,
        )
        val denied = crossTenant.admit("cross-tenant-key")
        assertEquals(CapacityRuntimeErrorCode.AUTHORIZATION_REVOKED, denied.errorCode)
        assertEquals(0, baseline.providerHarness.probe.mutationCount())

        val transactionHarness = newHarness()
        val activeTransaction = CapacityRuntimeContractHarness(
            transactionHarness.providerHarness,
            externalCalls = CapacityExternalCallBoundary { throw IllegalStateException("active transaction") },
        )
        val blocked = activeTransaction.observe()
        assertEquals(CapacityRuntimeErrorCode.TRANSACTION_BOUNDARY_VIOLATION, blocked.errorCode)
        assertEquals(0, transactionHarness.providerHarness.probe.observationCount())
    }
}
