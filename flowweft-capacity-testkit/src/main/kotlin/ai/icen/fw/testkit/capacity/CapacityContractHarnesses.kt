package ai.icen.fw.testkit.capacity

import ai.icen.fw.capacity.api.CapacityAdmissionDecision
import ai.icen.fw.capacity.api.CapacityAdmissionRequest
import ai.icen.fw.capacity.api.CapacityDegradationCapability
import ai.icen.fw.capacity.api.CapacityDemand
import ai.icen.fw.capacity.api.CapacityDimension
import ai.icen.fw.capacity.api.CapacityLeaseReleaseReceipt
import ai.icen.fw.capacity.api.CapacityLeaseReleaseRequest
import ai.icen.fw.capacity.api.CapacityLeaseRenewalReceipt
import ai.icen.fw.capacity.api.CapacityLeaseRenewalRequest
import ai.icen.fw.capacity.api.CapacityProviderResult
import ai.icen.fw.capacity.api.CapacityProviderSpi
import ai.icen.fw.capacity.api.CapacityPurpose
import ai.icen.fw.capacity.api.CapacityReservationLease
import ai.icen.fw.capacity.api.CapacitySnapshotRequest
import ai.icen.fw.capacity.api.CapacityTrustedContextProvider
import ai.icen.fw.capacity.api.CapacityUsageSnapshot
import ai.icen.fw.capacity.api.CapacityWritePrecondition
import ai.icen.fw.capacity.runtime.CapacityAfterCommitSignalPort
import ai.icen.fw.capacity.runtime.CapacityDegradationSafetyPolicy
import ai.icen.fw.capacity.runtime.CapacityExternalCallBoundary
import ai.icen.fw.capacity.runtime.CapacityGuardCommand
import ai.icen.fw.capacity.runtime.CapacityGuardReceipt
import ai.icen.fw.capacity.runtime.CapacityLeaseReleaseCommand
import ai.icen.fw.capacity.runtime.CapacityLeaseReleaseRuntimeReceipt
import ai.icen.fw.capacity.runtime.CapacityLeaseRenewCommand
import ai.icen.fw.capacity.runtime.CapacityLeaseRenewRuntimeReceipt
import ai.icen.fw.capacity.runtime.CapacityObserveCommand
import ai.icen.fw.capacity.runtime.CapacityObservationReceipt
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconcileCommand
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationEvidence
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationPort
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationReceipt
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationRequest
import ai.icen.fw.capacity.runtime.CapacityPolicySource
import ai.icen.fw.capacity.runtime.CapacityRuntime
import ai.icen.fw.capacity.runtime.CapacityRuntimeResult
import ai.icen.fw.capacity.runtime.CapacityUnknownOutcomeReference
import ai.icen.fw.capacity.runtime.FixedCapacityPolicySource
import ai.icen.fw.capacity.runtime.ImmutableCapacityProviderRegistry
import ai.icen.fw.core.id.Identifier
import java.util.Collections

/** Recreates a provider around the same durable state for restart contracts. */
fun interface CapacityProviderRestartFactory {
    fun restart(): CapacityProviderContractHarness
}

/**
 * Public composition boundary consumed by all three suites. Production adapters can supply their
 * real provider and policy source; persistence-only hooks remain optional outside that suite.
 */
class CapacityProviderContractHarness @JvmOverloads constructor(
    val hierarchy: CapacityHierarchyFixture,
    provider: CapacityProviderSpi,
    val policySource: CapacityPolicySource,
    reconciliation: CapacityOutcomeReconciliationPort,
    val inspection: CapacityPersistenceInspection? = null,
    val faults: CapacityPersistenceFaultController? = null,
    private val restartFactory: CapacityProviderRestartFactory? = null,
) {
    val probe: CapacityProviderProbe = CapacityProviderProbe.wrapping(provider, reconciliation)

    @JvmOverloads
    fun snapshot(suffix: String = "snapshot"): CapacityProviderResult<CapacityUsageSnapshot> {
        val now = hierarchy.nowEpochMilli
        return probe.snapshot(
            CapacitySnapshotRequest(
                Identifier("snapshot-$suffix"),
                hierarchy.context(CapacityPurpose.OBSERVE, "observe-$suffix"),
                hierarchy.target,
                hierarchy.workload,
                now,
                now + 500L,
            ),
        )
    }

    @JvmOverloads
    fun admissionRequest(
        snapshot: CapacityUsageSnapshot,
        amount: Long,
        rawIdempotencyKey: String,
        permittedDegradations: Collection<CapacityDegradationCapability> = emptyList(),
        suffix: String = "admission",
        expectedPolicyResolutionDigest: String = snapshot.policyResolution.resolutionDigest,
    ): CapacityAdmissionRequest {
        val now = hierarchy.nowEpochMilli
        return CapacityAdmissionRequest(
            Identifier("admission-$suffix"),
            hierarchy.context(CapacityPurpose.ADMISSION, "admission-$suffix"),
            hierarchy.target,
            hierarchy.workload,
            listOf(CapacityDemand(CapacityDimension.QUEUE_DEPTH, amount)),
            permittedDegradations,
            CapacityWritePrecondition.admission(
                CapacityContractAssertions.sha256(rawIdempotencyKey),
                snapshot.stateVersion,
                expectedPolicyResolutionDigest,
            ),
            now,
            now + 500L,
        )
    }

    @JvmOverloads
    fun renewalRequest(
        lease: CapacityReservationLease,
        rawIdempotencyKey: String,
        suffix: String = "renewal",
        requestedExpiresAt: Long = lease.expiresAt + 1_000L,
    ): CapacityLeaseRenewalRequest {
        val now = hierarchy.nowEpochMilli
        return CapacityLeaseRenewalRequest(
            Identifier("renewal-$suffix"),
            hierarchy.context(CapacityPurpose.LEASE, "renewal-$suffix"),
            lease,
            CapacityWritePrecondition.renewal(
                CapacityContractAssertions.sha256(rawIdempotencyKey),
                lease.stateVersion,
                hierarchy.resolution(now).resolutionDigest,
            ),
            requestedExpiresAt,
            now,
            now + 500L,
        )
    }

    @JvmOverloads
    fun releaseRequest(
        lease: CapacityReservationLease,
        rawIdempotencyKey: String,
        suffix: String = "release",
        reasonCode: String = "contract_complete",
    ): CapacityLeaseReleaseRequest {
        val now = hierarchy.nowEpochMilli
        return CapacityLeaseReleaseRequest(
            Identifier("release-$suffix"),
            hierarchy.context(CapacityPurpose.LEASE, "release-$suffix"),
            lease,
            CapacityWritePrecondition.release(
                CapacityContractAssertions.sha256(rawIdempotencyKey),
                lease.stateVersion,
            ),
            reasonCode,
            now,
            now + 500L,
        )
    }

    fun reference(decision: CapacityAdmissionDecision): CapacityUnknownOutcomeReference =
        CapacityUnknownOutcomeReference(
            CapacityMutationEvidenceKey.ADMIT,
            decision.providerId,
            decision.request.target,
            decision.request.workload,
            decision.request.context.tenantId,
            decision.request.context.principalId,
            decision.request.context.principalType,
            decision.request.bindingDigest,
            decision.request.idempotencyScope.scopeDigest,
            decision.request.idempotencyBindingDigest,
        )

    fun reference(request: CapacityAdmissionRequest): CapacityUnknownOutcomeReference =
        CapacityUnknownOutcomeReference(
            CapacityMutationEvidenceKey.ADMIT,
            hierarchy.providerId,
            request.target,
            request.workload,
            request.context.tenantId,
            request.context.principalId,
            request.context.principalType,
            request.bindingDigest,
            request.idempotencyScope.scopeDigest,
            request.idempotencyBindingDigest,
        )

    fun reference(receipt: CapacityLeaseRenewalReceipt): CapacityUnknownOutcomeReference =
        CapacityUnknownOutcomeReference(
            CapacityMutationEvidenceKey.RENEW,
            receipt.providerId,
            receipt.request.lease.target,
            receipt.request.lease.workload,
            receipt.request.context.tenantId,
            receipt.request.context.principalId,
            receipt.request.context.principalType,
            receipt.request.bindingDigest,
            receipt.request.idempotencyScope.scopeDigest,
            receipt.request.idempotencyBindingDigest,
        )

    fun reference(receipt: CapacityLeaseReleaseReceipt): CapacityUnknownOutcomeReference =
        CapacityUnknownOutcomeReference(
            CapacityMutationEvidenceKey.RELEASE,
            receipt.providerId,
            receipt.request.lease.target,
            receipt.request.lease.workload,
            receipt.request.context.tenantId,
            receipt.request.context.principalId,
            receipt.request.context.principalType,
            receipt.request.bindingDigest,
            receipt.request.idempotencyScope.scopeDigest,
            receipt.request.idempotencyBindingDigest,
        )

    @JvmOverloads
    fun reconcile(
        reference: CapacityUnknownOutcomeReference,
        suffix: String = "reconcile",
    ): CapacityOutcomeReconciliationEvidence {
        val now = hierarchy.nowEpochMilli
        return probe.reconcile(
            CapacityOutcomeReconciliationRequest(
                Identifier("reconciliation-$suffix"),
                hierarchy.context(
                    ai.icen.fw.capacity.runtime.CapacityRuntimePurposes.RECONCILIATION,
                    "reconciliation-$suffix",
                ),
                reference,
                now,
                now + 500L,
            ),
        )
    }

    fun normalDemand(snapshot: CapacityUsageSnapshot): Long {
        val measure = requireNotNull(snapshot.measureFor(CapacityDimension.QUEUE_DEPTH))
        val remainingBeforeCritical = measure.effectiveLimit.criticalWatermark - measure.total
        require(remainingBeforeCritical > 1L) {
            "Capacity normal-demand fixture has no strictly-normal positive demand."
        }
        return remainingBeforeCritical - 1L
    }

    fun criticalDemand(snapshot: CapacityUsageSnapshot): Long {
        val measure = requireNotNull(snapshot.measureFor(CapacityDimension.QUEUE_DEPTH))
        val amount = measure.effectiveLimit.criticalWatermark - measure.total
        require(amount > 0L) { "Capacity critical-demand fixture has no remaining headroom." }
        return amount
    }

    fun rejectingDemand(snapshot: CapacityUsageSnapshot): Long {
        val measure = requireNotNull(snapshot.measureFor(CapacityDimension.QUEUE_DEPTH))
        require(measure.total <= measure.effectiveLimit.limit) {
            "Capacity rejecting-demand fixture already exceeds its hard limit."
        }
        return Math.addExact(measure.effectiveLimit.limit - measure.total, 1L).also { amount ->
            require(amount > 0L) { "Capacity rejecting demand must be positive." }
        }
    }

    fun restart(): CapacityProviderContractHarness = requireNotNull(restartFactory) {
        "Capacity persistence contract requires a provider restart factory."
    }.restart()

    companion object {
        @JvmStatic
        @JvmOverloads
        fun inMemory(
            hierarchy: CapacityHierarchyFixture = CapacityHierarchyFixture.standard(),
        ): CapacityProviderContractHarness {
            val durable = CapacityInMemoryDurableState.create(hierarchy)
            return inMemory(hierarchy, durable)
        }

        private fun inMemory(
            hierarchy: CapacityHierarchyFixture,
            durable: CapacityInMemoryDurableState,
        ): CapacityProviderContractHarness {
            val provider = DeterministicCapacityProvider.create(hierarchy, durable)
            return CapacityProviderContractHarness(
                hierarchy,
                provider,
                FixedCapacityPolicySource(hierarchy.policies),
                provider,
                CapacityInMemoryPersistenceInspection.observing(durable),
                provider,
                CapacityProviderRestartFactory { inMemory(hierarchy, durable) },
            )
        }
    }
}

/** Complete provider-neutral runtime composition used by the runtime suite. */
class CapacityRuntimeContractHarness @JvmOverloads constructor(
    val providerHarness: CapacityProviderContractHarness,
    val clock: DeterministicCapacityClock = DeterministicCapacityClock.startingAt(
        providerHarness.hierarchy.nowEpochMilli,
    ),
    val identifiers: DeterministicCapacityIds = DeterministicCapacityIds.create(),
    trustedContexts: CapacityTrustedContextProvider? = null,
    externalCalls: CapacityExternalCallBoundary = CapacityExternalCallBoundary.UNMANAGED_NON_TRANSACTIONAL,
) {
    val contexts: StrictCapacityContextFixture? = if (trustedContexts == null) {
        StrictCapacityContextFixture.forHierarchy(providerHarness.hierarchy, clock)
    } else {
        null
    }
    private val metricsMutable = Collections.synchronizedList(
        mutableListOf<ai.icen.fw.capacity.api.CapacityMetricEvidence>(),
    )
    val metrics: List<ai.icen.fw.capacity.api.CapacityMetricEvidence> =
        Collections.unmodifiableList(metricsMutable)
    val runtime: CapacityRuntime = CapacityRuntime(
        trustedContexts ?: requireNotNull(contexts),
        providerHarness.policySource,
        ImmutableCapacityProviderRegistry(mapOf(providerHarness.hierarchy.providerId to providerHarness.probe)),
        providerHarness.probe,
        externalCalls,
        CapacityDegradationSafetyPolicy.STANDARD_CAPACITY_ONLY,
        { evidence -> metricsMutable.add(evidence) },
        CapacityAfterCommitSignalPort { signal -> signal.emit() },
        clock,
        identifiers,
    )

    @JvmOverloads
    fun guard(
        amount: Long,
        permittedDegradations: Collection<CapacityDegradationCapability> = emptyList(),
    ): CapacityGuardCommand = CapacityGuardCommand(
        providerHarness.hierarchy.providerId,
        providerHarness.hierarchy.target,
        providerHarness.hierarchy.workload,
        listOf(CapacityDemand(CapacityDimension.QUEUE_DEPTH, amount)),
        permittedDegradations,
        500L,
    )

    @JvmOverloads
    fun admit(
        rawIdempotencyKey: String,
        amount: Long = 1L,
        permittedDegradations: Collection<CapacityDegradationCapability> = emptyList(),
    ): CapacityRuntimeResult<CapacityGuardReceipt> = runtime.admission.admit(
        guard(amount, permittedDegradations),
        rawIdempotencyKey,
    )

    fun observe(): CapacityRuntimeResult<CapacityObservationReceipt> = runtime.observation.observe(
        CapacityObserveCommand(
            providerHarness.hierarchy.providerId,
            providerHarness.hierarchy.target,
            providerHarness.hierarchy.workload,
            500L,
        ),
    )

    fun renew(
        lease: CapacityReservationLease,
        rawIdempotencyKey: String,
    ): CapacityRuntimeResult<CapacityLeaseRenewRuntimeReceipt> = runtime.leases.renew(
        CapacityLeaseRenewCommand(lease, lease.expiresAt + 1_000L, 500L),
        rawIdempotencyKey,
    )

    fun release(
        lease: CapacityReservationLease,
        rawIdempotencyKey: String,
    ): CapacityRuntimeResult<CapacityLeaseReleaseRuntimeReceipt> = runtime.leases.release(
        CapacityLeaseReleaseCommand(lease, "contract_complete", 500L),
        rawIdempotencyKey,
    )

    fun reconcile(
        reference: CapacityUnknownOutcomeReference,
    ): CapacityRuntimeResult<CapacityOutcomeReconciliationReceipt> = runtime.reconciliation.reconcile(
        CapacityOutcomeReconcileCommand(reference, 500L),
    )

    companion object {
        @JvmStatic
        fun inMemory(): CapacityRuntimeContractHarness =
            CapacityRuntimeContractHarness(CapacityProviderContractHarness.inMemory())
    }
}
