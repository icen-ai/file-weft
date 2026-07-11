package com.fileweft.persistence.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
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
    fun `applies schema migrations for outbox recovery workflow tasks doctor records agents upload sessions and production indexes`() {
        val migrations = FlywayMigrationRunner(dataSource).migrate()

        assertEquals(17, migrations)
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
            assertTrue(columnExists(connection, "fw_outbox_event", "next_attempt_time"))
            assertTrue(columnExists(connection, "fw_outbox_event", "last_error"))
            assertTrue(columnExists(connection, "fw_audit_record", "operator_name"))
            assertTrue(columnExists(connection, "fw_task", "lease_expire_time"))
            assertTrue(columnExists(connection, "fw_operation_log", "trace_id"))
            assertTrue(columnExists(connection, "fw_outbox_event", "trace_id"))
            assertTrue(columnExists(connection, "fw_agent_result", "result_json"))
            assertTrue(columnExists(connection, "fw_upload_session", "storage_upload_id"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "removal_status"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "removal_error_message"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "removal_retry_count"))
            assertTrue(columnExists(connection, "fw_document", "delivery_generation"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "delivery_generation"))
            assertTrue(indexExists(connection, "fw_sync_record", "idx_fw_sync_tenant_document_connector_status"))
            assertTrue(indexExists(connection, "fw_task", "idx_fw_task_tenant_status_updated"))
            assertTrue(indexExists(connection, "fw_workflow_instance", "uq_fw_workflow_instance_tenant_document_pending"))
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
}
