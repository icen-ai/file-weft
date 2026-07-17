package ai.icen.fw.capacity.persistence.jdbc

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapacityJdbcIntentConcurrencyTest {
    @Test
    fun `one concurrent writer owns a scope and peers fail closed until canonical completion`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val row = AtomicReference<FakeIntent?>()
        val observations = Collections.synchronizedList(mutableListOf<String>())
        repeat(32) {
            pool.submit {
                start.await()
                val prepared = FakeIntent(BINDING_A, OPERATION, SCOPE, "PREPARED")
                if (row.compareAndSet(null, prepared)) observations += "OWNER"
                else observations += classify(requireNotNull(row.get()), BINDING_A).name
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5L, TimeUnit.SECONDS))
        assertEquals(1, observations.count { it == "OWNER" })
        assertEquals(31, observations.count { it == CapacityJdbcIntentDecision.UNKNOWN.name })

        row.set(FakeIntent(BINDING_A, OPERATION, SCOPE, "APPLIED"))
        assertEquals(CapacityJdbcIntentDecision.REPLAY, classify(requireNotNull(row.get()), BINDING_A))
        assertEquals(CapacityJdbcIntentDecision.CONFLICT, classify(requireNotNull(row.get()), BINDING_B))
    }

    @Test
    fun `crash after durable prepare never authorizes a blind second mutation`() {
        val prepared = FakeIntent(BINDING_A, OPERATION, SCOPE, "PREPARED")
        assertEquals(CapacityJdbcIntentDecision.UNKNOWN, classify(prepared, BINDING_A))
        val notApplied = prepared.copy(status = "NOT_APPLIED")
        assertEquals(CapacityJdbcIntentDecision.CONFLICT, classify(notApplied, BINDING_A))
    }

    private fun classify(row: FakeIntent, binding: String): CapacityJdbcIntentDecision =
        classifyCapacityJdbcIntent(
            row.binding, row.operation, row.scope, row.status,
            binding, OPERATION, SCOPE,
        )

    private data class FakeIntent(
        val binding: String,
        val operation: String,
        val scope: String,
        val status: String,
    )

    companion object {
        private const val OPERATION = "capacity.admit"
        private const val SCOPE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val BINDING_A = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val BINDING_B = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
