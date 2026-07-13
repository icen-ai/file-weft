package ai.icen.fw.persistence.migration

import com.mysql.cj.jdbc.MysqlDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MySQLFlywayMigrationRunnerIntegrationTest {
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun prepareDatabase() {
        assumeTrue(System.getenv("FILEWEFT_RUN_MYSQL_TESTS") == "true")
        val databaseName = System.getenv("FILEWEFT_MYSQL_DATABASE") ?: "fileweft"
        val adminDataSource = MysqlDataSource().apply {
            setURL(System.getenv("FILEWEFT_MYSQL_ADMIN_URL") ?: "jdbc:mysql://localhost:3306")
            user = System.getenv("FILEWEFT_MYSQL_ADMIN_USER") ?: "root"
            password = System.getenv("FILEWEFT_MYSQL_ADMIN_PASSWORD") ?: ""
        }
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE IF EXISTS `$databaseName`")
                statement.execute("CREATE DATABASE `$databaseName` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
            }
        }
        dataSource = MysqlDataSource().apply {
            setURL(System.getenv("FILEWEFT_MYSQL_URL") ?: "jdbc:mysql://localhost:3306/$databaseName")
            user = System.getenv("FILEWEFT_MYSQL_USER") ?: "root"
            password = System.getenv("FILEWEFT_MYSQL_PASSWORD") ?: ""
        }
    }

    @AfterEach
    fun cleanDatabase() {
        if (::dataSource.isInitialized) {
            val databaseName = System.getenv("FILEWEFT_MYSQL_DATABASE") ?: "fileweft"
            val adminDataSource = MysqlDataSource().apply {
                setURL(System.getenv("FILEWEFT_MYSQL_ADMIN_URL") ?: "jdbc:mysql://localhost:3306")
                user = System.getenv("FILEWEFT_MYSQL_ADMIN_USER") ?: "root"
                password = System.getenv("FILEWEFT_MYSQL_ADMIN_PASSWORD") ?: ""
            }
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP DATABASE IF EXISTS `$databaseName`")
                }
            }
        }
    }

    @Test
    fun `applies all mysql migrations and validates`() {
        val migrations = FlywayMigrationRunner(dataSource).migrate()
        assertEquals(26, migrations)

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
            assertTrue(tableExists(connection, "fw_document_delivery_target"))
        }

        FlywayMigrationRunner(dataSource).validate()
    }

    private fun tableExists(connection: Connection, tableName: String): Boolean =
        connection.metaData.getTables(null, null, tableName, arrayOf("TABLE")).use { result ->
            result.next()
        }
}
