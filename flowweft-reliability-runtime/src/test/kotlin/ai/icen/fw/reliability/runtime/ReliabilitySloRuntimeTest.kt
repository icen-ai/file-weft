package ai.icen.fw.reliability.runtime

import ai.icen.fw.reliability.api.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ReliabilitySloRuntimeTest {
    @Test
    fun `due schedule stores missing-data critical alert with lease CAS and outbox`() {
        val resource = ReliabilityResourceRef.of("service", "api", "1", digest('1'))
        val objective = ReliabilitySloObjective.of(
            "availability", "1", digest('2'), resource, ReliabilitySliKind.AVAILABILITY,
            999_000L, 1_000L, 100L, 2_000L, 0L, 200_000L,
        )
        val burn = ReliabilityBurnRatePolicy.of(
            "burn", "1", digest('3'), objective.objectiveDigest, 1_000_000L, 2_000_000L,
        )
        val policy = ReliabilitySloPolicySnapshot.of(
            objective, burn, "1", digest('4'), 0L, 200_000L,
        )
        val schedule = ReliabilitySloSchedule.of(
            "schedule-1", TENANT, policy.policyBindingDigest, resource,
            60_000L, 100_000L, 0L, null, null, 0L,
        )
        val repository = InMemorySloRepository(schedule)
        val clock = MutableClock(100_000L)
        val authorization = FakeAuthorization()
        val ids = SequenceIds()
        val worker = ReliabilitySloWorker(
            ReliabilityAuthorizedCallFactory(authorization, ids),
            ids,
            clock,
            ReliabilitySloPolicySource { policy },
            ReliabilitySliObservationProvider { null },
            repository,
        )
        val invocation = invocation(
            ReliabilityPurpose.EVALUATE_SLO,
            ReliabilityAction.EVALUATE_SLO,
            resource,
            rawIdempotencyKey = "slo-eval",
            requestedAt = 100_000L,
        )

        val result = worker.evaluateOne(invocation, "schedule-1", "slo-worker", 30_000L)

        assertEquals(ReliabilitySloWorkerStatus.ALERTED, result.status)
        val stored = assertNotNull(result.schedule)
        assertEquals(ReliabilityAlertSeverity.CRITICAL, stored.lastRecord?.alert?.severity)
        assertEquals(ReliabilitySloDataState.MISSING, stored.lastRecord?.evaluation?.state)
        assertFalse(requireNotNull(stored.lastRecord).evaluation.satisfied)
        assertEquals(1, repository.outboxes.size)
        assertEquals(ReliabilityOutboxType.SLO_ALERTED, repository.outboxes.single().type)
    }

    private class InMemorySloRepository(initial: ReliabilitySloSchedule) : ReliabilitySloScheduleRepository {
        private var schedule = initial
        val outboxes = ArrayList<ReliabilityOutboxRecord>()
        private var fence = 0L

        override fun load(tenantId: String, scheduleId: String): ReliabilitySloSchedule? =
            schedule.takeIf { it.tenantId == tenantId && it.scheduleId == scheduleId }

        override fun claimDue(
            tenantId: String,
            scheduleId: String,
            expectedVersion: Long,
            ownerId: String,
            nowEpochMilli: Long,
            leaseUntilEpochMilli: Long,
        ): ReliabilitySloSchedule? {
            if (schedule.tenantId != tenantId || schedule.scheduleId != scheduleId ||
                schedule.version != expectedVersion || schedule.nextEvaluationAtEpochMilli > nowEpochMilli
            ) return null
            schedule = ReliabilitySloSchedule.claimed(
                schedule, ownerId, nowEpochMilli, leaseUntilEpochMilli, ++fence,
            )
            return schedule
        }

        override fun compareAndSet(
            tenantId: String,
            scheduleId: String,
            expectedVersion: Long,
            expectedFencingToken: Long,
            candidate: ReliabilitySloSchedule,
            outbox: ReliabilityOutboxRecord,
        ): ReliabilityStoreCode {
            if (schedule.tenantId != tenantId || schedule.scheduleId != scheduleId ||
                schedule.version != expectedVersion || schedule.lease?.fencingToken != expectedFencingToken ||
                candidate.version != expectedVersion + 1L || outbox.aggregateStateDigest != candidate.stateDigest
            ) return ReliabilityStoreCode.CONFLICT
            schedule = candidate
            outboxes.add(outbox)
            return ReliabilityStoreCode.STORED
        }
    }
}
