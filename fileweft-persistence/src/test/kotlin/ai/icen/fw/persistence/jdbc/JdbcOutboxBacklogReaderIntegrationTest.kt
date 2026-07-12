package ai.icen.fw.persistence.jdbc

import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JdbcOutboxBacklogReaderIntegrationTest {
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
    fun `classifies global outbox rows into mutually exclusive backlog groups`() {
        val now = 1_000L
        val legacyRunningBefore = 900L
        insert("ready-pending", "tenant-a", "PENDING", nextAttemptTime = now, createdTime = 200)
        insert("ready-retry", "tenant-b", "RETRY", nextAttemptTime = now - 1, createdTime = 100)
        insert("delayed-pending", "tenant-a", "PENDING", nextAttemptTime = now + 1, createdTime = 300)
        insert("delayed-retry", "tenant-b", "RETRY", nextAttemptTime = now + 2, createdTime = 400)
        insert(
            "expired-token", "tenant-a", "RUNNING", nextAttemptTime = 0, createdTime = 500,
            updatedTime = now + 100, leaseOwner = "worker-a", leaseToken = "token-a", leaseExpiresAt = now,
        )
        insert(
            "expired-legacy", "tenant-b", "RUNNING", nextAttemptTime = 0, createdTime = 600,
            updatedTime = legacyRunningBefore, leaseExpiresAt = now + 100,
        )
        insert(
            "running-token", "tenant-a", "RUNNING", nextAttemptTime = 0, createdTime = 700,
            updatedTime = 1, leaseOwner = "worker-b", leaseToken = "token-b", leaseExpiresAt = now + 1,
        )
        insert(
            "running-legacy", "tenant-b", "RUNNING", nextAttemptTime = 0, createdTime = 800,
            updatedTime = legacyRunningBefore + 1, leaseExpiresAt = 0,
        )
        insert("failed", "tenant-a", "FAILED", nextAttemptTime = 0, createdTime = 900)
        insert("successful", "tenant-b", "SUCCESS", nextAttemptTime = 0, createdTime = 950)

        val snapshot = snapshot(now, legacyRunningBefore)

        assertEquals(2, snapshot.readyCount)
        assertEquals(2, snapshot.delayedCount)
        assertEquals(2, snapshot.expiredCount)
        assertEquals(2, snapshot.runningCount)
        assertEquals(1, snapshot.failedCount)
        assertEquals(100, snapshot.oldestReadyCreatedTime)
    }

    @Test
    fun `treats a token lease expiring at the observation boundary as expired`() {
        insert(
            "expires-now", "tenant-a", "RUNNING", nextAttemptTime = 0, createdTime = 100,
            updatedTime = 1_001, leaseOwner = "worker-a", leaseToken = "token-a", leaseExpiresAt = 1_000,
        )
        insert(
            "expires-later", "tenant-a", "RUNNING", nextAttemptTime = 0, createdTime = 101,
            updatedTime = 1, leaseOwner = "worker-b", leaseToken = "token-b", leaseExpiresAt = 1_001,
        )

        val snapshot = snapshot(now = 1_000, legacyRunningBefore = 900)

        assertEquals(1, snapshot.expiredCount)
        assertEquals(1, snapshot.runningCount)
    }

    @Test
    fun `treats a no token running row updated at the legacy cutoff as expired`() {
        insert(
            "legacy-at-cutoff", "tenant-a", "RUNNING", nextAttemptTime = 0, createdTime = 100,
            updatedTime = 900, leaseExpiresAt = 9_999,
        )
        insert(
            "legacy-after-cutoff", "tenant-a", "RUNNING", nextAttemptTime = 0, createdTime = 101,
            updatedTime = 901, leaseExpiresAt = 0,
        )

        val snapshot = snapshot(now = 1_000, legacyRunningBefore = 900)

        assertEquals(1, snapshot.expiredCount)
        assertEquals(1, snapshot.runningCount)
    }

    @Test
    fun `reports oldest ready age and nulls it when no ready row exists`() {
        insert("delayed", "tenant-a", "PENDING", nextAttemptTime = 1_001, createdTime = 100)

        val noReady = snapshot(now = 1_000, legacyRunningBefore = 900)

        assertEquals(0, noReady.readyCount)
        assertNull(noReady.oldestReadyCreatedTime)
        assertEquals(0.0, noReady.oldestReadyAgeSeconds(1_000))

        insert("ready-younger", "tenant-a", "PENDING", nextAttemptTime = 1_000, createdTime = 900)
        insert("ready-oldest", "tenant-b", "RETRY", nextAttemptTime = 999, createdTime = 700)

        val ready = snapshot(now = 1_000, legacyRunningBefore = 900)

        assertEquals(2, ready.readyCount)
        assertEquals(700, ready.oldestReadyCreatedTime)
        assertEquals(0.3, ready.oldestReadyAgeSeconds(1_000))
    }

    @Test
    fun `requires a short caller transaction and validates observation bounds`() {
        val reader = JdbcOutboxBacklogReader()

        assertFailsWith<IllegalStateException> { reader.snapshot(1_000, 900) }
        assertFailsWith<IllegalArgumentException> { reader.snapshot(-1, 0) }
        assertFailsWith<IllegalArgumentException> { reader.snapshot(1_000, -1) }
        assertFailsWith<IllegalArgumentException> { reader.snapshot(1_000, 1_001) }

        val snapshot = JdbcApplicationTransaction(dataSource).execute { reader.snapshot(1_000, 900) }
        assertEquals(0, snapshot.readyCount)
        assertNull(snapshot.oldestReadyCreatedTime)
    }

    @Test
    fun `requires a positive bounded JDBC statement timeout`() {
        assertFailsWith<IllegalArgumentException> { JdbcOutboxBacklogReader(0) }
        assertFailsWith<IllegalArgumentException> { JdbcOutboxBacklogReader(-1) }
    }

    private fun snapshot(now: Long, legacyRunningBefore: Long) =
        JdbcApplicationTransaction(dataSource).execute {
            JdbcOutboxBacklogReader().snapshot(now, legacyRunningBefore)
        }

    private fun insert(
        id: String,
        tenantId: String,
        status: String,
        nextAttemptTime: Long,
        createdTime: Long,
        updatedTime: Long = createdTime,
        leaseOwner: String? = null,
        leaseToken: String? = null,
        leaseExpiresAt: Long = 0,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_SQL).use { statement ->
                statement.setString(1, id)
                statement.setString(2, tenantId)
                statement.setString(3, "document.publish.requested")
                statement.setString(4, status)
                statement.setLong(5, nextAttemptTime)
                statement.setNullableString(6, leaseOwner)
                statement.setNullableString(7, leaseToken)
                statement.setLong(8, leaseExpiresAt)
                statement.setLong(9, createdTime)
                statement.setLong(10, updatedTime)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }

    private companion object {
        const val INSERT_SQL = """
            INSERT INTO fw_outbox_event(
                id, tenant_id, event_type, payload_json, event_status, retry_count, next_attempt_time,
                lease_owner, lease_token, lease_expire_time, created_time, updated_time
            ) VALUES (?, ?, ?, '{}'::jsonb, ?, 0, ?, ?, ?, ?, ?, ?)
        """
    }
}
