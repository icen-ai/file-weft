package com.fileweft.application.outbox

import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.spi.event.OutboxEventHandler
import com.fileweft.spi.event.OutboxHandlingStatus
import java.time.Clock
import java.time.Duration
import java.util.ArrayList
import kotlin.math.min

/**
 * Runs external outbox handlers outside database transactions and records the
 * outcome through short follow-up transactions.
 */
class OutboxWorker(
    private val repository: OutboxProcessingRepository,
    private val transaction: ApplicationTransaction,
    handlers: List<OutboxEventHandler>,
    private val clock: Clock,
    private val maxAttempts: Int = 5,
    initialRetryDelay: Duration = Duration.ofSeconds(10),
    maxRetryDelay: Duration = Duration.ofMinutes(5),
) {
    private val handlers: List<OutboxEventHandler> = ArrayList(handlers)
    private val initialRetryDelayMillis: Long = durationMillis(initialRetryDelay, "Initial retry delay")
    private val maxRetryDelayMillis: Long = durationMillis(maxRetryDelay, "Maximum retry delay")

    init {
        require(maxAttempts > 0) { "Maximum outbox attempts must be positive." }
        require(maxRetryDelayMillis >= initialRetryDelayMillis) {
            "Maximum retry delay must not be shorter than initial retry delay."
        }
    }

    fun processAvailable(limit: Int): OutboxProcessingSummary {
        require(limit > 0) { "Outbox processing limit must be positive." }
        val claimed = transaction.execute { repository.claimAvailable(limit, now()) }
        var succeeded = 0
        var retried = 0
        var failed = 0
        claimed.forEach { lease ->
            when (process(lease)) {
                ProcessingOutcome.SUCCEEDED -> succeeded++
                ProcessingOutcome.RETRIED -> retried++
                ProcessingOutcome.FAILED -> failed++
            }
        }
        return OutboxProcessingSummary(claimed.size, succeeded, retried, failed)
    }

    private fun process(lease: OutboxEventLease): ProcessingOutcome {
        val matchingHandlers = try {
            handlers.filter { it.supports(lease.event) }
        } catch (failure: Exception) {
            return retryOrFail(lease, "Outbox handler selection failed: ${failure.javaClass.name}")
        }
        if (matchingHandlers.size != 1) {
            val message = if (matchingHandlers.isEmpty()) {
                "No outbox handler supports event type ${lease.event.type}."
            } else {
                "Multiple outbox handlers support event type ${lease.event.type}."
            }
            markFailed(lease, message)
            return ProcessingOutcome.FAILED
        }
        val handler = matchingHandlers.single()
        val result = try {
            handler.handle(lease.event)
        } catch (failure: Exception) {
            return retryOrFail(lease, "Outbox handler failed: ${failure.javaClass.name}", handler)
        }
        return when (result.status) {
            OutboxHandlingStatus.SUCCEEDED -> {
                transaction.execute { repository.markSucceeded(lease, now()) }
                ProcessingOutcome.SUCCEEDED
            }

            OutboxHandlingStatus.RETRYABLE_FAILURE -> retryOrFail(
                lease,
                result.message ?: "Outbox handler requested a retry.",
                handler,
            )

            OutboxHandlingStatus.PERMANENT_FAILURE -> {
                markFailed(lease, result.message ?: "Outbox handler reported a permanent failure.", handler)
                ProcessingOutcome.FAILED
            }
        }
    }

    private fun retryOrFail(
        lease: OutboxEventLease,
        message: String,
        handler: OutboxEventHandler? = null,
    ): ProcessingOutcome {
        val attemptsAfterCurrent = lease.retryCount + 1
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

    private fun markFailed(lease: OutboxEventLease, message: String, handler: OutboxEventHandler? = null) {
        val now = now()
        transaction.execute { repository.markFailed(lease, truncateMessage(message), now) }
        try {
            handler?.onExhausted(lease.event, truncateMessage(message))
        } catch (_: Exception) {
            // A local failure projection cannot change the durable outbox result.
        }
    }

    private fun nextAttemptAt(now: Long, attemptsAfterCurrent: Int): Long {
        var delay = initialRetryDelayMillis
        repeat((attemptsAfterCurrent - 1).coerceAtMost(62)) {
            delay = min(maxRetryDelayMillis, if (delay > Long.MAX_VALUE / 2) Long.MAX_VALUE else delay * 2)
        }
        return if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }

    private fun now(): Long = clock.millis().also {
        require(it >= 0) { "Outbox worker clock must not return a negative time." }
    }

    private fun truncateMessage(message: String): String = message.take(MAX_ERROR_MESSAGE_LENGTH)

    private enum class ProcessingOutcome {
        SUCCEEDED,
        RETRIED,
        FAILED,
    }

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

class OutboxProcessingSummary(
    val claimed: Int,
    val succeeded: Int,
    val retried: Int,
    val failed: Int,
) {
    init {
        require(claimed >= 0 && succeeded >= 0 && retried >= 0 && failed >= 0) {
            "Outbox processing counts must not be negative."
        }
        require(succeeded + retried + failed == claimed) {
            "Outbox processing outcomes must account for every claimed event."
        }
    }
}
