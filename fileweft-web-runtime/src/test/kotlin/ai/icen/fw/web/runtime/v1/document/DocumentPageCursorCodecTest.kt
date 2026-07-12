package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.document.DocumentPageCursor
import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentPageCursorCodecTest {
    private val codec = DocumentPageCursorCodec()

    @Test
    fun `round trips the versioned base64url sort keys without padding`() {
        val encoded = codec.encode(DocumentPageCursor(123456789, Identifier("文档-1")))

        val decoded = codec.decode(encoded)

        assertTrue(encoded.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(encoded.contains('='))
        assertEquals(123456789, decoded.updatedTime)
        assertEquals("文档-1", decoded.id.value)
    }

    @Test
    fun `rejects malformed unknown version negative time and oversized declared lengths without echoing input`() {
        val invalidInputs = listOf(
            "***",
            payload(version = 2, updatedTime = 1, id = "document-1"),
            payload(version = 1, updatedTime = -1, id = "document-1"),
            payload(version = 1, updatedTime = 1, id = "", declaredLength = 0xffff),
        )

        invalidInputs.forEach { encoded ->
            val failure = assertFailsWith<IllegalArgumentException> { codec.decode(encoded) }

            assertEquals("Invalid document page cursor.", failure.message)
            assertFalse(failure.message.orEmpty().contains(encoded))
        }
    }

    private fun payload(version: Int, updatedTime: Long, id: String, declaredLength: Int = -1): String {
        val idBytes = id.toByteArray(StandardCharsets.UTF_16BE)
        val length = if (declaredLength >= 0) declaredLength else idBytes.size
        val bytes = ByteBuffer.allocate(1 + Long.SIZE_BYTES + Short.SIZE_BYTES + idBytes.size)
            .put(version.toByte())
            .putLong(updatedTime)
            .putShort(length.toShort())
            .put(idBytes)
            .array()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
