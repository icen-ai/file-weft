package com.fileweft.application.outbox

/**
 * Read port for an operational snapshot of the durable outbox.
 *
 * Implementations must classify rows into mutually exclusive groups:
 *
 * - ready: PENDING or RETRY events due at [now]
 * - delayed: PENDING or RETRY events due after [now]
 * - running: RUNNING events that are not reclaimable yet
 * - expired: RUNNING events reclaimable by their token lease or the legacy
 *   no-token cutoff
 * - failed: terminal FAILED events
 *
 * This port intentionally receives the same legacy running cutoff used by a
 * worker claim, so an operator sees the work that is recoverable now rather
 * than an undifferentiated RUNNING total.
 */
interface OutboxBacklogReader {
    fun snapshot(now: Long, legacyRunningBefore: Long): OutboxBacklogSnapshot
}

/**
 * Immutable, Java-friendly aggregate returned by [OutboxBacklogReader].
 *
 * [oldestReadyCreatedTime] is the minimum creation timestamp among ready
 * events. It is null exactly when there are no ready events. The publisher
 * derives an age from the supplied sampling time and clamps clock-skewed
 * negative ages to zero.
 */
class OutboxBacklogSnapshot @JvmOverloads constructor(
    val readyCount: Long,
    val delayedCount: Long,
    val runningCount: Long,
    val expiredCount: Long,
    val failedCount: Long,
    val oldestReadyCreatedTime: Long? = null,
) {
    init {
        require(readyCount >= 0) { "Ready outbox backlog count must not be negative." }
        require(delayedCount >= 0) { "Delayed outbox backlog count must not be negative." }
        require(runningCount >= 0) { "Running outbox backlog count must not be negative." }
        require(expiredCount >= 0) { "Expired outbox backlog count must not be negative." }
        require(failedCount >= 0) { "Failed outbox backlog count must not be negative." }
        require(oldestReadyCreatedTime == null || oldestReadyCreatedTime >= 0) {
            "Oldest ready outbox creation time must not be negative."
        }
        require((readyCount == 0L) == (oldestReadyCreatedTime == null)) {
            "Oldest ready outbox creation time must be present exactly when ready work exists."
        }
    }

    fun oldestReadyAgeSeconds(now: Long): Double {
        require(now >= 0) { "Outbox backlog observation time must not be negative." }
        val createdTime = oldestReadyCreatedTime ?: return 0.0
        return (now - createdTime).coerceAtLeast(0).toDouble() / MILLIS_PER_SECOND
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
