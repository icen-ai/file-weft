package ai.icen.fw.application.task

import ai.icen.fw.core.id.Identifier

/** Application port for enqueuing and inspecting durable background work. */
interface TaskRepository {
    /**
     * Duplicate tenant/idempotency pairs are intentionally a no-op. On
     * return, an inserted task must be readable through [findById] in the
     * caller's current application transaction so a command can verify the
     * durable identity it is about to expose in an idempotent receipt.
     */
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

/**
 * Immutable ownership data for one task claim. A token is intentionally
 * distinct from the worker id: one worker can reclaim a task after its own
 * earlier lease expired, and only the latest claim may acknowledge it.
 */
class TaskLeaseClaim @JvmOverloads constructor(
    val leaseOwner: String,
    val leaseToken: String,
    val leaseExpiresAt: Long,
    val legacyRunningBefore: Long = 0,
) {
    init {
        require(leaseOwner.isNotBlank()) { "Task lease owner must not be blank." }
        require(leaseToken.isNotBlank()) { "Task lease token must not be blank." }
        require(leaseExpiresAt >= 0) { "Task lease expiry time must not be negative." }
        require(legacyRunningBefore >= 0) { "Task legacy running cutoff must not be negative." }
    }
}

/**
 * Optional stronger task port for repositories that persist lease tokens.
 * Existing [TaskProcessingRepository] implementations remain source and
 * binary compatible; [TaskWorker] detects this interface at runtime.
 */
interface LeasedTaskProcessingRepository : TaskProcessingRepository {
    fun claimAvailable(limit: Int, now: Long, claim: TaskLeaseClaim): List<BackgroundTaskLease>
}

/**
 * Raised when a task acknowledgement loses its lease to a newer claim.
 * Workers treat this as an abandoned local outcome and continue polling.
 */
class TaskLeaseLostException(message: String) : IllegalStateException(message)
