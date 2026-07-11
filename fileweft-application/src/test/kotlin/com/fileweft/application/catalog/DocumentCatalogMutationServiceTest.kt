package com.fileweft.application.catalog

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.document.AddDocumentVersionCommand
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.security.ApplicationUnauthenticatedException
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentConflictException
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.file.FileAsset
import com.fileweft.domain.file.FileAssetMutationRepository
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.domain.file.FileObject
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.catalog.DocumentCatalogAccessRequest
import com.fileweft.spi.catalog.DocumentCatalogBinding
import com.fileweft.spi.catalog.DocumentCatalogFolder
import com.fileweft.spi.catalog.DocumentCatalogProvider
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocumentCatalogMutationServiceTest {
    @Test
    fun `rejects an invisible source before upload or mutation persistence`() {
        val fixture = Fixture(visibleFolderIds = emptySet())

        assertThrows<DocumentNotFoundException> {
            fixture.service.addVersion(fixture.document.id, versionCommand(), content())
        }

        assertEquals(listOf(SOURCE_FOLDER_ID), fixture.catalog.folderRequests)
        assertEquals(1, fixture.documents.ordinaryReads)
        assertEquals(0, fixture.documents.mutationReads)
        assertTrue(fixture.storage.uploads.isEmpty())
        assertNoMutationWasPersisted(fixture)
    }

    @Test
    fun `rejects rename from an invisible source without changing the document`() {
        val fixture = Fixture(visibleFolderIds = emptySet())

        assertThrows<DocumentNotFoundException> {
            fixture.service.rename(fixture.document.id, "不可见目录中的新标题")
        }

        assertEquals("Contract", fixture.document.title)
        assertEquals(0, fixture.documents.mutationReads)
        assertNoMutationWasPersisted(fixture)
    }

    @Test
    fun `checks base authorization before any repository catalog or storage access`() {
        val fixture = Fixture(authorized = false)

        assertThrows<SecurityException> {
            fixture.service.addVersion(fixture.document.id, versionCommand(), content())
        }

        assertEquals(0, fixture.documents.ordinaryReads)
        assertEquals(0, fixture.documents.mutationReads)
        assertTrue(fixture.assets.reads.isEmpty())
        assertTrue(fixture.catalog.folderRequests.isEmpty())
        assertTrue(fixture.storage.uploads.isEmpty())
        assertNoMutationWasPersisted(fixture)
    }

    @Test
    fun `rejects a missing current user before the snapshot transaction`() {
        val fixture = Fixture(authenticated = false)

        assertThrows<ApplicationUnauthenticatedException> {
            fixture.service.rename(fixture.document.id, "Must not be applied")
        }

        assertEquals(0, fixture.transaction.executions)
        assertEquals(0, fixture.documents.ordinaryReads)
        assertTrue(fixture.catalog.folderRequests.isEmpty())
        assertNoMutationWasPersisted(fixture)
    }

    @Test
    fun `fails fast when the draft asset repository has no mutation capability`() {
        val failure = assertThrows<IllegalArgumentException> {
            Fixture(mutationCapable = false)
        }

        assertTrue(failure.message.orEmpty().contains("FileAssetMutationRepository"))
    }

    @Test
    fun `rejects malicious cross tenant snapshot results before catalog or upload`() {
        val foreignTenant = Identifier("tenant-b")
        val foreignDocumentFixture = Fixture()
        foreignDocumentFixture.documents.findByIdOverride = { _, _ ->
            Document(
                id = foreignDocumentFixture.document.id,
                tenantId = foreignTenant,
                assetId = foreignDocumentFixture.document.assetId,
                documentNumber = "DOC-FOREIGN",
                title = "Foreign document",
            )
        }

        assertThrows<DocumentNotFoundException> {
            foreignDocumentFixture.service.addVersion(
                foreignDocumentFixture.document.id,
                versionCommand(),
                content(),
            )
        }
        assertTrue(foreignDocumentFixture.catalog.folderRequests.isEmpty())
        assertTrue(foreignDocumentFixture.storage.uploads.isEmpty())
        assertNoMutationWasPersisted(foreignDocumentFixture)

        listOf(
            FileAsset(
                foreignDocumentFixture.document.assetId,
                foreignTenant,
                Identifier("file-foreign"),
                "DOCUMENT",
            ),
            FileAsset(
                Identifier("asset-wrong"),
                TENANT_ID,
                Identifier("file-wrong"),
                "DOCUMENT",
            ),
        ).forEach { invalidAsset ->
            val fixture = Fixture()
            fixture.assets.findByIdOverride = { _, _ -> invalidAsset }

            assertThrows<IllegalStateException> {
                fixture.service.addVersion(fixture.document.id, versionCommand(), content())
            }
            assertTrue(fixture.catalog.folderRequests.isEmpty())
            assertTrue(fixture.storage.uploads.isEmpty())
            assertNoMutationWasPersisted(fixture)
        }
    }

    @Test
    fun `treats absent and blank persisted bindings as inbox`() {
        listOf(null, "   ").forEach { rawFolderId ->
            val fixture = Fixture(rawFolderId = rawFolderId, visibleFolderIds = setOf(INBOX_FOLDER_ID))

            val renamed = fixture.service.rename(fixture.document.id, "Inbox contract")

            assertEquals("Inbox contract", renamed.title)
            assertEquals(listOf(INBOX_FOLDER_ID), fixture.catalog.folderRequests)
            assertEquals(1, fixture.documents.mutationReads)
            assertEquals(1, fixture.documents.saved.size)
        }
    }

    @Test
    fun `calls catalog outside transactions before upload and takes the mutation lock afterwards`() {
        val fixture = Fixture()

        val updated = fixture.service.addVersion(fixture.document.id, versionCommand(), content())

        assertEquals(listOf("1.0", "1.1"), updated.versions.map { version -> version.versionNumber })
        assertEquals(listOf(SOURCE_FOLDER_ID, SOURCE_FOLDER_ID), fixture.catalog.folderRequests)
        assertEquals(listOf(false, false), fixture.catalog.transactionStates)
        val catalogPositions = fixture.events.indices.filter { index ->
            fixture.events[index] == "catalog:$SOURCE_FOLDER_ID"
        }
        assertTrue(fixture.events.indexOf("transaction:end:1") < catalogPositions.first())
        assertTrue(catalogPositions.first() < fixture.events.indexOf("storage:upload"))
        assertTrue(fixture.events.indexOf("storage:upload") < catalogPositions.last())
        assertTrue(catalogPositions.last() < fixture.events.indexOf("document:find-for-mutation"))
        assertTrue(
            fixture.events.indexOf("document:find-for-mutation") <
                fixture.events.indexOf("asset:find-for-mutation"),
        )
        assertEquals(2, fixture.transaction.executions)
        assertEquals(1, fixture.documents.mutationReads)
        assertEquals(1, fixture.assets.ordinaryReads)
        assertEquals(1, fixture.assets.mutationReads)
    }

    @Test
    fun `compensates the upload when source access is revoked during upload`() {
        val fixture = Fixture()
        fixture.storage.afterUpload = {
            fixture.catalog.hide(SOURCE_FOLDER_ID)
        }

        assertThrows<DocumentNotFoundException> {
            fixture.service.addVersion(fixture.document.id, versionCommand(), content())
        }

        assertEquals(listOf(SOURCE_FOLDER_ID, SOURCE_FOLDER_ID), fixture.catalog.folderRequests)
        assertEquals(listOf(false, false), fixture.catalog.transactionStates)
        assertEquals(listOf(fixture.storage.location), fixture.storage.deleted)
        assertEquals(0, fixture.documents.mutationReads)
        assertEquals(1, fixture.assets.ordinaryReads)
        assertEquals(0, fixture.assets.mutationReads)
        assertNoMutationWasPersisted(fixture)
    }

    @Test
    fun `compensates an uploaded version when the persisted binding changes before locked verification`() {
        val fixture = Fixture()
        fixture.documents.beforeFindForMutation = {
            fixture.assets.replaceBinding("finance")
        }

        val failure = assertThrows<DocumentCatalogBindingChangedException> {
            fixture.service.addVersion(fixture.document.id, versionCommand(), content())
        }

        assertIs<DocumentConflictException>(failure)
        assertEquals(fixture.document.id, failure.documentId)
        assertEquals(1, fixture.storage.uploads.size)
        assertEquals(listOf(fixture.storage.location), fixture.storage.deleted)
        assertEquals(listOf("1.0"), fixture.document.versions.map { version -> version.versionNumber })
        assertNoMutationWasPersisted(fixture)
    }

    @Test
    fun `does not rename or audit when the persisted binding changes before locked verification`() {
        val fixture = Fixture()
        fixture.documents.beforeFindForMutation = {
            fixture.assets.replaceBinding("finance")
        }

        assertThrows<DocumentCatalogBindingChangedException> {
            fixture.service.rename(fixture.document.id, "Must not be applied")
        }

        assertEquals("Contract", fixture.document.title)
        assertNoMutationWasPersisted(fixture)
    }

    @Test
    fun `compensates an upload when the document is rebound to another asset`() {
        val fixture = Fixture()
        fixture.documents.beforeFindForMutation = {
            fixture.documents.replaceAssetReference(Identifier("asset-2"))
        }

        assertThrows<DocumentCatalogBindingChangedException> {
            fixture.service.addVersion(fixture.document.id, versionCommand(), content())
        }

        assertEquals(listOf(fixture.storage.location), fixture.storage.deleted)
        assertNoMutationWasPersisted(fixture)
    }

    @Test
    fun `treats malicious locked repository identities as conflicts and compensates uploads`() {
        listOf("document", "asset").forEach { maliciousResult ->
            val fixture = Fixture()
            if (maliciousResult == "document") {
                fixture.documents.findForMutationOverride = { _, _ ->
                    Document(
                        id = fixture.document.id,
                        tenantId = Identifier("tenant-b"),
                        assetId = fixture.document.assetId,
                        documentNumber = "DOC-FOREIGN",
                        title = "Foreign document",
                    )
                }
            } else {
                fixture.assets.findForMutationOverride = { _, _ ->
                    FileAsset(
                        id = Identifier("asset-wrong"),
                        tenantId = TENANT_ID,
                        fileObjectId = Identifier("file-wrong"),
                        assetType = "DOCUMENT",
                    )
                }
            }

            assertThrows<DocumentCatalogBindingChangedException> {
                fixture.service.addVersion(fixture.document.id, versionCommand(), content())
            }

            assertEquals(listOf(fixture.storage.location), fixture.storage.deleted)
            assertNoMutationWasPersisted(fixture)
        }
    }

    @Test
    fun `compensates add version when the final document or asset disappears`() {
        listOf("document", "asset").forEach { missingResource ->
            val fixture = Fixture()
            if (missingResource == "document") {
                fixture.documents.findForMutationOverride = { _, _ -> null }
            } else {
                fixture.assets.findForMutationOverride = { _, _ -> null }
            }

            assertThrows<RuntimeException> {
                fixture.service.addVersion(fixture.document.id, versionCommand(), content())
            }

            assertEquals(listOf(fixture.storage.location), fixture.storage.deleted)
            assertNoMutationWasPersisted(fixture)
        }
    }

    @Test
    fun `does not rename or audit when the final document or asset disappears`() {
        listOf("document", "asset").forEach { missingResource ->
            val fixture = Fixture()
            if (missingResource == "document") {
                fixture.documents.findForMutationOverride = { _, _ -> null }
            } else {
                fixture.assets.findForMutationOverride = { _, _ -> null }
            }

            assertThrows<RuntimeException> {
                fixture.service.rename(fixture.document.id, "Must not be applied")
            }

            assertEquals("Contract", fixture.document.title)
            assertNoMutationWasPersisted(fixture)
        }
    }

    @Test
    fun `turns corrupt persisted bindings into an internal failure before upload`() {
        listOf("unsafe\u0000folder", "x".repeat(257)).forEach { corruptFolderId ->
            val fixture = Fixture(rawFolderId = corruptFolderId)

            val failure = assertThrows<IllegalStateException> {
                fixture.service.addVersion(fixture.document.id, versionCommand(), content())
            }

            assertFalse(failure is DocumentConflictException)
            assertTrue(failure.message.orEmpty().contains("Persisted document catalog binding"))
            assertTrue(fixture.catalog.folderRequests.isEmpty())
            assertTrue(fixture.storage.uploads.isEmpty())
            assertNoMutationWasPersisted(fixture)
        }
    }

    private fun assertNoMutationWasPersisted(fixture: Fixture) {
        assertTrue(fixture.documents.saved.isEmpty())
        assertTrue(fixture.fileObjects.saved.isEmpty())
        assertTrue(fixture.assets.saved.isEmpty())
        assertTrue(fixture.audits.records.isEmpty())
    }

    private fun versionCommand() = AddDocumentVersionCommand(
        versionNumber = "1.1",
        fileName = "contract-revised.txt",
        contentLength = 7,
        contentType = "text/plain",
    )

    private fun content() = ByteArrayInputStream("content".toByteArray())

    private class Fixture(
        rawFolderId: String? = SOURCE_FOLDER_ID,
        visibleFolderIds: Set<String> = setOf(SOURCE_FOLDER_ID),
        authorized: Boolean = true,
        authenticated: Boolean = true,
        mutationCapable: Boolean = true,
    ) {
        val events = mutableListOf<String>()
        val transaction = TrackingTransaction(events)
        val document = document()
        val documents = RecordingDocuments(document, transaction, events)
        val assets = RecordingAssets(asset(document, rawFolderId), transaction, events)
        private val assetRepository: FileAssetRepository = if (mutationCapable) {
            assets
        } else {
            NonLockingAssets(assets.asset)
        }
        val fileObjects = RecordingFileObjects()
        val audits = RecordingAudits()
        val storage = RecordingStorage(events)
        val catalog = RecordingCatalog(visibleFolderIds, transaction, events)
        private val authorization = RecordingAuthorization(authorized, events)
        private val tenants = object : TenantProvider {
            override fun currentTenant() = TenantContext(TENANT_ID)
        }
        private val users = object : UserRealmProvider {
            override fun currentUser() = if (authenticated) UserIdentity(USER_ID, "Editor A") else null
            override fun findUser(userId: Identifier): UserIdentity? = null
        }
        private val catalogAccess = DocumentCatalogAccessService(tenants, users, authorization, catalog)
        private val drafts = DocumentDraftService(
            tenantProvider = tenants,
            userRealmProvider = users,
            authorizationProvider = authorization,
            storageAdapter = storage,
            documentRepository = documents,
            fileObjectRepository = fileObjects,
            fileAssetRepository = assetRepository,
            identifierGenerator = SequenceIds("file-2", "version-2"),
            transaction = transaction,
            auditTrail = AuditTrail(
                audits,
                SequenceIds("audit-1"),
                Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC),
            ),
        )
        val service = DocumentCatalogMutationService(drafts, catalogAccess)
    }

    private class TrackingTransaction(
        private val events: MutableList<String>,
    ) : ApplicationTransaction {
        var active: Boolean = false
            private set
        var executions: Int = 0
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested test transactions are not expected." }
            executions++
            val execution = executions
            events += "transaction:start:$execution"
            active = true
            return try {
                action()
            } finally {
                active = false
                events += "transaction:end:$execution"
            }
        }
    }

    private class RecordingDocuments(
        var document: Document,
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : DocumentRepository {
        var ordinaryReads: Int = 0
        var mutationReads: Int = 0
        var beforeFindForMutation: (() -> Unit)? = null
        var findByIdOverride: ((Identifier, Identifier) -> Document?)? = null
        var findForMutationOverride: ((Identifier, Identifier) -> Document?)? = null
        val saved = mutableListOf<Document>()

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? {
            assertTrue(transaction.active, "Catalog snapshot reads must run in the short transaction.")
            ordinaryReads++
            events += "document:find"
            findByIdOverride?.let { provider -> return provider(tenantId, documentId) }
            return document.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == documentId }
        }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            assertTrue(transaction.active, "Mutation reads must run in the final transaction.")
            mutationReads++
            events += "document:find-for-mutation"
            beforeFindForMutation?.invoke()
            findForMutationOverride?.let { provider -> return provider(tenantId, documentId) }
            return document.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == documentId }
        }

        override fun save(document: Document) {
            assertTrue(transaction.active)
            this.document = document
            saved += document
            events += "document:save"
        }

        fun replaceAssetReference(assetId: Identifier) {
            document = Document(
                id = document.id,
                tenantId = document.tenantId,
                assetId = assetId,
                documentNumber = document.documentNumber,
                title = document.title,
                lifecycleState = document.lifecycleState,
                versions = document.versions,
                currentVersionId = document.currentVersionId,
                deliveryGeneration = document.deliveryGeneration,
            )
        }
    }

    private class RecordingAssets(
        var asset: FileAsset,
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : FileAssetMutationRepository {
        var ordinaryReads: Int = 0
        var mutationReads: Int = 0
        var findByIdOverride: ((Identifier, Identifier) -> FileAsset?)? = null
        var findForMutationOverride: ((Identifier, Identifier) -> FileAsset?)? = null
        val reads = mutableListOf<Identifier>()
        val saved = mutableListOf<FileAsset>()

        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? {
            assertTrue(transaction.active, "Asset binding reads must be transaction-scoped.")
            ordinaryReads++
            reads += fileAssetId
            events += "asset:find"
            findByIdOverride?.let { provider -> return provider(tenantId, fileAssetId) }
            return asset.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == fileAssetId }
        }

        override fun findForMutation(tenantId: Identifier, fileAssetId: Identifier): FileAsset? {
            assertTrue(transaction.active, "Asset mutation reads must run in the final transaction.")
            mutationReads++
            reads += fileAssetId
            events += "asset:find-for-mutation"
            findForMutationOverride?.let { provider -> return provider(tenantId, fileAssetId) }
            return asset.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == fileAssetId }
        }

        override fun save(fileAsset: FileAsset) {
            assertTrue(transaction.active)
            asset = fileAsset
            saved += fileAsset
        }

        fun replaceBinding(folderId: String) {
            asset = FileAsset(
                asset.id,
                asset.tenantId,
                asset.fileObjectId,
                asset.assetType,
                asset.metadata + (DocumentCatalogBinding.METADATA_KEY to folderId),
            )
        }
    }

    private class RecordingFileObjects : FileObjectRepository {
        val saved = mutableListOf<FileObject>()

        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? = null

        override fun save(fileObject: FileObject) {
            saved += fileObject
        }
    }

    private class RecordingCatalog(
        visibleFolderIds: Set<String>,
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : DocumentCatalogProvider {
        private val visibleFolderIds = visibleFolderIds.toMutableSet()
        val folderRequests = mutableListOf<String>()
        val transactionStates = mutableListOf<Boolean>()

        override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = emptyList()

        override fun findFolder(request: DocumentCatalogAccessRequest, folderId: String): DocumentCatalogFolder? {
            transactionStates += transaction.active
            assertFalse(transaction.active, "A host catalog must never be called inside a database transaction.")
            folderRequests += folderId
            events += "catalog:$folderId"
            return folderId.takeIf(visibleFolderIds::contains)
                ?.let { visibleId -> DocumentCatalogFolder(visibleId, null, "Folder $visibleId") }
        }

        fun hide(folderId: String) {
            visibleFolderIds -= folderId
        }
    }

    private class RecordingAuthorization(
        private val allowed: Boolean,
        private val events: MutableList<String>,
    ) : AuthorizationProvider {
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            events += "authorization:${request.action.name}"
            return AuthorizationDecision(allowed, "denied")
        }
    }

    private class RecordingStorage(
        private val events: MutableList<String>,
    ) : StorageAdapter {
        val location = StorageObjectLocation("memory", "objects/tenant-a/file-2")
        val uploads = mutableListOf<StorageUploadRequest>()
        val deleted = mutableListOf<StorageObjectLocation>()
        var afterUpload: (() -> Unit)? = null

        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
            events += "storage:upload"
            uploads += request
            val stored = StoredObject(location, request.contentLength, request.contentType, "sha256:test")
            afterUpload?.invoke()
            return stored
        }

        override fun delete(location: StorageObjectLocation) {
            events += "storage:delete"
            deleted += location
        }

        override fun download(location: StorageObjectLocation): StorageDownload = throw UnsupportedOperationException()
        override fun exists(location: StorageObjectLocation): Boolean = true
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = URI("http://localhost/file")
        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = throw UnsupportedOperationException()
        override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart =
            throw UnsupportedOperationException()
        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject =
            throw UnsupportedOperationException()
        override fun abortMultipartUpload(upload: MultipartUpload) = Unit
    }

    private class NonLockingAssets(
        private val asset: FileAsset,
    ) : FileAssetRepository {
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
            asset.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == fileAssetId }

        override fun save(fileAsset: FileAsset) = Unit
    }

    private class RecordingAudits : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()

        override fun append(record: AuditRecord) {
            records += record
        }

        override fun findByResource(
            tenantId: Identifier,
            resourceType: String,
            resourceId: Identifier,
            limit: Int,
        ): List<AuditRecord> = emptyList()
    }

    private class SequenceIds(vararg values: String) : IdentifierGenerator {
        private val values = ArrayDeque(values.toList())
        override fun nextId(): Identifier = Identifier(values.removeFirst())
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-a")
        val USER_ID = Identifier("editor-a")
        const val SOURCE_FOLDER_ID = "contracts"
        const val INBOX_FOLDER_ID = "inbox"

        fun document() = Document(
            id = Identifier("document-1"),
            tenantId = TENANT_ID,
            assetId = Identifier("asset-1"),
            documentNumber = "DOC-001",
            title = "Contract",
            versions = listOf(
                DocumentVersion(
                    id = Identifier("version-1"),
                    tenantId = TENANT_ID,
                    documentId = Identifier("document-1"),
                    versionNumber = "1.0",
                    fileObjectId = Identifier("file-1"),
                ),
            ),
            currentVersionId = Identifier("version-1"),
        )

        fun asset(document: Document, rawFolderId: String?): FileAsset = FileAsset(
            id = document.assetId,
            tenantId = document.tenantId,
            fileObjectId = Identifier("file-1"),
            assetType = "DOCUMENT",
            metadata = rawFolderId?.let { folderId -> mapOf(DocumentCatalogBinding.METADATA_KEY to folderId) }
                ?: emptyMap(),
        )
    }
}
