package com.fileweft.starter.boot3

import com.fileweft.application.outbox.OutboxWorker
import com.fileweft.application.task.TaskWorker
import com.fileweft.application.upload.ResumableUploadService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.util.logging.Level
import java.util.logging.Logger

/** Opt-in polling role; deploy it separately from HTTP-serving application nodes. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "fileweft.worker", name = ["enabled"], havingValue = "true")
class FileWeftWorkerSchedulingConfiguration {
    @Bean
    fun fileWeftWorkerScheduler(
        properties: FileWeftProperties,
        outbox: ObjectProvider<OutboxWorker>,
        tasks: ObjectProvider<TaskWorker>,
        uploads: ObjectProvider<ResumableUploadService>,
    ): FileWeftWorkerScheduler = FileWeftWorkerScheduler(
        properties.worker,
        outbox.getIfAvailable()?.let { worker -> { worker.processAvailable(properties.worker.outboxBatchSize) } },
        tasks.getIfAvailable()?.let { worker -> { worker.processAvailable(properties.worker.taskBatchSize) } },
        uploads.getIfAvailable()?.let { service -> { service.cleanupExpired(properties.upload.resumableCleanupBatchSize) } },
    )
}

class FileWeftWorkerScheduler(
    private val properties: FileWeftProperties.WorkerProperties,
    private val processOutbox: (() -> Unit)?,
    private val processTasks: (() -> Unit)?,
    private val cleanupUploads: (() -> Unit)? = null,
) {
    init {
        require(properties.enabled) { "FileWeft worker scheduler must be enabled explicitly." }
        require(properties.fixedDelayMillis > 0) { "FileWeft worker fixed delay must be positive." }
        require(properties.outboxBatchSize > 0 && properties.taskBatchSize > 0) { "FileWeft worker batch sizes must be positive." }
        require(processOutbox != null || processTasks != null || cleanupUploads != null) {
            "FileWeft worker is enabled but no durable worker capability is available; configure a database-backed FileWeft runtime."
        }
    }

    @Scheduled(fixedDelayString = "\${fileweft.worker.fixed-delay-millis:1000}")
    fun processAvailable() {
        if (properties.processOutbox) runSafely("outbox", processOutbox)
        if (properties.processTasks) runSafely("task", processTasks)
        if (properties.processUploadCleanup) runSafely("resumable-upload-cleanup", cleanupUploads)
    }

    private fun runSafely(role: String, processor: (() -> Unit)?) {
        if (processor == null) return
        try {
            processor()
        } catch (failure: Exception) {
            logger.log(Level.SEVERE, "FileWeft $role worker cycle failed; durable work remains available for a later retry.", failure)
        }
    }

    private companion object {
        val logger = Logger.getLogger(FileWeftWorkerScheduler::class.java.name)
    }
}
