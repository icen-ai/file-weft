package ai.icen.fw.adapter.storage

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.testkit.storage.StorageAdapterContractTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalStorageAdapterTest : StorageAdapterContractTest() {
    @TempDir
    lateinit var root: Path

    override val storageAdapter: StorageAdapter
        get() = LocalStorageAdapter(root)

    override fun uploadRequest(): StorageUploadRequest = request(contentLength = content().size.toLong())

    @Test
    fun `calculates a canonical sha256 hash and preserves content type`() {
        val content = "内容完整性".toByteArray(Charsets.UTF_8)
        val stored = adapter().upload(request(contentLength = content.size.toLong(), contentType = "text/plain"), ByteArrayInputStream(content))

        assertEquals("sha256:a1d9230be65bc71c7e05bdf440c69f8d4f5b85c64efd14f86c6df21eace3b2a6", stored.contentHash)
        assertEquals("text/plain", stored.contentType)
        assertEquals("text/plain", adapter().download(stored.location).contentType)
        assertTrue(stored.location.path.startsWith("objects/"))
        assertFalse(stored.location.path.contains("contract.pdf"))
    }

    @Test
    fun `does not trust a caller provided content hash`() {
        val stored = adapter().upload(
            request(contentLength = 3, contentHash = "untrusted-client-hash"),
            ByteArrayInputStream("abc".toByteArray()),
        )

        assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", stored.contentHash)
    }

    @Test
    fun `rejects content length mismatch without publishing an object`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            adapter().upload(request(contentLength = 2), ByteArrayInputStream(byteArrayOf(1)))
        }

        assertContains(failure.message.orEmpty(), "content length")
        assertFalse(Files.exists(root.resolve("objects")))
    }

    @Test
    fun `keeps identical object names in separate tenant directories`() {
        val first = adapter().upload(request(tenantId = "tenant-a", contentLength = 1), ByteArrayInputStream(byteArrayOf(1)))
        val second = adapter().upload(request(tenantId = "tenant-b", contentLength = 1), ByteArrayInputStream(byteArrayOf(2)))

        assertNotEquals(first.location.path.substringBeforeLast('/'), second.location.path.substringBeforeLast('/'))
        assertEquals(byteArrayOf(1).toList(), adapter().download(first.location).content.use { it.readBytes().toList() })
        assertEquals(byteArrayOf(2).toList(), adapter().download(second.location).content.use { it.readBytes().toList() })
    }

    @Test
    fun `rejects path traversal and locations from another storage type`() {
        val traversal = StorageObjectLocation("local", "objects/../../outside")
        val wrongStorage = StorageObjectLocation("s3", "objects/${"a".repeat(64)}/${"b".repeat(32)}")

        assertFailsWith<IllegalArgumentException> { adapter().exists(traversal) }
        assertFailsWith<IllegalArgumentException> { adapter().exists(wrongStorage) }
    }

    @Test
    fun `returns a file uri only for an existing object and positive lifetime`() {
        val stored = adapter().upload(request(contentLength = 1), ByteArrayInputStream(byteArrayOf(1)))

        assertEquals("file", adapter().accessUrl(stored.location, Duration.ofMinutes(5)).scheme)
        assertFailsWith<IllegalArgumentException> { adapter().accessUrl(stored.location, Duration.ZERO) }
        adapter().delete(stored.location)
        assertFailsWith<IllegalArgumentException> { adapter().accessUrl(stored.location, Duration.ofSeconds(1)) }
    }

    @Test
    fun `completes multipart upload after validating all part etags and length`() {
        val adapter = adapter()
        val upload = adapter.beginMultipartUpload(request(contentLength = 5, contentType = "text/plain"))
        val first = adapter.uploadPart(upload, 1, ByteArrayInputStream("hel".toByteArray()), 3)
        val second = adapter.uploadPart(upload, 2, ByteArrayInputStream("lo".toByteArray()), 2)

        val stored = adapter.completeMultipartUpload(upload, listOf(second, first))

        assertEquals("hello", adapter.download(stored.location).content.use { it.readBytes().toString(Charsets.UTF_8) })
        assertEquals("text/plain", adapter.download(stored.location).contentType)
        assertTrue(adapter.exists(stored.location))
        assertFalse(Files.exists(root.resolve(".uploads").resolve(upload.uploadId.value)))
    }

    @Test
    fun `rejects multipart completion when etag or declared length is invalid`() {
        val adapter = adapter()
        val upload = adapter.beginMultipartUpload(request(contentLength = 3))
        val part = adapter.uploadPart(upload, 1, ByteArrayInputStream("abc".toByteArray()), 3)

        assertFailsWith<IllegalArgumentException> {
            adapter.completeMultipartUpload(upload, listOf(part.copy(eTag = "incorrect")))
        }
        assertFalse(adapter.exists(upload.location))

        val wrongLength = adapter.beginMultipartUpload(request(contentLength = 4))
        val wrongLengthPart = adapter.uploadPart(wrongLength, 1, ByteArrayInputStream("abc".toByteArray()), 3)
        assertFailsWith<IllegalArgumentException> {
            adapter.completeMultipartUpload(wrongLength, listOf(wrongLengthPart))
        }
        assertFalse(adapter.exists(wrongLength.location))
    }

    @Test
    fun `aborts multipart uploads idempotently and rejects forged locations`() {
        val adapter = adapter()
        val upload = adapter.beginMultipartUpload(request(contentLength = 1))
        adapter.uploadPart(upload, 1, ByteArrayInputStream(byteArrayOf(1)), 1)
        val forged = MultipartUpload(upload.uploadId, StorageObjectLocation("local", "objects/${"a".repeat(64)}/${"b".repeat(32)}"))

        assertFailsWith<IllegalArgumentException> { adapter.abortMultipartUpload(forged) }

        adapter.abortMultipartUpload(upload)
        adapter.abortMultipartUpload(upload)

        assertFalse(adapter.exists(upload.location))
    }

    private fun adapter(): LocalStorageAdapter = LocalStorageAdapter(root)

    private fun request(
        tenantId: String = "tenant-1",
        contentLength: Long = 1,
        contentType: String? = null,
        contentHash: String? = null,
    ): StorageUploadRequest = StorageUploadRequest(
        tenantId = Identifier(tenantId),
        objectName = "contract.pdf",
        contentLength = contentLength,
        contentType = contentType,
        contentHash = contentHash,
        metadata = mapOf("source" to "adapter-test"),
    )
}
