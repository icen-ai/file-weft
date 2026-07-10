package com.fileweft.application.task

import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.spi.task.FileWeftTaskHandler
import com.fileweft.spi.task.TaskExecution
import com.fileweft.spi.task.TaskHandlingResult
import com.fileweft.spi.task.TaskHandlingStatus
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskWorkerTest {
    @Test
    fun `runs task handlers outside transactions and gives leases a stable owner`() {
        val transaction = TrackingTransaction()
        val repository = RecordingRepository(listOf(lease()))
        var invokedOutsideTransaction = false

        val summary = worker(repository, transaction, listOf(handler {
            invokedOutsideTransaction = !transaction.active
            TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
        })).processAvailable(10)

        assertTrue(invokedOutsideTransaction)
        assertEquals(1, summary.succeeded)
        assertEquals("worker-a", repository.claimOwner)
        assertEquals(160, repository.claimLeaseExpiry)
        assertEquals(listOf("task-1"), repository.succeeded)
    }

    @Test
    fun `uses backoff and notifies only the selected handler after retry exhaustion`() {
        val retryRepository = RecordingRepository(listOf(lease()))
        val retry = worker(retryRepository, TrackingTransaction(), listOf(handler {
            TaskHandlingResult(TaskHandlingStatus.RETRYABLE_FAILURE, "temporary outage")
        })).processAvailable(1)
        assertEquals(1, retry.retried)
        assertEquals(110, retryRepository.retries.single().nextAttemptAt)

        var exhausted: String? = null
        val selected = object : FileWeftTaskHandler {
            override fun supports(task: TaskExecution) = true
            override fun handle(task: TaskExecution) = TaskHandlingResult(TaskHandlingStatus.RETRYABLE_FAILURE, "still unavailable")
            override fun onExhausted(task: TaskExecution, message: String) { exhausted = message }
        }
        val exhaustedRepository = RecordingRepository(listOf(lease(retryCount = 1)))
        val failed = worker(exhaustedRepository, TrackingTransaction(), listOf(selected), maxAttempts = 2).processAvailable(1)
        assertEquals(1, failed.failed)
        assertEquals("still unavailable", exhausted)
        assertEquals("still unavailable", exhaustedRepository.failed.single().message)
    }

    @Test
    fun `fails unsupported and ambiguous task types without a retry`() {
        val unsupported = RecordingRepository(listOf(lease()))
        assertEquals(1, worker(unsupported, TrackingTransaction(), emptyList()).processAvailable(1).failed)

        val ambiguous = RecordingRepository(listOf(lease()))
        assertEquals(
            1,
            worker(
                ambiguous,
                TrackingTransaction(),
                listOf(handler { TaskHandlingResult(TaskHandlingStatus.SUCCEEDED) }, handler { TaskHandlingResult(TaskHandlingStatus.SUCCEEDED) }),
            ).processAvailable(1).failed,
        )
    }

    @Test
    fun `rejects invalid worker configuration`() {
        assertFailsWith<IllegalArgumentException> { worker(RecordingRepository(), TrackingTransaction(), emptyList(), maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { worker(RecordingRepository(), TrackingTransaction(), emptyList()).processAvailable(0) }
    }

    private fun worker(
        repository: RecordingRepository,
        transaction: TrackingTransaction,
        handlers: List<FileWeftTaskHandler>,
        maxAttempts: Int = 3,
    ) = TaskWorker(
        repository, transaction, handlers, Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC), "worker-a", maxAttempts,
        Duration.ofMillis(10), Duration.ofMillis(40), Duration.ofMillis(60),
    )

    private fun handler(handle: () -> TaskHandlingResult): FileWeftTaskHandler = object : FileWeftTaskHandler {
        override fun supports(task: TaskExecution) = true
        override fun handle(task: TaskExecution) = handle()
    }

    private fun lease(retryCount: Int = 0) = BackgroundTaskLease(
        BackgroundTask(
            id = Identifier("task-1"), tenantId = Identifier("tenant-1"), type = "document.doctor",
            idempotencyKey = "doctor:document-1", businessId = Identifier("document-1"),
            status = BackgroundTaskStatus.RUNNING, retryCount = retryCount,
        ),
        "worker-a",
    )

    private class TrackingTransaction : ApplicationTransaction {
        var active = false
        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested test transaction is not expected." }
            active = true
            return try { action() } finally { active = false }
        }
    }

    private class RecordingRepository(leases: List<BackgroundTaskLease> = emptyList()) : TaskProcessingRepository {
        private var available = leases.toMutableList()
        var claimOwner: String? = null
        var claimLeaseExpiry: Long? = null
        val succeeded = mutableListOf<String>()
        val retries = mutableListOf<Retry>()
        val failed = mutableListOf<Failure>()

        override fun claimAvailable(limit: Int, now: Long, leaseOwner: String, leaseExpiresAt: Long): List<BackgroundTaskLease> {
            claimOwner = leaseOwner
            claimLeaseExpiry = leaseExpiresAt
            return available.take(limit).also { available = available.drop(limit).toMutableList() }
        }

        override fun markSucceeded(lease: BackgroundTaskLease, completedAt: Long) { succeeded += lease.task.id.value }
        override fun markForRetry(lease: BackgroundTaskLease, nextAttemptAt: Long, message: String, updatedAt: Long) { retries += Retry(nextAttemptAt, message) }
        override fun markFailed(lease: BackgroundTaskLease, message: String, updatedAt: Long) { failed += Failure(message) }
    }

    private data class Retry(val nextAttemptAt: Long, val message: String)
    private data class Failure(val message: String)
}
