package com.fileweft.persistence.jdbc

import com.fileweft.application.sync.SyncRecord
import com.fileweft.core.id.Identifier
import com.fileweft.persistence.migration.FlywayMigrationRunner
import com.fileweft.spi.connector.ConnectorSyncStatus
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
import kotlin.test.assertNull

class JdbcSyncRecordRepositoryIntegrationTest {
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
    fun `upserts connector result by tenant event and connector without cross tenant lookup`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcSyncRecordRepository(Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
        transaction.execute { repository.save(record(ConnectorSyncStatus.RETRYABLE_FAILURE, retryCount = 1, error = "offline")) }
        transaction.execute {
            repository.save(
                SyncRecord(
                    Identifier("ignored-on-upsert"), Identifier("tenant-1"), Identifier("document-1"), Identifier("event-1"),
                    "test-connector", ConnectorSyncStatus.SUCCESS, "external-1", retryCount = 1,
                ),
            )
        }

        val restored = transaction.execute {
            repository.findBySourceEvent(Identifier("tenant-1"), Identifier("event-1"), "test-connector")
        }
        val leaked = transaction.execute {
            repository.findBySourceEvent(Identifier("tenant-2"), Identifier("event-1"), "test-connector")
        }

        requireNotNull(restored)
        assertEquals("sync-1", restored.id.value)
        assertEquals(ConnectorSyncStatus.SUCCESS, restored.status)
        assertEquals("external-1", restored.externalId)
        assertEquals(1, restored.retryCount)
        assertNull(restored.errorMessage)
        assertNull(leaked)
    }

    private fun record(status: ConnectorSyncStatus, retryCount: Int, error: String?) = SyncRecord(
        Identifier("sync-1"), Identifier("tenant-1"), Identifier("document-1"), Identifier("event-1"),
        "test-connector", status, errorMessage = error, retryCount = retryCount,
    )

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }
}
