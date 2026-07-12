package ai.icen.fw.application.document

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionNestingException
import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.application.transaction.ApplicationTransactionState
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
import ai.icen.fw.domain.document.LifecycleState
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
    fun `rejects create and add-version inside an active transaction before all side effects`() {
        val storage = RecordingStorage()
        val documents = RecordingDocuments()
        val fileObjects = RecordingFileObjects()
        val assets = RecordingAssets()
        val identifiers = CountingIdentifiers()
        val service = service(
            storage = storage,
            documents = documents,
            fileObjects = fileObjects,
            assets = assets,
            identifiers = emptyList(),
            identifierGenerator = identifiers,
            transaction = ActiveTransaction,
        )

        val createFailure = assertThrows(ApplicationTransactionNestingException::class.java) {
            service.create(createCommand(), ByteArrayInputStream("content".toByteArray()))
        }
        val addVersionFailure = assertThrows(ApplicationTransactionNestingException::class.java) {
            service.addVersion(
                Identifier("document-1"),
                AddDocumentVersionCommand("1.1", "revision.txt", 7, "text/plain"),
                ByteArrayInputStream("content".toByteArray()),
            )
        }

        assertEquals(ApplicationTransactionNestingException.DEFAULT_MESSAGE, createFailure.message)
        assertEquals(ApplicationTransactionNestingException.DEFAULT_MESSAGE, addVersionFailure.message)
        assertEquals(0, identifiers.calls)
        assertEquals(0, documents.accesses)
        assertTrue(storage.uploads.isEmpty())
        assertTrue(storage.deleted.isEmpty())
        assertTrue(fileObjects.saved.isEmpty())
        assertTrue(assets.saved.isEmpty())
    }

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
            transaction = SimulatedTransaction(writeCall = 2, outcome = WriteOutcome.KNOWN_FAILURE),
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
    fun `returns a concurrently advanced committed draft when commit acknowledgement is lost`() {
        val storage = RecordingStorage()
        val documents = RecordingDocuments()
        val fileObjects = RecordingFileObjects()
        val assets = RecordingAssets()
        val metrics = RecordingMetrics()
        val service = service(
            storage = storage,
            documents = documents,
            fileObjects = fileObjects,
            assets = assets,
            identifiers = listOf("document-1", "file-1", "asset-1", "version-1"),
            transaction = SimulatedTransaction(
                writeCall = 2,
                outcome = WriteOutcome.UNKNOWN_AFTER_ACTION,
                afterActionBeforeFailure = {
                    val committed = checkNotNull(documents.current)
                    committed.rename("Advanced after commit")
                    committed.addVersion(
                        DocumentVersion(
                            Identifier("version-2"),
                            Identifier("tenant-1"),
                            Identifier("document-1"),
                            "1.1",
                            Identifier("file-2"),
                        ),
                    )
                    committed.transition(LifecycleCommand.SUBMIT)
                },
            ),
            metrics = metrics,
        )

        val created = service.create(createCommand(), ByteArrayInputStream("content".toByteArray()))

        assertEquals("document-1", created.id.value)
        assertEquals(documents.current, created)
        assertEquals("Advanced after commit", created.title)
        assertEquals("version-2", created.currentVersionId?.value)
        assertEquals(LifecycleState.PENDING_REVIEW, created.lifecycleState)
        assertEquals("file-1", fileObjects.current?.id?.value)
        assertEquals("asset-1", assets.current?.id?.value)
        assertTrue(storage.deleted.isEmpty())
        assertEquals(listOf(FileWeftMetric.UPLOAD_COUNT), metrics.recorded)
    }

    @Test
    fun `does not report a created draft when audit append fails after all core rows are visible`() {
        val storage = RecordingStorage()
        val documents = RecordingDocuments()
        val fileObjects = RecordingFileObjects()
        val assets = RecordingAssets()
        val audits = FailingAudits()
        val metrics = RecordingMetrics()
        val service = service(
            storage = storage,
            documents = documents,
            fileObjects = fileObjects,
            assets = assets,
            identifiers = listOf("document-1", "file-1", "asset-1", "version-1"),
            auditTrail = auditTrail(audits, listOf("audit-1")),
            metrics = metrics,
        )

        val failure = assertThrows(ApplicationTransactionOutcomeUnknownException::class.java) {
            service.create(createCommand(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals("audit append failed", failure.cause?.message)
        assertEquals("document-1", documents.current?.id?.value)
        assertEquals("file-1", fileObjects.current?.id?.value)
        assertEquals("asset-1", assets.current?.id?.value)
        assertEquals(DocumentDraftService.CREATE_ACTION, audits.attempted?.action)
        assertTrue(storage.deleted.isEmpty())
        assertEquals(listOf(FileWeftMetric.UPLOAD_FAILURE), metrics.recorded)
    }

    @Test
    fun `retains storage when draft transaction outcome is unknown without a visible commit`() {
        val storage = RecordingStorage()
        val fileObjects = RecordingFileObjects()
        val assets = RecordingAssets()
        val metrics = RecordingMetrics()
        val service = service(
            storage = storage,
            fileObjects = fileObjects,
            assets = assets,
            identifiers = listOf("document-1", "file-1", "asset-1", "version-1"),
            transaction = SimulatedTransaction(writeCall = 2, outcome = WriteOutcome.UNKNOWN_BEFORE_ACTION),
            metrics = metrics,
        )

        val failure = assertThrows(ApplicationTransactionOutcomeUnknownException::class.java) {
            service.create(createCommand(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals(ApplicationTransactionOutcomeUnknownException.DEFAULT_MESSAGE, failure.message)
        assertEquals(null, fileObjects.current)
        assertEquals(null, assets.current)
        assertTrue(storage.deleted.isEmpty())
        assertEquals(listOf(FileWeftMetric.UPLOAD_FAILURE), metrics.recorded)
    }

    @Test
    fun `retains storage when draft reconciliation cannot read persistence`() {
        val storage = RecordingStorage()
        val reconciliationFailure = IllegalStateException("reconciliation unavailable")
        val service = service(
            storage = storage,
            identifiers = listOf("document-1", "file-1", "asset-1", "version-1"),
            transaction = SimulatedTransaction(
                writeCall = 2,
                outcome = WriteOutcome.KNOWN_FAILURE,
                reconciliationFailure = reconciliationFailure,
            ),
        )

        val failure = assertThrows(ApplicationTransactionOutcomeUnknownException::class.java) {
            service.create(createCommand(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals("persistence failed", failure.cause?.message)
        assertTrue(failure.suppressed.contains(reconciliationFailure))
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun `retains storage when failed draft persistence exposes a partial aggregate`() {
        val storage = RecordingStorage()
        val fileObjects = RecordingFileObjects()
        val assets = RecordingAssets()
        val service = service(
            storage = storage,
            documents = FailingDocuments,
            fileObjects = fileObjects,
            assets = assets,
            identifiers = listOf("document-1", "file-1", "asset-1", "version-1"),
        )

        val failure = assertThrows(ApplicationTransactionOutcomeUnknownException::class.java) {
            service.create(createCommand(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals("database failed", failure.cause?.message)
        assertEquals("file-1", fileObjects.current?.id?.value)
        assertEquals("asset-1", assets.current?.id?.value)
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun `retains storage when draft reconciliation finds conflicting generated ids`() {
        val storage = RecordingStorage()
        val conflicting = FileObject(
            Identifier("file-1"),
            Identifier("tenant-1"),
            "other.txt",
            5,
            "memory",
            "objects/tenant/other",
        )
        val service = service(
            storage = storage,
            fileObjects = RecordingFileObjects(conflicting),
            identifiers = listOf("document-1", "file-1", "asset-1", "version-1"),
            transaction = SimulatedTransaction(writeCall = 2, outcome = WriteOutcome.KNOWN_FAILURE),
        )

        val failure = assertThrows(ApplicationTransactionOutcomeUnknownException::class.java) {
            service.create(createCommand(), ByteArrayInputStream("content".toByteArray()))
        }

        assertEquals("persistence failed", failure.cause?.message)
        assertTrue(storage.deleted.isEmpty())
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
    fun `returns a concurrently advanced committed version when commit acknowledgement is lost`() {
        val existing = draftDocument()
        val storage = RecordingStorage()
        val documents = RecordingDocuments(existing)
        val fileObjects = RecordingFileObjects()
        val metrics = RecordingMetrics()
        val service = service(
            storage = storage,
            documents = documents,
            fileObjects = fileObjects,
            identifiers = listOf("file-2", "version-2"),
            transaction = SimulatedTransaction(
                writeCall = 1,
                outcome = WriteOutcome.UNKNOWN_AFTER_ACTION,
                afterActionBeforeFailure = {
                    val committed = checkNotNull(documents.current)
                    committed.rename("Advanced version draft")
                    committed.addVersion(
                        DocumentVersion(
                            Identifier("version-3"),
                            Identifier("tenant-1"),
                            Identifier("document-1"),
                            "1.2",
                            Identifier("file-3"),
                        ),
                    )
                    committed.transition(LifecycleCommand.SUBMIT)
                },
            ),
            metrics = metrics,
        )

        val updated = service.addVersion(
            existing.id,
            AddDocumentVersionCommand("1.1", "revised.txt", 7, "text/plain"),
            ByteArrayInputStream("content".toByteArray()),
        )

        assertEquals("version-3", updated.currentVersionId?.value)
        assertEquals(listOf("1.0", "1.1", "1.2"), updated.versions.map { it.versionNumber })
        assertEquals("Advanced version draft", updated.title)
        assertEquals(LifecycleState.PENDING_REVIEW, updated.lifecycleState)
        assertEquals("file-2", fileObjects.current?.id?.value)
        assertTrue(storage.deleted.isEmpty())
        assertEquals(listOf(FileWeftMetric.UPLOAD_COUNT), metrics.recorded)
    }

    @Test
    fun `does not report an added version when a live document is mutated before save fails`() {
        val existing = draftDocument()
        val storage = RecordingStorage()
        val documents = FailingSaveDocuments(existing)
        val fileObjects = RecordingFileObjects()
        val service = service(
            storage = storage,
            documents = documents,
            fileObjects = fileObjects,
            identifiers = listOf("file-2", "version-2"),
        )

        val failure = assertThrows(ApplicationTransactionOutcomeUnknownException::class.java) {
            service.addVersion(
                existing.id,
                AddDocumentVersionCommand("1.1", "revised.txt", 7, "text/plain"),
                ByteArrayInputStream("content".toByteArray()),
            )
        }

        assertEquals("document save failed", failure.cause?.message)
        assertEquals(listOf("1.0", "1.1"), existing.versions.map { it.versionNumber })
        assertEquals("file-2", fileObjects.current?.id?.value)
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun `does not report an added version when audit append fails after document save`() {
        val existing = draftDocument()
        val storage = RecordingStorage()
        val documents = RecordingDocuments(existing)
        val fileObjects = RecordingFileObjects()
        val audits = FailingAudits()
        val metrics = RecordingMetrics()
        val service = service(
            storage = storage,
            documents = documents,
            fileObjects = fileObjects,
            identifiers = listOf("file-2", "version-2"),
            auditTrail = auditTrail(audits, listOf("audit-1")),
            metrics = metrics,
        )

        val failure = assertThrows(ApplicationTransactionOutcomeUnknownException::class.java) {
            service.addVersion(
                existing.id,
                AddDocumentVersionCommand("1.1", "revised.txt", 7, "text/plain"),
                ByteArrayInputStream("content".toByteArray()),
            )
        }

        assertEquals("audit append failed", failure.cause?.message)
        assertEquals(listOf("1.0", "1.1"), documents.current?.versions?.map { it.versionNumber })
        assertEquals("file-2", fileObjects.current?.id?.value)
        assertEquals(DocumentDraftService.ADD_VERSION_ACTION, audits.attempted?.action)
        assertTrue(storage.deleted.isEmpty())
        assertEquals(listOf(FileWeftMetric.UPLOAD_FAILURE), metrics.recorded)
    }

    @Test
    fun `retains storage when added version transaction outcome is unknown without a visible commit`() {
        val existing = draftDocument()
        val storage = RecordingStorage()
        val fileObjects = RecordingFileObjects()
        val metrics = RecordingMetrics()
        val service = service(
            storage = storage,
            documents = RecordingDocuments(existing),
            fileObjects = fileObjects,
            identifiers = listOf("file-2", "version-2"),
            transaction = SimulatedTransaction(writeCall = 1, outcome = WriteOutcome.UNKNOWN_BEFORE_ACTION),
            metrics = metrics,
        )

        val failure = assertThrows(ApplicationTransactionOutcomeUnknownException::class.java) {
            service.addVersion(
                existing.id,
                AddDocumentVersionCommand("1.1", "revised.txt", 7, "text/plain"),
                ByteArrayInputStream("content".toByteArray()),
            )
        }

        assertEquals(ApplicationTransactionOutcomeUnknownException.DEFAULT_MESSAGE, failure.message)
        assertEquals(null, fileObjects.current)
        assertEquals(listOf("1.0"), existing.versions.map { it.versionNumber })
        assertTrue(storage.deleted.isEmpty())
        assertEquals(listOf(FileWeftMetric.UPLOAD_FAILURE), metrics.recorded)
    }

    @Test
    fun `retains storage when an existing version conflicts with the generated version binding`() {
        val existing = draftDocument().also { document ->
            document.addVersion(
                DocumentVersion(
                    Identifier("version-2"),
                    Identifier("tenant-1"),
                    Identifier("document-1"),
                    "conflicting",
                    Identifier("other-file"),
                ),
            )
        }
        val storage = RecordingStorage()
        val service = service(
            storage = storage,
            documents = RecordingDocuments(existing),
            identifiers = listOf("file-2", "version-2"),
            transaction = SimulatedTransaction(writeCall = 1, outcome = WriteOutcome.KNOWN_FAILURE),
        )

        val failure = assertThrows(ApplicationTransactionOutcomeUnknownException::class.java) {
            service.addVersion(
                existing.id,
                AddDocumentVersionCommand("1.1", "revised.txt", 7, "text/plain"),
                ByteArrayInputStream("content".toByteArray()),
            )
        }

        assertEquals("persistence failed", failure.cause?.message)
        assertTrue(storage.deleted.isEmpty())
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
        fileObjects: FileObjectRepository = RecordingFileObjects(),
        assets: FileAssetRepository = RecordingAssets(),
        identifiers: List<String>,
        transaction: ApplicationTransaction = DirectTransaction,
        auditTrail: AuditTrail? = null,
        metrics: FileWeftMetrics? = null,
        authorization: (AuthorizationRequest) -> AuthorizationDecision = { AuthorizationDecision(true) },
        identifierGenerator: IdentifierGenerator = SequentialIdentifiers(identifiers),
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
        identifierGenerator = identifierGenerator,
        transaction = transaction,
        auditTrail = auditTrail,
        metrics = metrics,
    )

    private fun auditTrail(repository: AuditRecordRepository, identifiers: List<String>): AuditTrail = AuditTrail(
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

    private class CountingIdentifiers : IdentifierGenerator {
        var calls: Int = 0
            private set
        override fun nextId(): Identifier {
            calls++
            return Identifier("unexpected-$calls")
        }
    }

    private class RecordingDocuments(initial: Document? = null) : DocumentRepository {
        private var document: Document? = initial
        var accesses: Int = 0
            private set
        val current: Document?
            get() = document
        val saved = mutableListOf<Document>()
        override fun findById(tenantId: Identifier, documentId: Identifier): Document? {
            accesses++
            return document?.takeIf { it.tenantId == tenantId && it.id == documentId }
        }
        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            accesses++
            return document?.takeIf { it.tenantId == tenantId && it.id == documentId }
        }
        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? {
            accesses++
            return document?.takeIf { it.tenantId == tenantId && it.documentNumber == documentNumber }
        }
        override fun save(document: Document) {
            accesses++
            this.document = document
            saved += document
        }
    }

    private object FailingDocuments : DocumentRepository {
        override fun findById(tenantId: Identifier, documentId: Identifier): Document? = null
        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? = null
        override fun save(document: Document): Nothing = throw IllegalStateException("database failed")
    }

    private class FailingSaveDocuments(private val document: Document) : DocumentRepository {
        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            document.takeIf { it.tenantId == tenantId && it.id == documentId }
        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? =
            findById(tenantId, documentId)
        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? =
            document.takeIf { it.tenantId == tenantId && it.documentNumber == documentNumber }
        override fun save(document: Document): Nothing = throw IllegalStateException("document save failed")
    }

    private class RecordingFileObjects(initial: FileObject? = null) : FileObjectRepository {
        var current: FileObject? = initial
            private set
        val saved = mutableListOf<FileObject>()
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? =
            current?.takeIf { it.tenantId == tenantId && it.id == fileObjectId }
        override fun save(fileObject: FileObject) {
            current = fileObject
            saved += fileObject
        }
    }

    private class RecordingAssets(initial: FileAsset? = null) : FileAssetRepository {
        var current: FileAsset? = initial
            private set
        val saved = mutableListOf<FileAsset>()
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
            current?.takeIf { it.tenantId == tenantId && it.id == fileAssetId }
        override fun save(fileAsset: FileAsset) {
            current = fileAsset
            saved += fileAsset
        }
    }

    private class RecordingAudits : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        override fun append(record: AuditRecord) { records += record }
        override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> = emptyList()
    }

    private class FailingAudits : AuditRecordRepository {
        var attempted: AuditRecord? = null
            private set
        override fun append(record: AuditRecord): Nothing {
            attempted = record
            throw IllegalStateException("audit append failed")
        }
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

    private object ActiveTransaction : ApplicationTransaction, ApplicationTransactionState {
        override fun <T> execute(action: () -> T): T = error("active transaction guard was bypassed")
        override fun isTransactionActive(): Boolean = true
    }

    private enum class WriteOutcome { KNOWN_FAILURE, UNKNOWN_BEFORE_ACTION, UNKNOWN_AFTER_ACTION }

    private class SimulatedTransaction(
        private val writeCall: Int,
        private val outcome: WriteOutcome,
        private val reconciliationFailure: Throwable? = null,
        private val afterActionBeforeFailure: (() -> Unit)? = null,
    ) : ApplicationTransaction {
        private var calls: Int = 0

        override fun <T> execute(action: () -> T): T {
            calls++
            if (calls == writeCall) {
                val writeFailure = IllegalStateException("persistence failed")
                return when (outcome) {
                    WriteOutcome.KNOWN_FAILURE -> throw writeFailure
                    WriteOutcome.UNKNOWN_BEFORE_ACTION ->
                        throw ApplicationTransactionOutcomeUnknownException(writeFailure)
                    WriteOutcome.UNKNOWN_AFTER_ACTION -> {
                        action()
                        afterActionBeforeFailure?.invoke()
                        throw ApplicationTransactionOutcomeUnknownException(writeFailure)
                    }
                }
            }
            if (calls == writeCall + 1 && reconciliationFailure != null) throw reconciliationFailure
            return action()
        }
    }
}
