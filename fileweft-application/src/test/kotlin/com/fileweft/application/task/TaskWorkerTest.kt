package com.fileweft.application.task

import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.spi.observability.FileWeftMetric
import com.fileweft.spi.observability.FileWeftMetrics
import com.fileweft.spi.task.FileWeftTaskHandler
import com.fileweft.spi.task.TaskExecution
import com.fileweft.spi.task.TaskHandlingResult
import com.fileweft.spi.task.TaskHandlingStatus
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskWorkerTest {
    @Test
    fun `runs task handlers outside transactions and gives legacy leases a stable owner`() {
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
        assertEquals(listOf(1, 1), repository.claimLimits)
        assertEquals(listOf("task-1"), repository.succeeded)
        assertEquals(3, transaction.executions)
    }

    @Test
    fun `claims one task at a time with a stable worker id and a new token for each task`() {
        val repository = LeasedRecordingRepository(listOf(lease("task-1"), lease("task-2"), lease("task-3")))

        val summary = worker(
            repository,
            TrackingTransaction(),
            listOf(handler { TaskHandlingResult(TaskHandlingStatus.SUCCEEDED) }),
            workerId = "task-worker-a",
            leaseDuration = Duration.ofMillis(60),
            legacyRunningGrace = Duration.ofMillis(40),
        ).processAvailable(2)

        assertEquals(2, summary.claimed)
        assertEquals(listOf("task-1", "task-2"), repository.succeeded)
        assertEquals(1, repository.remainingCount)
        assertEquals(listOf(1, 1), repository.claimLimits)
        assertEquals(listOf("task-worker-a", "task-worker-a"), repository.claims.map { it.leaseOwner })
        assertEquals(listOf(160L, 160L), repository.claims.map { it.leaseExpiresAt })
        assertEquals(listOf(60L, 60L), repository.claims.map { it.legacyRunningBefore })
        assertEquals(2, repository.claims.map { it.leaseToken }.distinct().size)
        assertEquals(repository.claims.map { it.leaseToken }, repository.succeededLeases.map { it.leaseToken })
    }

    @Test
    fun `passes the exact persisted lease to strong handlers and their terminal callbacks`() {
        val repository = LeasedRecordingRepository(listOf(lease()))
        var handledLease: BackgroundTaskLease? = null
        var exhaustedLease: BackgroundTaskLease? = null
        var legacyHandleCalls = 0
        var legacyExhaustedCalls = 0
        val handler = object : LeasedTaskHandler {
            override fun supports(task: TaskExecution): Boolean = true

            override fun handle(task: TaskExecution): TaskHandlingResult {
                legacyHandleCalls++
                return TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, "legacy path must not run")
            }

            override fun handle(lease: BackgroundTaskLease): TaskHandlingResult {
                handledLease = lease
                return TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, "invalid task")
            }

            override fun onExhausted(task: TaskExecution, message: String) {
                legacyExhaustedCalls++
            }

            override fun onExhausted(lease: BackgroundTaskLease, message: String) {
                exhaustedLease = lease
            }
        }

        val summary = worker(repository, TrackingTransaction(), listOf(handler)).processAvailable(1)

        assertEquals(1, summary.failed)
        assertEquals(0, legacyHandleCalls)
        assertEquals(0, legacyExhaustedCalls)
        assertEquals(repository.claims.single().leaseToken, handledLease?.leaseToken)
        assertEquals(handledLease?.leaseToken, exhaustedLease?.leaseToken)
        assertEquals("task-1", exhaustedLease?.task?.id?.value)
    }

    @Test
    fun `classifies lease loss raised by a strong handler without acknowledgement or retry`() {
        val repository = LeasedRecordingRepository(listOf(lease()))
        val metrics = RecordingMetrics()
        var exhausted = false
        val handler = object : LeasedTaskHandler {
            override fun supports(task: TaskExecution): Boolean = true
            override fun handle(task: TaskExecution) = error("Legacy path must not run.")
            override fun handle(lease: BackgroundTaskLease): TaskHandlingResult =
                throw TaskLeaseLostException("projection lease was replaced")

            override fun onExhausted(task: TaskExecution, message: String) {
                exhausted = true
            }

            override fun onExhausted(lease: BackgroundTaskLease, message: String) {
                exhausted = true
            }
        }

        val summary = worker(repository, TrackingTransaction(), listOf(handler), metrics = metrics).processAvailable(1)

        assertEquals(1, summary.lost)
        assertEquals(0, summary.succeeded)
        assertEquals(0, summary.retried)
        assertEquals(0, summary.failed)
        assertTrue(repository.succeeded.isEmpty())
        assertTrue(!exhausted)
        assertEquals(listOf(FileWeftMetric.TASK_LEASE_LOST), metrics.events.map { it.first })
    }

    @Test
    fun `abandons a lost task lease without terminal callbacks and continues later tasks`() {
        LostTransition.values().forEach { lostTransition ->
            val repository = LeaseLossRepository(listOf(lease("task-1"), lease("task-2")), lostTransition)
            val exhaustedCalls = AtomicInteger()
            val handler = object : FileWeftTaskHandler {
                override fun supports(task: TaskExecution): Boolean = true

                override fun handle(task: TaskExecution): TaskHandlingResult = when {
                    task.id.value == "task-2" -> TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
                    lostTransition == LostTransition.SUCCEEDED -> TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
                    lostTransition == LostTransition.RETRY -> TaskHandlingResult(TaskHandlingStatus.RETRYABLE_FAILURE, "retry")
                    else -> TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, "permanent")
                }

                override fun onExhausted(task: TaskExecution, message: String) {
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
            assertEquals(listOf("task-2"), repository.succeeded, "transition=$lostTransition")
        }
    }

    @Test
    fun `saturates task lease expiry instead of overflowing a near maximum clock`() {
        val repository = LeasedRecordingRepository(listOf(lease()))
        val clock = Clock.fixed(Instant.ofEpochMilli(Long.MAX_VALUE - 5), ZoneOffset.UTC)

        worker(
            repository,
            TrackingTransaction(),
            listOf(handler { TaskHandlingResult(TaskHandlingStatus.SUCCEEDED) }),
            clock = clock,
            leaseDuration = Duration.ofMillis(10),
        ).processAvailable(1)

        assertEquals(Long.MAX_VALUE, repository.claims.single().leaseExpiresAt)
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
        assertFailsWith<IllegalArgumentException> { worker(RecordingRepository(), TrackingTransaction(), emptyList(), workerId = " ") }
        assertFailsWith<IllegalArgumentException> {
            worker(RecordingRepository(), TrackingTransaction(), emptyList(), leaseDuration = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            worker(RecordingRepository(), TrackingTransaction(), emptyList(), leaseDuration = Duration.ofNanos(1))
        }
        assertFailsWith<IllegalArgumentException> {
            worker(RecordingRepository(), TrackingTransaction(), emptyList(), legacyRunningGrace = Duration.ofMillis(-1))
        }
        assertFailsWith<IllegalArgumentException> { BackgroundTaskLease(lease().task, "worker-a", " ") }
        assertFailsWith<IllegalArgumentException> { TaskLeaseClaim("worker-a", " ", 100) }
    }

    @Test
    fun `emits low cardinality task metrics and keeps lost leases out of task failures`() {
        val metrics = RecordingMetrics()
        worker(
            RecordingRepository(listOf(lease())),
            TrackingTransaction(),
            listOf(handler { TaskHandlingResult(TaskHandlingStatus.SUCCEEDED) }),
            metrics = metrics,
        ).processAvailable(1)
        worker(
            RecordingRepository(listOf(lease())),
            TrackingTransaction(),
            emptyList(),
            metrics = metrics,
        ).processAvailable(1)
        worker(
            RecordingRepository(listOf(lease())),
            TrackingTransaction(),
            listOf(handler { TaskHandlingResult(TaskHandlingStatus.RETRYABLE_FAILURE, "retry") }),
            metrics = metrics,
        ).processAvailable(1)
        worker(
            LeaseLossRepository(listOf(lease()), LostTransition.SUCCEEDED),
            TrackingTransaction(),
            listOf(handler { TaskHandlingResult(TaskHandlingStatus.SUCCEEDED) }),
            metrics = metrics,
        ).processAvailable(1)

        assertEquals(
            listOf(
                FileWeftMetric.TASK_SUCCESS,
                FileWeftMetric.TASK_FAILURE,
                FileWeftMetric.TASK_FAILURE,
                FileWeftMetric.TASK_LEASE_LOST,
            ),
            metrics.events.map { it.first },
        )
        assertEquals(
            List(4) { mapOf("taskType" to "document.doctor") },
            metrics.events.map { it.second },
        )
        assertTrue(metrics.events.all { "tenantId" !in it.second })
    }

    @Test
    fun `retains legacy Java constructors for task leases summaries and worker`() {
        val task = lease().task
        val legacyLease = BackgroundTaskLease::class.java.getConstructor(
            BackgroundTask::class.java,
            String::class.java,
        ).newInstance(task, "worker-a")
        assertEquals(null, legacyLease.leaseToken)

        val claim = TaskLeaseClaim::class.java.getConstructor(
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType!!,
        ).newInstance("worker-a", "token-a", 100L)
        assertEquals(0L, claim.legacyRunningBefore)

        val summary = TaskProcessingSummary::class.java.getConstructor(
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ).newInstance(1, 1, 0, 0)
        assertEquals(0, summary.lost)

        val legacyWorkerConstructor = TaskWorker::class.java.getConstructor(
            TaskProcessingRepository::class.java,
            ApplicationTransaction::class.java,
            java.util.List::class.java,
            Clock::class.java,
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Duration::class.java,
            Duration::class.java,
            Duration::class.java,
            FileWeftMetrics::class.java,
        )
        val legacyWorker = legacyWorkerConstructor.newInstance(
            RecordingRepository(),
            TrackingTransaction(),
            emptyList<FileWeftTaskHandler>(),
            Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
            "worker-a",
            3,
            Duration.ofMillis(10),
            Duration.ofMillis(40),
            Duration.ofMillis(60),
            null,
        )
        assertEquals(0, legacyWorker.processAvailable(1).claimed)
    }

    @Test
    fun `retains legacy Kotlin default constructor ABI`() {
        val legacyParameterTypes = arrayOf(
            TaskProcessingRepository::class.java,
            ApplicationTransaction::class.java,
            java.util.List::class.java,
            Clock::class.java,
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Duration::class.java,
            Duration::class.java,
            Duration::class.java,
            FileWeftMetrics::class.java,
            Int::class.javaPrimitiveType!!,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
        )
        val legacyKotlinConstructor = TaskWorker::class.java.declaredConstructors.singleOrNull { constructor ->
            constructor.parameterTypes.contentEquals(legacyParameterTypes)
        }
        assertTrue(legacyKotlinConstructor != null, "The pre-token Kotlin default constructor ABI must remain available.")

        val legacyWorker = requireNotNull(legacyKotlinConstructor).newInstance(
            RecordingRepository(),
            TrackingTransaction(),
            emptyList<FileWeftTaskHandler>(),
            Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
            "worker-a",
            0,
            null,
            null,
            null,
            null,
            0b11_1110_0000,
            null,
        ) as TaskWorker

        assertEquals(0, legacyWorker.processAvailable(1).claimed)
    }

    private fun worker(
        repository: TaskProcessingRepository,
        transaction: TrackingTransaction,
        handlers: List<FileWeftTaskHandler>,
        maxAttempts: Int = 3,
        metrics: FileWeftMetrics? = null,
        workerId: String = "worker-a",
        leaseDuration: Duration = Duration.ofMillis(60),
        legacyRunningGrace: Duration = Duration.ofMinutes(1),
        clock: Clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
    ): TaskWorker = TaskWorker(
        repository = repository,
        transaction = transaction,
        handlers = handlers,
        clock = clock,
        workerId = workerId,
        maxAttempts = maxAttempts,
        initialRetryDelay = Duration.ofMillis(10),
        maxRetryDelay = Duration.ofMillis(40),
        leaseDuration = leaseDuration,
        metrics = metrics,
        legacyRunningGrace = legacyRunningGrace,
    )

    private fun handler(handle: () -> TaskHandlingResult): FileWeftTaskHandler = object : FileWeftTaskHandler {
        override fun supports(task: TaskExecution) = true
        override fun handle(task: TaskExecution): TaskHandlingResult = handle()
    }

    private fun lease(id: String = "task-1", retryCount: Int = 0): BackgroundTaskLease = BackgroundTaskLease(
        BackgroundTask(
            id = Identifier(id), tenantId = Identifier("tenant-1"), type = "document.doctor",
            idempotencyKey = "doctor:$id", businessId = Identifier("document-1"),
            status = BackgroundTaskStatus.RUNNING, retryCount = retryCount,
        ),
        "worker-a",
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

    private class RecordingRepository(leases: List<BackgroundTaskLease> = emptyList()) : TaskProcessingRepository {
        private var available = leases.toMutableList()
        var claimOwner: String? = null
        var claimLeaseExpiry: Long? = null
        val claimLimits = mutableListOf<Int>()
        val succeeded = mutableListOf<String>()
        val retries = mutableListOf<Retry>()
        val failed = mutableListOf<Failure>()

        override fun claimAvailable(limit: Int, now: Long, leaseOwner: String, leaseExpiresAt: Long): List<BackgroundTaskLease> {
            claimOwner = leaseOwner
            claimLeaseExpiry = leaseExpiresAt
            claimLimits += limit
            return available.take(limit).also { available = available.drop(it.size).toMutableList() }
        }

        override fun markSucceeded(lease: BackgroundTaskLease, completedAt: Long) {
            succeeded += lease.task.id.value
        }

        override fun markForRetry(lease: BackgroundTaskLease, nextAttemptAt: Long, message: String, updatedAt: Long) {
            retries += Retry(nextAttemptAt, message)
        }

        override fun markFailed(lease: BackgroundTaskLease, message: String, updatedAt: Long) {
            failed += Failure(message)
        }
    }

    private class LeasedRecordingRepository(leases: List<BackgroundTaskLease>) : LeasedTaskProcessingRepository {
        private var available = leases.toMutableList()
        val claims = mutableListOf<TaskLeaseClaim>()
        val claimLimits = mutableListOf<Int>()
        val succeeded = mutableListOf<String>()
        val succeededLeases = mutableListOf<BackgroundTaskLease>()

        val remainingCount: Int
            get() = available.size

        override fun claimAvailable(
            limit: Int,
            now: Long,
            leaseOwner: String,
            leaseExpiresAt: Long,
        ): List<BackgroundTaskLease> = error("The token-aware worker path must be used for a leased repository.")

        override fun claimAvailable(limit: Int, now: Long, claim: TaskLeaseClaim): List<BackgroundTaskLease> {
            claims += claim
            claimLimits += limit
            val claimed = available.take(limit).map { lease ->
                BackgroundTaskLease(lease.task, claim.leaseOwner, claim.leaseToken)
            }
            available = available.drop(claimed.size).toMutableList()
            return claimed
        }

        override fun markSucceeded(lease: BackgroundTaskLease, completedAt: Long) {
            succeeded += lease.task.id.value
            succeededLeases += lease
        }

        override fun markForRetry(lease: BackgroundTaskLease, nextAttemptAt: Long, message: String, updatedAt: Long) = Unit

        override fun markFailed(lease: BackgroundTaskLease, message: String, updatedAt: Long) = Unit
    }

    private class LeaseLossRepository(
        leases: List<BackgroundTaskLease>,
        private val lostTransition: LostTransition,
    ) : LeasedTaskProcessingRepository {
        private var available = leases.toMutableList()
        val succeeded = mutableListOf<String>()

        override fun claimAvailable(
            limit: Int,
            now: Long,
            leaseOwner: String,
            leaseExpiresAt: Long,
        ): List<BackgroundTaskLease> = error("The token-aware worker path must be used for a leased repository.")

        override fun claimAvailable(limit: Int, now: Long, claim: TaskLeaseClaim): List<BackgroundTaskLease> {
            val claimed = available.take(limit).map { lease ->
                BackgroundTaskLease(lease.task, claim.leaseOwner, claim.leaseToken)
            }
            available = available.drop(claimed.size).toMutableList()
            return claimed
        }

        override fun markSucceeded(lease: BackgroundTaskLease, completedAt: Long) {
            if (lease.task.id.value == "task-1" && lostTransition == LostTransition.SUCCEEDED) {
                throw TaskLeaseLostException("success acknowledgement lost its lease")
            }
            succeeded += lease.task.id.value
        }

        override fun markForRetry(lease: BackgroundTaskLease, nextAttemptAt: Long, message: String, updatedAt: Long) {
            if (lease.task.id.value == "task-1" && lostTransition == LostTransition.RETRY) {
                throw TaskLeaseLostException("retry acknowledgement lost its lease")
            }
        }

        override fun markFailed(lease: BackgroundTaskLease, message: String, updatedAt: Long) {
            if (lease.task.id.value == "task-1" && lostTransition == LostTransition.FAILED) {
                throw TaskLeaseLostException("terminal acknowledgement lost its lease")
            }
        }
    }

    private enum class LostTransition {
        SUCCEEDED,
        RETRY,
        FAILED,
    }

    private data class Retry(val nextAttemptAt: Long, val message: String)
    private data class Failure(val message: String)

    private class RecordingMetrics : FileWeftMetrics {
        val events = mutableListOf<Pair<FileWeftMetric, Map<String, String>>>()

        override fun increment(metric: FileWeftMetric, tags: Map<String, String>) {
            events += metric to tags
        }
    }
}
