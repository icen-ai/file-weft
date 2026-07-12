package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.agent.AgentTaskHandler
import ai.icen.fw.agent.AgentTaskOrchestrator
import ai.icen.fw.agent.PersistedAgentSuggestionConfirmationService
import ai.icen.fw.application.agent.PersistedAgentResult
import ai.icen.fw.application.agent.PersistedAgentSuggestionConfirmation
import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.BackgroundTaskLease
import ai.icen.fw.application.task.TaskLeaseClaim
import ai.icen.fw.application.task.TaskLeaseLostException
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import ai.icen.fw.spi.ai.AgentCapability
import ai.icen.fw.spi.ai.AgentExecutionStatus
import ai.icen.fw.spi.ai.AgentResult
import ai.icen.fw.spi.ai.AgentSuggestion
import ai.icen.fw.spi.ai.AgentTask
import ai.icen.fw.spi.ai.FileWeftAgent
import ai.icen.fw.spi.task.TaskHandlingStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcAgentResultRepositoryIntegrationTest {
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
    fun `persists tenant isolated agent evidence and idempotent confirmations`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcAgentResultRepository(ObjectMapper(), Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC))
        transaction.execute {
            repository.save(result("tenant-1", "task-1", "result-1"))
            repository.save(result("tenant-2", "task-1", "result-2"))
            repository.saveConfirmation(confirmation("confirmation-1", "tenant-1", "operator-1"))
            repository.saveConfirmation(confirmation("confirmation-2", "tenant-1", "operator-2"))
        }

        val persisted = transaction.execute { repository.findByTask(Identifier("tenant-1"), Identifier("task-1")) }
        val confirmations = transaction.execute { repository.findConfirmations(Identifier("tenant-1"), Identifier("task-1")) }

        assertEquals("result-1", persisted?.id?.value)
        assertEquals(AgentExecutionStatus.SUCCEEDED, persisted?.result?.status)
        assertEquals("legal", persisted?.result?.suggestions?.single()?.payload?.get("classification"))
        assertEquals(listOf("confirmation-1"), confirmations.map { it.id.value })
        assertTrue(transaction.execute { repository.findByTask(Identifier("tenant-3"), Identifier("task-1")) } == null)
    }

    @Test
    fun `expired and forged leases cannot overwrite the winning agent result projection`() {
        val objectMapper = ObjectMapper()
        val clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)
        val transaction = JdbcApplicationTransaction(dataSource)
        val tasks = JdbcTaskRepository(objectMapper, clock)
        val results = JdbcAgentResultRepository(objectMapper, clock)
        transaction.execute { tasks.enqueue(agentBackgroundTask()) }
        val expired = transaction.execute {
            tasks.claimAvailable(1, 100, TaskLeaseClaim("worker-a", "token-a", 200))
        }.single()
        val staleAgentStarted = CountDownLatch(1)
        val releaseStaleAgent = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val staleHandler = agentHandler("stale", tasks, results, transaction, clock) {
                staleAgentStarted.countDown()
                check(releaseStaleAgent.await(5, TimeUnit.SECONDS)) { "Stale Agent was not released." }
            }
            val staleOutcome = executor.submit<Throwable?> {
                try {
                    staleHandler.handle(expired)
                    null
                } catch (failure: Throwable) {
                    failure
                }
            }
            assertTrue(staleAgentStarted.await(5, TimeUnit.SECONDS))

            val winner = transaction.execute {
                tasks.claimAvailable(1, 200, TaskLeaseClaim("worker-b", "token-b", 300))
            }.single()
            val forged = BackgroundTaskLease(winner.task, winner.leaseOwner, "token-forged")
            val forgedHandler = agentHandler("forged", tasks, results, transaction, clock)
            val winnerHandler = agentHandler("winner", tasks, results, transaction, clock)

            assertFailsWith<TaskLeaseLostException> { forgedHandler.handle(forged) }
            assertNull(transaction.execute { results.findByTask(Identifier("tenant-1"), Identifier("task-1")) })
            assertEquals(TaskHandlingStatus.SUCCEEDED, winnerHandler.handle(winner).status)
            val confirmations = PersistedAgentSuggestionConfirmationService(
                results,
                transaction,
                object : IdentifierGenerator {
                    override fun nextId(): Identifier = Identifier("confirmation-1")
                },
                clock,
                tasks,
            )
            assertFailsWith<IllegalStateException> {
                confirmations.confirm(
                    Identifier("tenant-1"), Identifier("task-1"),
                    Identifier("suggestion-1"), Identifier("operator-1"),
                )
            }
            assertTrue(
                transaction.execute {
                    results.findConfirmations(Identifier("tenant-1"), Identifier("task-1"))
                }.isEmpty(),
            )
            transaction.execute { tasks.markSucceeded(winner, 201) }
            assertEquals(
                "operator-1",
                confirmations.confirm(
                    Identifier("tenant-1"), Identifier("task-1"),
                    Identifier("suggestion-1"), Identifier("operator-1"),
                ).confirmedBy.value,
            )

            releaseStaleAgent.countDown()
            assertTrue(staleOutcome.get(5, TimeUnit.SECONDS) is TaskLeaseLostException)
            assertEquals(
                "winner",
                transaction.execute { results.findByTask(Identifier("tenant-1"), Identifier("task-1")) }?.result?.message,
            )
        } finally {
            releaseStaleAgent.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun result(tenant: String, task: String, id: String) = PersistedAgentResult(
        Identifier(id), Identifier(tenant), Identifier(task), AgentCapability.CLASSIFICATION,
        Identifier("event-1"), "document.created",
        AgentResult(
            Identifier(task), AgentExecutionStatus.SUCCEEDED,
            listOf(AgentSuggestion(Identifier("suggestion-1"), "document.classification", mapOf("classification" to "legal"))),
            completedAt = 2,
        ),
        1,
    )

    private fun confirmation(id: String, tenant: String, operator: String) = PersistedAgentSuggestionConfirmation(
        Identifier(id), Identifier(tenant), Identifier("task-1"), Identifier("suggestion-1"), Identifier(operator), 3,
    )

    private fun agentBackgroundTask() = BackgroundTask(
        id = Identifier("task-1"),
        tenantId = Identifier("tenant-1"),
        type = AgentTaskHandler.TASK_TYPE,
        idempotencyKey = "agent:METADATA:event-1",
        businessId = Identifier("document-1"),
        payload = mapOf(
            AgentTaskHandler.CAPABILITY_KEY to AgentCapability.METADATA.name,
            AgentTaskHandler.SOURCE_EVENT_ID_KEY to "event-1",
            AgentTaskHandler.SOURCE_EVENT_TYPE_KEY to "document.created",
            AgentTaskHandler.CONTEXT_PREFIX + "documentId" to "document-1",
        ),
    )

    private fun agentHandler(
        message: String,
        tasks: JdbcTaskRepository,
        results: JdbcAgentResultRepository,
        transaction: JdbcApplicationTransaction,
        clock: Clock,
        beforeResult: () -> Unit = {},
    ): AgentTaskHandler = AgentTaskHandler(
        AgentTaskOrchestrator(
            listOf(object : FileWeftAgent {
                override fun capability(): AgentCapability = AgentCapability.METADATA

                override fun execute(task: AgentTask): AgentResult {
                    beforeResult()
                    return AgentResult(
                        task.id,
                        AgentExecutionStatus.SUCCEEDED,
                        suggestions = listOf(
                            AgentSuggestion(Identifier("suggestion-1"), "document.metadata"),
                        ),
                        message = message,
                        completedAt = clock.millis(),
                    )
                }
            }),
            clock,
        ),
        results,
        transaction,
        clock,
        tasks,
    )

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }
}
