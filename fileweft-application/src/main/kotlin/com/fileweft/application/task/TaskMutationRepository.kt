package com.fileweft.application.task

import com.fileweft.core.id.Identifier

/**
 * Row-locked task state used to fence a handler-owned local projection.
 *
 * Payload, retry errors and lease expiry are deliberately absent. Projection
 * ownership depends only on exact task identity, state and the opaque current
 * lease capability.
 */
class TaskState @JvmOverloads constructor(
    val id: Identifier,
    val tenantId: Identifier,
    val type: String,
    val status: BackgroundTaskStatus,
    val businessId: Identifier? = null,
    val leaseOwner: String? = null,
    val leaseToken: String? = null,
) {
    init {
        require(type.isNotBlank()) { "Task state type must not be blank." }
        require(leaseOwner == null || leaseOwner.isNotBlank()) { "Task state lease owner must not be blank." }
        require(leaseToken == null || leaseToken.isNotBlank()) { "Task state lease token must not be blank." }
        require(leaseToken == null || leaseOwner != null) {
            "A task state lease token requires a lease owner."
        }
        require(status == BackgroundTaskStatus.RUNNING || (leaseOwner == null && leaseToken == null)) {
            "Only a running task may retain lease ownership."
        }
    }

    /**
     * Verifies exact task identity and current RUNNING owner/token. A legacy
     * tokenless claim always fails closed for business projection writes.
     */
    fun requireCurrentLease(lease: BackgroundTaskLease): TaskState {
        if (
            status != BackgroundTaskStatus.RUNNING ||
            lease.leaseToken == null ||
            !matchesIdentity(lease) ||
            leaseOwner != lease.leaseOwner ||
            leaseToken != lease.leaseToken
        ) {
            throw TaskLeaseLostException(
                "Task is no longer running under the supplied lease in the current tenant.",
            )
        }
        return this
    }

    /**
     * Terminal callbacks no longer own a token after acknowledgement. They
     * may project a failure only when this is the exact task changed to FAILED
     * from the supplied lease.
     */
    fun matchesFailedTask(lease: BackgroundTaskLease): Boolean =
        status == BackgroundTaskStatus.FAILED &&
            lease.leaseToken != null &&
            leaseOwner == null &&
            leaseToken == null &&
            matchesIdentity(lease)

    private fun matchesIdentity(lease: BackgroundTaskLease): Boolean =
        id == lease.task.id &&
            tenantId == lease.task.tenantId &&
            type == lease.task.type &&
            businessId == lease.task.businessId
}

/**
 * Additive persistence port for task projections. The returned row lock must
 * remain held until the caller's local transaction completes.
 */
interface TaskMutationRepository {
    fun findForMutation(tenantId: Identifier, taskId: Identifier): TaskState?
}
