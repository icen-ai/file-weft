package ai.icen.fw.retrieval.api;

import ai.icen.fw.core.id.Identifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JavaDeletionVisibilityCompatibilityTest {
    @Test
    void javaCanImplementTheAuthoritativeVisibilityPortWithoutKotlinTypes() {
        RetrievalDeletionVisibilityDescriptor descriptor = RetrievalDeletionVisibilityDescriptor.create(
            "host.deletion-visibility",
            "java-visibility-instance",
            repeat('a'),
            "visibility-v1",
            Arrays.asList(RetrievalDeletionResourceRef.DOCUMENT_RESOURCE_TYPE),
            32,
            true,
            true,
            false
        );
        RetrievalDeletionResourceRef resource = RetrievalDeletionResourceRef.documentContent(
            new Identifier("tenant-java"),
            new Identifier("document-java"),
            new Identifier("version-java"),
            repeat('b')
        );
        RetrievalDeletionVisibilityProvider provider = new RetrievalDeletionVisibilityProvider() {
            @Override
            public RetrievalDeletionVisibilityDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public RetrievalCall<RetrievalDeletionVisibilityBatch> inspect(
                RetrievalDeletionVisibilityRequest request
            ) {
                RetrievalDeletionVisibilityDecision decision =
                    RetrievalDeletionVisibilityDecision.tombstoned(
                        request.getResources().get(0),
                        "authority-java",
                        "tombstone-java",
                        120L
                    );
                return RetrievalCalls.completed(
                    RetrievalDeletionVisibilityBatch.create(
                        request,
                        descriptor,
                        Arrays.asList(decision),
                        125L
                    )
                );
            }
        };
        RetrievalDeletionVisibilityRequest request = RetrievalDeletionVisibilityRequest.create(
            new Identifier("visibility-java"),
            Arrays.asList(resource),
            provider.descriptor(),
            100L,
            200L
        );

        RetrievalDeletionVisibilityBatch result = provider.inspect(request)
            .completion().toCompletableFuture().join();

        result.requireExactFor(request, descriptor);
        assertFalse(result.getDecisions().get(0).isVisible());
        assertEquals(RetrievalDeletionVisibilityState.TOMBSTONED, result.getDecisions().get(0).getState());
    }

    private static String repeat(char value) {
        char[] chars = new char[64];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
