package ai.icen.fw.starter.boot2

import ai.icen.fw.application.outbox.OutboxBacklogMetricsPublisher
import ai.icen.fw.application.outbox.OutboxWorker
import ai.icen.fw.application.task.TaskWorker
import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.PresignedUploadCleanupService
import ai.icen.fw.application.upload.PresignedUploadRecoveryService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/** Opt-in polling role; deploy it separately from HTTP-serving application nodes. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "fileweft.worker", name = ["enabled"], havingValue = "true")
class FileWeftWorkerSchedulingConfiguration {
    @Bean(name = ["fileWeftWorkerScheduler"])
    fun configuredFlowWeftWorkerScheduler(
        properties: FileWeftProperties,
        outbox: ObjectProvider<OutboxWorker>,
        tasks: ObjectProvider<TaskWorker>,
        uploads: ObjectProvider<ResumableUploadService>,
        presignedRecovery: ObjectProvider<PresignedUploadRecoveryService>,
        presignedCleanup: ObjectProvider<PresignedUploadCleanupService>,
        presignedMetrics: ObjectProvider<FlowWeftPresignedUploadMetricsPublisher>,
        outboxBacklogMetrics: ObjectProvider<OutboxBacklogMetricsPublisher>,
        @Qualifier(OUTBOX_BACKLOG_EXECUTOR_BEAN) outboxBacklogMetricsExecutor: ObjectProvider<Executor>,
    ): FileWeftWorkerScheduler = newWorkerScheduler(
        properties = properties,
        outbox = outbox.getIfAvailable(),
        tasks = tasks.getIfAvailable(),
        uploads = uploads.getIfAvailable(),
        presignedRecovery = presignedRecovery.getIfAvailable(),
        presignedCleanup = presignedCleanup.getIfAvailable(),
        presignedMetrics = presignedMetrics.getIfAvailable(),
        outboxBacklogMetrics = outboxBacklogMetrics.getIfAvailable(),
        outboxBacklogMetricsExecutor = outboxBacklogMetricsExecutor.getIfAvailable(),
    )

    /** Retains the earlier Spring factory signature for compiled and source-compatible hosts. */
    @Deprecated("Use the Spring-managed worker scheduler with presigned maintenance providers.")
    fun configuredFileWeftWorkerScheduler(
        properties: FileWeftProperties,
        outbox: ObjectProvider<OutboxWorker>,
        tasks: ObjectProvider<TaskWorker>,
        uploads: ObjectProvider<ResumableUploadService>,
        outboxBacklogMetrics: ObjectProvider<OutboxBacklogMetricsPublisher>,
        @Qualifier(OUTBOX_BACKLOG_EXECUTOR_BEAN) outboxBacklogMetricsExecutor: ObjectProvider<Executor>,
    ): FileWeftWorkerScheduler = newWorkerScheduler(
        properties = properties,
        outbox = outbox.getIfAvailable(),
        tasks = tasks.getIfAvailable(),
        uploads = uploads.getIfAvailable(),
        presignedRecovery = null,
        presignedCleanup = null,
        presignedMetrics = null,
        outboxBacklogMetrics = outboxBacklogMetrics.getIfAvailable(),
        outboxBacklogMetricsExecutor = outboxBacklogMetricsExecutor.getIfAvailable(),
    )

    /**
     * Retains the original public Kotlin/Java factory method ABI for hosts
     * that construct a scheduler directly instead of using Spring wiring.
     */
    @Deprecated("Use the Spring-managed worker scheduler to enable optional bounded observations.")
    fun fileWeftWorkerScheduler(
        properties: FileWeftProperties,
        outbox: ObjectProvider<OutboxWorker>,
        tasks: ObjectProvider<TaskWorker>,
        uploads: ObjectProvider<ResumableUploadService>,
    ): FileWeftWorkerScheduler = newWorkerScheduler(
        properties = properties,
        outbox = outbox.getIfAvailable(),
        tasks = tasks.getIfAvailable(),
        uploads = uploads.getIfAvailable(),
        presignedRecovery = null,
        presignedCleanup = null,
        presignedMetrics = null,
        outboxBacklogMetrics = null,
        outboxBacklogMetricsExecutor = null,
    )

    @Bean(name = [OUTBOX_BACKLOG_EXECUTOR_BEAN], destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = [OUTBOX_BACKLOG_EXECUTOR_BEAN])
    @ConditionalOnProperty(
        prefix = "fileweft.outbox",
        name = ["backlog-metrics-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun fileWeftOutboxBacklogMetricsExecutor(): ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue<Runnable>(),
        ThreadFactory { action ->
            Thread(action, "fileweft-outbox-backlog-observer").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private fun newWorkerScheduler(
        properties: FileWeftProperties,
        outbox: OutboxWorker?,
        tasks: TaskWorker?,
        uploads: ResumableUploadService?,
        presignedRecovery: PresignedUploadRecoveryService?,
        presignedCleanup: PresignedUploadCleanupService?,
        presignedMetrics: FlowWeftPresignedUploadMetricsPublisher?,
        outboxBacklogMetrics: OutboxBacklogMetricsPublisher?,
        outboxBacklogMetricsExecutor: Executor?,
    ): FileWeftWorkerScheduler = FileWeftWorkerScheduler(
        properties.worker,
        outbox?.let { worker ->
            {
                try {
                    worker.processAvailable(properties.worker.outboxBatchSize)
                } finally {
                    if (properties.outbox.backlogMetricsEnabled) {
                        outboxBacklogMetrics?.let { publisher ->
                            outboxBacklogMetricsExecutor?.let { executor -> publisher.publishIfDueAsync(executor) }
                        }
                    }
                }
            }
        },
        tasks?.let { worker -> { worker.processAvailable(properties.worker.taskBatchSize) } },
        if (uploads != null || presignedRecovery != null || presignedCleanup != null) {
            {
                if (properties.worker.processUploadCleanup) {
                    uploads?.cleanupExpired(properties.upload.resumableCleanupBatchSize)
                }
                if (properties.worker.processPresignedUploadMaintenance) {
                    runPresignedMaintenance(
                        properties.upload.presignedMaintenanceBatchSize,
                        presignedRecovery,
                        presignedCleanup,
                        presignedMetrics,
                    )
                }
            }
        } else {
            null
        },
    )

    private fun runPresignedMaintenance(
        limit: Int,
        recovery: PresignedUploadRecoveryService?,
        cleanup: PresignedUploadCleanupService?,
        metrics: FlowWeftPresignedUploadMetricsPublisher?,
    ) {
        var firstFailure: Exception? = null
        try {
            recovery?.recover(limit)
        } catch (failure: Exception) {
            firstFailure = failure
        }
        try {
            cleanup?.cleanup(limit)
        } catch (failure: Exception) {
            if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
        }
        try {
            metrics?.publish()
        } catch (_: Exception) {
            // Observability must not change durable maintenance semantics.
        }
        firstFailure?.let { throw it }
    }

    private companion object {
        const val OUTBOX_BACKLOG_EXECUTOR_BEAN = "fileWeftOutboxBacklogMetricsExecutor"
    }
}

class FileWeftWorkerScheduler(
    private val properties: FileWeftProperties.WorkerProperties,
    private val processOutbox: (() -> Unit)?,
    private val processTasks: (() -> Unit)?,
    private val cleanupUploads: (() -> Unit)? = null,
) {
    init {
        require(properties.enabled) { "FlowWeft worker scheduler must be enabled explicitly." }
        require(properties.fixedDelayMillis > 0) { "FlowWeft worker fixed delay must be positive." }
        require(properties.outboxBatchSize > 0 && properties.taskBatchSize > 0) { "FlowWeft worker batch sizes must be positive." }
        require(processOutbox != null || processTasks != null || cleanupUploads != null) {
            "FlowWeft worker is enabled but no durable worker capability is available; configure a database-backed FlowWeft runtime."
        }
    }

    @Scheduled(fixedDelayString = "\${fileweft.worker.fixed-delay-millis:1000}")
    fun processAvailable() {
        if (properties.processOutbox) runSafely("outbox", processOutbox)
        if (properties.processTasks) runSafely("task", processTasks)
        if (properties.processUploadCleanup || properties.processPresignedUploadMaintenance) {
            runSafely("upload-maintenance", cleanupUploads)
        }
    }

    private fun runSafely(role: String, processor: (() -> Unit)?) {
        if (processor == null) return
        try {
            processor()
        } catch (failure: Exception) {
            logger.log(Level.SEVERE, "FlowWeft $role worker cycle failed; durable work remains available for a later retry.", failure)
        }
    }

    private companion object {
        val logger = Logger.getLogger(FileWeftWorkerScheduler::class.java.name)
    }
}
