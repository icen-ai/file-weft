package ai.icen.fw.application.upload

import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ResumableUploadSessionBinaryCompatibilityTest {
    @Test
    fun `Kotlin consumer compiled against released default constructor runs with current class`() {
        val consumer = Class.forName(
            "ai.icen.fw.application.upload.compatibility.ReleasedResumableUploadSessionConsumer",
        )
        val session = try {
            consumer.getMethod("constructUsingReleasedDefaults").invoke(null) as ResumableUploadSession
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }

        assertSame(ResumableUploadSession::class.java, session.javaClass)
        assertEquals("released-session", session.id.value)
        assertEquals("released-key", session.idempotencyKey)
        assertEquals(ResumableUploadSessionStatus.ACTIVE, session.status)
        assertNull(session.contentType)
        assertNull(session.expectedContentHash)
        assertEquals(emptyMap(), session.metadata)
        assertNull(session.lastError)
        assertNull(session.completedAt)
        assertNull(session.ownerId)
    }
}
