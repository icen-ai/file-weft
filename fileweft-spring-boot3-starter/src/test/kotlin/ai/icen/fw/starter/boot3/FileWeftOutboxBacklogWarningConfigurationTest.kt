package ai.icen.fw.starter.boot3

import ai.icen.fw.application.outbox.OutboxBacklogReader
import ai.icen.fw.application.outbox.OutboxBacklogSnapshot
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.spi.observability.FileWeftLogger
import ai.icen.fw.spi.observability.LogContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileWeftOutboxBacklogWarningConfigurationTest {
    @Test
    fun `warns only when the pending count rises above zero or changes`() {
        var snapshot = snapshot(ready = 2, delayed = 1)
        val logger = RecordingLogger()
        val scheduler = scheduler(reader { snapshot }, logger)

        scheduler.warnIfBacklogWithoutWorker()
        scheduler.warnIfBacklogWithoutWorker()

        assertEquals(
            listOf("3 outbox event(s) pending but no outbox worker is enabled in this process; deploy the worker to process deliveries."),
            logger.warnings,
            "A stable backlog warns once per value, not once per sample.",
        )

        snapshot = snapshot(ready = 5)
        scheduler.warnIfBacklogWithoutWorker()

        assertEquals(2, logger.warnings.size)
        assertTrue(logger.warnings.last().startsWith("5 outbox event(s) pending"))

        snapshot = snapshot()
        scheduler.warnIfBacklogWithoutWorker()

        assertEquals(2, logger.warnings.size, "A drained backlog only re-arms the warning.")

        snapshot = snapshot(delayed = 1)
        scheduler.warnIfBacklogWithoutWorker()

        assertEquals(3, logger.warnings.size)
        assertTrue(logger.warnings.last().startsWith("1 outbox event(s) pending"))
    }

    @Test
    fun `stays silent when only running expired or failed events remain`() {
        val logger = RecordingLogger()
        val scheduler = scheduler(reader { snapshot(running = 4, expired = 2, failed = 9) }, logger)

        scheduler.warnIfBacklogWithoutWorker()

        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun `does nothing without a backlog reader`() {
        val logger = RecordingLogger()
        val scheduler = OutboxBacklogWarningScheduler(
            reader = null,
            transaction = DirectTransaction,
            clock = FIXED_CLOCK,
            legacyRunningGrace = Duration.ofMinutes(5),
            logger = logger,
        )

        scheduler.warnIfBacklogWithoutWorker()

        assertTrue(logger.warnings.isEmpty())
        assertTrue(logger.debugs.isEmpty())
    }

    @Test
    fun `contains observation failures without touching the throttled state`() {
        var failure: Exception? = IllegalStateException("database down")
        val logger = RecordingLogger()
        val scheduler = scheduler(
            reader {
                failure?.let { throw it }
                snapshot(ready = 2)
            },
            logger,
        )

        scheduler.warnIfBacklogWithoutWorker()

        assertTrue(logger.warnings.isEmpty())
        assertEquals(1, logger.debugs.size)

        failure = null
        scheduler.warnIfBacklogWithoutWorker()

        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings.single().startsWith("2 outbox event(s) pending"))
    }

    @Test
    fun `wires the scheduler bean from the auto-configuration properties`() {
        val reader = reader { snapshot(ready = 1) }
        val factory = StaticListableBeanFactory().apply {
            addBean("outboxBacklogReader", reader)
        }

        val scheduler = FileWeftOutboxBacklogWarningConfiguration().fileWeftOutboxBacklogWarningScheduler(
            properties = FileWeftProperties(),
            transaction = DirectTransaction,
            reader = factory.getBeanProvider(OutboxBacklogReader::class.java),
            clock = FIXED_CLOCK,
        )

        assertSame(reader, privateField(scheduler, "reader"))
        assertSame(DirectTransaction, privateField(scheduler, "transaction"))
        assertSame(FIXED_CLOCK, privateField(scheduler, "clock"))
        assertEquals(300_000L, privateField(scheduler, "legacyRunningGraceMillis"))
    }

    private fun scheduler(reader: OutboxBacklogReader, logger: RecordingLogger): OutboxBacklogWarningScheduler =
        OutboxBacklogWarningScheduler(
            reader = reader,
            transaction = DirectTransaction,
            clock = FIXED_CLOCK,
            legacyRunningGrace = Duration.ofMinutes(5),
            logger = logger,
        )

    private fun reader(read: () -> OutboxBacklogSnapshot): OutboxBacklogReader = object : OutboxBacklogReader {
        override fun snapshot(now: Long, legacyRunningBefore: Long): OutboxBacklogSnapshot = read()
    }

    private fun snapshot(
        ready: Long = 0,
        delayed: Long = 0,
        running: Long = 0,
        expired: Long = 0,
        failed: Long = 0,
    ): OutboxBacklogSnapshot = OutboxBacklogSnapshot(
        readyCount = ready,
        delayedCount = delayed,
        runningCount = running,
        expiredCount = expired,
        failedCount = failed,
        oldestReadyCreatedTime = if (ready > 0) 100_000L else null,
    )

    private fun privateField(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }
        .get(target)

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private class RecordingLogger : FileWeftLogger {
        val warnings = mutableListOf<String>()
        val debugs = mutableListOf<String>()

        override fun info(message: String, context: LogContext) = Unit

        override fun warn(message: String, context: LogContext) {
            warnings += message
        }

        override fun error(message: String, throwable: Throwable?, context: LogContext) = Unit

        override fun debug(message: String, context: LogContext) {
            debugs += message
        }
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(400_000), ZoneOffset.UTC)
    }
}
