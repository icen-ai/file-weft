package com.fileweft.application.delivery

import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.document.LifecycleState
import com.fileweft.domain.file.FileObject
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.connector.ConnectorHealth
import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.ConnectorRemoveRequest
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncResult
import com.fileweft.spi.connector.ConnectorSyncStatus
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.delivery.DeliveryConnectorResolver
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.delivery.DocumentDeliveryProfile
import com.fileweft.spi.delivery.DocumentDeliveryProfileProvider
import com.fileweft.spi.delivery.DocumentDeliveryTargetDefinition
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.storage.*
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentDeliveryServiceTest {
    @Test
    fun `required destinations publish while optional failure remains visible without rollback`() {
        val fixture = fixture(
            listOf(
                target("archive", DeliveryRequirement.REQUIRED),
                target("workspace", DeliveryRequirement.REQUIRED),
                target("search", DeliveryRequirement.OPTIONAL),
            ),
            mapOf(
                "archive" to ConnectorSyncStatus.SUCCESS,
                "workspace" to ConnectorSyncStatus.SUCCESS,
                "search" to ConnectorSyncStatus.PERMANENT_FAILURE,
            ),
        )

        fixture.events.events.forEach { fixture.sync.synchronize(it) }

        assertEquals(LifecycleState.PUBLISHED, fixture.document.lifecycleState)
        assertEquals(
            listOf(DocumentDeliveryStatus.SUCCEEDED, DocumentDeliveryStatus.SUCCEEDED, DocumentDeliveryStatus.FAILED),
            fixture.deliveries.findByDocument(TENANT, fixture.document.id).map { it.status },
        )
        assertEquals("archive", fixture.connectors.getValue("archive").requests.single().attributes["deliveryTargetId"])
    }

    @Test
    fun `required failure marks the document sync error and manual retry requeues only that target`() {
        val fixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.PERMANENT_FAILURE),
        )

        fixture.sync.synchronize(fixture.events.events.single())
        val failed = fixture.deliveries.findByDocument(TENANT, fixture.document.id).single()
        assertEquals(DocumentDeliveryStatus.FAILED, failed.status)
        assertEquals(LifecycleState.SYNC_ERROR, fixture.document.lifecycleState)

        val retry = RetryDocumentDeliveryService(
            tenantProvider = object : TenantProvider { override fun currentTenant() = TenantContext(TENANT) },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser() = UserIdentity(Identifier("operator"), "交付管理员")
                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest) = AuthorizationDecision(true)
            },
            deliveries = fixture.deliveries,
            outbox = fixture.events,
            identifiers = SequenceIds("manual-event"),
            transaction = DirectTransaction,
            clock = CLOCK,
        )

        retry.retry(failed.id)

        assertEquals(DocumentDeliveryStatus.PENDING, fixture.deliveries.findById(TENANT, failed.id)?.status)
        assertEquals(2, fixture.events.events.size)
        assertEquals(failed.id.value, fixture.events.events.last().payload[DocumentDeliveryPlanner.DELIVERY_ID_PAYLOAD_KEY])
    }

    @Test
    fun `rejects an unavailable selected profile without silently changing the release policy`() {
        val fixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.SUCCESS),
            plan = false,
        )

        assertFailsWith<IllegalArgumentException> { fixture.planner.plan(fixture.document, "unknown-profile") }

        assertEquals(emptyList(), fixture.events.events)
        assertEquals(emptyList(), fixture.deliveries.findByDocument(TENANT, fixture.document.id))
    }

    private fun fixture(
        definitions: List<DocumentDeliveryTargetDefinition>,
        outcomes: Map<String, ConnectorSyncStatus>,
        plan: Boolean = true,
    ): Fixture {
        val document = publishingDocument()
        val documents = MemoryDocuments(document)
        val deliveries = MemoryDeliveries()
        val events = RecordingOutbox()
        val connectors = outcomes.mapValues { (id, outcome) -> RecordingConnector(id, outcome) }
        val planner = DocumentDeliveryPlanner(
            profiles = object : DocumentDeliveryProfileProvider {
                override fun listProfiles(tenantId: Identifier) = listOf(DocumentDeliveryProfile("regulated", "Regulated", definitions))
            },
            connectors = object : DeliveryConnectorResolver {
                override fun findConnector(connectorId: String): FileConnector? = connectors[connectorId]
            },
            deliveries = deliveries,
            outbox = events,
            identifiers = SequenceIds(*(definitions.flatMap { listOf("delivery-${it.id}", "event-${it.id}") }).toTypedArray()),
            clock = CLOCK,
        )
        if (plan) planner.plan(document, "regulated")
        val sync = DocumentDeliverySyncService(
            documentRepository = documents,
            fileObjectRepository = object : FileObjectRepository {
                private val file = FileObject(Identifier("file-1"), TENANT, "proof.txt", 5, "s3", "tenant/proof.txt")
                override fun findById(tenantId: Identifier, fileObjectId: Identifier) = file.takeIf { tenantId == TENANT && fileObjectId == file.id }
                override fun save(fileObject: FileObject) = Unit
            },
            storageAdapter = TestStorage,
            connectors = object : DeliveryConnectorResolver {
                override fun findConnector(connectorId: String): FileConnector? = connectors[connectorId]
            },
            deliveries = deliveries,
            transaction = DirectTransaction,
        )
        return Fixture(document, deliveries, events, connectors, sync, planner)
    }

    private fun target(id: String, requirement: DeliveryRequirement) =
        DocumentDeliveryTargetDefinition(id, id.replaceFirstChar { it.uppercase() }, id, requirement, "$id-ops")

    private fun publishingDocument(): Document = Document(
        id = Identifier("document-1"), tenantId = TENANT, assetId = Identifier("asset-1"), documentNumber = "DOC-001", title = "Proof",
        versions = listOf(DocumentVersion(Identifier("version-1"), TENANT, Identifier("document-1"), "1.0", Identifier("file-1"))),
        currentVersionId = Identifier("version-1"),
    ).also {
        it.transition(LifecycleCommand.SUBMIT)
        it.transition(LifecycleCommand.APPROVE)
    }

    private class MemoryDocuments(private var document: Document) : DocumentRepository {
        override fun findById(tenantId: Identifier, documentId: Identifier) = document.takeIf { it.tenantId == tenantId && it.id == documentId }
        override fun save(document: Document) { this.document = document }
    }

    private class MemoryDeliveries : DocumentDeliveryTargetRepository {
        private val targets = linkedMapOf<String, DocumentDeliveryTarget>()
        override fun findById(tenantId: Identifier, deliveryId: Identifier) = targets[deliveryId.value]?.takeIf { it.tenantId == tenantId }
        override fun findByDocument(tenantId: Identifier, documentId: Identifier) = targets.values.filter { it.tenantId == tenantId && it.documentId == documentId }
        override fun save(target: DocumentDeliveryTarget) { targets[target.id.value] = target }
    }

    private class RecordingOutbox : OutboxEventRepository {
        val events = mutableListOf<OutboxEvent>()
        override fun append(event: OutboxEvent) { events += event }
    }

    private class RecordingConnector(private val targetId: String, private val outcome: ConnectorSyncStatus) : FileConnector {
        val requests = mutableListOf<ConnectorSyncRequest>()
        override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult {
            requests += request
            return ConnectorSyncResult(outcome, if (outcome == ConnectorSyncStatus.SUCCESS) "$targetId-external" else null, "simulated-$targetId")
        }
        override fun remove(request: ConnectorRemoveRequest) = ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)
        override fun health() = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
    }

    private class SequenceIds(vararg values: String) : IdentifierGenerator {
        private val values = ArrayDeque(values.toList())
        override fun nextId() = Identifier(values.removeFirst())
    }

    private object TestStorage : StorageAdapter {
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration) = URI("https://storage.test/${location.path}")
        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject = throw UnsupportedOperationException()
        override fun download(location: StorageObjectLocation): StorageDownload = throw UnsupportedOperationException()
        override fun delete(location: StorageObjectLocation) = Unit
        override fun exists(location: StorageObjectLocation) = false
        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = throw UnsupportedOperationException()
        override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart = throw UnsupportedOperationException()
        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject = throw UnsupportedOperationException()
        override fun abortMultipartUpload(upload: MultipartUpload) = Unit
    }

    private object DirectTransaction : ApplicationTransaction { override fun <T> execute(action: () -> T): T = action() }

    private data class Fixture(
        val document: Document,
        val deliveries: MemoryDeliveries,
        val events: RecordingOutbox,
        val connectors: Map<String, RecordingConnector>,
        val sync: DocumentDeliverySyncService,
        val planner: DocumentDeliveryPlanner,
    )

    private companion object {
        val TENANT = Identifier("tenant-1")
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
    }
}
