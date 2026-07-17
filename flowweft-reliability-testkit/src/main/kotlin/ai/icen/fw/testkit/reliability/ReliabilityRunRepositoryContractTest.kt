package ai.icen.fw.testkit.reliability

import ai.icen.fw.reliability.runtime.ReliabilityOutboxRecord
import ai.icen.fw.reliability.runtime.ReliabilityOutboxType
import ai.icen.fw.reliability.runtime.ReliabilityRun
import ai.icen.fw.reliability.runtime.ReliabilityRunRepository
import ai.icen.fw.reliability.runtime.ReliabilityStoreCode
import ai.icen.fw.reliability.runtime.ReliabilitySubmissionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reusable tenant/CAS/fencing contract for [ReliabilityRunRepository] implementations. */
abstract class ReliabilityRunRepositoryContractTest {
    /** Returns a new empty repository for every test invocation. */
    protected abstract fun newRepository(): ReliabilityRunRepository

    @Test
    fun `create and replay are tenant scoped by the stable idempotency digest`() {
        val fixture = harness()
        val first = fixture.submitCreate("repository-replay")
        val replay = fixture.submitCreate("repository-replay")
        assertEquals(ReliabilitySubmissionStatus.CREATED, first.status)
        assertEquals(ReliabilitySubmissionStatus.REPLAY, replay.status)
        assertEquals(first.run?.runId, replay.run?.runId)
        val run = requireNotNull(first.run)
        assertEquals(run.stateDigest, fixture.repository.load(fixture.topology.tenantId, run.runId)?.stateDigest)
        val loaded = requireNotNull(fixture.repository.load(fixture.topology.tenantId, run.runId))
        ReliabilityDurableStateAssertions.assertRunRoundTrip(loaded)
        ReliabilityDurableStateAssertions.assertRunRejectsWrongExpectedDigest(loaded)
        assertNull(fixture.repository.load("tenant-not-authorized", run.runId))
        assertNull(
            fixture.repository.findByIdempotency("tenant-not-authorized", run.intent.idempotencyDigest),
        )
    }

    @Test
    fun `only one same-version claimant wins and receives a positive monotonic fence`() {
        val fixture = harness()
        val run = submit(fixture, "repository-claim")
        val first = fixture.repository.claim(
            run.tenantId,
            run.runId,
            run.version,
            "claim-owner-a",
            fixture.clock.nowEpochMilli(),
            fixture.clock.nowEpochMilli() + 30_000L,
        )
        val second = fixture.repository.claim(
            run.tenantId,
            run.runId,
            run.version,
            "claim-owner-b",
            fixture.clock.nowEpochMilli(),
            fixture.clock.nowEpochMilli() + 30_000L,
        )
        assertEquals(ReliabilityStoreCode.STORED, first.code)
        assertEquals(ReliabilityStoreCode.CONFLICT, second.code)
        val claimed = requireNotNull(first.run)
        assertNotNull(claimed.lease)
        assertTrue(requireNotNull(claimed.lease).fencingToken > 0L)
        assertEquals(run.version + 1L, claimed.version)
        assertEquals(claimed.stateDigest, fixture.repository.load(run.tenantId, run.runId)?.stateDigest)
    }

    @Test
    fun `stale fencing token cannot store a candidate and exact fence can`() {
        val fixture = harness()
        val run = submit(fixture, "repository-fence")
        val claimed = requireNotNull(
            fixture.repository.claim(
                run.tenantId,
                run.runId,
                run.version,
                "fence-owner",
                fixture.clock.nowEpochMilli(),
                fixture.clock.nowEpochMilli() + 30_000L,
            ).run,
        )
        val fence = requireNotNull(claimed.lease).fencingToken
        val candidate = ReliabilityRun.claimed(
            claimed,
            "fence-owner",
            fixture.clock.nowEpochMilli() + 1L,
            fixture.clock.nowEpochMilli() + 30_001L,
            fence,
        )
        val outbox = ReliabilityOutboxRecord.forRun(
            "contract-outbox-fenced",
            ReliabilityOutboxType.CALL_STARTED,
            candidate,
            fixture.clock.nowEpochMilli() + 1L,
        )
        val stale = fixture.repository.compareAndSet(
            claimed.tenantId,
            claimed.runId,
            claimed.version,
            fence + 1L,
            candidate,
            outbox,
        )
        assertNotEquals(ReliabilityStoreCode.STORED, stale.code)
        assertEquals(claimed.stateDigest, fixture.repository.load(claimed.tenantId, claimed.runId)?.stateDigest)

        val stored = fixture.repository.compareAndSet(
            claimed.tenantId,
            claimed.runId,
            claimed.version,
            fence,
            candidate,
            outbox,
        )
        assertEquals(ReliabilityStoreCode.STORED, stored.code)
        assertEquals(candidate.stateDigest, stored.run?.stateDigest)
        assertEquals(candidate.stateDigest, fixture.repository.load(candidate.tenantId, candidate.runId)?.stateDigest)
    }

    private fun harness(): ReliabilityRuntimeContractHarness {
        val topology = ReliabilityTopologyFixtures.singleDatabase("tenant-contract")
        return ReliabilityRuntimeContractHarness.of(
            topology,
            newRepository(),
            DeterministicReliabilityProvider.forDescriptor(topology.providerDescriptor),
            topology.providerDescriptor,
        )
    }

    private fun submit(fixture: ReliabilityRuntimeContractHarness, key: String): ReliabilityRun {
        val result = fixture.submitCreate(key)
        assertEquals(ReliabilitySubmissionStatus.CREATED, result.status)
        return requireNotNull(result.run)
    }
}
