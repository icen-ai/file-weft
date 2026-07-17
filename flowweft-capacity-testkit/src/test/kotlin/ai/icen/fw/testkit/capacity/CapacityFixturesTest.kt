package ai.icen.fw.testkit.capacity

import ai.icen.fw.capacity.api.CapacityDimension
import ai.icen.fw.capacity.api.CapacityPurpose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapacityFixturesTest {
    @Test
    fun `clock identifiers contexts and hierarchy are deterministic`() {
        val clock = DeterministicCapacityClock.startingAt(100_000L)
        assertEquals(100_000L, clock.currentTimeMillis())
        assertEquals(101_000L, clock.advance(1_000L))
        clock.set(102_000L)
        assertThrows(IllegalArgumentException::class.java) { clock.set(101_999L) }

        val ids = DeterministicCapacityIds.create()
        assertEquals("admission-1", ids.nextId("admission").value)
        assertEquals("lease-2", ids.nextId("lease").value)
        assertEquals(2L, ids.currentSequence())

        val hierarchy = CapacityHierarchyFixture.standard()
        val resolution = hierarchy.resolution()
        assertEquals(64L, resolution.limitFor(CapacityDimension.QUEUE_DEPTH)?.limit)
        assertEquals(setOf(hierarchy.degradation), resolution.allowedDegradations)

        val first = hierarchy.context(CapacityPurpose.ADMISSION, "one")
        val second = hierarchy.context(CapacityPurpose.ADMISSION, "two")
        assertNotEquals(first.bindingDigest, second.bindingDigest)
        assertEquals(first.tenantId, second.tenantId)
        assertEquals(first.principalId, second.principalId)
        assertTrue(first.toString().contains("<redacted>"))
    }
}
