package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.task.BackgroundTask
import com.fileweft.application.task.BackgroundTaskLease
import com.fileweft.application.task.BackgroundTaskStatus
import com.fileweft.application.task.LeasedTaskProcessingRepository
import com.fileweft.application.task.TaskLeaseClaim
import com.fileweft.application.task.TaskLeaseLostException
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    fun `deduplicates enqueue and fences an expired claim from the same worker with a new token`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = repository()
        val tasks: TaskRepository = repository
        val processing: LeasedTaskProcessingRepository = repository

        transaction.execute {
            tasks.enqueue(task("task-1", "doctor:document-1"))
            tasks.enqueue(task("task-duplicate", "doctor:document-1"))
        }
        assertEquals("task-1", transaction.execute { tasks.findById(Identifier("tenant-1"), Identifier("task-1")) }?.id?.value)
        assertEquals(1, transaction.execute { tasks.findByBusiness(Identifier("tenant-1"), Identifier("document-1"), 10) }.size)

        val firstLease = transaction.execute { processing.claimAvailable(1, 100, claim("worker-a", "token-a", 200)) }.single()
        assertEquals("worker-a", firstLease.leaseOwner)
        assertEquals("token-a", firstLease.leaseToken)
        assertEquals(State("RUNNING", 0, 0, null, "worker-a", "token-a", 200), state("task-1"))
        assertTrue(transaction.execute { processing.claimAvailable(1, 199, claim("worker-a", "token-b", 299)) }.isEmpty())

        val recoveredLease = transaction.execute { processing.claimAvailable(1, 200, claim("worker-a", "token-b", 300)) }.single()
        assertEquals("worker-a", recoveredLease.leaseOwner)
        assertEquals("token-b", recoveredLease.leaseToken)
        assertEquals(0, recoveredLease.task.retryCount)
        assertFailsWith<TaskLeaseLostException> {
            transaction.execute { processing.markSucceeded(firstLease, 201) }
        }
        assertFailsWith<TaskLeaseLostException> {
            transaction.execute { processing.markForRetry(firstLease, 400, "late worker", 201) }
        }
        assertFailsWith<TaskLeaseLostException> {
            transaction.execute { processing.markFailed(firstLease, "late worker", 201) }
        }
        assertEquals(State("RUNNING", 0, 0, null, "worker-a", "token-b", 300), state("task-1"))

        transaction.execute { processing.markForRetry(recoveredLease, 400, "temporary downstream failure", 201) }
        assertEquals(State("RETRY", 1, 400, "temporary downstream failure", null, null, 0), state("task-1"))

        val retryLease = transaction.execute { processing.claimAvailable(1, 400, claim("worker-c", "token-c", 500)) }.single()
        assertEquals(1, retryLease.task.retryCount)
        transaction.execute { processing.markSucceeded(retryLease, 401) }
        assertEquals(State("SUCCESS", 1, 0, null, null, null, 0), state("task-1"))
    }

    @Test
    fun `clears all lease columns after a tokenized terminal failure`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = repository()
        val tasks: TaskRepository = repository
        val processing: LeasedTaskProcessingRepository = repository
        transaction.execute { tasks.enqueue(task("task-1", "doctor:document-1")) }

        val lease = transaction.execute { processing.claimAvailable(1, 100, claim("worker-a", "token-a", 200)) }.single()
        transaction.execute { processing.markFailed(lease, "unsupported task type", 101) }

        assertEquals(State("FAILED", 0, 0, "unsupported task type", null, null, 0), state("task-1"))
    }

    @Test
    fun `allows only one concurrent worker to claim the same available task`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = repository()
        transaction.execute { repository.enqueue(task("task-1", "doctor:document-1")) }

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("a", "b").map { suffix ->
                executor.submit<List<BackgroundTaskLease>> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "Concurrent task claim did not start." }
                    transaction.execute {
                        repository.claimAvailable(1, 100, claim("worker-$suffix", "token-$suffix", 200))
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            val claimed = futures.flatMap { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, claimed.size)
            assertEquals("task-1", claimed.single().task.id.value)
            assertEquals("RUNNING", state("task-1").status)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `keeps the original claim method callable while returning a token lease`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = repository()
        transaction.execute { repository.enqueue(task("task-1", "doctor:document-1")) }

        val lease = transaction.execute { repository.claimAvailable(1, 100, "worker-a", 200) }.single()

        assertEquals("worker-a", lease.leaseOwner)
        assertTrue(!lease.leaseToken.isNullOrBlank())
        transaction.execute { repository.markSucceeded(lease, 101) }
        assertEquals(State("SUCCESS", 0, 0, null, null, null, 0), state("task-1"))
    }

    @Test
    fun `keeps no token legacy acknowledgements compatible but rejects them after a tokenized reclaim`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = repository()
        transaction.execute { repository.enqueue(task("task-1", "doctor:document-1")) }

        markLegacyRunning("task-1", updatedAt = 1)
        val legacyLease = BackgroundTaskLease(runningTask("task-1", "doctor:document-1"), "old-worker")
        transaction.execute { repository.markSucceeded(legacyLease, 2) }
        assertEquals(State("SUCCESS", 0, 0, null, null, null, 0), state("task-1"))

        transaction.execute { repository.enqueue(task("task-2", "doctor:document-2")) }
        markLegacyRunning("task-2", updatedAt = 1)
        val staleLegacyLease = BackgroundTaskLease(runningTask("task-2", "doctor:document-2"), "old-worker")
        val reclaimed = transaction.execute {
            repository.claimAvailable(1, 100, claim("worker-new", "token-new", 200, legacyRunningBefore = 1))
        }.single()

        assertFailsWith<TaskLeaseLostException> {
            transaction.execute { repository.markSucceeded(staleLegacyLease, 101) }
        }
        assertEquals("task-2", reclaimed.task.id.value)
        assertEquals(State("RUNNING", 0, 0, null, "worker-new", "token-new", 200), state("task-2"))
    }

    @Test
    fun `rejects a cross owner acknowledgement for a legacy no token task lease`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = repository()
        transaction.execute { repository.enqueue(task("task-1", "doctor:document-1")) }
        markLegacyRunning("task-1", updatedAt = 1)
        val intruderLease = BackgroundTaskLease(runningTask("task-1", "doctor:document-1"), "intruder-worker")

        assertFailsWith<TaskLeaseLostException> {
            transaction.execute { repository.markSucceeded(intruderLease, 2) }
        }
        assertFailsWith<TaskLeaseLostException> {
            transaction.execute { repository.markForRetry(intruderLease, 100, "intruder retry", 2) }
        }
        assertFailsWith<TaskLeaseLostException> {
            transaction.execute { repository.markFailed(intruderLease, "intruder failure", 2) }
        }
        assertEquals(State("RUNNING", 0, 0, null, "old-worker", null, 0), state("task-1"))
    }

    @Test
    fun `does not reclaim a recent no token running task before the legacy cutoff`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = repository()
        transaction.execute { repository.enqueue(task("task-1", "doctor:document-1")) }
        markLegacyRunning("task-1", updatedAt = 99)

        assertTrue(
            transaction.execute {
                repository.claimAvailable(1, 100, claim("worker-new", "token-new", 200, legacyRunningBefore = 98))
            }.isEmpty(),
        )
        assertEquals(State("RUNNING", 0, 0, null, "old-worker", null, 0), state("task-1"))

        val reclaimed = transaction.execute {
            repository.claimAvailable(1, 100, claim("worker-new", "token-new", 200, legacyRunningBefore = 99))
        }.single()
        assertEquals("task-1", reclaimed.task.id.value)
        assertEquals(State("RUNNING", 0, 0, null, "worker-new", "token-new", 200), state("task-1"))
    }

    @Test
    fun `requires a matching tenant while changing a tokenized task state`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = repository()
        transaction.execute { repository.enqueue(task("task-1", "doctor:document-1")) }
        val lease = transaction.execute { repository.claimAvailable(1, 100, claim("worker-a", "token-a", 200)) }.single()
        val wrongTenantLease = BackgroundTaskLease(
            BackgroundTask(
                id = lease.task.id,
                tenantId = Identifier("tenant-2"),
                type = lease.task.type,
                idempotencyKey = lease.task.idempotencyKey,
                businessId = lease.task.businessId,
                payload = lease.task.payload,
                status = BackgroundTaskStatus.RUNNING,
                retryCount = lease.task.retryCount,
                nextAttemptTime = lease.task.nextAttemptTime,
                lastError = lease.task.lastError,
            ),
            lease.leaseOwner,
            lease.leaseToken,
        )

        assertFailsWith<TaskLeaseLostException> {
            transaction.execute { repository.markFailed(wrongTenantLease, "wrong tenant", 101) }
        }
        assertEquals(State("RUNNING", 0, 0, null, "worker-a", "token-a", 200), state("task-1"))
    }

    private fun repository(): JdbcTaskRepository =
        JdbcTaskRepository(ObjectMapper(), Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC))

    private fun task(id: String, key: String) = BackgroundTask(
        id = Identifier(id), tenantId = Identifier("tenant-1"), type = "document.doctor", idempotencyKey = key,
        businessId = Identifier(key.removePrefix("doctor:")), payload = mapOf("requestedBy" to "operator-1"),
    )

    private fun runningTask(id: String, key: String) = BackgroundTask(
        id = Identifier(id), tenantId = Identifier("tenant-1"), type = "document.doctor", idempotencyKey = key,
        businessId = Identifier(key.removePrefix("doctor:")), payload = mapOf("requestedBy" to "operator-1"),
        status = BackgroundTaskStatus.RUNNING,
    )

    private fun claim(
        owner: String,
        token: String,
        expiresAt: Long,
        legacyRunningBefore: Long = 0,
    ): TaskLeaseClaim = TaskLeaseClaim(owner, token, expiresAt, legacyRunningBefore)

    private fun state(id: String): State = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT task_status, retry_count, next_attempt_time, last_error, lease_owner, lease_token, lease_expire_time FROM fw_task WHERE id = ?",
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

    private fun markLegacyRunning(id: String, updatedAt: Long) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE fw_task
                SET task_status = 'RUNNING', lease_owner = 'old-worker', lease_token = NULL, lease_expire_time = 0, updated_time = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, updatedAt)
                statement.setString(2, id)
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
