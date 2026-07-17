package ai.icen.fw.testkit.reliability

import ai.icen.fw.reliability.runtime.ReliabilityOutboxRecord
import ai.icen.fw.reliability.runtime.ReliabilityRun
import ai.icen.fw.reliability.runtime.ReliabilityRunRepository
import ai.icen.fw.reliability.runtime.ReliabilitySloSchedule
import ai.icen.fw.reliability.runtime.ReliabilitySloScheduleRepository
import ai.icen.fw.reliability.runtime.ReliabilityStoreCode
import ai.icen.fw.reliability.runtime.ReliabilityStoreResult
import java.util.concurrent.atomic.AtomicLong

/** Strict process-local repository double. It is test infrastructure, never a production adapter. */
class InMemoryReliabilityRunRepository private constructor() : ReliabilityRunRepository {
    private val runs = LinkedHashMap<String, ReliabilityRun>()
    private val idempotency = LinkedHashMap<String, String>()
    private val outboxRecords = ArrayList<ReliabilityOutboxRecord>()
    private val fencingSequence = AtomicLong()
    private val transaction = ThreadLocal<Boolean>()

    override fun createOrLoad(run: ReliabilityRun, outbox: ReliabilityOutboxRecord): ReliabilityStoreResult = tx {
        require(outbox.tenantId == run.tenantId && outbox.aggregateId == run.runId &&
            outbox.aggregateVersion == run.version && outbox.aggregateStateDigest == run.stateDigest
        ) { "Reliability fixture create outbox is not bound to the exact run." }
        val idempotencyKey = idempotencyKey(run.tenantId, run.intent.idempotencyDigest)
        val replayId = idempotency[idempotencyKey]
        if (replayId != null) {
            return@tx ReliabilityStoreResult.of(ReliabilityStoreCode.REPLAY, runs[runKey(run.tenantId, replayId)])
        }
        val key = runKey(run.tenantId, run.runId)
        if (runs.containsKey(key)) return@tx ReliabilityStoreResult.of(ReliabilityStoreCode.CONFLICT, runs[key])
        runs[key] = run
        idempotency[idempotencyKey] = run.runId
        outboxRecords.add(outbox)
        ReliabilityStoreResult.of(ReliabilityStoreCode.STORED, run)
    }

    override fun load(tenantId: String, runId: String): ReliabilityRun? = tx { runs[runKey(tenantId, runId)] }

    override fun findByIdempotency(tenantId: String, idempotencyDigest: String): ReliabilityRun? = tx {
        idempotency[idempotencyKey(tenantId, idempotencyDigest)]?.let { runId -> runs[runKey(tenantId, runId)] }
    }

    override fun claim(
        tenantId: String,
        runId: String,
        expectedVersion: Long,
        ownerId: String,
        nowEpochMilli: Long,
        leaseUntilEpochMilli: Long,
    ): ReliabilityStoreResult = tx {
        val key = runKey(tenantId, runId)
        val current = runs[key]
            ?: return@tx ReliabilityStoreResult.of(ReliabilityStoreCode.NOT_FOUND, null)
        if (current.version != expectedVersion || current.isTerminal() ||
            current.lease?.let { lease -> lease.isCurrent(lease.ownerId, nowEpochMilli) && lease.ownerId != ownerId } == true
        ) {
            return@tx ReliabilityStoreResult.of(ReliabilityStoreCode.CONFLICT, current)
        }
        val candidate = ReliabilityRun.claimed(
            current,
            ownerId,
            nowEpochMilli,
            leaseUntilEpochMilli,
            fencingSequence.incrementAndGet(),
        )
        runs[key] = candidate
        ReliabilityStoreResult.of(ReliabilityStoreCode.STORED, candidate)
    }

    override fun compareAndSet(
        tenantId: String,
        runId: String,
        expectedVersion: Long,
        expectedFencingToken: Long,
        candidate: ReliabilityRun,
        outbox: ReliabilityOutboxRecord,
    ): ReliabilityStoreResult = tx {
        val key = runKey(tenantId, runId)
        val current = runs[key]
            ?: return@tx ReliabilityStoreResult.of(ReliabilityStoreCode.NOT_FOUND, null)
        val exact = current.version == expectedVersion &&
            current.lease?.fencingToken == expectedFencingToken &&
            candidate.tenantId == tenantId && candidate.runId == runId &&
            candidate.version == expectedVersion + 1L &&
            candidate.lease?.fencingToken == expectedFencingToken &&
            outbox.tenantId == tenantId && outbox.aggregateId == runId &&
            outbox.aggregateVersion == candidate.version && outbox.aggregateStateDigest == candidate.stateDigest
        if (!exact) return@tx ReliabilityStoreResult.of(ReliabilityStoreCode.CONFLICT, current)
        runs[key] = candidate
        outboxRecords.add(outbox)
        ReliabilityStoreResult.of(ReliabilityStoreCode.STORED, candidate)
    }

    fun isTransactionActive(): Boolean = transaction.get() == true

    @Synchronized
    fun outboxes(): List<ReliabilityOutboxRecord> = ArrayList(outboxRecords)

    @Synchronized
    private fun <T> tx(block: () -> T): T {
        check(transaction.get() != true) { "Reliability fixture repository does not allow nested transactions." }
        transaction.set(true)
        return try {
            block()
        } finally {
            transaction.remove()
        }
    }

    private fun runKey(tenantId: String, runId: String): String = "$tenantId\u0000$runId"
    private fun idempotencyKey(tenantId: String, digest: String): String = "$tenantId\u0000$digest"

    companion object {
        @JvmStatic fun create(): InMemoryReliabilityRunRepository = InMemoryReliabilityRunRepository()
    }
}

/** Strict process-local SLO schedule repository double used by the fail-closed suite. */
class InMemoryReliabilitySloRepository private constructor(initial: ReliabilitySloSchedule) :
    ReliabilitySloScheduleRepository {
    private var schedule: ReliabilitySloSchedule = initial
    private val outboxRecords = ArrayList<ReliabilityOutboxRecord>()
    private val fencingSequence = AtomicLong()
    private val transaction = ThreadLocal<Boolean>()

    override fun load(tenantId: String, scheduleId: String): ReliabilitySloSchedule? = tx {
        schedule.takeIf { it.tenantId == tenantId && it.scheduleId == scheduleId }
    }

    override fun claimDue(
        tenantId: String,
        scheduleId: String,
        expectedVersion: Long,
        ownerId: String,
        nowEpochMilli: Long,
        leaseUntilEpochMilli: Long,
    ): ReliabilitySloSchedule? = tx {
        if (schedule.tenantId != tenantId || schedule.scheduleId != scheduleId ||
            schedule.version != expectedVersion || schedule.nextEvaluationAtEpochMilli > nowEpochMilli ||
            schedule.lease?.let { lease ->
                lease.isCurrent(lease.ownerId, nowEpochMilli) && lease.ownerId != ownerId
            } == true
        ) return@tx null
        ReliabilitySloSchedule.claimed(
            schedule,
            ownerId,
            nowEpochMilli,
            leaseUntilEpochMilli,
            fencingSequence.incrementAndGet(),
        ).also { schedule = it }
    }

    override fun compareAndSet(
        tenantId: String,
        scheduleId: String,
        expectedVersion: Long,
        expectedFencingToken: Long,
        candidate: ReliabilitySloSchedule,
        outbox: ReliabilityOutboxRecord,
    ): ReliabilityStoreCode = tx {
        val exact = schedule.tenantId == tenantId && schedule.scheduleId == scheduleId &&
            schedule.version == expectedVersion && schedule.lease?.fencingToken == expectedFencingToken &&
            candidate.tenantId == tenantId && candidate.scheduleId == scheduleId &&
            candidate.version == expectedVersion + 1L && candidate.lease?.fencingToken == expectedFencingToken &&
            outbox.tenantId == tenantId && outbox.aggregateId == scheduleId &&
            outbox.aggregateVersion == candidate.version && outbox.aggregateStateDigest == candidate.stateDigest
        if (!exact) return@tx ReliabilityStoreCode.CONFLICT
        schedule = candidate
        outboxRecords.add(outbox)
        ReliabilityStoreCode.STORED
    }

    fun isTransactionActive(): Boolean = transaction.get() == true

    @Synchronized
    fun outboxes(): List<ReliabilityOutboxRecord> = ArrayList(outboxRecords)

    @Synchronized
    private fun <T> tx(block: () -> T): T {
        check(transaction.get() != true) { "Reliability SLO fixture does not allow nested transactions." }
        transaction.set(true)
        return try {
            block()
        } finally {
            transaction.remove()
        }
    }

    companion object {
        @JvmStatic
        fun containing(initial: ReliabilitySloSchedule): InMemoryReliabilitySloRepository =
            InMemoryReliabilitySloRepository(initial)
    }
}
