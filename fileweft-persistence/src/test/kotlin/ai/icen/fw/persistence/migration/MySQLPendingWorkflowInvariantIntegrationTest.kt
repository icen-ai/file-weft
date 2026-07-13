package ai.icen.fw.persistence.migration

import com.mysql.cj.jdbc.MysqlDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MySQLPendingWorkflowInvariantIntegrationTest {
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun prepareDatabase() {
        check(System.getenv("FILEWEFT_RUN_MYSQL_TESTS") == "true") {
            "MySQL integration tests must run only through the fail-closed Gradle task"
        }
        val databaseName = mysqlDatabaseName()
        mysqlAdminDataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE IF EXISTS `$databaseName`")
                statement.execute(
                    "CREATE DATABASE `$databaseName` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                )
            }
        }
        dataSource = MysqlDataSource().apply {
            setURL(
                System.getenv("FILEWEFT_MYSQL_URL")
                    ?: "jdbc:mysql://localhost:3306/$databaseName",
            )
            user = System.getenv("FILEWEFT_MYSQL_USER") ?: "root"
            password = System.getenv("FILEWEFT_MYSQL_PASSWORD") ?: ""
        }
    }

    @AfterEach
    fun cleanDatabase() {
        if (::dataSource.isInitialized) {
            val databaseName = mysqlDatabaseName()
            mysqlAdminDataSource().connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP DATABASE IF EXISTS `$databaseName`")
                }
            }
        }
    }

    @Test
    fun `allows workflow history but rejects a second pending workflow`() {
        FlywayMigrationRunner(dataSource).migrate()

        dataSource.connection.use { connection ->
            insertWorkflow(connection, "workflow-approved-1", "tenant-1", "document-1", "APPROVED")
            insertWorkflow(connection, "workflow-approved-2", "tenant-1", "document-1", "APPROVED")
            insertWorkflow(connection, "workflow-rejected", "tenant-1", "document-1", "REJECTED")
            insertWorkflow(connection, "workflow-pending", "tenant-1", "document-1", "PENDING")

            val duplicate = assertFailsWith<SQLException> {
                insertWorkflow(connection, "workflow-pending-duplicate", "tenant-1", "document-1", "PENDING")
            }
            assertMySqlDuplicateKey(duplicate)

            connection.prepareStatement(
                "SELECT state, COUNT(*) FROM fw_workflow_instance " +
                    "WHERE tenant_id = ? AND document_id = ? GROUP BY state ORDER BY state",
            ).use { statement ->
                statement.setString(1, "tenant-1")
                statement.setString(2, "document-1")
                statement.executeQuery().use { result ->
                    val counts = buildMap {
                        while (result.next()) {
                            put(result.getString(1), result.getInt(2))
                        }
                    }
                    assertEquals(mapOf("APPROVED" to 2, "PENDING" to 1, "REJECTED" to 1), counts)
                }
            }
        }
    }

    @Test
    fun `concurrent transactions can commit at most one pending workflow`() {
        FlywayMigrationRunner(dataSource).migrate()

        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val attempts = (1..2).map { sequence ->
                executor.submit<InsertAttempt> {
                    dataSource.connection.use { connection ->
                        connection.autoCommit = false
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS)) {
                            "Concurrent workflow inserts did not receive the start signal"
                        }
                        try {
                            insertWorkflow(
                                connection,
                                "workflow-concurrent-$sequence",
                                "tenant-concurrent",
                                "document-concurrent",
                                "PENDING",
                            )
                            connection.commit()
                            InsertAttempt(succeeded = true, failure = null)
                        } catch (failure: SQLException) {
                            connection.rollback()
                            InsertAttempt(succeeded = false, failure = failure)
                        }
                    }
                }
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both transactions must be ready before release")
            start.countDown()
            val results = attempts.map { it.get(20, TimeUnit.SECONDS) }

            assertEquals(1, results.count(InsertAttempt::succeeded))
            val rejected = results.single { !it.succeeded }.failure
            assertTrue(rejected != null)
            assertMySqlDuplicateKey(rejected)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM fw_workflow_instance " +
                    "WHERE tenant_id = ? AND document_id = ? AND state = 'PENDING'",
            ).use { statement ->
                statement.setString(1, "tenant-concurrent")
                statement.setString(2, "document-concurrent")
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `migration fails closed when duplicate pending workflows already exist`() {
        migrateThroughVersion16()

        dataSource.connection.use { connection ->
            insertWorkflow(connection, "workflow-existing-1", "tenant-existing", "document-existing", "PENDING")
            insertWorkflow(connection, "workflow-existing-2", "tenant-existing", "document-existing", "PENDING")
        }

        val failure = assertFailsWith<FlywayException> {
            FlywayMigrationRunner(dataSource).migrate()
        }
        val duplicateKeyFailure = generateSequence<Throwable>(failure) { it.cause }
            .filterIsInstance<SQLException>()
            .firstOrNull { it.sqlState == MYSQL_INTEGRITY_CONSTRAINT_SQL_STATE && it.errorCode == MYSQL_DUPLICATE_KEY_ERROR }
        assertTrue(
            duplicateKeyFailure != null,
            "V017 must fail because its unique key encounters existing duplicate PENDING rows: ${failure.message}",
        )

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM fw_workflow_instance " +
                        "WHERE tenant_id = 'tenant-existing' " +
                        "AND document_id = 'document-existing' AND state = 'PENDING'",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals(2, result.getInt(1))
                }
            }
        }
    }

    private fun migrateThroughVersion16() {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(FlywayMigrationRunner.migrationLocation(FlywayMigrationRunner.DatabaseProduct.MYSQL))
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .table(FlywayMigrationRunner.HISTORY_TABLE)
            .defaultSchema(mysqlDatabaseName())
            .schemas(mysqlDatabaseName())
            .createSchemas(false)
            .baselineOnMigrate(false)
            .baselineVersion(MigrationVersion.fromVersion("0"))
            .baselineDescription("FileWeft namespace initialization")
            .target(MigrationVersion.fromVersion("16"))
            .validateOnMigrate(true)
            .load()
        flyway.baseline()
        assertEquals(16, flyway.migrate().migrations.size)
    }

    private fun insertWorkflow(
        connection: Connection,
        id: String,
        tenantId: String,
        documentId: String,
        state: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO fw_workflow_instance(" +
                "id, tenant_id, document_id, workflow_type, state, created_time, updated_time" +
                ") VALUES (?, ?, ?, 'APPROVAL', ?, 1, 1)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, tenantId)
            statement.setString(3, documentId)
            statement.setString(4, state)
            statement.executeUpdate()
        }
    }

    private fun assertMySqlDuplicateKey(failure: SQLException) {
        assertEquals(MYSQL_INTEGRITY_CONSTRAINT_SQL_STATE, failure.sqlState)
        assertEquals(MYSQL_DUPLICATE_KEY_ERROR, failure.errorCode)
    }

    private fun mysqlDatabaseName(): String =
        System.getenv("FILEWEFT_MYSQL_DATABASE") ?: "fileweft"

    private fun mysqlAdminDataSource(): DataSource = MysqlDataSource().apply {
        setURL(System.getenv("FILEWEFT_MYSQL_ADMIN_URL") ?: "jdbc:mysql://localhost:3306")
        user = System.getenv("FILEWEFT_MYSQL_ADMIN_USER") ?: "root"
        password = System.getenv("FILEWEFT_MYSQL_ADMIN_PASSWORD") ?: ""
    }

    private data class InsertAttempt(
        val succeeded: Boolean,
        val failure: SQLException?,
    )

    private companion object {
        const val MYSQL_INTEGRITY_CONSTRAINT_SQL_STATE: String = "23000"
        const val MYSQL_DUPLICATE_KEY_ERROR: Int = 1062
    }
}
