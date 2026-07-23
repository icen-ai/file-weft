package ai.icen.fw.starter.boot2

import ai.icen.fw.application.outbox.OutboxBacklogMetricsPublisher
import ai.icen.fw.application.outbox.OutboxBacklogReader
import ai.icen.fw.application.outbox.OutboxBacklogSnapshot
import ai.icen.fw.application.outbox.OutboxEventLease
import ai.icen.fw.application.outbox.OutboxProcessingRepository
import ai.icen.fw.application.outbox.OutboxWorker
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.task.TaskWorker
import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.spi.observability.FileWeftGauge
import ai.icen.fw.spi.observability.FileWeftGaugeRecorder
import ai.icen.fw.spi.observability.FileWeftLogger
import ai.icen.fw.spi.observability.LogContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import kotlin.test.assertEquals

class OutboxBacklogWorkerSchedulingConfigurationTest {
    @Test
    fun `submits backlog observation to the dedicated executor instead of blocking polling`() {
        val transaction = DirectTransaction
        val outboxWorker = OutboxWorker(EmptyOutboxRepository, transaction, emptyList(), FIXED_CLOCK)
        var readerCalls = 0
        val publisher = OutboxBacklogMetricsPublisher(
            transaction = transaction,
            reader = object : OutboxBacklogReader {
                override fun snapshot(now: Long, legacyRunningBefore: Long): OutboxBacklogSnapshot {
                    readerCalls++
                    return OutboxBacklogSnapshot(0, 0, 0, 0, 0)
                }
            },
            gauges = NoOpGauges,
            clock = FIXED_CLOCK,
        )
        val executor = ManualExecutor()
        val factory = StaticListableBeanFactory().apply {
            addBean("outboxWorker", outboxWorker)
            addBean("outboxBacklogMetricsPublisher", publisher)
            addBean("outboxBacklogMetricsExecutor", executor)
        }
        val properties = FileWeftProperties().apply { worker.enabled = true }
        val scheduler = FileWeftWorkerSchedulingConfiguration().configuredFileWeftWorkerScheduler(
            properties = properties,
            outbox = factory.getBeanProvider(OutboxWorker::class.java),
            tasks = factory.getBeanProvider(TaskWorker::class.java),
            uploads = factory.getBeanProvider(ResumableUploadService::class.java),
            outboxBacklogMetrics = factory.getBeanProvider(OutboxBacklogMetricsPublisher::class.java),
            outboxBacklogMetricsExecutor = factory.getBeanProvider(Executor::class.java),
        )

        scheduler.processAvailable()
        scheduler.processAvailable()

        assertEquals(0, readerCalls, "Outbox polling must not wait for the aggregate JDBC read.")
        assertEquals(1, executor.pendingCount(), "A slow sample must not queue another observation task.")

        executor.runNext()

        assertEquals(1, readerCalls)
    }

    @Test
    fun `logs the resolved outbox worker configuration when the scheduler starts`() {
        val transaction = DirectTransaction
        val outboxWorker = OutboxWorker(
            EmptyOutboxRepository,
            transaction,
            emptyList(),
            FIXED_CLOCK,
            workerId = "worker-startup",
            leaseDuration = Duration.ofSeconds(60),
            legacyRunningGrace = Duration.ofMinutes(5),
        )
        val logger = RecordingLogger()
        val factory = StaticListableBeanFactory().apply {
            addBean("outboxWorker", outboxWorker)
        }
        val configuration = FileWeftWorkerSchedulingConfiguration()
        configuration.workerLogger = logger
        val properties = FileWeftProperties().apply { worker.enabled = true }

        configuration.configuredFileWeftWorkerScheduler(
            properties = properties,
            outbox = factory.getBeanProvider(OutboxWorker::class.java),
            tasks = factory.getBeanProvider(TaskWorker::class.java),
            uploads = factory.getBeanProvider(ResumableUploadService::class.java),
            outboxBacklogMetrics = factory.getBeanProvider(OutboxBacklogMetricsPublisher::class.java),
            outboxBacklogMetricsExecutor = factory.getBeanProvider(Executor::class.java),
        )

        assertEquals(
            listOf("Outbox worker started: workerId=worker-startup pollIntervalMillis=1000 batchSize=50 maxAttempts=5."),
            logger.infos,
        )
    }

    private class RecordingLogger : FileWeftLogger {
        val infos = mutableListOf<String>()

        override fun info(message: String, context: LogContext) {
            infos += message
        }

        override fun warn(message: String, context: LogContext) = Unit

        override fun error(message: String, throwable: Throwable?, context: LogContext) = Unit

        override fun debug(message: String, context: LogContext) = Unit
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private object EmptyOutboxRepository : OutboxProcessingRepository {
        override fun claimAvailable(limit: Int, now: Long): List<OutboxEventLease> = emptyList()
        override fun markSucceeded(lease: OutboxEventLease, completedAt: Long) = Unit
        override fun markForRetry(lease: OutboxEventLease, nextAttemptAt: Long, message: String, updatedAt: Long) = Unit
        override fun markFailed(lease: OutboxEventLease, message: String, updatedAt: Long) = Unit
    }

    private object NoOpGauges : FileWeftGaugeRecorder {
        override fun set(gauge: FileWeftGauge, value: Double, tags: Map<String, String>) = Unit
    }

    private class ManualExecutor : Executor {
        private val commands = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            commands += command
        }

        fun pendingCount(): Int = commands.size

        fun runNext() {
            check(commands.isNotEmpty()) { "No backlog observation task was submitted." }
            commands.removeAt(0).run()
        }
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(100_000), ZoneOffset.UTC)
    }
}
