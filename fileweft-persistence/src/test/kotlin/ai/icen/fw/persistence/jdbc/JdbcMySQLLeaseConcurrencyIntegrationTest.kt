package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.outbox.OutboxLeaseClaim
import ai.icen.fw.application.outbox.OutboxLeaseLostException
import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.BackgroundTaskLease
import ai.icen.fw.application.task.TaskLeaseClaim
import ai.icen.fw.application.task.TaskLeaseLostException
import ai.icen.fw.application.outbox.OutboxEventLease
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import com.fasterxml.jackson.databind.ObjectMapper
import com.mysql.cj.jdbc.MysqlDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JdbcMySQLLeaseConcurrencyIntegrationTest {
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun prepareDatabase() {
        check(System.getenv("FILEWEFT_RUN_MYSQL_TESTS") == "true") {
            "MySQL integration tests must run only through the fail-closed Gradle task"
        }
        val databaseName = environment("FILEWEFT_MYSQL_DATABASE", "fileweft")
        adminDataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE IF EXISTS `$databaseName`")
                statement.execute("CREATE DATABASE `$databaseName` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
            }
        }
        dataSource = MysqlDataSource().apply {
            setURL(environment("FILEWEFT_MYSQL_URL", "jdbc:mysql://localhost:3306/$databaseName"))
            user = environment("FILEWEFT_MYSQL_USER", "root")
            password = environment("FILEWEFT_MYSQL_PASSWORD", "")
        }
        FlywayMigrationRunner(dataSource).migrate()
    }

    @AfterEach
    fun cleanDatabase() {
        if (::dataSource.isInitialized) {
            val databaseName = environment("FILEWEFT_MYSQL_DATABASE", "fileweft")
            adminDataSource().connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP DATABASE IF EXISTS `$databaseName`")
                }
            }
        }
    }

    @Test
    fun `outbox workers concurrently claim distinct ordered rows`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val events = JdbcOutboxEventRepository(ObjectMapper())
        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        transaction.execute {
            events.append(event("event-1", 1))
            events.append(event("event-2", 2))
        }

        dataSource.connection.use { firstConnection ->
            firstConnection.autoCommit = false
            val first = JdbcConnectionContext.withConnection(firstConnection) {
                processing.claimAvailable(1, 100, OutboxLeaseClaim("worker-a", "token-a", 200)).single()
            }

            val executor = Executors.newSingleThreadExecutor()
            try {
                val secondFuture = executor.submit<List<OutboxEventLease>> {
                    transaction.execute {
                        processing.claimAvailable(1, 100, OutboxLeaseClaim("worker-b", "token-b", 200))
                    }
                }
                val second = secondFuture.get(2, TimeUnit.SECONDS)

                assertEquals("event-1", first.event.id.value)
                assertEquals("event-2", second.single().event.id.value)
                assertEquals("worker-b", second.single().leaseOwner)
            } finally {
                firstConnection.rollback()
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }

        val recovered = transaction.execute {
            processing.claimAvailable(1, 100, OutboxLeaseClaim("worker-c", "token-c", 200)).single()
        }
        assertEquals("event-1", recovered.event.id.value)
    }

    @Test
    fun `task workers concurrently claim distinct ordered rows`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val tasks = JdbcTaskRepository(ObjectMapper(), Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC))
        transaction.execute {
            tasks.enqueue(task("task-1"))
            tasks.enqueue(task("task-2"))
        }

        dataSource.connection.use { firstConnection ->
            firstConnection.autoCommit = false
            val first = JdbcConnectionContext.withConnection(firstConnection) {
                tasks.claimAvailable(1, 100, TaskLeaseClaim("worker-a", "token-a", 200)).single()
            }

            val executor = Executors.newSingleThreadExecutor()
            try {
                val secondFuture = executor.submit<List<BackgroundTaskLease>> {
                    transaction.execute {
                        tasks.claimAvailable(1, 100, TaskLeaseClaim("worker-b", "token-b", 200))
                    }
                }
                val second = secondFuture.get(2, TimeUnit.SECONDS)

                assertEquals("task-1", first.task.id.value)
                assertEquals("task-2", second.single().task.id.value)
                assertEquals("worker-b", second.single().leaseOwner)
            } finally {
                firstConnection.rollback()
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }

        val recovered = transaction.execute {
            tasks.claimAvailable(1, 100, TaskLeaseClaim("worker-c", "token-c", 200)).single()
        }
        assertEquals("task-1", recovered.task.id.value)
    }

    @Test
    fun `outbox lease token fences every stale terminal transition`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val events = JdbcOutboxEventRepository(ObjectMapper())
        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        transaction.execute { events.append(event("event-1", 1)) }

        val stale = transaction.execute {
            processing.claimAvailable(1, 100, OutboxLeaseClaim("worker-a", "token-a", 150)).single()
        }
        val current = transaction.execute {
            processing.claimAvailable(1, 150, OutboxLeaseClaim("worker-b", "token-b", 250)).single()
        }

        assertFailsWith<OutboxLeaseLostException> {
            transaction.execute { processing.markSucceeded(stale, 151) }
        }
        assertFailsWith<OutboxLeaseLostException> {
            transaction.execute { processing.markForRetry(stale, 200, "late", 151) }
        }
        assertFailsWith<OutboxLeaseLostException> {
            transaction.execute { processing.markFailed(stale, "late", 151) }
        }
        transaction.execute { processing.markSucceeded(current, 151) }

        val state = transaction.execute {
            processing.findForMutation(Identifier("tenant-1"), Identifier("event-1"))
        }
        assertEquals("SUCCESS", state?.status?.name)
    }

    @Test
    fun `task lease token fences every stale terminal transition`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val tasks = JdbcTaskRepository(ObjectMapper(), Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC))
        transaction.execute { tasks.enqueue(task("task-1")) }

        val stale = transaction.execute {
            tasks.claimAvailable(1, 100, TaskLeaseClaim("worker-a", "token-a", 150)).single()
        }
        val current = transaction.execute {
            tasks.claimAvailable(1, 150, TaskLeaseClaim("worker-b", "token-b", 250)).single()
        }

        assertFailsWith<TaskLeaseLostException> {
            transaction.execute { tasks.markSucceeded(stale, 151) }
        }
        assertFailsWith<TaskLeaseLostException> {
            transaction.execute { tasks.markForRetry(stale, 200, "late", 151) }
        }
        assertFailsWith<TaskLeaseLostException> {
            transaction.execute { tasks.markFailed(stale, "late", 151) }
        }
        transaction.execute { tasks.markSucceeded(current, 151) }

        val state = transaction.execute {
            tasks.findForMutation(Identifier("tenant-1"), Identifier("task-1"))
        }
        assertEquals("SUCCESS", state?.status?.name)
    }

    private fun event(id: String, timestamp: Long): OutboxEvent = OutboxEvent(
        id = Identifier(id),
        tenantId = Identifier("tenant-1"),
        type = "document.publish.requested",
        payload = mapOf("documentId" to "document-1"),
        timestamp = timestamp,
    )

    private fun task(id: String): BackgroundTask = BackgroundTask(
        id = Identifier(id),
        tenantId = Identifier("tenant-1"),
        type = "document.delivery",
        idempotencyKey = "delivery:$id",
        businessId = Identifier("document-$id"),
        payload = mapOf("documentId" to "document-$id"),
    )

    private fun adminDataSource(): MysqlDataSource = MysqlDataSource().apply {
        setURL(environment("FILEWEFT_MYSQL_ADMIN_URL", "jdbc:mysql://localhost:3306"))
        user = environment("FILEWEFT_MYSQL_ADMIN_USER", "root")
        password = environment("FILEWEFT_MYSQL_ADMIN_PASSWORD", "")
    }

    private fun environment(name: String, fallback: String): String = System.getenv(name) ?: fallback
}
