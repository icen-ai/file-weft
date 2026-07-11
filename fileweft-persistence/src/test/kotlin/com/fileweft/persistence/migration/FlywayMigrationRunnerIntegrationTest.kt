package com.fileweft.persistence.migration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
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
    fun `applies schema migrations for outbox recovery workflow tasks doctor records agents and upload sessions`() {
        val migrations = FlywayMigrationRunner(dataSource).migrate()

        assertEquals(14, migrations)
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
        }
    }

    private fun tableExists(connection: Connection, tableName: String): Boolean =
        connection.metaData.getTables(null, "public", tableName, arrayOf("TABLE")).use { it.next() }

    private fun columnExists(connection: Connection, tableName: String, columnName: String): Boolean =
        connection.metaData.getColumns(null, "public", tableName, columnName).use { it.next() }
}
