package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.outbox.OutboxEventLease
import com.fileweft.application.outbox.OutboxEventStatus
import com.fileweft.application.outbox.OutboxLeaseClaim
import com.fileweft.application.outbox.OutboxLeaseLostException
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.persistence.migration.FlywayMigrationRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
    fun `reclaims expired leases and accepts only the current owner token`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val events = JdbcOutboxEventRepository(ObjectMapper())
        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        transaction.execute { events.append(event()) }

        val firstLease = transaction.execute { processing.claimAvailable(10, 100, claim("worker-a", "token-a", 150)) }.single()
        assertEquals("event-1", firstLease.event.id.value)
        assertEquals("trace-1", firstLease.event.traceId?.value)
        assertEquals(0, firstLease.retryCount)
        assertEquals("worker-a", firstLease.leaseOwner)
        assertEquals("token-a", firstLease.leaseToken)
        assertEquals(State("RUNNING", 0, 0, null, "worker-a", "token-a", 150), state("event-1"))
        assertTrue(transaction.execute { processing.claimAvailable(10, 149, claim("worker-b", "token-b", 249)) }.isEmpty())

        val recoveredLease = transaction.execute { processing.claimAvailable(10, 150, claim("worker-b", "token-b", 250)) }.single()
        assertEquals("worker-b", recoveredLease.leaseOwner)
        assertEquals("token-b", recoveredLease.leaseToken)
        assertFailsWith<OutboxLeaseLostException> {
            transaction.execute { processing.markSucceeded(firstLease, 151) }
        }
        assertFailsWith<OutboxLeaseLostException> {
            transaction.execute { processing.markForRetry(firstLease, 200, "late worker", 151) }
        }
        assertFailsWith<OutboxLeaseLostException> {
            transaction.execute { processing.markFailed(firstLease, "late worker", 151) }
        }
        assertEquals(State("RUNNING", 0, 0, null, "worker-b", "token-b", 250), state("event-1"))

        transaction.execute { processing.markForRetry(recoveredLease, 200, "connector offline", 151) }
        assertEquals(State("RETRY", 1, 200, "connector offline", null, null, 0), state("event-1"))
        assertTrue(transaction.execute { processing.claimAvailable(10, 199, claim("worker-c", "token-c", 299)) }.isEmpty())

        val retryLease = transaction.execute { processing.claimAvailable(10, 200, claim("worker-c", "token-c", 300)) }.single()
        assertEquals(1, retryLease.retryCount)
        transaction.execute { processing.markSucceeded(retryLease, 201) }
        assertEquals(State("SUCCESS", 1, 0, null, null, null, 0), state("event-1"))

        assertFailsWith<OutboxLeaseLostException> {
            transaction.execute { processing.markSucceeded(retryLease, 202) }
        }
    }

    @Test
    fun `requires matching tenant while changing a leased event state`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val events = JdbcOutboxEventRepository(ObjectMapper())
        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        transaction.execute { events.append(event()) }
        val lease = transaction.execute { processing.claimAvailable(1, 1, claim("worker-a", "token-a", 100)) }.single()
        val wrongTenantLease = OutboxEventLease(
            OutboxEvent(
                lease.event.id, Identifier("tenant-2"), lease.event.type, lease.event.payload,
                lease.event.timestamp, lease.event.traceId,
            ),
            lease.retryCount,
            lease.leaseOwner,
            lease.leaseToken,
        )

        assertFailsWith<OutboxLeaseLostException> {
            transaction.execute { processing.markFailed(wrongTenantLease, "wrong tenant", 2) }
        }
        assertEquals(State("RUNNING", 0, 0, null, "worker-a", "token-a", 100), state("event-1"))
    }

    @Test
    fun `locks and verifies only the exact current running lease in its tenant`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val events = JdbcOutboxEventRepository(ObjectMapper())
        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        transaction.execute { events.append(event()) }
        val lease = transaction.execute {
            processing.claimAvailable(1, 1, claim("worker-a", "token-a", 100))
        }.single()

        val locked = transaction.execute {
            processing.findForMutation(Identifier("tenant-1"), Identifier("event-1"))
        }

        assertEquals(OutboxEventStatus.RUNNING, locked?.status)
        assertEquals(lease.event.type, locked?.eventType)
        assertEquals("worker-a", locked?.leaseOwner)
        assertEquals("token-a", locked?.leaseToken)
        assertEquals(locked, locked?.requireCurrentLease(lease))
        assertNull(
            transaction.execute {
                processing.findForMutation(Identifier("tenant-2"), Identifier("event-1"))
            },
        )
        val forged = OutboxEventLease(lease.event, lease.retryCount, "worker-a", "token-forged")
        assertFailsWith<OutboxLeaseLostException> { requireNotNull(locked).requireCurrentLease(forged) }

        transaction.execute { processing.markSucceeded(lease, 2) }
        val completed = transaction.execute {
            processing.findForMutation(Identifier("tenant-1"), Identifier("event-1"))
        }
        assertEquals(OutboxEventStatus.SUCCESS, completed?.status)
        assertFailsWith<OutboxLeaseLostException> { requireNotNull(completed).requireCurrentLease(lease) }
    }

    @Test
    fun `holds the event mutation row lock until the caller transaction completes`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val events = JdbcOutboxEventRepository(ObjectMapper())
        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        transaction.execute { events.append(event()) }
        transaction.execute { processing.claimAvailable(1, 1, claim("worker-a", "token-a", 100)) }

        dataSource.connection.use { firstConnection ->
            firstConnection.autoCommit = false
            val first = JdbcConnectionContext.withConnection(firstConnection) {
                processing.findForMutation(Identifier("tenant-1"), Identifier("event-1"))
            }
            assertEquals(OutboxEventStatus.RUNNING, first?.status)

            val started = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val second = executor.submit<com.fileweft.application.outbox.OutboxEventState?> {
                    dataSource.connection.use { secondConnection ->
                        secondConnection.autoCommit = false
                        secondConnection.createStatement().use { statement ->
                            statement.execute("SET LOCAL statement_timeout = '5s'")
                        }
                        try {
                            started.countDown()
                            val state = JdbcConnectionContext.withConnection(secondConnection) {
                                processing.findForMutation(Identifier("tenant-1"), Identifier("event-1"))
                            }
                            secondConnection.commit()
                            state
                        } catch (failure: Throwable) {
                            secondConnection.rollback()
                            throw failure
                        }
                    }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertFailsWith<TimeoutException> { second.get(250, TimeUnit.MILLISECONDS) }

                firstConnection.commit()
                assertEquals(OutboxEventStatus.RUNNING, second.get(5, TimeUnit.SECONDS)?.status)
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
                firstConnection.rollback()
            }
        }
    }

    @Test
    fun `allows only one concurrent worker to claim the same available event`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val events = JdbcOutboxEventRepository(ObjectMapper())
        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        transaction.execute { events.append(event()) }

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("a", "b").map { suffix ->
                executor.submit<List<OutboxEventLease>> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "Concurrent outbox claim did not start." }
                    transaction.execute {
                        processing.claimAvailable(1, 100, claim("worker-$suffix", "token-$suffix", 200))
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            val claimed = futures.flatMap { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, claimed.size)
            assertEquals("event-1", claimed.single().event.id.value)
            assertEquals("RUNNING", state("event-1").status)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `keeps the original claim method callable while returning a token lease`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val events = JdbcOutboxEventRepository(ObjectMapper())
        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        transaction.execute { events.append(event()) }

        val lease = transaction.execute { processing.claimAvailable(1, 100) }.single()

        assertTrue(!lease.leaseOwner.isNullOrBlank())
        assertTrue(!lease.leaseToken.isNullOrBlank())
        transaction.execute { processing.markSucceeded(lease, 101) }
        assertEquals("SUCCESS", state("event-1").status)
    }

    @Test
    fun `keeps no token legacy acknowledgements compatible but rejects them after a tokenized reclaim`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val events = JdbcOutboxEventRepository(ObjectMapper())
        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        transaction.execute { events.append(event()) }
        val legacyLease = OutboxEventLease(event(), 0)

        markLegacyRunning("event-1", leaseExpiresAt = 1_000)
        transaction.execute { processing.markSucceeded(legacyLease, 2) }
        assertEquals("SUCCESS", state("event-1").status)

        transaction.execute { events.append(event("event-2")) }
        val staleLegacyLease = OutboxEventLease(event("event-2"), 0)
        markLegacyRunning("event-2", leaseExpiresAt = 0)
        val reclaimed = transaction.execute {
            processing.claimAvailable(1, 100, claim("worker-new", "token-new", 200, legacyRunningBefore = 100))
        }.single()

        assertFailsWith<OutboxLeaseLostException> {
            transaction.execute { processing.markSucceeded(staleLegacyLease, 101) }
        }
        assertEquals("event-2", reclaimed.event.id.value)
        assertEquals(State("RUNNING", 0, 0, null, "worker-new", "token-new", 200), state("event-2"))
    }

    @Test
    fun `does not reclaim a recent no token running event before the legacy cutoff`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val events = JdbcOutboxEventRepository(ObjectMapper())
        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        transaction.execute { events.append(event()) }
        markLegacyRunning("event-1", leaseExpiresAt = 0, updatedAt = 99)

        assertTrue(
            transaction.execute {
                processing.claimAvailable(1, 100, claim("worker-new", "token-new", 200, legacyRunningBefore = 98))
            }.isEmpty(),
        )
        assertEquals(State("RUNNING", 0, 0, null, null, null, 0), state("event-1"))

        val reclaimed = transaction.execute {
            processing.claimAvailable(1, 100, claim("worker-new", "token-new", 200, legacyRunningBefore = 99))
        }.single()
        assertEquals("event-1", reclaimed.event.id.value)
        assertEquals(State("RUNNING", 0, 0, null, "worker-new", "token-new", 200), state("event-1"))
    }

    private fun event(id: String = "event-1") = OutboxEvent(
        Identifier(id), Identifier("tenant-1"), "document.publish.requested",
        mapOf("documentId" to "document-1"), 1, Identifier("trace-1"),
    )

    private fun claim(
        owner: String,
        token: String,
        expiresAt: Long,
        legacyRunningBefore: Long = 0,
    ): OutboxLeaseClaim = OutboxLeaseClaim(owner, token, expiresAt, legacyRunningBefore)

    private fun state(id: String): State = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT event_status, retry_count, next_attempt_time, last_error, lease_owner, lease_token, lease_expire_time FROM fw_outbox_event WHERE id = ?",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { result ->
                require(result.next())
                State(
                    result.getString(1), result.getInt(2), result.getLong(3), result.getString(4),
                    result.getString(5), result.getString(6), result.getLong(7),
                )
            }
        }
    }

    private fun markLegacyRunning(id: String, leaseExpiresAt: Long, updatedAt: Long = 1) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE fw_outbox_event
                SET event_status = 'RUNNING', lease_owner = NULL, lease_token = NULL, lease_expire_time = ?, updated_time = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, leaseExpiresAt)
                statement.setLong(2, updatedAt)
                statement.setString(3, id)
                assertEquals(1, statement.executeUpdate())
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
        val leaseOwner: String?,
        val leaseToken: String?,
        val leaseExpiresAt: Long,
    )
}
