package ai.icen.fw.adapter.s3

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.testkit.storage.StorageAdapterContractTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/**
 * Runs against the RustFS service defined in .docker/docker-compose.dev.yaml.
 *
 * It is opt-in because a developer or CI worker must explicitly provide Docker:
 * FILEWEFT_RUN_RUSTFS_TESTS=true ./gradlew :fileweft-adapter-s3:test
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3StorageAdapterRustFsIntegrationTest : StorageAdapterContractTest() {
    private val adapterDelegate = lazy {
        S3StorageAdapter(
            S3StorageConfiguration(
                endpoint = URI(System.getenv("FILEWEFT_RUSTFS_ENDPOINT") ?: "http://127.0.0.1:9000"),
                region = System.getenv("FILEWEFT_RUSTFS_REGION") ?: "us-east-1",
                accessKey = System.getenv("FILEWEFT_RUSTFS_ACCESS_KEY") ?: "rustfsadmin",
                secretKey = System.getenv("FILEWEFT_RUSTFS_SECRET_KEY") ?: "ChangeMe123!",
                bucket = System.getenv("FILEWEFT_RUSTFS_BUCKET") ?: "fileweft-integration",
            ),
        )
    }

    override val storageAdapter: S3StorageAdapter
        get() = adapterDelegate.value

    @BeforeAll
    fun startRustFsIntegration() {
        assumeTrue(
            System.getenv("FILEWEFT_RUN_RUSTFS_TESTS") == "true",
            "Set FILEWEFT_RUN_RUSTFS_TESTS=true after starting the RustFS Docker service.",
        )
        storageAdapter.ensureBucket()
    }

    @AfterAll
    fun closeAdapter() {
        if (adapterDelegate.isInitialized()) {
            storageAdapter.close()
        }
    }

    override fun uploadRequest(): StorageUploadRequest {
        val content = content()
        return StorageUploadRequest(
            tenantId = Identifier("s3-contract-tenant"),
            objectName = "contract-${UUID.randomUUID()}.txt",
            contentLength = content.size.toLong(),
            contentType = "text/plain",
            metadata = mapOf("suite" to "adapter-s3"),
        )
    }

    @Test
    fun `completes multipart uploads with a canonical content hash`() {
        // S3 requires every non-final part to be at least 5 MiB.
        val first = ByteArray(5 * 1024 * 1024) { index -> (index % 251).toByte() }
        val second = "final-part".toByteArray(Charsets.UTF_8)
        val content = first + second
        val request = StorageUploadRequest(
            tenantId = Identifier("multipart-tenant"),
            objectName = "report.txt",
            contentLength = content.size.toLong(),
            contentType = "text/plain",
        )
        val upload = storageAdapter.beginMultipartUpload(request)
        val firstPart = storageAdapter.uploadPart(upload, 1, ByteArrayInputStream(first), first.size.toLong())
        val secondPart = storageAdapter.uploadPart(upload, 2, ByteArrayInputStream(second), second.size.toLong())

        val stored = storageAdapter.completeMultipartUpload(upload, listOf(secondPart, firstPart))

        assertEquals(content.size.toLong(), stored.contentLength)
        assertEquals("sha256:${sha256(content)}", stored.contentHash)
        storageAdapter.download(stored.location).content.use { downloaded ->
            assertArrayEquals(content, downloaded.readBytes())
        }
        storageAdapter.delete(stored.location)
    }

    @Test
    fun `keeps tenant objects opaque and isolated`() {
        val content = "isolated".toByteArray(Charsets.UTF_8)
        val first = storageAdapter.upload(
            StorageUploadRequest(Identifier("tenant-a"), "same-name.txt", content.size.toLong()),
            ByteArrayInputStream(content),
        )
        val second = storageAdapter.upload(
            StorageUploadRequest(Identifier("tenant-b"), "same-name.txt", content.size.toLong()),
            ByteArrayInputStream(content),
        )

        assertNotEquals(first.location.path, second.location.path)
        assertFalse(first.location.path.contains("same-name"))
        assertTrue(storageAdapter.exists(first.location))
        assertTrue(storageAdapter.exists(second.location))
        storageAdapter.delete(first.location)
        storageAdapter.delete(second.location)
    }

    @Test
    fun `provides a signed HTTP access URL`() {
        val content = "signed".toByteArray(Charsets.UTF_8)
        val stored = storageAdapter.upload(uploadRequest().copy(contentLength = content.size.toLong()), ByteArrayInputStream(content))

        val url = storageAdapter.accessUrl(stored.location, java.time.Duration.ofMinutes(2))

        assertEquals("http", url.scheme)
        assertTrue(url.query.orEmpty().contains("X-Amz-"))
        storageAdapter.delete(stored.location)
    }

    @Test
    fun `rejects multipart operations after an abort`() {
        val content = "aborted".toByteArray(Charsets.UTF_8)
        val upload = storageAdapter.beginMultipartUpload(
            StorageUploadRequest(Identifier("abort-tenant"), "abort.txt", content.size.toLong()),
        )

        storageAdapter.abortMultipartUpload(upload)

        assertThrows(Exception::class.java) {
            storageAdapter.uploadPart(upload, 1, ByteArrayInputStream(content), content.size.toLong())
        }
    }

    private fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
