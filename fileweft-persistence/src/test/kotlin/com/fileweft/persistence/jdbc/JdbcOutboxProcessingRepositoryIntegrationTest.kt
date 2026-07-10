package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.outbox.OutboxEventLease
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.persistence.migration.FlywayMigrationRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JdbcOutboxProcessingRepositoryIntegrationTest {
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
    fun `claims retries and completes only currently leased events`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val events = JdbcOutboxEventRepository(ObjectMapper())
        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        transaction.execute { events.append(event()) }

        val firstLease = transaction.execute { processing.claimAvailable(10, 100) }.single()
        assertEquals("event-1", firstLease.event.id.value)
        assertEquals("trace-1", firstLease.event.traceId?.value)
        assertEquals(0, firstLease.retryCount)
        assertEquals("RUNNING", state("event-1").status)
        assertTrue(transaction.execute { processing.claimAvailable(10, 100) }.isEmpty())

        transaction.execute { processing.markForRetry(firstLease, 200, "connector offline", 101) }
        assertEquals("RETRY", state("event-1").status)
        assertEquals(1, state("event-1").retryCount)
        assertEquals("connector offline", state("event-1").lastError)
        assertTrue(transaction.execute { processing.claimAvailable(10, 199) }.isEmpty())

        val retryLease = transaction.execute { processing.claimAvailable(10, 200) }.single()
        assertEquals(1, retryLease.retryCount)
        transaction.execute { processing.markSucceeded(retryLease, 201) }
        assertEquals("SUCCESS", state("event-1").status)
        assertEquals(0, state("event-1").nextAttemptAt)
        assertEquals(null, state("event-1").lastError)

        assertFailsWith<IllegalArgumentException> {
            transaction.execute { processing.markSucceeded(retryLease, 202) }
        }
    }

    @Test
    fun `requires matching tenant while changing a leased event state`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val events = JdbcOutboxEventRepository(ObjectMapper())
        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        transaction.execute { events.append(event()) }
        val lease = transaction.execute { processing.claimAvailable(1, 1) }.single()
        val wrongTenantLease = OutboxEventLease(
            OutboxEvent(
                lease.event.id, Identifier("tenant-2"), lease.event.type, lease.event.payload,
                lease.event.timestamp, lease.event.traceId,
            ),
            lease.retryCount,
        )

        assertFailsWith<IllegalArgumentException> {
            transaction.execute { processing.markFailed(wrongTenantLease, "wrong tenant", 2) }
        }
        assertEquals("RUNNING", state("event-1").status)
    }

    private fun event() = OutboxEvent(
        Identifier("event-1"), Identifier("tenant-1"), "document.publish.requested",
        mapOf("documentId" to "document-1"), 1, Identifier("trace-1"),
    )

    private fun state(id: String): State = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT event_status, retry_count, next_attempt_time, last_error FROM fw_outbox_event WHERE id = ?",
        ).use { statement ->
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

    private data class State(
        val status: String,
        val retryCount: Int,
        val nextAttemptAt: Long,
        val lastError: String?,
    )
}
