package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaim
import ai.icen.fw.application.upload.PresignedUploadSession
import ai.icen.fw.application.upload.PresignedUploadSessionStatus
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import ai.icen.fw.spi.storage.PresignedUploadFinalization
import ai.icen.fw.spi.storage.StorageContentChecksum
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StoredObject
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcPresignedUploadSessionRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var transaction: JdbcApplicationTransaction
    private lateinit var repository: JdbcPresignedUploadSessionRepository

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
        transaction = JdbcApplicationTransaction(dataSource)
        repository = JdbcPresignedUploadSessionRepository(ObjectMapper())
    }

    @AfterEach
    fun cleanSchema() {
        if (::dataSource.isInitialized) reset(dataSource.connection)
    }

    @Test
    fun `persists owner scoped authority and enforces claim token plus version CAS`() {
        val ready = session("upload-1")
        transaction.execute {
            assertTrue(repository.create(ready))
            assertEquals(ready.id, repository.findById(TENANT_ID, ready.id)?.id)
            assertEquals(ready.id, repository.findById(TENANT_ID, OWNER_ID, ready.id)?.id)
            assertNull(repository.findById(TENANT_ID, "other-owner", ready.id))
            assertEquals(
                ready.id,
                repository.findByIdempotencyKey(TENANT_ID, OWNER_ID, ready.idempotencyKeyDigest)?.id,
            )
        }

        val claimed = session(
            id = ready.id.value,
            status = PresignedUploadSessionStatus.FINALIZING,
            version = 1,
            claimTime = 200,
            claimToken = CLAIM_TOKEN,
            claimExpiresAt = 300,
            updatedTime = 200,
        )
        transaction.execute {
            assertTrue(repository.compareAndSet(TENANT_ID, ready.id, 0, null, claimed))
            assertFalse(
                repository.compareAndSet(
                    TENANT_ID,
                    ready.id,
                    1,
                    OTHER_CLAIM_TOKEN,
                    session(id = ready.id.value, version = 2),
                ),
            )
            assertEquals(
                listOf(ready.id),
                repository.findRecoveryCandidates(300, 10).map { it.id },
            )
        }

        val completedAt = 400L
        val completed = session(
            id = ready.id.value,
            status = PresignedUploadSessionStatus.COMPLETED,
            version = 2,
            finalization = finalization(ready),
            completedTime = completedAt,
            updatedTime = completedAt,
        )
        transaction.execute {
            assertTrue(repository.compareAndSet(TENANT_ID, ready.id, 1, CLAIM_TOKEN, completed))
            val durable = requireNotNull(repository.findById(TENANT_ID, OWNER_ID, ready.id))
            assertEquals(PresignedUploadSessionStatus.COMPLETED, durable.status)
            assertEquals(completed.finalLocation, durable.finalLocation)
            assertEquals(completed.finalization?.revision, durable.finalization?.revision)
            assertFalse(repository.compareAndSet(TENANT_ID, ready.id, 1, CLAIM_TOKEN, completed))
        }

        val assetClaim = CompletedPresignedUploadAssetClaim(
            tenantId = TENANT_ID,
            uploadId = ready.id,
            fileObjectId = Identifier("file-object-1"),
            fileAssetId = Identifier("file-asset-1"),
            idempotencyKeyDigest = DIGEST_C,
            purpose = "DOCUMENT",
            claimedBy = OWNER_ID,
            claimedTime = 500,
        )
        transaction.execute {
            val locked = requireNotNull(repository.lockCompletedAssetClaim(TENANT_ID, OWNER_ID, ready.id))
            assertNull(locked.claim)
            val claimed = requireNotNull(repository.markCompletedAssetClaimed(locked.session, assetClaim))
            assertEquals(3, claimed.session.version)
            assertEquals(assetClaim.fileObjectId, claimed.claim?.fileObjectId)
            assertNull(repository.markCompletedAssetClaimed(claimed.session, assetClaim))
        }
    }

    @Test
    fun `concurrent asset claims have exactly one durable winner`() {
        val completed = session(
            id = "upload-concurrent",
            status = PresignedUploadSessionStatus.COMPLETED,
            finalization = finalization(session("upload-concurrent")),
            completedTime = 400,
            updatedTime = 400,
        )
        assertTrue(transaction.execute { repository.create(completed) })

        val claims = listOf(
            assetClaim(completed, "file-object-a", "file-asset-a", DIGEST_C),
            assetClaim(completed, "file-object-b", "file-asset-b", DIGEST_D),
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(claims.size)
        try {
            val results = claims.map { claim ->
                executor.submit<Boolean> {
                    start.await(10, TimeUnit.SECONDS)
                    transaction.execute { repository.markCompletedAssetClaimed(completed, claim) != null }
                }
            }
            start.countDown()

            assertEquals(1, results.count { future -> future.get(30, TimeUnit.SECONDS) })
            val durable = transaction.execute {
                requireNotNull(repository.findCompletedAssetClaim(TENANT_ID, OWNER_ID, completed.id))
            }
            assertEquals(1, claims.count { candidate -> candidate.fileObjectId == durable.claim?.fileObjectId })
            assertEquals(1, durable.session.version)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `returns conflicts for owner idempotency or staging reuse and never cleans before grant expiry`() {
        val first = session("upload-1")
        val sameKey = session(
            id = "upload-2",
            idempotencyKeyDigest = first.idempotencyKeyDigest,
            stagingPath = "objects/tenant/upload-2",
        )
        val sameStaging = session(
            id = "upload-3",
            tenantId = Identifier("tenant-2"),
            idempotencyKeyDigest = DIGEST_C,
            stagingPath = first.stagingLocation.path,
        )
        val cancelled = session(
            id = "upload-4",
            idempotencyKeyDigest = DIGEST_D,
            stagingPath = "objects/tenant/upload-4",
            status = PresignedUploadSessionStatus.CANCELLED,
            cancelledTime = 200,
            updatedTime = 200,
        )

        assertTrue(transaction.execute { repository.create(first) })
        assertFalse(transaction.execute { repository.create(sameKey) })
        assertFalse(transaction.execute { repository.create(sameStaging) })
        assertTrue(transaction.execute { repository.create(cancelled) })
        transaction.execute {
            assertTrue(repository.findCleanupCandidates(999, 10).isEmpty())
            assertEquals(
                listOf(cancelled.id),
                repository.findCleanupCandidates(1_000, 10).map { it.id },
            )
        }
    }

    @Test
    fun `aggregates bounded tenant and global maintenance diagnostics`() {
        val finalizing = session(
            id = "upload-finalizing",
            idempotencyKeyDigest = DIGEST_C,
            stagingPath = "objects/tenant/upload-finalizing",
            status = PresignedUploadSessionStatus.FINALIZING,
            version = 1,
            claimTime = 200,
            claimToken = CLAIM_TOKEN,
            claimExpiresAt = 300,
            updatedTime = 200,
        )
        val cancelled = session(
            id = "upload-cancelled",
            idempotencyKeyDigest = DIGEST_D,
            stagingPath = "objects/tenant/upload-cancelled",
            status = PresignedUploadSessionStatus.CANCELLED,
            cancelledTime = 200,
            updatedTime = 200,
        )
        val completedSeed = session(
            id = "upload-completed",
            idempotencyKeyDigest = "sha256:${"1".repeat(64)}",
            stagingPath = "objects/tenant/upload-completed",
        )
        val completed = session(
            id = completedSeed.id.value,
            idempotencyKeyDigest = completedSeed.idempotencyKeyDigest,
            stagingPath = completedSeed.stagingLocation.path,
            status = PresignedUploadSessionStatus.COMPLETED,
            version = 1,
            finalization = finalization(completedSeed),
            completedTime = 400,
            updatedTime = 400,
        )
        transaction.execute {
            assertTrue(repository.create(finalizing))
            assertTrue(repository.create(cancelled))
            assertTrue(repository.create(completed))
        }

        val diagnostics = JdbcPresignedUploadDiagnosticsRepository()
        val tenant = transaction.execute { diagnostics.snapshot(TENANT_ID, 1_000) }
        val global = transaction.execute { diagnostics.snapshot(null, 1_000) }

        assertEquals(1, tenant.stuckClaimCount)
        assertEquals(1, tenant.cleanupDueCount)
        assertEquals(1, tenant.orphanRiskCount)
        assertEquals(tenant.orphanRiskCount, global.orphanRiskCount)
        assertEquals(0, tenant.cleanupFailureCount)
    }

    private fun session(
        id: String,
        tenantId: Identifier = TENANT_ID,
        idempotencyKeyDigest: String = DIGEST_A,
        stagingPath: String = "objects/tenant/$id",
        status: PresignedUploadSessionStatus = PresignedUploadSessionStatus.READY,
        version: Long = 0,
        claimTime: Long? = null,
        claimToken: String? = null,
        claimExpiresAt: Long? = null,
        finalization: PresignedUploadFinalization? = null,
        completedTime: Long? = null,
        cancelledTime: Long? = null,
        updatedTime: Long = 100,
    ): PresignedUploadSession = PresignedUploadSession(
        id = Identifier(id),
        tenantId = tenantId,
        ownerId = OWNER_ID,
        fileName = "contract.txt",
        contentLength = 7,
        contentType = "text/plain",
        contentHash = CONTENT_HASH,
        checksum = CHECKSUM,
        metadata = mapOf("business" to "legal"),
        storageLocation = StorageObjectLocation("test", stagingPath),
        grantExpiresAt = 1_000,
        sessionExpiresAt = 2_000,
        status = status,
        version = version,
        claimTime = claimTime,
        finalization = finalization,
        createdTime = 100,
        updatedTime = updatedTime,
        idempotencyKeyDigest = idempotencyKeyDigest,
        declarationDigest = DIGEST_B,
        grantDurationMillis = 900,
        requiredHeaders = mapOf("Content-Type" to "text/plain"),
        claimToken = claimToken,
        claimExpiresAt = claimExpiresAt,
        completedTime = completedTime,
        cancelledTime = cancelledTime,
    )

    private fun finalization(session: PresignedUploadSession): PresignedUploadFinalization =
        PresignedUploadFinalization(
            tenantId = session.tenantId,
            bindingId = session.id,
            sourceLocation = session.stagingLocation,
            storedObject = StoredObject(
                StorageObjectLocation("test", "bound/${session.id.value}/version-1"),
                session.contentLength,
                session.contentType,
                session.contentHash,
            ),
            revision = "version-1",
            checksum = session.checksum,
            metadata = session.metadata,
        )

    private fun assetClaim(
        session: PresignedUploadSession,
        fileObjectId: String,
        fileAssetId: String,
        keyDigest: String,
    ): CompletedPresignedUploadAssetClaim = CompletedPresignedUploadAssetClaim(
        tenantId = session.tenantId,
        uploadId = session.id,
        fileObjectId = Identifier(fileObjectId),
        fileAssetId = Identifier(fileAssetId),
        idempotencyKeyDigest = keyDigest,
        purpose = "DOCUMENT",
        claimedBy = session.ownerId,
        claimedTime = 500,
    )

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        const val OWNER_ID = "owner-1"
        const val CONTENT_HASH = "sha256:239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5"
        val DIGEST_A = "sha256:${"a".repeat(64)}"
        val DIGEST_B = "sha256:${"b".repeat(64)}"
        val DIGEST_C = "sha256:${"c".repeat(64)}"
        val DIGEST_D = "sha256:${"d".repeat(64)}"
        val CLAIM_TOKEN = "sha256:${"e".repeat(64)}"
        val OTHER_CLAIM_TOKEN = "sha256:${"f".repeat(64)}"
        val CHECKSUM = StorageContentChecksum("md5", "CY9rzUYh03PK3k6DJie09g==")
    }
}
