package com.fileweft.application.outbox

import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TraceContext
import com.fileweft.spi.event.OutboxEventHandler
import com.fileweft.spi.event.OutboxHandlingStatus
import com.fileweft.spi.observability.TraceContextScope
import java.time.Clock
import java.time.Duration
import java.util.ArrayList
import java.util.UUID
import kotlin.math.min

/**
 * Runs external outbox handlers outside database transactions and records the
 * outcome through short follow-up transactions.
 */
class OutboxWorker @JvmOverloads constructor(
    private val repository: OutboxProcessingRepository,
    private val transaction: ApplicationTransaction,
    handlers: List<OutboxEventHandler>,
    private val clock: Clock,
    private val maxAttempts: Int = 5,
    initialRetryDelay: Duration = Duration.ofSeconds(10),
    maxRetryDelay: Duration = Duration.ofMinutes(5),
    private val traceContextScope: TraceContextScope? = null,
    private val workerId: String = "fileweft-outbox-${UUID.randomUUID()}",
    leaseDuration: Duration = Duration.ofMinutes(5),
    legacyRunningGrace: Duration = Duration.ofMinutes(5),
) {
    private val handlers: List<OutboxEventHandler> = ArrayList(handlers)
    private val initialRetryDelayMillis: Long = durationMillis(initialRetryDelay, "Initial retry delay")
    private val maxRetryDelayMillis: Long = durationMillis(maxRetryDelay, "Maximum retry delay")
    private val leaseDurationMillis: Long = durationMillis(leaseDuration, "Outbox lease duration").also {
        require(it > 0) { "Outbox lease duration must be at least one millisecond." }
    }
    private val legacyRunningGraceMillis: Long = nonNegativeDurationMillis(legacyRunningGrace, "Outbox legacy running grace")

    init {
        require(workerId.isNotBlank()) { "Outbox worker id must not be blank." }
        require(maxAttempts > 0) { "Maximum outbox attempts must be positive." }
        require(maxRetryDelayMillis >= initialRetryDelayMillis) {
            "Maximum retry delay must not be shorter than initial retry delay."
        }
    }

    fun processAvailable(limit: Int): OutboxProcessingSummary {
        require(limit > 0) { "Outbox processing limit must be positive." }
        var succeeded = 0
        var retried = 0
        var failed = 0
        var lost = 0
        var claimed = 0
        for (ignored in 0 until limit) {
            val lease = claimOne() ?: break
            claimed++
            when (process(lease)) {
                ProcessingOutcome.SUCCEEDED -> succeeded++
                ProcessingOutcome.RETRIED -> retried++
                ProcessingOutcome.FAILED -> failed++
                ProcessingOutcome.LOST -> lost++
            }
        }
        return OutboxProcessingSummary(claimed, succeeded, retried, failed, lost)
    }

    /**
     * Claim one event at a time. A slow external handler can therefore only
     * consume its own bounded lease instead of expiring the remainder of a
     * large batch before processing starts.
     */
    private fun claimOne(): OutboxEventLease? {
        val claimNow = now()
        val leasedRepository = repository as? LeasedOutboxProcessingRepository
        val leaseClaim = leasedRepository?.let {
            OutboxLeaseClaim(
                leaseOwner = workerId,
                leaseToken = UUID.randomUUID().toString(),
                leaseExpiresAt = leaseExpiresAt(claimNow),
                legacyRunningBefore = safeSubtract(claimNow, legacyRunningGraceMillis),
            )
        }
        val claimed = transaction.execute {
            if (leasedRepository == null) {
                repository.claimAvailable(1, claimNow)
            } else {
                leasedRepository.claimAvailable(1, claimNow, requireNotNull(leaseClaim))
            }
        }
        require(claimed.size <= 1) { "Outbox repository returned more events than the requested single-event claim." }
        leaseClaim?.let { claim ->
            require(claimed.all { lease -> lease.leaseOwner == claim.leaseOwner && lease.leaseToken == claim.leaseToken }) {
                "Leased outbox repository returned an event without the current claim owner and token."
            }
        }
        return claimed.singleOrNull()
    }

    private fun process(lease: OutboxEventLease): ProcessingOutcome = withEventTrace(lease) {
        processInTrace(lease)
    }

    private fun processInTrace(lease: OutboxEventLease): ProcessingOutcome {
        val matchingHandlers = try {
            handlers.filter { it.supports(lease.event) }
        } catch (failure: Exception) {
            return retryOrFail(lease, "Outbox handler selection failed: ${failure.javaClass.name}")
        }
        if (matchingHandlers.isEmpty()) {
            val message = "No outbox handler supports event type ${lease.event.type}."
            return if (markFailed(lease, message)) ProcessingOutcome.FAILED else ProcessingOutcome.LOST
        }
        matchingHandlers.forEach { handler ->
            val result = try {
                handler.handle(lease.event)
            } catch (failure: Exception) {
                return retryOrFail(lease, "Outbox handler failed: ${failure.javaClass.name}", matchingHandlers)
            }
            when (result.status) {
                OutboxHandlingStatus.SUCCEEDED -> Unit
                OutboxHandlingStatus.RETRYABLE_FAILURE -> return retryOrFail(
                    lease,
                    result.message ?: "Outbox handler requested a retry.",
                    matchingHandlers,
                )

                OutboxHandlingStatus.PERMANENT_FAILURE -> {
                    return if (markFailed(lease, result.message ?: "Outbox handler reported a permanent failure.", matchingHandlers)) {
                        ProcessingOutcome.FAILED
                    } else {
                        ProcessingOutcome.LOST
                    }
                }
            }
        }
        return try {
            transaction.execute { repository.markSucceeded(lease, now()) }
            ProcessingOutcome.SUCCEEDED
        } catch (_: OutboxLeaseLostException) {
            ProcessingOutcome.LOST
        }
    }

    private fun <T> withEventTrace(lease: OutboxEventLease, action: () -> T): T {
        val scope = traceContextScope ?: return action()
        val previous = scope.currentTraceContext()
        scope.bindTraceContext(lease.event.traceId?.let(::TraceContext))
        return try {
            action()
        } finally {
            scope.bindTraceContext(previous)
        }
    }

    private fun retryOrFail(
        lease: OutboxEventLease,
        message: String,
        handlers: List<OutboxEventHandler> = emptyList(),
    ): ProcessingOutcome {
        val attemptsAfterCurrent = lease.retryCount + 1
        if (attemptsAfterCurrent >= maxAttempts) {
            return if (markFailed(lease, message, handlers)) ProcessingOutcome.FAILED else ProcessingOutcome.LOST
        }
        val now = now()
        return try {
            transaction.execute {
                repository.markForRetry(lease, nextAttemptAt(now, attemptsAfterCurrent), truncateMessage(message), now)
            }
            ProcessingOutcome.RETRIED
        } catch (_: OutboxLeaseLostException) {
            ProcessingOutcome.LOST
        }
    }

    private fun markFailed(lease: OutboxEventLease, message: String, handlers: List<OutboxEventHandler> = emptyList()): Boolean {
        val now = now()
        val truncated = truncateMessage(message)
        try {
            transaction.execute { repository.markFailed(lease, truncated, now) }
        } catch (_: OutboxLeaseLostException) {
            return false
        }
        handlers.forEach { handler ->
            try {
                handler.onExhausted(lease.event, truncated)
            } catch (_: Exception) {
                // A local failure projection cannot change the durable outbox result.
            }
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
        require(it >= 0) { "Outbox worker clock must not return a negative time." }
    }

    private fun leaseExpiresAt(claimedAt: Long): Long {
        val expiresAt = safeAdd(claimedAt, leaseDurationMillis)
        require(expiresAt > claimedAt) {
            "Outbox lease expiry cannot be represented after the current clock time."
        }
        return expiresAt
    }

    private fun safeAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private fun safeSubtract(value: Long, decrement: Long): Long =
        if (value < decrement) 0 else value - decrement

    private fun truncateMessage(message: String): String = message.take(MAX_ERROR_MESSAGE_LENGTH)

    private enum class ProcessingOutcome {
        SUCCEEDED,
        RETRIED,
        FAILED,
        LOST,
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

class OutboxProcessingSummary @JvmOverloads constructor(
    val claimed: Int,
    val succeeded: Int,
    val retried: Int,
    val failed: Int,
    val lost: Int = 0,
) {
    init {
        require(claimed >= 0 && succeeded >= 0 && retried >= 0 && failed >= 0 && lost >= 0) {
            "Outbox processing counts must not be negative."
        }
        require(succeeded + retried + failed + lost == claimed) {
            "Outbox processing outcomes must account for every claimed event."
        }
    }
}
