package ai.icen.fw.testkit.capacity

import ai.icen.fw.capacity.api.CapacityDegradationCapability
import ai.icen.fw.capacity.api.CapacityDimension
import ai.icen.fw.capacity.api.CapacityLimit
import ai.icen.fw.capacity.api.CapacityPolicy
import ai.icen.fw.capacity.api.CapacityPolicyResolution
import ai.icen.fw.capacity.api.CapacityPurpose
import ai.icen.fw.capacity.api.CapacityTrustedContext
import ai.icen.fw.capacity.api.CapacityTrustedContextProvider
import ai.icen.fw.capacity.api.ResourceScope
import ai.icen.fw.capacity.api.WorkloadKind
import ai.icen.fw.capacity.runtime.CapacityRuntimeClock
import ai.icen.fw.capacity.runtime.CapacityRuntimeIdGenerator
import ai.icen.fw.capacity.runtime.CapacityRuntimePurposes
import ai.icen.fw.core.id.Identifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Thread-safe manually advanced clock. It never reads wall time. */
class DeterministicCapacityClock private constructor(initialEpochMilli: Long) : CapacityRuntimeClock {
    private val value = AtomicLong(initialEpochMilli)

    init {
        require(initialEpochMilli >= 0L) { "Capacity fixture clock value is invalid." }
    }

    override fun currentTimeMillis(): Long = value.get()

    fun set(epochMilli: Long) {
        require(epochMilli >= value.get()) { "Capacity fixture clock must be monotonic." }
        value.set(epochMilli)
    }

    fun advance(millis: Long): Long {
        require(millis >= 0L) { "Capacity fixture clock advance is invalid." }
        while (true) {
            val current = value.get()
            val next = Math.addExact(current, millis)
            if (value.compareAndSet(current, next)) return next
        }
    }

    companion object {
        @JvmStatic fun startingAt(epochMilli: Long): DeterministicCapacityClock =
            DeterministicCapacityClock(epochMilli)
    }
}

/** Predictable, unique, opaque identifiers for one isolated contract fixture. */
class DeterministicCapacityIds private constructor(startAt: Long) : CapacityRuntimeIdGenerator {
    private val sequence = AtomicLong(startAt)

    init {
        require(startAt >= 0L) { "Capacity fixture id sequence is invalid." }
    }

    override fun nextId(kind: String): Identifier = Identifier("$kind-${sequence.incrementAndGet()}")

    fun currentSequence(): Long = sequence.get()

    companion object {
        @JvmStatic @JvmOverloads
        fun create(startAt: Long = 0L): DeterministicCapacityIds = DeterministicCapacityIds(startAt)
    }
}

/** Complete deterministic hierarchy used by every reusable contract suite. */
class CapacityHierarchyFixture private constructor(
    val tenantId: Identifier,
    val principalId: Identifier,
    val reconciliationPrincipalId: Identifier,
    val providerId: Identifier,
    val target: ResourceScope,
    val workload: WorkloadKind,
    policies: Collection<CapacityPolicy>,
    val nowEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val policies: List<CapacityPolicy> = Collections.unmodifiableList(ArrayList(policies))
    val degradation: CapacityDegradationCapability = CapacityDegradationCapability.DEFER_SECONDARY_INDEXING

    init {
        require(this.policies.size == 4) { "Capacity hierarchy fixture requires four policy layers." }
        require(this.policies.map { it.scope.level }.toSet().size == 4) {
            "Capacity hierarchy fixture requires one policy per hierarchy level."
        }
    }

    fun resolution(atTime: Long = nowEpochMilli): CapacityPolicyResolution =
        CapacityPolicyResolution.resolve(target, workload, policies, atTime)

    fun context(
        purpose: CapacityPurpose,
        suffix: String,
        tenantId: Identifier = this.tenantId,
        principalId: Identifier = if (purpose == CapacityRuntimePurposes.RECONCILIATION) {
            reconciliationPrincipalId
        } else {
            this.principalId
        },
        authorizedScope: ResourceScope = if (tenantId == this.tenantId) target else ResourceScope.tenant(tenantId),
        initiatedAt: Long = nowEpochMilli - 1_000L,
        authorizationExpiresAt: Long = expiresAtEpochMilli,
    ): CapacityTrustedContext = CapacityTrustedContext.authenticated(
        tenantId,
        principalId,
        if (purpose == CapacityRuntimePurposes.RECONCILIATION) "SERVICE" else "USER",
        Identifier("request-$suffix"),
        purpose,
        authorizedScope,
        Identifier("authentication-$suffix"),
        Identifier("authorization-$suffix"),
        "revision-$suffix",
        CapacityContractAssertions.sha256("authorization:$suffix"),
        initiatedAt,
        authorizationExpiresAt,
    )

    companion object {
        @JvmStatic
        @JvmOverloads
        fun standard(
            tenantId: String = "tenant-contract",
            nowEpochMilli: Long = 100_000L,
        ): CapacityHierarchyFixture {
            require(nowEpochMilli >= 10_000L) { "Capacity contract fixture time is too small." }
            val tenant = Identifier(tenantId)
            val provider = Identifier("capacity-provider")
            val target = ResourceScope.resource(
                tenant,
                "document",
                Identifier("capacity-resource"),
                provider,
            )
            val workload = WorkloadKind.UPLOAD
            val expiresAt = Math.addExact(nowEpochMilli, 120_000L)
            val degradations = setOf(
                CapacityDegradationCapability.DEFER_SECONDARY_INDEXING,
                CapacityDegradationCapability.REDUCE_OPTIONAL_ENRICHMENT,
            )
            val policies = listOf(
                policy("system", ResourceScope.system(), workload, 100L, 70L, 90L, 10_000L, 7_000L, 9_000L,
                    degradations, nowEpochMilli, expiresAt),
                policy("tenant", ResourceScope.tenant(tenant), workload, 80L, 60L, 72L, 8_000L, 6_000L, 7_200L,
                    setOf(CapacityDegradationCapability.DEFER_SECONDARY_INDEXING), nowEpochMilli, expiresAt),
                policy("provider", ResourceScope.provider(tenant, provider), workload, 72L, 48L, 64L,
                    7_000L, 4_800L, 6_400L, degradations, nowEpochMilli, expiresAt),
                policy("resource", target, workload, 64L, 32L, 48L, 6_000L, 3_000L, 4_500L,
                    degradations, nowEpochMilli, expiresAt),
            )
            return CapacityHierarchyFixture(
                tenant,
                Identifier("contract-operator"),
                Identifier("contract-reconciler"),
                provider,
                target,
                workload,
                policies,
                nowEpochMilli,
                expiresAt,
            )
        }

        private fun policy(
            name: String,
            scope: ResourceScope,
            workload: WorkloadKind,
            queueLimit: Long,
            queueWarning: Long,
            queueCritical: Long,
            byteLimit: Long,
            byteWarning: Long,
            byteCritical: Long,
            degradations: Set<CapacityDegradationCapability>,
            now: Long,
            expiresAt: Long,
        ): CapacityPolicy = CapacityPolicy(
            Identifier("capacity-policy-$name"),
            CapacityPolicy.CONTRACT_VERSION,
            "revision-1",
            1L,
            scope,
            setOf(workload),
            listOf(
                CapacityLimit(CapacityDimension.QUEUE_DEPTH, queueLimit, queueWarning, queueCritical),
                CapacityLimit(CapacityDimension.IN_FLIGHT_BYTES, byteLimit, byteWarning, byteCritical),
            ),
            now - 5_000L,
            expiresAt,
            degradations,
        )
    }
}

/** Strict trusted-host context source with explicit purpose denial and no request-derived tenant. */
class StrictCapacityContextFixture private constructor(
    val hierarchy: CapacityHierarchyFixture,
    private val clock: CapacityRuntimeClock,
) : CapacityTrustedContextProvider {
    private val sequence = AtomicLong()
    private val denied = Collections.newSetFromMap(ConcurrentHashMap<CapacityPurpose, Boolean>())
    @Volatile private var unavailable = false

    override fun currentContext(purpose: CapacityPurpose): CapacityTrustedContext? {
        if (unavailable) throw IllegalStateException("Capacity fixture authorization is unavailable.")
        if (purpose in denied) return null
        val ordinal = sequence.incrementAndGet()
        val now = clock.currentTimeMillis()
        return hierarchy.context(
            purpose,
            "$ordinal",
            initiatedAt = maxOf(0L, now - 1_000L),
            authorizationExpiresAt = hierarchy.expiresAtEpochMilli,
        )
    }

    fun deny(purpose: CapacityPurpose) {
        denied.add(purpose)
    }

    fun allow(purpose: CapacityPurpose) {
        denied.remove(purpose)
    }

    fun setUnavailable(value: Boolean) {
        unavailable = value
    }

    fun contextCount(): Long = sequence.get()

    companion object {
        @JvmStatic
        fun forHierarchy(
            hierarchy: CapacityHierarchyFixture,
            clock: CapacityRuntimeClock,
        ): StrictCapacityContextFixture = StrictCapacityContextFixture(hierarchy, clock)
    }
}
