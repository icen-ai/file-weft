package ai.icen.fw.capacity.api

import ai.icen.fw.core.id.Identifier

enum class CapacityPressureLevel {
    NORMAL,
    WARNING,
    CRITICAL,
    EXHAUSTED,
}

/** Atomic provider observation in one standard unit. */
class CapacityMeasureSnapshot(
    val effectiveLimit: CapacityEffectiveLimit,
    val used: Long,
    val reserved: Long,
) {
    val dimension: CapacityDimension = effectiveLimit.dimension
    val total: Long
    val pressure: CapacityPressureLevel
    val bindingDigest: String

    init {
        require(used >= 0L && reserved >= 0L) { "Capacity usage values must not be negative." }
        total = capacitySafeAdd(used, reserved, "Capacity usage total")
        pressure = when {
            total >= effectiveLimit.limit -> CapacityPressureLevel.EXHAUSTED
            total >= effectiveLimit.criticalWatermark -> CapacityPressureLevel.CRITICAL
            total >= effectiveLimit.warningWatermark -> CapacityPressureLevel.WARNING
            else -> CapacityPressureLevel.NORMAL
        }
        bindingDigest = CapacityDigest("flowweft.capacity.measure-snapshot.v1")
            .add(effectiveLimit.bindingDigest)
            .add(used)
            .add(reserved)
            .add(total)
            .add(pressure.name)
            .finish()
    }
}

/** Complete CAS snapshot: exact policy resolution, usage, reservations and state version. */
class CapacityUsageSnapshot private constructor(
    providerId: Identifier,
    val policyResolution: CapacityPolicyResolution,
    measures: Collection<CapacityMeasureSnapshot>,
    val stateVersion: Long,
    val observedAt: Long,
    val expiresAt: Long,
) {
    val providerId: Identifier = requireCapacityIdentifier(providerId, "Capacity provider identifier")
    val target: ResourceScope = policyResolution.target
    val workload: WorkloadKind = policyResolution.workload
    val measures: List<CapacityMeasureSnapshot> = capacityList(
        measures.sortedBy { measure -> measure.dimension.bindingCode },
        CapacityContractLimits.MAX_LIMITS,
        "Capacity usage measures",
    )
    val snapshotDigest: String

    init {
        require(stateVersion >= 0L && observedAt >= policyResolution.observedAt &&
            expiresAt > observedAt && expiresAt <= policyResolution.expiresAt &&
            policyResolution.isCurrent(observedAt)
        ) { "Capacity usage snapshot version or lifetime is invalid." }
        require(this.measures.isNotEmpty() &&
            this.measures.map { measure -> measure.dimension }.toSet().size == this.measures.size
        ) { "Capacity usage snapshot contains no measures or duplicate dimensions." }
        val resolvedLimits = policyResolution.effectiveLimits.associateBy { limit -> limit.dimension }
        require(this.measures.map { measure -> measure.dimension }.toSet() == resolvedLimits.keys &&
            this.measures.all { measure ->
                resolvedLimits[measure.dimension]?.bindingDigest == measure.effectiveLimit.bindingDigest
            }
        ) { "Capacity usage snapshot does not match its complete policy resolution." }
        val digest = CapacityDigest("flowweft.capacity.usage-snapshot.v1")
            .add(this.providerId.value)
            .add(policyResolution.resolutionDigest)
            .add(stateVersion)
            .add(observedAt)
            .add(expiresAt)
        this.measures.forEach { measure -> digest.add(measure.bindingDigest) }
        snapshotDigest = digest.finish()
    }

    fun isCurrent(atTime: Long): Boolean = atTime >= observedAt && atTime < expiresAt

    fun measureFor(dimension: CapacityDimension): CapacityMeasureSnapshot? =
        measures.firstOrNull { measure -> measure.dimension == dimension }

    override fun toString(): String =
        "CapacityUsageSnapshot(workload=$workload, stateVersion=$stateVersion, measures=${measures.size})"

    companion object {
        @JvmStatic
        fun capture(
            providerId: Identifier,
            policyResolution: CapacityPolicyResolution,
            measures: Collection<CapacityMeasureSnapshot>,
            stateVersion: Long,
            observedAt: Long,
            expiresAt: Long,
        ): CapacityUsageSnapshot = CapacityUsageSnapshot(
            providerId,
            policyResolution,
            measures,
            stateVersion,
            observedAt,
            expiresAt,
        )
    }
}
