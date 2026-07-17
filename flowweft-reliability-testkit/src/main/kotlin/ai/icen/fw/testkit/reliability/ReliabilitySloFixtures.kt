package ai.icen.fw.testkit.reliability

import ai.icen.fw.reliability.api.ReliabilityAction
import ai.icen.fw.reliability.api.ReliabilityBurnRatePolicy
import ai.icen.fw.reliability.api.ReliabilityPurpose
import ai.icen.fw.reliability.api.ReliabilityResourceRef
import ai.icen.fw.reliability.api.ReliabilitySliKind
import ai.icen.fw.reliability.api.ReliabilitySloObjective
import ai.icen.fw.reliability.runtime.ReliabilityAuthorizedCallFactory
import ai.icen.fw.reliability.runtime.ReliabilitySliObservationProvider
import ai.icen.fw.reliability.runtime.ReliabilitySloPolicySnapshot
import ai.icen.fw.reliability.runtime.ReliabilitySloPolicySource
import ai.icen.fw.reliability.runtime.ReliabilitySloSchedule
import ai.icen.fw.reliability.runtime.ReliabilitySloScheduleRepository
import ai.icen.fw.reliability.runtime.ReliabilitySloWorker
import ai.icen.fw.reliability.runtime.ReliabilityTrustedInvocation

/** Deterministic SLO policy/schedule/authorization fixture for missing-data behavior. */
class ReliabilitySloContractScenario private constructor(
    val tenantId: String,
    val clock: DeterministicReliabilityClock,
    val identifiers: DeterministicReliabilityIds,
    val authorization: StrictReliabilityAuthorizationFixture,
    val policy: ReliabilitySloPolicySnapshot,
    val schedule: ReliabilitySloSchedule,
) {
    @JvmOverloads
    fun invocation(
        rawIdempotencyKey: String,
        tenantId: String = this.tenantId,
    ): ReliabilityTrustedInvocation = ReliabilityTrustedInvocation.of(
        tenantId,
        authorization.operationPrincipal,
        ReliabilityPurpose.EVALUATE_SLO,
        ReliabilityAction.EVALUATE_SLO,
        policy.objective.resource,
        rawIdempotencyKey,
        clock.nowEpochMilli(),
        clock.nowEpochMilli() + 1_000L,
    )

    @JvmOverloads
    fun worker(
        repository: ReliabilitySloScheduleRepository,
        observations: ReliabilitySliObservationProvider = ReliabilitySliObservationProvider { null },
    ): ReliabilitySloWorker = ReliabilitySloWorker(
        ReliabilityAuthorizedCallFactory(authorization, identifiers),
        identifiers,
        clock,
        ReliabilitySloPolicySource { policy },
        observations,
        repository,
    )

    companion object {
        @JvmStatic
        @JvmOverloads
        fun missingData(
            tenantId: String = "tenant-contract",
            nowEpochMilli: Long = 100_000L,
        ): ReliabilitySloContractScenario {
            require(nowEpochMilli >= 10_000L) { "Reliability SLO fixture time is too small." }
            val resource = ReliabilityResourceRef.of(
                "service",
                "contract-api",
                "1",
                ReliabilityContractAssertions.digest('d'),
            )
            val objective = ReliabilitySloObjective.of(
                "contract-availability",
                "1",
                ReliabilityContractAssertions.digest('e'),
                resource,
                ReliabilitySliKind.AVAILABILITY,
                999_000L,
                1_000L,
                100L,
                2_000L,
                0L,
                nowEpochMilli + 100_000L,
            )
            val burn = ReliabilityBurnRatePolicy.of(
                "contract-burn",
                "1",
                ReliabilityContractAssertions.digest('f'),
                objective.objectiveDigest,
                1_000_000L,
                2_000_000L,
            )
            val policy = ReliabilitySloPolicySnapshot.of(
                objective,
                burn,
                "1",
                ReliabilityContractAssertions.digest('1'),
                0L,
                nowEpochMilli + 100_000L,
            )
            val schedule = ReliabilitySloSchedule.of(
                "contract-slo-schedule",
                tenantId,
                policy.policyBindingDigest,
                resource,
                60_000L,
                nowEpochMilli,
                0L,
                null,
                null,
                0L,
            )
            val clock = DeterministicReliabilityClock.startingAt(nowEpochMilli)
            val identifiers = DeterministicReliabilityIds.create()
            val authorization = StrictReliabilityAuthorizationFixture.forTenant(tenantId)
            return ReliabilitySloContractScenario(
                tenantId,
                clock,
                identifiers,
                authorization,
                policy,
                schedule,
            )
        }
    }
}
