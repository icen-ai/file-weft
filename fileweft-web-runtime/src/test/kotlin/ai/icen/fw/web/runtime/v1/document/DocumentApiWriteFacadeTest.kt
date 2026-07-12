package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.web.runtime.v1.V1FeatureUnavailableException

import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogDraftService
import ai.icen.fw.application.catalog.DocumentCatalogMutationService
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetMutationRepository
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
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
import ai.icen.fw.web.api.v1.document.AddDocumentVersionCommand
import ai.icen.fw.web.api.v1.document.CreateDocumentDraftCommand
import ai.icen.fw.web.api.v1.document.RenameDocumentCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.time.Duration
import java.util.ArrayDeque
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentApiWriteFacadeTest {
    @Test
    fun `only creates locations for identifiers that are reliably routable as one path segment`() {
        assertEquals(
            "/fileweft/v1/documents/7cf5c0ad-84bf-44c5-a093-25a3f4427bd4",
            DocumentApiLocations.detailIfRoutable("7cf5c0ad-84bf-44c5-a093-25a3f4427bd4")?.toString(),
        )
        assertNull(DocumentApiLocations.detailIfRoutable("文档/1 %"))
        assertNull(DocumentApiLocations.detailIfRoutable("."))
        assertNull(DocumentApiLocations.detailIfRoutable(".."))
        assertNull(DocumentApiLocations.detailIfRoutable("document\u0000other"))
        assertNull(DocumentApiLocations.detailIfRoutable("x".repeat(129)))
        assertNull(DocumentApiLocations.detailIfRoutable("\uD800"))
    }

    @Test
    fun `creates a flat draft and returns only its committed public state`() {
        val fixture = Fixture()
        val content = "公开内容".toByteArray()

        val result = fixture.facade.create(
            CreateDocumentDraftCommand(
                documentNumber = "DOC-1",
                title = "清税证明",
                fileName = "证明.pdf",
                contentLength = content.size.toLong(),
                contentType = "application/pdf",
            ),
            ByteArrayInputStream(content),
        )

        assertEquals("document-1", result.documentId)
        assertEquals("version-1", result.versionId)
        assertEquals("tenant-a", fixture.storage.requests.single().tenantId.value)
        assertEquals("证明.pdf", fixture.storage.requests.single().objectName)
        assertTrue(fixture.storage.requests.single().metadata.isEmpty())
        assertEquals(content.toList(), fixture.storage.uploaded.single().toList())
    }

    @Test
    fun `requires the verified host catalog path whenever catalog support is configured`() {
        val fixture = Fixture(withCatalog = true)

        assertThrows<IllegalArgumentException> {
            fixture.facade.create(
                CreateDocumentDraftCommand("DOC-1", "Title", "proof.pdf", 1),
                ByteArrayInputStream(byteArrayOf(1)),
            )
        }
        assertTrue(fixture.storage.requests.isEmpty())

        val result = fixture.facade.create(
            CreateDocumentDraftCommand("DOC-1", "Title", "proof.pdf", 1, folderId = "finance"),
            ByteArrayInputStream(byteArrayOf(1)),
        )

        assertEquals("document-1", result.documentId)
        assertEquals("finance", fixture.storage.requests.single().metadata["catalog.folder-id"])
        assertEquals("finance", fixture.assets.saved.single().metadata["catalog.folder-id"])
        assertTrue(fixture.authorization.requests.any { request ->
            request.resource.type == "DOCUMENT_CATALOG" && request.action.name == "document:create"
        })
    }

    @Test
    fun `does not silently discard a requested folder when the host has no catalog`() {
        val fixture = Fixture()

        assertThrows<V1FeatureUnavailableException> {
            fixture.facade.create(
                CreateDocumentDraftCommand("DOC-1", "Title", "proof.pdf", 1, folderId = "finance"),
                ByteArrayInputStream(byteArrayOf(1)),
            )
        }

        assertTrue(fixture.storage.requests.isEmpty())
        assertTrue(fixture.documents.values.isEmpty())
    }

    @Test
    fun `uses catalog aware mutations for version and rename when the current binding can be guarded`() {
        val fixture = Fixture(withCatalog = true)
        fixture.facade.create(
            CreateDocumentDraftCommand("DOC-1", "Title", "v1.txt", 2, folderId = "finance"),
            ByteArrayInputStream("v1".toByteArray()),
        )

        val version = fixture.facade.addVersion(
            "document-1",
            AddDocumentVersionCommand("2.0", "v2.txt", 2),
            ByteArrayInputStream("v2".toByteArray()),
        )
        val renamed = fixture.facade.rename("document-1", RenameDocumentCommand("Renamed"))

        assertEquals("version-2", version.versionId)
        assertEquals("document-1", renamed.documentId)
        assertEquals(2, fixture.storage.requests.size)
        assertEquals(2, fixture.documents.mutationReads)
        assertEquals("Renamed", fixture.documents.values.values.single().title)
    }

    @Test
    fun `still fails closed when a catalog host has not installed the guarded mutation service`() {
        val fixture = Fixture(withCatalog = true, withCatalogMutations = false)
        fixture.facade.create(
            CreateDocumentDraftCommand("DOC-1", "Title", "v1.txt", 2, folderId = "finance"),
            ByteArrayInputStream("v1".toByteArray()),
        )

        assertThrows<V1FeatureUnavailableException> {
            fixture.facade.addVersion(
                "document-1",
                AddDocumentVersionCommand("2.0", "v2.txt", 2),
                ByteArrayInputStream("v2".toByteArray()),
            )
        }
        assertThrows<V1FeatureUnavailableException> {
            fixture.facade.rename("document-1", RenameDocumentCommand("Renamed"))
        }

        assertEquals(1, fixture.storage.requests.size)
        assertEquals(0, fixture.documents.mutationReads)
        assertEquals("Title", fixture.documents.values.values.single().title)
    }

    @Test
    fun `adds a version and renames without a follow-up query DTO`() {
        val fixture = Fixture()
        fixture.facade.create(
            CreateDocumentDraftCommand("DOC-1", "Original", "v1.txt", 2, "text/plain"),
            ByteArrayInputStream("v1".toByteArray()),
        )

        val versionResult = fixture.facade.addVersion(
            "document-1",
            AddDocumentVersionCommand("2.0", "v2.txt", 2, "text/plain"),
            ByteArrayInputStream("v2".toByteArray()),
        )
        val renameResult = fixture.facade.rename("document-1", RenameDocumentCommand("Renamed"))

        assertEquals("version-2", versionResult.versionId)
        assertEquals("document-1", renameResult.documentId)
        assertNull(renameResult.versionId)
        assertEquals(2, fixture.storage.requests.size)
    }

    @Test
    fun `rejects unsafe opaque identifiers before opening storage or loading a document`() {
        val fixture = Fixture()

        assertThrows<IllegalArgumentException> {
            fixture.facade.addVersion(
                "document\u0000other",
                AddDocumentVersionCommand("2.0", "v2.txt", 2),
                ByteArrayInputStream("v2".toByteArray()),
            )
        }
        assertThrows<IllegalArgumentException> {
            fixture.facade.rename(" ", RenameDocumentCommand("Renamed"))
        }

        assertTrue(fixture.storage.requests.isEmpty())
        assertEquals(0, fixture.documents.mutationReads)
    }

    private class Fixture(
        withCatalog: Boolean = false,
        withCatalogMutations: Boolean = withCatalog,
    ) {
        val documents = MemoryDocuments()
        val assets = MemoryAssets()
        val storage = RecordingStorage()
        val authorization = RecordingAuthorization()
        private val tenants = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(TENANT_ID)
        }
        private val users = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(USER_ID, "Alice")
            override fun findUser(userId: Identifier): UserIdentity? = null
        }
        private val drafts = DocumentDraftService(
            tenantProvider = tenants,
            userRealmProvider = users,
            authorizationProvider = authorization,
            storageAdapter = storage,
            documentRepository = documents,
            fileObjectRepository = MemoryFileObjects(),
            fileAssetRepository = assets,
            identifierGenerator = SequenceIdentifiers(
                "document-1", "file-1", "asset-1", "version-1", "file-2", "version-2",
            ),
            transaction = DirectTransaction,
        )
        private val catalogAccess: DocumentCatalogAccessService? = if (withCatalog) {
            DocumentCatalogAccessService(
                tenantProvider = tenants,
                userRealmProvider = users,
                authorizationProvider = authorization,
                catalog = object : DocumentCatalogProvider {
                    override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = listOf(
                        DocumentCatalogFolder("finance", null, "Finance"),
                    )
                },
            )
        } else {
            null
        }
        private val catalogDrafts = catalogAccess?.let { access -> DocumentCatalogDraftService(drafts, access) }
        private val catalogMutations = catalogAccess
            ?.takeIf { withCatalogMutations }
            ?.let { access ->
                DocumentCatalogMutationService(drafts, access)
            }
        val facade = DocumentApiWriteFacade(drafts, catalogDrafts, catalogMutations)
    }

    private class RecordingAuthorization : AuthorizationProvider {
        val requests = mutableListOf<AuthorizationRequest>()
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            requests += request
            return AuthorizationDecision(true)
        }
    }

    private class MemoryDocuments : DocumentRepository {
        val values = linkedMapOf<Identifier, Document>()
        var mutationReads: Int = 0
            private set

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            values[documentId]?.takeIf { document -> document.tenantId == tenantId }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            mutationReads++
            return findById(tenantId, documentId)
        }

        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? =
            values.values.firstOrNull { document ->
                document.tenantId == tenantId && document.documentNumber == documentNumber
            }

        override fun save(document: Document) {
            values[document.id] = document
        }
    }

    private class MemoryFileObjects : FileObjectRepository {
        private val values = linkedMapOf<Identifier, FileObject>()
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? =
            values[fileObjectId]?.takeIf { fileObject -> fileObject.tenantId == tenantId }

        override fun save(fileObject: FileObject) {
            values[fileObject.id] = fileObject
        }
    }

    private class MemoryAssets : FileAssetMutationRepository {
        val saved = mutableListOf<FileAsset>()
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
            saved.firstOrNull { asset -> asset.tenantId == tenantId && asset.id == fileAssetId }

        override fun findForMutation(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
            findById(tenantId, fileAssetId)

        override fun save(fileAsset: FileAsset) {
            saved += fileAsset
        }
    }

    private class RecordingStorage : StorageAdapter {
        val requests = mutableListOf<StorageUploadRequest>()
        val uploaded = mutableListOf<ByteArray>()

        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
            requests += request
            uploaded += content.readBytes()
            return StoredObject(
                location = StorageObjectLocation("memory", "objects/${requests.size}"),
                contentLength = request.contentLength,
                contentType = request.contentType,
            )
        }

        override fun download(location: StorageObjectLocation): StorageDownload = throw UnsupportedOperationException()
        override fun delete(location: StorageObjectLocation) = Unit
        override fun exists(location: StorageObjectLocation): Boolean = false
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = URI.create("memory://object")
        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = throw UnsupportedOperationException()
        override fun uploadPart(
            upload: MultipartUpload,
            partNumber: Int,
            content: InputStream,
            contentLength: Long,
        ): MultipartPart = throw UnsupportedOperationException()

        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject =
            throw UnsupportedOperationException()

        override fun abortMultipartUpload(upload: MultipartUpload) = Unit
    }

    private class SequenceIdentifiers(vararg values: String) : IdentifierGenerator {
        private val remaining = ArrayDeque(values.map(::Identifier))
        override fun nextId(): Identifier = remaining.removeFirst()
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-a")
        val USER_ID = Identifier("user-a")
    }
}
