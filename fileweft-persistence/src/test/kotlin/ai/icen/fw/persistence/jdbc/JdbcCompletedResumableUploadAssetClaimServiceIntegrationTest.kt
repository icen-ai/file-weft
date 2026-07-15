package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.upload.AddDocumentVersionFromCompletedUploadCommand
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaim
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimRepository
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimService
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimState
import ai.icen.fw.application.upload.CreateDocumentFromCompletedUploadCommand
import ai.icen.fw.application.upload.ResumableUploadSession
import ai.icen.fw.application.upload.ResumableUploadSessionRepository
import ai.icen.fw.application.upload.ResumableUploadSessionStatus
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcCompletedResumableUploadAssetClaimServiceIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var transaction: JdbcApplicationTransaction
    private lateinit var uploads: JdbcResumableUploadSessionRepository
    private lateinit var documents: JdbcDocumentRepository
    private lateinit var fileObjects: JdbcFileObjectRepository
    private lateinit var fileAssets: JdbcFileAssetRepository
    private lateinit var identifiers: SequenceIdentifiers

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
        uploads = JdbcResumableUploadSessionRepository(ObjectMapper())
        documents = JdbcDocumentRepository(CLOCK)
        fileObjects = JdbcFileObjectRepository(CLOCK)
        fileAssets = JdbcFileAssetRepository(ObjectMapper(), CLOCK)
        identifiers = SequenceIdentifiers()
    }

    @AfterEach
    fun cleanSchema() {
        if (::dataSource.isInitialized) reset(dataSource.connection)
    }

    @Test
    fun `document claim idempotency and document rows share one JDBC commit`() {
        val upload = seedCompletedUpload("atomic-create")
        val failingClaims = object : CompletedResumableUploadAssetClaimRepository by uploads {
            override fun markCompletedAssetClaimed(
                expected: ResumableUploadSession,
                claim: CompletedResumableUploadAssetClaim,
            ): CompletedResumableUploadAssetClaimState? {
                uploads.markCompletedAssetClaimed(expected, claim) ?: return null
                throw IllegalStateException("simulated failure after JDBC claim marker write")
            }
        }
        val command = CreateDocumentFromCompletedUploadCommand(
            upload.id,
            "DOC-ATOMIC",
            "原子创建",
            "atomic-create-key",
        )

        assertFailsWith<IllegalStateException> {
            claimService(failingClaims).createDocument(command)
        }

        assertEquals(0, rowCount("fw_document"))
        assertEquals(0, rowCount("fw_document_version"))
        assertEquals(0, rowCount("fw_idempotency_record"))
        val rolledBack = transaction.execute {
            uploads.findCompletedAssetClaim(TENANT_ID, OWNER_ID, upload.id)
        }
        assertNull(rolledBack?.claim)
        assertEquals(100L, rolledBack?.session?.updatedTime)

        val service = claimService(uploads)
        val created = service.createDocument(command)
        val replay = service.createDocument(command)

        assertFalse(created.replayed)
        assertTrue(replay.replayed)
        assertEquals(created.documentId, replay.documentId)
        assertEquals(created.versionId, replay.versionId)
        assertEquals(1, rowCount("fw_document"))
        assertEquals(1, rowCount("fw_document_version"))
        assertEquals(1, rowCount("fw_idempotency_record"))
        val committed = transaction.execute {
            uploads.findCompletedAssetClaim(TENANT_ID, OWNER_ID, upload.id)
        }
        assertEquals(created.documentId, committed?.claim?.resourceId)
        assertEquals(created.versionId, committed?.claim?.subresourceId)
        assertEquals(200L, committed?.session?.updatedTime)
    }

    @Test
    fun `new document version consumes its exact completed asset and replays stably`() {
        val upload = seedCompletedUpload("atomic-version")
        val documentId = Identifier("existing-document")
        transaction.execute {
            val originalFile = FileObject(
                Identifier("existing-file"),
                TENANT_ID,
                "original.pdf",
                8,
                "s3",
                "objects/original",
                "application/pdf",
                "sha256:original",
            )
            val originalAsset = FileAsset(
                Identifier("existing-asset"),
                TENANT_ID,
                originalFile.id,
                "DOCUMENT",
            )
            fileObjects.save(originalFile)
            fileAssets.save(originalAsset)
            documents.save(
                Document(
                    id = documentId,
                    tenantId = TENANT_ID,
                    assetId = originalAsset.id,
                    documentNumber = "DOC-EXISTING",
                    title = "现有文档",
                    versions = listOf(
                        DocumentVersion(
                            Identifier("existing-version"),
                            TENANT_ID,
                            documentId,
                            "1.0",
                            originalFile.id,
                        ),
                    ),
                    currentVersionId = Identifier("existing-version"),
                ),
            )
        }
        val command = AddDocumentVersionFromCompletedUploadCommand(
            upload.id,
            documentId,
            "2.0",
            "atomic-version-key",
        )
        val service = claimService(uploads)

        val added = service.addDocumentVersion(command)
        val replay = service.addDocumentVersion(command)
        val document = transaction.execute { documents.findById(TENANT_ID, documentId) }

        assertFalse(added.replayed)
        assertTrue(replay.replayed)
        assertEquals(added.versionId, replay.versionId)
        assertEquals(2, document?.versions?.size)
        assertEquals(
            upload.fileObjectId,
            document?.versions?.single { version -> version.id == added.versionId }?.fileObjectId,
        )
        assertEquals(Identifier("existing-asset"), document?.assetId)
        val claim = transaction.execute {
            uploads.findCompletedAssetClaim(TENANT_ID, OWNER_ID, upload.id)?.claim
        }
        assertEquals(documentId, claim?.resourceId)
        assertEquals(added.versionId, claim?.subresourceId)
    }

    private fun claimService(
        sessions: ResumableUploadSessionRepository,
    ): CompletedResumableUploadAssetClaimService = CompletedResumableUploadAssetClaimService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(TENANT_ID)
        },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier(OWNER_ID), "Uploader")
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
        },
        uploadSessions = sessions,
        documents = documents,
        fileObjects = fileObjects,
        fileAssets = fileAssets,
        idempotencyRepository = JdbcRequestIdempotencyRepository(),
        transaction = transaction,
        identifiers = identifiers,
        clock = CLOCK,
    )

    private fun seedCompletedUpload(id: String): ResumableUploadSession {
        val session = ResumableUploadSession(
            id = Identifier(id),
            tenantId = TENANT_ID,
            idempotencyKey = "start-$id",
            storageUploadId = Identifier("storage-$id"),
            storageLocation = StorageObjectLocation("s3", "objects/$id"),
            fileObjectId = Identifier("file-$id"),
            fileAssetId = Identifier("asset-$id"),
            fileName = "$id.pdf",
            contentLength = 21,
            assetType = "DOCUMENT",
            contentType = "application/pdf",
            expectedContentHash = "sha256:content",
            metadata = mapOf("source" to "integration"),
            status = ResumableUploadSessionStatus.COMPLETED,
            expiresAt = 1_000,
            completedAt = 100,
            createdTime = 10,
            updatedTime = 100,
            ownerId = OWNER_ID,
        )
        transaction.execute {
            uploads.save(session)
            fileObjects.save(
                FileObject(
                    session.fileObjectId,
                    session.tenantId,
                    session.fileName,
                    session.contentLength,
                    session.storageLocation.storageType,
                    session.storageLocation.path,
                    session.contentType,
                    session.expectedContentHash,
                ),
            )
            fileAssets.save(
                FileAsset(
                    session.fileAssetId,
                    session.tenantId,
                    session.fileObjectId,
                    session.assetType,
                    session.metadata,
                ),
            )
        }
        return session
    }

    private fun rowCount(table: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                check(result.next())
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

    private class SequenceIdentifiers : IdentifierGenerator {
        private val sequence = AtomicInteger()
        override fun nextId(): Identifier = Identifier("claim-generated-${sequence.incrementAndGet()}")
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        const val OWNER_ID = "owner-1"
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(200), ZoneOffset.UTC)
    }
}
