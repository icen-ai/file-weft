package ai.icen.fw.spi.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StorageCapabilityModelsTest {
    @Test
    fun `range request rejects invalid arithmetic and revision text`() {
        val location = StorageObjectLocation("test", "objects/opaque")

        assertThrows(IllegalArgumentException::class.java) {
            StorageRangeRequest(location, -1, 1, "revision")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageRangeRequest(location, 0, 0, "revision")
        }
        assertThrows(ArithmeticException::class.java) {
            StorageRangeRequest(location, Long.MAX_VALUE, 2, "revision")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageRangeRequest(location, 0, 1, "revision\nunsafe")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageRangeRequest(location, 0, 1, "\uD800")
        }
    }

    @Test
    fun `metadata is bounded copied immutable and Unicode safe`() {
        val location = StorageObjectLocation("test", "objects/opaque")
        val mutable = linkedMapOf("classification" to "legal")
        val metadata = StorageObjectMetadata(
            location,
            7,
            "text/plain",
            "sha256:${"a".repeat(64)}",
            "revision-1",
            mutable,
            1_000,
        )
        mutable["classification"] = "changed"

        assertEquals("legal", metadata.metadata["classification"])
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (metadata.metadata as MutableMap<String, String>)["new"] = "value"
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageObjectMetadata(location, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageObjectMetadata(location, 1, lastModifiedTime = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageObjectMetadata(location, 1, revision = "\uD800")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageObjectMetadata(location, 1, metadata = mapOf("key" to "\uD800"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageObjectMetadata(
                StorageObjectLocation("test", "objects/\nunsafe"),
                1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageObjectMetadata(
                location,
                1,
                metadata = (1..129).associate { index -> "key-$index" to "value" },
            )
        }
    }
}
