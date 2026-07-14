package ai.icen.fw.persistence.jdbc

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.workflow.WorkflowInstance
import ai.icen.fw.domain.workflow.WorkflowState
import ai.icen.fw.domain.workflow.WorkflowTask
import ai.icen.fw.domain.workflow.WorkflowWithdrawalConflictException
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
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
import java.time.ZoneId
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
        assertEquals(Identifier("submitter-1"), active.submittedBy)
        assertEquals(Identifier("reviewer-1"), active.tasks.single().assigneeId)
        assertNull(transaction.execute { repository.findActiveByDocument(Identifier("tenant-2"), Identifier("document-1")) })

        active.approve(active.tasks.single().id, Identifier("reviewer-1"), "审批者一", "approved")
        transaction.execute { repository.save(active) }
        val restored = transaction.execute { repository.findById(Identifier("tenant-1"), active.id) }

        requireNotNull(restored)
        assertEquals(WorkflowState.APPROVED, restored.state)
        assertEquals(Identifier("submitter-1"), restored.submittedBy)
        assertEquals("approved", restored.tasks.single().comment)
        assertEquals(Identifier("reviewer-1"), restored.tasks.single().decisionOperatorId)
        assertEquals("审批者一", restored.tasks.single().decisionOperatorName)
        assertNull(transaction.execute { repository.findActiveByDocument(Identifier("tenant-1"), Identifier("document-1")) })
    }

    @Test
    fun `round trips a withdrawn workflow with immutable submitter and unfinished task evidence`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcWorkflowInstanceRepository(Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
        transaction.execute { repository.save(workflow()) }

        val pending = transaction.execute {
            requireNotNull(repository.findForDecision(Identifier("tenant-1"), Identifier("workflow-1")))
        }
        pending.withdraw()
        transaction.execute { repository.save(pending) }

        val restored = transaction.execute {
            requireNotNull(repository.findById(Identifier("tenant-1"), Identifier("workflow-1")))
        }
        assertEquals(WorkflowState.WITHDRAWN, restored.state)
        assertEquals(Identifier("submitter-1"), restored.submittedBy)
        assertEquals(ai.icen.fw.domain.workflow.WorkflowTaskState.PENDING, restored.tasks.single().state)
        assertNull(
            transaction.execute {
                repository.findActiveByDocument(Identifier("tenant-1"), Identifier("document-1"))
            },
        )
    }

    @Test
    fun `serializes concurrent withdrawals so the loser cannot read a stale pending aggregate`() {
        val repository = JdbcWorkflowInstanceRepository(Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
        JdbcApplicationTransaction(dataSource).execute { repository.save(workflow()) }

        dataSource.connection.use { firstConnection ->
            firstConnection.autoCommit = false
            var committed = false
            try {
                JdbcConnectionContext.withConnection(firstConnection) {
                    val firstWithdrawal = requireNotNull(
                        repository.findForDecision(Identifier("tenant-1"), Identifier("workflow-1")),
                    )
                    firstWithdrawal.withdraw()
                    repository.save(firstWithdrawal)
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
                firstConnection.commit()
                committed = true
            } finally {
                if (!committed) firstConnection.rollback()
            }
        }

        val losingWithdrawal = JdbcApplicationTransaction(dataSource).execute {
            requireNotNull(repository.findForDecision(Identifier("tenant-1"), Identifier("workflow-1")))
        }
        assertEquals(WorkflowState.WITHDRAWN, losingWithdrawal.state)
        assertFailsWith<WorkflowWithdrawalConflictException> { losingWithdrawal.withdraw() }
    }

    @Test
    fun `serializes parallel task decisions without overwriting the first reviewer`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val clock = MutableClock(Instant.ofEpochMilli(100))
        val repository = JdbcWorkflowInstanceRepository(clock)
        val workflow = WorkflowInstance(
            Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DUAL_REVIEW",
            tasks = listOf(
                WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-1")),
                WorkflowTask(Identifier("task-2"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-2")),
            ),
        )
        transaction.execute { repository.save(workflow) }

        clock.instant = Instant.ofEpochMilli(200)
        transaction.execute {
            val firstDecision = requireNotNull(repository.findForDecision(Identifier("tenant-1"), workflow.id))
            firstDecision.approve(Identifier("task-1"), Identifier("reviewer-1"), "审批人甲", null)
            repository.save(firstDecision)
        }
        clock.instant = Instant.ofEpochMilli(300)
        transaction.execute {
            val secondDecision = requireNotNull(repository.findForDecision(Identifier("tenant-1"), workflow.id))
            assertEquals(WorkflowState.PENDING, secondDecision.state)
            assertEquals(ai.icen.fw.domain.workflow.WorkflowTaskState.APPROVED, secondDecision.tasks.first().state)
            secondDecision.approve(Identifier("task-2"), Identifier("reviewer-2"), "审批人乙", null)
            repository.save(secondDecision)
        }

        val restored = transaction.execute { requireNotNull(repository.findById(Identifier("tenant-1"), workflow.id)) }
        assertEquals(WorkflowState.APPROVED, restored.state)
        assertEquals(
            listOf(
                ai.icen.fw.domain.workflow.WorkflowTaskState.APPROVED,
                ai.icen.fw.domain.workflow.WorkflowTaskState.APPROVED,
            ),
            restored.tasks.map { it.state },
        )
        assertEquals(
            listOf(Identifier("reviewer-1"), Identifier("reviewer-2")),
            restored.tasks.map { task -> task.decisionOperatorId },
        )
        assertEquals(listOf("审批人甲", "审批人乙"), restored.tasks.map { task -> task.decisionOperatorName })
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT id, decided_time, updated_time FROM fw_workflow_task WHERE tenant_id = ? ORDER BY id",
            ).use { statement ->
                statement.setString(1, "tenant-1")
                statement.executeQuery().use { result ->
                    result.next()
                    assertEquals("task-1", result.getString("id"))
                    assertEquals(200, result.getLong("decided_time"))
                    assertEquals(200, result.getLong("updated_time"))
                    result.next()
                    assertEquals("task-2", result.getString("id"))
                    assertEquals(300, result.getLong("decided_time"))
                    assertEquals(300, result.getLong("updated_time"))
                }
            }
        }
    }

    @Test
    fun `rejects a task id collision instead of rewriting another tenant workflow`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcWorkflowInstanceRepository(Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
        transaction.execute { repository.save(workflow()) }
        val colliding = WorkflowInstance(
            Identifier("workflow-2"), Identifier("tenant-2"), Identifier("document-2"), "DOCUMENT_REVIEW",
            tasks = listOf(
                WorkflowTask(
                    Identifier("task-1"), Identifier("tenant-2"), Identifier("workflow-2"), Identifier("reviewer-2"),
                ),
            ),
        )

        assertFailsWith<IllegalStateException> {
            transaction.execute { repository.save(colliding) }
        }

        val original = transaction.execute {
            requireNotNull(repository.findById(Identifier("tenant-1"), Identifier("workflow-1")))
        }
        assertEquals(Identifier("tenant-1"), original.tasks.single().tenantId)
        assertEquals(Identifier("reviewer-1"), original.tasks.single().assigneeId)
        assertNull(transaction.execute { repository.findById(Identifier("tenant-2"), Identifier("workflow-2")) })
    }

    @Test
    fun `round trips the maximum supported external identity width`() {
        val externalId = "u".repeat(256)
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcWorkflowInstanceRepository(Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
        val workflow = WorkflowInstance(
            Identifier("workflow-long"), Identifier("tenant-1"), Identifier("document-1"), "DOCUMENT_REVIEW",
            WorkflowState.PENDING,
            tasks = listOf(
                WorkflowTask(
                    Identifier("task-long"), Identifier("tenant-1"), Identifier("workflow-long"), Identifier(externalId),
                ),
            ),
            submittedBy = Identifier(externalId),
        )
        transaction.execute { repository.save(workflow) }
        val pending = transaction.execute {
            requireNotNull(repository.findForDecision(Identifier("tenant-1"), workflow.id))
        }
        pending.approve(Identifier("task-long"), Identifier(externalId), "长标识审批人", "approved")
        transaction.execute { repository.save(pending) }

        val restored = transaction.execute {
            requireNotNull(repository.findById(Identifier("tenant-1"), workflow.id))
        }
        assertEquals(externalId, restored.tasks.single().assigneeId?.value)
        assertEquals(externalId, restored.tasks.single().decisionOperatorId?.value)
        assertEquals(externalId, restored.submittedBy?.value)
    }

    private fun workflow() = WorkflowInstance(
        Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DOCUMENT_REVIEW",
        WorkflowState.PENDING,
        tasks = listOf(WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-1"))),
        submittedBy = Identifier("submitter-1"),
    )

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }

    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant, zone)
        override fun instant(): Instant = instant
    }
}
