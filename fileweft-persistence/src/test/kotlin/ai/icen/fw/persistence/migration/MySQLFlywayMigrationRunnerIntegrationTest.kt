package ai.icen.fw.persistence.migration

import com.mysql.cj.jdbc.MysqlDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MySQLFlywayMigrationRunnerIntegrationTest {
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun prepareDatabase() {
        check(System.getenv("FILEWEFT_RUN_MYSQL_TESTS") == "true") {
            "MySQL integration tests must run only through the fail-closed Gradle task"
        }
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
        assertEquals(30, migrations)

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
            assertTrue(tableExists(connection, "fw_idempotency_record"))
            assertTrue(tableExists(connection, "fw_document_delivery_target"))
            connection.metaData.getColumns(connection.catalog, null, "fw_workflow_instance", "submitted_by").use { result ->
                assertTrue(result.next(), "Missing fw_workflow_instance.submitted_by metadata")
                assertEquals(DatabaseMetaData.columnNullable, result.getInt("NULLABLE"))
                assertEquals(256, result.getInt("COLUMN_SIZE"))
            }
            assertBinaryBusinessColumnCollations(connection)
            assertColumnNotNullable(connection, "fw_agent_suggestion_confirmation", "confirmed_by")
            assertFailsWith<SQLException> {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO fw_agent_suggestion_confirmation(
                            id, tenant_id, task_id, suggestion_id, confirmed_by,
                            confirmed_time, created_time, updated_time
                        ) VALUES ('null-confirmation', 'tenant-1', 'task-1', 'suggestion-1', NULL, 1, 1, 1)
                        """.trimIndent(),
                    )
                }
            }
        }

        FlywayMigrationRunner(dataSource).validate()
    }

    private fun tableExists(connection: Connection, tableName: String): Boolean =
        connection.metaData.getTables(null, null, tableName, arrayOf("TABLE")).use { result ->
            result.next()
        }

    private fun columnExists(connection: Connection, tableName: String, columnName: String): Boolean =
        connection.metaData.getColumns(connection.catalog, null, tableName, columnName).use { result ->
            result.next()
        }

    private fun assertColumnNotNullable(connection: Connection, tableName: String, columnName: String) {
        connection.metaData.getColumns(connection.catalog, null, tableName, columnName).use { result ->
            assertTrue(result.next(), "Missing $tableName.$columnName metadata")
            assertEquals(DatabaseMetaData.columnNoNulls, result.getInt("NULLABLE"))
        }
    }

    private fun assertBinaryBusinessColumnCollations(connection: Connection) {
        connection.prepareStatement(
            "SELECT PAD_ATTRIBUTE FROM information_schema.COLLATIONS WHERE COLLATION_NAME = ?",
        ).use { statement ->
            statement.setString(1, "utf8mb4_0900_bin")
            statement.executeQuery().use { result ->
                assertTrue(result.next(), "MySQL 8 must provide utf8mb4_0900_bin")
                assertEquals("NO PAD", result.getString("PAD_ATTRIBUTE"))
            }
        }

        val tableCollations = linkedMapOf<String, String>()
        connection.prepareStatement(
            """
            SELECT TABLE_NAME, TABLE_COLLATION
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND LEFT(TABLE_NAME, 3) = 'fw_'
            ORDER BY TABLE_NAME
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { result ->
                while (result.next()) {
                    tableCollations[result.getString("TABLE_NAME")] = result.getString("TABLE_COLLATION")
                }
            }
        }
        assertEquals(18, tableCollations.size, "Expected every FileWeft business table")
        val nonBinaryTableDefaults = tableCollations.filterValues { collation -> collation != "utf8mb4_0900_bin" }
        assertTrue(
            nonBinaryTableDefaults.isEmpty(),
            "Every FileWeft business table default must use utf8mb4_0900_bin; found $nonBinaryTableDefaults",
        )

        val collations = linkedMapOf<String, String>()
        val generatedColumns = linkedSetOf<String>()
        connection.prepareStatement(
            """
            SELECT TABLE_NAME, COLUMN_NAME, COLLATION_NAME, EXTRA
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND LEFT(TABLE_NAME, 3) = 'fw_'
              AND COLLATION_NAME IS NOT NULL
            ORDER BY TABLE_NAME, ORDINAL_POSITION
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val qualifiedName = "${result.getString("TABLE_NAME")}.${result.getString("COLUMN_NAME")}"
                    collations[qualifiedName] = result.getString("COLLATION_NAME")
                    if (result.getString("EXTRA").contains("GENERATED", ignoreCase = true)) {
                        generatedColumns += qualifiedName
                    }
                }
            }
        }

        assertTrue(collations.isNotEmpty(), "Expected FileWeft textual business columns")
        val nonBinary = collations.filterValues { collation -> collation != "utf8mb4_0900_bin" }
        assertTrue(
            nonBinary.isEmpty(),
            "Every FileWeft textual business column must use utf8mb4_0900_bin; found $nonBinary",
        )
        assertEquals(
            setOf(
                "fw_workflow_instance.pending_tenant_id",
                "fw_workflow_instance.pending_document_id",
            ),
            generatedColumns.filter { column -> column.startsWith("fw_workflow_instance.pending_") }.toSet(),
        )
    }
}
