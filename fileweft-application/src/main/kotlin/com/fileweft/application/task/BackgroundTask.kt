package com.fileweft.application.task

import com.fileweft.core.id.Identifier
import com.fileweft.spi.task.TaskExecution
import java.util.Collections
import java.util.LinkedHashMap

enum class BackgroundTaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    RETRY,
}

/**
 * Persistence model for an independently recoverable background operation.
 * The idempotency key belongs to the business request, not an individual
 * worker lease, so duplicate enqueue attempts collapse safely.
 */
class BackgroundTask @JvmOverloads constructor(
    val id: Identifier,
    val tenantId: Identifier,
    val type: String,
    val idempotencyKey: String,
    val businessId: Identifier? = null,
    payload: Map<String, String> = emptyMap(),
    val status: BackgroundTaskStatus = BackgroundTaskStatus.PENDING,
    val retryCount: Int = 0,
    val nextAttemptTime: Long = 0,
    val lastError: String? = null,
) {
    val payload: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(payload))

    init {
        require(type.isNotBlank()) { "Task type must not be blank." }
        require(idempotencyKey.isNotBlank()) { "Task idempotency key must not be blank." }
        require(retryCount >= 0) { "Task retry count must not be negative." }
        require(nextAttemptTime >= 0) { "Task next attempt time must not be negative." }
        require(lastError == null || lastError.isNotBlank()) { "Task error message must not be blank when provided." }
        require(payload.keys.none { it.isBlank() } && payload.values.none { it.isBlank() }) {
            "Task payload keys and values must not be blank."
        }
    }

    fun execution(): TaskExecution = TaskExecution(id, tenantId, type, businessId, payload, retryCount)
}

/** A worker-owned lease; only the matching lease owner may change its state. */
class BackgroundTaskLease(
    val task: BackgroundTask,
    val leaseOwner: String,
) {
    init {
        require(leaseOwner.isNotBlank()) { "Task lease owner must not be blank." }
        require(task.status == BackgroundTaskStatus.RUNNING) { "Only running tasks can be leased." }
    }
}
