package com.fileweft.persistence.jdbc

import com.fileweft.persistence.migration.FlywayMigrationRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals

class JdbcApplicationTransactionIntegrationTest {
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
    fun `commits successful work and rolls back failed work`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        transaction.execute { insertOutboxEvent("event-1") }
        assertEquals(1, countOutboxEvents())

        assertThrows<IllegalStateException> {
            transaction.execute {
                insertOutboxEvent("event-2")
                throw IllegalStateException("fail after write")
            }
        }
        assertEquals(1, countOutboxEvents())
    }

    private fun insertOutboxEvent(id: String) {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "INSERT INTO fw_outbox_event(id, tenant_id, event_type, payload_json, event_status, retry_count, created_time, updated_time) VALUES (?, 'tenant-1', 'test', '{}'::jsonb, 'PENDING', 0, 1, 1)",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeUpdate()
        }
    }

    private fun countOutboxEvents(): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM fw_outbox_event").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }
}
