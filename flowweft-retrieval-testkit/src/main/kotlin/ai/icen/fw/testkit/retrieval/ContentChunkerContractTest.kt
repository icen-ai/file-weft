package ai.icen.fw.testkit.retrieval

import ai.icen.fw.retrieval.spi.ContentChunker
import ai.icen.fw.retrieval.spi.ContentChunkerDescriptor
import ai.icen.fw.retrieval.spi.ContentChunkingRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** Reusable deterministic chunk lineage and exact-offset provider contract. */
abstract class ContentChunkerContractTest {
    protected abstract val contentChunker: ContentChunker

    protected abstract fun chunkingRequest(descriptor: ContentChunkerDescriptor): ContentChunkingRequest

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    @Test
    fun `keeps its descriptor stable and returns a fully bound chunk manifest`() {
        val descriptor = contentChunker.descriptor()
        assertStableDescriptor(descriptor, contentChunker.descriptor())
        val request = chunkingRequest(descriptor)
        assertEquals(descriptor.digest, request.descriptor.digest)

        val result = RetrievalContractAssertions.awaitStage(
            contentChunker.chunk(request),
            asynchronousTimeout(),
            "Content chunking completion",
        )
        assertEquals(request.requestId, result.requestId)
        assertEquals(request.digest, result.requestDigest)
        assertEquals(request.extraction.digest, result.extractionDigest)
        assertEquals(descriptor.digest, result.descriptorDigest)
        assertTrue(result.completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli)
        assertTrue(result.chunks.size <= request.maximumChunks)
        assertEquals(result.chunks.indices.toList(), result.chunks.map { it.ordinal })
    }

    private fun assertStableDescriptor(first: ContentChunkerDescriptor, second: ContentChunkerDescriptor) {
        assertEquals(first.providerId, second.providerId)
        assertEquals(first.providerInstanceId, second.providerInstanceId)
        assertEquals(first.providerRevision, second.providerRevision)
        assertEquals(first.chunkerVersion, second.chunkerVersion)
        assertEquals(first.maximumInputCodePoints, second.maximumInputCodePoints)
        assertEquals(first.maximumChunks, second.maximumChunks)
        assertEquals(first.maximumChunkCodePoints, second.maximumChunkCodePoints)
        assertEquals(first.maximumOverlapCodePoints, second.maximumOverlapCodePoints)
        assertEquals(first.digest, second.digest, "Content chunker descriptor digest must be stable.")
    }
}
