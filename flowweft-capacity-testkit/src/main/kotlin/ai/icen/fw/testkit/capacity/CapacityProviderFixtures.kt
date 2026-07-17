package ai.icen.fw.testkit.capacity

import ai.icen.fw.capacity.api.CapacityAdmissionDecision
import ai.icen.fw.capacity.api.CapacityAdmissionOutcome
import ai.icen.fw.capacity.api.CapacityAdmissionRequest
import ai.icen.fw.capacity.api.CapacityDegradationCapability
import ai.icen.fw.capacity.api.CapacityDemand
import ai.icen.fw.capacity.api.CapacityDecisionReason
import ai.icen.fw.capacity.api.CapacityDimension
import ai.icen.fw.capacity.api.CapacityDoctorReport
import ai.icen.fw.capacity.api.CapacityDoctorRequest
import ai.icen.fw.capacity.api.CapacityDoctorSignal
import ai.icen.fw.capacity.api.CapacityDoctorSignalCode
import ai.icen.fw.capacity.api.CapacityDoctorStatus
import ai.icen.fw.capacity.api.CapacityLeaseReleaseReceipt
import ai.icen.fw.capacity.api.CapacityLeaseReleaseRequest
import ai.icen.fw.capacity.api.CapacityLeaseRenewalReceipt
import ai.icen.fw.capacity.api.CapacityLeaseRenewalRequest
import ai.icen.fw.capacity.api.CapacityMeasureSnapshot
import ai.icen.fw.capacity.api.CapacityPolicy
import ai.icen.fw.capacity.api.CapacityPolicyResolution
import ai.icen.fw.capacity.api.CapacityProviderCapability
import ai.icen.fw.capacity.api.CapacityProviderDescriptor
import ai.icen.fw.capacity.api.CapacityProviderErrorCode
import ai.icen.fw.capacity.api.CapacityProviderResult
import ai.icen.fw.capacity.api.CapacityProviderSpi
import ai.icen.fw.capacity.api.CapacityReservationLease
import ai.icen.fw.capacity.api.CapacitySnapshotRequest
import ai.icen.fw.capacity.api.CapacityTrustedContext
import ai.icen.fw.capacity.api.CapacityUnit
import ai.icen.fw.capacity.api.CapacityUsageSnapshot
import ai.icen.fw.capacity.api.CapacityWritePrecondition
import ai.icen.fw.capacity.api.CapacityPurpose
import ai.icen.fw.capacity.api.CapacityScopeLevel
import ai.icen.fw.capacity.api.ResourceScope
import ai.icen.fw.capacity.api.WorkloadKind
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationEvidence
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationPort
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationRequest
import ai.icen.fw.core.id.Identifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class CapacityTestIntentStatus {
    PREPARED,
    NOT_APPLIED,
    APPLIED,
}

/** Digest-only key for test-side inspection of one durable mutation. */
class CapacityMutationEvidenceKey(
    operation: String,
    tenantId: Identifier,
    idempotencyScopeDigest: String,
) {
    val operation: String = operation.also {
        require(it in SUPPORTED_OPERATIONS) { "Capacity inspection operation is unsupported." }
    }
    val tenantId: Identifier = tenantId
    val idempotencyScopeDigest: String = idempotencyScopeDigest.also {
        requireSha256Shape(it, "Capacity inspection idempotency scope")
    }

    internal val storageKey: String = CapacityContractAssertions.sha256(
        "${this.tenantId.value}:${this.operation}:${this.idempotencyScopeDigest}",
    )

    override fun equals(other: Any?): Boolean =
        other is CapacityMutationEvidenceKey && storageKey == other.storageKey

    override fun hashCode(): Int = storageKey.hashCode()
    override fun toString(): String = "CapacityMutationEvidenceKey(operation=$operation, <redacted>)"

    companion object {
        const val ADMIT: String = "capacity.admit"
        const val RENEW: String = "capacity.lease.renew"
        const val RELEASE: String = "capacity.lease.release"
        private val SUPPORTED_OPERATIONS = setOf(ADMIT, RENEW, RELEASE)

        @JvmStatic
        fun admission(decision: CapacityAdmissionDecision): CapacityMutationEvidenceKey =
            CapacityMutationEvidenceKey(
                ADMIT,
                decision.request.context.tenantId,
                decision.request.idempotencyScope.scopeDigest,
            )

        @JvmStatic
        fun renewal(receipt: CapacityLeaseRenewalReceipt): CapacityMutationEvidenceKey =
            CapacityMutationEvidenceKey(
                RENEW,
                receipt.request.context.tenantId,
                receipt.request.idempotencyScope.scopeDigest,
            )

        @JvmStatic
        fun release(receipt: CapacityLeaseReleaseReceipt): CapacityMutationEvidenceKey =
            CapacityMutationEvidenceKey(
                RELEASE,
                receipt.request.context.tenantId,
                receipt.request.idempotencyScope.scopeDigest,
            )
    }
}

/** Test-only, value-free view of intent/canonical/outbox durability. */
interface CapacityPersistenceInspection {
    fun intentStatus(key: CapacityMutationEvidenceKey): CapacityTestIntentStatus?
    fun canonicalOutcomeDigest(key: CapacityMutationEvidenceKey): String?
    fun outboxEvidenceCount(key: CapacityMutationEvidenceKey): Long

    /** Digest-only snapshot derived independently from the provider/reconciliation call path. */
    fun persistenceFingerprint(): String

    /** Counts calls at the raw provider mutation boundary, including calls hidden inside reconciliation. */
    fun mutationInvocationCount(): Long
}

/** Optional deterministic fault controls required only by the persistence contract suite. */
interface CapacityPersistenceFaultController {
    /** Commit the canonical transition, then make the caller observe a transport exception. */
    fun failNextMutationAfterApply()

    /** Persist PREPARED only and return an unknown provider result without applying state. */
    fun leaveNextMutationPrepared()
}

/** Counts mutation and exact reconciliation calls without rendering request values. */
class CapacityProviderProbe private constructor(
    private val provider: CapacityProviderSpi,
    private val reconciliation: CapacityOutcomeReconciliationPort,
) : CapacityProviderSpi, CapacityOutcomeReconciliationPort {
    private val mutations = AtomicInteger()
    private val reconciliations = AtomicInteger()
    private val observations = AtomicInteger()

    override fun descriptor(): CapacityProviderDescriptor = provider.descriptor()

    override fun snapshot(request: CapacitySnapshotRequest): CapacityProviderResult<CapacityUsageSnapshot> {
        observations.incrementAndGet()
        return provider.snapshot(request)
    }

    override fun admit(request: CapacityAdmissionRequest): CapacityProviderResult<CapacityAdmissionDecision> {
        mutations.incrementAndGet()
        return provider.admit(request)
    }

    override fun renew(request: CapacityLeaseRenewalRequest): CapacityProviderResult<CapacityLeaseRenewalReceipt> {
        mutations.incrementAndGet()
        return provider.renew(request)
    }

    override fun release(request: CapacityLeaseReleaseRequest): CapacityProviderResult<CapacityLeaseReleaseReceipt> {
        mutations.incrementAndGet()
        return provider.release(request)
    }

    override fun doctor(request: CapacityDoctorRequest): CapacityProviderResult<CapacityDoctorReport> =
        provider.doctor(request)

    override fun reconcile(request: CapacityOutcomeReconciliationRequest): CapacityOutcomeReconciliationEvidence {
        reconciliations.incrementAndGet()
        return reconciliation.reconcile(request)
    }

    fun mutationCount(): Int = mutations.get()
    fun reconciliationCount(): Int = reconciliations.get()
    fun observationCount(): Int = observations.get()

    companion object {
        @JvmStatic
        fun wrapping(
            provider: CapacityProviderSpi,
            reconciliation: CapacityOutcomeReconciliationPort,
        ): CapacityProviderProbe = CapacityProviderProbe(provider, reconciliation)
    }
}

/** Shared in-memory state makes provider recreation behave like a process restart. */
class CapacityInMemoryDurableState private constructor(
    hierarchy: CapacityHierarchyFixture,
) {
    internal val lock = Any()
    internal var stateVersion: Long = 0L
    internal var nextFence: Long = 0L
    internal val used = linkedMapOf(
        ai.icen.fw.capacity.api.CapacityDimension.QUEUE_DEPTH.bindingCode to 8L,
        ai.icen.fw.capacity.api.CapacityDimension.IN_FLIGHT_BYTES.bindingCode to 1_024L,
    )
    internal val reserved = linkedMapOf<String, Long>()
    internal val activeLeases = linkedMapOf<String, CapacityReservationLease>()
    internal val intents = linkedMapOf<String, CapacityTestIntentRecord>()
    internal val sequence = AtomicLong()
    internal val mutationInvocations = AtomicLong()
    internal val failAfterApply = AtomicBoolean()
    internal val leavePrepared = AtomicBoolean()
    internal val expectedTenant = hierarchy.tenantId

    companion object {
        @JvmStatic
        fun create(hierarchy: CapacityHierarchyFixture): CapacityInMemoryDurableState =
            CapacityInMemoryDurableState(hierarchy)
    }
}

internal class CapacityTestIntentRecord(
    val key: CapacityMutationEvidenceKey,
    val idempotencyBindingDigest: String,
    var status: CapacityTestIntentStatus = CapacityTestIntentStatus.PREPARED,
    var canonicalOutcome: Any? = null,
    var canonicalDigest: String? = null,
    var errorCode: CapacityProviderErrorCode? = null,
    var outboxCount: Long = 0L,
)

private class CapacityTestIntentSnapshot(
    val status: CapacityTestIntentStatus,
    val idempotencyBindingDigest: String,
    val canonicalOutcome: Any?,
)

/** Independent, read-only state probe used by persistence contracts. */
class CapacityInMemoryPersistenceInspection private constructor(
    private val durable: CapacityInMemoryDurableState,
) : CapacityPersistenceInspection {
    override fun intentStatus(key: CapacityMutationEvidenceKey): CapacityTestIntentStatus? =
        synchronized(durable.lock) { durable.intents[key.storageKey]?.status }

    override fun canonicalOutcomeDigest(key: CapacityMutationEvidenceKey): String? =
        synchronized(durable.lock) { durable.intents[key.storageKey]?.canonicalDigest }

    override fun outboxEvidenceCount(key: CapacityMutationEvidenceKey): Long =
        synchronized(durable.lock) { durable.intents[key.storageKey]?.outboxCount ?: 0L }

    override fun persistenceFingerprint(): String = synchronized(durable.lock) {
        val evidence = ArrayList<String>()
        evidence += "state:${durable.stateVersion}:${durable.nextFence}"
        durable.used.toSortedMap().forEach { (dimension, value) -> evidence += "used:$dimension:$value" }
        durable.reserved.toSortedMap().forEach { (dimension, value) -> evidence += "reserved:$dimension:$value" }
        durable.activeLeases.toSortedMap().forEach { (_, lease) -> evidence += "lease:${lease.leaseDigest}" }
        durable.intents.toSortedMap().forEach { (key, intent) ->
            evidence += "intent:$key:${intent.status}:${intent.idempotencyBindingDigest}:" +
                "${intent.canonicalDigest ?: "-"}:${intent.outboxCount}"
        }
        CapacityContractAssertions.sha256(evidence.joinToString("|"))
    }

    override fun mutationInvocationCount(): Long = durable.mutationInvocations.get()

    companion object {
        @JvmStatic
        fun observing(durable: CapacityInMemoryDurableState): CapacityInMemoryPersistenceInspection =
            CapacityInMemoryPersistenceInspection(durable)
    }
}

/**
 * Synthetic reference provider. It deliberately mirrors the public JDBC decision baseline while
 * keeping all operations in one synchronized state transition.
 */
class DeterministicCapacityProvider private constructor(
    private val hierarchy: CapacityHierarchyFixture,
    private val durable: CapacityInMemoryDurableState,
) : CapacityProviderSpi,
    CapacityOutcomeReconciliationPort,
    CapacityPersistenceFaultController {

    override fun descriptor(): CapacityProviderDescriptor = CapacityProviderDescriptor(
        hierarchy.providerId,
        "flowweft.capacity.test-provider.v1",
        setOf(
            CapacityProviderCapability.ATOMIC_ADMISSION,
            CapacityProviderCapability.HIERARCHICAL_POLICIES,
            CapacityProviderCapability.FENCED_LEASES,
            CapacityProviderCapability.USAGE_SNAPSHOTS,
            CapacityProviderCapability.DOCTOR_EVIDENCE,
        ),
        CapacityContractAssertions.sha256("capacity-test-provider"),
        hierarchy.nowEpochMilli - 5_000L,
        hierarchy.expiresAtEpochMilli,
    )

    override fun snapshot(request: CapacitySnapshotRequest): CapacityProviderResult<CapacityUsageSnapshot> = try {
        if (request.context.tenantId != hierarchy.tenantId || request.target != hierarchy.target) {
            CapacityProviderResult.failure(CapacityProviderErrorCode.NOT_FOUND)
        } else {
            val resolution = resolve(request.requestedAt)
            CapacityProviderResult.success(synchronized(durable.lock) {
                usage(resolution, request.requestedAt, request.deadlineAt)
            })
        }
    } catch (_: Exception) {
        CapacityProviderResult.failure(CapacityProviderErrorCode.UNAVAILABLE)
    }

    override fun admit(request: CapacityAdmissionRequest): CapacityProviderResult<CapacityAdmissionDecision> {
        durable.mutationInvocations.incrementAndGet()
        if (request.context.tenantId != hierarchy.tenantId || request.target != hierarchy.target) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.UNAUTHORIZED)
        }
        val result = synchronized(durable.lock) { admitLocked(request) }
        throwAfterApplyIfRequested(result)
        return result
    }

    override fun renew(request: CapacityLeaseRenewalRequest): CapacityProviderResult<CapacityLeaseRenewalReceipt> {
        durable.mutationInvocations.incrementAndGet()
        if (request.context.tenantId != hierarchy.tenantId || request.lease.providerId != hierarchy.providerId) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.UNAUTHORIZED)
        }
        val result = synchronized(durable.lock) { renewLocked(request) }
        throwAfterApplyIfRequested(result)
        return result
    }

    override fun release(request: CapacityLeaseReleaseRequest): CapacityProviderResult<CapacityLeaseReleaseReceipt> {
        durable.mutationInvocations.incrementAndGet()
        if (request.context.tenantId != hierarchy.tenantId || request.lease.providerId != hierarchy.providerId) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.UNAUTHORIZED)
        }
        val result = synchronized(durable.lock) { releaseLocked(request) }
        throwAfterApplyIfRequested(result)
        return result
    }

    override fun doctor(request: CapacityDoctorRequest): CapacityProviderResult<CapacityDoctorReport> {
        if (request.context.tenantId != hierarchy.tenantId) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.UNAUTHORIZED)
        }
        val signal = CapacityDoctorSignal(
            Identifier("capacity-doctor-${durable.sequence.incrementAndGet()}"),
            CapacityDoctorSignalCode.CAPACITY_WITHIN_LIMIT,
            CapacityDoctorStatus.READY,
            request.target.level,
            null,
            null,
            null,
            null,
            null,
            request.requestedAt,
            request.deadlineAt,
        )
        return CapacityProviderResult.success(
            CapacityDoctorReport(
                hierarchy.providerId,
                CapacityDoctorStatus.READY,
                listOf(signal),
                request.requestedAt,
                request.deadlineAt,
            ),
        )
    }

    override fun reconcile(request: CapacityOutcomeReconciliationRequest): CapacityOutcomeReconciliationEvidence {
        val reference = request.reference
        require(reference.providerId == hierarchy.providerId) { "Capacity reconciliation provider differs." }
        val key = CapacityMutationEvidenceKey(
            reference.operation,
            reference.tenantId,
            reference.idempotencyScopeDigest,
        )
        val record = synchronized(durable.lock) {
            durable.intents[key.storageKey]?.let { current ->
                CapacityTestIntentSnapshot(
                    current.status,
                    current.idempotencyBindingDigest,
                    if (current.status == CapacityTestIntentStatus.APPLIED) {
                        rehydrateCanonical(requireNotNull(current.canonicalOutcome))
                    } else {
                        null
                    },
                )
            }
        }
        val evidence = CapacityContractAssertions.sha256("reconcile:${reference.referenceDigest}:${record?.status}")
        if (record == null || record.status == CapacityTestIntentStatus.PREPARED ||
            record.idempotencyBindingDigest != reference.idempotencyBindingDigest
        ) {
            return CapacityOutcomeReconciliationEvidence.stillUnknown(
                request, evidence, request.requestedAt, request.deadlineAt,
            )
        }
        if (record.status == CapacityTestIntentStatus.NOT_APPLIED) {
            return CapacityOutcomeReconciliationEvidence.confirmedNotApplied(
                request, evidence, request.requestedAt, request.deadlineAt,
            )
        }
        return when (val outcome = record.canonicalOutcome) {
            is CapacityAdmissionDecision -> CapacityOutcomeReconciliationEvidence.appliedAdmission(
                request, outcome, evidence, request.requestedAt, request.deadlineAt,
            )
            is CapacityLeaseRenewalReceipt -> CapacityOutcomeReconciliationEvidence.appliedRenewal(
                request, outcome, evidence, request.requestedAt, request.deadlineAt,
            )
            is CapacityLeaseReleaseReceipt -> CapacityOutcomeReconciliationEvidence.appliedRelease(
                request, outcome, evidence, request.requestedAt, request.deadlineAt,
            )
            else -> error("Capacity applied intent omitted its canonical outcome.")
        }
    }

    override fun failNextMutationAfterApply() {
        check(durable.failAfterApply.compareAndSet(false, true)) { "Capacity after-apply fault is already armed." }
    }

    override fun leaveNextMutationPrepared() {
        check(durable.leavePrepared.compareAndSet(false, true)) { "Capacity PREPARED fault is already armed." }
    }

    private fun admitLocked(request: CapacityAdmissionRequest): CapacityProviderResult<CapacityAdmissionDecision> {
        val key = CapacityMutationEvidenceKey.admissionKey(request)
        replay<CapacityAdmissionDecision>(key, request.idempotencyBindingDigest)?.let { return it }
        val intent = prepare(key, request.idempotencyBindingDigest)
        if (durable.leavePrepared.compareAndSet(true, false)) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.INTERNAL_FAILURE)
        }
        val resolution = try {
            resolve(request.requestedAt)
        } catch (_: Exception) {
            return fail(intent, CapacityProviderErrorCode.NOT_FOUND)
        }
        if (request.precondition.expectedStateVersion != durable.stateVersion) {
            return fail(intent, CapacityProviderErrorCode.STATE_CONFLICT)
        }
        if (request.precondition.expectedPolicyResolutionDigest != resolution.resolutionDigest) {
            return fail(intent, CapacityProviderErrorCode.POLICY_CHANGED)
        }
        if (request.demands.any { resolution.limitFor(it.dimension) == null }) {
            return fail(intent, CapacityProviderErrorCode.NOT_FOUND)
        }

        val projected = LinkedHashMap(durable.reserved)
        request.demands.forEach { demand ->
            projected[demand.dimension.bindingCode] = Math.addExact(
                projected[demand.dimension.bindingCode] ?: 0L,
                demand.amount,
            )
        }
        val exceeds = resolution.effectiveLimits.any { limit ->
            total(limit.dimension.bindingCode, projected) > limit.limit
        }
        val critical = !exceeds && resolution.effectiveLimits.any { limit ->
            total(limit.dimension.bindingCode, projected) >= limit.criticalWatermark
        }
        val allowed = resolution.allowedDegradations.intersect(request.permittedDegradations)
        val outcome = when {
            exceeds -> CapacityAdmissionOutcome.REJECT
            critical && allowed.isNotEmpty() -> CapacityAdmissionOutcome.DEGRADE
            critical -> CapacityAdmissionOutcome.THROTTLE
            else -> CapacityAdmissionOutcome.ADMIT
        }
        val reserves = outcome == CapacityAdmissionOutcome.ADMIT || outcome == CapacityAdmissionOutcome.DEGRADE
        val candidateStateVersion = if (reserves) Math.addExact(durable.stateVersion, 1L) else durable.stateVersion
        val candidateFence = if (reserves) Math.addExact(durable.nextFence, 1L) else durable.nextFence
        val candidateReserved = if (reserves) projected else LinkedHashMap(durable.reserved)
        val lease = if (reserves) {
            CapacityReservationLease.issue(
                nextId("reservation"),
                nextId("lease"),
                hierarchy.providerId,
                request,
                candidateFence,
                candidateStateVersion,
                request.requestedAt,
                request.deadlineAt,
            )
        } else {
            null
        }
        val usage = usage(
            resolution,
            request.requestedAt,
            request.deadlineAt,
            candidateStateVersion,
            candidateReserved,
        )
        val decision = when (outcome) {
            CapacityAdmissionOutcome.ADMIT -> CapacityAdmissionDecision.admit(
                nextId("decision"), hierarchy.providerId, request, usage, requireNotNull(lease),
                request.requestedAt, minOf(request.deadlineAt, requireNotNull(lease).expiresAt),
            )
            CapacityAdmissionOutcome.DEGRADE -> CapacityAdmissionDecision.degrade(
                nextId("decision"), hierarchy.providerId, request, usage, requireNotNull(lease), allowed,
                CapacityDecisionReason.WATERMARK_PRESSURE, request.requestedAt,
                minOf(request.deadlineAt, requireNotNull(lease).expiresAt),
            )
            CapacityAdmissionOutcome.THROTTLE -> CapacityAdmissionDecision.throttle(
                nextId("decision"), hierarchy.providerId, request, usage, 1_000L,
                CapacityDecisionReason.WATERMARK_PRESSURE, request.requestedAt, request.deadlineAt,
            )
            CapacityAdmissionOutcome.REJECT -> CapacityAdmissionDecision.reject(
                nextId("decision"), hierarchy.providerId, request, usage,
                CapacityDecisionReason.LIMIT_EXCEEDED, request.requestedAt, request.deadlineAt,
            )
        }
        if (reserves) {
            durable.reserved.clear()
            durable.reserved.putAll(candidateReserved)
            durable.stateVersion = candidateStateVersion
            durable.nextFence = candidateFence
            durable.activeLeases[requireNotNull(lease).leaseId.value] = lease
        }
        apply(intent, decision, decision.decisionDigest)
        return CapacityProviderResult.success(decision)
    }

    private fun renewLocked(
        request: CapacityLeaseRenewalRequest,
    ): CapacityProviderResult<CapacityLeaseRenewalReceipt> {
        val key = CapacityMutationEvidenceKey.renewalKey(request)
        replay<CapacityLeaseRenewalReceipt>(key, request.idempotencyBindingDigest)?.let { return it }
        val intent = prepare(key, request.idempotencyBindingDigest)
        if (durable.leavePrepared.compareAndSet(true, false)) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.INTERNAL_FAILURE)
        }
        val current = durable.activeLeases[request.lease.leaseId.value]
        if (current == null || current.leaseDigest != request.lease.leaseDigest ||
            durable.stateVersion != request.lease.stateVersion || durable.nextFence != request.lease.fencingToken
        ) {
            return fail(intent, CapacityProviderErrorCode.STATE_CONFLICT)
        }
        val resolution = resolve(request.requestedAt)
        if (request.precondition.expectedPolicyResolutionDigest != resolution.resolutionDigest) {
            return fail(intent, CapacityProviderErrorCode.POLICY_CHANGED)
        }
        val candidateStateVersion = Math.addExact(durable.stateVersion, 1L)
        val candidateFence = Math.addExact(durable.nextFence, 1L)
        val renewed = CapacityReservationLease.renewed(
            request,
            resolution.resolutionDigest,
            candidateFence,
            candidateStateVersion,
            request.requestedAt,
            request.requestedExpiresAt,
        )
        val receipt = CapacityLeaseRenewalReceipt(
            nextId("renewal-receipt"),
            hierarchy.providerId,
            request,
            renewed,
            usage(
                resolution,
                request.requestedAt,
                request.deadlineAt,
                candidateStateVersion,
                durable.reserved,
            ),
            request.requestedAt,
        )
        durable.stateVersion = candidateStateVersion
        durable.nextFence = candidateFence
        durable.activeLeases[renewed.leaseId.value] = renewed
        apply(intent, receipt, receipt.receiptDigest)
        return CapacityProviderResult.success(receipt)
    }

    private fun releaseLocked(
        request: CapacityLeaseReleaseRequest,
    ): CapacityProviderResult<CapacityLeaseReleaseReceipt> {
        val key = CapacityMutationEvidenceKey.releaseKey(request)
        replay<CapacityLeaseReleaseReceipt>(key, request.idempotencyBindingDigest)?.let { return it }
        val intent = prepare(key, request.idempotencyBindingDigest)
        if (durable.leavePrepared.compareAndSet(true, false)) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.INTERNAL_FAILURE)
        }
        val current = durable.activeLeases[request.lease.leaseId.value]
        if (current == null || current.leaseDigest != request.lease.leaseDigest ||
            durable.stateVersion != request.lease.stateVersion || durable.nextFence != request.lease.fencingToken
        ) {
            return fail(intent, CapacityProviderErrorCode.STATE_CONFLICT)
        }
        val resolution = resolve(request.requestedAt)
        val candidateReserved = LinkedHashMap(durable.reserved)
        request.lease.demands.forEach { demand ->
            val remaining = (candidateReserved[demand.dimension.bindingCode] ?: 0L) - demand.amount
            check(remaining >= 0L) { "Capacity fixture reservation underflowed." }
            candidateReserved[demand.dimension.bindingCode] = remaining
        }
        val candidateStateVersion = Math.addExact(durable.stateVersion, 1L)
        val receipt = CapacityLeaseReleaseReceipt(
            nextId("release-receipt"),
            hierarchy.providerId,
            request,
            usage(
                resolution,
                request.requestedAt,
                request.deadlineAt,
                candidateStateVersion,
                candidateReserved,
            ),
            candidateStateVersion,
            request.requestedAt,
        )
        durable.reserved.clear()
        durable.reserved.putAll(candidateReserved)
        durable.activeLeases.remove(request.lease.leaseId.value)
        durable.stateVersion = candidateStateVersion
        apply(intent, receipt, receipt.receiptDigest)
        return CapacityProviderResult.success(receipt)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> replay(
        key: CapacityMutationEvidenceKey,
        bindingDigest: String,
    ): CapacityProviderResult<T>? {
        val current = durable.intents[key.storageKey] ?: return null
        if (current.idempotencyBindingDigest != bindingDigest) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.STATE_CONFLICT)
        }
        return when (current.status) {
            CapacityTestIntentStatus.APPLIED -> CapacityProviderResult.success(
                requireNotNull(current.canonicalOutcome) as T,
                true,
            )
            CapacityTestIntentStatus.NOT_APPLIED -> CapacityProviderResult.failure(
                current.errorCode ?: CapacityProviderErrorCode.INTERNAL_FAILURE,
            )
            CapacityTestIntentStatus.PREPARED -> CapacityProviderResult.failure(
                CapacityProviderErrorCode.INTERNAL_FAILURE,
            )
        }
    }

    private fun prepare(
        key: CapacityMutationEvidenceKey,
        bindingDigest: String,
    ): CapacityTestIntentRecord = CapacityTestIntentRecord(key, bindingDigest).also { record ->
        check(durable.intents.put(key.storageKey, record) == null) { "Capacity fixture intent already exists." }
    }

    private fun <T> fail(
        intent: CapacityTestIntentRecord,
        error: CapacityProviderErrorCode,
    ): CapacityProviderResult<T> {
        intent.status = CapacityTestIntentStatus.NOT_APPLIED
        intent.errorCode = error
        return CapacityProviderResult.failure(error)
    }

    private fun apply(intent: CapacityTestIntentRecord, outcome: Any, digest: String) {
        intent.canonicalOutcome = outcome
        intent.canonicalDigest = digest
        intent.outboxCount = 1L
        intent.status = CapacityTestIntentStatus.APPLIED
    }

    private fun resolve(atTime: Long): CapacityPolicyResolution = hierarchy.resolution(atTime)

    private fun usage(
        resolution: CapacityPolicyResolution,
        observedAt: Long,
        requestedExpiresAt: Long,
        stateVersion: Long = durable.stateVersion,
        reserved: Map<String, Long> = durable.reserved,
    ): CapacityUsageSnapshot = CapacityUsageSnapshot.capture(
        hierarchy.providerId,
        resolution,
        resolution.effectiveLimits.map { limit ->
            CapacityMeasureSnapshot(
                limit,
                durable.used[limit.dimension.bindingCode] ?: 0L,
                reserved[limit.dimension.bindingCode] ?: 0L,
            )
        },
        stateVersion,
        observedAt,
        minOf(requestedExpiresAt, resolution.expiresAt),
    )

    private fun total(bindingCode: String, projectedReserved: Map<String, Long>): Long = Math.addExact(
        durable.used[bindingCode] ?: 0L,
        projectedReserved[bindingCode] ?: 0L,
    )

    private fun nextId(kind: String): Identifier = Identifier("$kind-${durable.sequence.incrementAndGet()}")

    /**
     * Rebuilds the public canonical graph from value fields. This deliberately avoids returning the
     * object retained by the in-memory state so restart tests detect accidental reference reuse.
     */
    private fun rehydrateCanonical(outcome: Any): Any = when (outcome) {
        is CapacityAdmissionDecision -> copyAdmissionDecision(outcome)
        is CapacityLeaseRenewalReceipt -> copyRenewalReceipt(outcome)
        is CapacityLeaseReleaseReceipt -> copyReleaseReceipt(outcome)
        else -> error("Capacity canonical outcome type is unsupported.")
    }

    private fun copyAdmissionDecision(source: CapacityAdmissionDecision): CapacityAdmissionDecision {
        val request = copyAdmissionRequest(source.request)
        val usage = copyUsage(source.usage)
        val lease = source.lease?.let { current ->
            CapacityReservationLease.issue(
                copyId(current.reservationId),
                copyId(current.leaseId),
                copyId(current.providerId),
                request,
                current.fencingToken,
                current.stateVersion,
                current.acquiredAt,
                current.expiresAt,
            )
        }
        return when (source.outcome) {
            CapacityAdmissionOutcome.ADMIT -> CapacityAdmissionDecision.admit(
                copyId(source.decisionId), copyId(source.providerId), request, usage,
                requireNotNull(lease), source.decidedAt, source.expiresAt,
            )
            CapacityAdmissionOutcome.DEGRADE -> CapacityAdmissionDecision.degrade(
                copyId(source.decisionId), copyId(source.providerId), request, usage,
                requireNotNull(lease), source.degradationCapabilities.map(::copyDegradation),
                CapacityDecisionReason(requireNotNull(source.reason).value), source.decidedAt, source.expiresAt,
            )
            CapacityAdmissionOutcome.THROTTLE -> CapacityAdmissionDecision.throttle(
                copyId(source.decisionId), copyId(source.providerId), request, usage,
                requireNotNull(source.retryAfterMillis), CapacityDecisionReason(requireNotNull(source.reason).value),
                source.decidedAt, source.expiresAt,
            )
            CapacityAdmissionOutcome.REJECT -> CapacityAdmissionDecision.reject(
                copyId(source.decisionId), copyId(source.providerId), request, usage,
                CapacityDecisionReason(requireNotNull(source.reason).value), source.decidedAt, source.expiresAt,
            )
        }.also { restored ->
            check(restored.decisionDigest == source.decisionDigest) {
                "Capacity admission reconstruction changed the canonical digest."
            }
        }
    }

    private fun copyRenewalReceipt(source: CapacityLeaseRenewalReceipt): CapacityLeaseRenewalReceipt {
        val request = copyRenewalRequest(source.request)
        val renewed = CapacityReservationLease.renewed(
            request,
            source.renewedLease.policyResolutionDigest,
            source.renewedLease.fencingToken,
            source.renewedLease.stateVersion,
            source.renewedLease.updatedAt,
            source.renewedLease.expiresAt,
        )
        return CapacityLeaseRenewalReceipt(
            copyId(source.receiptId), copyId(source.providerId), request, renewed,
            copyUsage(source.usage), source.decidedAt,
        ).also { restored ->
            check(restored.receiptDigest == source.receiptDigest) {
                "Capacity renewal reconstruction changed the canonical digest."
            }
        }
    }

    private fun copyReleaseReceipt(source: CapacityLeaseReleaseReceipt): CapacityLeaseReleaseReceipt {
        val request = copyReleaseRequest(source.request)
        return CapacityLeaseReleaseReceipt(
            copyId(source.receiptId), copyId(source.providerId), request, copyUsage(source.usage),
            source.releasedStateVersion, source.releasedAt,
        ).also { restored ->
            check(restored.receiptDigest == source.receiptDigest) {
                "Capacity release reconstruction changed the canonical digest."
            }
        }
    }

    private fun copyAdmissionRequest(source: CapacityAdmissionRequest): CapacityAdmissionRequest =
        CapacityAdmissionRequest(
            copyId(source.operationId),
            copyContext(source.context),
            copyScope(source.target),
            WorkloadKind(source.workload.value),
            source.demands.map(::copyDemand),
            source.permittedDegradations.map(::copyDegradation),
            CapacityWritePrecondition.admission(
                source.precondition.idempotencyKeyDigest,
                source.precondition.expectedStateVersion,
                requireNotNull(source.precondition.expectedPolicyResolutionDigest),
            ),
            source.requestedAt,
            source.deadlineAt,
        ).also { restored ->
            check(restored.bindingDigest == source.bindingDigest) {
                "Capacity admission request reconstruction changed its binding digest."
            }
        }

    private fun copyRenewalRequest(source: CapacityLeaseRenewalRequest): CapacityLeaseRenewalRequest =
        CapacityLeaseRenewalRequest(
            copyId(source.operationId),
            copyContext(source.context),
            copyLease(source.lease),
            CapacityWritePrecondition.renewal(
                source.precondition.idempotencyKeyDigest,
                source.precondition.expectedStateVersion,
                requireNotNull(source.precondition.expectedPolicyResolutionDigest),
            ),
            source.requestedExpiresAt,
            source.requestedAt,
            source.deadlineAt,
        ).also { restored ->
            check(restored.bindingDigest == source.bindingDigest) {
                "Capacity renewal request reconstruction changed its binding digest."
            }
        }

    private fun copyReleaseRequest(source: CapacityLeaseReleaseRequest): CapacityLeaseReleaseRequest =
        CapacityLeaseReleaseRequest(
            copyId(source.operationId),
            copyContext(source.context),
            copyLease(source.lease),
            CapacityWritePrecondition.release(
                source.precondition.idempotencyKeyDigest,
                source.precondition.expectedStateVersion,
            ),
            source.reasonCode,
            source.requestedAt,
            source.deadlineAt,
        ).also { restored ->
            check(restored.bindingDigest == source.bindingDigest) {
                "Capacity release request reconstruction changed its binding digest."
            }
        }

    private fun copyLease(source: CapacityReservationLease): CapacityReservationLease {
        val admission = durable.intents.values.asSequence()
            .mapNotNull { intent -> intent.canonicalOutcome as? CapacityAdmissionDecision }
            .firstOrNull { decision -> decision.lease?.leaseDigest == source.leaseDigest }
        if (admission != null) {
            val request = copyAdmissionRequest(admission.request)
            return CapacityReservationLease.issue(
                copyId(source.reservationId), copyId(source.leaseId), copyId(source.providerId), request,
                source.fencingToken, source.stateVersion, source.acquiredAt, source.expiresAt,
            ).also { restored ->
                check(restored.leaseDigest == source.leaseDigest) {
                    "Capacity issued lease reconstruction changed its digest."
                }
            }
        }
        val renewal = durable.intents.values.asSequence()
            .mapNotNull { intent -> intent.canonicalOutcome as? CapacityLeaseRenewalReceipt }
            .firstOrNull { receipt -> receipt.renewedLease.leaseDigest == source.leaseDigest }
            ?: error("Capacity canonical lease provenance is unavailable.")
        val request = copyRenewalRequest(renewal.request)
        return CapacityReservationLease.renewed(
            request,
            source.policyResolutionDigest,
            source.fencingToken,
            source.stateVersion,
            source.updatedAt,
            source.expiresAt,
        ).also { restored ->
            check(restored.leaseDigest == source.leaseDigest) {
                "Capacity renewed lease reconstruction changed its digest."
            }
        }
    }

    private fun copyUsage(source: CapacityUsageSnapshot): CapacityUsageSnapshot {
        val resolution = copyResolution(source.policyResolution)
        val measures = source.measures.map { measure ->
            CapacityMeasureSnapshot(
                requireNotNull(resolution.limitFor(copyDimension(measure.dimension))),
                measure.used,
                measure.reserved,
            )
        }
        return CapacityUsageSnapshot.capture(
            copyId(source.providerId), resolution, measures, source.stateVersion,
            source.observedAt, source.expiresAt,
        ).also { restored ->
            check(restored.snapshotDigest == source.snapshotDigest) {
                "Capacity usage reconstruction changed its digest."
            }
        }
    }

    private fun copyResolution(source: CapacityPolicyResolution): CapacityPolicyResolution =
        CapacityPolicyResolution.resolve(
            copyScope(source.target),
            WorkloadKind(source.workload.value),
            source.policies.map(::copyPolicy),
            source.observedAt,
        ).also { restored ->
            check(restored.resolutionDigest == source.resolutionDigest) {
                "Capacity policy reconstruction changed its digest."
            }
        }

    private fun copyPolicy(source: CapacityPolicy): CapacityPolicy = CapacityPolicy(
        copyId(source.policyId),
        source.contractVersion,
        source.revision,
        source.stateVersion,
        copyScope(source.scope),
        source.workloads.map { workload -> WorkloadKind(workload.value) },
        source.limits.map { limit ->
            ai.icen.fw.capacity.api.CapacityLimit(
                copyDimension(limit.dimension), limit.limit, limit.warningWatermark, limit.criticalWatermark,
            )
        },
        source.effectiveFrom,
        source.expiresAt,
        source.degradationCapabilities.map(::copyDegradation),
        source.enabled,
    )

    private fun copyContext(source: CapacityTrustedContext): CapacityTrustedContext =
        CapacityTrustedContext.authenticated(
            copyId(source.tenantId),
            copyId(source.principalId),
            source.principalType,
            copyId(source.requestId),
            CapacityPurpose(source.purpose.value),
            copyScope(source.authorizedScope),
            copyId(source.authenticationId),
            copyId(source.authorizationDecisionId),
            source.authorizationRevision,
            source.authorizationEvidenceDigest,
            source.initiatedAt,
            source.authorizationExpiresAt,
        )

    private fun copyScope(source: ResourceScope): ResourceScope = when (source.level) {
        CapacityScopeLevel.SYSTEM -> ResourceScope.system()
        CapacityScopeLevel.TENANT -> ResourceScope.tenant(copyId(requireNotNull(source.tenantId)))
        CapacityScopeLevel.PROVIDER -> ResourceScope.provider(
            copyId(requireNotNull(source.tenantId)), copyId(requireNotNull(source.providerId)),
        )
        CapacityScopeLevel.RESOURCE -> ResourceScope.resource(
            copyId(requireNotNull(source.tenantId)), requireNotNull(source.resourceType),
            copyId(requireNotNull(source.resourceId)), source.providerId?.let(::copyId),
        )
    }

    private fun copyDemand(source: CapacityDemand): CapacityDemand =
        CapacityDemand(copyDimension(source.dimension), source.amount)

    private fun copyDimension(source: CapacityDimension): CapacityDimension =
        CapacityDimension(source.code, CapacityUnit(source.unit.value))

    private fun copyDegradation(source: CapacityDegradationCapability): CapacityDegradationCapability =
        CapacityDegradationCapability(source.value)

    private fun copyId(source: Identifier): Identifier = Identifier(source.value)

    private fun throwAfterApplyIfRequested(result: CapacityProviderResult<*>) {
        if (result.value != null && durable.failAfterApply.compareAndSet(true, false)) {
            throw IllegalStateException("Synthetic capacity transport failure after canonical commit.")
        }
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(
            hierarchy: CapacityHierarchyFixture,
            durableState: CapacityInMemoryDurableState = CapacityInMemoryDurableState.create(hierarchy),
        ): DeterministicCapacityProvider = DeterministicCapacityProvider(hierarchy, durableState)
    }
}

private fun CapacityMutationEvidenceKey.Companion.admissionKey(
    request: CapacityAdmissionRequest,
): CapacityMutationEvidenceKey = CapacityMutationEvidenceKey(
    CapacityMutationEvidenceKey.ADMIT,
    request.context.tenantId,
    request.idempotencyScope.scopeDigest,
)

private fun CapacityMutationEvidenceKey.Companion.renewalKey(
    request: CapacityLeaseRenewalRequest,
): CapacityMutationEvidenceKey = CapacityMutationEvidenceKey(
    CapacityMutationEvidenceKey.RENEW,
    request.context.tenantId,
    request.idempotencyScope.scopeDigest,
)

private fun CapacityMutationEvidenceKey.Companion.releaseKey(
    request: CapacityLeaseReleaseRequest,
): CapacityMutationEvidenceKey = CapacityMutationEvidenceKey(
    CapacityMutationEvidenceKey.RELEASE,
    request.context.tenantId,
    request.idempotencyScope.scopeDigest,
)

private fun requireSha256Shape(value: String, name: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "$name must be a lowercase SHA-256 digest."
    }
}
