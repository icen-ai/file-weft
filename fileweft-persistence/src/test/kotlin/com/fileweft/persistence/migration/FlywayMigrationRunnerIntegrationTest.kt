package com.fileweft.persistence.migration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.outbox.OutboxLeaseClaim
import com.fileweft.application.task.TaskLeaseClaim
import com.fileweft.persistence.jdbc.JdbcApplicationTransaction
import com.fileweft.persistence.jdbc.JdbcOutboxProcessingRepository
import com.fileweft.persistence.jdbc.JdbcTaskRepository
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlywayMigrationRunnerIntegrationTest {
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun prepareSchema() {
        assumeTrue(System.getenv("FILEWEFT_RUN_POSTGRES_TESTS") == "true")
        dataSource = PGSimpleDataSource().apply {
            setURL(System.getenv("FILEWEFT_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/fileweft")
            user = System.getenv("FILEWEFT_POSTGRES_USER") ?: "fileweft"
            password = System.getenv("FILEWEFT_POSTGRES_PASSWORD") ?: "fileweft-dev"
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA public CASCADE")
                statement.execute("CREATE SCHEMA public")
            }
        }
    }

    @AfterEach
    fun cleanSchema() {
        if (::dataSource.isInitialized) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA public CASCADE")
                    statement.execute("CREATE SCHEMA public")
                }
            }
        }
    }

    @Test
    fun `applies schema migrations for durable runtime recovery and request idempotency`() {
        val migrations = FlywayMigrationRunner(dataSource).migrate()

        assertEquals(20, migrations)
        dataSource.connection.use { connection ->
            assertTrue(tableExists(connection, "fw_file_object"))
            assertTrue(tableExists(connection, "fw_asset"))
            assertTrue(tableExists(connection, "fw_document"))
            assertTrue(tableExists(connection, "fw_document_version"))
            assertTrue(tableExists(connection, "fw_outbox_event"))
            assertTrue(tableExists(connection, "fw_sync_record"))
            assertTrue(tableExists(connection, "fw_audit_record"))
            assertTrue(tableExists(connection, "fw_workflow_instance"))
            assertTrue(tableExists(connection, "fw_workflow_task"))
            assertTrue(tableExists(connection, "fw_task"))
            assertTrue(tableExists(connection, "fw_doctor_record"))
            assertTrue(tableExists(connection, "fw_operation_log"))
            assertTrue(tableExists(connection, "fw_agent_result"))
            assertTrue(tableExists(connection, "fw_agent_suggestion_confirmation"))
            assertTrue(tableExists(connection, "fw_upload_session"))
            assertTrue(tableExists(connection, "fw_upload_session_part"))
            assertTrue(tableExists(connection, "fw_idempotency_record"))
            assertTrue(columnExists(connection, "fw_outbox_event", "next_attempt_time"))
            assertTrue(columnExists(connection, "fw_outbox_event", "last_error"))
            assertTrue(columnExists(connection, "fw_audit_record", "operator_name"))
            assertTrue(columnExists(connection, "fw_task", "lease_expire_time"))
            assertTrue(columnExists(connection, "fw_task", "lease_token"))
            assertTrue(columnExists(connection, "fw_operation_log", "trace_id"))
            assertTrue(columnExists(connection, "fw_outbox_event", "trace_id"))
            assertTrue(columnExists(connection, "fw_outbox_event", "lease_owner"))
            assertTrue(columnExists(connection, "fw_outbox_event", "lease_token"))
            assertTrue(columnExists(connection, "fw_outbox_event", "lease_expire_time"))
            assertTrue(columnExists(connection, "fw_agent_result", "result_json"))
            assertTrue(columnExists(connection, "fw_upload_session", "storage_upload_id"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "removal_status"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "removal_error_message"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "removal_retry_count"))
            assertTrue(columnExists(connection, "fw_document", "delivery_generation"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "delivery_generation"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "tenant_id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "key_digest"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "operator_id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "action"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "resource_type"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "resource_id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "subresource_id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "request_fingerprint"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "record_status"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "result_resource_type"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "result_resource_id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "result_related_resource_type"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "result_related_resource_id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "completed_time"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "created_time"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "updated_time"))
            assertFalse(columnExists(connection, "fw_idempotency_record", "idempotency_key"))
            assertFalse(columnExists(connection, "fw_idempotency_record", "result_json"))
            assertFalse(columnExists(connection, "fw_idempotency_record", "expires_at"))
            assertTrue(indexExists(connection, "fw_sync_record", "idx_fw_sync_tenant_document_connector_status"))
            assertTrue(indexExists(connection, "fw_task", "idx_fw_task_tenant_status_updated"))
            assertTrue(indexExists(connection, "fw_workflow_instance", "uq_fw_workflow_instance_tenant_document_pending"))
            assertTrue(indexExists(connection, "fw_outbox_event", "idx_fw_outbox_running_lease_expiry"))
            assertTrue(indexExists(connection, "fw_outbox_event", "idx_fw_outbox_legacy_running_updated"))
            assertTrue(indexExists(connection, "fw_task", "idx_fw_task_running_lease_expiry"))
            assertTrue(indexExists(connection, "fw_task", "idx_fw_task_legacy_running_updated"))
            assertTrue(indexExists(connection, "fw_idempotency_record", "uq_fw_idempotency_tenant_key_digest"))
            assertTrue(indexExists(connection, "fw_idempotency_record", "idx_fw_idempotency_tenant_resource_time"))
            assertTrue(indexExists(connection, "fw_idempotency_record", "idx_fw_idempotency_in_progress_diagnostic"))
            assertTrue(constraintExists(connection, "fw_idempotency_record", "ck_fw_idempotency_digests"))
            assertTrue(constraintExists(connection, "fw_idempotency_record", "ck_fw_idempotency_binding"))
            assertTrue(constraintExists(connection, "fw_idempotency_record", "ck_fw_idempotency_status"))
            assertTrue(constraintExists(connection, "fw_idempotency_record", "ck_fw_idempotency_result"))
            assertTrue(constraintExists(connection, "fw_idempotency_record", "ck_fw_idempotency_time"))
        }
    }

    @Test
    fun `makes historical running outbox rows reclaimable after the legacy cutoff with a new token lease`() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("17")
            .load()
            .migrate()

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_outbox_event(
                    id, tenant_id, event_type, payload_json, event_status, retry_count, next_attempt_time, created_time, updated_time
                ) VALUES (?, ?, ?, '{}'::jsonb, 'RUNNING', 0, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "historical-running-event")
                statement.setString(2, "tenant-1")
                statement.setString(3, "document.publish.requested")
                statement.setLong(4, 9_999)
                statement.setLong(5, 1)
                statement.setLong(6, 1)
                statement.executeUpdate()
            }
        }

        FlywayMigrationRunner(dataSource).migrate()

        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        val transaction = JdbcApplicationTransaction(dataSource)
        val lease = transaction.execute {
            processing.claimAvailable(1, 100, OutboxLeaseClaim("worker-new", "token-new", 200, 1))
        }.single()

        assertEquals("historical-running-event", lease.event.id.value)
        assertEquals("worker-new", lease.leaseOwner)
        assertEquals("token-new", lease.leaseToken)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT event_status, lease_owner, lease_token, lease_expire_time FROM fw_outbox_event WHERE id = ?",
            ).use { statement ->
                statement.setString(1, "historical-running-event")
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals("RUNNING", result.getString("event_status"))
                    assertEquals("worker-new", result.getString("lease_owner"))
                    assertEquals("token-new", result.getString("lease_token"))
                    assertEquals(200, result.getLong("lease_expire_time"))
                }
            }
        }
    }

    @Test
    fun `makes historical running task rows reclaimable after the legacy cutoff with a new token lease`() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("18")
            .load()
            .migrate()

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_task(
                    id, tenant_id, task_type, payload_json, idempotency_key, task_status, retry_count,
                    next_attempt_time, lease_owner, lease_expire_time, created_time, updated_time
                ) VALUES (?, ?, ?, '{}'::jsonb, ?, 'RUNNING', 0, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "historical-running-task")
                statement.setString(2, "tenant-1")
                statement.setString(3, "document.doctor")
                statement.setString(4, "doctor:document-1")
                statement.setLong(5, 9_999)
                statement.setString(6, "old-worker")
                statement.setLong(7, 0)
                statement.setLong(8, 1)
                statement.setLong(9, 1)
                statement.executeUpdate()
            }
        }

        FlywayMigrationRunner(dataSource).migrate()

        val processing = JdbcTaskRepository(ObjectMapper(), Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC))
        val transaction = JdbcApplicationTransaction(dataSource)
        val lease = transaction.execute {
            processing.claimAvailable(1, 100, TaskLeaseClaim("worker-new", "token-new", 200, 1))
        }.single()

        assertEquals("historical-running-task", lease.task.id.value)
        assertEquals("worker-new", lease.leaseOwner)
        assertEquals("token-new", lease.leaseToken)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT task_status, lease_owner, lease_token, lease_expire_time FROM fw_task WHERE id = ?",
            ).use { statement ->
                statement.setString(1, "historical-running-task")
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals("RUNNING", result.getString("task_status"))
                    assertEquals("worker-new", result.getString("lease_owner"))
                    assertEquals("token-new", result.getString("lease_token"))
                    assertEquals(200, result.getLong("lease_expire_time"))
                }
            }
        }
    }

    @Test
    fun `refuses to migrate when historical pending workflows are duplicated for one document`() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("16")
            .load()
            .migrate()

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_workflow_instance(id, tenant_id, document_id, workflow_type, state, created_time, updated_time)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "workflow-1")
                statement.setString(2, "tenant-1")
                statement.setString(3, "document-1")
                statement.setString(4, "DOCUMENT_REVIEW")
                statement.setLong(5, 100)
                statement.setLong(6, 100)
                statement.executeUpdate()
                statement.setString(1, "workflow-2")
                statement.setLong(5, 101)
                statement.setLong(6, 101)
                statement.executeUpdate()
            }
        }

        val failure = assertFailsWith<FlywayException> {
            FlywayMigrationRunner(dataSource).migrate()
        }
        val messages = generateSequence<Throwable>(failure) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        assertTrue(messages.contains("Cannot enforce one PENDING workflow per tenant/document"))
        assertTrue(messages.contains("tenant_id=tenant-1, document_id=document-1, pending_count=2"))
    }

    private fun tableExists(connection: Connection, tableName: String): Boolean =
        connection.metaData.getTables(null, "public", tableName, arrayOf("TABLE")).use { it.next() }

    private fun columnExists(connection: Connection, tableName: String, columnName: String): Boolean =
        connection.metaData.getColumns(null, "public", tableName, columnName).use { it.next() }

    private fun indexExists(connection: Connection, tableName: String, indexName: String): Boolean =
        connection.metaData.getIndexInfo(null, "public", tableName, false, false).use { indexes ->
            generateSequence { if (indexes.next()) indexes.getString("INDEX_NAME") else null }
                .any { it.equals(indexName, ignoreCase = true) }
        }

    private fun constraintExists(connection: Connection, tableName: String, constraintName: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.table_constraints WHERE table_schema = 'public' AND table_name = ? AND constraint_name = ?",
        ).use { statement ->
            statement.setString(1, tableName)
            statement.setString(2, constraintName)
            statement.executeQuery().use { result -> result.next() }
        }
}
