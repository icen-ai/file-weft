package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.delivery.DocumentDeliveryRemovalStatus
import ai.icen.fw.application.delivery.DocumentDeliveryStatus
import ai.icen.fw.application.delivery.DocumentDeliveryTarget
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentNumberAlreadyExistsException
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.document.LifecycleCommand
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetMutationRepository
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import ai.icen.fw.spi.delivery.DeliveryRequirement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JdbcRepositoriesIntegrationTest {
    private lateinit var dataSource: DataSource
    private val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)

    @BeforeEach
    fun prepareSchema() {
        assumeTrue(System.getenv("FILEWEFT_RUN_POSTGRES_TESTS") == "true")
        dataSource = PGSimpleDataSource().apply {
            setURL(System.getenv("FILEWEFT_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/fileweft")
            user = System.getenv("FILEWEFT_POSTGRES_USER") ?: "fileweft"
            password = System.getenv("FILEWEFT_POSTGRES_PASSWORD") ?: "fileweft-dev"
        }
        reset(dataSource.connection)
        FlywayMigrationRunner(dataSource).migrate()
    }

    @AfterEach
    fun cleanSchema() {
        if (::dataSource.isInitialized) reset(dataSource.connection)
    }

    @Test
    fun `reconstructs a document only for its tenant and preserves versions`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcDocumentRepository(clock)
        val document = document()

        transaction.execute { repository.save(document) }
        val restored = transaction.execute { repository.findById(Identifier("tenant-1"), document.id) }
        val leaked = transaction.execute { repository.findById(Identifier("tenant-2"), document.id) }

        requireNotNull(restored)
        assertEquals(document.id, restored.id)
        assertEquals(LifecycleState.PENDING_REVIEW, restored.lifecycleState)
        assertEquals(document.currentVersionId, restored.currentVersionId)
        assertEquals(1, restored.versions.size)
        assertNull(leaked)

        restored.transition(LifecycleCommand.APPROVE)
        transaction.execute { repository.save(restored) }
        val updated = transaction.execute { repository.findById(Identifier("tenant-1"), document.id) }
        requireNotNull(updated)
        assertEquals(LifecycleState.PUBLISHING, updated.lifecycleState)
        assertEquals(1, updated.deliveryGeneration)
    }

    @Test
    fun `finds documents by tenant scoped number and translates duplicate insert conflicts`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcDocumentRepository(clock)
        val original = document()
        transaction.execute { repository.save(original) }

        val found = transaction.execute { repository.findByDocumentNumber(original.tenantId, original.documentNumber) }
        val leaked = transaction.execute { repository.findByDocumentNumber(Identifier("tenant-2"), original.documentNumber) }

        assertEquals(original.id, found?.id)
        assertNull(leaked)
        assertFailsWith<DocumentNumberAlreadyExistsException> {
            transaction.execute {
                repository.save(
                    Document(
                        id = Identifier("document-2"),
                        tenantId = original.tenantId,
                        assetId = Identifier("asset-2"),
                        documentNumber = original.documentNumber,
                        title = "Duplicate",
                    ),
                )
            }
        }
    }

    @Test
    fun `locks one document mutation so a second transaction cannot read stale state`() {
        val repository = JdbcDocumentRepository(clock)
        val document = document()
        JdbcApplicationTransaction(dataSource).execute { repository.save(document) }

        dataSource.connection.use { firstConnection ->
            firstConnection.autoCommit = false
            try {
                JdbcConnectionContext.withConnection(firstConnection) {
                    requireNotNull(repository.findForMutation(document.tenantId, document.id))
                }

                dataSource.connection.use { secondConnection ->
                    secondConnection.autoCommit = false
                    try {
                        secondConnection.createStatement().use { statement ->
                            statement.execute("SET LOCAL lock_timeout = '250ms'")
                        }
                        val failure = assertFailsWith<PSQLException> {
                            JdbcConnectionContext.withConnection(secondConnection) {
                                repository.findForMutation(document.tenantId, document.id)
                            }
                        }
                        assertEquals("55P03", failure.sqlState)
                    } finally {
                        secondConnection.rollback()
                    }
                }
            } finally {
                firstConnection.rollback()
            }
        }
    }

    @Test
    fun `persists outbox payload as jsonb in the active transaction`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcOutboxEventRepository(ObjectMapper())
        transaction.execute {
            repository.append(
                OutboxEvent(
                    id = Identifier("event-1"),
                    tenantId = Identifier("tenant-1"),
                    type = "document.publish.requested",
                    payload = mapOf("documentId" to "document-1"),
                    timestamp = 100,
                ),
            )
        }

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT payload_json->>'documentId' FROM fw_outbox_event WHERE tenant_id = 'tenant-1'").use { result ->
                    result.next()
                    assertEquals("document-1", result.getString(1))
                }
            }
        }
    }

    @Test
    fun `persists file metadata and prevents cross tenant asset lookup`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val fileObjects = JdbcFileObjectRepository(clock)
        val assets = JdbcFileAssetRepository(ObjectMapper(), clock)
        val fileObject = FileObject(
            Identifier("file-1"), Identifier("tenant-1"), "contract.pdf", 7,
            "local", "tenant-1/contract.pdf", "application/pdf", "hash-1",
        )
        val asset = FileAsset(
            Identifier("asset-1"), Identifier("tenant-1"), fileObject.id, "DOCUMENT", mapOf("source" to "upload"),
        )

        transaction.execute {
            fileObjects.save(fileObject)
            assets.save(asset)
        }
        val restored = transaction.execute { assets.findById(Identifier("tenant-1"), asset.id) }
        val leaked = transaction.execute { assets.findById(Identifier("tenant-2"), asset.id) }

        requireNotNull(restored)
        assertEquals("upload", restored.metadata["source"])
        assertEquals(fileObject.id, restored.fileObjectId)
        assertNull(leaked)

        val updatedAsset = FileAsset(
            asset.id, asset.tenantId, asset.fileObjectId, asset.assetType, mapOf("source" to "reviewed"),
        )
        transaction.execute { assets.save(updatedAsset) }
        val updated = transaction.execute { assets.findById(Identifier("tenant-1"), asset.id) }
        requireNotNull(updated)
        assertEquals("reviewed", updated.metadata["source"])
        assertEquals(1, countRows("fw_asset"))
    }

    @Test
    fun `locks asset mutations against competing updates and locks without crossing tenants`() {
        val repository: FileAssetMutationRepository = JdbcFileAssetRepository(ObjectMapper(), clock)
        val asset = FileAsset(
            Identifier("asset-locked"),
            Identifier("tenant-1"),
            Identifier("file-locked"),
            "DOCUMENT",
            mapOf("catalog.folder-id" to "contracts"),
        )
        JdbcApplicationTransaction(dataSource).execute { repository.save(asset) }

        dataSource.connection.use { firstConnection ->
            firstConnection.autoCommit = false
            try {
                JdbcConnectionContext.withConnection(firstConnection) {
                    val locked = requireNotNull(repository.findForMutation(asset.tenantId, asset.id))
                    assertEquals("contracts", locked.metadata["catalog.folder-id"])
                    assertNull(repository.findForMutation(Identifier("tenant-2"), asset.id))
                }

                dataSource.connection.use { updateConnection ->
                    updateConnection.autoCommit = false
                    try {
                        updateConnection.createStatement().use { statement ->
                            statement.execute("SET LOCAL lock_timeout = '250ms'")
                        }
                        val failure = assertFailsWith<PSQLException> {
                            JdbcConnectionContext.withConnection(updateConnection) {
                                repository.save(
                                    FileAsset(
                                        asset.id,
                                        asset.tenantId,
                                        asset.fileObjectId,
                                        asset.assetType,
                                        mapOf("catalog.folder-id" to "finance"),
                                    ),
                                )
                            }
                        }
                        assertEquals("55P03", failure.sqlState)
                    } finally {
                        updateConnection.rollback()
                    }
                }

                dataSource.connection.use { lockConnection ->
                    lockConnection.autoCommit = false
                    try {
                        lockConnection.createStatement().use { statement ->
                            statement.execute("SET LOCAL lock_timeout = '250ms'")
                        }
                        val failure = assertFailsWith<PSQLException> {
                            JdbcConnectionContext.withConnection(lockConnection) {
                                repository.findForMutation(asset.tenantId, asset.id)
                            }
                        }
                        assertEquals("55P03", failure.sqlState)
                    } finally {
                        lockConnection.rollback()
                    }
                }
            } finally {
                firstConnection.rollback()
            }
        }
    }

    @Test
    fun `persists a target withdrawal state separately from its delivery state`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val deliveries = JdbcDocumentDeliveryTargetRepository(clock)
        val target = DocumentDeliveryTarget(
            id = Identifier("delivery-1"), tenantId = Identifier("tenant-1"), documentId = Identifier("document-1"),
            profileId = "regulated", targetId = "archive", displayName = "Archive", connectorId = "archive-connector",
            requirement = DeliveryRequirement.REQUIRED,
            deliveryGeneration = 2,
        )
        target.bindInitialDelivery(Identifier("delivery-event-1"))
        target.markSucceeded("archive:document-1")
        target.requestRemoval(Identifier("removal-event-1"))
        target.markRemovalRetrying("platform unavailable")

        transaction.execute { deliveries.save(target) }
        val restored = transaction.execute { deliveries.findById(Identifier("tenant-1"), target.id) }

        requireNotNull(restored)
        assertEquals(DocumentDeliveryStatus.SUCCEEDED, restored.status)
        assertEquals(DocumentDeliveryRemovalStatus.RETRYING, restored.removalStatus)
        assertEquals("platform unavailable", restored.removalErrorMessage)
        assertEquals(1, restored.removalRetryCount)
        assertEquals(2, restored.deliveryGeneration)
        assertEquals("removal-event-1", restored.currentDispatchFence?.eventId?.value)
        assertEquals(2, restored.currentDispatchFence?.sequence)
        assertNull(transaction.execute { deliveries.findById(Identifier("tenant-2"), target.id) })
    }

    private fun document(): Document {
        val document = Document(
            id = Identifier("document-1"),
            tenantId = Identifier("tenant-1"),
            assetId = Identifier("asset-1"),
            documentNumber = "DOC-001",
            title = "Contract",
        )
        document.addVersion(
            DocumentVersion(Identifier("version-1"), document.tenantId, document.id, "1.0", Identifier("file-1")),
        )
        document.transition(LifecycleCommand.SUBMIT)
        return document
    }

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }

    private fun countRows(table: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }
}
