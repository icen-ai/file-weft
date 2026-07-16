package ai.icen.fw.reliability.persistence.jdbc

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReliabilityJdbcConcurrencyContractTest {
    @Test
    fun `one concurrent claimant owns each expected version and fence`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val row = AtomicReference(FakeLeaseRow(7L, 41L, null))
        val winners = AtomicLong()
        val observedFences = Collections.synchronizedList(mutableListOf<Long>())

        repeat(32) { index ->
            pool.submit {
                start.await()
                while (true) {
                    val current = row.get()
                    if (current.version != 7L || current.owner != null) break
                    val candidate = FakeLeaseRow(current.version + 1L, current.nextFence + 1L, "worker-$index")
                    if (row.compareAndSet(current, candidate)) {
                        winners.incrementAndGet()
                        observedFences += current.nextFence
                        break
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5L, TimeUnit.SECONDS))

        assertEquals(1L, winners.get())
        assertEquals(listOf(41L), observedFences)
        assertEquals(8L, row.get().version)
        assertEquals(42L, row.get().nextFence)
    }

    @Test
    fun `stale acknowledgement cannot publish a re-fenced outbox claim`() {
        val current = FakeOutboxClaim("worker-new", 9L, "CLAIMED")
        assertEquals(false, canAcknowledge(current, "worker-old", 8L))
        assertEquals(false, canAcknowledge(current, "worker-new", 8L))
        assertEquals(true, canAcknowledge(current, "worker-new", 9L))
    }

    private fun canAcknowledge(row: FakeOutboxClaim, owner: String, fence: Long): Boolean =
        row.status == "CLAIMED" && row.owner == owner && row.fence == fence

    private data class FakeLeaseRow(val version: Long, val nextFence: Long, val owner: String?)
    private data class FakeOutboxClaim(val owner: String, val fence: Long, val status: String)
}
