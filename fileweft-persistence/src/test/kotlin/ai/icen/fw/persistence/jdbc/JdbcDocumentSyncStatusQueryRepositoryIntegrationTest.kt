package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.delivery.DocumentDeliveryRemovalPlanner
import ai.icen.fw.application.delivery.DocumentDeliveryRemovalStatus
import ai.icen.fw.application.delivery.DocumentDeliveryPlanner
import ai.icen.fw.application.delivery.DocumentDeliveryStatus
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import ai.icen.fw.spi.delivery.DeliveryRequirement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcDocumentSyncStatusQueryRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource

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
    fun `returns only current generation targets in stable order with exact fenced retry readiness`() {
        insertDocument("document-a", "tenant-a", "finance", deliveryGeneration = 2)
        insertDocument("document-other", "tenant-a", "finance", deliveryGeneration = 1)
        insertEvent("event-delivery-ready", "tenant-a", DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE, "FAILED")
        insertEvent("event-delivery-running", "tenant-a", DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE, "RUNNING")
        insertEvent("event-removal-ready", "tenant-a", DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE, "FAILED")
        insertEvent("event-wrong-type", "tenant-a", DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE, "FAILED")
        insertEvent("event-historical", "tenant-a", DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE, "FAILED")
        insertEvent("event-other", "tenant-a", DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE, "FAILED")

        insertTarget(
            id = "delivery-b", documentId = "document-a", targetId = "archive", generation = 2,
            deliveryStatus = "RETRYING", removalStatus = "NOT_REQUESTED", currentEventId = "event-delivery-ready",
            currentOperation = "DELIVERY", createdTime = 100, updatedTime = 210,
            retryCount = 3, errorMessage = "jdbc://private-delivery-error",
        )
        insertTarget(
            id = "delivery-a", documentId = "document-a", targetId = "workspace", generation = 2,
            deliveryStatus = "FAILED", removalStatus = "NOT_REQUESTED", currentEventId = "event-delivery-running",
            currentOperation = "DELIVERY", createdTime = 100, updatedTime = 220,
        )
        insertTarget(
            id = "delivery-c", documentId = "document-a", targetId = "search", generation = 2,
            deliveryStatus = "SUCCEEDED", removalStatus = "RETRYING", currentEventId = "event-removal-ready",
            currentOperation = "REMOVAL", createdTime = 120, updatedTime = 230,
            removalRetryCount = 4, removalErrorMessage = "private downstream removal error",
            requirement = "OPTIONAL",
        )
        insertTarget(
            id = "delivery-d", documentId = "document-a", targetId = "wrong-type", generation = 2,
            deliveryStatus = "FAILED", removalStatus = "NOT_REQUESTED", currentEventId = "event-wrong-type",
            currentOperation = "DELIVERY", createdTime = 130, updatedTime = 240,
        )
        insertTarget(
            id = "delivery-historical", documentId = "document-a", targetId = "old", generation = 1,
            deliveryStatus = "FAILED", removalStatus = "NOT_REQUESTED", currentEventId = "event-historical",
            currentOperation = "DELIVERY", createdTime = 1, updatedTime = 2,
        )
        insertTarget(
            id = "delivery-other", documentId = "document-other", targetId = "other", generation = 1,
            deliveryStatus = "FAILED", removalStatus = "NOT_REQUESTED", currentEventId = "event-other",
            currentOperation = "DELIVERY", createdTime = 1, updatedTime = 2,
        )

        val result = transaction {
            JdbcDocumentSyncStatusQueryRepository().findByDocument(
                Identifier("tenant-a"),
                Identifier("document-a"),
                DocumentFolderReadScope(listOf("finance")),
            )
        }
        val status = assertNotNull(result)

        assertEquals("document-a", status.documentId.value)
        assertEquals(
            listOf("delivery-a", "delivery-b", "delivery-c", "delivery-d"),
            status.deliveryTargets.map { target -> target.deliveryId.value },
        )
        val running = status.deliveryTargets[0]
        assertEquals(DocumentDeliveryStatus.FAILED, running.deliveryStatus)
        assertFalse(running.deliveryRetryable)
        val deliveryReady = status.deliveryTargets[1]
        assertEquals(DocumentDeliveryStatus.RETRYING, deliveryReady.deliveryStatus)
        assertEquals("archive", deliveryReady.targetId)
        assertEquals("Target archive", deliveryReady.displayName)
        assertEquals(DeliveryRequirement.REQUIRED, deliveryReady.requirement)
        assertEquals(3, deliveryReady.deliveryRetryCount)
        assertEquals(210, deliveryReady.updatedTime)
        assertTrue(deliveryReady.deliveryRetryable)
        assertFalse(deliveryReady.removalRetryable)
        val removalReady = status.deliveryTargets[2]
        assertEquals(DeliveryRequirement.OPTIONAL, removalReady.requirement)
        assertEquals(DocumentDeliveryRemovalStatus.RETRYING, removalReady.removalStatus)
        assertEquals(4, removalReady.removalRetryCount)
        assertFalse(removalReady.deliveryRetryable)
        assertTrue(removalReady.removalRetryable)
        assertFalse(status.deliveryTargets[3].deliveryRetryable)
    }

    @Test
    fun `treats missing mismatched and non terminal current events as not retryable`() {
        insertDocument("document-fences", "tenant-a", "finance", deliveryGeneration = 1)
        insertEvent("event-other-tenant", "tenant-b", DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE, "FAILED")
        insertEvent("event-success", "tenant-a", DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE, "SUCCESS")
        insertEvent(
            "event-removal-running", "tenant-a",
            DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE, "RUNNING",
        )
        insertTarget(
            "delivery-missing", "document-fences", "missing", 1, "FAILED", "NOT_REQUESTED",
            "event-missing", "DELIVERY", 10, 10,
        )
        insertTarget(
            "delivery-cross-tenant-event", "document-fences", "cross", 1, "FAILED", "NOT_REQUESTED",
            "event-other-tenant", "DELIVERY", 20, 20,
        )
        insertTarget(
            "delivery-success-event", "document-fences", "success", 1, "FAILED", "NOT_REQUESTED",
            "event-success", "DELIVERY", 30, 30,
        )
        insertTarget(
            "delivery-removal-running", "document-fences", "removal-running", 1, "SUCCEEDED", "FAILED",
            "event-removal-running", "REMOVAL", 40, 40,
        )

        val result = transaction {
            JdbcDocumentSyncStatusQueryRepository().findByDocument(
                Identifier("tenant-a"), Identifier("document-fences"), null,
            )
        }

        assertNotNull(result)
        assertEquals(4, result.deliveryTargets.size)
        assertTrue(result.deliveryTargets.none { target -> target.deliveryRetryable || target.removalRetryable })
    }

    @Test
    fun `distinguishes visible empty documents from tenant folder and identifier misses`() {
        insertDocument("document-visible", "tenant-a", "finance", deliveryGeneration = 0)
        insertDocument("document-default", "tenant-a", null, deliveryGeneration = 0)
        insertDocument("document-hidden", "tenant-a", "operations", deliveryGeneration = 0)
        insertDocument("document-' OR 1=1 --", "tenant-a", "finance", deliveryGeneration = 0)
        val repository = JdbcDocumentSyncStatusQueryRepository()
        val finance = DocumentFolderReadScope(listOf("finance"))

        val visible = transaction {
            repository.findByDocument(Identifier("tenant-a"), Identifier("document-visible"), finance)
        }
        val defaultFolder = transaction {
            repository.findByDocument(
                Identifier("tenant-a"), Identifier("document-default"), DocumentFolderReadScope(listOf("inbox")),
            )
        }
        val exactQuotedIdentifier = transaction {
            repository.findByDocument(Identifier("tenant-a"), Identifier("document-' OR 1=1 --"), finance)
        }
        val hidden = transaction {
            repository.findByDocument(Identifier("tenant-a"), Identifier("document-hidden"), finance)
        }
        val denied = transaction {
            repository.findByDocument(
                Identifier("tenant-a"), Identifier("document-visible"), DocumentFolderReadScope(emptyList()),
            )
        }
        val crossTenant = transaction {
            repository.findByDocument(Identifier("tenant-b"), Identifier("document-visible"), null)
        }
        val missing = transaction {
            repository.findByDocument(Identifier("tenant-a"), Identifier("document-missing"), null)
        }

        assertNotNull(visible)
        assertTrue(visible.deliveryTargets.isEmpty())
        assertNotNull(defaultFolder)
        assertNotNull(exactQuotedIdentifier)
        assertNull(hidden)
        assertNull(denied)
        assertNull(crossTenant)
        assertNull(missing)
    }

    @Test
    fun `requires the caller bound JDBC transaction`() {
        assertFailsWith<IllegalStateException> {
            JdbcDocumentSyncStatusQueryRepository().findByDocument(
                Identifier("tenant-a"), Identifier("document-a"), null,
            )
        }
    }

    private fun <T> transaction(action: () -> T): T = JdbcApplicationTransaction(dataSource).execute(action)

    private fun insertDocument(
        id: String,
        tenantId: String,
        folderId: String?,
        deliveryGeneration: Int,
    ) {
        val assetId = "asset-$id"
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_ASSET_SQL).use { statement ->
                statement.setString(1, assetId)
                statement.setString(2, tenantId)
                statement.setString(3, "file-$id")
                statement.setString(4, folderId?.let { "{\"catalog.folder-id\":\"$it\"}" } ?: "{}")
                statement.setLong(5, 1)
                statement.setLong(6, 1)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(INSERT_DOCUMENT_SQL).use { statement ->
                statement.setString(1, id)
                statement.setString(2, tenantId)
                statement.setString(3, assetId)
                statement.setString(4, "DOC-$id")
                statement.setString(5, "Title $id")
                statement.setInt(6, deliveryGeneration)
                statement.setLong(7, 1)
                statement.setLong(8, 1)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun insertEvent(id: String, tenantId: String, type: String, status: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_OUTBOX_SQL).use { statement ->
                statement.setString(1, id)
                statement.setString(2, tenantId)
                statement.setString(3, type)
                statement.setString(4, "{\"private\":\"payload-must-not-leak\"}")
                statement.setString(5, status)
                statement.setLong(6, 1)
                statement.setLong(7, 1)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    @Suppress("LongParameterList")
    private fun insertTarget(
        id: String,
        documentId: String,
        targetId: String,
        generation: Int,
        deliveryStatus: String,
        removalStatus: String,
        currentEventId: String,
        currentOperation: String,
        createdTime: Long,
        updatedTime: Long,
        retryCount: Int = 0,
        removalRetryCount: Int = 0,
        errorMessage: String? = null,
        removalErrorMessage: String? = null,
        requirement: String = "REQUIRED",
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_TARGET_SQL).use { statement ->
                var index = 1
                statement.setString(index++, id)
                statement.setString(index++, "tenant-a")
                statement.setString(index++, documentId)
                statement.setString(index++, "profile-private")
                statement.setString(index++, targetId)
                statement.setString(index++, "Target $targetId")
                statement.setString(index++, "connector-private")
                statement.setString(index++, requirement)
                statement.setString(index++, "owner-private")
                statement.setString(index++, deliveryStatus)
                statement.setString(index++, "external-private")
                statement.setNullableString(index++, errorMessage)
                statement.setInt(index++, retryCount)
                statement.setString(index++, removalStatus)
                statement.setNullableString(index++, removalErrorMessage)
                statement.setInt(index++, removalRetryCount)
                statement.setInt(index++, generation)
                statement.setString(index++, currentEventId)
                statement.setString(index++, currentOperation)
                statement.setLong(index++, 1)
                statement.setLong(index++, createdTime)
                statement.setLong(index, updatedTime)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }

    private companion object {
        const val INSERT_ASSET_SQL: String = """
            INSERT INTO fw_asset(id, tenant_id, file_id, asset_type, metadata_json, created_time, updated_time)
            VALUES (?, ?, ?, 'DOCUMENT', ?::jsonb, ?, ?)
        """
        const val INSERT_DOCUMENT_SQL: String = """
            INSERT INTO fw_document(
                id, tenant_id, asset_id, doc_no, title, lifecycle_state, current_version_id,
                delivery_generation, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, 'PUBLISHED', NULL, ?, ?, ?)
        """
        const val INSERT_OUTBOX_SQL: String = """
            INSERT INTO fw_outbox_event(
                id, tenant_id, event_type, payload_json, event_status, retry_count,
                next_attempt_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?::jsonb, ?, 0, 0, ?, ?)
        """
        const val INSERT_TARGET_SQL: String = """
            INSERT INTO fw_document_delivery_target(
                id, tenant_id, document_id, profile_id, target_id, target_name, connector_id,
                delivery_requirement, owner_ref, delivery_status, external_id, error_message,
                retry_count, removal_status, removal_error_message, removal_retry_count,
                delivery_generation, current_event_id, current_operation, dispatch_sequence,
                created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    }
}
