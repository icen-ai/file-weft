package ai.icen.fw.dev.api.config

import ai.icen.fw.application.upload.ExpiredResumableUploadCleanupResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DevResumableUploadRunnerTest {
    @Test
    fun `passes the configured cleanup batch size to the resilient cleanup action`() {
        var received = 0
        DevResumableUploadRunner(37) { limit ->
            received = limit
            ExpiredResumableUploadCleanupResult(inspected = 0, expired = 0, failed = 0)
        }.cleanupExpired()

        assertEquals(37, received)
    }

    @Test
    fun `rejects non-positive cleanup batch size`() {
        assertFailsWith<IllegalArgumentException> {
            DevResumableUploadRunner(0) { ExpiredResumableUploadCleanupResult(0, 0, 0) }
        }
    }
}
