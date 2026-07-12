package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fileweft.application.doctor.DocumentDoctorTaskHandler
import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.application.task.BackgroundTaskStatus
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorReport
import com.fileweft.core.result.DoctorStatus
import com.fileweft.persistence.migration.FlywayMigrationRunner
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JdbcDocumentDoctorTaskQueryRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private val objectMapper = ObjectMapper()

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
    fun `returns exact terminal task and report without worker internals`() {
        insertDocument("tenant-a", "document-a", "finance")
        insertTask("tenant-a", "document-a", "task-a", DocumentDoctorTaskHandler.TASK_TYPE, "SUCCESS", 101, 202)
        insertReport(
            tenantId = "tenant-a",
            documentId = "document-a",
            taskId = "task-a",
            report = report("tenant-a", "document-a"),
        )

        val result = transaction {
            repository().findTask(
                Identifier("tenant-a"),
                Identifier("document-a"),
                Identifier("task-a"),
                DocumentFolderReadScope(listOf("finance")),
            )
        }

        val view = assertNotNull(result)
        assertEquals(BackgroundTaskStatus.SUCCESS, view.status)
        assertEquals(101, view.createdTime)
        assertEquals(202, view.updatedTime)
        assertEquals(DoctorStatus.HEALTHY, assertNotNull(view.report).status)
        assertEquals("storage", view.report?.checks?.single()?.checkerName)
    }

    @Test
    fun `non terminal tasks never expose a prematurely written report`() {
        insertDocument("tenant-a", "document-a", "finance")
        insertTask(
            "tenant-a", "document-a", "task-running", DocumentDoctorTaskHandler.TASK_TYPE, "RUNNING", 10, 20,
        )
        insertReport("tenant-a", "document-a", "task-running", report("tenant-a", "document-a"))

        val result = transaction {
            repository().findTask(
                Identifier("tenant-a"), Identifier("document-a"), Identifier("task-running"), null,
            )
        }

        assertEquals(BackgroundTaskStatus.RUNNING, assertNotNull(result).status)
        assertNull(result.report)
    }

    @Test
    fun `failed task never exposes a stale report from an earlier lost attempt`() {
        insertDocument("tenant-a", "document-a", "finance")
        insertTask(
            "tenant-a", "document-a", "task-failed", DocumentDoctorTaskHandler.TASK_TYPE, "FAILED", 10, 30,
        )
        insertReport("tenant-a", "document-a", "task-failed", report("tenant-a", "document-a"))

        val result = transaction {
            repository().findTask(
                Identifier("tenant-a"), Identifier("document-a"), Identifier("task-failed"), null,
            )
        }

        assertEquals(BackgroundTaskStatus.FAILED, assertNotNull(result).status)
        assertNull(result.report)
    }

    @Test
    fun `writer and reader keep a canonical report across host mapper configuration changes`() {
        insertDocument("tenant-a", "document-a", "finance")
        insertTask("tenant-a", "document-a", "task-a", DocumentDoctorTaskHandler.TASK_TYPE, "SUCCESS", 1, 2)
        val snakeCaseWriter = ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        transaction {
            JdbcDoctorReportRepository(
                snakeCaseWriter,
                Clock.fixed(Instant.ofEpochMilli(200), ZoneOffset.UTC),
            ).save(
                Identifier("tenant-a"),
                Identifier("document-a"),
                Identifier("task-a"),
                report("tenant-a", "document-a"),
            )
        }

        val result = transaction {
            JdbcDocumentDoctorTaskQueryRepository(ObjectMapper()).findTask(
                Identifier("tenant-a"), Identifier("document-a"), Identifier("task-a"), null,
            )
        }
        val raw = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT report_json::text FROM fw_doctor_record WHERE tenant_id = ? AND task_id = ?",
            ).use { statement ->
                statement.setString(1, "tenant-a")
                statement.setString(2, "task-a")
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }

        assertEquals(DoctorStatus.HEALTHY, assertNotNull(result).report?.status)
        val stored = objectMapper.readTree(raw)
        assertEquals(1, stored.path("schemaVersion").intValue())
        assertEquals("tenant-a", stored.path("tenantId").asText())
        kotlin.test.assertTrue(!stored.has("tenant_id"))
    }

    @Test
    fun `tenant document type and folder scopes all fail closed`() {
        insertDocument("tenant-a", "document-a", "finance")
        insertTask("tenant-a", "document-a", "task-a", DocumentDoctorTaskHandler.TASK_TYPE, "PENDING", 1, 1)
        insertTask("tenant-a", "document-a", "task-other-type", "OTHER", "PENDING", 1, 1)
        val repository = repository()

        assertNull(transaction {
            repository.findTask(Identifier("tenant-b"), Identifier("document-a"), Identifier("task-a"), null)
        })
        assertNull(transaction {
            repository.findTask(Identifier("tenant-a"), Identifier("document-b"), Identifier("task-a"), null)
        })
        assertNull(transaction {
            repository.findTask(
                Identifier("tenant-a"), Identifier("document-a"), Identifier("task-other-type"), null,
            )
        })
        assertNull(transaction {
            repository.findTask(
                Identifier("tenant-a"), Identifier("document-a"), Identifier("task-a"),
                DocumentFolderReadScope(listOf("operations")),
            )
        })
        assertNull(transaction {
            repository.findTask(
                Identifier("tenant-a"), Identifier("document-a"), Identifier("task-a"),
                DocumentFolderReadScope(emptyList()),
            )
        })
    }

    @Test
    fun `rejects a persisted report whose embedded scope was tampered`() {
        insertDocument("tenant-a", "document-a", "finance")
        insertTask("tenant-a", "document-a", "task-a", DocumentDoctorTaskHandler.TASK_TYPE, "SUCCESS", 1, 2)
        insertReport("tenant-a", "document-a", "task-a", report("tenant-b", "document-a"))

        assertFailsWith<IllegalStateException> {
            transaction {
                repository().findTask(
                    Identifier("tenant-a"), Identifier("document-a"), Identifier("task-a"), null,
                )
            }
        }
    }

    @Test
    fun `requires a caller bound JDBC transaction`() {
        assertFailsWith<IllegalStateException> {
            repository().findTask(
                Identifier("tenant-a"), Identifier("document-a"), Identifier("task-a"), null,
            )
        }
    }

    private fun repository() = JdbcDocumentDoctorTaskQueryRepository(objectMapper)

    private fun report(tenantId: String, documentId: String) = DoctorReport(
        tenantId = Identifier(tenantId),
        documentId = Identifier(documentId),
        checks = listOf(
            DoctorCheckResult("storage", DoctorStatus.HEALTHY, "Storage is reachable."),
        ),
        inspectedAt = 99,
    )

    private fun insertDocument(tenantId: String, documentId: String, folderId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_asset(id, tenant_id, file_id, asset_type, metadata_json, created_time, updated_time)
                VALUES (?, ?, ?, 'DOCUMENT', ?::jsonb, 1, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "asset-$documentId")
                statement.setString(2, tenantId)
                statement.setString(3, "file-$documentId")
                statement.setString(4, "{\"catalog.folder-id\":\"$folderId\"}")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO fw_document(
                    id, tenant_id, asset_id, doc_no, title, lifecycle_state,
                    delivery_generation, created_time, updated_time
                ) VALUES (?, ?, ?, ?, ?, 'DRAFT', 0, 1, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, documentId)
                statement.setString(2, tenantId)
                statement.setString(3, "asset-$documentId")
                statement.setString(4, "DOC-$documentId")
                statement.setString(5, "Doctor test")
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun insertTask(
        tenantId: String,
        documentId: String,
        taskId: String,
        type: String,
        status: String,
        createdTime: Long,
        updatedTime: Long,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_task(
                    id, tenant_id, task_type, business_id, payload_json, idempotency_key,
                    task_status, retry_count, next_attempt_time, created_time, updated_time, last_error
                ) VALUES (?, ?, ?, ?, '{"requestedBy":"private-user"}'::jsonb, ?, ?, 0, 0, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, taskId)
                statement.setString(2, tenantId)
                statement.setString(3, type)
                statement.setString(4, documentId)
                statement.setString(5, "key-$taskId")
                statement.setString(6, status)
                statement.setLong(7, createdTime)
                statement.setLong(8, updatedTime)
                statement.setString(9, "private worker failure")
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun insertReport(
        tenantId: String,
        documentId: String,
        taskId: String,
        report: DoctorReport,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_doctor_record(
                    id, tenant_id, document_id, task_id, doctor_status, report_json, created_time, updated_time
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, 1, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, taskId)
                statement.setString(2, tenantId)
                statement.setString(3, documentId)
                statement.setString(4, taskId)
                statement.setString(5, report.status.name)
                statement.setString(6, objectMapper.writeValueAsString(report))
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun <T> transaction(action: () -> T): T = JdbcApplicationTransaction(dataSource).execute(action)

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }
}
