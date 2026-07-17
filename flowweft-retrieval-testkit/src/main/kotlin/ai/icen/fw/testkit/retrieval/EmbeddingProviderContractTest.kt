package ai.icen.fw.testkit.retrieval

import ai.icen.fw.retrieval.spi.EmbeddingProvider
import ai.icen.fw.retrieval.spi.EmbeddingProviderDescriptor
import ai.icen.fw.retrieval.spi.EmbeddingRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** Reusable embedding provider contract with ordered input and vector-dimension binding. */
abstract class EmbeddingProviderContractTest {
    protected abstract val embeddingProvider: EmbeddingProvider

    protected abstract fun embeddingRequest(descriptor: EmbeddingProviderDescriptor): EmbeddingRequest

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    @Test
    fun `keeps its descriptor stable and embeds every exact input once`() {
        val descriptor = embeddingProvider.descriptor()
        assertStableDescriptor(descriptor, embeddingProvider.descriptor())
        val request = embeddingRequest(descriptor)
        assertEquals(descriptor.digest, request.descriptor.digest)

        val result = RetrievalContractAssertions.awaitStage(
            embeddingProvider.embed(request),
            asynchronousTimeout(),
            "Embedding completion",
        )
        assertEquals(request.requestId, result.requestId)
        assertEquals(request.digest, result.requestDigest)
        assertEquals(descriptor.digest, result.descriptorDigest)
        assertTrue(result.completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli)
        assertEquals(request.inputs.size, result.vectors.size)
        request.inputs.zip(result.vectors).forEach { (input, vector) ->
            assertEquals(input.digest, vector.inputDigest)
            assertEquals(descriptor.dimensions, vector.values.size)
        }
    }

    private fun assertStableDescriptor(first: EmbeddingProviderDescriptor, second: EmbeddingProviderDescriptor) {
        assertEquals(first.providerId, second.providerId)
        assertEquals(first.providerInstanceId, second.providerInstanceId)
        assertEquals(first.providerRevision, second.providerRevision)
        assertEquals(first.modelId, second.modelId)
        assertEquals(first.modelVersion, second.modelVersion)
        assertEquals(first.dimensions, second.dimensions)
        assertEquals(first.maximumBatchSize, second.maximumBatchSize)
        assertEquals(first.maximumInputCodePoints, second.maximumInputCodePoints)
        assertEquals(first.digest, second.digest, "Embedding provider descriptor digest must be stable.")
    }
}
