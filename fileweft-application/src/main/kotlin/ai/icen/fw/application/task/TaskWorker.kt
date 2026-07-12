package ai.icen.fw.application.task

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.task.FileWeftTaskHandler
import ai.icen.fw.spi.task.TaskHandlingStatus
import java.time.Clock
import java.time.Duration
import java.util.ArrayList
import java.util.UUID
import kotlin.math.min

/**
 * Multi-worker-safe task executor. External handlers always run outside a
 * local database transaction. Repositories that opt into
 * [LeasedTaskProcessingRepository] additionally fence stale acknowledgements
 * with a unique claim token.
 */
class TaskWorker private constructor(
    private val repository: TaskProcessingRepository,
    private val transaction: ApplicationTransaction,
    handlers: List<FileWeftTaskHandler>,
    private val clock: Clock,
    private val maxAttempts: Int,
    initialRetryDelay: Duration,
    maxRetryDelay: Duration,
    private val metrics: FileWeftMetrics?,
    leaseSettings: TaskLeaseSettings,
) {
    private val workerId: String = leaseSettings.workerId

    /**
     * Retains the original Kotlin default-constructor ABI for plugins. Do not
     * append persisted-lease settings here: compiled Kotlin callers link to
     * this constructor's synthetic default-argument overload.
     */
    constructor(
        repository: TaskProcessingRepository,
        transaction: ApplicationTransaction,
        handlers: List<FileWeftTaskHandler>,
        clock: Clock,
        workerId: String,
        maxAttempts: Int = 5,
        initialRetryDelay: Duration = Duration.ofSeconds(10),
        maxRetryDelay: Duration = Duration.ofMinutes(5),
        leaseDuration: Duration = Duration.ofMinutes(1),
        metrics: FileWeftMetrics? = null,
    ) : this(
        repository = repository,
        transaction = transaction,
        handlers = handlers,
        clock = clock,
        maxAttempts = maxAttempts,
        initialRetryDelay = initialRetryDelay,
        maxRetryDelay = maxRetryDelay,
        metrics = metrics,
        leaseSettings = TaskLeaseSettings(
            workerId = workerId,
            leaseDuration = leaseDuration,
            legacyRunningGrace = Duration.ofMinutes(5),
        ),
    )

    /**
     * Adds persisted-lease configuration without changing the constructor
     * used by plugins compiled against the pre-token task API.
     */
    constructor(
        repository: TaskProcessingRepository,
        transaction: ApplicationTransaction,
        handlers: List<FileWeftTaskHandler>,
        clock: Clock,
        workerId: String,
        maxAttempts: Int = 5,
        initialRetryDelay: Duration = Duration.ofSeconds(10),
        maxRetryDelay: Duration = Duration.ofMinutes(5),
        leaseDuration: Duration = Duration.ofMinutes(1),
        metrics: FileWeftMetrics? = null,
        legacyRunningGrace: Duration,
    ) : this(
        repository = repository,
        transaction = transaction,
        handlers = handlers,
        clock = clock,
        maxAttempts = maxAttempts,
        initialRetryDelay = initialRetryDelay,
        maxRetryDelay = maxRetryDelay,
        metrics = metrics,
        leaseSettings = TaskLeaseSettings(workerId, leaseDuration, legacyRunningGrace),
    )

    private val handlers = ArrayList(handlers)
    private val initialRetryDelayMillis = durationMillis(initialRetryDelay, "Initial task retry delay")
    private val maxRetryDelayMillis = durationMillis(maxRetryDelay, "Maximum task retry delay")
    private val leaseDurationMillis = durationMillis(leaseSettings.leaseDuration, "Task lease duration").also {
        require(it > 0) { "Task lease duration must be at least one millisecond." }
    }
    private val legacyRunningGraceMillis = nonNegativeDurationMillis(
        leaseSettings.legacyRunningGrace,
        "Task legacy running grace",
    )

    init {
        require(workerId.isNotBlank()) { "Task worker id must not be blank." }
        require(maxAttempts > 0) { "Maximum task attempts must be positive." }
        require(maxRetryDelayMillis >= initialRetryDelayMillis) {
            "Maximum task retry delay must not be shorter than initial task retry delay."
        }
    }

    fun processAvailable(limit: Int): TaskProcessingSummary {
        require(limit > 0) { "Task processing limit must be positive." }
        var succeeded = 0
        var retried = 0
        var failed = 0
        var lost = 0
        var claimed = 0
        for (ignored in 0 until limit) {
            val lease = claimOne() ?: break
            claimed++
            val outcome = process(lease)
            when (outcome) {
                ProcessingOutcome.SUCCEEDED -> succeeded++
                ProcessingOutcome.RETRIED -> retried++
                ProcessingOutcome.FAILED -> failed++
                ProcessingOutcome.LOST -> lost++
            }
            recordMetric(lease, outcome)
        }
        return TaskProcessingSummary(claimed, succeeded, retried, failed, lost)
    }

    /**
     * Claim one task at a time so a slow external handler cannot let a whole
     * batch of unrelated task leases expire before their handlers start.
     */
    private fun claimOne(): BackgroundTaskLease? {
        val claimNow = now()
        val leasedRepository = repository as? LeasedTaskProcessingRepository
        val leaseClaim = leasedRepository?.let {
            TaskLeaseClaim(
                leaseOwner = workerId,
                leaseToken = UUID.randomUUID().toString(),
                leaseExpiresAt = leaseExpiresAt(claimNow),
                legacyRunningBefore = safeSubtract(claimNow, legacyRunningGraceMillis),
            )
        }
        val claimed = transaction.execute {
            if (leasedRepository == null) {
                repository.claimAvailable(1, claimNow, workerId, leaseExpiresAt(claimNow))
            } else {
                leasedRepository.claimAvailable(1, claimNow, requireNotNull(leaseClaim))
            }
        }
        require(claimed.size <= 1) { "Task repository returned more tasks than the requested single-task claim." }
        leaseClaim?.let { claim ->
            require(claimed.all { lease -> lease.leaseOwner == claim.leaseOwner && lease.leaseToken == claim.leaseToken }) {
                "Leased task repository returned a task without the current claim owner and token."
            }
        }
        return claimed.singleOrNull()
    }

    private fun process(lease: BackgroundTaskLease): ProcessingOutcome {
        val matchingHandlers = try {
            handlers.filter { it.supports(lease.task.execution()) }
        } catch (failure: Exception) {
            return retryOrFail(lease, "Task handler selection failed: ${failure.javaClass.name}")
        }
        if (matchingHandlers.size != 1) {
            return if (
                markFailed(
                    lease,
                    if (matchingHandlers.isEmpty()) "No task handler supports task type ${lease.task.type}."
                    else "Multiple task handlers support task type ${lease.task.type}.",
                )
            ) {
                ProcessingOutcome.FAILED
            } else {
                ProcessingOutcome.LOST
            }
        }
        val handler = matchingHandlers.single()
        val result = try {
            if (handler is LeasedTaskHandler) {
                handler.handle(lease)
            } else {
                handler.handle(lease.task.execution())
            }
        } catch (_: TaskLeaseLostException) {
            return ProcessingOutcome.LOST
        } catch (failure: Exception) {
            return retryOrFail(lease, "Task handler failed: ${failure.javaClass.name}", handler)
        }
        return when (result.status) {
            TaskHandlingStatus.SUCCEEDED -> {
                try {
                    transaction.execute { repository.markSucceeded(lease, now()) }
                    ProcessingOutcome.SUCCEEDED
                } catch (_: TaskLeaseLostException) {
                    ProcessingOutcome.LOST
                }
            }

            TaskHandlingStatus.RETRYABLE_FAILURE -> retryOrFail(
                lease,
                result.message ?: "Task handler requested a retry.",
                handler,
            )

            TaskHandlingStatus.PERMANENT_FAILURE -> {
                if (markFailed(lease, result.message ?: "Task handler reported a permanent failure.", handler)) {
                    ProcessingOutcome.FAILED
                } else {
                    ProcessingOutcome.LOST
                }
            }
        }
    }

    private fun retryOrFail(
        lease: BackgroundTaskLease,
        message: String,
        handler: FileWeftTaskHandler? = null,
    ): ProcessingOutcome {
        val attemptsAfterCurrent = lease.task.retryCount + 1
        if (attemptsAfterCurrent >= maxAttempts) {
            return if (markFailed(lease, message, handler)) ProcessingOutcome.FAILED else ProcessingOutcome.LOST
        }
        val now = now()
        return try {
            transaction.execute {
                repository.markForRetry(lease, nextAttemptAt(now, attemptsAfterCurrent), truncateMessage(message), now)
            }
            ProcessingOutcome.RETRIED
        } catch (_: TaskLeaseLostException) {
            ProcessingOutcome.LOST
        }
    }

    private fun markFailed(
        lease: BackgroundTaskLease,
        message: String,
        handler: FileWeftTaskHandler? = null,
    ): Boolean {
        val truncated = truncateMessage(message)
        try {
            transaction.execute { repository.markFailed(lease, truncated, now()) }
        } catch (_: TaskLeaseLostException) {
            return false
        }
        try {
            if (handler is LeasedTaskHandler) {
                handler.onExhausted(lease, truncated)
            } else {
                handler?.onExhausted(lease.task.execution(), truncated)
            }
        } catch (_: Exception) {
            // Local terminal-state projection must not change task acknowledgement semantics.
        }
        return true
    }

    private fun nextAttemptAt(now: Long, attemptsAfterCurrent: Int): Long {
        var delay = initialRetryDelayMillis
        repeat((attemptsAfterCurrent - 1).coerceAtMost(62)) {
            delay = min(maxRetryDelayMillis, if (delay > Long.MAX_VALUE / 2) Long.MAX_VALUE else delay * 2)
        }
        return safeAdd(now, delay)
    }

    private fun now(): Long = clock.millis().also {
        require(it >= 0) { "Task worker clock must not return a negative time." }
    }

    private fun leaseExpiresAt(claimedAt: Long): Long {
        val expiresAt = safeAdd(claimedAt, leaseDurationMillis)
        require(expiresAt > claimedAt) {
            "Task lease expiry cannot be represented after the current clock time."
        }
        return expiresAt
    }

    private fun truncateMessage(message: String): String = message.take(MAX_ERROR_MESSAGE_LENGTH)

    private fun safeAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private fun safeSubtract(value: Long, decrement: Long): Long = if (value < decrement) 0 else value - decrement

    private fun recordMetric(lease: BackgroundTaskLease, outcome: ProcessingOutcome) {
        val metric = when (outcome) {
            ProcessingOutcome.SUCCEEDED -> FileWeftMetric.TASK_SUCCESS
            ProcessingOutcome.RETRIED,
            ProcessingOutcome.FAILED,
            -> FileWeftMetric.TASK_FAILURE

            ProcessingOutcome.LOST -> FileWeftMetric.TASK_LEASE_LOST
        }
        try {
            // The generic SPI may be implemented by a metrics system that does
            // not filter high-cardinality tags, so never pass tenant identity.
            metrics?.increment(metric, mapOf("taskType" to lease.task.type))
        } catch (_: Exception) {
            // Metrics must not affect task acknowledgement or lease recovery.
        }
    }

    private enum class ProcessingOutcome { SUCCEEDED, RETRIED, FAILED, LOST }

    private class TaskLeaseSettings(
        val workerId: String,
        val leaseDuration: Duration,
        val legacyRunningGrace: Duration,
    )

    private companion object {
        const val MAX_ERROR_MESSAGE_LENGTH = 1024

        fun durationMillis(duration: Duration, name: String): Long {
            require(!duration.isNegative && !duration.isZero) { "$name must be positive." }
            return try {
                duration.toMillis()
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("$name is too large.", failure)
            }
        }

        fun nonNegativeDurationMillis(duration: Duration, name: String): Long {
            require(!duration.isNegative) { "$name must not be negative." }
            return try {
                duration.toMillis()
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("$name is too large.", failure)
            }
        }
    }
}

class TaskProcessingSummary @JvmOverloads constructor(
    val claimed: Int,
    val succeeded: Int,
    val retried: Int,
    val failed: Int,
    val lost: Int = 0,
) {
    init {
        require(claimed >= 0 && succeeded >= 0 && retried >= 0 && failed >= 0 && lost >= 0) {
            "Task processing counts must not be negative."
        }
        require(claimed == succeeded + retried + failed + lost) {
            "Task outcomes must account for every claimed task."
        }
    }
}
