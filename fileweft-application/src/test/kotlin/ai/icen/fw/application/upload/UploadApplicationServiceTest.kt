package ai.icen.fw.application.upload

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionNestingException
import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.application.transaction.ApplicationTransactionState
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
    fun `rejects an active transaction before allocating ids or touching storage and persistence`() {
        val storage = FakeStorage()
        val fileObjects = RecordingFileObjects()
        val assets = RecordingAssets()
        val outbox = RecordingOutbox()
        val identifiers = CountingIdentifiers()
        val service = service(
            storage = storage,
            fileObjects = fileObjects,
            assets = assets,
            outbox = outbox,
            transaction = ActiveTransaction,
            identifierGenerator = identifiers,
        )

        val failure = assertThrows<ApplicationTransactionNestingException> {
            service.upload(command(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals(ApplicationTransactionNestingException.DEFAULT_MESSAGE, failure.message)
        assertEquals(0, identifiers.calls)
        assertEquals(0, storage.uploadCalls)
        assertTrue(fileObjects.saved.isEmpty())
        assertTrue(assets.saved.isEmpty())
        assertTrue(outbox.events.isEmpty())
    }

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
        val service = service(
            storage,
            RecordingFileObjects(),
            RecordingAssets(),
            RecordingOutbox(),
            SimulatedTransaction(WriteOutcome.KNOWN_FAILURE),
            metrics,
        )

        assertThrows<IllegalStateException> {
            service.upload(command(), ByteArrayInputStream(byteArrayOf(1)))
        }
        assertEquals(listOf(storage.location), storage.deleted)
        assertEquals(listOf(FileWeftMetric.UPLOAD_FAILURE), metrics.metrics)
    }

    @Test
    fun `returns the committed aggregate without deleting storage when commit acknowledgement is lost`() {
        val storage = FakeStorage()
        val fileObjects = RecordingFileObjects()
        val assets = RecordingAssets()
        val outbox = RecordingOutbox()
        val metrics = RecordingMetrics()
        val service = service(
            storage,
            fileObjects,
            assets,
            outbox,
            SimulatedTransaction(WriteOutcome.UNKNOWN_AFTER_ACTION),
            metrics,
        )

        val result = service.upload(command(), ByteArrayInputStream("content".toByteArray()))

        assertEquals("file-1", result.fileObject.id.value)
        assertEquals("asset-1", result.fileAsset.id.value)
        assertEquals(fileObjects.current, result.fileObject)
        assertEquals(assets.current, result.fileAsset)
        assertEquals(1, outbox.events.size)
        assertTrue(storage.deleted.isEmpty())
        assertEquals(listOf(FileWeftMetric.UPLOAD_COUNT), metrics.metrics)
    }

    @Test
    fun `does not report success when outbox append fails after both core rows are visible`() {
        val storage = FakeStorage()
        val fileObjects = RecordingFileObjects()
        val assets = RecordingAssets()
        val metrics = RecordingMetrics()
        val service = service(
            storage,
            fileObjects,
            assets,
            FailingOutbox,
            DirectTransaction,
            metrics,
        )

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> {
            service.upload(command(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals("outbox append failed", failure.cause?.message)
        assertEquals("file-1", fileObjects.current?.id?.value)
        assertEquals("asset-1", assets.current?.id?.value)
        assertTrue(storage.deleted.isEmpty())
        assertEquals(listOf(FileWeftMetric.UPLOAD_FAILURE), metrics.metrics)
    }

    @Test
    fun `does not report success when reconciliation sees uncommitted rows through a joined transaction`() {
        val storage = FakeStorage()
        val fileObjects = RecordingFileObjects()
        val assets = RecordingAssets()
        val transaction = JoiningTransaction()
        val service = service(
            storage,
            fileObjects,
            assets,
            FailingOutbox,
            transaction,
        )

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> {
            transaction.execute {
                service.upload(command(), ByteArrayInputStream("content".toByteArray()))
            }
        }

        assertEquals("outbox append failed", failure.cause?.message)
        assertEquals(2, transaction.nestedExecutions)
        assertEquals("file-1", fileObjects.current?.id?.value)
        assertEquals("asset-1", assets.current?.id?.value)
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun `retains storage when an unknown transaction has no visible committed aggregate`() {
        val storage = FakeStorage()
        val fileObjects = RecordingFileObjects()
        val assets = RecordingAssets()
        val metrics = RecordingMetrics()
        val service = service(
            storage,
            fileObjects,
            assets,
            RecordingOutbox(),
            SimulatedTransaction(WriteOutcome.UNKNOWN_BEFORE_ACTION),
            metrics,
        )

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> {
            service.upload(command(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals(ApplicationTransactionOutcomeUnknownException.DEFAULT_MESSAGE, failure.message)
        assertEquals(null, fileObjects.current)
        assertEquals(null, assets.current)
        assertTrue(storage.deleted.isEmpty())
        assertEquals(listOf(FileWeftMetric.UPLOAD_FAILURE), metrics.metrics)
    }

    @Test
    fun `retains storage and reports an unknown outcome when reconciliation cannot read persistence`() {
        val storage = FakeStorage()
        val reconciliationFailure = IllegalStateException("reconciliation unavailable")
        val service = service(
            storage,
            RecordingFileObjects(),
            RecordingAssets(),
            RecordingOutbox(),
            SimulatedTransaction(WriteOutcome.KNOWN_FAILURE, reconciliationFailure),
        )

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> {
            service.upload(command(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals("metadata failed", failure.cause?.message)
        assertTrue(failure.suppressed.contains(reconciliationFailure))
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun `retains storage when a failed write leaves only part of the aggregate visible`() {
        val storage = FakeStorage()
        val fileObjects = RecordingFileObjects()
        val service = service(
            storage,
            fileObjects,
            FailingAssets,
            RecordingOutbox(),
            DirectTransaction,
        )

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> {
            service.upload(command(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals("asset persistence failed", failure.cause?.message)
        assertEquals("file-1", fileObjects.current?.id?.value)
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun `retains storage when reconciliation finds conflicting data under a generated id`() {
        val storage = FakeStorage()
        val conflicting = FileObject(
            Identifier("file-1"),
            Identifier("tenant-1"),
            "other.pdf",
            12,
            "local",
            "tenant-1/other.pdf",
        )
        val service = service(
            storage,
            RecordingFileObjects(conflicting),
            RecordingAssets(),
            RecordingOutbox(),
            SimulatedTransaction(WriteOutcome.KNOWN_FAILURE),
        )

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> {
            service.upload(command(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals("metadata failed", failure.cause?.message)
        assertTrue(storage.deleted.isEmpty())
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
        fileObjects: FileObjectRepository,
        assets: FileAssetRepository,
        outbox: OutboxEventRepository,
        transaction: ApplicationTransaction,
        metrics: FileWeftMetrics? = null,
        identifierGenerator: IdentifierGenerator = object : IdentifierGenerator {
            private var value = 0
            override fun nextId() = Identifier(listOf("file-1", "asset-1", "event-1")[value++])
        },
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
        identifierGenerator = identifierGenerator,
        transaction = transaction,
        clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC),
        metrics = metrics,
    )

    private fun command(contentHash: String? = null) = UploadFileCommand(
        "contract.pdf", 7, "DOCUMENT", "application/pdf", contentHash,
    )

    private class RecordingFileObjects(initial: FileObject? = null) : FileObjectRepository {
        var current: FileObject? = initial
            private set
        val saved = mutableListOf<FileObject>()
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? =
            current?.takeIf { it.tenantId == tenantId && it.id == fileObjectId }
        override fun save(fileObject: FileObject) {
            current = fileObject
            saved.add(fileObject)
        }
    }
    private class RecordingAssets(initial: FileAsset? = null) : FileAssetRepository {
        var current: FileAsset? = initial
            private set
        val saved = mutableListOf<FileAsset>()
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
            current?.takeIf { it.tenantId == tenantId && it.id == fileAssetId }
        override fun save(fileAsset: FileAsset) {
            current = fileAsset
            saved.add(fileAsset)
        }
    }
    private object FailingAssets : FileAssetRepository {
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? = null
        override fun save(fileAsset: FileAsset): Nothing = throw IllegalStateException("asset persistence failed")
    }
    private class RecordingOutbox : OutboxEventRepository {
        val events = mutableListOf<OutboxEvent>()
        override fun append(event: OutboxEvent) { events.add(event) }
    }
    private object FailingOutbox : OutboxEventRepository {
        override fun append(event: OutboxEvent): Nothing = throw IllegalStateException("outbox append failed")
    }
    private class FakeStorage : StorageAdapter {
        val location = StorageObjectLocation("local", "tenant-1/contract.pdf")
        val deleted = mutableListOf<StorageObjectLocation>()
        var uploadCalls: Int = 0
            private set
        var storedContentLength: Long? = null
        var storedContentHash: String? = null
        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
            uploadCalls++
            return StoredObject(
                location, storedContentLength ?: request.contentLength, request.contentType, storedContentHash,
            )
        }
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
    private object ActiveTransaction : ApplicationTransaction, ApplicationTransactionState {
        override fun <T> execute(action: () -> T): T = error("active transaction guard was bypassed")
        override fun isTransactionActive(): Boolean = true
    }
    private class CountingIdentifiers : IdentifierGenerator {
        var calls: Int = 0
            private set
        override fun nextId(): Identifier {
            calls++
            return Identifier("unexpected-$calls")
        }
    }
    private class JoiningTransaction : ApplicationTransaction {
        private var depth: Int = 0
        var nestedExecutions: Int = 0
            private set

        override fun <T> execute(action: () -> T): T {
            if (depth > 0) nestedExecutions++
            depth++
            return try {
                action()
            } finally {
                depth--
            }
        }
    }
    private enum class WriteOutcome { KNOWN_FAILURE, UNKNOWN_BEFORE_ACTION, UNKNOWN_AFTER_ACTION }
    private class SimulatedTransaction(
        private val outcome: WriteOutcome,
        private val reconciliationFailure: Throwable? = null,
    ) : ApplicationTransaction {
        private var calls: Int = 0

        override fun <T> execute(action: () -> T): T {
            calls++
            if (calls == 1) {
                val writeFailure = IllegalStateException("metadata failed")
                return when (outcome) {
                    WriteOutcome.KNOWN_FAILURE -> throw writeFailure
                    WriteOutcome.UNKNOWN_BEFORE_ACTION ->
                        throw ApplicationTransactionOutcomeUnknownException(writeFailure)
                    WriteOutcome.UNKNOWN_AFTER_ACTION -> {
                        action()
                        throw ApplicationTransactionOutcomeUnknownException(writeFailure)
                    }
                }
            }
            if (calls == 2 && reconciliationFailure != null) throw reconciliationFailure
            return action()
        }
    }
    private class RecordingMetrics : FileWeftMetrics {
        val metrics = mutableListOf<FileWeftMetric>()
        override fun increment(metric: FileWeftMetric, tags: Map<String, String>) { metrics += metric }
    }
    private object ThrowingMetrics : FileWeftMetrics {
        override fun increment(metric: FileWeftMetric, tags: Map<String, String>) = throw IllegalStateException("metrics offline")
    }
}
