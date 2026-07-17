package ai.icen.fw.testkit.retrieval

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.spi.ContentBinarySource
import ai.icen.fw.retrieval.spi.ContentChunk
import ai.icen.fw.retrieval.spi.ContentChunker
import ai.icen.fw.retrieval.spi.ContentChunkerDescriptor
import ai.icen.fw.retrieval.spi.ContentChunkingRequest
import ai.icen.fw.retrieval.spi.ContentChunkingResult
import ai.icen.fw.retrieval.spi.ContentExtractionRequest
import ai.icen.fw.retrieval.spi.ContentExtractionResult
import ai.icen.fw.retrieval.spi.ContentExtractor
import ai.icen.fw.retrieval.spi.ContentExtractorDescriptor
import ai.icen.fw.retrieval.spi.ContentInputHandle
import ai.icen.fw.retrieval.spi.ContentSourceRef
import ai.icen.fw.retrieval.spi.EmbeddingInput
import ai.icen.fw.retrieval.spi.EmbeddingProvider
import ai.icen.fw.retrieval.spi.EmbeddingProviderDescriptor
import ai.icen.fw.retrieval.spi.EmbeddingRequest
import ai.icen.fw.retrieval.spi.EmbeddingResult
import ai.icen.fw.retrieval.spi.EmbeddingSimilarity
import ai.icen.fw.retrieval.spi.EmbeddingVector
import ai.icen.fw.retrieval.spi.ExtractedContentSegment
import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.concurrent.CompletableFuture

private object RetrievalContentPipelineFixture {
    const val TEXT = "authorized fixture content"
    private val bytes = TEXT.toByteArray(Charsets.UTF_8)

    val extractorDescriptor: ContentExtractorDescriptor = ContentExtractorDescriptor.of(
        "fixture-extractor",
        "fixture-extractor-instance",
        "extractor-provider-v1",
        "extractor-v1",
        listOf("text/plain"),
        4_096L,
        4_096,
        false,
        false,
    )

    val extractor: ContentExtractor = object : ContentExtractor {
        override fun descriptor(): ContentExtractorDescriptor = extractorDescriptor

        override fun extract(request: ContentExtractionRequest) = request.openSource().thenApply { handle ->
            try {
                val text = handle.stream().reader(Charsets.UTF_8).readText()
                ContentExtractionResult.success(
                    request,
                    listOf(
                        ExtractedContentSegment.of(
                            0,
                            text,
                            "text/plain",
                            0L,
                            request.source.sourceSizeBytes,
                        ),
                    ),
                    "fixture-extraction-request",
                    120L,
                )
            } finally {
                handle.close()
            }
        }
    }

    val chunkerDescriptor: ContentChunkerDescriptor = ContentChunkerDescriptor.of(
        "fixture-chunker",
        "fixture-chunker-instance",
        "chunker-provider-v1",
        "chunker-v1",
        4_096,
        16,
        1_024,
        128,
    )

    val chunker: ContentChunker = object : ContentChunker {
        override fun descriptor(): ContentChunkerDescriptor = chunkerDescriptor

        override fun chunk(request: ContentChunkingRequest) = CompletableFuture.completedFuture(
            ContentChunkingResult.success(
                request,
                listOf(chunkFor(request)),
                140L,
            ),
        )
    }

    val embeddingDescriptor: EmbeddingProviderDescriptor = EmbeddingProviderDescriptor.of(
        "fixture-embedding",
        "fixture-embedding-instance",
        "embedding-provider-v1",
        "fixture-embedding-model",
        "model-v1",
        3,
        16,
        1_024,
        EmbeddingSimilarity.COSINE,
        false,
    )

    val embeddingProvider: EmbeddingProvider = object : EmbeddingProvider {
        override fun descriptor(): EmbeddingProviderDescriptor = embeddingDescriptor

        override fun embed(request: EmbeddingRequest) = CompletableFuture.completedFuture(
            EmbeddingResult.success(
                request,
                request.inputs.map { input -> EmbeddingVector.of(input, listOf(0.1, 0.2, 0.3)) },
                "fixture-embedding-request",
                160L,
            ),
        )
    }

    fun extractionRequest(
        suffix: String,
        descriptor: ContentExtractorDescriptor = extractorDescriptor,
    ): ContentExtractionRequest {
        val source = ContentSourceRef.of(
            Identifier("tenant-1"),
            Identifier("catalog-1"),
            Identifier("document-1"),
            Identifier("version-1"),
            Identifier("asset-1"),
            Identifier("object-1"),
            RetrievalSecurityGateFixture.SOURCE_SHA256,
            bytes.size.toLong(),
            "text/plain",
            "content-v1",
        )
        val binarySource = ContentBinarySource { requestedSource, _ ->
            CompletableFuture.completedFuture(
                ContentInputHandle.of(
                    ByteArrayInputStream(bytes),
                    requestedSource.digest,
                    requestedSource.sourceSha256,
                    bytes.size.toLong(),
                ),
            )
        }
        return ContentExtractionRequest.of(
            Identifier("extraction-$suffix"),
            source,
            descriptor,
            RetrievalSecurityGateFixture.digest('7'),
            4_096,
            100L,
            300L,
            binarySource,
        )
    }

    fun extractionResult(suffix: String): ContentExtractionResult = RetrievalContractAssertions.awaitStage(
        extractor.extract(extractionRequest(suffix)),
        Duration.ofSeconds(1),
        "Fixture extraction",
    )

    fun chunkingRequest(
        suffix: String,
        descriptor: ContentChunkerDescriptor = chunkerDescriptor,
    ): ContentChunkingRequest = ContentChunkingRequest.of(
        Identifier("chunking-$suffix"),
        extractionResult("$suffix-source"),
        descriptor,
        RetrievalSecurityGateFixture.digest('8'),
        16,
        1_024,
        128,
        130L,
        300L,
    )

    fun chunkingResult(suffix: String): ContentChunkingResult = RetrievalContractAssertions.awaitStage(
        chunker.chunk(chunkingRequest(suffix)),
        Duration.ofSeconds(1),
        "Fixture chunking",
    )

    fun embeddingRequest(
        suffix: String,
        descriptor: EmbeddingProviderDescriptor = embeddingDescriptor,
    ): EmbeddingRequest = EmbeddingRequest.of(
        Identifier("embedding-$suffix"),
        descriptor,
        chunkingResult("$suffix-source").chunks.map { chunk -> EmbeddingInput.from(chunk) },
        150L,
        300L,
    )

    private fun chunkFor(request: ContentChunkingRequest): ContentChunk {
        val segment = request.extraction.segments.single()
        val end = segment.text.codePointCount(0, segment.text.length)
        return ContentChunk.of(
            0,
            segment.ordinal,
            0,
            end,
            segment.text,
            segment.mediaType,
            request.extraction.sourceSha256,
            segment.pageNumber,
        )
    }
}

class ContentExtractorContractBehaviorTest : ContentExtractorContractTest() {
    override val contentExtractor: ContentExtractor = RetrievalContentPipelineFixture.extractor

    override fun extractionRequest(descriptor: ContentExtractorDescriptor): ContentExtractionRequest =
        RetrievalContentPipelineFixture.extractionRequest("contract", descriptor)
}

class ContentChunkerContractBehaviorTest : ContentChunkerContractTest() {
    override val contentChunker: ContentChunker = RetrievalContentPipelineFixture.chunker

    override fun chunkingRequest(descriptor: ContentChunkerDescriptor): ContentChunkingRequest =
        RetrievalContentPipelineFixture.chunkingRequest("contract", descriptor)
}

class EmbeddingProviderContractBehaviorTest : EmbeddingProviderContractTest() {
    override val embeddingProvider: EmbeddingProvider = RetrievalContentPipelineFixture.embeddingProvider

    override fun embeddingRequest(descriptor: EmbeddingProviderDescriptor): EmbeddingRequest =
        RetrievalContentPipelineFixture.embeddingRequest("contract", descriptor)
}
