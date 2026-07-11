package com.fileweft.application.document

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.file.FileObject
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.storage.MultipartPart
import com.fileweft.spi.storage.MultipartUpload
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageDownload
import com.fileweft.spi.storage.StorageObjectLocation
import com.fileweft.spi.storage.StorageUploadRequest
import com.fileweft.spi.storage.StoredObject
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentDownloadServiceTest {
    @Test
    fun `authorizes then streams the selected tenant version and audits the intent`() {
        val storage = RecordingStorage()
        val audits = RecordingAudits()
        val service = service(storage = storage, audits = audits)

        val download = service.download(Identifier("document-1"))
        val bytes = download.use { it.content.readBytes() }

        assertEquals("document content", bytes.decodeToString())
        assertEquals(StorageObjectLocation("local", "tenant-1/document-1.txt"), storage.requested.single())
        assertEquals("document:download", audits.records.single().action)
        assertEquals("version-1", audits.records.single().details["versionId"])
        assertEquals("file-1", audits.records.single().details["fileObjectId"])
        assertEquals(16L, download.expectedContentLength)
        assertEquals(16L, download.verifiedContentLength)
        assertEquals(16L, download.contentLength)
    }

    @Test
    fun `wraps a storage open failure after recording the download intent`() {
        val storageFailure = IllegalStateException("s3://internal-bucket/tenant-object is unavailable")
        val storage = RecordingStorage(openFailure = storageFailure)
        val audits = RecordingAudits()
        val service = service(storage = storage, audits = audits)

        val failure = assertThrows<DocumentContentUnavailableException> {
            service.download(Identifier("document-1"))
        }

        assertEquals(DocumentContentUnavailableException.DEFAULT_MESSAGE, failure.message)
        assertSame(storageFailure, failure.cause)
        assertEquals(StorageObjectLocation("local", "tenant-1/document-1.txt"), storage.requested.single())
        assertEquals("document:download", audits.records.single().action)
        assertEquals("version-1", audits.records.single().details["versionId"])
    }

    @Test
    fun `closes a storage stream whose reported length conflicts with persisted metadata`() {
        val content = CloseTrackingInputStream("document content".encodeToByteArray())
        val storage = RecordingStorage(downloadContent = content, reportedContentLength = 15L)
        val audits = RecordingAudits()
        val service = service(storage = storage, audits = audits)

        val failure = assertThrows<DocumentContentUnavailableException> {
            service.download(Identifier("document-1"))
        }

        assertEquals(DocumentContentUnavailableException.DEFAULT_MESSAGE, failure.message)
        assertNull(failure.cause)
        assertTrue(content.closed)
        assertEquals("document:download", audits.records.single().action)
    }

    @Test
    fun `preserves the expected length without claiming verification when storage reports no length`() {
        val storage = RecordingStorage(reportedContentLength = null)
        val service = service(storage = storage)

        val download = service.download(Identifier("document-1"))

        assertEquals(16L, download.expectedContentLength)
        assertNull(download.verifiedContentLength)
        assertEquals(16L, download.contentLength)
        assertEquals("document content", download.use { it.content.readBytes() }.decodeToString())
    }

    @Test
    fun `rejects a denied download before it reaches storage`() {
        val storage = RecordingStorage()
        val service = service(storage = storage, authorized = false)

        assertThrows<SecurityException> { service.download(Identifier("document-1")) }

        assertEquals(emptyList(), storage.requested)
    }

    @Test
    fun `does not resolve a document outside the current tenant`() {
        val storage = RecordingStorage()
        val service = service(storage = storage, documents = RecordingDocuments(emptyMap()))

        assertThrows<DocumentNotFoundException> { service.download(Identifier("document-1")) }

        assertEquals(emptyList(), storage.requested)
    }

    @Test
    fun `keeps missing versions and file records as not found failures without opening storage`() {
        val versionStorage = RecordingStorage()
        val versionService = service(storage = versionStorage)

        assertThrows<DocumentDownloadNotFoundException> {
            versionService.download(Identifier("document-1"), Identifier("version-other"))
        }
        assertEquals(emptyList(), versionStorage.requested)

        val fileStorage = RecordingStorage()
        val fileService = service(
            storage = fileStorage,
            files = object : FileObjectRepository {
                override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? = null
                override fun save(fileObject: FileObject) = Unit
            },
        )

        assertThrows<DocumentDownloadNotFoundException> {
            fileService.download(Identifier("document-1"))
        }
        assertEquals(emptyList(), fileStorage.requested)
    }

    @Test
    fun `snapshots the user realm outside the download persistence transaction`() {
        val transaction = TrackingTransaction()
        val audits = RecordingAudits()
        val service = service(
            storage = RecordingStorage(),
            audits = audits,
            transaction = transaction,
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser(): UserIdentity {
                    check(!transaction.active) { "User realm must not be called in the download transaction." }
                    return UserIdentity(Identifier("user-1"), "Alice")
                }

                override fun findUser(userId: Identifier): UserIdentity? = null
            },
        )

        service.download(Identifier("document-1")).close()

        assertEquals("user-1", audits.records.single().operatorId?.value)
        assertEquals("Alice", audits.records.single().operatorName)
    }

    @Test
    fun `exposes conventional Java constructors for content unavailable failures`() {
        val type = DocumentContentUnavailableException::class.java

        type.getConstructor()
        type.getConstructor(String::class.java)
        type.getConstructor(String::class.java, Throwable::class.java)
        type.getConstructor(Throwable::class.java)
    }

    private fun service(
        storage: RecordingStorage,
        authorized: Boolean = true,
        documents: RecordingDocuments = RecordingDocuments(mapOf(Identifier("document-1") to document())),
        files: FileObjectRepository = RecordingFiles(),
        audits: RecordingAudits? = null,
        transaction: ApplicationTransaction = DirectTransaction,
        userRealmProvider: UserRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-1"), "Alice")
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
    ): DocumentDownloadService = DocumentDownloadService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
        },
        userRealmProvider = userRealmProvider,
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(authorized)
        },
        documentRepository = documents,
        fileObjectRepository = files,
        storageAdapter = storage,
        transaction = transaction,
        auditTrail = audits?.let { repository ->
            AuditTrail(repository, object : IdentifierGenerator { override fun nextId() = Identifier("audit-1") }, CLOCK)
        },
    )

    private fun document() = Document(
        id = Identifier("document-1"), tenantId = Identifier("tenant-1"), assetId = Identifier("asset-1"),
        documentNumber = "DOC-1", title = "Document", versions = listOf(
            DocumentVersion(Identifier("version-1"), Identifier("tenant-1"), Identifier("document-1"), "1.0", Identifier("file-1")),
        ), currentVersionId = Identifier("version-1"),
    )

    private class RecordingDocuments(private val documents: Map<Identifier, Document>) : DocumentRepository {
        override fun findById(tenantId: Identifier, documentId: Identifier): Document? = documents[documentId]
            ?.takeIf { it.tenantId == tenantId }
        override fun save(document: Document) = Unit
    }

    private class RecordingFiles : FileObjectRepository {
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? =
            FileObject(fileObjectId, tenantId, "document-1.txt", 16, "local", "tenant-1/document-1.txt", "text/plain")
        override fun save(fileObject: FileObject) = Unit
    }

    private class RecordingStorage(
        private val downloadContent: InputStream = ByteArrayInputStream("document content".encodeToByteArray()),
        private val reportedContentLength: Long? = 16,
        private val openFailure: Exception? = null,
    ) : StorageAdapter {
        val requested = mutableListOf<StorageObjectLocation>()
        override fun download(location: StorageObjectLocation): StorageDownload {
            requested += location
            openFailure?.let { throw it }
            return StorageDownload(downloadContent, reportedContentLength, "text/plain")
        }
        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject = throw UnsupportedOperationException()
        override fun delete(location: StorageObjectLocation) = Unit
        override fun exists(location: StorageObjectLocation): Boolean = true
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = URI.create("http://example.invalid")
        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = throw UnsupportedOperationException()
        override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart = throw UnsupportedOperationException()
        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject = throw UnsupportedOperationException()
        override fun abortMultipartUpload(upload: MultipartUpload) = Unit
    }

    private class CloseTrackingInputStream(content: ByteArray) : ByteArrayInputStream(content) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class RecordingAudits : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        override fun append(record: AuditRecord) { records += record }
        override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> = emptyList()
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private class TrackingTransaction : ApplicationTransaction {
        var active = false
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested transaction is not expected in this fixture." }
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
    }
}
