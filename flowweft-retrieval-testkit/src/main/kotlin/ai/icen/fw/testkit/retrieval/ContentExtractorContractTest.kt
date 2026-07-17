package ai.icen.fw.testkit.retrieval

import ai.icen.fw.retrieval.spi.ContentExtractionRequest
import ai.icen.fw.retrieval.spi.ContentExtractor
import ai.icen.fw.retrieval.spi.ContentExtractorDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** Reusable content extraction contract with source, descriptor and result lineage binding. */
abstract class ContentExtractorContractTest {
    protected abstract val contentExtractor: ContentExtractor

    /** A fresh request whose single-use binary source has not been opened. */
    protected abstract fun extractionRequest(descriptor: ContentExtractorDescriptor): ContentExtractionRequest

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    @Test
    fun `keeps its descriptor stable and extracts a fully bound result`() {
        val descriptor = contentExtractor.descriptor()
        assertStableDescriptor(descriptor, contentExtractor.descriptor())
        val request = extractionRequest(descriptor)
        assertEquals(descriptor.digest, request.descriptor.digest)
        descriptor.requireSupports(request.source)

        val result = RetrievalContractAssertions.awaitStage(
            contentExtractor.extract(request),
            asynchronousTimeout(),
            "Content extraction completion",
        )
        assertEquals(request.requestId, result.requestId)
        assertEquals(request.digest, result.requestDigest)
        assertEquals(request.source.digest, result.sourceDigest)
        assertEquals(request.source.sourceSha256, result.sourceSha256)
        assertEquals(descriptor.digest, result.descriptorDigest)
        assertTrue(result.completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli)
        val outputCodePoints = result.segments.sumOf { segment ->
            segment.text.codePointCount(0, segment.text.length).toLong()
        }
        assertTrue(outputCodePoints <= request.maximumOutputCodePoints.toLong())
    }

    private fun assertStableDescriptor(first: ContentExtractorDescriptor, second: ContentExtractorDescriptor) {
        assertEquals(first.providerId, second.providerId)
        assertEquals(first.providerInstanceId, second.providerInstanceId)
        assertEquals(first.providerRevision, second.providerRevision)
        assertEquals(first.extractorVersion, second.extractorVersion)
        assertEquals(first.supportedMediaTypes, second.supportedMediaTypes)
        assertEquals(first.maximumSourceBytes, second.maximumSourceBytes)
        assertEquals(first.maximumOutputCodePoints, second.maximumOutputCodePoints)
        assertEquals(first.digest, second.digest, "Content extractor descriptor digest must be stable.")
    }
}
