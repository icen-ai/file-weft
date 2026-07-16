package ai.icen.fw.capacity.api

import ai.icen.fw.core.id.Identifier

/** One hard limit with warning and critical watermarks in the dimension's canonical unit. */
class CapacityLimit(
    val dimension: CapacityDimension,
    val limit: Long,
    val warningWatermark: Long,
    val criticalWatermark: Long,
) {
    init {
        require(limit >= 0L && warningWatermark in 0L..limit &&
            criticalWatermark in warningWatermark..limit
        ) { "Capacity limit or watermarks are invalid." }
    }

    val bindingDigest: String = CapacityDigest("flowweft.capacity.limit.v1")
        .add(dimension.code)
        .add(dimension.unit.value)
        .add(limit)
        .add(warningWatermark)
        .add(criticalWatermark)
        .finish()

    override fun toString(): String =
        "CapacityLimit(dimension=$dimension, limit=$limit, warning=$warningWatermark, critical=$criticalWatermark)"
}

/** Versioned, immutable policy. It contains operational limits only, never authorization policy. */
class CapacityPolicy @JvmOverloads constructor(
    policyId: Identifier,
    contractVersion: String,
    revision: String,
    val stateVersion: Long,
    val scope: ResourceScope,
    workloads: Collection<WorkloadKind>,
    limits: Collection<CapacityLimit>,
    val effectiveFrom: Long,
    val expiresAt: Long,
    degradationCapabilities: Collection<CapacityDegradationCapability> = emptyList(),
    val enabled: Boolean = true,
) {
    val policyId: Identifier = requireCapacityIdentifier(policyId, "Capacity policy identifier")
    val contractVersion: String = requireCapacityCode(contractVersion, "Capacity policy contract version")
    val revision: String = requireCapacityToken(revision, "Capacity policy revision")
    val workloads: Set<WorkloadKind> = capacitySet(
        workloads,
        CapacityContractLimits.MAX_WORKLOADS,
        "Capacity policy workloads",
    )
    val limits: List<CapacityLimit> = capacityList(
        limits.sortedBy { limit -> limit.dimension.bindingCode },
        CapacityContractLimits.MAX_LIMITS,
        "Capacity policy limits",
    )
    val degradationCapabilities: Set<CapacityDegradationCapability> = capacitySet(
        degradationCapabilities,
        CapacityContractLimits.MAX_DEGRADATIONS,
        "Capacity policy degradation capabilities",
    )
    val bindingDigest: String

    init {
        require(stateVersion >= 0L && effectiveFrom >= 0L && expiresAt > effectiveFrom) {
            "Capacity policy version or lifetime is invalid."
        }
        require(this.workloads.isNotEmpty() && this.limits.isNotEmpty()) {
            "Capacity policy requires workloads and limits."
        }
        require(this.limits.map { limit -> limit.dimension.code }.toSet().size == this.limits.size) {
            "Capacity policy contains duplicate dimension codes."
        }
        bindingDigest = capacityPolicyDigest()
    }

    fun isApplicableTo(target: ResourceScope, workload: WorkloadKind, atTime: Long): Boolean =
        enabled && scope.appliesTo(target) && workload in workloads && atTime in effectiveFrom until expiresAt

    private fun capacityPolicyDigest(): String {
        val digest = CapacityDigest("flowweft.capacity.policy.v1")
            .add(policyId.value)
            .add(contractVersion)
            .add(revision)
            .add(stateVersion)
            .add(scope.bindingDigest)
            .add(effectiveFrom)
            .add(expiresAt)
            .add(enabled)
        workloads.sorted().forEach { workload -> digest.add(workload.value) }
        limits.forEach { limit -> digest.add(limit.bindingDigest) }
        degradationCapabilities.sorted().forEach { capability -> digest.add(capability.value) }
        return digest.finish()
    }

    override fun toString(): String =
        "CapacityPolicy(contractVersion=$contractVersion, scope=${scope.level}, <redacted>)"

    companion object {
        const val CONTRACT_VERSION: String = "flowweft.capacity.policy.v1"
    }
}

/** Conservative merge of every applicable hierarchy layer for one dimension. */
class CapacityEffectiveLimit internal constructor(
    val dimension: CapacityDimension,
    val limit: Long,
    val warningWatermark: Long,
    val criticalWatermark: Long,
    sourcePolicyDigests: Collection<String>,
) {
    val sourcePolicyDigests: Set<String> = capacitySet(
        sourcePolicyDigests.map { digest -> requireCapacityDigest(digest, "Capacity source policy") },
        CapacityContractLimits.MAX_POLICIES,
        "Capacity source policy digests",
    )
    val bindingDigest: String

    init {
        require(limit >= 0L && warningWatermark in 0L..limit &&
            criticalWatermark in warningWatermark..limit && this.sourcePolicyDigests.isNotEmpty()
        ) { "Effective capacity limit is invalid." }
        val digest = CapacityDigest("flowweft.capacity.effective-limit.v1")
            .add(dimension.code)
            .add(dimension.unit.value)
            .add(limit)
            .add(warningWatermark)
            .add(criticalWatermark)
        this.sourcePolicyDigests.sorted().forEach { sourceDigest -> digest.add(sourceDigest) }
        bindingDigest = digest.finish()
    }
}

/**
 * Deterministic hierarchy resolution. Each threshold is the minimum across system, tenant,
 * provider and resource policies; degradations are allowed only by the intersection of all layers.
 */
class CapacityPolicyResolution private constructor(
    val target: ResourceScope,
    val workload: WorkloadKind,
    policies: Collection<CapacityPolicy>,
    effectiveLimits: Collection<CapacityEffectiveLimit>,
    allowedDegradations: Collection<CapacityDegradationCapability>,
    val observedAt: Long,
    val expiresAt: Long,
) {
    val policies: List<CapacityPolicy> = capacityList(
        policies.sortedBy { policy -> policy.bindingDigest },
        CapacityContractLimits.MAX_POLICIES,
        "Resolved capacity policies",
    )
    val effectiveLimits: List<CapacityEffectiveLimit> = capacityList(
        effectiveLimits.sortedBy { limit -> limit.dimension.bindingCode },
        CapacityContractLimits.MAX_LIMITS,
        "Effective capacity limits",
    )
    val allowedDegradations: Set<CapacityDegradationCapability> = capacitySet(
        allowedDegradations,
        CapacityContractLimits.MAX_DEGRADATIONS,
        "Effective capacity degradations",
    )
    val resolutionDigest: String

    init {
        require(this.policies.isNotEmpty() && this.effectiveLimits.isNotEmpty()) {
            "Capacity resolution requires an applicable policy and limit."
        }
        require(observedAt >= 0L && expiresAt > observedAt) {
            "Capacity resolution lifetime is invalid."
        }
        require(this.policies.all { policy -> policy.isApplicableTo(target, workload, observedAt) }) {
            "Capacity resolution contains an inapplicable policy."
        }
        require(this.policies.map { policy -> policy.policyId }.toSet().size == this.policies.size) {
            "Capacity resolution contains overlapping revisions for one policy."
        }
        val digest = CapacityDigest("flowweft.capacity.policy-resolution.v1")
            .add(target.bindingDigest)
            .add(workload.value)
            .add(observedAt)
            .add(expiresAt)
        this.policies.forEach { policy -> digest.add(policy.bindingDigest) }
        this.effectiveLimits.forEach { limit -> digest.add(limit.bindingDigest) }
        this.allowedDegradations.sorted().forEach { capability -> digest.add(capability.value) }
        resolutionDigest = digest.finish()
    }

    fun isCurrent(atTime: Long): Boolean = atTime >= observedAt && atTime < expiresAt

    fun limitFor(dimension: CapacityDimension): CapacityEffectiveLimit? =
        effectiveLimits.firstOrNull { limit -> limit.dimension == dimension }

    override fun toString(): String =
        "CapacityPolicyResolution(scope=${target.level}, workload=$workload, limits=${effectiveLimits.size})"

    companion object {
        @JvmStatic
        fun resolve(
            target: ResourceScope,
            workload: WorkloadKind,
            candidatePolicies: Collection<CapacityPolicy>,
            observedAt: Long,
        ): CapacityPolicyResolution {
            require(candidatePolicies.size <= CapacityContractLimits.MAX_POLICIES) {
                "Capacity policy candidate set is too large."
            }
            val applicable = candidatePolicies
                .filter { policy -> policy.isApplicableTo(target, workload, observedAt) }
                .sortedBy { policy -> policy.bindingDigest }
            require(applicable.isNotEmpty()) { "No capacity policy applies to this target and workload." }
            val limitsByCode = applicable.flatMap { policy -> policy.limits.map { limit -> policy to limit } }
                .groupBy { (_, limit) -> limit.dimension.code }
            val effective = limitsByCode.entries.map { (_, entries) ->
                val units = entries.map { (_, limit) -> limit.dimension.unit }.toSet()
                require(units.size == 1) { "Capacity policies disagree on a dimension unit." }
                CapacityEffectiveLimit(
                    entries.first().second.dimension,
                    entries.minOf { (_, limit) -> limit.limit },
                    entries.minOf { (_, limit) -> limit.warningWatermark },
                    entries.minOf { (_, limit) -> limit.criticalWatermark },
                    entries.map { (policy, _) -> policy.bindingDigest },
                )
            }
            val degradationIntersection = applicable
                .map { policy -> policy.degradationCapabilities }
                .reduce { current, next -> current.intersect(next) }
            return CapacityPolicyResolution(
                target,
                workload,
                applicable,
                effective,
                degradationIntersection,
                observedAt,
                applicable.minOf { policy -> policy.expiresAt },
            )
        }
    }
}
