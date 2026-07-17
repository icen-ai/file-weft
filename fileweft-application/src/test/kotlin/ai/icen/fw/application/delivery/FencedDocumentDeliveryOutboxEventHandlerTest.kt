package ai.icen.fw.application.delivery

import ai.icen.fw.application.document.visibleDeletionGuard

import ai.icen.fw.application.outbox.OutboxEventLease
import ai.icen.fw.application.outbox.OutboxEventMutationRepository
import ai.icen.fw.application.outbox.OutboxEventState
import ai.icen.fw.application.outbox.OutboxEventStatus
import ai.icen.fw.application.outbox.OutboxLeaseLostException
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.delivery.DeliveryConnectorResolver
import ai.icen.fw.spi.delivery.DeliveryRequirement
import ai.icen.fw.spi.event.OutboxHandlingStatus
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.URI
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FencedDocumentDeliveryOutboxEventHandlerTest {
    @Test
    fun `fenced handler rejects the tokenless SPI entry without invoking a connector`() {
        val fixture = fixture()

        val result = fixture.handler.handle(deliveryEvent(EVENT_1))
        fixture.handler.onExhausted(deliveryEvent(EVENT_1), "unleased dispatcher failure")

        assertEquals(OutboxHandlingStatus.PERMANENT_FAILURE, result.status)
        assertEquals(DocumentDeliveryStatus.PENDING, fixture.deliveries.current().status)
        assertEquals(0, fixture.connector.syncRequests.size)
    }

    @Test
    fun `fenced exhaustion projects only after the exact outbox event is durably failed`() {
        val fixture = fixture()
        fixture.outbox.state = OutboxEventState(
            EVENT_1,
            TENANT_ID,
            OutboxEventStatus.FAILED,
            null,
            null,
            DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE,
        )

        fixture.handler.onExhausted(deliveryEvent(EVENT_1), "retry limit reached")

        assertEquals(DocumentDeliveryStatus.FAILED, fixture.deliveries.current().status)
        assertEquals(0, fixture.connector.syncRequests.size)
    }

    @Test
    fun `legacy two argument handler keeps processing leases through its compatible path`() {
        val fixture = fixture(fenced = false)

        val result = fixture.handler.handle(lease(deliveryEvent(EVENT_1), "token-a"))

        assertEquals(OutboxHandlingStatus.SUCCEEDED, result.status)
        assertEquals(DocumentDeliveryStatus.SUCCEEDED, fixture.deliveries.current().status)
        assertEquals(1, fixture.connector.syncRequests.size)
    }

    @Test
    fun `stale E1 completion and exhaustion cannot overwrite manually dispatched E2`() {
        lateinit var fixture: Fixture
        fixture = fixture(beforeSyncResult = {
            val current = fixture.deliveries.current()
            current.markFailed("E1 failed before operator recovery")
            current.retryManually(EVENT_2)
            fixture.deliveries.save(current)
        })
        val event = deliveryEvent(EVENT_1)

        val result = fixture.handler.handle(lease(event, "token-a"))
        fixture.handler.onExhausted(event, "late E1 exhaustion")

        assertEquals(OutboxHandlingStatus.SUCCEEDED, result.status)
        val current = fixture.deliveries.current()
        assertEquals(DocumentDeliveryStatus.PENDING, current.status)
        assertEquals(EVENT_2, current.currentDispatchFence?.eventId)
        assertEquals(2, current.currentDispatchFence?.sequence)
        assertEquals(1, fixture.connector.syncRequests.size)
    }

    @Test
    fun `same worker old token A cannot project while current token B can`() {
        val fixture = fixture(currentToken = "token-b")
        val event = deliveryEvent(EVENT_1)

        assertFailsWith<OutboxLeaseLostException> {
            fixture.handler.handle(lease(event, "token-a"))
        }
        assertEquals(DocumentDeliveryStatus.PENDING, fixture.deliveries.current().status)
        assertEquals(LifecycleState.PUBLISHING, fixture.documents.current().lifecycleState)

        val accepted = fixture.handler.handle(lease(event, "token-b"))

        assertEquals(OutboxHandlingStatus.SUCCEEDED, accepted.status)
        assertEquals(DocumentDeliveryStatus.SUCCEEDED, fixture.deliveries.current().status)
        assertEquals(LifecycleState.PUBLISHED, fixture.documents.current().lifecycleState)
        assertEquals(2, fixture.connector.syncRequests.size)
        assertEquals(
            listOf(DELIVERY_ID.value, DELIVERY_ID.value),
            fixture.connector.syncRequests.map { request -> request.invocation.idempotencyKey },
        )
    }

    private fun fixture(
        currentToken: String = "token-a",
        beforeSyncResult: () -> Unit = {},
        fenced: Boolean = true,
    ): Fixture {
        val documents = MemoryDocuments(document())
        val deliveries = MemoryDeliveries(delivery())
        val outbox = MemoryOutboxStates().apply {
            state = OutboxEventState(
                EVENT_1,
                TENANT_ID,
                OutboxEventStatus.RUNNING,
                WORKER_ID,
                currentToken,
                DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE,
            )
        }
        val connector = RecordingConnector(beforeSyncResult)
        val connectors = object : DeliveryConnectorResolver {
            override fun findConnector(connectorId: String): FileConnector? =
                connector.takeIf { connectorId == CONNECTOR_ID }
        }
        val sync = DocumentDeliverySyncService.withDeletionVisibility(
            documentRepository = documents,
            fileObjectRepository = MemoryFileObjects(fileObject()),
            storageAdapter = TestStorage,
            connectors = connectors,
            deliveries = deliveries,
            deletionVisibilityGuard = visibleDeletionGuard(),
            transaction = DirectTransaction,
        )
        val removal = DocumentDeliveryRemovalService(connectors, deliveries, DirectTransaction)
        val handler = if (fenced) {
            DocumentDeliveryOutboxEventHandler(sync, removal, outbox, documents)
        } else {
            DocumentDeliveryOutboxEventHandler(sync, removal)
        }
        return Fixture(handler, documents, deliveries, outbox, connector)
    }

    private data class Fixture(
        val handler: DocumentDeliveryOutboxEventHandler,
        val documents: MemoryDocuments,
        val deliveries: MemoryDeliveries,
        val outbox: MemoryOutboxStates,
        val connector: RecordingConnector,
    )

    private class MemoryDocuments(private var document: Document) : DocumentRepository {
        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            document.takeIf { it.tenantId == tenantId && it.id == documentId }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? =
            findById(tenantId, documentId)

        override fun save(document: Document) {
            this.document = document
        }

        fun current(): Document = document
    }

    private class MemoryDeliveries(target: DocumentDeliveryTarget) : DocumentDeliveryTargetMutationRepository {
        private var target = target

        override fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? =
            target.takeIf { it.tenantId == tenantId && it.id == deliveryId }

        override fun findForMutation(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? =
            findById(tenantId, deliveryId)

        override fun findByDocument(tenantId: Identifier, documentId: Identifier): List<DocumentDeliveryTarget> =
            listOfNotNull(target.takeIf { it.tenantId == tenantId && it.documentId == documentId })

        override fun save(target: DocumentDeliveryTarget) {
            this.target = target
        }

        fun current(): DocumentDeliveryTarget = target
    }

    private class MemoryOutboxStates : OutboxEventMutationRepository {
        lateinit var state: OutboxEventState

        override fun findForMutation(tenantId: Identifier, eventId: Identifier): OutboxEventState? =
            state.takeIf { it.tenantId == tenantId && it.id == eventId }
    }

    private class MemoryFileObjects(private val fileObject: FileObject) : FileObjectRepository {
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? =
            fileObject.takeIf { it.tenantId == tenantId && it.id == fileObjectId }

        override fun save(fileObject: FileObject) = Unit
    }

    private class RecordingConnector(private val beforeSyncResult: () -> Unit) : FileConnector {
        val syncRequests = mutableListOf<ConnectorSyncRequest>()

        override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult {
            syncRequests += request
            beforeSyncResult()
            return ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, "external-1")
        }

        override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult =
            ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)

        override fun health(): ConnectorHealth = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private object TestStorage : StorageAdapter {
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI =
            URI("https://storage.test/object")

        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject = unsupported()
        override fun download(location: StorageObjectLocation): StorageDownload = unsupported()
        override fun delete(location: StorageObjectLocation) = Unit
        override fun exists(location: StorageObjectLocation): Boolean = true
        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = unsupported()
        override fun uploadPart(
            upload: MultipartUpload,
            partNumber: Int,
            content: InputStream,
            contentLength: Long,
        ): MultipartPart = unsupported()
        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject = unsupported()
        override fun abortMultipartUpload(upload: MultipartUpload) = Unit

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    private fun deliveryEvent(id: Identifier): OutboxEvent = OutboxEvent(
        id = id,
        tenantId = TENANT_ID,
        type = DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE,
        payload = mapOf(
            DocumentDeliveryPlanner.DOCUMENT_ID_PAYLOAD_KEY to DOCUMENT_ID.value,
            DocumentDeliveryPlanner.DELIVERY_ID_PAYLOAD_KEY to DELIVERY_ID.value,
        ),
        timestamp = 1,
    )

    private fun lease(event: OutboxEvent, token: String): OutboxEventLease =
        OutboxEventLease(event, 0, WORKER_ID, token)

    private fun document(): Document {
        val version = DocumentVersion(VERSION_ID, TENANT_ID, DOCUMENT_ID, "1.0", FILE_ID)
        return Document(
            id = DOCUMENT_ID,
            tenantId = TENANT_ID,
            assetId = Identifier("asset-1"),
            documentNumber = "DOC-1",
            title = "Fenced delivery",
            lifecycleState = LifecycleState.PUBLISHING,
            versions = listOf(version),
            currentVersionId = version.id,
            deliveryGeneration = 1,
        )
    }

    private fun delivery(): DocumentDeliveryTarget = DocumentDeliveryTarget(
        id = DELIVERY_ID,
        tenantId = TENANT_ID,
        documentId = DOCUMENT_ID,
        profileId = "profile-1",
        targetId = "archive",
        displayName = "Archive",
        connectorId = CONNECTOR_ID,
        requirement = DeliveryRequirement.REQUIRED,
        deliveryGeneration = 1,
    ).also { target -> target.bindInitialDelivery(EVENT_1) }

    private fun fileObject(): FileObject = FileObject(
        id = FILE_ID,
        tenantId = TENANT_ID,
        fileName = "document.pdf",
        contentLength = 10,
        storageType = "S3",
        storagePath = "tenant-1/document.pdf",
        contentType = "application/pdf",
        contentHash = "sha256:test",
    )

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val DOCUMENT_ID = Identifier("document-1")
        val DELIVERY_ID = Identifier("delivery-1")
        val VERSION_ID = Identifier("version-1")
        val FILE_ID = Identifier("file-1")
        val EVENT_1 = Identifier("event-1")
        val EVENT_2 = Identifier("event-2")
        const val CONNECTOR_ID = "archive-connector"
        const val WORKER_ID = "worker-a"
    }
}
