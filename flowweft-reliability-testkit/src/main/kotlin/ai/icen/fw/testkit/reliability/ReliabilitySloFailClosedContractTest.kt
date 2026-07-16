package ai.icen.fw.testkit.reliability

import ai.icen.fw.reliability.api.ReliabilityAlertCode
import ai.icen.fw.reliability.api.ReliabilityAlertSeverity
import ai.icen.fw.reliability.api.ReliabilitySloDataState
import ai.icen.fw.reliability.runtime.ReliabilitySliObservationProvider
import ai.icen.fw.reliability.runtime.ReliabilitySloSchedule
import ai.icen.fw.reliability.runtime.ReliabilitySloScheduleRepository
import ai.icen.fw.reliability.runtime.ReliabilitySloWorkerStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Missing/failed SLI evidence must persist a critical fail-closed result through an exact CAS. */
abstract class ReliabilitySloFailClosedContractTest {
    /** Returns a new repository containing exactly [initial]. */
    protected abstract fun newRepository(initial: ReliabilitySloSchedule): ReliabilitySloScheduleRepository

    @Test
    fun `missing SLI data persists a critical unavailable-data alert`() {
        assertMissingDataFailsClosed(ReliabilitySliObservationProvider { null }, "missing-data")
    }

    @Test
    fun `observation provider failure is treated as missing data and fails closed`() {
        assertMissingDataFailsClosed(
            ReliabilitySliObservationProvider { throw IllegalStateException("synthetic observation failure") },
            "failed-observation",
        )
    }

    @Test
    fun `schedule lookup is tenant scoped before authorization and observation`() {
        val scenario = ReliabilitySloContractScenario.missingData()
        val repository = newRepository(scenario.schedule)
        var observationCalls = 0
        val worker = scenario.worker(repository, ReliabilitySliObservationProvider {
            observationCalls++
            null
        })
        val result = worker.evaluateOne(
            scenario.invocation("cross-tenant-slo", "tenant-not-authorized"),
            scenario.schedule.scheduleId,
            "contract-slo-worker",
            30_000L,
        )
        assertEquals(ReliabilitySloWorkerStatus.NOT_FOUND, result.status)
        assertNull(result.schedule)
        assertEquals(0, observationCalls)
        assertNull(repository.load("tenant-not-authorized", scenario.schedule.scheduleId))
    }

    @Test
    fun `same-version schedule claim has one winner with a positive fencing token`() {
        val scenario = ReliabilitySloContractScenario.missingData()
        val repository = newRepository(scenario.schedule)
        val first = repository.claimDue(
            scenario.tenantId,
            scenario.schedule.scheduleId,
            scenario.schedule.version,
            "contract-slo-owner-a",
            scenario.clock.nowEpochMilli(),
            scenario.clock.nowEpochMilli() + 30_000L,
        )
        val second = repository.claimDue(
            scenario.tenantId,
            scenario.schedule.scheduleId,
            scenario.schedule.version,
            "contract-slo-owner-b",
            scenario.clock.nowEpochMilli(),
            scenario.clock.nowEpochMilli() + 30_000L,
        )
        assertNotNull(first)
        assertNull(second)
        assertTrue(requireNotNull(requireNotNull(first).lease).fencingToken > 0L)
        assertEquals(scenario.schedule.version + 1L, first.version)
    }

    private fun assertMissingDataFailsClosed(
        observations: ReliabilitySliObservationProvider,
        key: String,
    ) {
        val scenario = ReliabilitySloContractScenario.missingData()
        val repository = newRepository(scenario.schedule)
        val result = scenario.worker(repository, observations).evaluateOne(
            scenario.invocation(key),
            scenario.schedule.scheduleId,
            "contract-slo-worker",
            30_000L,
        )
        assertEquals(ReliabilitySloWorkerStatus.ALERTED, result.status)
        val record = requireNotNull(requireNotNull(result.schedule).lastRecord)
        assertEquals(ReliabilitySloDataState.MISSING, record.evaluation.state)
        assertFalse(record.evaluation.satisfied)
        assertEquals(ReliabilityAlertSeverity.CRITICAL, record.alert.severity)
        assertEquals(ReliabilityAlertCode.DATA_UNAVAILABLE, record.alert.code)
        assertTrue(record.alert.triggered)
        ReliabilityDurableStateAssertions.assertSloScheduleRoundTrip(requireNotNull(result.schedule))
        ReliabilityDurableStateAssertions.assertSloScheduleRejectsWrongExpectedDigest(requireNotNull(result.schedule))
        assertEquals(
            result.schedule?.stateDigest,
            repository.load(scenario.tenantId, scenario.schedule.scheduleId)?.stateDigest,
            "The exact fail-closed result must be durable.",
        )
    }
}
