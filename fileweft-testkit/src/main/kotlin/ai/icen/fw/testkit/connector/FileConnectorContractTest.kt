package ai.icen.fw.testkit.connector

import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import ai.icen.fw.spi.connector.FileConnector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Reusable contract for a downstream [FileConnector]. The configured test
 * endpoint may legitimately report [ConnectorHealthStatus.UNHEALTHY]; the
 * health assertion verifies protocol behavior rather than current operations.
 */
abstract class FileConnectorContractTest {
    protected abstract val fileConnector: FileConnector

    protected abstract fun syncRequest(): ConnectorSyncRequest

    protected abstract fun removalRequest(): ConnectorRemoveRequest

    /** Override to reduce concurrency for a provider-constrained test environment. */
    protected open fun concurrentSyncReplayAttempts(): Int = 2

    /** Bounds local waiting for all concurrent idempotent replays. */
    protected open fun concurrentSyncReplayTimeout(): Duration = Duration.ofSeconds(10)

    @Test
    fun `reports a valid health status without requiring an active downstream`() {
        val health = fileConnector.health()

        assertTrue(
            ConnectorHealthStatus.values().contains(health.status),
            "Connector health must return a documented status.",
        )
    }

    @Test
    fun `synchronizes a request idempotently with a stable external id`() {
        val request = syncRequest()
        val result = fileConnector.sync(request)
        val replay = fileConnector.sync(request)

        assertSuccessfulReplay(result)
        assertSuccessfulReplay(replay)
        assertEquals(result.externalId, replay.externalId, "An idempotent replay must retain the external id.")
    }

    @Test
    fun `synchronizes concurrent idempotent replays with one stable external id`() {
        val attempts = concurrentSyncReplayAttempts()
        require(attempts > 0) { "Connector contract concurrent replay attempts must be positive." }
        val timeoutMillis = positiveMillis(concurrentSyncReplayTimeout(), "Connector contract concurrent replay timeout")
        val request = syncRequest()
        val executor = Executors.newFixedThreadPool(attempts)
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        try {
            val futures = ArrayList<Future<ConnectorSyncResult>>()
            repeat(attempts) {
                futures += executor.submit<ConnectorSyncResult> {
                    ready.countDown()
                    check(start.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        "Connector contract concurrent replays did not receive a start signal."
                    }
                    fileConnector.sync(request)
                }
            }
            assertTrue(
                ready.await(timeoutMillis, TimeUnit.MILLISECONDS),
                "Connector contract concurrent replays did not become ready in time.",
            )
            start.countDown()

            val results = futures.map { future -> future.get(timeoutMillis, TimeUnit.MILLISECONDS) }
            results.forEach(::assertSuccessfulReplay)
            val externalId = results.first().externalId
            assertTrue(!externalId.isNullOrBlank(), "Successful synchronization must return an external id.")
            assertTrue(
                results.all { it.externalId == externalId },
                "Concurrent idempotent replays must retain one external id.",
            )
        } finally {
            start.countDown()
            executor.shutdownNow()
            executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `removes a request idempotently`() {
        val request = removalRequest()
        val result = fileConnector.remove(request)
        val replay = fileConnector.remove(request)

        assertEquals(ConnectorSyncStatus.SUCCESS, result.status, result.message)
        assertEquals(ConnectorSyncStatus.SUCCESS, replay.status, replay.message)
    }

    private fun assertSuccessfulReplay(result: ConnectorSyncResult) {
        assertEquals(ConnectorSyncStatus.SUCCESS, result.status, result.message)
        assertTrue(!result.externalId.isNullOrBlank(), "Successful synchronization must return an external id.")
    }

    private fun positiveMillis(duration: Duration, name: String): Long {
        require(!duration.isNegative && !duration.isZero) { "$name must be positive." }
        val millis = try {
            duration.toMillis()
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("$name is too large.", failure)
        }
        require(millis > 0) { "$name must be at least one millisecond." }
        return millis
    }
}
