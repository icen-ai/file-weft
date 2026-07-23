package ai.icen.fw.adapter.connector

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.connector.ConnectorFileSource
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorInvocation
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.observability.FileWeftLogger
import ai.icen.fw.spi.observability.LogContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResilientFileConnectorTest {
    private val executors = mutableListOf<ConnectorInvocationExecutor>()

    @AfterEach
    fun closeExecutors() {
        executors.forEach(ConnectorInvocationExecutor::close)
    }

    @Test
    fun `opens after retryable failures then permits one successful recovery probe`() {
        val clock = MutableClock(1_000)
        val calls = AtomicInteger()
        val connector = connector {
            when (calls.incrementAndGet()) {
                1, 2 -> ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE, message = "remote unavailable")
                else -> ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, externalId = "external-1")
            }
        }
        val protected = ResilientFileConnector(
            "platform", connector, policy(failureThreshold = 2, circuitOpenDuration = Duration.ofMillis(100)), executor(), clock,
        )

        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, protected.sync(request()).status)
        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, protected.sync(request()).status)
        val blocked = protected.sync(request())
        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, blocked.status)
        assertTrue(blocked.message!!.contains("circuit is open"))
        assertEquals(2, calls.get())

        clock.advance(100)
        assertEquals(ConnectorSyncStatus.SUCCESS, protected.sync(request()).status)
        assertEquals(3, calls.get())
        assertEquals(ConnectorSyncStatus.SUCCESS, protected.sync(request()).status)
        assertEquals(4, calls.get())
    }

    @Test
    fun `times out an uncooperative connector and rejects later calls without consuming another worker`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val protected = ResilientFileConnector(
            "slow-platform",
            connector {
                calls.incrementAndGet()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, externalId = "too-late")
            },
            policy(failureThreshold = 1, timeout = Duration.ofMillis(25)),
            executor(maxConcurrentInvocations = 1, queueCapacity = 1),
            Clock.systemUTC(),
        )

        val timedOut = protected.sync(request(timeout = Duration.ofSeconds(1)))
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, timedOut.status)
        assertTrue(timedOut.message!!.contains("timed out"))
        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, protected.sync(request()).status)
        assertEquals(1, calls.get())
        release.countDown()
    }

    @Test
    fun `uses the caller timeout when it is stricter than the global policy`() {
        val release = CountDownLatch(1)
        val protected = ResilientFileConnector(
            "strict-request",
            connector {
                release.await(5, TimeUnit.SECONDS)
                ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)
            },
            policy(timeout = Duration.ofSeconds(1)),
            executor(),
            Clock.systemUTC(),
        )

        val result = protected.sync(request(timeout = Duration.ofMillis(10)))

        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, result.status)
        assertTrue(result.message!!.contains("10 ms"))
        release.countDown()
    }

    @Test
    fun `does not open the circuit for a permanent downstream rejection`() {
        val calls = AtomicInteger()
        val protected = ResilientFileConnector(
            "validation-platform",
            connector {
                calls.incrementAndGet()
                ConnectorSyncResult(ConnectorSyncStatus.PERMANENT_FAILURE, message = "unsupported document type")
            },
            policy(failureThreshold = 1),
            executor(),
            Clock.systemUTC(),
        )

        assertEquals(ConnectorSyncStatus.PERMANENT_FAILURE, protected.sync(request()).status)
        assertEquals(ConnectorSyncStatus.PERMANENT_FAILURE, protected.sync(request()).status)
        assertEquals(2, calls.get())
    }

    @Test
    fun `reports an open circuit to Doctor without calling the downstream health endpoint`() {
        val healthCalls = AtomicInteger()
        val protected = ResilientFileConnector(
            "doctor-platform",
            object : FileConnector {
                override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult =
                    ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE)

                override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult =
                    ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE)

                override fun health(): ConnectorHealth {
                    healthCalls.incrementAndGet()
                    return ConnectorHealth(ConnectorHealthStatus.HEALTHY)
                }
            },
            policy(failureThreshold = 1),
            executor(),
            Clock.systemUTC(),
        )

        protected.sync(request())
        val health = protected.health()

        assertEquals(ConnectorHealthStatus.DEGRADED, health.status)
        assertTrue(health.message!!.contains("circuit is open"))
        assertEquals(0, healthCalls.get())
    }

    @Test
    fun `registry shares one circuit and rejects conflicting connector identities`() {
        val registry = ConnectorResilienceRegistry(policy(), executor(), Clock.systemUTC())
        val first = connector { ConnectorSyncResult(ConnectorSyncStatus.SUCCESS) }

        assertTrue(registry.protect("platform", first) === registry.protect("platform", first))
        assertTrue(registry.protect("platform-alias", first) === registry.protect("platform", first))
        assertFailsWith<IllegalArgumentException> {
            registry.protect("platform", connector { ConnectorSyncResult(ConnectorSyncStatus.SUCCESS) })
        }
    }

    @Test
    fun `does not open a downstream circuit when only the local invocation pool is saturated`() {
        val sharedExecutor = executor(maxConcurrentInvocations = 1, queueCapacity = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingInvocation = Thread {
            sharedExecutor.invoke(Duration.ofSeconds(5)) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val queuedInvocation = Thread {
            sharedExecutor.invoke(Duration.ofSeconds(5)) { Unit }
        }
        blockingInvocation.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            queuedInvocation.start()
            var waitAttempts = 0
            while (sharedExecutor.queuedInvocationCount() != 1 && waitAttempts++ < 100) {
                Thread.sleep(5)
            }
            assertEquals(1, sharedExecutor.queuedInvocationCount())

            val calls = AtomicInteger()
            val protected = ResilientFileConnector(
                "independent-platform",
                connector {
                    calls.incrementAndGet()
                    ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)
                },
                policy(failureThreshold = 1),
                sharedExecutor,
                Clock.systemUTC(),
            )

            val saturated = protected.sync(request())
            assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, saturated.status)
            assertTrue(saturated.message!!.contains("pool is saturated"))

            release.countDown()
            blockingInvocation.join(1_000)
            queuedInvocation.join(1_000)
            assertEquals(ConnectorSyncStatus.SUCCESS, protected.sync(request()).status)
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            blockingInvocation.join(1_000)
            queuedInvocation.join(1_000)
        }
    }

    @Test
    fun `logs the underlying failure and returns only the exception type to the caller`() {
        val logger = RecordingLogger()
        val failure = ConnectorSpecificException("sensitive downstream secret detail")
        val protected = ResilientFileConnector(
            "failing-platform",
            connector { throw failure },
            policy(),
            executor(),
            Clock.systemUTC(),
            logger,
        )

        val result = protected.sync(request())

        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, result.status)
        assertTrue(result.message!!.contains("invocation could not complete (ConnectorSpecificException)"))
        assertFalse(result.message!!.contains("sensitive downstream secret detail"))
        assertEquals(1, logger.errors.size)
        assertTrue(logger.errors[0].first.contains("failing-platform"))
        assertTrue(logger.errors[0].first.contains("sync"))
        assertTrue(logger.errors[0].second === failure)
    }

    @Test
    fun `stays silent when constructed without a logger`() {
        val protected = ResilientFileConnector(
            "quiet-platform",
            connector { throw ConnectorSpecificException("hidden detail") },
            policy(),
            executor(),
            Clock.systemUTC(),
        )

        val result = protected.sync(request())

        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, result.status)
        assertTrue(result.message!!.contains("invocation could not complete (ConnectorSpecificException)"))
        assertFalse(result.message!!.contains("hidden detail"))
    }

    @Test
    fun `logs circuit transitions while opening, probing, reopening, and closing`() {
        val clock = MutableClock(1_000)
        val logger = RecordingLogger()
        val calls = AtomicInteger()
        val protected = ResilientFileConnector(
            "transition-platform",
            connector {
                when (calls.incrementAndGet()) {
                    1, 2 -> ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE, message = "remote unavailable")
                    3 -> throw ConnectorSpecificException("probe failure detail")
                    else -> ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, externalId = "external-1")
                }
            },
            policy(failureThreshold = 2, circuitOpenDuration = Duration.ofMillis(100)),
            executor(),
            clock,
            logger,
        )

        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, protected.sync(request()).status)
        assertTrue(logger.warns.isEmpty())
        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, protected.sync(request()).status)
        assertEquals(1, logger.warns.count { it.contains("circuit opened") })
        assertTrue(logger.warns.single().contains("after 2 consecutive failures"))

        clock.advance(100)
        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, protected.sync(request()).status)
        assertEquals(1, logger.debugs.count { it.contains("recovery probe") })
        assertEquals(1, logger.errors.count { it.second is ConnectorSpecificException })
        assertEquals(1, logger.warns.count { it.contains("reopened") })
        assertTrue(logger.warns.last().contains("(ConnectorSpecificException)"))

        clock.advance(100)
        assertEquals(ConnectorSyncStatus.SUCCESS, protected.sync(request()).status)
        assertEquals(2, logger.debugs.count { it.contains("recovery probe") })
        assertEquals(1, logger.infos.count { it.contains("circuit closed") })
    }

    @Test
    fun `warns when the invocation pool rejects work`() {
        val sharedExecutor = executor(maxConcurrentInvocations = 1, queueCapacity = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingInvocation = Thread {
            sharedExecutor.invoke(Duration.ofSeconds(5)) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val queuedInvocation = Thread {
            sharedExecutor.invoke(Duration.ofSeconds(5)) { Unit }
        }
        blockingInvocation.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            queuedInvocation.start()
            var waitAttempts = 0
            while (sharedExecutor.queuedInvocationCount() != 1 && waitAttempts++ < 100) {
                Thread.sleep(5)
            }
            assertEquals(1, sharedExecutor.queuedInvocationCount())

            val logger = RecordingLogger()
            val protected = ResilientFileConnector(
                "saturated-platform",
                connector { ConnectorSyncResult(ConnectorSyncStatus.SUCCESS) },
                policy(failureThreshold = 1),
                sharedExecutor,
                Clock.systemUTC(),
                logger,
            )

            val saturated = protected.sync(request())

            assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, saturated.status)
            assertTrue(saturated.message!!.contains("pool is saturated"))
            assertEquals(1, logger.warns.count { it.contains("pool is saturated") })
            assertTrue(logger.warns.single().contains("saturated-platform"))
        } finally {
            release.countDown()
            blockingInvocation.join(1_000)
            queuedInvocation.join(1_000)
        }
    }

    @Test
    fun `logs the underlying failure when a health check cannot complete`() {
        val logger = RecordingLogger()
        val failure = ConnectorSpecificException("health secret detail")
        val protected = ResilientFileConnector(
            "unhealthy-platform",
            object : FileConnector {
                override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult =
                    ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)

                override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult =
                    ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)

                override fun health(): ConnectorHealth = throw failure
            },
            policy(),
            executor(),
            Clock.systemUTC(),
            logger,
        )

        val health = protected.health()

        assertEquals(ConnectorHealthStatus.UNHEALTHY, health.status)
        assertTrue(health.message!!.contains("could not complete (ConnectorSpecificException)"))
        assertFalse(health.message!!.contains("health secret detail"))
        assertEquals(1, logger.errors.size)
        assertTrue(logger.errors[0].second === failure)
    }

    @Test
    fun `registry wires the configured logger into protected connectors`() {
        val logger = RecordingLogger()
        val registry = ConnectorResilienceRegistry(policy(), executor(), Clock.systemUTC(), logger)
        val failure = ConnectorSpecificException("registry detail")
        val protected = registry.protect("registry-platform", connector { throw failure })

        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, protected.sync(request()).status)
        assertEquals(1, logger.errors.size)
        assertTrue(logger.errors[0].second === failure)
    }

    private class ConnectorSpecificException(message: String) : RuntimeException(message)

    private class RecordingLogger : FileWeftLogger {
        val infos = mutableListOf<String>()
        val warns = mutableListOf<String>()
        val debugs = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable?>>()

        override fun info(message: String, context: LogContext) {
            infos += message
        }

        override fun warn(message: String, context: LogContext) {
            warns += message
        }

        override fun error(message: String, throwable: Throwable?, context: LogContext) {
            errors += message to throwable
        }

        override fun debug(message: String, context: LogContext) {
            debugs += message
        }
    }

    private fun executor(
        maxConcurrentInvocations: Int = 2,
        queueCapacity: Int = 4,
    ) = ConnectorInvocationExecutor(maxConcurrentInvocations, queueCapacity).also(executors::add)

    private fun policy(
        timeout: Duration = Duration.ofMillis(100),
        failureThreshold: Int = 3,
        circuitOpenDuration: Duration = Duration.ofSeconds(1),
    ) = ConnectorResiliencePolicy(timeout, failureThreshold, circuitOpenDuration)

    private fun request(timeout: Duration = Duration.ofSeconds(1)) = ConnectorSyncRequest(
        tenantId = Identifier("tenant-1"),
        businessId = Identifier("document-1"),
        source = ConnectorFileSource(URI("https://storage.example.test/document-1"), "document.txt"),
        invocation = ConnectorInvocation("delivery-1", timeout),
    )

    private fun connector(sync: () -> ConnectorSyncResult): FileConnector = object : FileConnector {
        override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult = sync()

        override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult = sync()

        override fun health(): ConnectorHealth = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
    }

    private class MutableClock(initialMillis: Long) : Clock() {
        private var millis = initialMillis

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(millis)

        fun advance(millis: Long) {
            this.millis += millis
        }
    }
}
