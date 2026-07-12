package ai.icen.fw.persistence.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeliveryDispatchFenceMigrationIntegrationTest {
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
        migrateThroughV022()
    }

    @AfterEach
    fun cleanSchema() {
        if (::dataSource.isInitialized) reset(dataSource.connection)
    }

    @Test
    fun `backfills the newest logical event and historical dispatch sequence for both operations`() {
        insertTarget("delivery-sync", "target-sync", "FAILED", "NOT_REQUESTED")
        insertEvent("sync-event-1", "delivery-sync", DELIVERY_EVENT, "FAILED", 10)
        insertEvent("sync-event-2", "delivery-sync", DELIVERY_EVENT, "FAILED", 20)

        insertTarget("delivery-removal", "target-removal", "SUCCEEDED", "FAILED")
        insertEvent("delivery-success", "delivery-removal", DELIVERY_EVENT, "SUCCESS", 10)
        insertEvent("removal-failure", "delivery-removal", REMOVAL_EVENT, "FAILED", 20)

        assertEquals(2, FlywayMigrationRunner(dataSource).migrate())

        assertEquals(Fence("sync-event-2", "DELIVERY", 2), fence("delivery-sync"))
        assertEquals(Fence("removal-failure", "REMOVAL", 2), fence("delivery-removal"))
        dataSource.connection.use { connection ->
            assertTrue(columnExists(connection, "current_event_id"))
            assertTrue(columnExists(connection, "current_operation"))
            assertTrue(columnExists(connection, "dispatch_sequence"))
            assertTrue(constraintExists(connection, "uq_fw_delivery_current_event"))
            assertTrue(constraintExists(connection, "ck_fw_delivery_dispatch_state"))
            assertTrue(constraintExists(connection, "ck_fw_delivery_removal_requires_success"))
        }
    }

    @Test
    fun `refuses migration while a related worker event is running`() {
        insertTarget("delivery-running", "target-running", "PENDING", "NOT_REQUESTED")
        insertEvent("event-running", "delivery-running", DELIVERY_EVENT, "RUNNING", 10)

        assertFailsWith<FlywayException> { FlywayMigrationRunner(dataSource).migrate() }
        dataSource.connection.use { connection -> assertFalse(columnExists(connection, "current_event_id")) }
    }

    @Test
    fun `refuses migration when a target has no recoverable event`() {
        insertTarget("delivery-orphan", "target-orphan", "FAILED", "NOT_REQUESTED")

        assertFailsWith<FlywayException> { FlywayMigrationRunner(dataSource).migrate() }
        dataSource.connection.use { connection -> assertFalse(columnExists(connection, "current_event_id")) }
    }

    @Test
    fun `refuses migration when one target has multiple active logical events`() {
        insertTarget("delivery-duplicate", "target-duplicate", "PENDING", "NOT_REQUESTED")
        insertEvent("event-pending", "delivery-duplicate", DELIVERY_EVENT, "PENDING", 10)
        insertEvent("event-retry", "delivery-duplicate", DELIVERY_EVENT, "RETRY", 20)

        assertFailsWith<FlywayException> { FlywayMigrationRunner(dataSource).migrate() }
        dataSource.connection.use { connection -> assertFalse(columnExists(connection, "current_event_id")) }
    }

    @Test
    fun `refuses migration instead of preserving an impossible removal state`() {
        insertTarget("delivery-invalid", "target-invalid", "FAILED", "PENDING")
        insertEvent("event-invalid", "delivery-invalid", REMOVAL_EVENT, "PENDING", 10)

        assertFailsWith<FlywayException> { FlywayMigrationRunner(dataSource).migrate() }
        dataSource.connection.use { connection -> assertFalse(columnExists(connection, "current_event_id")) }
    }

    private fun migrateThroughV022() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("22"))
            .load()
            .migrate()
    }

    private fun insertTarget(
        id: String,
        targetId: String,
        deliveryStatus: String,
        removalStatus: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_document_delivery_target(
                    id, tenant_id, document_id, profile_id, target_id, target_name, connector_id,
                    delivery_requirement, owner_ref, delivery_status, external_id, error_message,
                    retry_count, removal_status, removal_error_message, removal_retry_count,
                    delivery_generation, created_time, updated_time
                ) VALUES (?, 'tenant-1', 'document-1', 'profile-1', ?, ?, ?,
                          'REQUIRED', NULL, ?, NULL, NULL, 0, ?, NULL, 0, 0, 1, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, targetId)
                statement.setString(3, targetId)
                statement.setString(4, "connector-$targetId")
                statement.setString(5, deliveryStatus)
                statement.setString(6, removalStatus)
                statement.executeUpdate()
            }
        }
    }

    private fun insertEvent(
        id: String,
        deliveryId: String,
        type: String,
        status: String,
        timestamp: Long,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_outbox_event(
                    id, tenant_id, event_type, payload_json, event_status,
                    retry_count, created_time, updated_time
                ) VALUES (?, 'tenant-1', ?, CAST(? AS jsonb), ?, 0, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, type)
                statement.setString(3, "{\"documentId\":\"document-1\",\"deliveryId\":\"$deliveryId\"}")
                statement.setString(4, status)
                statement.setLong(5, timestamp)
                statement.setLong(6, timestamp)
                statement.executeUpdate()
            }
        }
    }

    private fun fence(deliveryId: String): Fence = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT current_event_id, current_operation, dispatch_sequence FROM fw_document_delivery_target WHERE tenant_id = 'tenant-1' AND id = ?",
        ).use { statement ->
            statement.setString(1, deliveryId)
            statement.executeQuery().use { result ->
                check(result.next()) { "Delivery target was not migrated." }
                Fence(
                    result.getString("current_event_id"),
                    result.getString("current_operation"),
                    result.getLong("dispatch_sequence"),
                )
            }
        }
    }

    private fun columnExists(connection: Connection, columnName: String): Boolean =
        connection.metaData.getColumns(null, "public", "fw_document_delivery_target", columnName).use { it.next() }

    private fun constraintExists(connection: Connection, constraintName: String): Boolean =
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = ? AND conrelid = 'fw_document_delivery_target'::regclass)",
        ).use { statement ->
            statement.setString(1, constraintName)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }

    private data class Fence(
        val eventId: String,
        val operation: String,
        val sequence: Long,
    )

    private companion object {
        const val DELIVERY_EVENT = "document.delivery.target.requested"
        const val REMOVAL_EVENT = "document.delivery.target.removal.requested"
    }
}
