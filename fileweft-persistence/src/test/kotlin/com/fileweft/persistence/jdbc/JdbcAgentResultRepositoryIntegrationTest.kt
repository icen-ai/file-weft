package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.agent.PersistedAgentResult
import com.fileweft.application.agent.PersistedAgentSuggestionConfirmation
import com.fileweft.core.id.Identifier
import com.fileweft.persistence.migration.FlywayMigrationRunner
import com.fileweft.spi.ai.AgentCapability
import com.fileweft.spi.ai.AgentExecutionStatus
import com.fileweft.spi.ai.AgentResult
import com.fileweft.spi.ai.AgentSuggestion
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

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }
}
