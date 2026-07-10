package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.task.BackgroundTask
import com.fileweft.application.task.TaskProcessingRepository
import com.fileweft.application.task.TaskRepository
import com.fileweft.core.id.Identifier
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
import kotlin.test.assertTrue

class JdbcTaskRepositoryIntegrationTest {
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
    fun `deduplicates enqueue and recovers an expired worker lease`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcTaskRepository(ObjectMapper(), Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC))
        val tasks: TaskRepository = repository
        val processing: TaskProcessingRepository = repository

        transaction.execute {
            tasks.enqueue(task("task-1", "doctor:document-1"))
            tasks.enqueue(task("task-duplicate", "doctor:document-1"))
        }
        assertEquals("task-1", transaction.execute { tasks.findById(Identifier("tenant-1"), Identifier("task-1")) }?.id?.value)
        assertEquals(1, transaction.execute { tasks.findByBusiness(Identifier("tenant-1"), Identifier("document-1"), 10) }.size)

        val firstLease = transaction.execute { processing.claimAvailable(1, 100, "worker-a", 200) }.single()
        assertEquals("worker-a", firstLease.leaseOwner)
        assertTrue(transaction.execute { processing.claimAvailable(1, 199, "worker-b", 299) }.isEmpty())

        val recoveredLease = transaction.execute { processing.claimAvailable(1, 200, "worker-b", 300) }.single()
        assertEquals("worker-b", recoveredLease.leaseOwner)
        assertFailsWith<IllegalArgumentException> {
            transaction.execute { processing.markSucceeded(firstLease, 201) }
        }

        transaction.execute { processing.markForRetry(recoveredLease, 400, "temporary downstream failure", 201) }
        assertEquals(State("RETRY", 1, 400, "temporary downstream failure"), state("task-1"))

        val retryLease = transaction.execute { processing.claimAvailable(1, 400, "worker-c", 500) }.single()
        transaction.execute { processing.markSucceeded(retryLease, 401) }
        assertEquals(State("SUCCESS", 1, 0, null), state("task-1"))
    }

    private fun task(id: String, key: String) = BackgroundTask(
        id = Identifier(id), tenantId = Identifier("tenant-1"), type = "document.doctor", idempotencyKey = key,
        businessId = Identifier("document-1"), payload = mapOf("requestedBy" to "operator-1"),
    )

    private fun state(id: String): State = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT task_status, retry_count, next_attempt_time, last_error FROM fw_task WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { result ->
                require(result.next())
                State(result.getString(1), result.getInt(2), result.getLong(3), result.getString(4))
            }
        }
    }

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }

    private data class State(val status: String, val retryCount: Int, val nextAttemptAt: Long, val lastError: String?)
}
