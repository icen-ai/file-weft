package ai.icen.fw.dev.api.config

import ai.icen.fw.application.upload.ExpiredResumableUploadCleanupResult
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/** Development-only scheduler proving that abandoned multipart state is reclaimed across every tenant. */
class DevResumableUploadRunner(
    private val batchSize: Int,
    private val cleanup: (Int) -> ExpiredResumableUploadCleanupResult,
) {
    init {
        require(batchSize > 0) { "Development resumable upload cleanup batch size must be positive." }
    }

    @Scheduled(fixedDelayString = "\${fileweft.dev.upload.cleanup-fixed-delay-millis:60000}")
    fun cleanupExpired() {
        try {
            cleanup(batchSize)
        } catch (failure: Exception) {
            logger.warn("Development resumable upload cleanup failed; expired sessions remain visible for the next retry.", failure)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(DevResumableUploadRunner::class.java)
    }
}
