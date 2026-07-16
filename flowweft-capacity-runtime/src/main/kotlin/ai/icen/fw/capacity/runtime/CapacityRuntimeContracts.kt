package ai.icen.fw.capacity.runtime

import ai.icen.fw.capacity.api.CapacityAdmissionDecision
import ai.icen.fw.capacity.api.CapacityAdmissionOutcome
import ai.icen.fw.capacity.api.CapacityDegradationCapability
import ai.icen.fw.capacity.api.CapacityDemand
import ai.icen.fw.capacity.api.CapacityDoctorReport
import ai.icen.fw.capacity.api.CapacityLeaseReleaseReceipt
import ai.icen.fw.capacity.api.CapacityLeaseRenewalReceipt
import ai.icen.fw.capacity.api.CapacityReservationLease
import ai.icen.fw.capacity.api.CapacityScopeLevel
import ai.icen.fw.capacity.api.CapacityUsageSnapshot
import ai.icen.fw.capacity.api.ResourceScope
import ai.icen.fw.capacity.api.WorkloadKind
import ai.icen.fw.core.id.Identifier

class CapacityGuardCommand(
    providerId: Identifier,
    val target: ResourceScope,
    val workload: WorkloadKind,
    demands: Collection<CapacityDemand>,
    permittedDegradations: Collection<CapacityDegradationCapability>,
    maximumDurationMillis: Long,
) {
    val providerId: Identifier = requireRuntimeIdentifier(providerId, "Capacity guard provider identifier")
    val demands: List<CapacityDemand> = runtimeList(
        demands.sortedBy { demand -> demand.dimension.bindingCode },
        CapacityRuntimeLimits.MAX_COMMAND_ITEMS,
        "Capacity guard demands",
    )
    val permittedDegradations: Set<CapacityDegradationCapability> = runtimeSet(
        permittedDegradations,
        CapacityRuntimeLimits.MAX_COMMAND_ITEMS,
        "Capacity guard permitted degradations",
    )
    val maximumDurationMillis: Long = requireRuntimeDuration(maximumDurationMillis)

    init {
        require(target.level != CapacityScopeLevel.SYSTEM && this.demands.isNotEmpty() &&
            this.demands.map { demand -> demand.dimension.code }.toSet().size == this.demands.size
        ) { "Capacity guard requires a tenant target and unique demands." }
    }

    override fun toString(): String = "CapacityGuardCommand(workload=$workload, dimensions=${demands.size}, <redacted>)"
}

class CapacityObserveCommand(
    providerId: Identifier,
    val target: ResourceScope,
    val workload: WorkloadKind,
    maximumDurationMillis: Long,
) {
    val providerId: Identifier = requireRuntimeIdentifier(providerId, "Capacity observe provider identifier")
    val maximumDurationMillis: Long = requireRuntimeDuration(maximumDurationMillis)

    init {
        require(target.level != CapacityScopeLevel.SYSTEM) { "Capacity observation requires a tenant target." }
    }

    override fun toString(): String = "CapacityObserveCommand(workload=$workload, <redacted>)"
}

class CapacityLeaseRenewCommand(
    val lease: CapacityReservationLease,
    val requestedExpiresAt: Long,
    maximumDurationMillis: Long,
) {
    val maximumDurationMillis: Long = requireRuntimeDuration(maximumDurationMillis)

    init {
        require(requestedExpiresAt > lease.expiresAt) { "Capacity renewal must extend the current lease." }
    }

    override fun toString(): String = "CapacityLeaseRenewCommand(<redacted>)"
}

class CapacityLeaseReleaseCommand(
    val lease: CapacityReservationLease,
    reasonCode: String,
    maximumDurationMillis: Long,
) {
    val reasonCode: String = requireRuntimeCode(reasonCode, "Capacity release reason")
    val maximumDurationMillis: Long = requireRuntimeDuration(maximumDurationMillis)

    override fun toString(): String = "CapacityLeaseReleaseCommand(<redacted>)"
}

class CapacityDoctorCommand(
    providerId: Identifier,
    val target: ResourceScope,
    maximumDurationMillis: Long,
) {
    val providerId: Identifier = requireRuntimeIdentifier(providerId, "Capacity Doctor provider identifier")
    val maximumDurationMillis: Long = requireRuntimeDuration(maximumDurationMillis)

    override fun toString(): String = "CapacityDoctorCommand(scope=${target.level}, <redacted>)"
}

class CapacityGuardReceipt internal constructor(
    val decision: CapacityAdmissionDecision,
    providerDescriptorDigest: String,
    val completedAt: Long,
) {
    val providerDescriptorDigest: String = requireRuntimeDigest(
        providerDescriptorDigest,
        "Capacity guard provider descriptor",
    )
    val policyResolutionDigest: String = decision.usage.policyResolution.resolutionDigest

    init {
        require(completedAt >= decision.decidedAt) { "Capacity guard completion predates its decision." }
    }

    /** Capacity only; callers still require fresh business authorization before doing work. */
    fun isCapacityReserved(): Boolean =
        (decision.outcome == CapacityAdmissionOutcome.ADMIT ||
            decision.outcome == CapacityAdmissionOutcome.DEGRADE) &&
            decision.expiresAt > completedAt && decision.lease?.isCurrent(completedAt) == true

    override fun toString(): String = "CapacityGuardReceipt(outcome=${decision.outcome}, <redacted>)"
}

class CapacityObservationReceipt internal constructor(
    val snapshot: CapacityUsageSnapshot,
    providerDescriptorDigest: String,
    val completedAt: Long,
) {
    val providerDescriptorDigest: String = requireRuntimeDigest(
        providerDescriptorDigest,
        "Capacity observation provider descriptor",
    )

    init {
        require(completedAt >= snapshot.observedAt && snapshot.isCurrent(completedAt)) {
            "Capacity observation is stale at completion."
        }
    }

    override fun toString(): String = "CapacityObservationReceipt(workload=${snapshot.workload}, <redacted>)"
}

class CapacityLeaseRenewRuntimeReceipt internal constructor(
    val receipt: CapacityLeaseRenewalReceipt,
    providerDescriptorDigest: String,
    val completedAt: Long,
) {
    val providerDescriptorDigest: String = requireRuntimeDigest(
        providerDescriptorDigest,
        "Capacity renewal provider descriptor",
    )

    init {
        require(completedAt >= receipt.decidedAt && receipt.renewedLease.isCurrent(completedAt)) {
            "Capacity renewed lease is stale at runtime completion."
        }
    }

    override fun toString(): String = "CapacityLeaseRenewRuntimeReceipt(<redacted>)"
}

class CapacityLeaseReleaseRuntimeReceipt internal constructor(
    val receipt: CapacityLeaseReleaseReceipt,
    providerDescriptorDigest: String,
    val completedAt: Long,
) {
    val providerDescriptorDigest: String = requireRuntimeDigest(
        providerDescriptorDigest,
        "Capacity release provider descriptor",
    )

    init {
        require(completedAt >= receipt.releasedAt) { "Capacity release completion predates its receipt." }
    }

    override fun toString(): String = "CapacityLeaseReleaseRuntimeReceipt(<redacted>)"
}

class CapacityDoctorRuntimeReceipt internal constructor(
    val report: CapacityDoctorReport,
    requestBindingDigest: String,
    providerDescriptorDigest: String,
    val completedAt: Long,
) {
    val requestBindingDigest: String = requireRuntimeDigest(
        requestBindingDigest,
        "Capacity Doctor request binding",
    )
    val providerDescriptorDigest: String = requireRuntimeDigest(
        providerDescriptorDigest,
        "Capacity Doctor provider descriptor",
    )

    init {
        require(completedAt >= report.observedAt && completedAt < report.expiresAt) {
            "Capacity Doctor report is stale at completion."
        }
    }

    override fun toString(): String = "CapacityDoctorRuntimeReceipt(status=${report.status})"
}
