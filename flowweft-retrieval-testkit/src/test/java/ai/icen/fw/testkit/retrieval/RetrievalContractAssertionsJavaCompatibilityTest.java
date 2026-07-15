package ai.icen.fw.testkit.retrieval;

import ai.icen.fw.retrieval.api.RetrievalCancellationOutcome;
import ai.icen.fw.retrieval.api.RetrievalFailureCode;
import ai.icen.fw.retrieval.api.RetrievalProviderException;
import ai.icen.fw.retrieval.api.RetrievalRetryability;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RetrievalContractAssertionsJavaCompatibilityTest {
    @Test
    void exposesRealStaticJavaEightHelpers() {
        assertEquals(1_000L, RetrievalContractAssertions.timeoutMillis(Duration.ofSeconds(1), "Java timeout"));
        assertEquals(
                "ok",
                RetrievalContractAssertions.awaitStage(
                        CompletableFuture.completedFuture("ok"),
                        Duration.ofSeconds(1),
                        "Java stage"));
        RetrievalContractAssertions.assertCancellationDeclaration(
                true,
                RetrievalCancellationOutcome.ACCEPTED);

        RetrievalProviderException failure = new RetrievalProviderException(
                RetrievalFailureCode.INDEX_PROJECTION_CONFLICT,
                RetrievalRetryability.NOT_RETRYABLE);
        CompletableFuture<String> failedStage = new CompletableFuture<>();
        failedStage.completeExceptionally(failure);
        assertSame(
                failure,
                RetrievalContractAssertions.awaitProviderFailure(
                        failedStage,
                        Duration.ofSeconds(1),
                        "Java failed stage"));
        assertSame(
                failure,
                RetrievalContractAssertions.requireProviderFailure(
                        new CompletionException(failure),
                        "Java wrapped failure"));
    }
}
