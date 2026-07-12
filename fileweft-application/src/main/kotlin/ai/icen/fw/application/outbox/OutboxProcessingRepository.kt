package ai.icen.fw.application.outbox

import ai.icen.fw.core.event.OutboxEvent

/**
 * Immutable ownership data for one outbox claim. A token is intentionally
 * distinct from the worker id: the same worker may reclaim an event after an
 * earlier lease has expired, and only the newest claim may acknowledge it.
 */
class OutboxLeaseClaim @JvmOverloads constructor(
    val leaseOwner: String,
    val leaseToken: String,
    val leaseExpiresAt: Long,
    val legacyRunningBefore: Long = 0,
) {
    init {
        require(leaseOwner.isNotBlank()) { "Outbox lease owner must not be blank." }
        require(leaseToken.isNotBlank()) { "Outbox lease token must not be blank." }
        require(leaseExpiresAt >= 0) { "Outbox lease expiry time must not be negative." }
        require(legacyRunningBefore >= 0) { "Outbox legacy running cutoff must not be negative." }
    }
}

/**
 * A leased event is marked RUNNING before its external handler is invoked.
 *
 * The two-argument constructor remains available for custom repositories
 * compiled against older FileWeft versions. Such a legacy lease has no token
 * and therefore cannot receive the stronger stale-worker acknowledgement
 * protection provided by [LeasedOutboxProcessingRepository].
 */
class OutboxEventLease @JvmOverloads constructor(
    val event: OutboxEvent,
    val retryCount: Int,
    val leaseOwner: String? = null,
    val leaseToken: String? = null,
) {
    init {
        require(retryCount >= 0) { "Outbox event retry count must not be negative." }
        require((leaseOwner == null) == (leaseToken == null)) {
            "Outbox lease owner and token must either both be present or both be absent."
        }
        require(leaseOwner == null || leaseOwner.isNotBlank()) { "Outbox lease owner must not be blank." }
        require(leaseToken == null || leaseToken.isNotBlank()) { "Outbox lease token must not be blank." }
    }
}

/**
 * Persistence port for the outbox worker. Every method is expected to execute
 * inside a short [ai.icen.fw.application.transaction.ApplicationTransaction].
 */
interface OutboxProcessingRepository {
    fun claimAvailable(limit: Int, now: Long): List<OutboxEventLease>

    fun markSucceeded(lease: OutboxEventLease, completedAt: Long)

    fun markForRetry(lease: OutboxEventLease, nextAttemptAt: Long, message: String, updatedAt: Long)

    fun markFailed(lease: OutboxEventLease, message: String, updatedAt: Long)
}

/**
 * Optional stronger outbox port for repositories that persist ownership
 * leases. Existing custom [OutboxProcessingRepository] implementations remain
 * source and binary compatible; the worker detects this interface at runtime.
 */
interface LeasedOutboxProcessingRepository : OutboxProcessingRepository {
    fun claimAvailable(limit: Int, now: Long, claim: OutboxLeaseClaim): List<OutboxEventLease>
}

/**
 * Raised when an acknowledgement loses its lease to a newer claim. Workers
 * treat this as an abandoned local outcome: no terminal callback is invoked
 * and later events in the same polling round continue normally.
 */
class OutboxLeaseLostException(message: String) : IllegalStateException(message)
