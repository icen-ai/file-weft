package com.fileweft.application.outbox

import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TraceContext
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.spi.event.OutboxEventHandler
import com.fileweft.spi.event.OutboxHandlingResult
import com.fileweft.spi.event.OutboxHandlingStatus
import com.fileweft.spi.observability.TraceContextScope
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OutboxWorkerTest {
    @Test
    fun `claims records and handles a successful event outside database transactions`() {
        val transaction = TrackingTransaction()
        val repository = RecordingRepository(listOf(lease()))
        var invokedOutsideTransaction = false
        val summary = worker(repository, transaction, listOf(handler {
            invokedOutsideTransaction = !transaction.active
            OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED)
        })).processAvailable(10)

        assertTrue(invokedOutsideTransaction)
        assertEquals(1, summary.succeeded)
        assertEquals(listOf("event-1"), repository.succeeded)
        assertEquals(2, transaction.executions)
    }

    @Test
    fun `schedules retry with backoff and caps repeated handler failures`() {
        val retryRepository = RecordingRepository(listOf(lease(retryCount = 0)))
        val retrySummary = worker(retryRepository, TrackingTransaction(), listOf(handler {
            OutboxHandlingResult(OutboxHandlingStatus.RETRYABLE_FAILURE, "remote unavailable")
        })).processAvailable(1)

        assertEquals(1, retrySummary.retried)
        assertEquals(110, retryRepository.retries.single().nextAttemptAt)
        assertEquals("remote unavailable", retryRepository.retries.single().message)

        val failedRepository = RecordingRepository(listOf(lease(retryCount = 1)))
        val failedSummary = worker(
            failedRepository,
            TrackingTransaction(),
            listOf(handler { throw IllegalStateException("offline") }),
            maxAttempts = 2,
        ).processAvailable(1)

        assertEquals(1, failedSummary.failed)
        assertTrue(failedRepository.failed.single().message.startsWith("Outbox handler failed:"))
    }

    @Test
    fun `fails unsupported ambiguous and permanently failed events without retry`() {
        val unsupported = RecordingRepository(listOf(lease()))
        assertEquals(1, worker(unsupported, TrackingTransaction(), emptyList()).processAvailable(1).failed)

        val ambiguous = RecordingRepository(listOf(lease()))
        assertEquals(
            1,
            worker(
                ambiguous,
                TrackingTransaction(),
                listOf(handler { OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED) }, handler { OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED) }),
            ).processAvailable(1).failed,
        )

        val permanent = RecordingRepository(listOf(lease()))
        assertEquals(
            1,
            worker(
                permanent,
                TrackingTransaction(),
                listOf(handler { OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "invalid payload") }),
            ).processAvailable(1).failed,
        )
        assertEquals("invalid payload", permanent.failed.single().message)
    }

    @Test
    fun `notifies the selected handler when a retry is exhausted but never for ambiguous routing`() {
        var exhausted: String? = null
        val selectedHandler = object : OutboxEventHandler {
            override fun supports(event: OutboxEvent): Boolean = true
            override fun handle(event: OutboxEvent) = OutboxHandlingResult(OutboxHandlingStatus.RETRYABLE_FAILURE, "remote down")
            override fun onExhausted(event: OutboxEvent, message: String) { exhausted = message }
        }

        worker(RecordingRepository(listOf(lease(retryCount = 1))), TrackingTransaction(), listOf(selectedHandler), maxAttempts = 2)
            .processAvailable(1)
        assertEquals("remote down", exhausted)

        exhausted = null
        worker(
            RecordingRepository(listOf(lease())),
            TrackingTransaction(),
            listOf(selectedHandler, handler { OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED) }),
        ).processAvailable(1)
        assertEquals(null, exhausted)
    }

    @Test
    fun `binds an event trace while handling and restores the prior worker context`() {
        val traces = RecordingTraceScope(TraceContext(Identifier("trace-before")))
        var handledTrace: String? = null

        val summary = worker(
            RecordingRepository(listOf(lease(traceId = "trace-event"))),
            TrackingTransaction(),
            listOf(handler {
                handledTrace = traces.currentTraceContext()?.traceId?.value
                OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED)
            }),
            traces = traces,
        ).processAvailable(1)

        assertEquals(1, summary.succeeded)
        assertEquals("trace-event", handledTrace)
        assertEquals("trace-before", traces.currentTraceContext()?.traceId?.value)
    }

    @Test
    fun `rejects invalid worker configuration and processing limit`() {
        assertFailsWith<IllegalArgumentException> {
            worker(RecordingRepository(), TrackingTransaction(), emptyList(), maxAttempts = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            worker(RecordingRepository(), TrackingTransaction(), emptyList()).processAvailable(0)
        }
    }

    private fun worker(
        repository: RecordingRepository,
        transaction: TrackingTransaction,
        handlers: List<OutboxEventHandler>,
        maxAttempts: Int = 3,
        traces: TraceContextScope? = null,
    ): OutboxWorker = OutboxWorker(
        repository,
        transaction,
        handlers,
        Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
        maxAttempts,
        Duration.ofMillis(10),
        Duration.ofMillis(40),
        traces,
    )

    private fun handler(handle: () -> OutboxHandlingResult): OutboxEventHandler = object : OutboxEventHandler {
        override fun supports(event: OutboxEvent): Boolean = true
        override fun handle(event: OutboxEvent): OutboxHandlingResult = handle()
    }

    private fun lease(retryCount: Int = 0, traceId: String? = null): OutboxEventLease = OutboxEventLease(
        OutboxEvent(
            Identifier("event-1"), Identifier("tenant-1"), "document.publish.requested", emptyMap(), 1,
            traceId?.let(::Identifier),
        ),
        retryCount,
    )

    private class TrackingTransaction : ApplicationTransaction {
        var active = false
        var executions = 0

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested test transaction is not expected." }
            executions++
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }

    private class RecordingRepository(
        leases: List<OutboxEventLease> = emptyList(),
    ) : OutboxProcessingRepository {
        private var available = leases.toMutableList()
        val succeeded = mutableListOf<String>()
        val retries = mutableListOf<Retry>()
        val failed = mutableListOf<Failure>()

        override fun claimAvailable(limit: Int, now: Long): List<OutboxEventLease> = available.take(limit).also {
            available = available.drop(limit).toMutableList()
        }

        override fun markSucceeded(lease: OutboxEventLease, completedAt: Long) {
            succeeded += lease.event.id.value
        }

        override fun markForRetry(lease: OutboxEventLease, nextAttemptAt: Long, message: String, updatedAt: Long) {
            retries += Retry(nextAttemptAt, message)
        }

        override fun markFailed(lease: OutboxEventLease, message: String, updatedAt: Long) {
            failed += Failure(message)
        }
    }

    private data class Retry(val nextAttemptAt: Long, val message: String)
    private data class Failure(val message: String)

    private class RecordingTraceScope(initial: TraceContext?) : TraceContextScope {
        private var traceContext = initial

        override fun currentTraceContext(): TraceContext? = traceContext

        override fun bindTraceContext(traceContext: TraceContext?) {
            this.traceContext = traceContext
        }
    }
}
