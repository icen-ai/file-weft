package ai.icen.fw.application.outbox

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.spi.observability.FileWeftGauge
import ai.icen.fw.spi.observability.FileWeftGaugeRecorder
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxBacklogMetricsPublisherTest {
    @Test
    fun `publishes grouped backlog gauges and oldest ready age without high cardinality tags`() {
        val clock = MutableClock(100_000)
        val transaction = TrackingTransaction()
        val reader = RecordingReader(
            OutboxBacklogSnapshot(
                readyCount = 7,
                delayedCount = 5,
                runningCount = 3,
                expiredCount = 2,
                failedCount = 1,
                oldestReadyCreatedTime = 97_500,
            ),
        ) { assertTrue(transaction.active, "The durable snapshot belongs in the short read transaction.") }
        val gauges = RecordingGauges { assertFalse(transaction.active, "Gauge writes must happen after the read transaction.") }
        val publisher = OutboxBacklogMetricsPublisher(
            transaction = transaction,
            reader = reader,
            gauges = gauges,
            clock = clock,
            samplingInterval = Duration.ofSeconds(30),
            legacyRunningGrace = Duration.ofSeconds(5),
        )

        assertTrue(publisher.publishIfDue())
        assertEquals(listOf(100_000L to 95_000L), reader.arguments)
        assertEquals(1, transaction.executions)
        assertEquals(
            listOf(
                RecordedGauge(FileWeftGauge.OUTBOX_BACKLOG, 7.0, mapOf("state" to "ready")),
                RecordedGauge(FileWeftGauge.OUTBOX_BACKLOG, 5.0, mapOf("state" to "delayed")),
                RecordedGauge(FileWeftGauge.OUTBOX_BACKLOG, 3.0, mapOf("state" to "running")),
                RecordedGauge(FileWeftGauge.OUTBOX_BACKLOG, 2.0, mapOf("state" to "expired")),
                RecordedGauge(FileWeftGauge.OUTBOX_BACKLOG, 1.0, mapOf("state" to "failed")),
                RecordedGauge(FileWeftGauge.OUTBOX_OLDEST_READY_AGE_SECONDS, 2.5, emptyMap()),
                RecordedGauge(FileWeftGauge.OUTBOX_BACKLOG_OBSERVATION_FAILURE, 0.0, emptyMap()),
            ),
            gauges.recorded(),
        )
        assertTrue(
            gauges.recorded().all { gauge ->
                gauge.tags.isEmpty() || (gauge.tags.keys == setOf("state") && gauge.tags["state"] in BACKLOG_STATES)
            },
            "Backlog metrics must never receive tenant, document, event, or connector identifiers.",
        )
    }

    @Test
    fun `uses zero cutoff before legacy grace and clamps oldest ready age`() {
        val clock = MutableClock(1_000)
        val reader = RecordingReader(
            OutboxBacklogSnapshot(
                readyCount = 1,
                delayedCount = 0,
                runningCount = 0,
                expiredCount = 0,
                failedCount = 0,
                oldestReadyCreatedTime = 2_000,
            ),
        )
        val gauges = RecordingGauges()
        val publisher = OutboxBacklogMetricsPublisher(
            transaction = TrackingTransaction(),
            reader = reader,
            gauges = gauges,
            clock = clock,
            samplingInterval = Duration.ofSeconds(1),
            legacyRunningGrace = Duration.ofMinutes(5),
        )

        assertTrue(publisher.publishIfDue())
        assertEquals(listOf(1_000L to 0L), reader.arguments)
        assertEquals(
            0.0,
            gauges.recorded().single { it.gauge == FileWeftGauge.OUTBOX_OLDEST_READY_AGE_SECONDS }.value,
        )
        assertEquals(0.0, OutboxBacklogSnapshot(0, 0, 0, 0, 0).oldestReadyAgeSeconds(1_000))
    }

    @Test
    fun `rate limits concurrent samples with an atomic gate`() {
        val clock = MutableClock(50_000)
        val reader = CountingReader()
        val publisher = OutboxBacklogMetricsPublisher(
            transaction = TrackingTransaction(),
            reader = reader,
            gauges = RecordingGauges(),
            clock = clock,
            samplingInterval = Duration.ofSeconds(10),
        )
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map {
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "Concurrent publisher test did not start in time." }
                    publisher.publishIfDue()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, reader.calls.get())
            assertFalse(publisher.publishIfDue())
            clock.advance(Duration.ofSeconds(10))
            assertTrue(publisher.publishIfDue())
            assertEquals(2, reader.calls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `dispatches a due snapshot off the worker caller and never queues a second sample`() {
        val reader = CountingReader()
        val executor = ManualExecutor()
        val publisher = OutboxBacklogMetricsPublisher(
            transaction = TrackingTransaction(),
            reader = reader,
            gauges = RecordingGauges(),
            clock = MutableClock(50_000),
            samplingInterval = Duration.ofSeconds(10),
        )

        assertTrue(publisher.publishIfDueAsync(executor))
        assertFalse(publisher.publishIfDueAsync(executor))
        assertEquals(0, reader.calls.get(), "The caller must not run the JDBC snapshot itself.")
        assertEquals(1, executor.pendingCount())

        executor.runNext()

        assertEquals(1, reader.calls.get())
        assertEquals(0, executor.pendingCount())
    }

    @Test
    fun `releases a rejected async reservation so a later worker cycle can retry`() {
        val reader = CountingReader()
        val gauges = RecordingGauges()
        val publisher = OutboxBacklogMetricsPublisher(
            transaction = TrackingTransaction(),
            reader = reader,
            gauges = gauges,
            clock = MutableClock(50_000),
            samplingInterval = Duration.ofSeconds(10),
        )

        assertFalse(
            publisher.publishIfDueAsync(
                Executor { throw RejectedExecutionException("observation lane is unavailable") },
            ),
        )
        assertTrue(publisher.publishIfDue())
        assertEquals(1, reader.calls.get())
        assertTrue(
            gauges.recorded().none { it.gauge == FileWeftGauge.OUTBOX_BACKLOG_OBSERVATION_FAILURE && it.value == 1.0 },
            "A rejected executor must not invoke a custom gauge adapter synchronously on the delivery worker thread.",
        )
    }

    @Test
    fun `does nothing when either optional observation dependency is absent`() {
        val transaction = TrackingTransaction()
        val reader = CountingReader()

        assertFalse(
            OutboxBacklogMetricsPublisher(
                transaction = transaction,
                reader = null,
                gauges = RecordingGauges(),
            ).publishIfDue(),
        )
        assertFalse(
            OutboxBacklogMetricsPublisher(
                transaction = transaction,
                reader = reader,
                gauges = null,
            ).publishIfDue(),
        )

        assertEquals(0, transaction.executions)
        assertEquals(0, reader.calls.get())
    }

    @Test
    fun `contains reader and gauge failures without changing worker-facing control flow`() {
        val failingReader = object : OutboxBacklogReader {
            override fun snapshot(now: Long, legacyRunningBefore: Long): OutboxBacklogSnapshot {
                throw IllegalStateException("database temporarily unavailable")
            }
        }
        val emptyGauges = RecordingGauges()
        val transaction = TrackingTransaction()
        val readerFailurePublisher = OutboxBacklogMetricsPublisher(
            transaction = transaction,
            reader = failingReader,
            gauges = emptyGauges,
            clock = MutableClock(1_000),
            samplingInterval = Duration.ofSeconds(1),
        )

        assertFalse(readerFailurePublisher.publishIfDue())
        assertEquals(1, transaction.executions)
        assertEquals(
            listOf(RecordedGauge(FileWeftGauge.OUTBOX_BACKLOG_OBSERVATION_FAILURE, 1.0, emptyMap())),
            emptyGauges.recorded(),
        )

        val healthyReader = CountingReader()
        val partiallyFailingGauges = RecordingGauges { gauge ->
            if (gauge.gauge == FileWeftGauge.OUTBOX_BACKLOG && gauge.tags["state"] == "ready") {
                throw IllegalStateException("metrics backend rejected one series")
            }
        }
        val gaugeFailurePublisher = OutboxBacklogMetricsPublisher(
            transaction = TrackingTransaction(),
            reader = healthyReader,
            gauges = partiallyFailingGauges,
            clock = MutableClock(2_000),
            samplingInterval = Duration.ofSeconds(1),
        )

        assertTrue(gaugeFailurePublisher.publishIfDue())
        assertEquals(1, healthyReader.calls.get())
        assertEquals(6, partiallyFailingGauges.recorded().size)
        assertTrue(
            partiallyFailingGauges.recorded().any { it.gauge == FileWeftGauge.OUTBOX_OLDEST_READY_AGE_SECONDS },
            "A failed series must not prevent the remaining low-cardinality gauges from updating.",
        )
        assertTrue(
            partiallyFailingGauges.recorded().any {
                it.gauge == FileWeftGauge.OUTBOX_BACKLOG_OBSERVATION_FAILURE && it.value == 0.0
            },
            "A successful snapshot must clear an earlier observation failure signal.",
        )
    }

    @Test
    fun `keeps immutable snapshot age and absent oldest-ready semantics explicit`() {
        val snapshot = OutboxBacklogSnapshot(
            readyCount = 1,
            delayedCount = 0,
            runningCount = 0,
            expiredCount = 0,
            failedCount = 0,
            oldestReadyCreatedTime = 5_000,
        )

        assertEquals(2.0, snapshot.oldestReadyAgeSeconds(7_000))
        assertNull(OutboxBacklogSnapshot(0, 0, 0, 0, 0).oldestReadyCreatedTime)
    }

    private class RecordingReader(
        private val result: OutboxBacklogSnapshot,
        private val onRead: () -> Unit = {},
    ) : OutboxBacklogReader {
        val arguments = CopyOnWriteArrayList<Pair<Long, Long>>()

        override fun snapshot(now: Long, legacyRunningBefore: Long): OutboxBacklogSnapshot {
            arguments += now to legacyRunningBefore
            onRead()
            return result
        }
    }

    private class CountingReader : OutboxBacklogReader {
        val calls = AtomicInteger()

        override fun snapshot(now: Long, legacyRunningBefore: Long): OutboxBacklogSnapshot {
            calls.incrementAndGet()
            return OutboxBacklogSnapshot(0, 0, 0, 0, 0)
        }
    }

    private class RecordingGauges(
        private val beforeRecord: (RecordedGauge) -> Unit = {},
    ) : FileWeftGaugeRecorder {
        private val values = CopyOnWriteArrayList<RecordedGauge>()

        override fun set(gauge: FileWeftGauge, value: Double, tags: Map<String, String>) {
            val recorded = RecordedGauge(gauge, value, tags)
            beforeRecord(recorded)
            values += recorded
        }

        fun recorded(): List<RecordedGauge> = values.toList()
    }

    private class ManualExecutor : Executor {
        private val commands = CopyOnWriteArrayList<Runnable>()

        override fun execute(command: Runnable) {
            commands += command
        }

        fun pendingCount(): Int = commands.size

        fun runNext() {
            check(commands.isNotEmpty()) { "No pending observation task exists." }
            commands.removeAt(0).run()
        }
    }

    private class RecordedGauge(
        val gauge: FileWeftGauge,
        val value: Double,
        val tags: Map<String, String>,
    ) {
        override fun equals(other: Any?): Boolean =
            other is RecordedGauge && gauge == other.gauge && value == other.value && tags == other.tags

        override fun hashCode(): Int = 31 * (31 * gauge.hashCode() + value.hashCode()) + tags.hashCode()

        override fun toString(): String = "RecordedGauge(gauge=$gauge, value=$value, tags=$tags)"
    }

    private class TrackingTransaction : ApplicationTransaction {
        var active = false
        var executions = 0

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested transaction is not expected in this fixture." }
            active = true
            return try {
                action()
            } finally {
                active = false
                executions++
            }
        }
    }

    private class MutableClock(initialMillis: Long) : Clock() {
        private val currentMillis = AtomicLong(initialMillis)

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(currentMillis.get())

        override fun millis(): Long = currentMillis.get()

        fun advance(duration: Duration) {
            currentMillis.addAndGet(duration.toMillis())
        }
    }

    private companion object {
        val BACKLOG_STATES = setOf("ready", "delayed", "running", "expired", "failed")
    }
}
