package ai.icen.fw.testkit.storage

import ai.icen.fw.spi.storage.ConditionalRangedStorageAdapter
import ai.icen.fw.spi.storage.StorageMetadataAdapter
import ai.icen.fw.spi.storage.StorageRangeRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/** Contract for HEAD-bound byte ranges that cannot mix object revisions. */
abstract class ConditionalRangedStorageAdapterContractTest : RangedStorageAdapterContractTest() {
    protected open val metadataStorageAdapter: StorageMetadataAdapter
        get() = storageAdapter as? StorageMetadataAdapter
            ?: error("Conditional range contract requires StorageMetadataAdapter.")

    protected open val conditionalRangedStorageAdapter: ConditionalRangedStorageAdapter
        get() = storageAdapter as? ConditionalRangedStorageAdapter
            ?: error("Conditional range contract requires ConditionalRangedStorageAdapter.")

    @Test
    fun `reads a range only from the authoritative head revision`() {
        val expected = content()
        require(expected.size >= 3) { "Conditional range contract content must contain at least three bytes." }
        val request = requestForContent(uploadRequest(), expected)
        val stored = storageAdapter.upload(request, ByteArrayInputStream(expected))
        try {
            val metadata = metadataStorageAdapter.metadata(stored.location)
            val revision = requireNotNull(metadata.revision) {
                "Conditional range metadata must expose an opaque revision token."
            }
            assertEquals(stored.location, metadata.location)
            assertEquals(expected.size.toLong(), metadata.contentLength)
            request.metadata.forEach { (key, value) -> assertEquals(value, metadata.metadata[key]) }

            val download = conditionalRangedStorageAdapter.downloadRange(
                StorageRangeRequest(stored.location, 1, expected.size.toLong() - 2, revision),
            )
            assertEquals(expected.size.toLong() - 2, download.contentLength)
            download.content.use { content ->
                assertArrayEquals(expected.copyOfRange(1, expected.lastIndex), content.readBytes())
            }
        } finally {
            storageAdapter.delete(stored.location)
        }
    }

    @Test
    fun `rejects a revision issued for a different object`() {
        val firstContent = content()
        val secondContent = replacementContent()
        require(firstContent.isNotEmpty()) { "Conditional range contract content must not be empty." }
        require(!firstContent.contentEquals(secondContent)) {
            "Conditional range contract replacement content must differ from the original content."
        }
        val first = storageAdapter.upload(
            requestForContent(uploadRequest(), firstContent),
            ByteArrayInputStream(firstContent),
        )
        var secondLocation: ai.icen.fw.spi.storage.StorageObjectLocation? = null
        try {
            val second = storageAdapter.upload(
                requestForContent(uploadRequest(), secondContent),
                ByteArrayInputStream(secondContent),
            )
            secondLocation = second.location
            val firstRevision = requireNotNull(metadataStorageAdapter.metadata(first.location).revision)
            val secondRevision = requireNotNull(metadataStorageAdapter.metadata(second.location).revision)
            assertNotEquals(firstRevision, secondRevision) {
                "Conditional range contract objects must expose distinguishable revisions."
            }

            assertThrows(RuntimeException::class.java) {
                conditionalRangedStorageAdapter.downloadRange(
                    StorageRangeRequest(first.location, 0, 1, secondRevision),
                )
            }
        } finally {
            storageAdapter.delete(first.location)
            secondLocation?.let(storageAdapter::delete)
        }
    }
}
