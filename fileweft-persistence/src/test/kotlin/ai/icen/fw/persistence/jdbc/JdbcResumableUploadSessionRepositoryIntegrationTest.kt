package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.upload.ResumableUploadPart
import ai.icen.fw.application.upload.QuarantinableResumableUploadSessionRepository
import ai.icen.fw.application.upload.ResumableUploadSession
import ai.icen.fw.application.upload.ResumableUploadSessionStatus
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import ai.icen.fw.spi.storage.StorageObjectLocation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `persists owner scoped multipart sessions and preserves owner through state transitions`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())
        val original = session("tenant-1", "owner-1", "session-1", "request-1", expiresAt = 1_000)
        transaction.execute {
            repository.save(original)
            repository.savePart(part("tenant-1", "session-1", 1, "part-1", 10))
            repository.savePart(part("tenant-1", "session-1", 2, "part-2", 11))
        }

        val restored = transaction.execute { repository.findById(Identifier("tenant-1"), Identifier("session-1")) }
        val leaked = transaction.execute { repository.findById(Identifier("tenant-2"), Identifier("session-1")) }
        val owned = transaction.execute {
            repository.findById(Identifier("tenant-1"), "owner-1", Identifier("session-1"))
        }
        val wrongOwner = transaction.execute {
            repository.findById(Identifier("tenant-1"), "owner-2", Identifier("session-1"))
        }
        val ownedReplay = transaction.execute {
            repository.findByIdempotencyKey(Identifier("tenant-1"), "owner-1", "request-1")
        }
        val wrongOwnerReplay = transaction.execute {
            repository.findByIdempotencyKey(Identifier("tenant-1"), "owner-2", "request-1")
        }
        val parts = transaction.execute { repository.findParts(Identifier("tenant-1"), Identifier("session-1")) }
        assertEquals("request-1", restored?.idempotencyKey)
        assertEquals("owner-1", restored?.ownerId)
        assertEquals("integration", restored?.metadata?.get("source"))
        assertEquals("session-1", owned?.id?.value)
        assertEquals("session-1", ownedReplay?.id?.value)
        assertNull(leaked)
        assertNull(wrongOwner)
        assertNull(wrongOwnerReplay)
        assertEquals(listOf(1, 2), parts.map { it.partNumber })

        val claimed = transaction.execute { repository.claimForCompletion(Identifier("tenant-1"), Identifier("session-1"), 100) }
        assertEquals(ResumableUploadSessionStatus.COMPLETING, claimed?.status)
        assertEquals("owner-1", claimed?.ownerId)
        assertNull(transaction.execute { repository.claimForAbort(Identifier("tenant-1"), Identifier("session-1"), 101) })
        assertTrue(transaction.execute {
            repository.reactivateAfterCompletionFailure(Identifier("tenant-1"), Identifier("session-1"), "network reset", 102)
        })
        assertEquals(ResumableUploadSessionStatus.ACTIVE, transaction.execute {
            repository.findById(Identifier("tenant-1"), Identifier("session-1"))
        }?.status)
        assertEquals("owner-1", transaction.execute {
            repository.findById(Identifier("tenant-1"), Identifier("session-1"))
        }?.ownerId)

        val aborting = transaction.execute { repository.claimForAbort(Identifier("tenant-1"), Identifier("session-1"), 103) }
        assertEquals("owner-1", aborting?.ownerId)
        assertTrue(transaction.execute { repository.markAborted(Identifier("tenant-1"), Identifier("session-1"), expired = true, updatedAt = 104) })
        val expired = transaction.execute {
            repository.findById(Identifier("tenant-1"), Identifier("session-1"))
        }
        assertEquals(ResumableUploadSessionStatus.EXPIRED, expired?.status)
        assertEquals("owner-1", expired?.ownerId)
        assertEquals("network reset", expired?.lastError)
    }

    @Test
    fun `claims only active unexpired sessions and discovers expired unfinished sessions`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())
        transaction.execute {
            repository.save(session("tenant-1", "owner-1", "expired-active", "request-expired", expiresAt = 50))
            repository.save(session("tenant-1", "owner-2", "future-active", "request-future", expiresAt = 500))
        }

        assertNull(transaction.execute {
            repository.claimForCompletion(Identifier("tenant-1"), Identifier("expired-active"), 50)
        })
        val expired = transaction.execute { repository.findExpired(50, 10) }

        assertEquals(listOf("expired-active"), expired.map { it.id.value })
        assertEquals(listOf("owner-1"), expired.map { it.ownerId })
        assertEquals(ResumableUploadSessionStatus.COMPLETING, transaction.execute {
            repository.claimForCompletion(Identifier("tenant-1"), Identifier("future-active"), 50)
        }?.status)
        assertEquals(listOf("future-active"), transaction.execute {
            repository.findExpiredCompleting(500, 10)
        }.map { it.id.value })
        assertEquals(listOf("future-active"), transaction.execute {
            repository.findExpiredCompleting(Identifier("tenant-1"), 500, 10)
        }.map { it.id.value })
        assertEquals(emptyList(), transaction.execute {
            repository.findExpiredCompleting(Identifier("tenant-2"), 500, 10)
        })
    }

    @Test
    fun `refuses to create a new ownerless session while retaining nullable model compatibility`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())

        val failure = assertFailsWith<IllegalArgumentException> {
            transaction.execute {
                repository.save(session("tenant-1", null, "ownerless-new", "request-ownerless", expiresAt = 500))
            }
        }

        assertEquals("New resumable upload sessions must have a trusted owner id.", failure.message)
        assertNull(transaction.execute {
            repository.findById(Identifier("tenant-1"), Identifier("ownerless-new"))
        })
    }

    @Test
    fun `quarantine is monotonic excluded from expiry and cannot be reopened by ordinary transitions`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())
        val tenantId = Identifier("tenant-1")
        val sessionId = Identifier("quarantined-session")
        transaction.execute {
            repository.save(session("tenant-1", "owner-1", sessionId.value, "request-quarantined", expiresAt = 50))
        }

        val claimed = transaction.execute { repository.claimForAbort(tenantId, sessionId, 50) }
        assertEquals(ResumableUploadSessionStatus.ABORTING, claimed?.status)
        assertTrue(transaction.execute {
            repository.markQuarantined(tenantId, sessionId, "fileweft:resumable-upload:owner-isolation:v1", 51)
        })

        assertEquals(emptyList(), transaction.execute { repository.findExpired(1_000, 10) })
        assertNull(transaction.execute { repository.claimForAbort(tenantId, sessionId, 52) })
        assertTrue(!transaction.execute { repository.markFailed(tenantId, sessionId, "retry", 52) })
        assertTrue(!transaction.execute { repository.markAborted(tenantId, sessionId, expired = true, updatedAt = 52) })
        val quarantined = transaction.execute { repository.findById(tenantId, sessionId) }
        assertEquals(ResumableUploadSessionStatus.QUARANTINED, quarantined?.status)
        assertEquals("fileweft:resumable-upload:owner-isolation:v1", quarantined?.lastError)
        assertEquals("owner-1", quarantined?.ownerId)
    }

    @Test
    fun `exception after a real JDBC claim rolls the state transition back before external work`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())
        val tenantId = Identifier("tenant-1")
        val sessionId = Identifier("rollback-claim")
        transaction.execute {
            repository.save(session("tenant-1", "owner-1", sessionId.value, "request-rollback", expiresAt = 500))
        }

        assertFailsWith<IllegalStateException> {
            transaction.execute {
                val claimed = checkNotNull(repository.claimForAbort(tenantId, sessionId, 100))
                check(claimed.id == Identifier("misdirected-session")) { "simulated exact claim validation failure" }
            }
        }

        val restored = transaction.execute { repository.findById(tenantId, sessionId) }
        assertEquals(ResumableUploadSessionStatus.ACTIVE, restored?.status)
        assertEquals(10L, restored?.updatedTime)
    }

    @Test
    fun `activates only an exact owner bound staging row before its expiry`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())
        val marker = "fileweft:resumable-upload:creation-staging:v1"
        val tenantId = Identifier("tenant-1")
        transaction.execute {
            repository.save(
                session(
                    "tenant-1",
                    "owner-1",
                    "staged-active",
                    "request-staged-active",
                    expiresAt = 500,
                    status = ResumableUploadSessionStatus.ABORTING,
                    lastError = marker,
                ),
            )
            repository.save(
                session(
                    "tenant-1",
                    "owner-1",
                    "staged-expired",
                    "request-staged-expired",
                    expiresAt = 100,
                    status = ResumableUploadSessionStatus.ABORTING,
                    lastError = marker,
                ),
            )
        }

        assertTrue(!transaction.execute {
            repository.activateStaged(tenantId, Identifier("staged-active"), "owner-wrong", marker, 100)
        })
        assertTrue(transaction.execute {
            repository.activateStaged(tenantId, Identifier("staged-active"), "owner-1", marker, 100)
        })
        val active = transaction.execute { repository.findById(tenantId, Identifier("staged-active")) }
        assertEquals(ResumableUploadSessionStatus.ACTIVE, active?.status)
        assertNull(active?.lastError)

        assertTrue(!transaction.execute {
            repository.activateStaged(tenantId, Identifier("staged-expired"), "owner-1", marker, 100)
        })
        assertTrue(!transaction.execute {
            repository.markFailed(tenantId, Identifier("staged-expired"), "cleanup failed", 101)
        })
        assertTrue(!transaction.execute {
            repository.markAborted(tenantId, Identifier("staged-expired"), expired = true, updatedAt = 101)
        })
        val expiredStage = transaction.execute { repository.findById(tenantId, Identifier("staged-expired")) }
        assertEquals(ResumableUploadSessionStatus.ABORTING, expiredStage?.status)
        assertEquals(marker, expiredStage?.lastError)
    }

    @Test
    fun `ordinary aborting row with a null error can become failed while staging remains fenced`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())
        val tenantId = Identifier("tenant-1")
        val sessionId = Identifier("ordinary-abort-failure")
        transaction.execute {
            repository.save(
                session(
                    "tenant-1",
                    "owner-1",
                    sessionId.value,
                    "request-ordinary-abort-failure",
                    expiresAt = 500,
                ),
            )
        }

        val claimed = transaction.execute { repository.claimForAbort(tenantId, sessionId, 100) }
        assertEquals(ResumableUploadSessionStatus.ABORTING, claimed?.status)
        assertNull(claimed?.lastError)
        assertTrue(transaction.execute {
            repository.markFailed(tenantId, sessionId, "storage abort failed", 101)
        })

        val failed = transaction.execute { repository.findById(tenantId, sessionId) }
        assertEquals(ResumableUploadSessionStatus.FAILED, failed?.status)
        assertEquals("storage abort failed", failed?.lastError)
    }

    @Test
    fun `quarantine rejection or rollback leaves the separately committed claim hidden`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())
        val tenantId = Identifier("tenant-1")
        val falseSessionId = Identifier("quarantine-false")
        val rollbackSessionId = Identifier("quarantine-rollback")
        transaction.execute {
            repository.save(session("tenant-1", "owner-1", falseSessionId.value, "request-q-false", expiresAt = 500))
            repository.save(session("tenant-1", "owner-1", rollbackSessionId.value, "request-q-rollback", expiresAt = 500))
        }
        assertEquals(ResumableUploadSessionStatus.ABORTING, transaction.execute {
            repository.claimForAbort(tenantId, falseSessionId, 100)
        }?.status)
        assertEquals(ResumableUploadSessionStatus.ABORTING, transaction.execute {
            repository.claimForAbort(tenantId, rollbackSessionId, 100)
        }?.status)

        val rejecting = object : QuarantinableResumableUploadSessionRepository by repository {
            override fun markQuarantined(
                tenantId: Identifier,
                sessionId: Identifier,
                message: String,
                updatedAt: Long,
            ): Boolean = false
        }
        assertTrue(!transaction.execute {
            rejecting.markQuarantined(tenantId, falseSessionId, "isolation", 101)
        })
        assertFailsWith<IllegalStateException> {
            transaction.execute {
                check(repository.markQuarantined(tenantId, rollbackSessionId, "isolation", 101))
                error("simulated quarantine transaction failure")
            }
        }

        listOf(falseSessionId, rollbackSessionId).forEach { sessionId ->
            val hidden = transaction.execute { repository.findById(tenantId, sessionId) }
            assertEquals(ResumableUploadSessionStatus.ABORTING, hidden?.status)
            assertNull(hidden?.lastError)
        }
    }

    private fun session(
        tenant: String,
        owner: String?,
        id: String,
        key: String,
        expiresAt: Long,
        status: ResumableUploadSessionStatus = ResumableUploadSessionStatus.ACTIVE,
        lastError: String? = null,
    ) = ResumableUploadSession(
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
        status = status,
        expiresAt = expiresAt,
        lastError = lastError,
        createdTime = 10,
        updatedTime = 10,
        ownerId = owner,
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
