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
import com.fileweft.spi.event.OutboxHandlingStatus
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.observability.FileWeftMetric
import com.fileweft.spi.observability.FileWeftMetrics
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
import kotlin.test.assertTrue

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
            documentRepository = fixture.documents,
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
    fun `writes an outbox withdrawal for an offline document and removes each delivered target idempotently`() {
        val fixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.SUCCESS),
        )
        fixture.sync.synchronize(fixture.events.events.single())
        fixture.document.transition(LifecycleCommand.OFFLINE)
        val planner = DocumentDeliveryRemovalPlanner(fixture.deliveries, fixture.events, SequenceIds("removal-event"), CLOCK)

        val plan = planner.plan(fixture.document)
        val removalEvent = fixture.events.events.last()
        val result = fixture.removal.remove(removalEvent)
        val delivery = fixture.deliveries.findByDocument(TENANT, fixture.document.id).single()

        assertEquals(1, plan.deliveries.size)
        assertEquals(DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE, removalEvent.type)
        assertEquals(OutboxHandlingStatus.SUCCEEDED, result.status)
        assertEquals(DocumentDeliveryRemovalStatus.SUCCEEDED, delivery.removalStatus)
        assertEquals("archive-external", fixture.connectors.getValue("archive").removalRequests.single().externalId)
        assertEquals("delivery-remove:delivery-archive", fixture.connectors.getValue("archive").removalRequests.single().invocation.idempotencyKey)
    }

    @Test
    fun `queues withdrawal when a delivery succeeds after the document was taken offline`() {
        val fixture = fixture(
            listOf(
                target("archive", DeliveryRequirement.REQUIRED),
                target("search", DeliveryRequirement.OPTIONAL),
            ),
            mapOf("archive" to ConnectorSyncStatus.SUCCESS, "search" to ConnectorSyncStatus.SUCCESS),
            beforeConnectorResult = { targetId, document ->
                if (targetId == "search") document.transition(LifecycleCommand.OFFLINE)
            },
        )

        fixture.sync.synchronize(fixture.events.events.first())
        val result = fixture.sync.synchronize(fixture.events.events[1])
        val delivery = fixture.deliveries.findByDocument(TENANT, fixture.document.id).single { it.targetId == "search" }
        val withdrawalEvent = fixture.events.events.last()

        assertEquals(OutboxHandlingStatus.SUCCEEDED, result.status)
        assertEquals(LifecycleState.OFFLINE, fixture.document.lifecycleState)
        assertEquals(DocumentDeliveryStatus.SUCCEEDED, delivery.status)
        assertEquals(DocumentDeliveryRemovalStatus.PENDING, delivery.removalStatus)
        assertEquals(DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE, withdrawalEvent.type)
        assertEquals(delivery.id.value, withdrawalEvent.payload[DocumentDeliveryPlanner.DELIVERY_ID_PAYLOAD_KEY])
    }

    @Test
    fun `allows an administrator to requeue a permanently failed downstream withdrawal`() {
        val fixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.SUCCESS),
            removalOutcomes = mapOf("archive" to ConnectorSyncStatus.PERMANENT_FAILURE),
        )
        fixture.sync.synchronize(fixture.events.events.single())
        fixture.document.transition(LifecycleCommand.OFFLINE)
        DocumentDeliveryRemovalPlanner(fixture.deliveries, fixture.events, SequenceIds("removal-event"), CLOCK).plan(fixture.document)
        val removalEvent = fixture.events.events.last()
        fixture.removal.remove(removalEvent)
        val target = fixture.deliveries.findByDocument(TENANT, fixture.document.id).single()
        assertEquals(DocumentDeliveryRemovalStatus.FAILED, target.removalStatus)

        RetryDocumentDeliveryService(
            tenantProvider = object : TenantProvider { override fun currentTenant() = TenantContext(TENANT) },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser() = UserIdentity(Identifier("operator"), "交付管理员")
                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest) = AuthorizationDecision(true)
            },
            documentRepository = fixture.documents,
            deliveries = fixture.deliveries,
            outbox = fixture.events,
            identifiers = SequenceIds("manual-removal-event"),
            transaction = DirectTransaction,
            clock = CLOCK,
        ).retry(target.id)

        assertEquals(DocumentDeliveryRemovalStatus.PENDING, target.removalStatus)
        assertEquals(DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE, fixture.events.events.last().type)
    }

    @Test
    fun `creates a fresh delivery generation when an offline document is restored and republished`() {
        val fixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.SUCCESS),
        )
        fixture.sync.synchronize(fixture.events.events.single())
        fixture.document.transition(LifecycleCommand.OFFLINE)
        DocumentDeliveryRemovalPlanner(fixture.deliveries, fixture.events, SequenceIds("removal-event"), CLOCK).plan(fixture.document)
        fixture.removal.remove(fixture.events.events.last())
        fixture.document.transition(LifecycleCommand.RESTORE_DRAFT)
        fixture.document.transition(LifecycleCommand.SUBMIT)
        fixture.document.transition(LifecycleCommand.APPROVE)
        val republicationPlanner = DocumentDeliveryPlanner(
            profiles = object : DocumentDeliveryProfileProvider {
                override fun listProfiles(tenantId: Identifier) = listOf(
                    DocumentDeliveryProfile("regulated", "Regulated", listOf(target("archive", DeliveryRequirement.REQUIRED))),
                )
            },
            connectors = fixture.connectorResolver,
            deliveries = fixture.deliveries,
            outbox = fixture.events,
            identifiers = SequenceIds("delivery-generation-2", "event-generation-2"),
            clock = CLOCK,
        )

        val generationTwo = republicationPlanner.plan(fixture.document, "regulated").deliveries.single()
        val allDeliveries = fixture.deliveries.findByDocument(TENANT, fixture.document.id)

        assertEquals(2, fixture.document.deliveryGeneration)
        assertEquals(2, generationTwo.deliveryGeneration)
        assertEquals(listOf(1, 2), allDeliveries.map { it.deliveryGeneration })
        assertEquals(DocumentDeliveryRemovalStatus.SUCCEEDED, allDeliveries.first().removalStatus)
        assertEquals("delivery-generation-2", generationTwo.id.value)
    }

    @Test
    fun `rejects manual retry of a failed historical delivery generation`() {
        val fixture = fixture(
            listOf(
                target("archive", DeliveryRequirement.REQUIRED),
                target("search", DeliveryRequirement.OPTIONAL),
            ),
            mapOf("archive" to ConnectorSyncStatus.SUCCESS, "search" to ConnectorSyncStatus.PERMANENT_FAILURE),
        )
        fixture.sync.synchronize(fixture.events.events.first())
        fixture.sync.synchronize(fixture.events.events[1])
        val historicalFailure = fixture.deliveries.findByDocument(TENANT, fixture.document.id).single { it.targetId == "search" }
        fixture.document.transition(LifecycleCommand.OFFLINE)
        DocumentDeliveryRemovalPlanner(fixture.deliveries, fixture.events, SequenceIds("removal-event"), CLOCK).plan(fixture.document)
        fixture.removal.remove(fixture.events.events.last())
        fixture.document.transition(LifecycleCommand.RESTORE_DRAFT)
        fixture.document.transition(LifecycleCommand.SUBMIT)
        fixture.document.transition(LifecycleCommand.APPROVE)

        val retry = RetryDocumentDeliveryService(
            tenantProvider = object : TenantProvider { override fun currentTenant() = TenantContext(TENANT) },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser() = UserIdentity(Identifier("operator"), "交付管理员")
                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest) = AuthorizationDecision(true)
            },
            documentRepository = fixture.documents,
            deliveries = fixture.deliveries,
            outbox = fixture.events,
            identifiers = SequenceIds("forbidden-event"),
            transaction = DirectTransaction,
            clock = CLOCK,
        )

        assertFailsWith<IllegalArgumentException> { retry.retry(historicalFailure.id) }
        assertEquals(DocumentDeliveryStatus.FAILED, historicalFailure.status)
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

    @Test
    fun `records per target delivery outcomes outside transactions and skips idempotent replays`() {
        val transaction = TrackingTransaction()
        val metrics = RecordingMetrics(transaction)
        val fixture = fixture(
            listOf(
                target("archive", DeliveryRequirement.REQUIRED),
                target("search", DeliveryRequirement.REQUIRED),
            ),
            mapOf(
                "archive" to ConnectorSyncStatus.SUCCESS,
                "search" to ConnectorSyncStatus.PERMANENT_FAILURE,
            ),
            transaction = transaction,
            metrics = metrics,
        )

        fixture.events.events.forEach { fixture.sync.synchronize(it) }
        fixture.sync.synchronize(fixture.events.events.first())

        assertEquals(
            listOf(
                MetricCall(FileWeftMetric.SYNC_SUCCESS, mapOf("tenantId" to TENANT.value, "connector" to "archive")),
                MetricCall(FileWeftMetric.SYNC_FAILURE, mapOf("tenantId" to TENANT.value, "connector" to "search")),
            ),
            metrics.calls,
        )
    }

    @Test
    fun `metric failures do not alter retryable delivery acknowledgement or exhaustion handling`() {
        val fixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.RETRYABLE_FAILURE),
            metrics = object : FileWeftMetrics {
                override fun increment(metric: FileWeftMetric, tags: Map<String, String>) {
                    throw IllegalStateException("metrics unavailable")
                }
            },
        )
        val event = fixture.events.events.single()

        val result = fixture.sync.synchronize(event)
        fixture.sync.exhaust(event, "outbox retry limit reached")

        assertEquals(OutboxHandlingStatus.RETRYABLE_FAILURE, result.status)
        assertEquals(DocumentDeliveryStatus.FAILED, fixture.deliveries.findByDocument(TENANT, fixture.document.id).single().status)
        assertEquals(LifecycleState.SYNC_ERROR, fixture.document.lifecycleState)
    }

    @Test
    fun `records downstream removal outcomes outside transactions and skips idempotent replays`() {
        val transaction = TrackingTransaction()
        val metrics = RecordingMetrics(transaction)
        val fixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.SUCCESS),
            transaction = transaction,
            metrics = metrics,
        )
        fixture.sync.synchronize(fixture.events.events.single())
        metrics.calls.clear()
        fixture.document.transition(LifecycleCommand.OFFLINE)
        DocumentDeliveryRemovalPlanner(fixture.deliveries, fixture.events, SequenceIds("removal-event"), CLOCK).plan(fixture.document)
        val removalEvent = fixture.events.events.last()

        val result = fixture.removal.remove(removalEvent)
        fixture.removal.remove(removalEvent)

        assertEquals(OutboxHandlingStatus.SUCCEEDED, result.status)
        assertEquals(
            listOf(
                MetricCall(
                    FileWeftMetric.DELIVERY_REMOVAL_SUCCESS,
                    mapOf("tenantId" to TENANT.value, "connector" to "archive"),
                ),
            ),
            metrics.calls,
        )
    }

    @Test
    fun `records failed downstream removals without hiding the target state`() {
        val transaction = TrackingTransaction()
        val metrics = RecordingMetrics(transaction)
        val fixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.SUCCESS),
            removalOutcomes = mapOf("archive" to ConnectorSyncStatus.PERMANENT_FAILURE),
            transaction = transaction,
            metrics = metrics,
        )
        fixture.sync.synchronize(fixture.events.events.single())
        metrics.calls.clear()
        fixture.document.transition(LifecycleCommand.OFFLINE)
        DocumentDeliveryRemovalPlanner(fixture.deliveries, fixture.events, SequenceIds("removal-event"), CLOCK).plan(fixture.document)

        val result = fixture.removal.remove(fixture.events.events.last())

        assertEquals(OutboxHandlingStatus.PERMANENT_FAILURE, result.status)
        assertEquals(DocumentDeliveryRemovalStatus.FAILED, fixture.deliveries.findByDocument(TENANT, fixture.document.id).single().removalStatus)
        assertEquals(
            listOf(
                MetricCall(
                    FileWeftMetric.DELIVERY_REMOVAL_FAILURE,
                    mapOf("tenantId" to TENANT.value, "connector" to "archive"),
                ),
            ),
            metrics.calls,
        )
    }

    @Test
    fun `removal metric failures do not alter downstream confirmation`() {
        val fixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.SUCCESS),
            metrics = object : FileWeftMetrics {
                override fun increment(metric: FileWeftMetric, tags: Map<String, String>) {
                    throw IllegalStateException("metrics unavailable")
                }
            },
        )
        fixture.sync.synchronize(fixture.events.events.single())
        fixture.document.transition(LifecycleCommand.OFFLINE)
        DocumentDeliveryRemovalPlanner(fixture.deliveries, fixture.events, SequenceIds("removal-event"), CLOCK).plan(fixture.document)

        val result = fixture.removal.remove(fixture.events.events.last())

        assertEquals(OutboxHandlingStatus.SUCCEEDED, result.status)
        assertEquals(DocumentDeliveryRemovalStatus.SUCCEEDED, fixture.deliveries.findByDocument(TENANT, fixture.document.id).single().removalStatus)
    }

    @Test
    fun `retains the pre metrics Java constructor overloads for custom host wiring`() {
        assertTrue(
            DocumentDeliverySyncService::class.java.constructors.any { constructor ->
                constructor.parameterTypes.size == 9 &&
                    constructor.parameterTypes.last() == DocumentDeliveryRemovalPlanner::class.java
            },
        )
        assertTrue(
            DocumentDeliveryRemovalService::class.java.constructors.any { constructor ->
                constructor.parameterTypes.size == 5 &&
                    constructor.parameterTypes.last() == com.fileweft.application.audit.AuditTrail::class.java
            },
        )
    }

    private fun fixture(
        definitions: List<DocumentDeliveryTargetDefinition>,
        outcomes: Map<String, ConnectorSyncStatus>,
        removalOutcomes: Map<String, ConnectorSyncStatus> = emptyMap(),
        plan: Boolean = true,
        beforeConnectorResult: ((String, Document) -> Unit)? = null,
        transaction: ApplicationTransaction = DirectTransaction,
        metrics: FileWeftMetrics? = null,
    ): Fixture {
        val document = publishingDocument()
        val documents = MemoryDocuments(document)
        val deliveries = MemoryDeliveries()
        val events = RecordingOutbox()
        val connectors = outcomes.mapValues { (id, outcome) ->
            RecordingConnector(
                id,
                outcome,
                removalOutcomes[id] ?: ConnectorSyncStatus.SUCCESS,
                beforeResult = { beforeConnectorResult?.invoke(id, document) },
            )
        }
        val connectorResolver = object : DeliveryConnectorResolver {
            override fun findConnector(connectorId: String): FileConnector? = connectors[connectorId]
        }
        val planner = DocumentDeliveryPlanner(
            profiles = object : DocumentDeliveryProfileProvider {
                override fun listProfiles(tenantId: Identifier) = listOf(DocumentDeliveryProfile("regulated", "Regulated", definitions))
            },
            connectors = connectorResolver,
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
            connectors = connectorResolver,
            deliveries = deliveries,
            transaction = transaction,
            removalPlanner = DocumentDeliveryRemovalPlanner(
                deliveries,
                events,
                SequenceIds("late-removal-event-archive", "late-removal-event-search"),
                CLOCK,
            ),
            metrics = metrics,
        )
        val removal = DocumentDeliveryRemovalService(connectorResolver, deliveries, transaction, metrics = metrics)
        return Fixture(document, documents, deliveries, events, connectors, connectorResolver, sync, removal, planner)
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

    private class RecordingConnector(
        private val targetId: String,
        private val outcome: ConnectorSyncStatus,
        private val removalOutcome: ConnectorSyncStatus,
        private val beforeResult: () -> Unit = {},
    ) : FileConnector {
        val requests = mutableListOf<ConnectorSyncRequest>()
        val removalRequests = mutableListOf<ConnectorRemoveRequest>()
        override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult {
            requests += request
            beforeResult()
            return ConnectorSyncResult(outcome, if (outcome == ConnectorSyncStatus.SUCCESS) "$targetId-external" else null, "simulated-$targetId")
        }
        override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult {
            removalRequests += request
            return ConnectorSyncResult(removalOutcome, message = "simulated-remove-$targetId")
        }
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

    private class TrackingTransaction : ApplicationTransaction {
        var active = false
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested transaction is not expected in this fixture." }
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }

    private data class MetricCall(val metric: FileWeftMetric, val tags: Map<String, String>)

    private class RecordingMetrics(private val transaction: TrackingTransaction) : FileWeftMetrics {
        val calls = mutableListOf<MetricCall>()

        override fun increment(metric: FileWeftMetric, tags: Map<String, String>) {
            check(!transaction.active) { "Metrics must be emitted outside the application transaction." }
            calls += MetricCall(metric, tags)
        }
    }

    private data class Fixture(
        val document: Document,
        val documents: MemoryDocuments,
        val deliveries: MemoryDeliveries,
        val events: RecordingOutbox,
        val connectors: Map<String, RecordingConnector>,
        val connectorResolver: DeliveryConnectorResolver,
        val sync: DocumentDeliverySyncService,
        val removal: DocumentDeliveryRemovalService,
        val planner: DocumentDeliveryPlanner,
    )

    private companion object {
        val TENANT = Identifier("tenant-1")
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
    }
}
