package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.core.id.Identifier
import com.fileweft.domain.operation.OperationLogRecord
import com.fileweft.persistence.migration.FlywayMigrationRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdbcOperationLogRepositoryIntegrationTest {
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
    fun `persists operation evidence isolated by tenant resource and trace`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcOperationLogRepository(ObjectMapper())
        transaction.execute {
            repository.append(record("operation-1", "tenant-1", "document:create", "trace-1", 10))
            repository.append(record("operation-2", "tenant-1", "document:rename", "trace-2", 20))
            repository.append(record("operation-3", "tenant-2", "document:create", "trace-3", 30))
        }

        val records = transaction.execute {
            repository.findByResource(Identifier("tenant-1"), "DOCUMENT", Identifier("document-1"), 10)
        }

        assertEquals(listOf("operation-2", "operation-1"), records.map { it.id.value })
        assertEquals("trace-2", records.first().traceId?.value)
        assertEquals("Operator 1", records.first().operatorName)
        assertEquals("HTTP", records.first().details["source"])
        assertTrue(records.none { it.tenantId.value == "tenant-2" })
    }

    private fun record(id: String, tenantId: String, action: String, traceId: String, time: Long) = OperationLogRecord(
        id = Identifier(id),
        tenantId = Identifier(tenantId),
        resourceType = "DOCUMENT",
        resourceId = Identifier("document-1"),
        action = action,
        operatorId = Identifier("operator-1"),
        operatorName = "Operator 1",
        traceId = Identifier(traceId),
        details = mapOf("source" to "HTTP"),
        createdAt = time,
    )

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }
}
