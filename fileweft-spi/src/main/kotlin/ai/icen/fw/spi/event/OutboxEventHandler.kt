package ai.icen.fw.spi.event

import ai.icen.fw.core.event.OutboxEvent

enum class OutboxHandlingStatus {
    SUCCEEDED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
}

class OutboxHandlingResult @JvmOverloads constructor(
    val status: OutboxHandlingStatus,
    val message: String? = null,
) {
    init {
        require(message == null || message.isNotBlank()) {
            "Outbox handling message must not be blank when provided."
        }
    }
}

/**
 * Adapter extension point for external side effects driven by a committed
 * outbox event. Implementations must be idempotent for the event identifier.
 */
interface OutboxEventHandler {
    fun supports(event: OutboxEvent): Boolean

    fun handle(event: OutboxEvent): OutboxHandlingResult

    /**
     * Invoked after the worker has permanently failed this event, including
     * retry exhaustion. Implementations should only persist local state and
     * must not start a new external side effect from this callback.
     */
    fun onExhausted(event: OutboxEvent, message: String) = Unit
}
