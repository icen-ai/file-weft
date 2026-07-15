package ai.icen.fw.persistence.migration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KingbaseFlywayMigrationRunnerIntegrationTest {
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun prepareSchema() {
        check(System.getenv("FILEWEFT_RUN_KINGBASE_TESTS") == "true") {
            "Kingbase integration tests must run only through the fail-closed Gradle task"
        }
        val driverClassName = System.getenv("FILEWEFT_KINGBASE_DRIVER") ?: "com.kingbase8.Driver"
        Class.forName(driverClassName)
        val url = System.getenv("FILEWEFT_KINGBASE_URL") ?: "jdbc:kingbase8://localhost:54321/fileweft"
        val user = System.getenv("FILEWEFT_KINGBASE_USER") ?: "system"
        val password = System.getenv("FILEWEFT_KINGBASE_PASSWORD") ?: "kingbase"
        val schema = System.getenv("FILEWEFT_KINGBASE_SCHEMA") ?: "public"

        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                statement.execute("CREATE SCHEMA $schema")
            }
        }

        dataSource = object : DataSource {
            override fun getConnection(): Connection = DriverManager.getConnection(url, user, password)
            override fun getConnection(username: String?, password: String?): Connection =
                DriverManager.getConnection(url, username ?: user, password ?: password)
            override fun getLogWriter(): java.io.PrintWriter = throw UnsupportedOperationException()
            override fun setLogWriter(out: java.io.PrintWriter) = throw UnsupportedOperationException()
            override fun setLoginTimeout(seconds: Int) = throw UnsupportedOperationException()
            override fun getLoginTimeout(): Int = throw UnsupportedOperationException()
            override fun getParentLogger(): java.util.logging.Logger = throw UnsupportedOperationException()
            override fun <T : Any> unwrap(iface: Class<T>): T = throw UnsupportedOperationException()
            override fun isWrapperFor(iface: Class<*>?): Boolean = false
        }
    }

    @AfterEach
    fun cleanSchema() {
        if (::dataSource.isInitialized) {
            val schema = System.getenv("FILEWEFT_KINGBASE_SCHEMA") ?: "public"
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                }
            }
        }
    }

    @Test
    fun `applies all kingbase migrations and validates`() {
        val migrations = FlywayMigrationRunner(dataSource).migrate()
        assertEquals(32, migrations)

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
            assertTrue(columnExists(connection, "fw_upload_session", "claimed_idempotency_key_digest"))
            assertTrue(columnExists(connection, "fw_upload_session", "claimed_time"))
            assertTrue(tableExists(connection, "fw_presigned_upload_session"))
            assertTrue(columnExists(connection, "fw_presigned_upload_session", "asset_file_object_id"))
            assertTrue(tableExists(connection, "fw_idempotency_record"))
            assertTrue(tableExists(connection, "fw_document_delivery_target"))
            assertTrue(columnExists(connection, "fw_workflow_instance", "submitted_by"))
        }

        FlywayMigrationRunner(dataSource).validate()
    }

    private fun tableExists(connection: Connection, tableName: String): Boolean =
        connection.metaData.getTables(null, null, tableName, arrayOf("TABLE")).use { result ->
            result.next()
        }

    private fun columnExists(connection: Connection, tableName: String, columnName: String): Boolean =
        connection.metaData.getColumns(null, null, tableName, columnName).use { result ->
            result.next()
        }
}
