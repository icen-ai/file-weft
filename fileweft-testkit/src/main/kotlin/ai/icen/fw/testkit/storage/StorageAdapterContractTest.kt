package ai.icen.fw.testkit.storage

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.MultipartCompletionRejectedException
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Duration

/**
 * Reusable contract for a tenant-safe [StorageAdapter]. The suite uses opaque
 * object locations only, so an adapter may use its own provider-specific key
 * scheme. Override the multipart hooks when a provider imposes a minimum part
 * size or other upload constraint.
 */
abstract class StorageAdapterContractTest {
    protected abstract val storageAdapter: StorageAdapter

    /** Returns a valid request for one unique contract test object. */
    protected abstract fun uploadRequest(): StorageUploadRequest

    protected open fun content(): ByteArray = "fileweft-storage-contract".toByteArray(Charsets.UTF_8)

    protected open fun replacementContent(): ByteArray = "fileweft-storage-contract-version-2".toByteArray(Charsets.UTF_8)

    /** Override when an adapter needs a provider-specific non-final multipart size. */
    protected open fun multipartParts(): List<ByteArray> = listOf(content())

    /** Replaces the first part during a retry while retaining its provider-valid size. */
    protected open fun replacementMultipartPart(original: ByteArray): ByteArray {
        require(original.isNotEmpty()) {
            "Storage contract multipart retry needs a non-empty first part; override multipartParts for this provider."
        }
        return original.copyOf().also { copy -> copy[0] = (copy[0].toInt() xor 0x5a).toByte() }
    }

    /** Override when a provider has a lower or upper bound for signed URL lifetimes. */
    protected open fun accessUrlExpiresIn(): Duration = Duration.ofMinutes(1)

    /** Override when the integration uses a constrained tenant identifier format. */
    protected open fun secondaryTenantId(primaryTenantId: Identifier): Identifier =
        Identifier("${primaryTenantId.value}-contract-secondary")

    protected open fun requestForContent(request: StorageUploadRequest, content: ByteArray): StorageUploadRequest = request.copy(
        contentLength = content.size.toLong(),
        // A contract fixture may replace bytes while testing storage isolation.
        // Hash verification is an application-level opt-in and must not make a
        // generic adapter fixture invalid.
        contentHash = null,
    )

    protected open fun multipartRequest(parts: List<ByteArray>): StorageUploadRequest = requestForContent(
        uploadRequest(),
        concatenate(parts),
    )

    @Test
    fun `uploads downloads reports metadata and deletes an object idempotently`() {
        val expected = content()
        val request = requestForContent(uploadRequest(), expected)
        var stored: StoredObject? = null
        try {
            stored = storageAdapter.upload(request, ByteArrayInputStream(expected))

            assertStoredContent(request, stored, expected)
            storageAdapter.delete(stored.location)
            storageAdapter.delete(stored.location)
            assertFalse(storageAdapter.exists(stored.location))
        } finally {
            cleanupLocations(stored?.location)
        }
    }

    @Test
    fun `keeps same tenant same name uploads as independently readable versions`() {
        val firstContent = content()
        val secondContent = replacementContent()
        require(!firstContent.contentEquals(secondContent)) { "Storage contract replacement content must differ from original content." }
        val firstRequest = requestForContent(uploadRequest(), firstContent)
        val secondRequest = requestForContent(firstRequest, secondContent)
        var firstStored: StoredObject? = null
        var secondStored: StoredObject? = null
        try {
            firstStored = storageAdapter.upload(firstRequest, ByteArrayInputStream(firstContent))
            secondStored = storageAdapter.upload(secondRequest, ByteArrayInputStream(secondContent))

            assertEquals(firstRequest.objectName, secondRequest.objectName)
            assertEquals(firstRequest.tenantId, secondRequest.tenantId)
            assertNotEquals(firstStored.location, secondStored.location, "A newer version must not overwrite the previous object.")
            assertStoredContent(firstRequest, firstStored, firstContent)
            assertStoredContent(secondRequest, secondStored, secondContent)
        } finally {
            cleanupLocations(firstStored?.location, secondStored?.location)
        }
    }

    @Test
    fun `isolates identical object names across tenants`() {
        val firstContent = content()
        val secondContent = replacementContent()
        val firstRequest = requestForContent(uploadRequest(), firstContent)
        val secondaryTenant = secondaryTenantId(firstRequest.tenantId)
        require(secondaryTenant != firstRequest.tenantId) { "Storage contract secondary tenant must differ from the primary tenant." }
        val secondRequest = requestForContent(firstRequest.copy(tenantId = secondaryTenant), secondContent)
        var firstStored: StoredObject? = null
        var secondStored: StoredObject? = null
        try {
            firstStored = storageAdapter.upload(firstRequest, ByteArrayInputStream(firstContent))
            secondStored = storageAdapter.upload(secondRequest, ByteArrayInputStream(secondContent))

            assertEquals(firstRequest.objectName, secondRequest.objectName)
            assertNotEquals(firstStored.location, secondStored.location, "Tenant-scoped objects must not share a location.")
            assertStoredContent(firstRequest, firstStored, firstContent)
            assertStoredContent(secondRequest, secondStored, secondContent)
        } finally {
            cleanupLocations(firstStored?.location, secondStored?.location)
        }
    }

    @Test
    fun `creates an absolute access url for a positive lifetime`() {
        val expected = content()
        val request = requestForContent(uploadRequest(), expected)
        var stored: StoredObject? = null
        try {
            stored = storageAdapter.upload(request, ByteArrayInputStream(expected))
            val expiresIn = accessUrlExpiresIn()
            require(!expiresIn.isNegative && !expiresIn.isZero) { "Storage contract access URL lifetime must be positive." }

            val accessUrl = storageAdapter.accessUrl(stored.location, expiresIn)

            assertTrue(accessUrl.isAbsolute, "Storage access URL must be absolute.")
            assertTrue(!accessUrl.scheme.isNullOrBlank(), "Storage access URL must contain a scheme.")
        } finally {
            cleanupLocations(stored?.location)
        }
    }

    @Test
    fun `retries a multipart part and completes the latest acknowledged content at the upload location`() {
        val parts = multipartParts()
        require(parts.isNotEmpty()) { "Storage contract multipart parts must not be empty." }
        val originalFirstPart = parts.first()
        val replacementFirstPart = replacementMultipartPart(originalFirstPart)
        require(replacementFirstPart.size == originalFirstPart.size) {
            "Storage contract replacement multipart part must retain the original part size."
        }
        require(!replacementFirstPart.contentEquals(originalFirstPart)) {
            "Storage contract replacement multipart part must differ from the original part."
        }
        val expectedParts = ArrayList(parts)
        expectedParts[0] = replacementFirstPart
        val request = multipartRequest(expectedParts)
        var upload: MultipartUpload? = null
        var stored: StoredObject? = null
        try {
            upload = storageAdapter.beginMultipartUpload(request)
            val originalAcknowledgement = storageAdapter.uploadPart(
                upload,
                1,
                ByteArrayInputStream(originalFirstPart),
                originalFirstPart.size.toLong(),
            )
            assertEquals(1, originalAcknowledgement.partNumber)
            val replacementAcknowledgement = storageAdapter.uploadPart(
                upload,
                1,
                ByteArrayInputStream(replacementFirstPart),
                replacementFirstPart.size.toLong(),
            )
            assertEquals(1, replacementAcknowledgement.partNumber)
            val acknowledgements = mutableListOf(replacementAcknowledgement)
            expectedParts.drop(1).forEachIndexed { offset, part ->
                val partNumber = offset + 2
                val acknowledgement = storageAdapter.uploadPart(
                    upload,
                    partNumber,
                    ByteArrayInputStream(part),
                    part.size.toLong(),
                )
                assertEquals(partNumber, acknowledgement.partNumber)
                acknowledgements += acknowledgement
            }

            val staleAcknowledgements = ArrayList(acknowledgements)
            staleAcknowledgements[0] = originalAcknowledgement
            org.junit.jupiter.api.assertThrows<MultipartCompletionRejectedException> {
                storageAdapter.completeMultipartUpload(upload, staleAcknowledgements)
            }
            assertFalse(
                storageAdapter.exists(upload.location),
                "A stale part acknowledgement must be rejected without publishing a final object.",
            )

            stored = storageAdapter.completeMultipartUpload(upload, acknowledgements)

            assertEquals(upload.location, stored.location, "Multipart completion must publish at the upload location.")
            assertStoredContent(request, stored, concatenate(expectedParts))
        } finally {
            if (stored == null) {
                upload?.let(::cleanupIncompleteUpload)
            }
            cleanupLocations(stored?.location, upload?.location)
        }
    }

    @Test
    fun `aborts a multipart upload idempotently without publishing a final object`() {
        val parts = multipartParts()
        require(parts.isNotEmpty()) { "Storage contract multipart parts must not be empty." }
        val request = multipartRequest(parts)
        var upload: MultipartUpload? = null
        try {
            upload = storageAdapter.beginMultipartUpload(request)
            storageAdapter.uploadPart(upload, 1, ByteArrayInputStream(parts.first()), parts.first().size.toLong())

            storageAdapter.abortMultipartUpload(upload)
            storageAdapter.abortMultipartUpload(upload)

            assertFalse(storageAdapter.exists(upload.location))
        } finally {
            upload?.let(::cleanupIncompleteUpload)
        }
    }

    private fun assertStoredContent(request: StorageUploadRequest, stored: StoredObject, expected: ByteArray) {
        assertEquals(expected.size.toLong(), stored.contentLength, "Stored object content length must match uploaded bytes.")
        if (request.contentType != null && stored.contentType != null) {
            assertEquals(request.contentType, stored.contentType, "Stored object content type must match the upload request when provided.")
        }
        assertTrue(storageAdapter.exists(stored.location))
        val download = storageAdapter.download(stored.location)
        download.contentLength?.let { length ->
            assertEquals(expected.size.toLong(), length, "Downloaded content length must match uploaded bytes when provided.")
        }
        if (request.contentType != null && download.contentType != null) {
            assertEquals(request.contentType, download.contentType, "Downloaded content type must match the upload request when provided.")
        }
        download.content.use { content ->
            assertArrayEquals(expected, content.readBytes())
        }
    }

    private fun cleanupIncompleteUpload(upload: MultipartUpload) {
        try {
            storageAdapter.abortMultipartUpload(upload)
        } finally {
            cleanupLocations(upload.location)
        }
    }

    private fun cleanupLocations(vararg locations: StorageObjectLocation?) {
        locations.filterNotNull().distinct().forEach(storageAdapter::delete)
    }

    private fun concatenate(parts: List<ByteArray>): ByteArray {
        var length = 0L
        parts.forEach { part -> length = Math.addExact(length, part.size.toLong()) }
        require(length <= Int.MAX_VALUE) { "Storage contract multipart content is too large for an in-memory assertion." }
        val combined = ByteArray(length.toInt())
        var offset = 0
        parts.forEach { part ->
            part.copyInto(combined, destinationOffset = offset)
            offset += part.size
        }
        return combined
    }
}
