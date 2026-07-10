package com.fileweft.dev.api.config

import com.fileweft.application.task.TaskWorker
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/** Development-only scheduler for exercising durable task leasing and recovery. */
class DevTaskRunner(
    private val batchSize: Int,
    private val worker: TaskWorker,
) {
    init {
        require(batchSize > 0) { "Development task batch size must be positive." }
    }

    @Scheduled(fixedDelayString = "\${fileweft.dev.task.fixed-delay-millis:1000}")
    fun processAvailable() {
        try {
            worker.processAvailable(batchSize)
        } catch (failure: Exception) {
            logger.warn("Development task processing failed; expired leases and retryable tasks remain recoverable.", failure)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(DevTaskRunner::class.java)
    }
}
