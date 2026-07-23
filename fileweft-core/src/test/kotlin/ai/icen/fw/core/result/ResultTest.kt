package ai.icen.fw.core.result

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
class ResultTest {
    @Test
    fun `success contains a value and no error`() {
        val result = Result.success("file-1")

        assertTrue(result.isSuccess())
        assertFalse(result.isFailure())
        assertEquals("file-1", result.value)
        assertNull(result.error)
        assertEquals("file-1", result.getOrThrow())
    }

    @Test
    fun `failure contains an immutable error detail`() {
        val sourceAttributes = linkedMapOf("documentId" to "doc-1")
        val detail = ErrorDetail(ErrorCode.CONFLICT, "Document already exists", sourceAttributes)
        val result = Result.failure<String>(detail)
        sourceAttributes["documentId"] = "doc-2"

        assertTrue(result.isFailure())
        assertFalse(result.isSuccess())
        assertNull(result.value)
        assertEquals("doc-1", result.error?.attributes?.get("documentId"))
        assertThrows<FileWeftException> {
            result.getOrThrow()
        }
    }
}
