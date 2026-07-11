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
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `locks one workflow decision so a second reviewer cannot read a stale aggregate`() {
        val repository = JdbcWorkflowInstanceRepository(Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
        JdbcApplicationTransaction(dataSource).execute { repository.save(workflow()) }

        dataSource.connection.use { firstConnection ->
            firstConnection.autoCommit = false
            try {
                JdbcConnectionContext.withConnection(firstConnection) {
                    requireNotNull(repository.findForDecision(Identifier("tenant-1"), Identifier("workflow-1")))
                }

                dataSource.connection.use { secondConnection ->
                    secondConnection.autoCommit = false
                    try {
                        secondConnection.createStatement().use { statement ->
                            statement.execute("SET LOCAL lock_timeout = '250ms'")
                        }
                        val failure = assertFailsWith<PSQLException> {
                            JdbcConnectionContext.withConnection(secondConnection) {
                                repository.findForDecision(Identifier("tenant-1"), Identifier("workflow-1"))
                            }
                        }
                        assertEquals("55P03", failure.sqlState)
                    } finally {
                        secondConnection.rollback()
                    }
                }
            } finally {
                firstConnection.rollback()
            }
        }
    }

    @Test
    fun `serializes parallel task decisions without overwriting the first reviewer`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcWorkflowInstanceRepository(Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
        val workflow = WorkflowInstance(
            Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DUAL_REVIEW",
            tasks = listOf(
                WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-1")),
                WorkflowTask(Identifier("task-2"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-2")),
            ),
        )
        transaction.execute { repository.save(workflow) }

        transaction.execute {
            val firstDecision = requireNotNull(repository.findForDecision(Identifier("tenant-1"), workflow.id))
            firstDecision.approve(Identifier("task-1"), Identifier("reviewer-1"))
            repository.save(firstDecision)
        }
        transaction.execute {
            val secondDecision = requireNotNull(repository.findForDecision(Identifier("tenant-1"), workflow.id))
            assertEquals(WorkflowState.PENDING, secondDecision.state)
            assertEquals(com.fileweft.domain.workflow.WorkflowTaskState.APPROVED, secondDecision.tasks.first().state)
            secondDecision.approve(Identifier("task-2"), Identifier("reviewer-2"))
            repository.save(secondDecision)
        }

        val restored = transaction.execute { requireNotNull(repository.findById(Identifier("tenant-1"), workflow.id)) }
        assertEquals(WorkflowState.APPROVED, restored.state)
        assertEquals(
            listOf(
                com.fileweft.domain.workflow.WorkflowTaskState.APPROVED,
                com.fileweft.domain.workflow.WorkflowTaskState.APPROVED,
            ),
            restored.tasks.map { it.state },
        )
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
