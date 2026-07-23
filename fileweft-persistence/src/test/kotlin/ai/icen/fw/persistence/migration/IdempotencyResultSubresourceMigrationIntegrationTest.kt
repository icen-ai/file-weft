package ai.icen.fw.persistence.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLException
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdempotencyResultSubresourceMigrationIntegrationTest {
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun prepareSchemaAtV029() {
        assumeTrue(System.getenv("FILEWEFT_RUN_POSTGRES_TESTS") == "true")
        dataSource = PGSimpleDataSource().apply {
            setURL(System.getenv("FILEWEFT_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/fileweft")
            user = System.getenv("FILEWEFT_POSTGRES_USER") ?: "fileweft"
            password = System.getenv("FILEWEFT_POSTGRES_PASSWORD") ?: "fileweft-dev"
        }
        reset(dataSource.connection)
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(FlywayMigrationRunner.migrationLocation(FlywayMigrationRunner.DatabaseProduct.POSTGRESQL))
            .table(FlywayMigrationRunner.HISTORY_TABLE)
            .baselineVersion("0")
            .baselineDescription("FileWeft namespace initialization")
            .target(MigrationVersion.fromVersion("29"))
            .load()
        flyway.baseline()
        flyway.migrate()
    }

    @AfterEach
    fun cleanSchema() {
        if (::dataSource.isInitialized) reset(dataSource.connection)
    }

    @Test
    fun `migration adds a nullable validated result subresource slot over legacy rows`() {
        insertLegacyCompletedRecord("idempotency-legacy", seed = 1)

        assertEquals(1, FlywayMigrationRunner(dataSource).migrate())

        dataSource.connection.use { connection ->
            assertEquals("YES", subresourceColumnNullable(connection))
            assertTrue(constraintExists(connection, "ck_fw_idempotency_result"))
            assertTrue(constraintValidated(connection, "ck_fw_idempotency_result"))
            assertTrue(
                constraintDefinition(connection, "ck_fw_idempotency_result").contains("result_subresource_id"),
            )
        }

        // A completed record persists the stable submit receipt task, while a
        // 0.3.1-shaped row without the column still satisfies the constraint.
        insertCompletedRecord("idempotency-task", seed = 2, subresourceId = "task-1")
        insertLegacyCompletedRecord("idempotency-rolling", seed = 3)
    }

    @Test
    fun `result constraint rejects subresources on in progress rows and malformed values`() {
        assertEquals(1, FlywayMigrationRunner(dataSource).migrate())

        val inProgress = assertFailsWith<PSQLException> {
            insertInProgressRecord("idempotency-running", seed = 1, subresourceId = "task-1")
        }
        assertEquals("23514", inProgress.sqlState)
        assertEquals("ck_fw_idempotency_result", inProgress.serverErrorMessage?.constraint)

        listOf(" task-1", "task-1 ", "task-").forEachIndexed { index, invalid ->
            val failure = assertFailsWith<PSQLException> {
                insertCompletedRecord("idempotency-invalid-$index", seed = 10 + index, subresourceId = invalid)
            }
            assertEquals("23514", failure.sqlState)
            assertEquals("ck_fw_idempotency_result", failure.serverErrorMessage?.constraint)
        }
    }

    private fun insertCompletedRecord(id: String, seed: Int, subresourceId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_idempotency_record(
                    id, tenant_id, key_digest, operator_id, action, resource_type, resource_id,
                    request_fingerprint, record_status, result_resource_type, result_resource_id,
                    result_related_resource_type, result_related_resource_id, result_subresource_id,
                    completed_time, created_time, updated_time
                ) VALUES (?, 'tenant-1', ?, 'operator-1', 'document:review:submit', 'DOCUMENT', 'document-1',
                          ?, 'COMPLETED', 'DOCUMENT', 'document-1', 'WORKFLOW', 'workflow-1', ?, 100, 100, 100)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, keyDigest(seed))
                statement.setString(3, REQUEST_FINGERPRINT)
                statement.setString(4, subresourceId)
                statement.executeUpdate()
            }
        }
    }

    private fun insertLegacyCompletedRecord(id: String, seed: Int) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_idempotency_record(
                    id, tenant_id, key_digest, operator_id, action, resource_type, resource_id,
                    request_fingerprint, record_status, result_resource_type, result_resource_id,
                    result_related_resource_type, result_related_resource_id,
                    completed_time, created_time, updated_time
                ) VALUES (?, 'tenant-1', ?, 'operator-1', 'document:review:submit', 'DOCUMENT', 'document-1',
                          ?, 'COMPLETED', 'DOCUMENT', 'document-1', 'WORKFLOW', 'workflow-1', 100, 100, 100)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, keyDigest(seed))
                statement.setString(3, REQUEST_FINGERPRINT)
                statement.executeUpdate()
            }
        }
    }

    private fun insertInProgressRecord(id: String, seed: Int, subresourceId: String?) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_idempotency_record(
                    id, tenant_id, key_digest, operator_id, action, resource_type, resource_id,
                    request_fingerprint, record_status, result_subresource_id, created_time, updated_time
                ) VALUES (?, 'tenant-1', ?, 'operator-1', 'document:review:submit', 'DOCUMENT', 'document-1',
                          ?, 'IN_PROGRESS', ?, 100, 100)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, keyDigest(seed))
                statement.setString(3, REQUEST_FINGERPRINT)
                statement.setString(4, subresourceId)
                statement.executeUpdate()
            }
        }
    }

    private fun subresourceColumnNullable(connection: Connection): String =
        connection.metaData.getColumns(null, "public", "fw_idempotency_record", "result_subresource_id")
            .use { columns ->
                check(columns.next()) { "result_subresource_id column was not created." }
                columns.getString("IS_NULLABLE")
            }

    private fun constraintExists(connection: Connection, constraintName: String): Boolean =
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = ? AND conrelid = 'fw_idempotency_record'::regclass)",
        ).use { statement ->
            statement.setString(1, constraintName)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }

    private fun constraintValidated(connection: Connection, constraintName: String): Boolean =
        connection.prepareStatement(
            "SELECT convalidated FROM pg_constraint WHERE conname = ? AND conrelid = 'fw_idempotency_record'::regclass",
        ).use { statement ->
            statement.setString(1, constraintName)
            statement.executeQuery().use { result ->
                check(result.next()) { "Constraint $constraintName was not found." }
                result.getBoolean(1)
            }
        }

    private fun constraintDefinition(connection: Connection, constraintName: String): String =
        connection.prepareStatement(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ? AND conrelid = 'fw_idempotency_record'::regclass",
        ).use { statement ->
            statement.setString(1, constraintName)
            statement.executeQuery().use { result ->
                check(result.next()) { "Constraint $constraintName was not found." }
                result.getString(1)
            }
        }

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }

    private companion object {
        val REQUEST_FINGERPRINT = "sha256:" + "f".repeat(64)

        fun keyDigest(seed: Int): String = "sha256:" + seed.toString(16).padStart(64, '0')
    }
}
