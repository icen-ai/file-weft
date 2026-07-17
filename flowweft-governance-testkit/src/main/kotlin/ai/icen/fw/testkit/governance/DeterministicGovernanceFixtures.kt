package ai.icen.fw.testkit.governance

import ai.icen.fw.governance.api.GovernanceAuthorizationSnapshot
import ai.icen.fw.governance.api.GovernanceEffectiveClock
import ai.icen.fw.governance.api.GovernanceFailure
import ai.icen.fw.governance.api.GovernanceFailureClass
import ai.icen.fw.governance.api.GovernanceLegalHoldResolution
import ai.icen.fw.governance.api.GovernanceLegalHoldResolutionRequest
import ai.icen.fw.governance.api.GovernanceLegalHoldResolver
import ai.icen.fw.governance.api.GovernanceLegalHoldScope
import ai.icen.fw.governance.api.GovernanceLegalHoldScopeType
import ai.icen.fw.governance.api.GovernanceLegalHoldSnapshot
import ai.icen.fw.governance.api.GovernancePrincipalRef
import ai.icen.fw.governance.api.GovernancePurpose
import ai.icen.fw.governance.api.GovernanceRetentionPolicyMode
import ai.icen.fw.governance.api.GovernanceRetentionPolicySnapshot
import ai.icen.fw.governance.runtime.GovernanceClockObservationRequest
import ai.icen.fw.governance.runtime.GovernanceRetentionPolicyPort
import ai.icen.fw.governance.runtime.GovernanceRetentionPolicyRequest
import ai.icen.fw.governance.runtime.GovernanceRuntimeAuthorizationPort
import ai.icen.fw.governance.runtime.GovernanceRuntimeAuthorizationRequest
import ai.icen.fw.governance.runtime.GovernanceRuntimeClockPort
import ai.icen.fw.governance.runtime.GovernanceRuntimeIdPort
import ai.icen.fw.governance.runtime.GovernanceRuntimeIdRequest
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/** Thread-safe, manually advanced governance clock. It never reads ambient time. */
class DeterministicGovernanceClock private constructor(initialEpochMilli: Long) : GovernanceRuntimeClockPort {
    private val value = AtomicLong(initialEpochMilli)
    private val observations = AtomicLong()

    init {
        require(initialEpochMilli >= 0L) { "Governance fixture clock value is invalid." }
    }

    override fun nowEpochMilli(): Long = value.get()

    override fun observe(request: GovernanceClockObservationRequest): GovernanceEffectiveClock {
        check(request.observedAtEpochMilli == value.get()) {
            "Governance fixture rejected a non-current clock observation."
        }
        val expiry = Math.addExact(request.requiredUntilEpochMilli, 60_000L)
        return GovernanceEffectiveClock.of(
            "clock-${observations.incrementAndGet()}",
            "contract-clock",
            "1",
            request.observedAtEpochMilli,
            request.observedAtEpochMilli,
            expiry,
        )
    }

    fun set(epochMilli: Long) {
        require(epochMilli >= value.get()) { "Governance fixture clock must be monotonic." }
        value.set(epochMilli)
    }

    fun advance(millis: Long): Long {
        require(millis >= 0L) { "Governance fixture clock advance is invalid." }
        while (true) {
            val current = value.get()
            val next = Math.addExact(current, millis)
            if (value.compareAndSet(current, next)) return next
        }
    }

    fun observationCount(): Long = observations.get()

    companion object {
        @JvmStatic
        fun startingAt(epochMilli: Long): DeterministicGovernanceClock =
            DeterministicGovernanceClock(epochMilli)
    }
}

/** Predictable opaque identifiers for one isolated contract fixture. */
class DeterministicGovernanceIds private constructor(startAt: Long) : GovernanceRuntimeIdPort {
    private val sequence = AtomicLong(startAt)

    init {
        require(startAt >= 0L) { "Governance fixture id sequence is invalid." }
    }

    override fun nextId(request: GovernanceRuntimeIdRequest): String =
        "${request.kind.code}-${sequence.incrementAndGet()}"

    fun currentSequence(): Long = sequence.get()

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(startAt: Long = 0L): DeterministicGovernanceIds = DeterministicGovernanceIds(startAt)
    }
}

/** Strict trusted-host authorization double bound to one exact tenant and principal. */
class StrictGovernanceAuthorizationFixture private constructor(
    val tenantId: String,
    val principal: GovernancePrincipalRef,
) : GovernanceRuntimeAuthorizationPort {
    private val sequence = AtomicLong()
    private val denied = Collections.synchronizedSet(mutableSetOf<GovernancePurpose>())
    @Volatile private var revision = "1"

    override fun authorize(request: GovernanceRuntimeAuthorizationRequest): GovernanceAuthorizationSnapshot {
        check(request.invocation.tenantId == tenantId) {
            "Governance fixture rejected an untrusted tenant."
        }
        check(request.invocation.principal == principal) {
            "Governance fixture rejected an untrusted principal."
        }
        check(!denied.contains(request.purpose)) {
            "Governance fixture authorization is denied for this exact purpose."
        }
        val ordinal = sequence.incrementAndGet()
        val issuedAt = if (request.invocation.requestedAtEpochMilli == 0L) {
            0L
        } else {
            request.invocation.requestedAtEpochMilli - 1L
        }
        return GovernanceAuthorizationSnapshot.of(
            "authorization-$ordinal",
            tenantId,
            principal,
            request.purpose,
            request.invocation.resource,
            "contract-authority",
            "1",
            revision,
            GovernanceContractAssertions.sha256("decision:${request.requestDigest}:$ordinal"),
            issuedAt,
            Math.addExact(request.invocation.deadlineEpochMilli, 1L),
        )
    }

    fun deny(purpose: GovernancePurpose) {
        denied.add(purpose)
    }

    fun allow(purpose: GovernancePurpose) {
        denied.remove(purpose)
    }

    fun setRevision(value: String) {
        require(value.isNotEmpty() && !value.equals("latest", ignoreCase = true)) {
            "Governance fixture authorization revision is invalid."
        }
        revision = value
    }

    fun authorizationCount(): Long = sequence.get()

    companion object {
        @JvmStatic
        @JvmOverloads
        fun forTenant(
            tenantId: String,
            principal: GovernancePrincipalRef = GovernancePrincipalRef.of("user", "contract-operator"),
        ): StrictGovernanceAuthorizationFixture = StrictGovernanceAuthorizationFixture(tenantId, principal)
    }
}

enum class GovernanceLegalHoldFixtureState {
    CLEAR,
    ACTIVE,
    INCOMPLETE,
}

/** Controllable resolver that emits complete resource-bound evidence or an explicit unknown result. */
class ControllableGovernanceLegalHoldResolver private constructor(
    val tenantId: String,
) : GovernanceLegalHoldResolver {
    private val resolutions = AtomicLong()
    @Volatile private var state = GovernanceLegalHoldFixtureState.CLEAR

    override fun resolve(request: GovernanceLegalHoldResolutionRequest) = CompletableFuture.completedFuture(
        when (state) {
            GovernanceLegalHoldFixtureState.CLEAR -> GovernanceLegalHoldResolution.clear(
                request.resource,
                tenantId,
                "contract-hold-authority",
                "1",
                request.clock,
                emptyList(),
                expiry(request),
            )
            GovernanceLegalHoldFixtureState.ACTIVE -> GovernanceLegalHoldResolution.held(
                request.resource,
                tenantId,
                "contract-hold-authority",
                "1",
                request.clock,
                listOf(activeHold(request)),
                true,
                expiry(request),
            )
            GovernanceLegalHoldFixtureState.INCOMPLETE -> GovernanceLegalHoldResolution.unknown(
                request.resource,
                tenantId,
                "contract-hold-authority",
                "1",
                request.clock,
                GovernanceFailure.of(
                    GovernanceFailureClass.TEMPORARY_UNAVAILABLE,
                    "contract-hold-evidence-unavailable",
                    true,
                    false,
                ),
                expiry(request),
            )
        }.also { resolutions.incrementAndGet() },
    )

    fun setState(value: GovernanceLegalHoldFixtureState) {
        state = value
    }

    fun currentState(): GovernanceLegalHoldFixtureState = state

    fun resolutionCount(): Long = resolutions.get()

    private fun activeHold(request: GovernanceLegalHoldResolutionRequest): GovernanceLegalHoldSnapshot {
        val scope = GovernanceLegalHoldScope.of(
            tenantId,
            GovernanceLegalHoldScopeType.RESOURCE,
            "contract-resource",
            "1",
            GovernanceContractAssertions.digest('c'),
        )
        return GovernanceLegalHoldSnapshot.active(
            "contract-hold",
            tenantId,
            scope,
            1_000,
            "1",
            GovernanceContractAssertions.digest('d'),
            request.clock.observedAtEpochMilli,
        )
    }

    private fun expiry(request: GovernanceLegalHoldResolutionRequest): Long =
        minOf(request.clock.expiresAtEpochMilli, Math.addExact(request.clock.observedAtEpochMilli, 30_000L))

    companion object {
        @JvmStatic
        fun forTenant(tenantId: String): ControllableGovernanceLegalHoldResolver =
            ControllableGovernanceLegalHoldResolver(tenantId)
    }
}

/** Exact expired-retention policy source with a visible call count. */
class DeterministicGovernanceRetentionPolicy private constructor(
    val tenantId: String,
) : GovernanceRetentionPolicyPort {
    private val loads = AtomicLong()

    override fun load(request: GovernanceRetentionPolicyRequest) = CompletableFuture.completedFuture(
        GovernanceRetentionPolicySnapshot.of(
            tenantId,
            request.resource,
            "contract-retention-policy",
            "1",
            GovernanceContractAssertions.digest('e'),
            GovernanceRetentionPolicyMode.RETAIN_UNTIL,
            0L,
            request.clock.observedAtEpochMilli,
            request.clock.expiresAtEpochMilli,
            if (request.clock.effectiveAtEpochMilli == 0L) 0L else request.clock.effectiveAtEpochMilli - 1L,
        ).also { loads.incrementAndGet() },
    )

    fun loadCount(): Long = loads.get()

    companion object {
        @JvmStatic
        fun forTenant(tenantId: String): DeterministicGovernanceRetentionPolicy =
            DeterministicGovernanceRetentionPolicy(tenantId)
    }
}
