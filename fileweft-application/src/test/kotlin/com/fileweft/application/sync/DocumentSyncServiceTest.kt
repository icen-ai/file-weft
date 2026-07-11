package com.fileweft.application.sync

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.delivery.DeliveryDiagnosticMessage
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.document.LifecycleState
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.domain.file.FileObject
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.spi.connector.ConnectorHealth
import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.ConnectorRemoveRequest
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncResult
import com.fileweft.spi.connector.ConnectorSyncStatus
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.event.OutboxHandlingStatus
import com.fileweft.spi.observability.FileWeftMetric
import com.fileweft.spi.observability.FileWeftMetrics
import com.fileweft.spi.storage.MultipartPart
import com.fileweft.spi.storage.MultipartUpload
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageDownload
import com.fileweft.spi.storage.StorageObjectLocation
import com.fileweft.spi.storage.StorageUploadRequest
import com.fileweft.spi.storage.StoredObject
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.URI
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentSyncServiceTest {
    @Test
    fun `syncs published event outside transactions and records connector success`() {
        val transaction = TrackingTransaction()
        val documents = InMemoryDocuments(publishingDocument())
        val records = InMemorySyncRecords()
        var invokedOutsideTransaction = true
        var idempotencyKey: String? = null
        val service = service(
            documents,
            records,
            transaction,
            storage = object : StorageStub() {
                override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI {
                    invokedOutsideTransaction = invokedOutsideTransaction && !transaction.active
                    return URI.create("https://storage.example/document-1")
                }
            },
            connector = connector { request ->
                invokedOutsideTransaction = !transaction.active
                idempotencyKey = request.invocation.idempotencyKey
                ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, "external-1")
            },
        )

        val result = service.synchronize(event())

        assertEquals(OutboxHandlingStatus.SUCCEEDED, result.status)
        assertEquals(LifecycleState.PUBLISHED, documents.document?.lifecycleState)
        assertTrue(invokedOutsideTransaction)
        assertEquals("event-1", idempotencyKey)
        assertEquals(ConnectorSyncStatus.SUCCESS, records.record?.status)
        assertEquals("external-1", records.record?.externalId)
    }

    @Test
    fun `uses a fifteen minute source URL lifetime without extending the connector RPC timeout`() {
        var requestedSourceTtl: Duration? = null
        var connectorInvocationTimeout: Duration? = null
        val service = service(
            InMemoryDocuments(publishingDocument()),
            InMemorySyncRecords(),
            TrackingTransaction(),
            storage = object : StorageStub() {
                override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI {
                    requestedSourceTtl = expiresIn
                    return URI.create("https://storage.example/document-1")
                }
            },
            connector = connector { request ->
                connectorInvocationTimeout = request.invocation.timeout
                ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, "external-1")
            },
            connectorTimeout = Duration.ofSeconds(2),
        )

        assertEquals(OutboxHandlingStatus.SUCCEEDED, service.synchronize(event()).status)

        assertEquals(Duration.ofMinutes(15), requestedSourceTtl)
        assertEquals(Duration.ofSeconds(2), connectorInvocationTimeout)
    }

    @Test
    fun `rejects invalid source URL lifetime combinations`() {
        assertFailsWith<IllegalArgumentException> {
            service(
                InMemoryDocuments(publishingDocument()), InMemorySyncRecords(), TrackingTransaction(),
                object : StorageStub() { override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration) = URI.create("https://storage.example/document-1") },
                connector { ConnectorSyncResult(ConnectorSyncStatus.SUCCESS) },
                sourceAccessUrlTtl = Duration.ZERO,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service(
                InMemoryDocuments(publishingDocument()), InMemorySyncRecords(), TrackingTransaction(),
                object : StorageStub() { override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration) = URI.create("https://storage.example/document-1") },
                connector { ConnectorSyncResult(ConnectorSyncStatus.SUCCESS) },
                connectorTimeout = Duration.ofSeconds(2),
                sourceAccessUrlTtl = Duration.ofSeconds(1),
            )
        }
    }

    @Test
    fun `retains the prior Java constructor after adding source URL lifetime`() {
        assertTrue(
            DocumentSyncService::class.java.constructors.any { constructor ->
                constructor.parameterTypes.size == 11 &&
                    constructor.parameterTypes.last() == FileWeftMetrics::class.java
            },
        )
    }

    @Test
    fun `records retryable failure and resumes from sync error using the same idempotency key`() {
        val transaction = TrackingTransaction()
        val documents = InMemoryDocuments(publishingDocument())
        val records = InMemorySyncRecords()
        val keys = mutableListOf<String>()
        val outcomes = ArrayDeque(listOf(
            ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE, message = "remote unavailable"),
            ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, "external-1"),
        ))
        val metrics = RecordingMetrics()
        val service = service(
            documents,
            records,
            transaction,
            storage = object : StorageStub() {
                override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = URI.create("https://storage.example/document-1")
            },
            connector = connector { request ->
                keys += request.invocation.idempotencyKey
                outcomes.removeFirst()
            },
            metrics = metrics,
        )

        val first = service.synchronize(event())
        assertEquals(OutboxHandlingStatus.RETRYABLE_FAILURE, first.status)
        assertEquals(LifecycleState.SYNC_ERROR, documents.document?.lifecycleState)
        assertEquals(1, records.record?.retryCount)
        assertEquals(listOf(FileWeftMetric.SYNC_FAILURE), metrics.metrics)

        val second = service.synchronize(event())
        assertEquals(OutboxHandlingStatus.SUCCEEDED, second.status)
        assertEquals(LifecycleState.PUBLISHED, documents.document?.lifecycleState)
        assertEquals(1, records.record?.retryCount)
        assertEquals(listOf("event-1", "event-1"), keys)
        assertEquals(listOf(FileWeftMetric.SYNC_FAILURE, FileWeftMetric.SYNC_SUCCESS), metrics.metrics)
    }

    @Test
    fun `bounds legacy connector diagnostics before persisting sync records`() {
        val oversizedMessage = "x".repeat(DeliveryDiagnosticMessage.MAX_LENGTH + 100)
        val records = InMemorySyncRecords()
        val service = service(
            InMemoryDocuments(publishingDocument()), records, TrackingTransaction(),
            object : StorageStub() { override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration) = URI.create("https://storage.example/document-1") },
            connector { ConnectorSyncResult(ConnectorSyncStatus.PERMANENT_FAILURE, message = oversizedMessage) },
        )

        val result = service.synchronize(event())

        assertEquals(DeliveryDiagnosticMessage.MAX_LENGTH, records.record?.errorMessage?.length)
        assertTrue(records.record!!.errorMessage!!.endsWith("…[truncated]"))
        assertEquals(records.record?.errorMessage, result.message)
    }

    @Test
    fun `rejects malformed event payload before external interaction`() {
        var invoked = false
        val service = service(
            InMemoryDocuments(publishingDocument()), InMemorySyncRecords(), TrackingTransaction(),
            storage = object : StorageStub() {
                override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI {
                    invoked = true
                    return URI.create("https://storage.example/document-1")
                }
            },
            connector = connector { invoked = true; ConnectorSyncResult(ConnectorSyncStatus.SUCCESS) },
        )

        val result = service.synchronize(OutboxEvent(Identifier("event-1"), Identifier("tenant-1"), "document.publish.requested", emptyMap(), 1))

        assertEquals(OutboxHandlingStatus.PERMANENT_FAILURE, result.status)
        assertFalse(invoked)
    }

    @Test
    fun `routes only document publish requested events through the handler`() {
        val handler = DocumentPublishOutboxEventHandler(
            service(
                InMemoryDocuments(publishingDocument()), InMemorySyncRecords(), TrackingTransaction(),
                object : StorageStub() { override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration) = URI.create("https://storage.example/document-1") },
                connector { ConnectorSyncResult(ConnectorSyncStatus.SUCCESS) },
            ),
        )

        assertTrue(handler.supports(event()))
        assertFalse(handler.supports(OutboxEvent(Identifier("event-2"), Identifier("tenant-1"), "file.uploaded", emptyMap(), 1)))
    }

    @Test
    fun `appends connector outcome audit within the final persistence transaction`() {
        val transaction = TrackingTransaction()
        val audits = RecordingAudits(transaction)
        val service = service(
            InMemoryDocuments(publishingDocument()), InMemorySyncRecords(), transaction,
            object : StorageStub() { override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration) = URI.create("https://storage.example/document-1") },
            connector { ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, "external-1") },
            AuditTrail(
                audits,
                object : IdentifierGenerator { override fun nextId(): Identifier = Identifier("audit-1") },
                java.time.Clock.fixed(java.time.Instant.ofEpochMilli(10), java.time.ZoneOffset.UTC),
            ),
        )

        service.synchronize(event())

        assertEquals("document.sync", audits.records.single().action)
        assertEquals("SUCCESS", audits.records.single().details["status"])
        assertTrue(audits.appendedInTransaction)
    }

    @Test
    fun `records sync outcomes without changing connector result when metrics fail`() {
        val metrics = RecordingMetrics()
        val service = service(
            InMemoryDocuments(publishingDocument()), InMemorySyncRecords(), TrackingTransaction(),
            object : StorageStub() { override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration) = URI.create("https://storage.example/document-1") },
            connector { ConnectorSyncResult(ConnectorSyncStatus.SUCCESS) },
            metrics = metrics,
        )

        assertEquals(OutboxHandlingStatus.SUCCEEDED, service.synchronize(event()).status)
        assertEquals(listOf(FileWeftMetric.SYNC_SUCCESS), metrics.metrics)

        val unaffected = service(
            InMemoryDocuments(publishingDocument()), InMemorySyncRecords(), TrackingTransaction(),
            object : StorageStub() { override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration) = URI.create("https://storage.example/document-1") },
            connector { ConnectorSyncResult(ConnectorSyncStatus.SUCCESS) },
            metrics = ThrowingMetrics,
        ).synchronize(event())
        assertEquals(OutboxHandlingStatus.SUCCEEDED, unaffected.status)
    }

    private fun service(
        documents: InMemoryDocuments,
        records: InMemorySyncRecords,
        transaction: TrackingTransaction,
        storage: StorageAdapter,
        connector: FileConnector,
        auditTrail: AuditTrail? = null,
        metrics: FileWeftMetrics? = null,
        connectorTimeout: Duration = Duration.ofSeconds(30),
        sourceAccessUrlTtl: Duration = Duration.ofMinutes(15),
    ) = DocumentSyncService(
        documents,
        InMemoryFiles(fileObject()),
        storage,
        connector,
        "test-connector",
        records,
        object : IdentifierGenerator { override fun nextId(): Identifier = Identifier("sync-1") },
        transaction,
        connectorTimeout = connectorTimeout,
        auditTrail = auditTrail,
        metrics = metrics,
        sourceAccessUrlTtl = sourceAccessUrlTtl,
    )

    private fun event() = OutboxEvent(
        Identifier("event-1"), Identifier("tenant-1"), "document.publish.requested", mapOf("documentId" to "document-1"), 1,
    )

    private fun publishingDocument(): Document = Document(
        Identifier("document-1"), Identifier("tenant-1"), Identifier("asset-1"), "DOC-001", "Contract",
        versions = listOf(DocumentVersion(Identifier("version-1"), Identifier("tenant-1"), Identifier("document-1"), "1.0", Identifier("file-1"))),
        currentVersionId = Identifier("version-1"),
    ).also {
        it.transition(LifecycleCommand.SUBMIT)
        it.transition(LifecycleCommand.APPROVE)
    }

    private fun fileObject() = FileObject(
        Identifier("file-1"), Identifier("tenant-1"), "contract.pdf", 10,
        "local", "objects/test/file", "application/pdf", "sha256:test",
    )

    private fun connector(sync: (ConnectorSyncRequest) -> ConnectorSyncResult): FileConnector = object : FileConnector {
        override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult = sync(request)
        override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult = throw UnsupportedOperationException()
        override fun health(): ConnectorHealth = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
    }

    private class InMemoryDocuments(var document: Document?) : DocumentRepository {
        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            document?.takeIf { it.tenantId == tenantId && it.id == documentId }
        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? =
            findById(tenantId, documentId)
        override fun save(document: Document) { this.document = document }
    }

    private class InMemoryFiles(private val file: FileObject) : FileObjectRepository {
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? =
            file.takeIf { it.tenantId == tenantId && it.id == fileObjectId }
        override fun save(fileObject: FileObject) = Unit
    }

    private class InMemorySyncRecords : SyncRecordRepository {
        var record: SyncRecord? = null
        override fun findBySourceEvent(tenantId: Identifier, sourceEventId: Identifier, connectorName: String): SyncRecord? =
            record?.takeIf { it.tenantId == tenantId && it.sourceEventId == sourceEventId && it.connectorName == connectorName }
        override fun save(record: SyncRecord) { this.record = record }
    }

    private class RecordingAudits(
        private val transaction: TrackingTransaction,
    ) : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        var appendedInTransaction = false
        override fun append(record: AuditRecord) {
            appendedInTransaction = transaction.active
            records += record
        }
        override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> = emptyList()
    }

    private class RecordingMetrics : FileWeftMetrics {
        val metrics = mutableListOf<FileWeftMetric>()
        override fun increment(metric: FileWeftMetric, tags: Map<String, String>) { metrics += metric }
    }

    private object ThrowingMetrics : FileWeftMetrics {
        override fun increment(metric: FileWeftMetric, tags: Map<String, String>) = throw IllegalStateException("metrics offline")
    }

    private abstract class StorageStub : StorageAdapter {
        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject = unsupported()
        override fun download(location: StorageObjectLocation): StorageDownload = unsupported()
        override fun delete(location: StorageObjectLocation) = unsupported<Unit>()
        override fun exists(location: StorageObjectLocation): Boolean = unsupported()
        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = unsupported()
        override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart = unsupported()
        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject = unsupported()
        override fun abortMultipartUpload(upload: MultipartUpload) = unsupported<Unit>()
        protected fun <T> unsupported(): T = throw UnsupportedOperationException()
    }

    private class TrackingTransaction : ApplicationTransaction {
        var active = false
        override fun <T> execute(action: () -> T): T {
            check(!active)
            active = true
            return try { action() } finally { active = false }
        }
    }
}
