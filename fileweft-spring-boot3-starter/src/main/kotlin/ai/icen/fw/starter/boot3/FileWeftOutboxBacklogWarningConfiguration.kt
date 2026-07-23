package ai.icen.fw.starter.boot3

import ai.icen.fw.adapter.logging.Slf4jFileWeftLogger
import ai.icen.fw.application.outbox.OutboxBacklogReader
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.spi.observability.FileWeftLogger
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

/**
 * HTTP-serving role companion for [FileWeftWorkerSchedulingConfiguration].
 * When the worker role is not enabled in this process, nothing drains the
 * durable Outbox locally; a forgotten worker deployment would otherwise stay
 * invisible until downstream systems report missing deliveries.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBean(DataSource::class)
@ConditionalOnProperty(prefix = "fileweft.worker", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class FileWeftOutboxBacklogWarningConfiguration {
    @Bean(name = ["fileWeftOutboxBacklogWarningScheduler"])
    fun fileWeftOutboxBacklogWarningScheduler(
        properties: FileWeftProperties,
        transaction: ApplicationTransaction,
        reader: ObjectProvider<OutboxBacklogReader>,
        clock: Clock,
    ): OutboxBacklogWarningScheduler = OutboxBacklogWarningScheduler(
        reader = reader.getIfAvailable(),
        transaction = transaction,
        clock = clock,
        legacyRunningGrace = Duration.ofMillis(properties.outbox.legacyRunningGraceMillis),
    )
}

/**
 * Samples the durable Outbox backlog and warns while events wait for a worker
 * this process does not run. The warning is throttled: it is emitted when the
 * pending count rises above zero or changes while positive, and it re-arms
 * after the backlog drains, so one sampling interval produces at most one
 * line. Gauge publishing is deliberately not involved; a missing metrics
 * backend must not silence the warning.
 */
class OutboxBacklogWarningScheduler(
    private val reader: OutboxBacklogReader?,
    private val transaction: ApplicationTransaction,
    private val clock: Clock,
    legacyRunningGrace: Duration,
    private val logger: FileWeftLogger = Slf4jFileWeftLogger(OutboxBacklogWarningScheduler::class.java.name),
) {
    private val legacyRunningGraceMillis = legacyRunningGrace.toMillis().also {
        require(!legacyRunningGrace.isNegative) { "Outbox backlog warning legacy running grace must not be negative." }
    }

    private val lastWarnedPendingCount = AtomicLong(0)

    @Scheduled(
        fixedDelayString = "\${fileweft.outbox.backlog-metrics-interval-millis:30000}",
        initialDelayString = "\${fileweft.outbox.backlog-metrics-interval-millis:30000}",
    )
    fun warnIfBacklogWithoutWorker() {
        val currentReader = reader ?: return
        val now = try {
            clock.millis()
        } catch (_: Exception) {
            return
        }
        if (now < 0) return
        val snapshot = try {
            transaction.execute { currentReader.snapshot(now, safeSubtract(now, legacyRunningGraceMillis)) }
        } catch (_: Exception) {
            // A failed observation must not escalate: the next interval retries.
            logger.debug("Outbox backlog observation failed; the next scheduled sample retries.")
            return
        }
        val pending = snapshot.readyCount + snapshot.delayedCount
        if (pending <= 0) {
            lastWarnedPendingCount.set(0)
            return
        }
        if (lastWarnedPendingCount.getAndSet(pending) != pending) {
            logger.warn(
                "$pending outbox event(s) pending but no outbox worker is enabled in this process; " +
                    "deploy the worker to process deliveries.",
            )
        }
    }

    private fun safeSubtract(value: Long, decrement: Long): Long =
        if (value < decrement) 0 else value - decrement
}
