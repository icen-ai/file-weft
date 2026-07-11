package com.fileweft.testkit.storage

import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageUploadRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

abstract class StorageAdapterContractTest {
    protected abstract val storageAdapter: StorageAdapter

    protected abstract fun uploadRequest(): StorageUploadRequest

    protected open fun content(): ByteArray = "fileweft-storage-contract".toByteArray(Charsets.UTF_8)

    /** Override when an adapter needs a provider-specific non-final multipart size. */
    protected open fun multipartParts(): List<ByteArray> = listOf(content())

    protected open fun multipartRequest(parts: List<ByteArray>): StorageUploadRequest = uploadRequest().copy(
        contentLength = parts.fold(0L) { total, part -> Math.addExact(total, part.size.toLong()) },
    )

    @Test
    fun `uploads downloads checks and deletes an object`() {
        val content = content()
        val storedObject = storageAdapter.upload(uploadRequest(), ByteArrayInputStream(content))

        assertTrue(storageAdapter.exists(storedObject.location))
        storageAdapter.download(storedObject.location).content.use { downloaded ->
            assertArrayEquals(content, downloaded.readBytes())
        }

        storageAdapter.delete(storedObject.location)
        assertFalse(storageAdapter.exists(storedObject.location))
    }

    @Test
    fun `completes a durable multipart upload and returns the full object`() {
        val parts = multipartParts()
        require(parts.isNotEmpty()) { "Storage contract multipart parts must not be empty." }
        val request = multipartRequest(parts)
        val upload = storageAdapter.beginMultipartUpload(request)
        val acknowledged = parts.mapIndexed { index, part ->
            storageAdapter.uploadPart(upload, index + 1, ByteArrayInputStream(part), part.size.toLong())
        }

        val stored = storageAdapter.completeMultipartUpload(upload, acknowledged)
        val expected = parts.fold(ByteArray(0)) { combined, part -> combined + part }

        assertEquals(expected.size.toLong(), stored.contentLength)
        storageAdapter.download(stored.location).content.use { downloaded ->
            assertArrayEquals(expected, downloaded.readBytes())
        }
        storageAdapter.delete(stored.location)
    }

    @Test
    fun `aborts a multipart upload idempotently without publishing a final object`() {
        val parts = multipartParts()
        require(parts.isNotEmpty()) { "Storage contract multipart parts must not be empty." }
        val upload = storageAdapter.beginMultipartUpload(multipartRequest(parts))
        storageAdapter.uploadPart(upload, 1, ByteArrayInputStream(parts.first()), parts.first().size.toLong())

        storageAdapter.abortMultipartUpload(upload)
        storageAdapter.abortMultipartUpload(upload)

        assertFalse(storageAdapter.exists(upload.location))
    }
}
