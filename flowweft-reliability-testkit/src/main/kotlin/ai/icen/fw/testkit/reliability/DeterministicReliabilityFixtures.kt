package ai.icen.fw.testkit.reliability

import ai.icen.fw.reliability.api.ReliabilityAction
import ai.icen.fw.reliability.api.ReliabilityAuthorizationSnapshot
import ai.icen.fw.reliability.api.ReliabilityCapability
import ai.icen.fw.reliability.api.ReliabilityComponentKind
import ai.icen.fw.reliability.api.ReliabilityComponentScope
import ai.icen.fw.reliability.api.ReliabilityEnvironmentKind
import ai.icen.fw.reliability.api.ReliabilityEnvironmentRef
import ai.icen.fw.reliability.api.ReliabilityPrincipalRef
import ai.icen.fw.reliability.api.ReliabilityProviderDescriptor
import ai.icen.fw.reliability.api.ReliabilityPurpose
import ai.icen.fw.reliability.api.ReliabilityRecoveryObjective
import ai.icen.fw.reliability.api.ReliabilityRecoveryObjectiveSet
import ai.icen.fw.reliability.api.ReliabilityResourceRef
import ai.icen.fw.reliability.runtime.ReliabilityRuntimeAuthorizationPort
import ai.icen.fw.reliability.runtime.ReliabilityRuntimeAuthorizationRequest
import ai.icen.fw.reliability.runtime.ReliabilityRuntimeClock
import ai.icen.fw.reliability.runtime.ReliabilityRuntimeIdPort
import ai.icen.fw.reliability.runtime.ReliabilityRuntimeIdRequest
import ai.icen.fw.reliability.runtime.ReliabilityTopologySnapshot
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Thread-safe manually advanced clock. It never reads wall time. */
class DeterministicReliabilityClock private constructor(initialEpochMilli: Long) : ReliabilityRuntimeClock {
    private val value = AtomicLong(initialEpochMilli)

    init {
        require(initialEpochMilli >= 0L) { "Reliability fixture clock value is invalid." }
    }

    override fun nowEpochMilli(): Long = value.get()

    fun set(epochMilli: Long) {
        require(epochMilli >= value.get()) { "Reliability fixture clock must be monotonic." }
        value.set(epochMilli)
    }

    fun advance(millis: Long): Long {
        require(millis >= 0L) { "Reliability fixture clock advance is invalid." }
        while (true) {
            val current = value.get()
            val next = Math.addExact(current, millis)
            if (value.compareAndSet(current, next)) return next
        }
    }

    companion object {
        @JvmStatic fun startingAt(epochMilli: Long): DeterministicReliabilityClock =
            DeterministicReliabilityClock(epochMilli)
    }
}

/** Predictable, unique, opaque identifiers for one isolated contract fixture. */
class DeterministicReliabilityIds private constructor(startAt: Long) : ReliabilityRuntimeIdPort {
    private val sequence = AtomicLong(startAt)

    init {
        require(startAt >= 0L) { "Reliability fixture id sequence is invalid." }
    }

    override fun nextId(request: ReliabilityRuntimeIdRequest): String =
        "${request.kind.name.lowercase()}-${sequence.incrementAndGet()}"

    fun currentSequence(): Long = sequence.get()

    companion object {
        @JvmStatic @JvmOverloads
        fun create(startAt: Long = 0L): DeterministicReliabilityIds = DeterministicReliabilityIds(startAt)
    }
}

/**
 * Strict host authorization double. It grants only the configured tenant and one of the exact
 * original/reconciliation principals. Every returned snapshot is rebound to the current request.
 */
class StrictReliabilityAuthorizationFixture private constructor(
    val tenantId: String,
    val operationPrincipal: ReliabilityPrincipalRef,
    val reconciliationPrincipal: ReliabilityPrincipalRef,
) : ReliabilityRuntimeAuthorizationPort {
    private val sequence = AtomicLong()
    @Volatile private var revoked = false

    override fun authorize(request: ReliabilityRuntimeAuthorizationRequest): ReliabilityAuthorizationSnapshot {
        check(!revoked) { "Reliability fixture authorization is revoked." }
        val invocation = request.invocation
        check(invocation.tenantId == tenantId) { "Reliability fixture rejected an untrusted tenant." }
        val expectedPrincipal = if (invocation.purpose == ReliabilityPurpose.RECONCILE) {
            check(invocation.action == ReliabilityAction.RECONCILE_OPERATION) {
                "Reliability fixture reconciliation action is invalid."
            }
            reconciliationPrincipal
        } else {
            operationPrincipal
        }
        check(invocation.principal == expectedPrincipal) {
            "Reliability fixture rejected an untrusted principal."
        }
        val ordinal = sequence.incrementAndGet()
        val issuedAt = if (invocation.requestedAtEpochMilli == 0L) 0L else invocation.requestedAtEpochMilli - 1L
        val expiresAt = Math.addExact(invocation.deadlineEpochMilli, 1L)
        return ReliabilityAuthorizationSnapshot.of(
            "authorization-$ordinal",
            tenantId,
            invocation.principal,
            invocation.purpose,
            invocation.action,
            invocation.resource,
            "testkit-host-policy",
            "1",
            ordinal.toString(),
            ReliabilityContractAssertions.sha256("decision:${request.requestDigest}:$ordinal"),
            issuedAt,
            expiresAt,
        )
    }

    fun revoke() {
        revoked = true
    }

    fun restore() {
        revoked = false
    }

    fun authorizationCount(): Long = sequence.get()

    companion object {
        @JvmStatic
        @JvmOverloads
        fun forTenant(
            tenantId: String,
            operationPrincipal: ReliabilityPrincipalRef = ReliabilityPrincipalRef.of("user", "contract-operator"),
            reconciliationPrincipal: ReliabilityPrincipalRef = ReliabilityPrincipalRef.of(
                "service", "contract-reconciler",
            ),
        ): StrictReliabilityAuthorizationFixture = StrictReliabilityAuthorizationFixture(
            tenantId, operationPrincipal, reconciliationPrincipal,
        )
    }
}

/** Complete authoritative topology and recovery-policy fixture. */
class ReliabilityContractTopology internal constructor(
    val tenantId: String,
    val source: ReliabilityEnvironmentRef,
    val target: ReliabilityEnvironmentRef,
    components: Collection<ReliabilityComponentScope>,
    val objectives: ReliabilityRecoveryObjectiveSet,
    val sourceSnapshot: ReliabilityTopologySnapshot,
    val targetSnapshot: ReliabilityTopologySnapshot,
    val providerDescriptor: ReliabilityProviderDescriptor,
) {
    val components: List<ReliabilityComponentScope> = Collections.unmodifiableList(ArrayList(components))

    init {
        require(this.components.isNotEmpty()) { "Reliability contract topology requires a component." }
        require(objectives.objectives.map { it.scope.scopeDigest }.toSet() ==
            this.components.map { it.scopeDigest }.toSet()
        ) { "Reliability contract topology and objectives differ." }
    }

    fun snapshotFor(environment: ReliabilityEnvironmentRef): ReliabilityTopologySnapshot = when (environment) {
        source -> sourceSnapshot
        target -> targetSnapshot
        else -> throw IllegalArgumentException("Reliability contract requested an unknown environment.")
    }
}

object ReliabilityTopologyFixtures {
    @JvmStatic
    @JvmOverloads
    fun singleDatabase(tenantId: String, nowEpochMilli: Long = 100_000L): ReliabilityContractTopology =
        create(
            tenantId,
            nowEpochMilli,
            listOf(
                ReliabilityComponentScope.of(
                    ReliabilityComponentKind.DATABASE,
                    "workflow-db",
                    "1",
                    ReliabilityContractAssertions.digest('1'),
                ),
            ),
        )

    @JvmStatic
    @JvmOverloads
    fun multiComponent(tenantId: String, nowEpochMilli: Long = 100_000L): ReliabilityContractTopology =
        create(
            tenantId,
            nowEpochMilli,
            listOf(
                ReliabilityComponentScope.of(
                    ReliabilityComponentKind.DATABASE,
                    "workflow-db",
                    "1",
                    ReliabilityContractAssertions.digest('1'),
                ),
                ReliabilityComponentScope.of(
                    ReliabilityComponentKind.OBJECT_STORAGE,
                    "document-objects",
                    "1",
                    ReliabilityContractAssertions.digest('2'),
                ),
                ReliabilityComponentScope.of(
                    ReliabilityComponentKind.SEARCH_INDEX,
                    "retrieval-index",
                    "1",
                    ReliabilityContractAssertions.digest('3'),
                ),
            ),
        )

    private fun create(
        tenantId: String,
        nowEpochMilli: Long,
        components: List<ReliabilityComponentScope>,
    ): ReliabilityContractTopology {
        require(nowEpochMilli >= 10_000L) { "Reliability contract fixture time is too small." }
        val validFrom = nowEpochMilli - 10_000L
        val validUntil = Math.addExact(nowEpochMilli, 7L * 24L * 60L * 60L * 1000L)
        val sourceResource = ReliabilityResourceRef.of(
            ReliabilityEnvironmentRef.RESOURCE_TYPE,
            "contract-source",
            "1",
            ReliabilityContractAssertions.digest('4'),
        )
        val targetResource = ReliabilityResourceRef.of(
            ReliabilityEnvironmentRef.RESOURCE_TYPE,
            "contract-recovery",
            "1",
            ReliabilityContractAssertions.digest('5'),
        )
        val environmentTopologyDigest = ReliabilityContractAssertions.sha256(
            components.sortedBy { it.scopeDigest }.joinToString(":") { it.scopeDigest },
        )
        val source = ReliabilityEnvironmentRef.of(
            tenantId,
            "contract-source",
            ReliabilityEnvironmentKind.PRODUCTION,
            sourceResource,
            environmentTopologyDigest,
        )
        val target = ReliabilityEnvironmentRef.of(
            tenantId,
            "contract-recovery",
            ReliabilityEnvironmentKind.RECOVERY,
            targetResource,
            environmentTopologyDigest,
        )
        val objectives = ReliabilityRecoveryObjectiveSet.of(
            "contract-recovery-policy",
            "1",
            ReliabilityContractAssertions.digest('6'),
            source,
            components.map { ReliabilityRecoveryObjective.of(it, 60_000L, 3_600_000L) },
            validFrom,
            validUntil,
        )
        val sourceSnapshot = ReliabilityTopologySnapshot.of(
            source,
            components,
            "1",
            ReliabilityContractAssertions.digest('7'),
            validFrom,
            validUntil,
        )
        val targetSnapshot = ReliabilityTopologySnapshot.of(
            target,
            components,
            "1",
            ReliabilityContractAssertions.digest('7'),
            validFrom,
            validUntil,
        )
        val descriptor = ReliabilityProviderDescriptor.of(
            "contract-provider",
            "1",
            "1",
            ReliabilityContractAssertions.digest('8'),
            ReliabilityCapability.values().toList(),
            components.map { it.kind }.distinct(),
            components.size.coerceAtLeast(8),
            validFrom,
            validUntil,
        )
        return ReliabilityContractTopology(
            tenantId,
            source,
            target,
            components,
            objectives,
            sourceSnapshot,
            targetSnapshot,
            descriptor,
        )
    }
}
