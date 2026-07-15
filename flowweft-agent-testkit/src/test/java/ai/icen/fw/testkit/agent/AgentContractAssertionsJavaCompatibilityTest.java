package ai.icen.fw.testkit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class AgentContractAssertionsJavaCompatibilityTest {
    @Test
    void exposesRealStaticJavaEightHelpers() {
        assertEquals(
                1_000L,
                AgentContractAssertions.timeoutMillis(Duration.ofSeconds(1), "Java timeout"));
        assertEquals(
                "ok",
                AgentContractAssertions.awaitStage(
                        CompletableFuture.completedFuture("ok"),
                        Duration.ofSeconds(1),
                        "Java stage"));
    }
}
