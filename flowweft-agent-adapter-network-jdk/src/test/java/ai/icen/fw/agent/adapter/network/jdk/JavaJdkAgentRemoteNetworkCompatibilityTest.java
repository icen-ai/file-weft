package ai.icen.fw.agent.adapter.network.jdk;

import ai.icen.fw.agent.api.AgentRemoteNetworkResolver;
import ai.icen.fw.agent.api.ProviderId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaJdkAgentRemoteNetworkCompatibilityTest {
    @Test
    void publicSurfaceIsJava8Friendly() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            JdkAgentRemoteNetworkResolverConfiguration configuration =
                new JdkAgentRemoteNetworkResolverConfiguration(100L, 1_000L, 4);
            JdkAgentRemoteNetworkResolver resolver = new JdkAgentRemoteNetworkResolver(
                new ProviderId("network.jdk"),
                executor,
                scheduler,
                configuration,
                AgentRemoteDnsLookup.SYSTEM,
                JdkAgentRemoteNetworkClock.SYSTEM,
                JdkAgentRemoteNetworkIdSource.RANDOM_UUID
            );

            assertTrue(resolver instanceof AgentRemoteNetworkResolver);
            assertEquals(4, configuration.getMaximumAddresses());
            assertNotNull(resolver.providerId());
        } finally {
            executor.shutdownNow();
            scheduler.shutdownNow();
        }
    }
}
