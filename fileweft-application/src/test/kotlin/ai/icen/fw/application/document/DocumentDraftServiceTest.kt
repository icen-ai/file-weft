package ai.icen.fw.application.document

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.upload.StoredObjectIntegrityException
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.audit.AuditRecord
import ai.icen.fw.domain.audit.AuditRecordRepository
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentNotEditableException
import ai.icen.fw.domain.document.DocumentNumberAlreadyExistsException
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.document.DocumentVersionAlreadyExistsException
import ai.icen.fw.domain.document.LifecycleCommand
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.catalog.DocumentCatalogBinding
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertTrue

class DocumentDraftServiceTest {
    @Test
    fun `creates a draft with an initial version and audit evidence`() {
        val documents = RecordingDocuments()
        val fileObjects = RecordingFileObjects()
        val assets = RecordingAssets()
        val audits = RecordingAudits()
        val service = service(
            documents = documents,
            fileObjects = fileObjects,
            assets = assets,
            identifiers = listOf("document-1", "file-1", "asset-1", "version-1", "audit-1"),
            auditTrail = auditTrail(audits, listOf("audit-1")),
        )

        val document = service.create(createCommand(), ByteArrayInputStream("content".toByteArray()))

        assertEquals("document-1", document.id.value)
        assertEquals("asset-1", document.assetId.value)
        assertEquals("version-1", document.currentVersionId?.value)
        assertEquals("file-1", document.versions.single().fileObjectId.value)
        assertEquals(document, documents.saved.single())
        assertEquals("file-1", fileObjects.saved.single().id.value)
        assertEquals("asset-1", assets.saved.single().id.value)
        assertEquals(DocumentDraftService.CREATE_ACTION, audits.records.single().action)
        assertEquals("user-1", audits.records.single().operatorId?.value)
        assertEquals("测试编辑者", audits.records.single().operatorName)
    }

    @Test
    fun `snapshots create metadata before an untrusted storage adapter can mutate it`() {
        val storage = RecordingStorage().apply { attemptMetadataMutation = true }
        val assets = RecordingAssets()
        val audits = RecordingAudits()
        val service = service(
            storage = storage,
            assets = assets,
            identifiers = listOf("document-1", "file-1", "asset-1", "version-1"),
            auditTrail = auditTrail(audits, listOf("audit-1")),
        )
        val metadata = linkedMapOf(
            "source" to "host",
            DocumentCatalogBinding.METADATA_KEY to "inbox",
        )

        service.create(createCommand().copy(metadata = metadata), ByteArrayInputStream("content".toByteArray()))

        assertTrue(storage.metadataMutationRejected)
        assertEquals("host", assets.saved.single().metadata["source"])
        assertEquals("inbox", assets.saved.single().metadata[DocumentCatalogBinding.METADATA_KEY])
        assertEquals("inbox", audits.records.single().details["folderId"])
    }

    @Test
    fun `compensates storage and records failure when draft persistence fails`() {
        val storage = RecordingStorage()
        val metrics = RecordingMetrics()
        val service = service(
            storage = storage,
            documents = FailingDocuments,
            identifiers = listOf("document-1", "file-1", "asset-1", "version-1"),
            metrics = metrics,
        )

        assertThrows(IllegalStateException::class.java) {
            service.create(createCommand(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals(listOf(storage.location), storage.deleted)
        assertEquals(listOf(FileWeftMetric.UPLOAD_FAILURE), metrics.recorded)
    }

    @Test
    fun `compensates storage and skips document persistence when storage reports a different length`() {
        val storage = RecordingStorage().apply { storedContentLength = 6 }
        val documents = RecordingDocuments()
        val fileObjects = RecordingFileObjects()
        val service = service(
            storage = storage,
            documents = documents,
            fileObjects = fileObjects,
            identifiers = listOf("document-1", "file-1", "asset-1", "version-1"),
        )

        assertThrows(StoredObjectIntegrityException::class.java) {
            service.create(createCommand(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals(listOf(storage.location), storage.deleted)
        assertTrue(documents.saved.isEmpty())
        assertTrue(fileObjects.saved.isEmpty())
    }

    @Test
    fun `rejects a duplicate document number before uploading content`() {
        val storage = RecordingStorage()
        val service = service(
            storage = storage,
            documents = RecordingDocuments(draftDocument()),
            identifiers = listOf("document-2"),
        )

        assertThrows(DocumentNumberAlreadyExistsException::class.java) {
            service.create(createCommand(), ByteArrayInputStream("content".toByteArray()))
        }

        assertTrue(storage.uploads.isEmpty())
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun `adds a new version through the document lifecycle`() {
        val existing = draftDocument()
        val documents = RecordingDocuments(existing)
        val fileObjects = RecordingFileObjects()
        val service = service(
            documents = documents,
            fileObjects = fileObjects,
            identifiers = listOf("file-2", "version-2"),
        )

        val updated = service.addVersion(
            existing.id,
            AddDocumentVersionCommand("1.1", "revised.txt", 7, "text/plain"),
            ByteArrayInputStream("content".toByteArray()),
        )

        assertEquals("version-2", updated.currentVersionId?.value)
        assertEquals(listOf("1.0", "1.1"), updated.versions.map { it.versionNumber })
        assertEquals("file-2", updated.versions.last().fileObjectId.value)
        assertEquals("file-2", fileObjects.saved.single().id.value)
    }

    @Test
    fun `snapshots version metadata before an untrusted storage adapter can mutate it`() {
        val existing = draftDocument()
        val storage = RecordingStorage().apply { attemptMetadataMutation = true }
        val service = service(
            storage = storage,
            documents = RecordingDocuments(existing),
            identifiers = listOf("file-2", "version-2"),
        )

        service.addVersion(
            existing.id,
            AddDocumentVersionCommand("1.1", "revised.txt", 7, "text/plain", metadata = mapOf("source" to "host")),
            ByteArrayInputStream("content".toByteArray()),
        )

        assertTrue(storage.metadataMutationRejected)
        assertEquals("host", storage.uploads.single().metadata["source"])
    }

    @Test
    fun `compensates an added version when storage reports a different length`() {
        val existing = draftDocument()
        val storage = RecordingStorage().apply { storedContentLength = 6 }
        val documents = RecordingDocuments(existing)
        val fileObjects = RecordingFileObjects()
        val service = service(
            storage = storage,
            documents = documents,
            fileObjects = fileObjects,
            identifiers = listOf("file-2", "version-2"),
        )

        assertThrows(StoredObjectIntegrityException::class.java) {
            service.addVersion(
                existing.id,
                AddDocumentVersionCommand("1.1", "revised.txt", 7, "text/plain"),
                ByteArrayInputStream("content".toByteArray()),
            )
        }

        assertEquals(listOf(storage.location), storage.deleted)
        assertTrue(fileObjects.saved.isEmpty())
        assertTrue(documents.saved.isEmpty())
        assertEquals(listOf("1.0"), existing.versions.map { it.versionNumber })
    }

    @Test
    fun `compensates an uploaded version when its business version number conflicts`() {
        val existing = draftDocument()
        val storage = RecordingStorage()
        val documents = RecordingDocuments(existing)
        val fileObjects = RecordingFileObjects()
        val service = service(
            storage = storage,
            documents = documents,
            fileObjects = fileObjects,
            identifiers = listOf("file-2", "version-2"),
        )

        val failure = assertThrows(DocumentVersionAlreadyExistsException::class.java) {
            service.addVersion(
                existing.id,
                AddDocumentVersionCommand("1.0", "duplicate.txt", 7, "text/plain"),
                ByteArrayInputStream("content".toByteArray()),
            )
        }

        assertEquals("1.0", failure.versionNumber)
        assertEquals(listOf(storage.location), storage.deleted)
        assertTrue(fileObjects.saved.isEmpty())
        assertTrue(documents.saved.isEmpty())
        assertEquals(listOf("1.0"), existing.versions.map { it.versionNumber })
    }

    @Test
    fun `checks document action before a write is sent to storage`() {
        val storage = RecordingStorage()
        val service = service(
            storage = storage,
            authorization = { AuthorizationDecision(false, "editor role is required") },
            identifiers = listOf("document-1", "file-1", "asset-1", "version-1"),
        )

        assertThrows(SecurityException::class.java) {
            service.create(createCommand(), ByteArrayInputStream("content".toByteArray()))
        }

        assertTrue(storage.uploads.isEmpty())
    }

    @Test
    fun `does not let metrics failures change a committed draft`() {
        val documents = RecordingDocuments()
        val service = service(
            documents = documents,
            identifiers = listOf("document-1", "file-1", "asset-1", "version-1"),
            metrics = ThrowingMetrics,
        )

        val created = service.create(createCommand(), ByteArrayInputStream("content".toByteArray()))

        assertEquals("document-1", created.id.value)
        assertEquals(created, documents.saved.single())
    }

    @Test
    fun `renames a draft through the document lifecycle`() {
        val existing = draftDocument()
        val documents = RecordingDocuments(existing)
        val service = service(documents = documents, identifiers = emptyList())

        val renamed = service.rename(existing.id, "Renamed contract")

        assertEquals("Renamed contract", renamed.title)
        assertEquals("Renamed contract", documents.saved.single().title)
    }

    @Test
    fun `returns an explicit conflict when renaming a non-editable document`() {
        val existing = draftDocument().also { it.transition(LifecycleCommand.SUBMIT) }
        val documents = RecordingDocuments(existing)
        val service = service(documents = documents, identifiers = emptyList())

        val failure = assertThrows(DocumentNotEditableException::class.java) {
            service.rename(existing.id, "Renamed contract")
        }

        assertEquals(existing.lifecycleState, failure.currentState)
        assertEquals("Contract", existing.title)
        assertTrue(documents.saved.isEmpty())
    }

    private fun service(
        storage: RecordingStorage = RecordingStorage(),
        documents: DocumentRepository = RecordingDocuments(),
        fileObjects: RecordingFileObjects = RecordingFileObjects(),
        assets: RecordingAssets = RecordingAssets(),
        identifiers: List<String>,
        transaction: ApplicationTransaction = DirectTransaction,
        auditTrail: AuditTrail? = null,
        metrics: FileWeftMetrics? = null,
        authorization: (AuthorizationRequest) -> AuthorizationDecision = { AuthorizationDecision(true) },
    ): DocumentDraftService = DocumentDraftService(
        tenantProvider = object : TenantProvider { override fun currentTenant() = TenantContext(Identifier("tenant-1")) },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser() = UserIdentity(Identifier("user-1"), "测试编辑者")
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = authorization(request)
        },
        storageAdapter = storage,
        documentRepository = documents,
        fileObjectRepository = fileObjects,
        fileAssetRepository = assets,
        identifierGenerator = SequentialIdentifiers(identifiers),
        transaction = transaction,
        auditTrail = auditTrail,
        metrics = metrics,
    )

    private fun auditTrail(repository: RecordingAudits, identifiers: List<String>): AuditTrail = AuditTrail(
        repository,
        SequentialIdentifiers(identifiers),
        Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC),
    )

    private fun createCommand() = CreateDocumentDraftCommand(
        documentNumber = "DOC-001",
        title = "Contract",
        fileName = "contract.txt",
        contentLength = 7,
        contentType = "text/plain",
    )

    private fun draftDocument() = Document(
        Identifier("document-1"), Identifier("tenant-1"), Identifier("asset-1"), "DOC-001", "Contract",
        versions = listOf(DocumentVersion(Identifier("version-1"), Identifier("tenant-1"), Identifier("document-1"), "1.0", Identifier("file-1"))),
        currentVersionId = Identifier("version-1"),
    )

    private class SequentialIdentifiers(values: List<String>) : IdentifierGenerator {
        private val values = ArrayDeque(values)
        override fun nextId(): Identifier = Identifier(values.removeFirst())
    }

    private class RecordingDocuments(initial: Document? = null) : DocumentRepository {
        private var document: Document? = initial
        val saved = mutableListOf<Document>()
        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            document?.takeIf { it.tenantId == tenantId && it.id == documentId }
        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? =
            findById(tenantId, documentId)
        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? =
            document?.takeIf { it.tenantId == tenantId && it.documentNumber == documentNumber }
        override fun save(document: Document) {
            this.document = document
            saved += document
        }
    }

    private object FailingDocuments : DocumentRepository {
        override fun findById(tenantId: Identifier, documentId: Identifier): Document? = null
        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? = null
        override fun save(document: Document): Nothing = throw IllegalStateException("database failed")
    }

    private class RecordingFileObjects : FileObjectRepository {
        val saved = mutableListOf<FileObject>()
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? = null
        override fun save(fileObject: FileObject) { saved += fileObject }
    }

    private class RecordingAssets : FileAssetRepository {
        val saved = mutableListOf<FileAsset>()
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? = null
        override fun save(fileAsset: FileAsset) { saved += fileAsset }
    }

    private class RecordingAudits : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        override fun append(record: AuditRecord) { records += record }
        override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> = emptyList()
    }

    private class RecordingStorage : StorageAdapter {
        val location = StorageObjectLocation("memory", "objects/tenant/file")
        val uploads = mutableListOf<StorageUploadRequest>()
        val deleted = mutableListOf<StorageObjectLocation>()
        var storedContentLength: Long? = null
        var attemptMetadataMutation: Boolean = false
        var metadataMutationRejected: Boolean = false
        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
            uploads += request
            if (attemptMetadataMutation) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    (request.metadata as MutableMap<String, String>)["source"] = "tampered"
                } catch (_: UnsupportedOperationException) {
                    metadataMutationRejected = true
                } catch (_: ClassCastException) {
                    metadataMutationRejected = true
                }
            }
            return StoredObject(location, storedContentLength ?: request.contentLength, request.contentType, "sha256:test")
        }
        override fun delete(location: StorageObjectLocation) { deleted += location }
        override fun download(location: StorageObjectLocation): StorageDownload = throw UnsupportedOperationException()
        override fun exists(location: StorageObjectLocation): Boolean = true
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = URI("http://localhost/file")
        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = throw UnsupportedOperationException()
        override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart = throw UnsupportedOperationException()
        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject = throw UnsupportedOperationException()
        override fun abortMultipartUpload(upload: MultipartUpload) = Unit
    }

    private class RecordingMetrics : FileWeftMetrics {
        val recorded = mutableListOf<FileWeftMetric>()
        override fun increment(metric: FileWeftMetric, tags: Map<String, String>) { recorded += metric }
    }

    private object ThrowingMetrics : FileWeftMetrics {
        override fun increment(metric: FileWeftMetric, tags: Map<String, String>) = throw IllegalStateException("metrics unavailable")
    }

    private object DirectTransaction : ApplicationTransaction { override fun <T> execute(action: () -> T): T = action() }
}
