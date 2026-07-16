package ai.icen.fw.agent.adapter.credential.runtime;

import ai.icen.fw.agent.adapter.http.okhttp.AgentProtocolHttpCredentialProvider;
import ai.icen.fw.agent.api.AgentRemoteCredentialBroker;
import ai.icen.fw.agent.api.ProviderId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaAgentCredentialRuntimeCompatibilityTest {
    @Test
    void publicSurfaceIsJava8Friendly() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AgentRemoteCredentialMaterialSource source = request -> new CompletableFuture<>();
            OpaqueAgentRemoteCredentialBroker broker = new OpaqueAgentRemoteCredentialBroker(
                new ProviderId("credential.broker"),
                source,
                scheduler,
                new AgentCredentialRuntimeConfiguration(1_000L, 100L, 100, 10),
                AgentCredentialRuntimeClock.SYSTEM,
                AgentCredentialRuntimeIdSource.RANDOM_UUID
            );

            assertTrue(broker instanceof AgentRemoteCredentialBroker);
            assertTrue(broker instanceof AgentProtocolHttpCredentialProvider);
            assertEquals("credential.broker", broker.brokerId().getValue());
            broker.close();
        } finally {
            scheduler.shutdownNow();
        }
    }
}
