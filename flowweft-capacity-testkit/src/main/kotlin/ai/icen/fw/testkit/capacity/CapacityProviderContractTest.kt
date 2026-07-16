package ai.icen.fw.testkit.capacity

import ai.icen.fw.capacity.api.CapacityAdmissionOutcome
import ai.icen.fw.capacity.api.CapacityAdmissionRequest
import ai.icen.fw.capacity.api.CapacityDegradationCapability
import ai.icen.fw.capacity.api.CapacityDemand
import ai.icen.fw.capacity.api.CapacityDimension
import ai.icen.fw.capacity.api.CapacityPressureLevel
import ai.icen.fw.capacity.api.CapacityProviderErrorCode
import ai.icen.fw.capacity.api.CapacityScopeLevel
import ai.icen.fw.capacity.api.CapacitySnapshotRequest
import ai.icen.fw.capacity.api.CapacityPurpose
import ai.icen.fw.capacity.api.CapacityWritePrecondition
import ai.icen.fw.capacity.api.ResourceScope
import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable

/** Reusable hierarchy, admission, idempotency, tenant, and atomic-CAS provider contract. */
abstract class CapacityProviderContractTest {
    protected abstract fun newHarness(): CapacityProviderContractHarness

    protected open fun concurrentTimeout(): Duration = Duration.ofSeconds(30)

    @Test
    fun `provider preserves canonical hierarchy units watermarks and pressure`() {
        val harness = newHarness()
        val resolution = harness.hierarchy.resolution()

        assertEquals(
            CapacityScopeLevel.values().toSet(),
            resolution.policies.map { it.scope.level }.toSet(),
        )
        val queue = requireNotNull(resolution.limitFor(CapacityDimension.QUEUE_DEPTH))
        assertEquals(64L, queue.limit)
        assertEquals(32L, queue.warningWatermark)
        assertEquals(48L, queue.criticalWatermark)
        val bytes = requireNotNull(resolution.limitFor(CapacityDimension.IN_FLIGHT_BYTES))
        assertEquals("bytes", bytes.dimension.unit.value)
        assertEquals(6_000L, bytes.limit)
        assertEquals(3_000L, bytes.warningWatermark)
        assertEquals(4_500L, bytes.criticalWatermark)
        assertEquals(setOf(harness.hierarchy.degradation), resolution.allowedDegradations)

        val snapshot = requireNotNull(harness.snapshot().value)
        assertEquals(resolution.resolutionDigest, snapshot.policyResolution.resolutionDigest)
        assertEquals(CapacityPressureLevel.NORMAL, snapshot.measureFor(CapacityDimension.QUEUE_DEPTH)?.pressure)
        assertEquals(CapacityPressureLevel.NORMAL, snapshot.measureFor(CapacityDimension.IN_FLIGHT_BYTES)?.pressure)
    }

    @Test
    fun `provider exposes explicit admit throttle degrade and reject semantics`() {
        val admittedHarness = newHarness()
        val admittedSnapshot = requireNotNull(admittedHarness.snapshot("admit").value)
        val admitted = admittedHarness.probe.admit(
            admittedHarness.admissionRequest(
                admittedSnapshot,
                admittedHarness.normalDemand(admittedSnapshot),
                "admit-key",
            ),
        )
        assertEquals(CapacityAdmissionOutcome.ADMIT, admitted.value?.outcome)
        assertNotNull(admitted.value?.lease)
        assertEquals(admittedSnapshot.stateVersion + 1L, admitted.value?.usage?.stateVersion)

        val throttledHarness = newHarness()
        val throttledSnapshot = requireNotNull(throttledHarness.snapshot("throttle").value)
        val throttled = throttledHarness.probe.admit(
            throttledHarness.admissionRequest(
                throttledSnapshot,
                throttledHarness.criticalDemand(throttledSnapshot),
                "throttle-key",
            ),
        )
        assertEquals(CapacityAdmissionOutcome.THROTTLE, throttled.value?.outcome)
        assertNull(throttled.value?.lease)
        assertTrue(requireNotNull(throttled.value?.retryAfterMillis) > 0L)
        assertEquals(throttledSnapshot.stateVersion, throttled.value?.usage?.stateVersion)

        val degradedHarness = newHarness()
        val degradedSnapshot = requireNotNull(degradedHarness.snapshot("degrade").value)
        val degraded = degradedHarness.probe.admit(
            degradedHarness.admissionRequest(
                degradedSnapshot,
                degradedHarness.criticalDemand(degradedSnapshot),
                "degrade-key",
                setOf(CapacityDegradationCapability.DEFER_SECONDARY_INDEXING),
            ),
        )
        assertEquals(CapacityAdmissionOutcome.DEGRADE, degraded.value?.outcome)
        assertNotNull(degraded.value?.lease)
        assertEquals(setOf(degradedHarness.hierarchy.degradation), degraded.value?.degradationCapabilities)

        val rejectedHarness = newHarness()
        val rejectedSnapshot = requireNotNull(rejectedHarness.snapshot("reject").value)
        val rejected = rejectedHarness.probe.admit(
            rejectedHarness.admissionRequest(
                rejectedSnapshot,
                rejectedHarness.rejectingDemand(rejectedSnapshot),
                "reject-key",
            ),
        )
        assertEquals(CapacityAdmissionOutcome.REJECT, rejected.value?.outcome)
        assertNull(rejected.value?.lease)
        assertNull(rejected.value?.retryAfterMillis)
        assertEquals(rejectedSnapshot.stateVersion, rejected.value?.usage?.stateVersion)
    }

    @Test
    fun `stable digest replay and concurrent state CAS allow no duplicate reservation`() {
        val replayHarness = newHarness()
        val replaySnapshot = requireNotNull(replayHarness.snapshot("replay").value)
        val amount = replayHarness.criticalDemand(replaySnapshot)
        val firstRequest = replayHarness.admissionRequest(replaySnapshot, amount, "stable-key", suffix = "first")
        val secondRequest = replayHarness.admissionRequest(replaySnapshot, amount, "stable-key", suffix = "second")
        assertFalse(firstRequest.bindingDigest == secondRequest.bindingDigest)
        assertEquals(firstRequest.idempotencyScope.scopeDigest, secondRequest.idempotencyScope.scopeDigest)
        assertEquals(firstRequest.idempotencyBindingDigest, secondRequest.idempotencyBindingDigest)

        val first = replayHarness.probe.admit(firstRequest)
        val replay = replayHarness.probe.admit(secondRequest)
        assertEquals(CapacityAdmissionOutcome.THROTTLE, first.value?.outcome)
        assertTrue(replay.replayed)
        assertEquals(first.value?.decisionDigest, replay.value?.decisionDigest)

        val changed = replayHarness.probe.admit(
            replayHarness.admissionRequest(replaySnapshot, amount + 1L, "stable-key", suffix = "changed"),
        )
        assertEquals(CapacityProviderErrorCode.STATE_CONFLICT, changed.errorCode)

        val raceHarness = newHarness()
        val raceSnapshot = requireNotNull(raceHarness.snapshot("race").value)
        val firstContender = raceHarness.admissionRequest(
            raceSnapshot, raceHarness.normalDemand(raceSnapshot), "race-one", suffix = "race-one",
        )
        val secondContender = raceHarness.admissionRequest(
            raceSnapshot, raceHarness.normalDemand(raceSnapshot), "race-two", suffix = "race-two",
        )
        val results = CapacityContractAssertions.race(
            Callable { raceHarness.probe.admit(firstContender) },
            Callable { raceHarness.probe.admit(secondContender) },
            concurrentTimeout(),
        )
        assertEquals(1, results.count { it.value?.outcome == CapacityAdmissionOutcome.ADMIT })
        assertEquals(1, results.count { it.errorCode == CapacityProviderErrorCode.STATE_CONFLICT })
        val winner = requireNotNull(results.single { it.value != null }.value)
        assertEquals(raceSnapshot.stateVersion + 1L, winner.usage.stateVersion)
        assertTrue(requireNotNull(winner.lease).fencingToken > 0L)
    }

    @Test
    fun `stateful admission renewal and release replay canonical outcomes and reject changed arguments`() {
        val admissionHarness = newHarness()
        val admissionSnapshot = requireNotNull(admissionHarness.snapshot("stateful-admit").value)
        val admissionAmount = admissionHarness.normalDemand(admissionSnapshot)
        val firstAdmission = requireNotNull(admissionHarness.probe.admit(
            admissionHarness.admissionRequest(
                admissionSnapshot, admissionAmount, "stateful-admit-key", suffix = "stateful-admit-first",
            ),
        ).value)
        assertEquals(CapacityAdmissionOutcome.ADMIT, firstAdmission.outcome)
        val replayedAdmission = admissionHarness.probe.admit(
            admissionHarness.admissionRequest(
                admissionSnapshot, admissionAmount, "stateful-admit-key", suffix = "stateful-admit-replay",
            ),
        )
        assertTrue(replayedAdmission.replayed)
        assertEquals(firstAdmission.decisionDigest, replayedAdmission.value?.decisionDigest)
        assertEquals(
            CapacityProviderErrorCode.STATE_CONFLICT,
            admissionHarness.probe.admit(
                admissionHarness.admissionRequest(
                    admissionSnapshot, admissionAmount + 1L, "stateful-admit-key", suffix = "stateful-admit-changed",
                ),
            ).errorCode,
        )

        val renewalHarness = newHarness()
        val renewalSnapshot = requireNotNull(renewalHarness.snapshot("stateful-renew").value)
        val renewalLease = requireNotNull(renewalHarness.probe.admit(
            renewalHarness.admissionRequest(
                renewalSnapshot, renewalHarness.normalDemand(renewalSnapshot), "stateful-renew-seed",
            ),
        ).value?.lease)
        val firstRenewalRequest = renewalHarness.renewalRequest(
            renewalLease, "stateful-renew-key", "stateful-renew-first",
        )
        val firstRenewal = requireNotNull(renewalHarness.probe.renew(firstRenewalRequest).value)
        val replayedRenewal = renewalHarness.probe.renew(
            renewalHarness.renewalRequest(
                renewalLease,
                "stateful-renew-key",
                "stateful-renew-replay",
                firstRenewalRequest.requestedExpiresAt,
            ),
        )
        assertTrue(replayedRenewal.replayed)
        assertEquals(firstRenewal.receiptDigest, replayedRenewal.value?.receiptDigest)
        assertEquals(firstRenewal.renewedLease.fencingToken, replayedRenewal.value?.renewedLease?.fencingToken)
        assertEquals(
            CapacityProviderErrorCode.STATE_CONFLICT,
            renewalHarness.probe.renew(
                renewalHarness.renewalRequest(
                    renewalLease,
                    "stateful-renew-key",
                    "stateful-renew-changed",
                    firstRenewalRequest.requestedExpiresAt + 1L,
                ),
            ).errorCode,
        )

        val releaseHarness = newHarness()
        val releaseSnapshot = requireNotNull(releaseHarness.snapshot("stateful-release").value)
        val releaseLease = requireNotNull(releaseHarness.probe.admit(
            releaseHarness.admissionRequest(
                releaseSnapshot, releaseHarness.normalDemand(releaseSnapshot), "stateful-release-seed",
            ),
        ).value?.lease)
        val firstRelease = requireNotNull(releaseHarness.probe.release(
            releaseHarness.releaseRequest(releaseLease, "stateful-release-key", "stateful-release-first"),
        ).value)
        assertEquals(0L, firstRelease.usage.measureFor(CapacityDimension.QUEUE_DEPTH)?.reserved)
        val replayedRelease = releaseHarness.probe.release(
            releaseHarness.releaseRequest(releaseLease, "stateful-release-key", "stateful-release-replay"),
        )
        assertTrue(replayedRelease.replayed)
        assertEquals(firstRelease.receiptDigest, replayedRelease.value?.receiptDigest)
        assertEquals(
            CapacityProviderErrorCode.STATE_CONFLICT,
            releaseHarness.probe.release(
                releaseHarness.releaseRequest(
                    releaseLease, "stateful-release-key", "stateful-release-changed", "operator_cancelled",
                ),
            ).errorCode,
        )
    }

    @Test
    fun `cross tenant same resource is isolated after tenant A writes and renders no trusted identity`() {
        val harness = newHarness()
        val tenantSnapshot = requireNotNull(harness.snapshot("tenant-a-seed").value)
        assertEquals(
            CapacityAdmissionOutcome.ADMIT,
            harness.probe.admit(
                harness.admissionRequest(
                    tenantSnapshot, harness.normalDemand(tenantSnapshot), "tenant-a-seed-key",
                ),
            ).value?.outcome,
        )
        val fingerprintAfterTenantA = harness.inspection?.persistenceFingerprint()
        val otherTenant = Identifier("tenant-other")
        val otherTarget = ResourceScope.resource(
            otherTenant,
            "document",
            Identifier(requireNotNull(harness.hierarchy.target.resourceId).value),
            harness.hierarchy.providerId,
        )
        val now = harness.hierarchy.nowEpochMilli
        val request = CapacitySnapshotRequest(
            Identifier("snapshot-other"),
            harness.hierarchy.context(
                CapacityPurpose.OBSERVE,
                "other-tenant",
                tenantId = otherTenant,
                authorizedScope = otherTarget,
            ),
            otherTarget,
            harness.hierarchy.workload,
            now,
            now + 500L,
        )
        val result = harness.probe.snapshot(request)

        assertNull(result.value)
        assertTrue(result.errorCode == CapacityProviderErrorCode.NOT_FOUND ||
            result.errorCode == CapacityProviderErrorCode.UNAUTHORIZED
        )
        val otherContext = harness.hierarchy.context(
            CapacityPurpose.ADMISSION,
            "other-tenant-admit",
            tenantId = otherTenant,
            authorizedScope = otherTarget,
        )
        val mutation = harness.probe.admit(
            CapacityAdmissionRequest(
                Identifier("admission-other-tenant"),
                otherContext,
                otherTarget,
                harness.hierarchy.workload,
                listOf(CapacityDemand(CapacityDimension.QUEUE_DEPTH, 1L)),
                emptySet(),
                CapacityWritePrecondition.admission(
                    CapacityContractAssertions.sha256("other-tenant-key"),
                    tenantSnapshot.stateVersion,
                    tenantSnapshot.policyResolution.resolutionDigest,
                ),
                now,
                now + 500L,
            ),
        )
        assertNull(mutation.value)
        assertEquals(CapacityProviderErrorCode.UNAUTHORIZED, mutation.errorCode)
        if (fingerprintAfterTenantA != null) {
            assertEquals(fingerprintAfterTenantA, harness.inspection?.persistenceFingerprint())
        }
        CapacityContractAssertions.assertRedacted(
            request.toString(),
            otherTenant.value,
            otherTarget.resourceId?.value ?: "",
        )
    }
}
