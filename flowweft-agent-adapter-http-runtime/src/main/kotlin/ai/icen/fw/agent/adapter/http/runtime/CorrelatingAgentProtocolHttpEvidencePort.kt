package ai.icen.fw.agent.adapter.http.runtime

import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpEvidenceRecorder
import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpExchangeEvidence
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives one live runtime process access to the same evidence that the hardened transport durably
 * records. The durable delegate completes first; only then is the evidence made claimable here.
 * This correlation cache is intentionally not a recovery store: clustered or crash-recoverable
 * deployments must implement [AgentProtocolHttpRuntimeEvidenceSource] over their durable recorder.
 */
class CorrelatingAgentProtocolHttpEvidencePort(
    private val durableRecorder: AgentProtocolHttpEvidenceRecorder,
) : AgentProtocolHttpEvidenceRecorder, AgentProtocolHttpRuntimeEvidenceSource {
    private val pending = ConcurrentHashMap<String, AgentProtocolHttpRuntimeEvidence>()

    override fun record(evidence: AgentProtocolHttpExchangeEvidence): CompletionStage<Void> {
        val output = CompletableFuture<Void>()
        val durable = try {
            requireNotNull(durableRecorder.record(evidence))
        } catch (_: RuntimeException) {
            output.completeExceptionally(evidenceFailure("protocol.http.evidence-record-failed"))
            return output
        }
        durable.whenComplete { _, failure ->
            if (failure != null) {
                output.completeExceptionally(evidenceFailure("protocol.http.evidence-record-failed"))
                return@whenComplete
            }
            try {
                val runtimeEvidence = AgentProtocolHttpRuntimeEvidence(evidence)
                val key = runtimeEvidence.transportReceipt.dispatchBindingDigest
                val previous = pending.putIfAbsent(key, runtimeEvidence)
                if (previous != null && previous.evidenceDigest != runtimeEvidence.evidenceDigest) {
                    output.completeExceptionally(evidenceFailure("protocol.http.evidence-conflict"))
                } else {
                    output.complete(null)
                }
            } catch (_: RuntimeException) {
                output.completeExceptionally(evidenceFailure("protocol.http.evidence-invalid"))
            }
        }
        return output
    }

    override fun take(
        query: AgentProtocolHttpEvidenceQuery,
    ): CompletionStage<AgentProtocolHttpRuntimeEvidence?> {
        val evidence = pending[query.dispatchBindingDigest]
        if (evidence == null) return CompletableFuture.completedFuture(null)
        val receipt = evidence.transportReceipt
        if (receipt.dispatchRequestId != query.dispatchRequestId ||
            receipt.dispatchBindingDigest != query.dispatchBindingDigest
        ) {
            return CompletableFuture<AgentProtocolHttpRuntimeEvidence?>().also { future ->
                future.completeExceptionally(evidenceFailure("protocol.http.evidence-identity-mismatch"))
            }
        }
        return if (pending.remove(query.dispatchBindingDigest, evidence)) {
            CompletableFuture.completedFuture(evidence)
        } else {
            CompletableFuture.completedFuture(null)
        }
    }

    fun pendingEvidenceCount(): Int = pending.size

    private fun evidenceFailure(code: String): AgentProtocolHttpRuntimeException =
        AgentProtocolHttpRuntimeException(
            code,
            AgentProtocolHttpRuntimeFailurePhase.EVIDENCE,
            true,
        )
}
