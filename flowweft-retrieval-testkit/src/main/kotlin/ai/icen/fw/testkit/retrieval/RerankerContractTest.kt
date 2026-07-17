package ai.icen.fw.testkit.retrieval

import ai.icen.fw.retrieval.api.RetrievalCancellationReason
import ai.icen.fw.retrieval.spi.RerankRequest
import ai.icen.fw.retrieval.spi.Reranker
import ai.icen.fw.retrieval.spi.RerankerDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** Reusable reranker contract that proves an implementation cannot add unauthorized candidates. */
abstract class RerankerContractTest {
    protected abstract val reranker: Reranker

    protected abstract fun rerankRequest(descriptor: RerankerDescriptor): RerankRequest

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    @Test
    fun `keeps its descriptor stable and returns only exact authorized input candidates`() {
        val descriptor = reranker.descriptor()
        assertStableDescriptor(descriptor, reranker.descriptor())
        val request = rerankRequest(descriptor)
        assertEquals(descriptor.digest, request.descriptor.digest)

        val call = reranker.rerank(request)
        assertNotNull(call, "Reranker must return a non-null call handle.")
        val result = RetrievalContractAssertions.awaitStage(
            requireNotNull(call).completion(),
            asynchronousTimeout(),
            "Retrieval reranking completion",
        )
        assertEquals(request.requestId, result.requestId)
        assertEquals(request.digest, result.requestDigest)
        assertEquals(descriptor.digest, result.descriptorDigest)
        assertEquals(descriptor.binding.digest, result.providerBindingDigest)
        assertTrue(result.completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli)
        val allowed = request.items.map { item -> item.candidate.digest }.toSet()
        assertTrue(result.scores.all { score -> score.candidateDigest in allowed })

        val cancellation = RetrievalContractAssertions.awaitStage(
            call.cancel(RetrievalCancellationReason.CALLER_CANCELLED),
            asynchronousTimeout(),
            "Retrieval reranking cancellation",
        )
        RetrievalContractAssertions.assertCancellationDeclaration(descriptor.supportsCancellation, cancellation)
    }

    private fun assertStableDescriptor(first: RerankerDescriptor, second: RerankerDescriptor) {
        assertEquals(first.providerId, second.providerId)
        assertEquals(first.providerInstanceId, second.providerInstanceId)
        assertEquals(first.configurationDigest, second.configurationDigest)
        assertEquals(first.capabilityDigest, second.capabilityDigest)
        assertEquals(first.providerRevision, second.providerRevision)
        assertEquals(first.modelId, second.modelId)
        assertEquals(first.modelVersion, second.modelVersion)
        assertEquals(first.supportsCancellation, second.supportsCancellation)
        assertEquals(first.digest, second.digest, "Reranker descriptor digest must be stable.")
    }
}
