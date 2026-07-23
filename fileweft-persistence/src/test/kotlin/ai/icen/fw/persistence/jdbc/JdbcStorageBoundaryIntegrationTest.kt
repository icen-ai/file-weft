package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.document.AddDocumentVersionCommand
import ai.icen.fw.application.document.CreateDocumentDraftCommand
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.transaction.ApplicationTransactionNestingException
import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.StartResumableUploadCommand
import ai.icen.fw.application.upload.UploadApplicationService
import ai.icen.fw.application.upload.UploadFileCommand
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentMutationRepository
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.ds.PGSimpleDataSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class JdbcStorageBoundaryIntegrationTest {
    @Test
    fun `real jdbc ambient transaction rejects every direct storage write before side effects`() {
        assumeTrue(System.getenv("FILEWEFT_RUN_POSTGRES_TESTS") == "true")
        val transaction = JdbcApplicationTransaction(postgresDataSource())
        val storage = CountingStorage()
        val identifiers = CountingIdentifiers()
        val uploadService = UploadApplicationService(
            tenantProvider = Tenant,
            userRealmProvider = UserRealm,
            authorizationProvider = AllowAll,
            storageAdapter = storage,
            fileObjectRepository = UntouchedFileObjects,
            fileAssetRepository = UntouchedFileAssets,
            outboxEventRepository = UntouchedOutbox,
            identifierGenerator = identifiers,
            transaction = transaction,
            clock = Clock.systemUTC(),
        )
        val documentService = DocumentDraftService(
            tenantProvider = Tenant,
            userRealmProvider = UserRealm,
            authorizationProvider = AllowAll,
            storageAdapter = storage,
            documentRepository = UntouchedDocuments,
            fileObjectRepository = UntouchedFileObjects,
            fileAssetRepository = UntouchedFileAssets,
            identifierGenerator = identifiers,
            transaction = transaction,
        )
        val resumableService = ResumableUploadService(
            tenantProvider = Tenant,
            userRealmProvider = UserRealm,
            authorizationProvider = AllowAll,
            storageAdapter = storage,
            sessions = JdbcResumableUploadSessionRepository(ObjectMapper()),
            fileObjects = UntouchedFileObjects,
            fileAssets = UntouchedFileAssets,
            outbox = UntouchedOutbox,
            identifiers = identifiers,
            transaction = transaction,
            clock = Clock.systemUTC(),
        )
        val absentSessionId = Identifier("session-never-read")

        transaction.execute {
            assertThrows<ApplicationTransactionNestingException> {
                uploadService.upload(
                    UploadFileCommand("upload.txt", 1, "DOCUMENT", "text/plain"),
                    ByteArrayInputStream(byteArrayOf(1)),
                )
            }
            assertThrows<ApplicationTransactionNestingException> {
                documentService.create(
                    CreateDocumentDraftCommand("DOC-1", "Draft", "draft.txt", 1, "text/plain"),
                    ByteArrayInputStream(byteArrayOf(1)),
                )
            }
            assertThrows<ApplicationTransactionNestingException> {
                documentService.addVersion(
                    Identifier("document-1"),
                    AddDocumentVersionCommand("1.1", "revision.txt", 1, "text/plain"),
                    ByteArrayInputStream(byteArrayOf(1)),
                )
            }
            assertThrows<ApplicationTransactionNestingException> {
                resumableService.start(
                    StartResumableUploadCommand(
                        fileName = "multipart.txt",
                        contentLength = 1,
                        assetType = "DOCUMENT",
                        idempotencyKey = "ambient-boundary",
                        contentType = "text/plain",
                    ),
                )
            }
            assertThrows<ApplicationTransactionNestingException> {
                resumableService.uploadPart(absentSessionId, 1, 1, ByteArrayInputStream(byteArrayOf(1)))
            }
            assertThrows<ApplicationTransactionNestingException> { resumableService.complete(absentSessionId) }
            assertThrows<ApplicationTransactionNestingException> { resumableService.abort(absentSessionId) }
            assertThrows<ApplicationTransactionNestingException> { resumableService.cleanupExpired() }
        }

        assertEquals(0, storage.calls.get())
        assertEquals(0, identifiers.calls.get())
    }

    private fun postgresDataSource() = PGSimpleDataSource().apply {
        setURL(System.getenv("FILEWEFT_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/fileweft")
        user = System.getenv("FILEWEFT_POSTGRES_USER") ?: "fileweft"
        password = System.getenv("FILEWEFT_POSTGRES_PASSWORD") ?: "fileweft-dev"
    }

    private object Tenant : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
    }

    private object UserRealm : UserRealmProvider {
        override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-1"))
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private object AllowAll : AuthorizationProvider {
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
    }

    private class CountingIdentifiers : IdentifierGenerator {
        val calls = AtomicInteger()
        override fun nextId(): Identifier = Identifier("unexpected-${calls.incrementAndGet()}")
    }

    private class CountingStorage : StorageAdapter {
        val calls = AtomicInteger()
        private fun touched(): Nothing {
            calls.incrementAndGet()
            error("storage must not be called inside an ambient transaction")
        }
        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject = touched()
        override fun delete(location: StorageObjectLocation): Unit = touched()
        override fun download(location: StorageObjectLocation): StorageDownload = touched()
        override fun exists(location: StorageObjectLocation): Boolean = touched()
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = touched()
        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = touched()
        override fun uploadPart(
            upload: MultipartUpload,
            partNumber: Int,
            content: InputStream,
            contentLength: Long,
        ): MultipartPart = touched()
        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject = touched()
        override fun abortMultipartUpload(upload: MultipartUpload): Unit = touched()
    }

    private object UntouchedFileObjects : FileObjectRepository {
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? = touched()
        override fun save(fileObject: FileObject): Unit = touched()
    }

    private object UntouchedFileAssets : FileAssetRepository {
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? = touched()
        override fun save(fileAsset: FileAsset): Unit = touched()
    }

    private object UntouchedDocuments : DocumentMutationRepository {
        override fun findById(tenantId: Identifier, documentId: Identifier): Document? = touched()
        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? = touched()
        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? = touched()
        override fun save(document: Document): Unit = touched()
    }

    private object UntouchedOutbox : OutboxEventRepository {
        override fun append(event: OutboxEvent): Unit = touched()
    }

    private companion object {
        fun touched(): Nothing = error("persistence must not be called inside an ambient transaction")
    }
}
