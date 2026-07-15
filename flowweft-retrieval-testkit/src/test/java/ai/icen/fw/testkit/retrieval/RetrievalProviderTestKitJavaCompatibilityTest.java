package ai.icen.fw.testkit.retrieval;

import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.retrieval.api.CandidateRetriever;
import ai.icen.fw.retrieval.api.CandidateRetrieverDescriptor;
import ai.icen.fw.retrieval.api.ExecutableRetrievalRequest;
import ai.icen.fw.retrieval.api.PrefilteredCandidateBatch;
import ai.icen.fw.retrieval.api.ResolvedCandidateBatch;
import ai.icen.fw.retrieval.api.RetrievalAuthorizationPlanner;
import ai.icen.fw.retrieval.api.RetrievalAuthorizationRequest;
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizer;
import ai.icen.fw.retrieval.api.RetrievalHydrationRequest;
import ai.icen.fw.retrieval.api.RetrievalLineageResolver;
import ai.icen.fw.retrieval.spi.ContentChunker;
import ai.icen.fw.retrieval.spi.ContentChunkerDescriptor;
import ai.icen.fw.retrieval.spi.ContentChunkingRequest;
import ai.icen.fw.retrieval.spi.ContentExtractionRequest;
import ai.icen.fw.retrieval.spi.ContentExtractor;
import ai.icen.fw.retrieval.spi.ContentExtractorDescriptor;
import ai.icen.fw.retrieval.spi.EmbeddingProvider;
import ai.icen.fw.retrieval.spi.EmbeddingProviderDescriptor;
import ai.icen.fw.retrieval.spi.EmbeddingRequest;
import ai.icen.fw.retrieval.spi.RerankRequest;
import ai.icen.fw.retrieval.spi.Reranker;
import ai.icen.fw.retrieval.spi.RerankerDescriptor;
import ai.icen.fw.retrieval.spi.RetrievalContentProvider;
import ai.icen.fw.retrieval.spi.RetrievalContentProviderDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Proves every non-index retrieval provider contract remains subclassable from Java 8. */
class RetrievalProviderTestKitJavaCompatibilityTest {
    @Test
    void allRetrievalContractsAreSubclassableFromJavaEight() {
        assertNotNull(new CandidateContract());
        assertNotNull(new AuthorizationContract());
        assertNotNull(new LineageContract());
        assertNotNull(new CandidateAuthorizationContract());
        assertNotNull(new ContentContract());
        assertNotNull(new RerankContract());
        assertNotNull(new ExtractionContract());
        assertNotNull(new ChunkingContract());
        assertNotNull(new EmbeddingContract());
    }

    private static UnsupportedOperationException fixtureOnly() {
        return new UnsupportedOperationException("Compilation fixture only");
    }

    private static final class CandidateContract extends CandidateRetrieverContractTest {
        @Override protected CandidateRetriever getCandidateRetriever() { throw fixtureOnly(); }
        @Override protected ExecutableRetrievalRequest executableRequest(CandidateRetrieverDescriptor descriptor) {
            throw fixtureOnly();
        }
    }

    private static final class AuthorizationContract extends RetrievalAuthorizationPlannerContractTest {
        @Override protected RetrievalAuthorizationPlanner getAuthorizationPlanner() { throw fixtureOnly(); }
        @Override protected RetrievalAuthorizationRequest allowedAuthorizationRequest() { throw fixtureOnly(); }
        @Override protected RetrievalAuthorizationRequest deniedAuthorizationRequest() { throw fixtureOnly(); }
    }

    private static final class LineageContract extends RetrievalLineageResolverContractTest {
        @Override protected RetrievalLineageResolver getLineageResolver() { throw fixtureOnly(); }
        @Override protected ExecutableRetrievalRequest executableRequest() { throw fixtureOnly(); }
        @Override protected PrefilteredCandidateBatch prefilteredBatch() { throw fixtureOnly(); }
        @Override protected Identifier resolutionRequestId() { throw fixtureOnly(); }
        @Override protected LongSupplier resolutionClock() { throw fixtureOnly(); }
    }

    private static final class CandidateAuthorizationContract extends RetrievalCandidateAuthorizerContractTest {
        @Override protected RetrievalCandidateAuthorizer getCandidateAuthorizer() { throw fixtureOnly(); }
        @Override protected RetrievalAuthorizationRequest queryAuthorizationRequest() { throw fixtureOnly(); }
        @Override protected ResolvedCandidateBatch resolvedCandidateBatch() { throw fixtureOnly(); }
        @Override protected Identifier authorizationBatchId() { throw fixtureOnly(); }
        @Override protected Collection<Identifier> candidateAuthorizationRequestIds() { throw fixtureOnly(); }
        @Override protected LongSupplier authorizationClock() { throw fixtureOnly(); }
    }

    private static final class ContentContract extends RetrievalContentProviderContractTest {
        @Override protected RetrievalContentProvider getContentProvider() { throw fixtureOnly(); }
        @Override protected RetrievalHydrationRequest hydrationRequest(RetrievalContentProviderDescriptor descriptor) {
            throw fixtureOnly();
        }
        @Override protected LongSupplier hydrationClock() { throw fixtureOnly(); }
    }

    private static final class RerankContract extends RerankerContractTest {
        @Override protected Reranker getReranker() { throw fixtureOnly(); }
        @Override protected RerankRequest rerankRequest(RerankerDescriptor descriptor) { throw fixtureOnly(); }
    }

    private static final class ExtractionContract extends ContentExtractorContractTest {
        @Override protected ContentExtractor getContentExtractor() { throw fixtureOnly(); }
        @Override protected ContentExtractionRequest extractionRequest(ContentExtractorDescriptor descriptor) {
            throw fixtureOnly();
        }
    }

    private static final class ChunkingContract extends ContentChunkerContractTest {
        @Override protected ContentChunker getContentChunker() { throw fixtureOnly(); }
        @Override protected ContentChunkingRequest chunkingRequest(ContentChunkerDescriptor descriptor) {
            throw fixtureOnly();
        }
    }

    private static final class EmbeddingContract extends EmbeddingProviderContractTest {
        @Override protected EmbeddingProvider getEmbeddingProvider() { throw fixtureOnly(); }
        @Override protected EmbeddingRequest embeddingRequest(EmbeddingProviderDescriptor descriptor) {
            throw fixtureOnly();
        }
    }
}
