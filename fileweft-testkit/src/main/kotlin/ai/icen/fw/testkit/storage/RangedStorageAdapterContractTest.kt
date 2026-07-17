package ai.icen.fw.testkit.storage

import ai.icen.fw.spi.storage.RangedStorageAdapter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/** Reusable contract for the optional provider-enforced range capability. */
abstract class RangedStorageAdapterContractTest : StorageAdapterContractTest() {
    protected open val rangedStorageAdapter: RangedStorageAdapter
        get() = storageAdapter as? RangedStorageAdapter
            ?: error("Range contract requires a StorageAdapter that also implements RangedStorageAdapter.")

    @Test
    fun `downloads exactly one bounded middle range`() {
        val expected = content()
        require(expected.size >= MINIMUM_FIXTURE_SIZE) {
            "Range contract content must contain at least $MINIMUM_FIXTURE_SIZE bytes."
        }
        val stored = storageAdapter.upload(
            requestForContent(uploadRequest(), expected),
            ByteArrayInputStream(expected),
        )
        try {
            val offset = 1L
            val length = expected.size.toLong() - 2
            val download = rangedStorageAdapter.downloadRange(stored.location, offset, length)

            assertEquals(length, download.contentLength)
            download.content.use { content ->
                assertArrayEquals(expected.copyOfRange(1, expected.lastIndex), content.readBytes())
            }
        } finally {
            storageAdapter.delete(stored.location)
        }
    }

    @Test
    fun `clips a range only at the object end`() {
        val expected = content()
        require(expected.size >= MINIMUM_FIXTURE_SIZE) {
            "Range contract content must contain at least $MINIMUM_FIXTURE_SIZE bytes."
        }
        val stored = storageAdapter.upload(
            requestForContent(uploadRequest(), expected),
            ByteArrayInputStream(expected),
        )
        try {
            val offset = expected.size.toLong() - 2
            val download = rangedStorageAdapter.downloadRange(stored.location, offset, REQUESTED_SUFFIX_LENGTH)

            assertEquals(2L, download.contentLength)
            download.content.use { content ->
                assertArrayEquals(expected.copyOfRange(expected.size - 2, expected.size), content.readBytes())
            }
        } finally {
            storageAdapter.delete(stored.location)
        }
    }

    @Test
    fun `rejects a range that starts beyond the object end`() {
        val expected = content()
        require(expected.isNotEmpty()) { "Range contract content must not be empty." }
        val stored = storageAdapter.upload(
            requestForContent(uploadRequest(), expected),
            ByteArrayInputStream(expected),
        )
        try {
            assertThrows(RuntimeException::class.java) {
                rangedStorageAdapter.downloadRange(stored.location, -1, 1)
            }
            assertThrows(RuntimeException::class.java) {
                rangedStorageAdapter.downloadRange(stored.location, 0, 0)
            }
            assertThrows(RuntimeException::class.java) {
                rangedStorageAdapter.downloadRange(stored.location, Long.MAX_VALUE, 2)
            }
            assertThrows(RuntimeException::class.java) {
                rangedStorageAdapter.downloadRange(stored.location, expected.size.toLong(), 1)
            }
        } finally {
            storageAdapter.delete(stored.location)
        }
    }

    private companion object {
        const val MINIMUM_FIXTURE_SIZE = 3
        const val REQUESTED_SUFFIX_LENGTH = 10L
    }
}
