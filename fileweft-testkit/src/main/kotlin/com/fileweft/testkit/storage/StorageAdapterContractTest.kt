package com.fileweft.testkit.storage

import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageUploadRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

abstract class StorageAdapterContractTest {
    protected abstract val storageAdapter: StorageAdapter

    protected abstract fun uploadRequest(): StorageUploadRequest

    protected open fun content(): ByteArray = "fileweft-storage-contract".toByteArray(Charsets.UTF_8)

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
}
