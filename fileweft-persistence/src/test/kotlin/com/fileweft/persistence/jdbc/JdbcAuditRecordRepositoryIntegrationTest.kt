package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.core.id.Identifier
import com.fileweft.domain.audit.AuditRecord
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

class JdbcAuditRecordRepositoryIntegrationTest {
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
    fun `appends immutable audit history and filters it by tenant resource and limit`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcAuditRecordRepository(ObjectMapper())
        transaction.execute {
            repository.append(record("audit-1", "tenant-1", "document.sync", 10))
            repository.append(record("audit-2", "tenant-1", "document.archive", 20))
            repository.append(record("audit-3", "tenant-2", "document.sync", 30))
        }

        val latest = transaction.execute {
            repository.findByResource(Identifier("tenant-1"), "DOCUMENT", Identifier("document-1"), 1)
        }
        val all = transaction.execute {
            repository.findByResource(Identifier("tenant-1"), "DOCUMENT", Identifier("document-1"), 10)
        }

        assertEquals(listOf("audit-2"), latest.map { it.id.value })
        assertEquals(listOf("audit-2", "audit-1"), all.map { it.id.value })
        assertEquals("SUCCESS", all.last().details["status"])
        assertTrue(all.none { it.tenantId.value == "tenant-2" })
    }

    private fun record(id: String, tenantId: String, action: String, time: Long) = AuditRecord(
        Identifier(id), Identifier(tenantId), "DOCUMENT", Identifier("document-1"), action,
        Identifier("operator-1"), mapOf("status" to "SUCCESS"), time,
    )

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }
}
