package com.fileweft.application.outbox

import com.fileweft.core.id.Identifier

/** Persisted processing states understood by the Outbox worker. */
enum class OutboxEventStatus {
    PENDING,
    RETRY,
    RUNNING,
    SUCCESS,
    FAILED,
}

/**
 * A row-locked snapshot of one Outbox event's processing ownership.
 *
 * Payload, error text and retry metadata are intentionally absent: mutation
 * fencing needs only event identity/type, state and the opaque lease capability.
 */
class OutboxEventState @JvmOverloads constructor(
    val id: Identifier,
    val tenantId: Identifier,
    val status: OutboxEventStatus,
    val leaseOwner: String?,
    val leaseToken: String?,
    val eventType: String? = null,
) {
    init {
        require(eventType == null || eventType.isNotBlank()) {
            "Outbox event type must not be blank."
        }
        require((leaseOwner == null) == (leaseToken == null)) {
            "Outbox event lease owner and token must either both be present or both be absent."
        }
        require(leaseOwner == null || leaseOwner.isNotBlank()) {
            "Outbox event lease owner must not be blank."
        }
        require(leaseToken == null || leaseToken.isNotBlank()) {
            "Outbox event lease token must not be blank."
        }
        require(status == OutboxEventStatus.RUNNING || leaseOwner == null) {
            "Only a running Outbox event may retain lease ownership."
        }
    }

    /**
     * Verifies that this locked row is still RUNNING under exactly [lease].
     * A legacy tokenless lease always fails closed.
     */
    fun requireCurrentLease(lease: OutboxEventLease): OutboxEventState {
        if (
            status != OutboxEventStatus.RUNNING ||
            lease.leaseOwner == null ||
            lease.leaseToken == null ||
            id != lease.event.id ||
            tenantId != lease.event.tenantId ||
            (eventType != null && eventType != lease.event.type) ||
            leaseOwner != lease.leaseOwner ||
            leaseToken != lease.leaseToken
        ) {
            throw OutboxLeaseLostException(
                "Outbox event is no longer running under the supplied lease in the current tenant.",
            )
        }
        return this
    }
}

/**
 * Strong, additive persistence port for business projections that must lock
 * and fence against an Outbox event. The returned row lock must remain held
 * until the caller's current local transaction completes.
 */
interface OutboxEventMutationRepository {
    fun findForMutation(tenantId: Identifier, eventId: Identifier): OutboxEventState?
}
