package ai.icen.fw.spi.storage

import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class PresignedUploadStorageAdapterTest {
    @Test
    fun `copies authority maps and redacts checksum rendering`() {
        val mutable = linkedMapOf("classification" to "legal")
        val request = request(mutable)
        mutable["classification"] = "changed"

        assertEquals("legal", request.metadata["classification"])
        assertFalse(request.checksum.toString().contains(request.checksum.value))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (request.metadata as MutableMap<String, String>)["new"] = "value"
        }
    }

    @Test
    fun `rejects unsafe URL headers checksum and expiration`() {
        val location = StorageObjectLocation("test", "objects/key")
        assertThrows(IllegalArgumentException::class.java) {
            PresignedUploadGrant(
                location,
                URI.create("http://storage.example/object?signature=x"),
                emptyMap(),
                1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PresignedUploadGrant(
                location,
                URI.create("https://storage.example/object?signature=x"),
                mapOf("Authorization" to "secret"),
                1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PresignedUploadGrant(
                location,
                URI.create("https://storage.example/object?signature=x"),
                linkedMapOf("Content-Type" to "text/plain", "content-type" to "application/json"),
                1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PresignedUploadGrant(
                location,
                URI.create("https://storage.example/object?signature=x"),
                mapOf("bad header" to "value"),
                1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageContentChecksum("md5\n", "value")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageContentChecksum("md5", "\uD800")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageContentChecksum("sha 256", "value")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageContentChecksum("sha-256", "value with whitespace")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PresignedUploadGrantRequest(
                Identifier("binding"),
                Identifier("tenant"),
                "file",
                1,
                "text/plain",
                "sha256:value",
                StorageContentChecksum("md5", "value"),
                expiresIn = Duration.ZERO,
            )
        }
    }

    @Test
    fun `finalization distinguishes staging authority from immutable stored location`() {
        val tenantId = Identifier("tenant")
        val bindingId = Identifier("binding")
        val source = StorageObjectLocation("test", "staging/key")
        val bound = StorageObjectLocation("test", "bound/key/version")
        val checksum = StorageContentChecksum("md5", "CY9rzUYh03PK3k6DJie09g==")
        val finalization = PresignedUploadFinalization(
            tenantId,
            bindingId,
            source,
            StoredObject(bound, 7, "text/plain", "sha256:${"a".repeat(64)}"),
            "provider-version",
            checksum,
            mapOf("classification" to "legal"),
        )

        assertEquals(tenantId, finalization.tenantId)
        assertEquals(bindingId, finalization.bindingId)
        assertEquals(source, finalization.sourceLocation)
        assertEquals(bound, finalization.storedObject.location)
        assertThrows(IllegalArgumentException::class.java) {
            PresignedUploadFinalization(
                tenantId,
                bindingId,
                source,
                StoredObject(source, 7, "text/plain", "sha256:${"a".repeat(64)}"),
                "provider-version",
                checksum,
                emptyMap(),
            )
        }
    }

    @Test
    fun `reissue binds the exact location headers and absolute deadline`() {
        val original = request(mapOf("classification" to "legal"))
        val location = StorageObjectLocation("test", "staging/key")
        val headers = linkedMapOf("Content-Type" to "text/plain")
        val reissue = PresignedUploadReissueRequest(
            original.bindingId,
            original.tenantId,
            location,
            original.contentLength,
            original.contentType,
            original.contentHash,
            original.checksum,
            original.metadata,
            headers,
            1_000,
        )
        headers["Content-Type"] = "changed"

        assertEquals(location, reissue.location)
        assertEquals("text/plain", reissue.requiredHeaders["Content-Type"])
        assertEquals(1_000L, reissue.expiresAt)
        assertEquals(location, PresignedUploadCleanupRequest(original.bindingId, original.tenantId, location).location)
    }

    @Test
    fun `rejects unsafe locations and incomplete or cross-adapter finalization evidence`() {
        val tenantId = Identifier("tenant")
        val bindingId = Identifier("binding")
        val source = StorageObjectLocation("test", "staging/key")
        val checksum = StorageContentChecksum("md5", "CY9rzUYh03PK3k6DJie09g==")

        assertThrows(IllegalArgumentException::class.java) {
            PresignedUploadCleanupRequest(
                bindingId,
                tenantId,
                StorageObjectLocation("test", "staging/\nkey"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PresignedUploadFinalization(
                tenantId,
                bindingId,
                source,
                StoredObject(
                    StorageObjectLocation("other", "bound/key/version"),
                    7,
                    "text/plain",
                    "sha256:${"a".repeat(64)}",
                ),
                "provider-version",
                checksum,
                emptyMap(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PresignedUploadFinalization(
                tenantId,
                bindingId,
                source,
                StoredObject(StorageObjectLocation("test", "bound/key/version"), 7),
                "provider-version",
                checksum,
                emptyMap(),
            )
        }
    }

    private fun request(metadata: Map<String, String>) = PresignedUploadGrantRequest(
        Identifier("binding"),
        Identifier("tenant"),
        "file",
        7,
        "text/plain",
        "sha256:239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
        StorageContentChecksum("md5", "CY9rzUYh03PK3k6DJie09g=="),
        metadata,
    )
}
