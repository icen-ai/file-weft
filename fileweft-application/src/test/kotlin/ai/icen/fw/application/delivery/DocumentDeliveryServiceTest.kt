package ai.icen.fw.application.delivery

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.document.LifecycleCommand
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.delivery.DeliveryConnectorResolver
import ai.icen.fw.spi.delivery.DeliveryRequirement
import ai.icen.fw.spi.delivery.DocumentDeliveryProfile
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider
import ai.icen.fw.spi.delivery.DocumentDeliveryTargetDefinition
import ai.icen.fw.spi.event.OutboxHandlingStatus
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.storage.*
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
    fun `formal delivery accepts one and 512 UTF-16 code unit external ids`() {
        val validExternalIds = listOf(
            1 to "x",
            ConnectorSyncResult.MAX_EXTERNAL_ID_UTF16_LENGTH to "😀".repeat(256),
        )

        validExternalIds.forEach { (expectedLength, externalId) ->
            assertEquals(expectedLength, externalId.length)
            val fixture = fixture(
                listOf(target("archive", DeliveryRequirement.REQUIRED)),
                mapOf("archive" to ConnectorSyncStatus.SUCCESS),
                syncExternalIds = mapOf("archive" to externalId),
            )

            val result = fixture.sync.synchronize(fixture.events.events.single())
            val delivery = fixture.deliveries.findByDocument(TENANT, fixture.document.id).single()

            assertEquals(OutboxHandlingStatus.SUCCEEDED, result.status)
            assertEquals(DocumentDeliveryStatus.SUCCEEDED, delivery.status)
            assertEquals(externalId, delivery.externalId)
            assertNull(delivery.errorMessage)
            assertEquals(LifecycleState.PUBLISHED, fixture.document.lifecycleState)
            assertEquals(1, fixture.connectors.getValue("archive").requests.size)
        }
    }

    @Test
    fun `formal delivery permanently rejects invalid success external ids without replaying connector`() {
        val oversizedExternalId = "😀".repeat(256) + "x"
        assertEquals(ConnectorSyncResult.MAX_EXTERNAL_ID_UTF16_LENGTH + 1, oversizedExternalId.length)
        val invalidExternalIds = listOf(
            "null" to null,
            "empty" to "",
            "blank" to "   ",
            "control" to "remote\u0000id",
            "513 UTF-16 code units" to oversizedExternalId,
        )

        invalidExternalIds.forEach { (case, externalId) ->
            val fixture = fixture(
                listOf(target("archive", DeliveryRequirement.REQUIRED)),
                mapOf("archive" to ConnectorSyncStatus.SUCCESS),
                syncExternalIds = mapOf("archive" to externalId),
            )
            val event = fixture.events.events.single()

            val result = fixture.sync.synchronize(event)
            val repeatedResult = fixture.sync.synchronize(event)
            val delivery = fixture.deliveries.findByDocument(TENANT, fixture.document.id).single()

            assertEquals(OutboxHandlingStatus.PERMANENT_FAILURE, result.status, case)
            assertEquals(
                "Connector reported success with an invalid external identifier; expected a non-blank value without " +
                    "ISO control characters and at most 512 UTF-16 code units.",
                result.message,
                case,
            )
            assertEquals(OutboxHandlingStatus.PERMANENT_FAILURE, repeatedResult.status, case)
            assertEquals(DocumentDeliveryStatus.FAILED, delivery.status, case)
            assertNull(delivery.externalId, case)
            assertEquals(result.message, delivery.errorMessage, case)
            assertEquals(LifecycleState.SYNC_ERROR, fixture.document.lifecycleState, case)
            assertEquals(1, fixture.connectors.getValue("archive").requests.size, case)
            assertEquals(1, fixture.events.events.size, case)
        }
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

        val generationTwo = republicationPlanner.plan(
            fixture.document,
            republicationPlanner.prepare(TENANT, "regulated"),
        ).deliveries.single()
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

        assertFailsWith<IllegalArgumentException> { fixture.planner.prepare(TENANT, "unknown-profile") }

        assertEquals(emptyList(), fixture.events.events)
        assertEquals(emptyList(), fixture.deliveries.findByDocument(TENANT, fixture.document.id))
    }

    @Test
    fun `freezes a prepared delivery profile without resolving integration SPI during planning`() {
        val document = publishingDocument()
        val definitions = mutableListOf(target("archive", DeliveryRequirement.REQUIRED))
        val deliveries = MemoryDeliveries()
        val events = RecordingOutbox()
        var connectorResolutions = 0
        val planner = DocumentDeliveryPlanner(
            profiles = object : DocumentDeliveryProfileProvider {
                override fun listProfiles(tenantId: Identifier): List<DocumentDeliveryProfile> =
                    listOf(DocumentDeliveryProfile("regulated", "Regulated", definitions))
            },
            connectors = object : DeliveryConnectorResolver {
                override fun findConnector(connectorId: String): FileConnector? {
                    connectorResolutions++
                    return RecordingConnector(
                        outcome = ConnectorSyncStatus.SUCCESS,
                        removalOutcome = ConnectorSyncStatus.SUCCESS,
                        syncExternalId = "$connectorId-external",
                        syncMessage = "ok",
                        removalMessage = "removed",
                    )
                }
            },
            deliveries = deliveries,
            outbox = events,
            identifiers = SequenceIds("delivery-archive", "event-archive"),
            clock = CLOCK,
        )

        val preparation = planner.prepare(TENANT, "regulated")
        definitions += target("search", DeliveryRequirement.OPTIONAL)
        val plan = planner.plan(document, preparation)

        assertEquals(1, connectorResolutions)
        assertEquals(listOf("archive"), plan.deliveries.map { it.targetId })
        assertEquals(listOf("archive"), deliveries.findByDocument(TENANT, document.id).map { it.targetId })
        assertEquals(1, events.events.size)
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
    fun `uses a source URL lifetime independent from the downstream RPC timeout`() {
        var requestedSourceTtl: Duration? = null
        val connectorTimeout = Duration.ofSeconds(2)
        val sourceAccessUrlTtl = Duration.ofMinutes(5)
        val fixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.SUCCESS),
            storageAdapter = object : TestStorage() {
                override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI {
                    requestedSourceTtl = expiresIn
                    return URI("https://storage.test/${location.path}")
                }
            },
            connectorTimeout = connectorTimeout,
            sourceAccessUrlTtl = sourceAccessUrlTtl,
        )

        assertEquals(OutboxHandlingStatus.SUCCEEDED, fixture.sync.synchronize(fixture.events.events.single()).status)

        assertEquals(sourceAccessUrlTtl, requestedSourceTtl)
        assertEquals(connectorTimeout, fixture.connectors.getValue("archive").requests.single().invocation.timeout)
    }

    @Test
    fun `rejects non-positive or shorter source URL lifetimes`() {
        assertFailsWith<IllegalArgumentException> {
            fixture(
                listOf(target("archive", DeliveryRequirement.REQUIRED)),
                mapOf("archive" to ConnectorSyncStatus.SUCCESS),
                sourceAccessUrlTtl = Duration.ZERO,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            fixture(
                listOf(target("archive", DeliveryRequirement.REQUIRED)),
                mapOf("archive" to ConnectorSyncStatus.SUCCESS),
                connectorTimeout = Duration.ofSeconds(2),
                sourceAccessUrlTtl = Duration.ofSeconds(1),
            )
        }
    }

    @Test
    fun `resolves synchronization and withdrawal connectors outside database transactions`() {
        val transaction = TrackingTransaction()
        val fixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.SUCCESS),
            transaction = transaction,
            resolverTransaction = transaction,
        )

        fixture.sync.synchronize(fixture.events.events.single())
        fixture.document.transition(LifecycleCommand.OFFLINE)
        DocumentDeliveryRemovalPlanner(fixture.deliveries, fixture.events, SequenceIds("removal-event"), CLOCK)
            .plan(fixture.document)
        val removalResult = fixture.removal.remove(fixture.events.events.last())

        assertEquals(OutboxHandlingStatus.SUCCEEDED, removalResult.status)
        assertEquals(1, fixture.connectors.getValue("archive").requests.size)
        assertEquals(1, fixture.connectors.getValue("archive").removalRequests.size)
    }

    @Test
    fun `acknowledges a stale synchronization result without overwriting a newer target state`() {
        lateinit var fixture: Fixture
        var mutateDuringResolution = false
        fixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.PERMANENT_FAILURE),
            afterConnectorResolution = {
                if (mutateDuringResolution) {
                    fixture.deliveries.findByDocument(TENANT, fixture.document.id).single().markSucceeded("newer-result")
                }
            },
        )

        mutateDuringResolution = true
        val result = fixture.sync.synchronize(fixture.events.events.single())
        val delivery = fixture.deliveries.findByDocument(TENANT, fixture.document.id).single()

        assertEquals(OutboxHandlingStatus.SUCCEEDED, result.status)
        assertEquals(DocumentDeliveryStatus.SUCCEEDED, delivery.status)
        assertEquals("newer-result", delivery.externalId)
        assertEquals(1, fixture.connectors.getValue("archive").requests.size)
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
    fun `acknowledges a stale withdrawal result without overwriting a newer target state`() {
        lateinit var fixture: Fixture
        var mutateDuringResolution = false
        fixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.SUCCESS),
            removalOutcomes = mapOf("archive" to ConnectorSyncStatus.PERMANENT_FAILURE),
            afterConnectorResolution = {
                if (mutateDuringResolution) {
                    fixture.deliveries.findByDocument(TENANT, fixture.document.id).single().markRemovalSucceeded()
                }
            },
        )
        fixture.sync.synchronize(fixture.events.events.single())
        fixture.document.transition(LifecycleCommand.OFFLINE)
        DocumentDeliveryRemovalPlanner(fixture.deliveries, fixture.events, SequenceIds("removal-event"), CLOCK)
            .plan(fixture.document)

        mutateDuringResolution = true
        val result = fixture.removal.remove(fixture.events.events.last())
        val delivery = fixture.deliveries.findByDocument(TENANT, fixture.document.id).single()

        assertEquals(OutboxHandlingStatus.SUCCEEDED, result.status)
        assertEquals(DocumentDeliveryRemovalStatus.SUCCEEDED, delivery.removalStatus)
        assertEquals(1, fixture.connectors.getValue("archive").removalRequests.size)
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
    fun `bounds downstream diagnostics before persisting delivery and removal state`() {
        val oversizedMessage = "x".repeat(DeliveryDiagnosticMessage.MAX_LENGTH + 100)
        val syncFixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.PERMANENT_FAILURE),
            syncMessages = mapOf("archive" to oversizedMessage),
        )

        val syncResult = syncFixture.sync.synchronize(syncFixture.events.events.single())
        syncFixture.sync.exhaust(syncFixture.events.events.single(), oversizedMessage)
        val syncError = syncFixture.deliveries.findByDocument(TENANT, syncFixture.document.id).single().errorMessage

        assertEquals(DeliveryDiagnosticMessage.MAX_LENGTH, syncError?.length)
        assertTrue(syncError!!.endsWith("…[truncated]"))
        assertEquals(syncError, syncResult.message)

        val removalFixture = fixture(
            listOf(target("archive", DeliveryRequirement.REQUIRED)),
            mapOf("archive" to ConnectorSyncStatus.SUCCESS),
            removalOutcomes = mapOf("archive" to ConnectorSyncStatus.PERMANENT_FAILURE),
            removalMessages = mapOf("archive" to oversizedMessage),
        )
        removalFixture.sync.synchronize(removalFixture.events.events.single())
        removalFixture.document.transition(LifecycleCommand.OFFLINE)
        DocumentDeliveryRemovalPlanner(removalFixture.deliveries, removalFixture.events, SequenceIds("removal-event"), CLOCK)
            .plan(removalFixture.document)

        val removalResult = removalFixture.removal.remove(removalFixture.events.events.last())
        removalFixture.removal.exhaust(removalFixture.events.events.last(), oversizedMessage)
        val removalError = removalFixture.deliveries.findByDocument(TENANT, removalFixture.document.id).single().removalErrorMessage

        assertEquals(DeliveryDiagnosticMessage.MAX_LENGTH, removalError?.length)
        assertTrue(removalError!!.endsWith("…[truncated]"))
        assertEquals(removalError, removalResult.message)
    }

    @Test
    fun `retains Java constructor overloads for custom host wiring`() {
        assertTrue(
            DocumentDeliverySyncService::class.java.constructors.any { constructor ->
                constructor.parameterTypes.size == 9 &&
                    constructor.parameterTypes.last() == DocumentDeliveryRemovalPlanner::class.java
            },
        )
        assertTrue(
            DocumentDeliverySyncService::class.java.constructors.any { constructor ->
                constructor.parameterTypes.size == 10 &&
                    constructor.parameterTypes.last() == FileWeftMetrics::class.java
            },
        )
        assertTrue(
            DocumentDeliveryRemovalService::class.java.constructors.any { constructor ->
                constructor.parameterTypes.size == 5 &&
                    constructor.parameterTypes.last() == ai.icen.fw.application.audit.AuditTrail::class.java
            },
        )
    }

    private fun fixture(
        definitions: List<DocumentDeliveryTargetDefinition>,
        outcomes: Map<String, ConnectorSyncStatus>,
        removalOutcomes: Map<String, ConnectorSyncStatus> = emptyMap(),
        syncExternalIds: Map<String, String?> = emptyMap(),
        syncMessages: Map<String, String> = emptyMap(),
        removalMessages: Map<String, String> = emptyMap(),
        plan: Boolean = true,
        beforeConnectorResult: ((String, Document) -> Unit)? = null,
        transaction: ApplicationTransaction = DirectTransaction,
        metrics: FileWeftMetrics? = null,
        resolverTransaction: TrackingTransaction? = null,
        afterConnectorResolution: ((String) -> Unit)? = null,
        storageAdapter: StorageAdapter = TestStorage(),
        connectorTimeout: Duration = Duration.ofSeconds(30),
        sourceAccessUrlTtl: Duration = Duration.ofMinutes(15),
    ): Fixture {
        val document = publishingDocument()
        val documents = MemoryDocuments(document)
        val deliveries = MemoryDeliveries()
        val events = RecordingOutbox()
        val connectors = outcomes.mapValues { (id, outcome) ->
            RecordingConnector(
                outcome,
                removalOutcomes[id] ?: ConnectorSyncStatus.SUCCESS,
                syncExternalId = if (syncExternalIds.containsKey(id)) {
                    syncExternalIds[id]
                } else {
                    if (outcome == ConnectorSyncStatus.SUCCESS) "$id-external" else null
                },
                syncMessage = syncMessages[id] ?: "simulated-$id",
                removalMessage = removalMessages[id] ?: "simulated-remove-$id",
                beforeResult = { beforeConnectorResult?.invoke(id, document) },
            )
        }
        val connectorResolver = object : DeliveryConnectorResolver {
            override fun findConnector(connectorId: String): FileConnector? {
                check(resolverTransaction?.active != true) {
                    "Delivery connector resolution must run outside the application transaction."
                }
                afterConnectorResolution?.invoke(connectorId)
                return connectors[connectorId]
            }
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
        if (plan) planner.plan(document, planner.prepare(TENANT, "regulated"))
        val sync = DocumentDeliverySyncService(
            documentRepository = documents,
            fileObjectRepository = object : FileObjectRepository {
                private val file = FileObject(Identifier("file-1"), TENANT, "proof.txt", 5, "s3", "tenant/proof.txt")
                override fun findById(tenantId: Identifier, fileObjectId: Identifier) = file.takeIf { tenantId == TENANT && fileObjectId == file.id }
                override fun save(fileObject: FileObject) = Unit
            },
            storageAdapter = storageAdapter,
            connectors = connectorResolver,
            deliveries = deliveries,
            transaction = transaction,
            connectorTimeout = connectorTimeout,
            removalPlanner = DocumentDeliveryRemovalPlanner(
                deliveries,
                events,
                SequenceIds("late-removal-event-archive", "late-removal-event-search"),
                CLOCK,
            ),
            metrics = metrics,
            sourceAccessUrlTtl = sourceAccessUrlTtl,
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
        override fun findForMutation(tenantId: Identifier, documentId: Identifier) = findById(tenantId, documentId)
        override fun save(document: Document) { this.document = document }
    }

    private class MemoryDeliveries : DocumentDeliveryTargetMutationRepository {
        private val targets = linkedMapOf<String, DocumentDeliveryTarget>()
        override fun findById(tenantId: Identifier, deliveryId: Identifier) = targets[deliveryId.value]?.takeIf { it.tenantId == tenantId }
        override fun findForMutation(tenantId: Identifier, deliveryId: Identifier) = findById(tenantId, deliveryId)
        override fun findByDocument(tenantId: Identifier, documentId: Identifier) = targets.values.filter { it.tenantId == tenantId && it.documentId == documentId }
        override fun save(target: DocumentDeliveryTarget) { targets[target.id.value] = target }
    }

    private class RecordingOutbox : OutboxEventRepository {
        val events = mutableListOf<OutboxEvent>()
        override fun append(event: OutboxEvent) { events += event }
    }

    private class RecordingConnector(
        private val outcome: ConnectorSyncStatus,
        private val removalOutcome: ConnectorSyncStatus,
        private val syncExternalId: String?,
        private val syncMessage: String,
        private val removalMessage: String,
        private val beforeResult: () -> Unit = {},
    ) : FileConnector {
        val requests = mutableListOf<ConnectorSyncRequest>()
        val removalRequests = mutableListOf<ConnectorRemoveRequest>()
        override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult {
            requests += request
            beforeResult()
            return ConnectorSyncResult(outcome, syncExternalId, syncMessage)
        }
        override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult {
            removalRequests += request
            return ConnectorSyncResult(removalOutcome, message = removalMessage)
        }
        override fun health() = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
    }

    private class SequenceIds(vararg values: String) : IdentifierGenerator {
        private val values = ArrayDeque(values.toList())
        override fun nextId() = Identifier(values.removeFirst())
    }

    private open class TestStorage : StorageAdapter {
        override open fun accessUrl(location: StorageObjectLocation, expiresIn: Duration) = URI("https://storage.test/${location.path}")
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
