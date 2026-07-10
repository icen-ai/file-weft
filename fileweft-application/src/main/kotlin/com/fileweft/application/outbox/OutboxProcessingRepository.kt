package com.fileweft.application.outbox

import com.fileweft.core.event.OutboxEvent

/** A leased event is marked RUNNING before its external handler is invoked. */
class OutboxEventLease(
    val event: OutboxEvent,
    val retryCount: Int,
) {
    init {
        require(retryCount >= 0) { "Outbox event retry count must not be negative." }
    }
}

/**
 * Persistence port for the outbox worker. Every method is expected to execute
 * inside a short [com.fileweft.application.transaction.ApplicationTransaction].
 */
interface OutboxProcessingRepository {
    fun claimAvailable(limit: Int, now: Long): List<OutboxEventLease>

    fun markSucceeded(lease: OutboxEventLease, completedAt: Long)

    fun markForRetry(lease: OutboxEventLease, nextAttemptAt: Long, message: String, updatedAt: Long)

    fun markFailed(lease: OutboxEventLease, message: String, updatedAt: Long)
}
