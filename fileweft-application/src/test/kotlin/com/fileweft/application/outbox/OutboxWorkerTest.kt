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
import java.util.concurrent.atomic.AtomicInteger
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
        assertEquals(listOf(1, 1), repository.claimLimits)
        assertEquals(3, transaction.executions)
    }

    @Test
    fun `claims one event at a time with a stable worker id and a new token for each event`() {
        val repository = LeasedRecordingRepository(listOf(lease("event-1"), lease("event-2"), lease("event-3")))

        val summary = worker(
            repository,
            TrackingTransaction(),
            listOf(handler { OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED) }),
            workerId = "outbox-worker-a",
            leaseDuration = Duration.ofMillis(60),
            legacyRunningGrace = Duration.ofMillis(40),
        ).processAvailable(2)

        assertEquals(2, summary.claimed)
        assertEquals(listOf("event-1", "event-2"), repository.succeeded)
        assertEquals(1, repository.remainingCount)
        assertEquals(listOf(1, 1), repository.claimLimits)
        assertEquals(listOf("outbox-worker-a", "outbox-worker-a"), repository.claims.map { it.leaseOwner })
        assertEquals(listOf(160L, 160L), repository.claims.map { it.leaseExpiresAt })
        assertEquals(listOf(60L, 60L), repository.claims.map { it.legacyRunningBefore })
        assertEquals(2, repository.claims.map { it.leaseToken }.distinct().size)
        assertEquals(repository.claims.map { it.leaseToken }, repository.succeededLeases.map { it.leaseToken })
    }

    @Test
    fun `abandons a lost lease without terminal callbacks and continues later events`() {
        LostTransition.values().forEach { lostTransition ->
            val repository = LeaseLossRepository(listOf(lease("event-1"), lease("event-2")), lostTransition)
            val exhaustedCalls = AtomicInteger()
            val handler = object : OutboxEventHandler {
                override fun supports(event: OutboxEvent): Boolean = true

                override fun handle(event: OutboxEvent): OutboxHandlingResult = when {
                    event.id.value == "event-2" -> OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED)
                    lostTransition == LostTransition.SUCCEEDED -> OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED)
                    lostTransition == LostTransition.RETRY -> OutboxHandlingResult(OutboxHandlingStatus.RETRYABLE_FAILURE, "retry")
                    else -> OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "permanent")
                }

                override fun onExhausted(event: OutboxEvent, message: String) {
                    exhaustedCalls.incrementAndGet()
                }
            }

            val summary = worker(repository, TrackingTransaction(), listOf(handler)).processAvailable(2)

            assertEquals(2, summary.claimed, "transition=$lostTransition")
            assertEquals(1, summary.lost, "transition=$lostTransition")
            assertEquals(1, summary.succeeded, "transition=$lostTransition")
            assertEquals(0, summary.retried, "transition=$lostTransition")
            assertEquals(0, summary.failed, "transition=$lostTransition")
            assertEquals(0, exhaustedCalls.get(), "transition=$lostTransition")
            assertEquals(listOf("event-2"), repository.succeeded, "transition=$lostTransition")
        }
    }

    @Test
    fun `saturates lease expiry instead of overflowing a near maximum clock`() {
        val repository = LeasedRecordingRepository(listOf(lease()))
        val clock = Clock.fixed(Instant.ofEpochMilli(Long.MAX_VALUE - 5), ZoneOffset.UTC)

        worker(
            repository,
            TrackingTransaction(),
            listOf(handler { OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED) }),
            clock = clock,
            leaseDuration = Duration.ofMillis(10),
        ).processAvailable(1)

        assertEquals(Long.MAX_VALUE, repository.claims.single().leaseExpiresAt)
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
    fun `fails unsupported and permanently failed events without retry while fanning out matches`() {
        val unsupported = RecordingRepository(listOf(lease()))
        assertEquals(1, worker(unsupported, TrackingTransaction(), emptyList()).processAvailable(1).failed)

        val fanout = RecordingRepository(listOf(lease()))
        assertEquals(
            1,
            worker(
                fanout,
                TrackingTransaction(),
                listOf(handler { OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED) }, handler { OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED) }),
            ).processAvailable(1).succeeded,
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
    fun `notifies every matching handler when a retry is exhausted`() {
        var exhausted: String? = null
        var secondExhausted: String? = null
        val selectedHandler = object : OutboxEventHandler {
            override fun supports(event: OutboxEvent): Boolean = true
            override fun handle(event: OutboxEvent) = OutboxHandlingResult(OutboxHandlingStatus.RETRYABLE_FAILURE, "remote down")
            override fun onExhausted(event: OutboxEvent, message: String) { exhausted = message }
        }

        worker(RecordingRepository(listOf(lease(retryCount = 1))), TrackingTransaction(), listOf(selectedHandler), maxAttempts = 2)
            .processAvailable(1)
        assertEquals("remote down", exhausted)

        worker(
            RecordingRepository(listOf(lease(retryCount = 1))),
            TrackingTransaction(),
            listOf(
                selectedHandler,
                object : OutboxEventHandler {
                    override fun supports(event: OutboxEvent): Boolean = true
                    override fun handle(event: OutboxEvent) = OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED)
                    override fun onExhausted(event: OutboxEvent, message: String) { secondExhausted = message }
                },
            ),
            maxAttempts = 2,
        ).processAvailable(1)
        assertEquals("remote down", exhausted)
        assertEquals("remote down", secondExhausted)
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
        assertFailsWith<IllegalArgumentException> {
            worker(RecordingRepository(), TrackingTransaction(), emptyList(), workerId = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            worker(RecordingRepository(), TrackingTransaction(), emptyList(), leaseDuration = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            worker(RecordingRepository(), TrackingTransaction(), emptyList(), leaseDuration = Duration.ofNanos(1))
        }
        assertFailsWith<IllegalArgumentException> {
            worker(RecordingRepository(), TrackingTransaction(), emptyList(), legacyRunningGrace = Duration.ofMillis(-1))
        }
        assertFailsWith<IllegalArgumentException> {
            OutboxEventLease(lease().event, 0, "worker-a", " ")
        }
    }

    @Test
    fun `retains legacy Java constructors for leases summaries and worker`() {
        val event = lease().event
        val legacyLease = OutboxEventLease::class.java.getConstructor(
            OutboxEvent::class.java,
            Int::class.javaPrimitiveType!!,
        ).newInstance(event, 0)
        assertEquals(null, legacyLease.leaseToken)

        val claim = OutboxLeaseClaim::class.java.getConstructor(
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType!!,
        ).newInstance("worker-a", "token-a", 100L)
        assertEquals(0L, claim.legacyRunningBefore)

        val summary = OutboxProcessingSummary::class.java.getConstructor(
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ).newInstance(1, 1, 0, 0)
        assertEquals(0, summary.lost)

        val legacyWorkerConstructor = OutboxWorker::class.java.getConstructor(
            OutboxProcessingRepository::class.java,
            ApplicationTransaction::class.java,
            java.util.List::class.java,
            Clock::class.java,
            Int::class.javaPrimitiveType!!,
            Duration::class.java,
            Duration::class.java,
            TraceContextScope::class.java,
        )
        val legacyWorker = legacyWorkerConstructor.newInstance(
            RecordingRepository(),
            TrackingTransaction(),
            emptyList<OutboxEventHandler>(),
            Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
            3,
            Duration.ofMillis(10),
            Duration.ofMillis(40),
            null,
        )
        assertEquals(0, legacyWorker.processAvailable(1).claimed)
    }

    private fun worker(
        repository: OutboxProcessingRepository,
        transaction: TrackingTransaction,
        handlers: List<OutboxEventHandler>,
        maxAttempts: Int = 3,
        traces: TraceContextScope? = null,
        workerId: String = "worker-a",
        leaseDuration: Duration = Duration.ofMillis(60),
        legacyRunningGrace: Duration = Duration.ofMinutes(5),
        clock: Clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
    ): OutboxWorker = OutboxWorker(
        repository,
        transaction,
        handlers,
        clock,
        maxAttempts,
        Duration.ofMillis(10),
        Duration.ofMillis(40),
        traces,
        workerId,
        leaseDuration,
        legacyRunningGrace,
    )

    private fun handler(handle: () -> OutboxHandlingResult): OutboxEventHandler = object : OutboxEventHandler {
        override fun supports(event: OutboxEvent): Boolean = true
        override fun handle(event: OutboxEvent): OutboxHandlingResult = handle()
    }

    private fun lease(id: String = "event-1", retryCount: Int = 0, traceId: String? = null): OutboxEventLease = OutboxEventLease(
        OutboxEvent(
            Identifier(id), Identifier("tenant-1"), "document.publish.requested", emptyMap(), 1,
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
        val claimLimits = mutableListOf<Int>()
        val succeeded = mutableListOf<String>()
        val retries = mutableListOf<Retry>()
        val failed = mutableListOf<Failure>()

        override fun claimAvailable(limit: Int, now: Long): List<OutboxEventLease> = available.take(limit).also {
            claimLimits += limit
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

    private class LeasedRecordingRepository(
        leases: List<OutboxEventLease>,
    ) : LeasedOutboxProcessingRepository {
        private var available = leases.toMutableList()
        val claims = mutableListOf<OutboxLeaseClaim>()
        val claimLimits = mutableListOf<Int>()
        val succeeded = mutableListOf<String>()
        val succeededLeases = mutableListOf<OutboxEventLease>()

        val remainingCount: Int
            get() = available.size

        override fun claimAvailable(limit: Int, now: Long): List<OutboxEventLease> =
            error("The token-aware worker path must be used for a leased repository.")

        override fun claimAvailable(limit: Int, now: Long, claim: OutboxLeaseClaim): List<OutboxEventLease> {
            claims += claim
            claimLimits += limit
            val claimed = available.take(limit).map { lease ->
                OutboxEventLease(lease.event, lease.retryCount, claim.leaseOwner, claim.leaseToken)
            }
            available = available.drop(claimed.size).toMutableList()
            return claimed
        }

        override fun markSucceeded(lease: OutboxEventLease, completedAt: Long) {
            succeeded += lease.event.id.value
            succeededLeases += lease
        }

        override fun markForRetry(lease: OutboxEventLease, nextAttemptAt: Long, message: String, updatedAt: Long) = Unit

        override fun markFailed(lease: OutboxEventLease, message: String, updatedAt: Long) = Unit
    }

    private class LeaseLossRepository(
        leases: List<OutboxEventLease>,
        private val lostTransition: LostTransition,
    ) : LeasedOutboxProcessingRepository {
        private var available = leases.toMutableList()
        val succeeded = mutableListOf<String>()

        override fun claimAvailable(limit: Int, now: Long): List<OutboxEventLease> =
            error("The token-aware worker path must be used for a leased repository.")

        override fun claimAvailable(limit: Int, now: Long, claim: OutboxLeaseClaim): List<OutboxEventLease> {
            val claimed = available.take(limit).map { lease ->
                OutboxEventLease(lease.event, lease.retryCount, claim.leaseOwner, claim.leaseToken)
            }
            available = available.drop(claimed.size).toMutableList()
            return claimed
        }

        override fun markSucceeded(lease: OutboxEventLease, completedAt: Long) {
            if (lease.event.id.value == "event-1" && lostTransition == LostTransition.SUCCEEDED) {
                throw OutboxLeaseLostException("success acknowledgement lost its lease")
            }
            succeeded += lease.event.id.value
        }

        override fun markForRetry(lease: OutboxEventLease, nextAttemptAt: Long, message: String, updatedAt: Long) {
            if (lease.event.id.value == "event-1" && lostTransition == LostTransition.RETRY) {
                throw OutboxLeaseLostException("retry acknowledgement lost its lease")
            }
        }

        override fun markFailed(lease: OutboxEventLease, message: String, updatedAt: Long) {
            if (lease.event.id.value == "event-1" && lostTransition == LostTransition.FAILED) {
                throw OutboxLeaseLostException("terminal acknowledgement lost its lease")
            }
        }
    }

    private enum class LostTransition {
        SUCCEEDED,
        RETRY,
        FAILED,
    }

    private class RecordingTraceScope(initial: TraceContext?) : TraceContextScope {
        private var traceContext = initial

        override fun currentTraceContext(): TraceContext? = traceContext

        override fun bindTraceContext(traceContext: TraceContext?) {
            this.traceContext = traceContext
        }
    }
}
