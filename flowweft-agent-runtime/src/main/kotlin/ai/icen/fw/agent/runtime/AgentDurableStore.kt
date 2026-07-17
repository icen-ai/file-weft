package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentRunEvent
import ai.icen.fw.agent.api.AgentRunStatus
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier

class AgentRunKey(
    tenantId: Identifier,
    runId: Identifier,
) {
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Agent run key tenant identifier is invalid.")
    val runId: Identifier = requireRuntimeIdentifier(runId, "Agent run key run identifier is invalid.")

    override fun equals(other: Any?): Boolean = this === other ||
        other is AgentRunKey && tenantId == other.tenantId && runId == other.runId
    override fun hashCode(): Int = 31 * tenantId.hashCode() + runId.hashCode()
    override fun toString(): String = "AgentRunKey(<redacted>)"
}

class AgentRunCreateCommit(
    val state: AgentDurableRunState,
    val initialEvent: AgentRunEvent,
) {
    val idempotencyScope: AgentRunIdempotencyScope = state.idempotencyScope

    init {
        require(state.stateVersion == 0L && state.status == AgentRunStatus.QUEUED && state.eventSequence == 1L) {
            "Agent run create commit requires an initial QUEUED state at version zero."
        }
        require(initialEvent.runId == state.runId && initialEvent.tenantId == state.tenantId &&
            initialEvent.sequence == 1L
        ) { "Agent run create event does not match its initial state." }
    }
}

enum class AgentRunCreateStatus {
    CREATED,
    REPLAYED,
}

class AgentRunCreateResult(
    val status: AgentRunCreateStatus,
    val state: AgentDurableRunState,
) {
    init {
        require(status != AgentRunCreateStatus.CREATED || state.stateVersion == 0L) {
            "A newly created Agent run must remain at version zero."
        }
    }
}

class AgentRunLeaseClaim(
    val key: AgentRunKey,
    val ownerId: ProviderId,
    leaseId: Identifier,
    val requestedAt: Long,
    val leaseDurationMillis: Long,
) {
    val leaseId: Identifier = requireRuntimeIdentifier(leaseId, "Agent lease claim identifier is invalid.")

    init {
        require(requestedAt >= 0L) { "Agent lease claim time must not be negative." }
        require(leaseDurationMillis in 1L..300_000L) { "Agent lease duration is invalid." }
        require(requestedAt <= Long.MAX_VALUE - leaseDurationMillis) { "Agent lease lifetime overflows time." }
    }
}

enum class AgentRunLeaseClaimStatus {
    ACQUIRED,
    BUSY,
    MISSING,
    TERMINAL,
}

class AgentRunLeaseClaimResult(
    val status: AgentRunLeaseClaimStatus,
    val state: AgentDurableRunState?,
) {
    init {
        require((status == AgentRunLeaseClaimStatus.MISSING) == (state == null)) {
            "Agent lease claim result state does not match its status."
        }
        require(status != AgentRunLeaseClaimStatus.ACQUIRED || state?.lease != null) {
            "An acquired Agent lease claim requires a durable lease."
        }
    }
}

enum class AgentStoreCommitAuthority {
    WORKER,
    TRUSTED_COMMAND,
}

/**
 * One atomic store mutation. The store must commit the CAS state, ordered events, cumulative usage
 * and pending operation together. This command deliberately has no callback, preventing provider
 * or tool calls from running inside a storage transaction.
 */
class AgentStoreCommit(
    val key: AgentRunKey,
    val expectedStateVersion: Long,
    val expectedEventSequence: Long,
    val authority: AgentStoreCommitAuthority,
    val expectedLease: AgentRunLease?,
    val committedAt: Long,
    val nextState: AgentDurableRunState,
    events: Collection<AgentRunEvent>,
) {
    val events: List<AgentRunEvent> = runtimeImmutableList(events, "Agent commit events exceed the limit.")
    val cumulativeUsage: AgentUsage = nextState.usage
    val pendingOperation: AgentPendingOperation? = nextState.pendingOperation

    init {
        require(expectedStateVersion >= 0L && expectedEventSequence >= 0L &&
            nextState.stateVersion == expectedStateVersion + 1L
        ) {
            "Agent store commit has an invalid CAS version transition."
        }
        require(key.tenantId == nextState.tenantId && key.runId == nextState.runId) {
            "Agent store commit key does not match its state."
        }
        require((authority == AgentStoreCommitAuthority.WORKER) == (expectedLease != null)) {
            "Worker commits require a lease; trusted commands must use CAS without a worker lease."
        }
        require(committedAt >= 0L && (expectedLease == null || expectedLease.isCurrent(committedAt))) {
            "Agent store commit time is outside its worker lease."
        }
        require(this.events.isNotEmpty()) { "Agent business commits require at least one ordered event." }
        require(nextState.eventSequence == expectedEventSequence + this.events.size) {
            "Agent store commit next event sequence does not match its ordered event batch."
        }
        val firstSequence = expectedEventSequence + 1L
        require(firstSequence > 0L) { "Agent store commit event range is invalid." }
        this.events.forEachIndexed { index, event ->
            require(event.runId == key.runId && event.tenantId == key.tenantId &&
                event.sequence == firstSequence + index
            ) { "Agent store commit events are not contiguous or do not match the run." }
        }
        require(
            authority != AgentStoreCommitAuthority.WORKER ||
                nextState.lease?.matches(expectedLease!!) == true ||
                nextState.lease == null &&
                (nextState.status.isTerminal() || nextState.status == AgentRunStatus.WAITING_APPROVAL ||
                    nextState.status == AgentRunStatus.WAITING_TOOL),
        ) { "Agent worker commit state neither retains its exact fencing lease nor enters a releasable state." }
    }
}

enum class AgentStoreCommitStatus {
    APPLIED,
    VERSION_CONFLICT,
    LEASE_LOST,
    MISSING,
}

class AgentStoreCommitResult(
    val status: AgentStoreCommitStatus,
    val state: AgentDurableRunState?,
) {
    init {
        require((status == AgentStoreCommitStatus.MISSING) == (state == null)) {
            "Agent store commit result state does not match its status."
        }
    }
}

/**
 * Durable storage port. Implementations provide atomic create/idempotency, fencing leases and CAS
 * commits. Every lookup is tenant-scoped by its key/scope; an implementation must never return a
 * state from a different tenant or idempotency owner. A lease request older than the persisted
 * `updatedAt` must fail closed instead of moving durable time backwards, and every successful claim
 * increments a monotonic fencing token.
 *
 * Provider/application callbacks are forbidden while a database transaction is open. If `create`
 * or `commit` throws after JDBC reports an uncertain outcome, the adapter must not translate that
 * exception into APPLIED or invite a blind retry: callers reload the exact state/event/operation
 * evidence and reconcile any already-claimed external operation.
 */
interface AgentDurableRunStore {
    fun create(commit: AgentRunCreateCommit): AgentRunCreateResult

    fun load(key: AgentRunKey): AgentDurableRunState?

    fun findByIdempotency(scope: AgentRunIdempotencyScope): AgentDurableRunState?

    fun claimLease(claim: AgentRunLeaseClaim): AgentRunLeaseClaimResult

    fun commit(commit: AgentStoreCommit): AgentStoreCommitResult

    fun recoverable(atTime: Long, limit: Int): List<AgentDurableRunState>

    fun events(key: AgentRunKey, afterSequence: Long, limit: Int): List<AgentRunEvent>
}
