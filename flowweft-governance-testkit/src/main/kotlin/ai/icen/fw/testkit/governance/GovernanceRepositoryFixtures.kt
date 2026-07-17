package ai.icen.fw.testkit.governance

import ai.icen.fw.governance.runtime.GovernanceClaimedOutboxRecord
import ai.icen.fw.governance.runtime.GovernanceDeletionRepository
import ai.icen.fw.governance.runtime.GovernanceDeletionRun
import ai.icen.fw.governance.runtime.GovernanceOutboxClaimRequest
import ai.icen.fw.governance.runtime.GovernanceOutboxRecord
import ai.icen.fw.governance.runtime.GovernanceOutboxRepository
import ai.icen.fw.governance.runtime.GovernanceStoreCode
import ai.icen.fw.governance.runtime.GovernanceStoreResult
import java.util.concurrent.atomic.AtomicInteger

/** Deletion and outbox repositories that belong to the same consumer persistence boundary. */
class GovernanceRepositoryBundle private constructor(
    val deletion: GovernanceDeletionRepository,
    val outbox: GovernanceOutboxRepository,
) {
    companion object {
        @JvmStatic
        fun of(
            deletion: GovernanceDeletionRepository,
            outbox: GovernanceOutboxRepository,
        ): GovernanceRepositoryBundle = GovernanceRepositoryBundle(deletion, outbox)

        @JvmStatic
        fun inMemory(): GovernanceRepositoryBundle {
            val repository = InMemoryGovernanceRepository.create()
            return GovernanceRepositoryBundle(repository, repository)
        }
    }
}

/** Strict tenant-scoped in-memory persistence used to self-test the public suites. */
class InMemoryGovernanceRepository private constructor() : GovernanceDeletionRepository, GovernanceOutboxRepository {
    private val monitor = Any()
    private val transactions = AtomicInteger()
    private val byPlan = linkedMapOf<PlanKey, GovernanceDeletionRun>()
    private val byIdempotency = linkedMapOf<IdempotencyKey, GovernanceDeletionRun>()
    private val outbox = linkedMapOf<OutboxKey, OutboxRow>()

    override fun findByIdempotency(tenantId: String, idempotencyKey: String): GovernanceDeletionRun? = tx {
        byIdempotency[IdempotencyKey(tenantId, idempotencyKey)]
    }

    override fun load(tenantId: String, planId: String): GovernanceDeletionRun? = tx {
        byPlan[PlanKey(tenantId, planId)]
    }

    override fun compareAndSet(
        tenantId: String,
        planId: String,
        expectedVersion: Long?,
        candidate: GovernanceDeletionRun,
        outbox: GovernanceOutboxRecord,
    ): GovernanceStoreResult = tx {
        if (candidate.tenantId != tenantId || candidate.planId != planId ||
            outbox.tenantId != tenantId || outbox.planId != planId ||
            outbox.runVersion != candidate.version || outbox.stateDigest != candidate.stateDigest) {
            return@tx GovernanceStoreResult.failed(GovernanceStoreCode.CONFLICT)
        }
        val planKey = PlanKey(tenantId, planId)
        val idempotencyKey = IdempotencyKey(tenantId, candidate.idempotencyKey)
        val current = byPlan[planKey]
        val existingIdempotency = byIdempotency[idempotencyKey]
        if (existingIdempotency != null && existingIdempotency.planId != planId) {
            return@tx GovernanceStoreResult.failed(GovernanceStoreCode.CONFLICT)
        }
        if (current != null && current.stateDigest == candidate.stateDigest) {
            return@tx GovernanceStoreResult.replayed(current)
        }
        val versionMatches = if (expectedVersion == null) {
            current == null && candidate.version == 1L
        } else {
            current?.version == expectedVersion && candidate.version == expectedVersion + 1L
        }
        if (!versionMatches) return@tx GovernanceStoreResult.failed(GovernanceStoreCode.CONFLICT)

        val outboxKey = OutboxKey(tenantId, outbox.recordId)
        val currentOutbox = this.outbox[outboxKey]
        if (currentOutbox != null && currentOutbox.record.recordDigest != outbox.recordDigest) {
            return@tx GovernanceStoreResult.failed(GovernanceStoreCode.CONFLICT)
        }
        byPlan[planKey] = candidate
        byIdempotency[idempotencyKey] = candidate
        this.outbox.putIfAbsent(outboxKey, OutboxRow(outbox))
        GovernanceStoreResult.stored(candidate)
    }

    override fun claimReady(request: GovernanceOutboxClaimRequest): List<GovernanceClaimedOutboxRecord> = tx {
        outbox.values.asSequence()
            .filter { row -> row.record.tenantId == request.tenantId && !row.acknowledged &&
                row.record.createdAtEpochMilli <= request.nowEpochMilli &&
                (row.claim == null || requireNotNull(row.claim).leaseExpiresAtEpochMilli <= request.nowEpochMilli)
            }
            .sortedWith(compareBy<OutboxRow> { it.record.createdAtEpochMilli }.thenBy { it.record.recordId })
            .take(request.maximumRecords)
            .map { row ->
                row.fencingToken = Math.addExact(row.fencingToken, 1L)
                GovernanceClaimedOutboxRecord.of(
                    row.record,
                    request.claimId,
                    request.workerId,
                    row.fencingToken,
                    request.leaseExpiresAtEpochMilli,
                ).also { claim -> row.claim = claim }
            }
            .toList()
    }

    override fun acknowledge(
        claim: GovernanceClaimedOutboxRecord,
        acknowledgedAtEpochMilli: Long,
    ): Boolean = tx {
        val row = outbox[OutboxKey(claim.record.tenantId, claim.record.recordId)] ?: return@tx false
        val current = row.claim ?: return@tx false
        val exact = current.claimId == claim.claimId && current.workerId == claim.workerId &&
            current.fencingToken == claim.fencingToken &&
            current.record.recordDigest == claim.record.recordDigest
        if (!exact || acknowledgedAtEpochMilli < row.record.createdAtEpochMilli ||
            acknowledgedAtEpochMilli >= current.leaseExpiresAtEpochMilli) {
            return@tx false
        }
        row.acknowledged = true
        true
    }

    fun transactionActive(): Boolean = transactions.get() > 0

    fun runCount(): Int = tx { byPlan.size }

    fun outboxCount(tenantId: String): Int = tx {
        outbox.values.count { it.record.tenantId == tenantId }
    }

    private fun <T> tx(block: () -> T): T = synchronized(monitor) {
        check(transactions.incrementAndGet() == 1) { "Governance in-memory transaction unexpectedly nested." }
        try {
            block()
        } finally {
            transactions.decrementAndGet()
        }
    }

    private data class PlanKey(val tenantId: String, val planId: String)
    private data class IdempotencyKey(val tenantId: String, val idempotencyKey: String)
    private data class OutboxKey(val tenantId: String, val recordId: String)

    private class OutboxRow(val record: GovernanceOutboxRecord) {
        var claim: GovernanceClaimedOutboxRecord? = null
        var fencingToken: Long = 0L
        var acknowledged: Boolean = false
    }

    companion object {
        @JvmStatic
        fun create(): InMemoryGovernanceRepository = InMemoryGovernanceRepository()
    }
}
