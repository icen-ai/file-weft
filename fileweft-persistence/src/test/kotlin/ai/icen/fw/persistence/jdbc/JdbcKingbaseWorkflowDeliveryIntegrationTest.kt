package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.delivery.DocumentDeliveryOutboxEventHandler
import ai.icen.fw.application.delivery.DocumentDeliveryPlanner
import ai.icen.fw.application.delivery.DocumentDeliveryRemovalService
import ai.icen.fw.application.delivery.DocumentDeliveryStatus
import ai.icen.fw.application.delivery.DocumentDeliverySyncService
import ai.icen.fw.application.delivery.IdempotentDocumentDeliveryRecoveryService
import ai.icen.fw.application.delivery.MapDeliveryConnectorResolver
import ai.icen.fw.application.delivery.StaticDocumentDeliveryProfileProvider
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.outbox.OutboxWorker
import ai.icen.fw.application.workflow.DocumentReviewRouteResolver
import ai.icen.fw.application.workflow.DocumentReviewWorkflowService
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.workflow.WorkflowState
import ai.icen.fw.domain.workflow.WorkflowTaskState
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
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
import ai.icen.fw.spi.delivery.DeliveryRequirement
import ai.icen.fw.spi.delivery.DocumentDeliveryProfile
import ai.icen.fw.spi.delivery.DocumentDeliveryTargetDefinition
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.workflow.DocumentReviewRoute
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest
import ai.icen.fw.spi.workflow.DocumentReviewRouteTask
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.PrintWriter
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** KingbaseES proof for the complete review, delivery, Outbox, and manual-recovery path. */
class JdbcKingbaseWorkflowDeliveryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var connectionSettings: ConnectionSettings
    private val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
    private val tenantId = Identifier("tenant-kingbase")

    @BeforeEach
    fun prepareDatabase() {
        check(System.getenv("FILEWEFT_RUN_KINGBASE_TESTS") == "true") {
            "Kingbase integration tests must run only through the fail-closed Gradle task"
        }
        Class.forName(System.getenv("FILEWEFT_KINGBASE_DRIVER") ?: "com.kingbase8.Driver")
        connectionSettings = ConnectionSettings(
            url = System.getenv("FILEWEFT_KINGBASE_URL") ?: "jdbc:kingbase8://localhost:54321/fileweft",
            user = System.getenv("FILEWEFT_KINGBASE_USER") ?: "system",
            password = System.getenv("FILEWEFT_KINGBASE_PASSWORD") ?: "kingbase",
            schema = System.getenv("FILEWEFT_KINGBASE_SCHEMA") ?: "public",
        )
        require(connectionSettings.schema.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "FILEWEFT_KINGBASE_SCHEMA must be an unquoted SQL identifier"
        }
        connectionSettings.rawConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA IF EXISTS ${connectionSettings.schema} CASCADE")
                statement.execute("CREATE SCHEMA ${connectionSettings.schema}")
            }
        }
        dataSource = DriverManagerDataSource(connectionSettings)
        FlywayMigrationRunner(dataSource).migrate()
    }

    @AfterEach
    fun cleanDatabase() {
        if (::connectionSettings.isInitialized) {
            connectionSettings.rawConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA IF EXISTS ${connectionSettings.schema} CASCADE")
                }
            }
        }
    }

    @Test
    fun `completes dual approval and required recovery while optional delivery remains failed`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val documents = JdbcDocumentRepository(clock)
        val workflows = JdbcWorkflowInstanceRepository(clock)
        val fileObjects = JdbcFileObjectRepository(clock)
        val deliveries = JdbcDocumentDeliveryTargetRepository(clock)
        val objectMapper = ObjectMapper()
        val outbox = JdbcOutboxEventRepository(objectMapper)
        val outboxProcessing = JdbcOutboxProcessingRepository(objectMapper)
        val requiredConnector = MutableConnector("required")
        val optionalConnector = MutableConnector("optional")
        val connectors = MapDeliveryConnectorResolver(
            mapOf("required" to requiredConnector, "optional" to optionalConnector),
        )
        val planner = DocumentDeliveryPlanner(
            StaticDocumentDeliveryProfileProvider(
                listOf(
                    DocumentDeliveryProfile(
                        "regulated",
                        "Regulated",
                        listOf(
                            DocumentDeliveryTargetDefinition(
                                "archive", "Required archive", "required", DeliveryRequirement.REQUIRED,
                            ),
                            DocumentDeliveryTargetDefinition(
                                "search", "Optional search", "optional", DeliveryRequirement.OPTIONAL,
                            ),
                        ),
                    ),
                ),
                "regulated",
            ),
            connectors,
            deliveries,
            outbox,
            SequenceIdentifiers("delivery"),
            clock,
        )
        val users = MutableUsers(UserIdentity(Identifier("editor"), "Editor"))
        val reviews = DocumentReviewWorkflowService(
            tenantProvider = FixedTenant(tenantId),
            userRealmProvider = users,
            authorizationProvider = AllowAllAuthorization,
            documentRepository = documents,
            workflowRepository = workflows,
            deliveryPlanner = planner,
            identifierGenerator = SequenceIdentifiers("workflow"),
            transaction = transaction,
            reviewRoutes = DocumentReviewRouteResolver(listOf(DualControlRoute), DualControlRoute.id()),
        )
        val document = draftDocument()
        transaction.execute {
            fileObjects.save(fileObject())
            documents.save(document)
        }

        val workflow = reviews.submit(document.id, null, DualControlRoute.id())
        assertEquals(2, workflow.tasks.size)
        users.current = UserIdentity(Identifier("reviewer"), "Reviewer")
        val afterFirstApproval = reviews.approve(
            workflow.id,
            workflow.tasks[0].id,
            "professional approval",
            null,
        )

        assertEquals(LifecycleState.PENDING_REVIEW, afterFirstApproval.lifecycleState)
        assertEquals(0, countRows("fw_document_delivery_target"))
        assertEquals(0, countRows("fw_outbox_event"))
        val afterFirstWorkflow = transaction.execute { workflows.findById(tenantId, workflow.id) }
        assertEquals(WorkflowState.PENDING, assertNotNull(afterFirstWorkflow).state)
        assertEquals(WorkflowTaskState.APPROVED, afterFirstWorkflow.tasks[0].state)
        assertEquals(WorkflowTaskState.PENDING, afterFirstWorkflow.tasks[1].state)

        users.current = UserIdentity(Identifier("administrator"), "Administrator")
        val readyForDelivery = reviews.approve(
            workflow.id,
            workflow.tasks[1].id,
            "control approval",
            "regulated",
        )

        assertEquals(LifecycleState.PUBLISHING, readyForDelivery.lifecycleState)
        val approvedWorkflow = transaction.execute { workflows.findById(tenantId, workflow.id) }
        assertEquals(WorkflowState.APPROVED, assertNotNull(approvedWorkflow).state)
        assertTrue(approvedWorkflow.tasks.all { it.state == WorkflowTaskState.APPROVED })
        val planned = transaction.execute { deliveries.findByDocument(tenantId, document.id) }
        assertEquals(setOf(DeliveryRequirement.REQUIRED, DeliveryRequirement.OPTIONAL), planned.map { it.requirement }.toSet())
        assertEquals(2, countRows("fw_outbox_event"))

        val sync = DocumentDeliverySyncService(
            documentRepository = documents,
            fileObjectRepository = fileObjects,
            storageAdapter = TestStorage,
            connectors = connectors,
            deliveries = deliveries,
            transaction = transaction,
        )
        val handler = DocumentDeliveryOutboxEventHandler(
            sync,
            DocumentDeliveryRemovalService(connectors, deliveries, transaction),
            outboxProcessing,
            documents,
        )
        val worker = OutboxWorker(
            repository = outboxProcessing,
            transaction = transaction,
            handlers = listOf(handler),
            clock = clock,
            maxAttempts = 1,
        )

        val failed = worker.processAvailable(10)

        assertEquals(2, failed.claimed)
        assertEquals(2, failed.failed)
        assertEquals(0, failed.lost)
        assertEquals(LifecycleState.SYNC_ERROR, transaction.execute { documents.findById(tenantId, document.id) }?.lifecycleState)
        val failedTargets = transaction.execute { deliveries.findByDocument(tenantId, document.id) }
        assertTrue(failedTargets.all { it.status == DocumentDeliveryStatus.FAILED })
        assertEquals(mapOf("FAILED" to 2), outboxStatuses())
        val requiredTarget = failedTargets.single { it.requirement == DeliveryRequirement.REQUIRED }
        val optionalTarget = failedTargets.single { it.requirement == DeliveryRequirement.OPTIONAL }

        users.current = UserIdentity(Identifier("recovery-admin"), "Recovery administrator")
        requiredConnector.status = ConnectorSyncStatus.SUCCESS
        val recovery = IdempotentDocumentDeliveryRecoveryService(
            tenants = FixedTenant(tenantId),
            users = users,
            authorization = AllowAllAuthorization,
            documents = documents,
            deliveries = deliveries,
            outboxMutations = outboxProcessing,
            outbox = outbox,
            identifiers = SequenceIdentifiers("recovery-event"),
            clock = clock,
            idempotency = RequestIdempotencyService(
                JdbcRequestIdempotencyRepository(),
                transaction,
                SequenceIdentifiers("idempotency"),
                clock,
            ),
        )
        val firstReceipt = recovery.retryDelivery(document.id, requiredTarget.id, "kingbase-required-retry")
        val recoveredFence = transaction.execute {
            deliveries.findById(tenantId, requiredTarget.id)?.currentDispatchFence
        }
        assertEquals(3, countRows("fw_outbox_event"))

        val replayReceipt = recovery.retryDelivery(document.id, requiredTarget.id, "kingbase-required-retry")

        assertEquals(firstReceipt.documentId, replayReceipt.documentId)
        assertEquals(firstReceipt.deliveryId, replayReceipt.deliveryId)
        assertEquals(recoveredFence?.eventId, transaction.execute {
            deliveries.findById(tenantId, requiredTarget.id)?.currentDispatchFence?.eventId
        })
        assertEquals(3, countRows("fw_outbox_event"))

        val recovered = worker.processAvailable(10)

        assertEquals(1, recovered.claimed)
        assertEquals(1, recovered.succeeded)
        assertEquals(0, recovered.failed)
        assertEquals(LifecycleState.PUBLISHED, transaction.execute { documents.findById(tenantId, document.id) }?.lifecycleState)
        val finalTargets = transaction.execute { deliveries.findByDocument(tenantId, document.id) }
        val finalRequired = finalTargets.single { it.id == requiredTarget.id }
        val finalOptional = finalTargets.single { it.id == optionalTarget.id }
        assertEquals(DocumentDeliveryStatus.SUCCEEDED, finalRequired.status)
        assertNotNull(finalRequired.externalId)
        assertEquals(DocumentDeliveryStatus.FAILED, finalOptional.status)
        assertEquals(mapOf("FAILED" to 2, "SUCCESS" to 1), outboxStatuses())
        assertEquals(2, requiredConnector.syncCalls)
        assertEquals(1, optionalConnector.syncCalls)
    }

    private fun draftDocument(): Document = Document(
        id = Identifier("document-1"),
        tenantId = tenantId,
        assetId = Identifier("asset-1"),
        documentNumber = "KB-DOC-001",
        title = "Kingbase regulated document",
    ).also { document ->
        document.addVersion(
            DocumentVersion(Identifier("version-1"), tenantId, document.id, "1.0", Identifier("file-1")),
        )
    }

    private fun fileObject(): FileObject = FileObject(
        Identifier("file-1"),
        tenantId,
        "regulated.txt",
        7,
        "test",
        "tenant-kingbase/regulated.txt",
        "text/plain",
        "sha256:test",
    )

    private fun countRows(table: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun outboxStatuses(): Map<String, Int> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT event_status, COUNT(*) FROM fw_outbox_event GROUP BY event_status ORDER BY event_status",
            ).use { result ->
                buildMap {
                    while (result.next()) put(result.getString(1), result.getInt(2))
                }
            }
        }
    }

    private class MutableConnector(private val connectorId: String) : FileConnector {
        var status: ConnectorSyncStatus = ConnectorSyncStatus.PERMANENT_FAILURE
        var syncCalls: Int = 0
            private set

        override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult {
            syncCalls++
            return if (status == ConnectorSyncStatus.SUCCESS) {
                ConnectorSyncResult(status, "$connectorId:${request.businessId.value}")
            } else {
                ConnectorSyncResult(status, message = "$connectorId unavailable")
            }
        }

        override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult =
            ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)

        override fun health(): ConnectorHealth = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
    }

    private class MutableUsers(var current: UserIdentity) : UserRealmProvider {
        override fun currentUser(): UserIdentity = current

        override fun findUser(userId: Identifier): UserIdentity? = current.takeIf { it.id == userId }
    }

    private class SequenceIdentifiers(private val prefix: String) : IdentifierGenerator {
        private var sequence = 0

        override fun nextId(): Identifier = Identifier("$prefix-${++sequence}")
    }

    private class FixedTenant(private val tenantId: Identifier) : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(tenantId)
    }

    private object AllowAllAuthorization : AuthorizationProvider {
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
    }

    private object DualControlRoute : DocumentReviewRouteProvider {
        override fun id(): String = "dual-control"

        override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute = DocumentReviewRoute(
            "DUAL_CONTROL",
            listOf(
                DocumentReviewRouteTask(Identifier("reviewer")),
                DocumentReviewRouteTask(Identifier("administrator")),
            ),
        )
    }

    private object TestStorage : StorageAdapter {
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI =
            URI("https://storage.test/${location.path}")

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

    private data class ConnectionSettings(
        val url: String,
        val user: String,
        val password: String,
        val schema: String,
    ) {
        fun rawConnection(): Connection = DriverManager.getConnection(url, user, password)
    }

    private class DriverManagerDataSource(
        private val settings: ConnectionSettings,
    ) : DataSource {
        override fun getConnection(): Connection = configure(settings.rawConnection())

        override fun getConnection(username: String, password: String): Connection =
            configure(DriverManager.getConnection(settings.url, username, password))

        private fun configure(connection: Connection): Connection = connection.apply {
            createStatement().use { statement -> statement.execute("SET search_path TO ${settings.schema}") }
        }

        override fun getLogWriter(): PrintWriter? = DriverManager.getLogWriter()
        override fun setLogWriter(out: PrintWriter?) = DriverManager.setLogWriter(out)
        override fun setLoginTimeout(seconds: Int) = DriverManager.setLoginTimeout(seconds)
        override fun getLoginTimeout(): Int = DriverManager.getLoginTimeout()
        override fun getParentLogger(): Logger = Logger.getLogger("ai.icen.fw.persistence.jdbc.kingbase.workflow")

        override fun <T : Any> unwrap(iface: Class<T>): T {
            if (iface.isInstance(this)) return iface.cast(this)
            throw java.sql.SQLException("Not a wrapper for ${iface.name}")
        }

        override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
    }
}
