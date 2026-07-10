package com.fileweft.application.task

import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.spi.task.FileWeftTaskHandler
import com.fileweft.spi.task.TaskHandlingStatus
import java.time.Clock
import java.time.Duration
import java.util.ArrayList
import kotlin.math.min

/**
 * Multi-worker-safe task executor. A crashed worker's RUNNING lease becomes
 * eligible again after its bounded lease expires, without a manual database
 * repair step.
 */
class TaskWorker(
    private val repository: TaskProcessingRepository,
    private val transaction: ApplicationTransaction,
    handlers: List<FileWeftTaskHandler>,
    private val clock: Clock,
    private val workerId: String,
    private val maxAttempts: Int = 5,
    initialRetryDelay: Duration = Duration.ofSeconds(10),
    maxRetryDelay: Duration = Duration.ofMinutes(5),
    leaseDuration: Duration = Duration.ofMinutes(1),
) {
    private val handlers = ArrayList(handlers)
    private val initialRetryDelayMillis = durationMillis(initialRetryDelay, "Initial task retry delay")
    private val maxRetryDelayMillis = durationMillis(maxRetryDelay, "Maximum task retry delay")
    private val leaseDurationMillis = durationMillis(leaseDuration, "Task lease duration")

    init {
        require(workerId.isNotBlank()) { "Task worker id must not be blank." }
        require(maxAttempts > 0) { "Maximum task attempts must be positive." }
        require(maxRetryDelayMillis >= initialRetryDelayMillis) {
            "Maximum task retry delay must not be shorter than initial task retry delay."
        }
    }

    fun processAvailable(limit: Int): TaskProcessingSummary {
        require(limit > 0) { "Task processing limit must be positive." }
        val now = now()
        val leases = transaction.execute {
            repository.claimAvailable(limit, now, workerId, safeAdd(now, leaseDurationMillis))
        }
        var succeeded = 0
        var retried = 0
        var failed = 0
        leases.forEach { lease ->
            when (process(lease)) {
                ProcessingOutcome.SUCCEEDED -> succeeded++
                ProcessingOutcome.RETRIED -> retried++
                ProcessingOutcome.FAILED -> failed++
            }
        }
        return TaskProcessingSummary(leases.size, succeeded, retried, failed)
    }

    private fun process(lease: BackgroundTaskLease): ProcessingOutcome {
        val matchingHandlers = try {
            handlers.filter { it.supports(lease.task.execution()) }
        } catch (failure: Exception) {
            return retryOrFail(lease, "Task handler selection failed: ${failure.javaClass.name}")
        }
        if (matchingHandlers.size != 1) {
            markFailed(
                lease,
                if (matchingHandlers.isEmpty()) "No task handler supports task type ${lease.task.type}."
                else "Multiple task handlers support task type ${lease.task.type}.",
            )
            return ProcessingOutcome.FAILED
        }
        val handler = matchingHandlers.single()
        val result = try {
            handler.handle(lease.task.execution())
        } catch (failure: Exception) {
            return retryOrFail(lease, "Task handler failed: ${failure.javaClass.name}", handler)
        }
        return when (result.status) {
            TaskHandlingStatus.SUCCEEDED -> {
                transaction.execute { repository.markSucceeded(lease, now()) }
                ProcessingOutcome.SUCCEEDED
            }

            TaskHandlingStatus.RETRYABLE_FAILURE -> retryOrFail(
                lease,
                result.message ?: "Task handler requested a retry.",
                handler,
            )

            TaskHandlingStatus.PERMANENT_FAILURE -> {
                markFailed(lease, result.message ?: "Task handler reported a permanent failure.", handler)
                ProcessingOutcome.FAILED
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
            markFailed(lease, message, handler)
            return ProcessingOutcome.FAILED
        }
        val now = now()
        transaction.execute {
            repository.markForRetry(lease, nextAttemptAt(now, attemptsAfterCurrent), truncateMessage(message), now)
        }
        return ProcessingOutcome.RETRIED
    }

    private fun markFailed(lease: BackgroundTaskLease, message: String, handler: FileWeftTaskHandler? = null) {
        val truncated = truncateMessage(message)
        transaction.execute { repository.markFailed(lease, truncated, now()) }
        try {
            handler?.onExhausted(lease.task.execution(), truncated)
        } catch (_: Exception) {
            // Local terminal-state projection must not change task acknowledgement semantics.
        }
    }

    private fun nextAttemptAt(now: Long, attemptsAfterCurrent: Int): Long {
        var delay = initialRetryDelayMillis
        repeat((attemptsAfterCurrent - 1).coerceAtMost(62)) {
            delay = min(maxRetryDelayMillis, if (delay > Long.MAX_VALUE / 2) Long.MAX_VALUE else delay * 2)
        }
        return safeAdd(now, delay)
    }

    private fun now(): Long = clock.millis().also { require(it >= 0) { "Task worker clock must not return a negative time." } }

    private fun truncateMessage(message: String): String = message.take(MAX_ERROR_MESSAGE_LENGTH)

    private fun safeAdd(value: Long, increment: Long): Long = if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private enum class ProcessingOutcome { SUCCEEDED, RETRIED, FAILED }

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
    }
}

class TaskProcessingSummary(
    val claimed: Int,
    val succeeded: Int,
    val retried: Int,
    val failed: Int,
) {
    init {
        require(claimed >= 0 && succeeded >= 0 && retried >= 0 && failed >= 0) { "Task processing counts must not be negative." }
        require(claimed == succeeded + retried + failed) { "Task outcomes must account for every claimed task." }
    }
}
