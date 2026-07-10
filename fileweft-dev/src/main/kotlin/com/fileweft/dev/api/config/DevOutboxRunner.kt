package com.fileweft.dev.api.config

import com.fileweft.application.outbox.OutboxWorker
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/** Schedules the worker only in the development host; the reusable starter remains passive. */
class DevOutboxRunner(
    private val fixedDelayMillis: Long,
    private val batchSize: Int,
    private val worker: OutboxWorker,
) {
    init {
        require(fixedDelayMillis > 0) { "Development outbox delay must be positive." }
        require(batchSize > 0) { "Development outbox batch size must be positive." }
    }

    @Scheduled(fixedDelayString = "\${fileweft.dev.outbox.fixed-delay-millis:1000}")
    fun processAvailable() {
        try {
            worker.processAvailable(batchSize)
        } catch (failure: Exception) {
            logger.warn("Development outbox processing failed; the next scheduled run will retry eligible events.", failure)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(DevOutboxRunner::class.java)
    }
}
