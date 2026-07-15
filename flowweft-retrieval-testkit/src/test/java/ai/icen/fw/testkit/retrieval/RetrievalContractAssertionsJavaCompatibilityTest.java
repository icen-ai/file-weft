package ai.icen.fw.testkit.retrieval;

import ai.icen.fw.retrieval.api.RetrievalCancellationOutcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    }
}
