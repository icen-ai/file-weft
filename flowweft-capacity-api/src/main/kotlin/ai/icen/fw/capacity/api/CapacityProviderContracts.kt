package ai.icen.fw.capacity.api

import ai.icen.fw.core.id.Identifier

class CapacityProviderCapability(value: String) : Comparable<CapacityProviderCapability> {
    val value: String = requireCapacityCode(value, "Capacity provider capability")

    override fun compareTo(other: CapacityProviderCapability): Int = value.compareTo(other.value)
    override fun equals(other: Any?): Boolean = other is CapacityProviderCapability && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val ATOMIC_ADMISSION = CapacityProviderCapability("atomic_admission")
        @JvmField val HIERARCHICAL_POLICIES = CapacityProviderCapability("hierarchical_policies")
        @JvmField val FENCED_LEASES = CapacityProviderCapability("fenced_leases")
        @JvmField val USAGE_SNAPSHOTS = CapacityProviderCapability("usage_snapshots")
        @JvmField val DOCTOR_EVIDENCE = CapacityProviderCapability("doctor_evidence")
    }
}

/** Safe local descriptor. Configuration digests are allowed; URLs and credentials are not. */
class CapacityProviderDescriptor(
    providerId: Identifier,
    contractVersion: String,
    capabilities: Collection<CapacityProviderCapability>,
    configurationDigest: String,
    val observedAt: Long,
    val expiresAt: Long,
) {
    val providerId: Identifier = requireCapacityIdentifier(providerId, "Capacity provider identifier")
    val contractVersion: String = requireCapacityCode(contractVersion, "Capacity provider contract version")
    val capabilities: Set<CapacityProviderCapability> = capacitySet(
        capabilities,
        CapacityContractLimits.MAX_PROVIDER_CAPABILITIES,
        "Capacity provider capabilities",
    )
    val configurationDigest: String = requireCapacityDigest(
        configurationDigest,
        "Capacity provider configuration",
    )
    val descriptorDigest: String

    init {
        require(observedAt >= 0L && expiresAt > observedAt) { "Capacity provider descriptor lifetime is invalid." }
        require(this.capabilities.containsAll(REQUIRED_CAPABILITIES)) {
            "Capacity provider lacks an atomic hierarchical admission or fenced-lease capability."
        }
        val digest = CapacityDigest("flowweft.capacity.provider-descriptor.v1")
            .add(this.providerId.value)
            .add(this.contractVersion)
            .add(this.configurationDigest)
            .add(observedAt)
            .add(expiresAt)
        this.capabilities.sorted().forEach { capability -> digest.add(capability.value) }
        descriptorDigest = digest.finish()
    }

    fun isCurrent(atTime: Long): Boolean = atTime >= observedAt && atTime < expiresAt

    companion object {
        private val REQUIRED_CAPABILITIES: Set<CapacityProviderCapability> = setOf(
            CapacityProviderCapability.ATOMIC_ADMISSION,
            CapacityProviderCapability.HIERARCHICAL_POLICIES,
            CapacityProviderCapability.FENCED_LEASES,
            CapacityProviderCapability.USAGE_SNAPSHOTS,
        )
    }
}

class CapacitySnapshotRequest(
    operationId: Identifier,
    val context: CapacityTrustedContext,
    val target: ResourceScope,
    val workload: WorkloadKind,
    val requestedAt: Long,
    val deadlineAt: Long,
) {
    val operationId: Identifier = requireCapacityIdentifier(operationId, "Capacity snapshot operation identifier")
    val bindingDigest: String

    init {
        context.requirePurpose(CapacityPurpose.OBSERVE)
        context.requireFresh(requestedAt)
        require(target.level != CapacityScopeLevel.SYSTEM && target.tenantId == context.tenantId &&
            context.authorizedScope.appliesTo(target)
        ) {
            "Capacity snapshot target does not belong to the trusted tenant."
        }
        require(deadlineAt > requestedAt && deadlineAt <= context.authorizationExpiresAt &&
            deadlineAt - requestedAt <= CapacityContractLimits.MAX_REQUEST_DURATION_MILLIS
        ) { "Capacity snapshot request lifetime is invalid." }
        bindingDigest = CapacityDigest("flowweft.capacity.snapshot-request.v1")
            .add(this.operationId.value)
            .add(context.bindingDigest)
            .add(target.bindingDigest)
            .add(workload.value)
            .add(requestedAt)
            .add(deadlineAt)
            .finish()
    }

    override fun toString(): String = "CapacitySnapshotRequest(workload=$workload, <redacted>)"
}

class CapacityDoctorRequest(
    operationId: Identifier,
    val context: CapacityTrustedContext,
    val target: ResourceScope,
    val requestedAt: Long,
    val deadlineAt: Long,
) {
    val operationId: Identifier = requireCapacityIdentifier(operationId, "Capacity Doctor operation identifier")
    val bindingDigest: String

    init {
        context.requirePurpose(CapacityPurpose.DOCTOR)
        context.requireFresh(requestedAt)
        require((target.level == CapacityScopeLevel.SYSTEM || target.tenantId == context.tenantId) &&
            context.authorizedScope.appliesTo(target)
        ) {
            "Capacity Doctor target is outside the trusted tenant or authorized system scope."
        }
        require(deadlineAt > requestedAt && deadlineAt <= context.authorizationExpiresAt &&
            deadlineAt - requestedAt <= CapacityContractLimits.MAX_REQUEST_DURATION_MILLIS
        ) { "Capacity Doctor request lifetime is invalid." }
        bindingDigest = CapacityDigest("flowweft.capacity.doctor-request.v1")
            .add(this.operationId.value)
            .add(context.bindingDigest)
            .add(target.bindingDigest)
            .add(requestedAt)
            .add(deadlineAt)
            .finish()
    }

    override fun toString(): String = "CapacityDoctorRequest(scope=${target.level}, <redacted>)"
}

/** Open, code-only provider failure vocabulary. */
class CapacityProviderErrorCode(value: String) {
    val value: String = requireCapacityCode(value, "Capacity provider error code")

    override fun equals(other: Any?): Boolean = other is CapacityProviderErrorCode && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val STATE_CONFLICT = CapacityProviderErrorCode("state_conflict")
        @JvmField val POLICY_CHANGED = CapacityProviderErrorCode("policy_changed")
        @JvmField val LEASE_EXPIRED = CapacityProviderErrorCode("lease_expired")
        @JvmField val NOT_FOUND = CapacityProviderErrorCode("not_found")
        @JvmField val UNAUTHORIZED = CapacityProviderErrorCode("unauthorized")
        @JvmField val UNSUPPORTED = CapacityProviderErrorCode("unsupported")
        @JvmField val UNAVAILABLE = CapacityProviderErrorCode("unavailable")
        @JvmField val INTERNAL_FAILURE = CapacityProviderErrorCode("internal_failure")
    }
}

/** Result envelope contains no Throwable, provider message or raw payload. */
class CapacityProviderResult<T> private constructor(
    val value: T?,
    val errorCode: CapacityProviderErrorCode?,
    val replayed: Boolean,
) {
    init {
        require((value != null) != (errorCode != null)) {
            "Capacity provider result requires exactly one value or error code."
        }
        require(!replayed || value != null) { "Only a successful capacity mutation may be an idempotent replay." }
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun <T> success(value: T, replayed: Boolean = false): CapacityProviderResult<T> =
            CapacityProviderResult(value, null, replayed)

        @JvmStatic
        fun <T> failure(errorCode: CapacityProviderErrorCode): CapacityProviderResult<T> =
            CapacityProviderResult(null, errorCode, false)
    }
}

/**
 * Provider-neutral SPI. Implementations must revalidate fresh authorization, select the strictest
 * applicable hierarchy, and perform CAS, idempotency, usage update and lease fencing atomically.
 * They contain all provider failures and return only [CapacityProviderErrorCode].
 */
interface CapacityProviderSpi {
    fun descriptor(): CapacityProviderDescriptor

    fun snapshot(request: CapacitySnapshotRequest): CapacityProviderResult<CapacityUsageSnapshot>

    fun admit(request: CapacityAdmissionRequest): CapacityProviderResult<CapacityAdmissionDecision>

    fun renew(request: CapacityLeaseRenewalRequest): CapacityProviderResult<CapacityLeaseRenewalReceipt>

    fun release(request: CapacityLeaseReleaseRequest): CapacityProviderResult<CapacityLeaseReleaseReceipt>

    fun doctor(request: CapacityDoctorRequest): CapacityProviderResult<CapacityDoctorReport>
}
