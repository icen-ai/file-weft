package ai.icen.fw.reliability.runtime

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

enum class ReliabilityOutboxRelayStatus { RELAYED, EMPTY, CONFLICT, OUTCOME_UNKNOWN, SIGNAL_FAILED }

class ReliabilityOutboxRelayResult private constructor(
    val status: ReliabilityOutboxRelayStatus,
    val record: ReliabilityOutboxRecord?,
) {
    companion object {
        @JvmStatic
        fun of(
            status: ReliabilityOutboxRelayStatus,
            record: ReliabilityOutboxRecord?,
        ): ReliabilityOutboxRelayResult = ReliabilityOutboxRelayResult(status, record)
    }
}

class ReliabilityOutboxRelay(
    private val repository: ReliabilityOutboxRepository,
    private val signal: ReliabilityWorkerSignalPort,
    private val clock: ReliabilityRuntimeClock,
) {
    fun relayOne(ownerId: String, leaseMillis: Long): CompletionStage<ReliabilityOutboxRelayResult> {
        ReliabilityRuntimeSupport.opaque(ownerId, "Reliability outbox relay owner is invalid.")
        require(leaseMillis in 1L..MAX_LEASE_MILLIS) { "Reliability outbox relay lease is invalid." }
        val now = clock.nowEpochMilli()
        val claim = try {
            repository.claimNext(ownerId, now, safeAdd(now, leaseMillis))
        } catch (_: RuntimeException) {
            return completedRelay(ReliabilityOutboxRelayResult.of(ReliabilityOutboxRelayStatus.OUTCOME_UNKNOWN, null))
        }
        if (claim.code != ReliabilityOutboxClaimCode.CLAIMED) {
            val status = when (claim.code) {
                ReliabilityOutboxClaimCode.EMPTY -> ReliabilityOutboxRelayStatus.EMPTY
                ReliabilityOutboxClaimCode.CONFLICT -> ReliabilityOutboxRelayStatus.CONFLICT
                else -> ReliabilityOutboxRelayStatus.OUTCOME_UNKNOWN
            }
            return completedRelay(ReliabilityOutboxRelayResult.of(status, claim.record))
        }
        val record = requireNotNull(claim.record)
        // claimNext transaction has returned before signaling any worker.
        val handled = try {
            signal.signal(record).handle { _, throwable -> throwable }
        } catch (_: RuntimeException) {
            return completedRelay(ReliabilityOutboxRelayResult.of(ReliabilityOutboxRelayStatus.SIGNAL_FAILED, record))
        }
        return handled.thenApply { throwable ->
            if (throwable != null) {
                ReliabilityOutboxRelayResult.of(ReliabilityOutboxRelayStatus.SIGNAL_FAILED, record)
            } else {
                val acknowledged = try {
                    repository.acknowledge(record.tenantId, record.outboxId, ownerId, claim.fencingToken)
                } catch (_: RuntimeException) {
                    ReliabilityStoreCode.OUTCOME_UNKNOWN
                }
                val status = when (acknowledged) {
                    ReliabilityStoreCode.STORED -> ReliabilityOutboxRelayStatus.RELAYED
                    ReliabilityStoreCode.CONFLICT -> ReliabilityOutboxRelayStatus.CONFLICT
                    else -> ReliabilityOutboxRelayStatus.OUTCOME_UNKNOWN
                }
                ReliabilityOutboxRelayResult.of(status, record)
            }
        }
    }

    companion object {
        const val MAX_LEASE_MILLIS: Long = 10L * 60L * 1000L
    }

    private fun safeAdd(value: Long, delta: Long): Long =
        if (Long.MAX_VALUE - value < delta) Long.MAX_VALUE else value + delta
}

private fun <T> completedRelay(value: T): CompletionStage<T> = CompletableFuture.completedFuture(value)
