package ai.icen.fw.agent.adapter.network.jdk

import ai.icen.fw.agent.api.AgentRemoteNetworkResolutionRequest
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.net.URI
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdkAgentRemoteNetworkResolverTest {
    @Test
    fun `returns a short lived exact public address set`() = withExecutors { executor, scheduler ->
        val request = request()
        val resolver = resolver(
            executor,
            scheduler,
            AgentRemoteDnsLookup {
                listOf(
                    byteArrayOf(8, 8, 4, 4),
                    byteArrayOf(8, 8, 8, 8),
                    byteArrayOf(8, 8, 4, 4),
                )
            },
        )

        val result = resolver.resolve(request).toCompletableFuture().join()

        assertEquals(PROVIDER, result.providerId)
        assertEquals(2, result.addresses.size)
        assertEquals(result.addresses.map { it.addressDigest }.sorted(), result.addresses.map { it.addressDigest })
        result.requirePublicAndCurrent(request, 1_000L)
        assertFalse(result.toString().contains("8.8"))
    }

    @Test
    fun `mixed public and private DNS answer is rejected as a whole`() = withExecutors { executor, scheduler ->
        val resolver = resolver(
            executor,
            scheduler,
            AgentRemoteDnsLookup { listOf(byteArrayOf(8, 8, 8, 8), byteArrayOf(10, 0, 0, 1)) },
        )

        val failure = assertFailsWith<CompletionException> {
            resolver.resolve(request()).toCompletableFuture().join()
        }

        assertEquals("network.resolution-non-public-address", (failure.cause as JdkAgentRemoteNetworkResolutionException).code)
    }

    @Test
    fun `lookup cannot outlive its bounded deadline`() = withExecutors { executor, scheduler ->
        val resolver = JdkAgentRemoteNetworkResolver(
            PROVIDER,
            executor,
            scheduler,
            JdkAgentRemoteNetworkResolverConfiguration(25L, 100L, 4),
            AgentRemoteDnsLookup {
                Thread.sleep(2_000L)
                listOf(byteArrayOf(8, 8, 8, 8))
            },
            JdkAgentRemoteNetworkClock { 1_000L },
            JdkAgentRemoteNetworkIdSource { Identifier("resolution-test") },
        )

        val failure = assertFailsWith<CompletionException> {
            resolver.resolve(request()).toCompletableFuture().join()
        }

        assertEquals("network.resolution-timed-out", (failure.cause as JdkAgentRemoteNetworkResolutionException).code)
    }

    @Test
    fun `expired request performs no DNS lookup`() = withExecutors { executor, scheduler ->
        var called = false
        val resolver = JdkAgentRemoteNetworkResolver(
            PROVIDER,
            executor,
            scheduler,
            lookup = AgentRemoteDnsLookup {
                called = true
                emptyList()
            },
            clock = JdkAgentRemoteNetworkClock { 2_000L },
        )

        val failure = assertFailsWith<CompletionException> {
            resolver.resolve(request(deadlineAt = 2_000L)).toCompletableFuture().join()
        }

        assertEquals("network.resolution-deadline-exceeded", (failure.cause as JdkAgentRemoteNetworkResolutionException).code)
        assertFalse(called)
        assertTrue(resolver.toString().contains("<redacted>"))
    }

    private fun resolver(
        executor: java.util.concurrent.ExecutorService,
        scheduler: java.util.concurrent.ScheduledExecutorService,
        lookup: AgentRemoteDnsLookup,
    ): JdkAgentRemoteNetworkResolver = JdkAgentRemoteNetworkResolver(
        PROVIDER,
        executor,
        scheduler,
        JdkAgentRemoteNetworkResolverConfiguration(500L, 100L, 4),
        lookup,
        JdkAgentRemoteNetworkClock { 1_000L },
        JdkAgentRemoteNetworkIdSource { Identifier("resolution-test") },
    )

    private fun request(deadlineAt: Long = 2_000L): AgentRemoteNetworkResolutionRequest =
        AgentRemoteNetworkResolutionRequest(
            Identifier("resolution-request"),
            ProviderId("peer.mcp"),
            DIGEST,
            URI("https://mcp.example/protocol"),
            null,
            0,
            900L,
            deadlineAt,
        )

    private fun withExecutors(
        block: (
            java.util.concurrent.ExecutorService,
            java.util.concurrent.ScheduledExecutorService,
        ) -> Unit,
    ) {
        val executor = Executors.newSingleThreadExecutor()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            block(executor, scheduler)
        } finally {
            executor.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    private companion object {
        val PROVIDER = ProviderId("network.jdk")
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
