package ai.icen.fw.testkit.reliability

import ai.icen.fw.reliability.api.ReliabilityComponentKind
import ai.icen.fw.reliability.runtime.ReliabilityRuntimeIdKind
import ai.icen.fw.reliability.runtime.ReliabilityRuntimeIdRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReliabilityFixturesTest {
    @Test
    fun `clock and identifiers are deterministic monotonic and opaque`() {
        val clock = DeterministicReliabilityClock.startingAt(100_000L)
        assertEquals(100_000L, clock.nowEpochMilli())
        assertEquals(101_000L, clock.advance(1_000L))
        clock.set(102_000L)
        assertEquals(102_000L, clock.nowEpochMilli())
        assertThrows<IllegalArgumentException> { clock.set(101_999L) }

        val ids = DeterministicReliabilityIds.create()
        val request = ReliabilityRuntimeIdRequest.of(
            ReliabilityRuntimeIdKind.RUN,
            "tenant-contract",
            ReliabilityContractAssertions.digest('1'),
            0,
        )
        assertEquals("run-1", ids.nextId(request))
        assertEquals("run-2", ids.nextId(request))
        assertEquals(2L, ids.currentSequence())
    }

    @Test
    fun `topology factories distinguish single database and consistent multi component cuts`() {
        val single = ReliabilityTopologyFixtures.singleDatabase("tenant-contract")
        val multi = ReliabilityTopologyFixtures.multiComponent("tenant-contract")
        assertEquals(listOf(ReliabilityComponentKind.DATABASE), single.components.map { it.kind })
        assertEquals(3, multi.components.size)
        assertEquals(
            setOf(
                ReliabilityComponentKind.DATABASE,
                ReliabilityComponentKind.OBJECT_STORAGE,
                ReliabilityComponentKind.SEARCH_INDEX,
            ),
            multi.components.map { it.kind }.toSet(),
        )
        assertEquals(
            multi.components.map { it.scopeDigest }.toSet(),
            multi.objectives.objectives.map { it.scope.scopeDigest }.toSet(),
        )
        assertEquals(multi.source, multi.sourceSnapshot.environment)
        assertEquals(multi.target, multi.targetSnapshot.environment)
        assertNotEquals(single.objectives.topologyDigest, multi.objectives.topologyDigest)
    }

    @Test
    fun `strict authorization binds exact host principal and renders no sensitive values`() {
        val fixture = ReliabilityRuntimeContractHarness.inMemory()
        val invocation = fixture.operationInvocation("raw-secret-key")
        val context = fixture.calls.create(
            invocation,
            "contract-authorization",
            ReliabilityContractAssertions.digest('2'),
        )
        assertEquals(fixture.topology.tenantId, context.tenantId)
        assertEquals(fixture.authorization.operationPrincipal, context.principal)
        assertTrue(context.isFreshAt(invocation.requestedAtEpochMilli))
        ReliabilityContractAssertions.assertRedacted(
            invocation.toString(),
            "raw-secret-key",
            fixture.topology.tenantId,
            fixture.authorization.operationPrincipal.id,
        )

        val wrongPrincipal = fixture.operationInvocation(
            "wrong-principal",
            fixture.clock.nowEpochMilli(),
            fixture.topology.tenantId,
            ai.icen.fw.reliability.api.ReliabilityPrincipalRef.of("user", "intruder"),
        )
        assertThrows<IllegalStateException> {
            fixture.calls.create(
                wrongPrincipal,
                "contract-authorization",
                ReliabilityContractAssertions.digest('3'),
            )
        }
    }
}
