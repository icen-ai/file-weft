package ai.icen.fw.application.catalog

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.document.CreateDocumentDraftCommand
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.audit.AuditRecord
import ai.icen.fw.domain.audit.AuditRecordRepository
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentMutationRepository
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.catalog.DocumentCatalogAccessRequest
import ai.icen.fw.spi.catalog.DocumentCatalogBinding
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentCatalogDraftServiceTest {
    @Test
    fun `schema-aware create checks catalog and document access before metadata and keeps it out of storage`() {
        val fixture = Fixture()
        val providerTenants = mutableListOf<Identifier>()

        fixture.service.createInFolderWithMetadata(
            command(),
            "folder-alias",
            content(),
        ) { tenantId ->
            providerTenants += tenantId
            linkedMapOf(
                "amount" to "12.5",
                "metadata.schema-id" to "invoice",
                "metadata.schema-version" to "2",
            )
        }

        assertEquals(listOf(Identifier("tenant-a")), providerTenants)
        assertTrue(fixture.storage.uploads.single().metadata.isEmpty())
        assertEquals(
            linkedMapOf(
                "amount" to "12.5",
                "metadata.schema-id" to "invoice",
                "metadata.schema-version" to "2",
                DocumentCatalogBinding.METADATA_KEY to CANONICAL_FOLDER_ID,
            ),
            fixture.assets.saved.single().metadata,
        )
        assertTrue(fixture.events.indexOf("catalog") < fixture.events.indexOf("upload"))
    }

    @Test
    fun `schema-aware create never invokes metadata before both catalog and document authorization`() {
        listOf(
            Fixture(authorized = false),
            Fixture(documentAuthorized = false),
        ).forEach { fixture ->
            var providerCalls = 0

            assertThrows<SecurityException> {
                fixture.service.createInFolderWithMetadata(
                    command(),
                    "folder-alias",
                    content(),
                ) {
                    providerCalls++
                    mapOf("amount" to "private")
                }
            }

            assertEquals(0, providerCalls)
            assertNoDraftWasCreated(fixture)
        }
    }

    @Test
    fun `validates a host folder before upload then persists its canonical binding and audit detail`() {
        val fixture = Fixture()

        val created = fixture.service.createInFolder(
            command(metadata = linkedMapOf("source" to "portal")),
            "folder-alias",
            content(),
        )

        assertEquals("document-1", created.id.value)
        assertEquals(DocumentCatalogBinding.METADATA_KEY, fixture.storage.uploads.single().metadata.keys.last())
        assertEquals(CANONICAL_FOLDER_ID, fixture.storage.uploads.single().metadata[DocumentCatalogBinding.METADATA_KEY])
        assertEquals("portal", fixture.assets.saved.single().metadata["source"])
        assertEquals(CANONICAL_FOLDER_ID, fixture.assets.saved.single().metadata[DocumentCatalogBinding.METADATA_KEY])
        assertEquals(CANONICAL_FOLDER_ID, fixture.audits.records.single().details["folderId"])
        assertTrue(fixture.events.indexOf("catalog") < fixture.events.indexOf("upload"))
        assertEquals(1, fixture.catalog.calls)
        assertEquals("document:create", fixture.authorization.requests.first().action.name)
        assertEquals(1, fixture.documents.saved.size)
    }

    @Test
    fun `rejects denied and unknown folders before storage or document persistence`() {
        val denied = Fixture(authorized = false)

        assertThrows<SecurityException> {
            denied.service.createInFolder(command(), "folder-alias", content())
        }
        assertNoDraftWasCreated(denied)
        assertEquals(0, denied.catalog.calls)

        val missing = Fixture(folder = null)

        assertThrows<IllegalArgumentException> {
            missing.service.createInFolder(command(), "folder-alias", content())
        }
        assertNoDraftWasCreated(missing)
        assertEquals(1, missing.catalog.calls)
    }

    @Test
    fun `rejects a provider folder id with surrounding whitespace before upload`() {
        val fixture = Fixture(
            folder = DocumentCatalogFolder(" $CANONICAL_FOLDER_ID ", null, "Invalid canonical folder"),
        )

        val failure = assertThrows<IllegalStateException> {
            fixture.service.createInFolder(command(), "folder-alias", content())
        }

        assertTrue(failure.message.orEmpty().contains("provider returned an invalid canonical folder id"))
        assertEquals(1, fixture.catalog.calls)
        assertNoDraftWasCreated(fixture)
    }

    @Test
    fun `rejects every caller reserved namespace before upload`() {
        listOf(
            "catalog.folder-id",
            "CATALOG.host-binding",
            "fileweft.internal",
            "FILEWEFT.audit",
            "catalog.\nunsafe",
        ).forEach { key ->
            val fixture = Fixture()

            val failure = assertThrows<IllegalArgumentException> {
                fixture.service.createInFolder(command(metadata = mapOf(key to "value")), "folder-alias", content())
            }

            assertFalse(failure.message.orEmpty().contains(key), "Unsafe input must not be echoed by validation errors.")
            assertNoDraftWasCreated(fixture)
        }
    }

    @Test
    fun `rejects metadata count text control utf8 and aggregate limits before upload`() {
        val invalidMetadata = listOf(
            mapOf("key" to "   "),
            mapOf("key\u0000" to "value"),
            mapOf("key" to "value\u0000"),
            mapOf("x".repeat(DocumentCatalogDraftService.MAX_METADATA_KEY_CHARACTERS + 1) to "value"),
            mapOf("key" to "x".repeat(DocumentCatalogDraftService.MAX_METADATA_VALUE_CHARACTERS + 1)),
            mapOf("中".repeat(86) to "value"),
            mapOf("key" to "中".repeat(683)),
            (1..(DocumentCatalogDraftService.MAX_CALLER_METADATA_ENTRIES + 1)).associate { "key-$it" to "value" },
            (1..17).associate { "key-$it" to "x".repeat(DocumentCatalogDraftService.MAX_METADATA_VALUE_CHARACTERS) },
        )

        invalidMetadata.forEach { metadata ->
            val fixture = Fixture()

            assertThrows<IllegalArgumentException> {
                fixture.service.createInFolder(command(metadata = metadata), "folder-alias", content())
            }

            assertNoDraftWasCreated(fixture)
        }
    }

    @Test
    fun `accepts exactly thirty one caller metadata entries plus the verified folder binding`() {
        val fixture = Fixture()
        val metadata = (1..DocumentCatalogDraftService.MAX_CALLER_METADATA_ENTRIES)
            .associate { "key-$it" to "value-$it" }

        fixture.service.createInFolder(command(metadata = metadata), "folder-alias", content())

        assertEquals(DocumentCatalogDraftService.MAX_STORED_METADATA_ENTRIES, fixture.assets.saved.single().metadata.size)
        assertEquals(CANONICAL_FOLDER_ID, fixture.assets.saved.single().metadata[DocumentCatalogBinding.METADATA_KEY])
    }

    @Test
    fun `prevents a storage adapter from changing the verified binding before persistence or audit`() {
        val fixture = Fixture(attemptMetadataMutation = true)

        fixture.service.createInFolder(
            command(metadata = linkedMapOf("source" to "portal")),
            "folder-alias",
            content(),
        )

        assertTrue(fixture.storage.metadataMutationRejected)
        assertEquals(CANONICAL_FOLDER_ID, fixture.assets.saved.single().metadata[DocumentCatalogBinding.METADATA_KEY])
        assertEquals("portal", fixture.assets.saved.single().metadata["source"])
        assertEquals(CANONICAL_FOLDER_ID, fixture.audits.records.single().details["folderId"])
    }

    @Test
    fun `keeps direct draft creation usable when no catalog integration is installed`() {
        val fixture = Fixture()

        fixture.drafts.create(command(metadata = mapOf("source" to "legacy-host")), content())

        assertEquals(1, fixture.storage.uploads.size)
        assertEquals("legacy-host", fixture.assets.saved.single().metadata["source"])
        assertNull(fixture.assets.saved.single().metadata[DocumentCatalogBinding.METADATA_KEY])
        assertNull(fixture.audits.records.single().details["folderId"])
        assertEquals(0, fixture.catalog.calls)
    }

    private fun assertNoDraftWasCreated(fixture: Fixture) {
        assertTrue(fixture.storage.uploads.isEmpty())
        assertTrue(fixture.documents.saved.isEmpty())
        assertTrue(fixture.assets.saved.isEmpty())
        assertTrue(fixture.audits.records.isEmpty())
    }

    private fun command(metadata: Map<String, String> = emptyMap()) = CreateDocumentDraftCommand(
        documentNumber = "DOC-001",
        title = "目录归档合同",
        fileName = "contract.txt",
        contentLength = 7,
        contentType = "text/plain",
        metadata = metadata,
    )

    private fun content() = ByteArrayInputStream("content".toByteArray())

    private class Fixture(
        authorized: Boolean = true,
        documentAuthorized: Boolean = authorized,
        folder: DocumentCatalogFolder? = DocumentCatalogFolder(CANONICAL_FOLDER_ID, null, "Canonical folder"),
        attemptMetadataMutation: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        val storage = RecordingStorage(events, attemptMetadataMutation)
        val documents = RecordingDocuments()
        val assets = RecordingAssets()
        val audits = RecordingAudits()
        val authorization = RecordingAuthorization(authorized, documentAuthorized)
        val catalog = RecordingCatalog(events, folder)
        private val tenants = object : TenantProvider {
            override fun currentTenant() = TenantContext(Identifier("tenant-a"))
        }
        private val users = object : UserRealmProvider {
            override fun currentUser() = UserIdentity(Identifier("editor-a"), "Editor A")
            override fun findUser(userId: Identifier): UserIdentity? = null
        }
        private val catalogAccess = DocumentCatalogAccessService(tenants, users, authorization, catalog)
        val drafts = DocumentDraftService(
            tenantProvider = tenants,
            userRealmProvider = users,
            authorizationProvider = authorization,
            storageAdapter = storage,
            documentRepository = documents,
            fileObjectRepository = RecordingFileObjects(),
            fileAssetRepository = assets,
            identifierGenerator = SequenceIds("document-1", "file-1", "asset-1", "version-1"),
            transaction = DirectTransaction,
            auditTrail = AuditTrail(audits, SequenceIds("audit-1"), Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)),
        )
        val service = DocumentCatalogDraftService(drafts, catalogAccess)
    }

    private class RecordingAuthorization(
        private val catalogAllowed: Boolean,
        private val documentAllowed: Boolean,
    ) : AuthorizationProvider {
        val requests = mutableListOf<AuthorizationRequest>()

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            requests += request
            val allowed = if (request.resource.type == "DOCUMENT_CATALOG") catalogAllowed else documentAllowed
            return AuthorizationDecision(allowed, "denied")
        }
    }

    private class RecordingCatalog(
        private val events: MutableList<String>,
        private val folder: DocumentCatalogFolder?,
    ) : DocumentCatalogProvider {
        var calls = 0

        override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = emptyList()

        override fun findFolder(request: DocumentCatalogAccessRequest, folderId: String): DocumentCatalogFolder? {
            calls++
            events += "catalog"
            return folder?.takeIf { folderId == "folder-alias" }
        }
    }

    private class RecordingDocuments : DocumentMutationRepository {
        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? = null

        val saved = mutableListOf<Document>()

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? = null

        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? = null

        override fun save(document: Document) {
            saved += document
        }
    }

    private class RecordingFileObjects : FileObjectRepository {
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? = null

        override fun save(fileObject: FileObject) = Unit
    }

    private class RecordingAssets : FileAssetRepository {
        val saved = mutableListOf<FileAsset>()

        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? = null

        override fun save(fileAsset: FileAsset) {
            saved += fileAsset
        }
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

    private class RecordingStorage(
        private val events: MutableList<String>,
        private val attemptMetadataMutation: Boolean,
    ) : StorageAdapter {
        val uploads = mutableListOf<StorageUploadRequest>()
        var metadataMutationRejected = false

        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
            events += "upload"
            uploads += request
            if (attemptMetadataMutation) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    (request.metadata as MutableMap<String, String>)[DocumentCatalogBinding.METADATA_KEY] = "tampered"
                } catch (_: UnsupportedOperationException) {
                    metadataMutationRejected = true
                } catch (_: ClassCastException) {
                    metadataMutationRejected = true
                }
            }
            return StoredObject(StorageObjectLocation("memory", "objects/tenant-a/file"), request.contentLength, request.contentType, "sha256:test")
        }

        override fun delete(location: StorageObjectLocation) = Unit

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

    private class SequenceIds(values: List<String>) : IdentifierGenerator {
        private val values = ArrayDeque(values)

        constructor(vararg values: String) : this(values.toList())

        override fun nextId(): Identifier = Identifier(values.removeFirst())
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private companion object {
        const val CANONICAL_FOLDER_ID = "folder-canonical"
    }
}
