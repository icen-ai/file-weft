package com.fileweft.application.task

import com.fileweft.core.id.Identifier

/** Application port for enqueuing and inspecting durable background work. */
interface TaskRepository {
    /** Duplicate tenant/idempotency pairs are intentionally a no-op. */
    fun enqueue(task: BackgroundTask)

    fun findById(tenantId: Identifier, taskId: Identifier): BackgroundTask?

    fun findByBusiness(tenantId: Identifier, businessId: Identifier, limit: Int): List<BackgroundTask>
}

/** Worker persistence port. Every operation runs in a short local transaction. */
interface TaskProcessingRepository {
    fun claimAvailable(limit: Int, now: Long, leaseOwner: String, leaseExpiresAt: Long): List<BackgroundTaskLease>

    fun markSucceeded(lease: BackgroundTaskLease, completedAt: Long)

    fun markForRetry(lease: BackgroundTaskLease, nextAttemptAt: Long, message: String, updatedAt: Long)

    fun markFailed(lease: BackgroundTaskLease, message: String, updatedAt: Long)
}
