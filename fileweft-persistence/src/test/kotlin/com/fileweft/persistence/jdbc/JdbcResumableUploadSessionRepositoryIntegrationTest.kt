package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.upload.ResumableUploadPart
import com.fileweft.application.upload.ResumableUploadSession
import com.fileweft.application.upload.ResumableUploadSessionStatus
import com.fileweft.core.id.Identifier
import com.fileweft.persistence.migration.FlywayMigrationRunner
import com.fileweft.spi.storage.StorageObjectLocation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcResumableUploadSessionRepositoryIntegrationTest {
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
    fun `persists tenant isolated multipart parts and atomically transitions session state`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())
        val original = session("tenant-1", "session-1", "request-1", expiresAt = 1_000)
        transaction.execute {
            repository.save(original)
            repository.savePart(part("tenant-1", "session-1", 1, "part-1", 10))
            repository.savePart(part("tenant-1", "session-1", 2, "part-2", 11))
        }

        val restored = transaction.execute { repository.findById(Identifier("tenant-1"), Identifier("session-1")) }
        val leaked = transaction.execute { repository.findById(Identifier("tenant-2"), Identifier("session-1")) }
        val parts = transaction.execute { repository.findParts(Identifier("tenant-1"), Identifier("session-1")) }
        assertEquals("request-1", restored?.idempotencyKey)
        assertEquals("integration", restored?.metadata?.get("source"))
        assertNull(leaked)
        assertEquals(listOf(1, 2), parts.map { it.partNumber })

        val claimed = transaction.execute { repository.claimForCompletion(Identifier("tenant-1"), Identifier("session-1"), 100) }
        assertEquals(ResumableUploadSessionStatus.COMPLETING, claimed?.status)
        assertNull(transaction.execute { repository.claimForAbort(Identifier("tenant-1"), Identifier("session-1"), 101) })
        assertTrue(transaction.execute {
            repository.reactivateAfterCompletionFailure(Identifier("tenant-1"), Identifier("session-1"), "network reset", 102)
        })
        assertEquals(ResumableUploadSessionStatus.ACTIVE, transaction.execute {
            repository.findById(Identifier("tenant-1"), Identifier("session-1"))
        }?.status)

        assertTrue(transaction.execute { repository.claimForAbort(Identifier("tenant-1"), Identifier("session-1"), 103) != null })
        assertTrue(transaction.execute { repository.markAborted(Identifier("tenant-1"), Identifier("session-1"), expired = true, updatedAt = 104) })
        assertEquals(ResumableUploadSessionStatus.EXPIRED, transaction.execute {
            repository.findById(Identifier("tenant-1"), Identifier("session-1"))
        }?.status)
    }

    @Test
    fun `claims only active unexpired sessions and discovers expired unfinished sessions`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())
        transaction.execute {
            repository.save(session("tenant-1", "expired-active", "request-expired", expiresAt = 50))
            repository.save(session("tenant-1", "future-active", "request-future", expiresAt = 500))
        }

        assertNull(transaction.execute {
            repository.claimForCompletion(Identifier("tenant-1"), Identifier("expired-active"), 50)
        })
        val expired = transaction.execute { repository.findExpired(50, 10) }

        assertEquals(listOf("expired-active"), expired.map { it.id.value })
        assertEquals(ResumableUploadSessionStatus.COMPLETING, transaction.execute {
            repository.claimForCompletion(Identifier("tenant-1"), Identifier("future-active"), 50)
        }?.status)
    }

    private fun session(tenant: String, id: String, key: String, expiresAt: Long) = ResumableUploadSession(
        id = Identifier(id),
        tenantId = Identifier(tenant),
        idempotencyKey = key,
        storageUploadId = Identifier("storage-$id"),
        storageLocation = StorageObjectLocation("s3", "objects/${id.padEnd(64, 'a').take(64)}"),
        fileObjectId = Identifier("file-$id"),
        fileAssetId = Identifier("asset-$id"),
        fileName = "contract.pdf",
        contentLength = 21,
        assetType = "DOCUMENT",
        contentType = "application/pdf",
        metadata = mapOf("source" to "integration"),
        expiresAt = expiresAt,
        createdTime = 10,
        updatedTime = 10,
    )

    private fun part(tenant: String, sessionId: String, number: Int, id: String, time: Long) = ResumableUploadPart(
        id = Identifier(id), tenantId = Identifier(tenant), sessionId = Identifier(sessionId), partNumber = number,
        eTag = "etag-$number", contentLength = 10, createdTime = time, updatedTime = time,
    )

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }
}
