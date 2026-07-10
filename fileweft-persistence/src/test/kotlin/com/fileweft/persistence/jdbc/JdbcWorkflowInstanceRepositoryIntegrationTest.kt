package com.fileweft.persistence.jdbc

import com.fileweft.core.id.Identifier
import com.fileweft.domain.workflow.WorkflowInstance
import com.fileweft.domain.workflow.WorkflowState
import com.fileweft.domain.workflow.WorkflowTask
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
import kotlin.test.assertNull

class JdbcWorkflowInstanceRepositoryIntegrationTest {
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
        FlywayMigrationRunner(dataSource).migrate()
    }

    @AfterEach
    fun cleanSchema() {
        if (::dataSource.isInitialized) reset(dataSource.connection)
    }

    @Test
    fun `persists workflow task decisions and exposes only active workflow in its tenant`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcWorkflowInstanceRepository(Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
        val workflow = workflow()
        transaction.execute { repository.save(workflow) }

        val active = transaction.execute { repository.findActiveByDocument(Identifier("tenant-1"), Identifier("document-1")) }
        requireNotNull(active)
        assertEquals(WorkflowState.PENDING, active.state)
        assertEquals(Identifier("reviewer-1"), active.tasks.single().assigneeId)
        assertNull(transaction.execute { repository.findActiveByDocument(Identifier("tenant-2"), Identifier("document-1")) })

        active.approve(active.tasks.single().id, Identifier("reviewer-1"), "approved")
        transaction.execute { repository.save(active) }
        val restored = transaction.execute { repository.findById(Identifier("tenant-1"), active.id) }

        requireNotNull(restored)
        assertEquals(WorkflowState.APPROVED, restored.state)
        assertEquals("approved", restored.tasks.single().comment)
        assertNull(transaction.execute { repository.findActiveByDocument(Identifier("tenant-1"), Identifier("document-1")) })
    }

    private fun workflow() = WorkflowInstance(
        Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DOCUMENT_REVIEW",
        tasks = listOf(WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-1"))),
    )

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }
}
