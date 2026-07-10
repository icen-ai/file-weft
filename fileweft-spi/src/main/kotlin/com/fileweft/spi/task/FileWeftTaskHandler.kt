package com.fileweft.spi.task

import com.fileweft.core.id.Identifier
import java.util.Collections
import java.util.LinkedHashMap

/** A durable, tenant-scoped unit of background work supplied to one handler. */
class TaskExecution @JvmOverloads constructor(
    val id: Identifier,
    val tenantId: Identifier,
    val type: String,
    val businessId: Identifier? = null,
    payload: Map<String, String> = emptyMap(),
    val retryCount: Int = 0,
) {
    val payload: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(payload))

    init {
        require(type.isNotBlank()) { "Task type must not be blank." }
        require(retryCount >= 0) { "Task retry count must not be negative." }
        require(payload.keys.none { it.isBlank() } && payload.values.none { it.isBlank() }) {
            "Task payload keys and values must not be blank."
        }
    }
}

enum class TaskHandlingStatus {
    SUCCEEDED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
}

class TaskHandlingResult @JvmOverloads constructor(
    val status: TaskHandlingStatus,
    val message: String? = null,
) {
    init {
        require(message == null || message.isNotBlank()) { "Task handling message must not be blank when provided." }
    }
}

/**
 * SPI for durable background work. Implementations must be idempotent for the
 * task id because a worker may resume a task after a lease expires.
 */
interface FileWeftTaskHandler {
    fun supports(task: TaskExecution): Boolean

    fun handle(task: TaskExecution): TaskHandlingResult

    /**
     * Invoked after permanent failure or retry exhaustion. This callback must
     * persist local state only and must not invoke a remote side effect.
     */
    fun onExhausted(task: TaskExecution, message: String) = Unit
}
