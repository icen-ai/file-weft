package ai.icen.fw.retrieval.spi;

import ai.icen.fw.core.id.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaRetrievalSpiCompatibilityTest {
    @Test
    void publicProviderContractsAreCallableFromJava8() throws Exception {
        ContentExtractorDescriptor extractor = ContentExtractorDescriptor.of(
            "extractor", "extractor-1", "revision-1", "parser-1",
            Arrays.asList("text/plain"), 1024L, 512, false, false
        );
        EmbeddingProviderDescriptor embedding = EmbeddingProviderDescriptor.of(
            "embedding", "embedding-1", "revision-1", "model-1", "model-v1",
            3, 8, 512, EmbeddingSimilarity.COSINE, false
        );
        RetrievalIndexProviderDescriptor index = RetrievalIndexProviderDescriptor.of(
            "index", "index-1", "revision-1", "schema-1",
            64, true, true, true, true, true
        );
        RerankerDescriptor reranker = RerankerDescriptor.of(
            "reranker", "reranker-1",
            repeat('a', 64), repeat('b', 64), "revision-1", "model-1", "model-v1",
            10, 512, false, true
        );
        RetrievalContentProviderDescriptor content = RetrievalContentProviderDescriptor.of(
            "content", "content-1",
            repeat('c', 64), repeat('d', 64), "revision-1",
            512, false, true
        );
        FilenameCatalogDescriptor filenameCatalog = FilenameCatalogDescriptor.create(
            "host.filename-catalog", "filename-catalog-1",
            repeat('e', 64), repeat('f', 64), "revision-1",
            100, 1_000, false, true, true
        );

        assertEquals("extractor", extractor.getProviderId());
        assertEquals(3, embedding.getDimensions());
        assertFalse(reranker.getSendsQueryOrContentOffHost());
        assertEquals("content-hydration", content.getBinding().getStageId());
        assertEquals(100, filenameCatalog.getMaximumPageSize());
        assertFalse(filenameCatalog.getSendsMetadataOffHost());
        assertSame(EmbeddingSimilarity.COSINE, embedding.getSimilarity());
        assertEquals(64, index.getMaximumStageBatchSize());
        assertEquals("request-java", new Identifier("request-java").getValue());

        assertNotNull(ExtractedContentSegment.class.getMethod("of", int.class, String.class, String.class));
        assertNotNull(ContentChunk.class.getMethod(
            "of",
            int.class,
            int.class,
            int.class,
            int.class,
            String.class,
            String.class,
            String.class
        ));
        assertNotNull(RetrievalIndexGenerationManifest.class.getMethod(
            "of",
            String.class,
            ContentSourceRef.class,
            ContentExtractionResult.class,
            ContentChunkingResult.class,
            RetrievalIndexProviderDescriptor.class,
            String.class,
            String.class
        ));
        assertNotNull(RetrievalIndexActivationRequest.class.getMethod(
            "of",
            Identifier.class,
            RetrievalIndexSealReceipt.class,
            long.class,
            long.class,
            long.class
        ));
        assertNotNull(RetrievalIndexState.class.getMethod(
            "observed",
            RetrievalIndexStateRequest.class,
            long.class,
            long.class
        ));
        assertNotNull(FilenameCatalog.class.getMethod("descriptor"));
        assertNotNull(FilenameCatalog.class.getMethod("scan", FilenameCatalogScanRequest.class));
        assertNotNull(FilenameCatalogScanRequest.class.getMethod(
            "create",
            ai.icen.fw.retrieval.api.ExecutableRetrievalRequest.class,
            FilenameCatalogDescriptor.class
        ));

        ContentChunk chunk = ContentChunk.of(
            0,
            0,
            0,
            1,
            "a",
            "text/plain",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        EmbeddingInput input = EmbeddingInput.from(chunk);
        assertThrows(
            IllegalArgumentException.class,
            () -> EmbeddingVector.of(input, Arrays.asList(0.1, null, 0.3))
        );
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        Arrays.fill(result, value);
        return new String(result);
    }
}
