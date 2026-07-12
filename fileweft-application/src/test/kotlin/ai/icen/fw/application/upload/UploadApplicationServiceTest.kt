package ai.icen.fw.application.upload

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UploadApplicationServiceTest {
    @Test
    fun `stores metadata and an outbox event after successful upload`() {
        val storage = FakeStorage()
        val fileObjects = RecordingFileObjects()
        val assets = RecordingAssets()
        val outbox = RecordingOutbox()
        val service = service(storage, fileObjects, assets, outbox, DirectTransaction)

        val result = service.upload(command(), ByteArrayInputStream("content".toByteArray()))

        assertEquals(result.fileObject, fileObjects.saved.single())
        assertEquals(result.fileAsset, assets.saved.single())
        assertEquals("file.uploaded", outbox.events.single().type)
        assertEquals("file-1", outbox.events.single().payload["fileObjectId"])
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun `cleans up storage when metadata transaction fails`() {
        val storage = FakeStorage()
        val metrics = RecordingMetrics()
        val service = service(storage, RecordingFileObjects(), RecordingAssets(), RecordingOutbox(), FailingTransaction, metrics)

        assertThrows<IllegalStateException> {
            service.upload(command(), ByteArrayInputStream(byteArrayOf(1)))
        }
        assertEquals(listOf(storage.location), storage.deleted)
        assertEquals(listOf(FileWeftMetric.UPLOAD_FAILURE), metrics.metrics)
    }

    @Test
    fun `deletes an object and skips persistence when storage acknowledges a different length`() {
        val storage = FakeStorage().apply { storedContentLength = 6 }
        val fileObjects = RecordingFileObjects()
        val assets = RecordingAssets()
        val outbox = RecordingOutbox()
        val service = service(storage, fileObjects, assets, outbox, DirectTransaction)

        assertThrows<StoredObjectIntegrityException> {
            service.upload(command(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals(listOf(storage.location), storage.deleted)
        assertTrue(fileObjects.saved.isEmpty())
        assertTrue(assets.saved.isEmpty())
        assertTrue(outbox.events.isEmpty())
    }

    @Test
    fun `deletes an object when its calculated hash differs from the declared hash`() {
        val storage = FakeStorage().apply { storedContentHash = "sha256:actual" }
        val service = service(storage, RecordingFileObjects(), RecordingAssets(), RecordingOutbox(), DirectTransaction)

        assertThrows<StoredObjectIntegrityException> {
            service.upload(command(contentHash = "sha256:expected"), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals(listOf(storage.location), storage.deleted)
    }

    @Test
    fun `records committed upload and does not let metrics failures change success`() {
        val metrics = RecordingMetrics()
        val result = service(FakeStorage(), RecordingFileObjects(), RecordingAssets(), RecordingOutbox(), DirectTransaction, metrics)
            .upload(command(), ByteArrayInputStream("content".toByteArray()))

        assertEquals("file-1", result.fileObject.id.value)
        assertEquals(listOf(FileWeftMetric.UPLOAD_COUNT), metrics.metrics)

        val unaffected = service(FakeStorage(), RecordingFileObjects(), RecordingAssets(), RecordingOutbox(), DirectTransaction, ThrowingMetrics)
            .upload(command(), ByteArrayInputStream("content".toByteArray()))
        assertEquals("file-1", unaffected.fileObject.id.value)
    }

    private fun service(
        storage: FakeStorage,
        fileObjects: RecordingFileObjects,
        assets: RecordingAssets,
        outbox: RecordingOutbox,
        transaction: ApplicationTransaction,
        metrics: FileWeftMetrics? = null,
    ) = UploadApplicationService(
        tenantProvider = object : TenantProvider { override fun currentTenant() = TenantContext(Identifier("tenant-1")) },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser() = UserIdentity(Identifier("user-1"))
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest) = AuthorizationDecision(true)
        },
        storageAdapter = storage, fileObjectRepository = fileObjects, fileAssetRepository = assets,
        outboxEventRepository = outbox,
        identifierGenerator = object : IdentifierGenerator {
            private var value = 0
            override fun nextId() = Identifier(listOf("file-1", "asset-1", "event-1")[value++])
        },
        transaction = transaction,
        clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC),
        metrics = metrics,
    )

    private fun command(contentHash: String? = null) = UploadFileCommand(
        "contract.pdf", 7, "DOCUMENT", "application/pdf", contentHash,
    )

    private class RecordingFileObjects : FileObjectRepository {
        val saved = mutableListOf<FileObject>()
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? = null
        override fun save(fileObject: FileObject) { saved.add(fileObject) }
    }
    private class RecordingAssets : FileAssetRepository {
        val saved = mutableListOf<FileAsset>()
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? = null
        override fun save(fileAsset: FileAsset) { saved.add(fileAsset) }
    }
    private class RecordingOutbox : OutboxEventRepository {
        val events = mutableListOf<OutboxEvent>()
        override fun append(event: OutboxEvent) { events.add(event) }
    }
    private class FakeStorage : StorageAdapter {
        val location = StorageObjectLocation("local", "tenant-1/contract.pdf")
        val deleted = mutableListOf<StorageObjectLocation>()
        var storedContentLength: Long? = null
        var storedContentHash: String? = null
        override fun upload(request: StorageUploadRequest, content: InputStream) = StoredObject(
            location, storedContentLength ?: request.contentLength, request.contentType, storedContentHash,
        )
        override fun delete(location: StorageObjectLocation) { deleted.add(location) }
        override fun download(location: StorageObjectLocation) = throw UnsupportedOperationException()
        override fun exists(location: StorageObjectLocation) = true
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = URI.create("http://localhost/file")
        override fun beginMultipartUpload(request: StorageUploadRequest) = throw UnsupportedOperationException()
        override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart = throw UnsupportedOperationException()
        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>) = throw UnsupportedOperationException()
        override fun abortMultipartUpload(upload: MultipartUpload) = Unit
    }
    private object DirectTransaction : ApplicationTransaction { override fun <T> execute(action: () -> T): T = action() }
    private object FailingTransaction : ApplicationTransaction { override fun <T> execute(action: () -> T): T = throw IllegalStateException("metadata failed") }
    private class RecordingMetrics : FileWeftMetrics {
        val metrics = mutableListOf<FileWeftMetric>()
        override fun increment(metric: FileWeftMetric, tags: Map<String, String>) { metrics += metric }
    }
    private object ThrowingMetrics : FileWeftMetrics {
        override fun increment(metric: FileWeftMetric, tags: Map<String, String>) = throw IllegalStateException("metrics offline")
    }
}
