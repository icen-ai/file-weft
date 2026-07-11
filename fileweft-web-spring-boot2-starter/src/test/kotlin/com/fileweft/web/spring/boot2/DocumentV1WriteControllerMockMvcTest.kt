package com.fileweft.web.spring.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.catalog.DocumentCatalogAccessService
import com.fileweft.application.catalog.DocumentCatalogDraftService
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.context.TraceContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.file.FileAsset
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.domain.file.FileObject
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.catalog.DocumentCatalogFolder
import com.fileweft.spi.catalog.DocumentCatalogProvider
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.spi.storage.MultipartPart
import com.fileweft.spi.storage.MultipartUpload
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageDownload
import com.fileweft.spi.storage.StorageObjectLocation
import com.fileweft.spi.storage.StorageUploadRequest
import com.fileweft.spi.storage.StoredObject
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentApiWriteFacade
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.InputStream
import java.lang.reflect.Modifier
import java.net.URI
import java.time.Duration
import java.util.ArrayDeque
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentV1WriteControllerMockMvcTest {
    @Test
    fun `creates a draft adds a version and renames through JSON without Kotlin Jackson support`() {
        val fixture = DocumentV1WriteControllerTestFixture()
        val mockMvc = mvc(fixture, "trace-write-1")

        mockMvc.perform(createRequest())
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/fileweft/v1/documents/document-1"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").value("trace-write-1"))
            .andExpect(jsonPath("$.data.documentId").value("document-1"))
            .andExpect(jsonPath("$.data.versionId").value("version-1"))
            .andExpect(jsonPath("$.data.title").doesNotExist())
            .andExpect(content().string(not(containsString("tenantId"))))
            .andExpect(content().string(not(containsString("storagePath"))))
            .andExpect(content().string(not(containsString("fileObjectId"))))
            .andExpect(content().string(not(containsString("success"))))
            .andExpect(content().string(not(containsString("failure"))))

        mockMvc.perform(
            multipart("/fileweft/v1/documents/document-1/versions")
                .file(file("version.txt", "version-two"))
                .param("versionNumber", "2.0"),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/fileweft/v1/documents/document-1"))
            .andExpect(jsonPath("$.traceId").value("trace-write-1"))
            .andExpect(jsonPath("$.data.documentId").value("document-1"))
            .andExpect(jsonPath("$.data.versionId").value("version-2"))

        mockMvc.perform(
            patch("/fileweft/v1/documents/document-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Renamed document\"}"),
        )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.traceId").value("trace-write-1"))
            .andExpect(jsonPath("$.data.documentId").value("document-1"))
            .andExpect(jsonPath("$.data.title").doesNotExist())

        assertEquals(2, fixture.storage.uploads.size)
        assertEquals("draft.txt", fixture.storage.uploads[0].objectName)
        assertEquals("draft-content".toByteArray().size.toLong(), fixture.storage.uploads[0].contentLength)
        assertEquals("text/plain", fixture.storage.uploads[0].contentType)
        assertEquals("version.txt", fixture.storage.uploads[1].objectName)
        assertEquals("version-two".toByteArray().size.toLong(), fixture.storage.uploads[1].contentLength)
        assertEquals("text/plain", fixture.storage.uploads[1].contentType)
        assertEquals("draft-content".toByteArray().toList(), fixture.storage.contents[0].toList())
        assertEquals("version-two".toByteArray().toList(), fixture.storage.contents[1].toList())
        assertEquals("Renamed document", fixture.documents.values.getValue(Identifier("document-1")).title)
    }

    @Test
    fun `rejects missing duplicate and path-like multipart values before storage opens`() {
        val fixture = DocumentV1WriteControllerTestFixture()
        val mockMvc = mvc(fixture)

        mockMvc.perform(
            multipart("/fileweft/v1/documents")
                .file(file("draft.txt", "draft-content"))
                .param("title", "Draft"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        mockMvc.perform(
            multipart("/fileweft/v1/documents")
                .file(file("draft.txt", "draft-content"))
                .file(file("other.txt", "other-content"))
                .param("documentNumber", "DOC-1", "DOC-2")
                .param("title", "Draft"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        mockMvc.perform(
            multipart("/fileweft/v1/documents")
                .file(file("../private.txt", "draft-content"))
                .param("documentNumber", "DOC-1")
                .param("title", "Draft"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(content().string(not(containsString("../private.txt"))))

        assertTrue(fixture.storage.uploads.isEmpty())
    }

    @Test
    fun `returns a created body without Location when the committed document id is not publicly routable`() {
        val fixture = DocumentV1WriteControllerTestFixture(
            identifiers = listOf("unsafe/document", "file-1", "asset-1", "version-1"),
        )

        mvc(fixture)
            .perform(createRequest())
            .andExpect(status().isCreated)
            .andExpect(header().doesNotExist("Location"))
            .andExpect(jsonPath("$.data.documentId").value("unsafe/document"))
    }

    @Test
    fun `maps missing or incomplete rename JSON inside the fixed 400 envelope`() {
        val mockMvc = mvc(DocumentV1WriteControllerTestFixture())

        mockMvc.perform(
            patch("/fileweft/v1/documents/document-1")
                .contentType(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        mockMvc.perform(
            patch("/fileweft/v1/documents/document-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `fails closed for catalog hosts until catalog-aware version and rename services exist`() {
        val fixture = DocumentV1WriteControllerTestFixture()
        val mockMvc = mvc(fixture, catalogMode = true)

        mockMvc.perform(
            multipart("/fileweft/v1/documents/document-1/versions")
                .file(file("version.txt", "version-two"))
                .param("versionNumber", "2.0"),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))

        mockMvc.perform(
            patch("/fileweft/v1/documents/document-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Renamed document\"}"),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))

        assertTrue(fixture.storage.uploads.isEmpty())
    }

    @Test
    fun `maps write failures to fixed public envelopes`() {
        val unauthenticated = DocumentV1WriteControllerTestFixture().apply { currentUser = null }
        mvc(unauthenticated)
            .perform(createRequest())
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        assertTrue(unauthenticated.storage.uploads.isEmpty())

        val forbidden = DocumentV1WriteControllerTestFixture().apply {
            authorizationDecision = AuthorizationDecision(false, "host-policy=restricted")
        }
        mvc(forbidden)
            .perform(createRequest())
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(content().string(not(containsString("host-policy"))))
        assertTrue(forbidden.storage.uploads.isEmpty())

        val missing = DocumentV1WriteControllerTestFixture()
        mvc(missing)
            .perform(
                multipart("/fileweft/v1/documents/missing-document/versions")
                    .file(file("version.txt", "version-two"))
                    .param("versionNumber", "2.0"),
            )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(content().string(not(containsString("missing-document"))))

        val conflict = DocumentV1WriteControllerTestFixture().apply { documents.seed("DOC-1") }
        mvc(conflict)
            .perform(createRequest())
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))

        val unavailable = DocumentV1WriteControllerTestFixture()
        mvc(unavailable)
            .perform(createRequest(folderId = "finance"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))
            .andExpect(content().string(not(containsString("finance"))))
        assertTrue(unavailable.storage.uploads.isEmpty())

        val failedStorage = DocumentV1WriteControllerTestFixture().apply {
            storage.failure = IllegalStateException("jdbc://internal-storage/password=secret")
        }
        mvc(failedStorage)
            .perform(createRequest())
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(content().string(not(containsString("jdbc://"))))
            .andExpect(content().string(not(containsString("password=secret"))))
    }

    @Test
    fun `write handlers do not accept tenant user or reviewer inputs`() {
        val handlerMethods = DocumentV1WriteController::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }

        assertEquals(setOf("create", "addVersion", "rename"), handlerMethods.map { it.name }.toSet())
        assertTrue(handlerMethods.none { method ->
            method.parameterTypes.any { type ->
                type == TenantContext::class.java || type == UserIdentity::class.java || type == Identifier::class.java
            }
        })
        assertTrue(handlerMethods.none { method ->
            method.parameters.any { parameter ->
                parameter.name.contains("tenant", ignoreCase = true) ||
                    parameter.name.contains("user", ignoreCase = true) ||
                    parameter.name.contains("reviewer", ignoreCase = true)
            }
        })
    }

    private fun mvc(
        fixture: DocumentV1WriteControllerTestFixture,
        traceId: String? = null,
        catalogMode: Boolean = false,
    ): MockMvc =
        MockMvcBuilders.standaloneSetup(
            DocumentV1WriteController(
                documents = fixture.facade(catalogMode),
                responses = V1ApiResponseFactory(),
                traceContextProvider = traceId?.let(DocumentV1WriteControllerTestFixture::traceProvider),
            ),
        )
            // A plain ObjectMapper proves RenameDocumentRequest needs no jackson-module-kotlin.
            .setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper()))
            .build()

    private fun createRequest(folderId: String? = null) = multipart("/fileweft/v1/documents")
        .file(file("draft.txt", "draft-content"))
        .param("documentNumber", "DOC-1")
        .param("title", "Draft")
        .apply { folderId?.let { param("folderId", it) } }

    private fun file(name: String, content: String): MockMultipartFile =
        MockMultipartFile("file", name, "text/plain", content.toByteArray())
}

internal class DocumentV1WriteControllerTestFixture(
    identifiers: List<String> = listOf(
        "document-1", "file-1", "asset-1", "version-1", "file-2", "version-2",
    ),
) {
    var currentUser: UserIdentity? = UserIdentity(Identifier("user-a"), "User A")
    var authorizationDecision: AuthorizationDecision = AuthorizationDecision(true)
    val documents = MemoryDocuments()
    val storage = RecordingStorage()
    private val tenants = object : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
    }
    private val users = object : UserRealmProvider {
        override fun currentUser(): UserIdentity? = this@DocumentV1WriteControllerTestFixture.currentUser

        override fun findUser(userId: Identifier): UserIdentity? = null
    }
    private val authorization = object : AuthorizationProvider {
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision = authorizationDecision
    }
    val drafts = DocumentDraftService(
        tenantProvider = tenants,
        userRealmProvider = users,
        authorizationProvider = authorization,
        storageAdapter = storage,
        documentRepository = documents,
        fileObjectRepository = MemoryFileObjects(),
        fileAssetRepository = MemoryAssets(),
        identifierGenerator = SequenceIdentifiers(identifiers),
        transaction = DirectTransaction,
    )

    fun facade(catalogMode: Boolean = false): DocumentApiWriteFacade = if (catalogMode) {
        DocumentApiWriteFacade(
            drafts = drafts,
            catalogDrafts = DocumentCatalogDraftService(
                drafts,
                DocumentCatalogAccessService(tenants, users, authorization, CatalogProvider),
            ),
        )
    } else {
        DocumentApiWriteFacade(drafts)
    }

    class MemoryDocuments : DocumentRepository {
        val values = linkedMapOf<Identifier, Document>()

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            values[documentId]?.takeIf { document -> document.tenantId == tenantId }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? = findById(tenantId, documentId)

        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? =
            values.values.firstOrNull { document ->
                document.tenantId == tenantId && document.documentNumber == documentNumber
            }

        override fun save(document: Document) {
            values[document.id] = document
        }

        fun seed(documentNumber: String) {
            val document = Document(
                id = Identifier("existing-document"),
                tenantId = Identifier("tenant-a"),
                assetId = Identifier("existing-asset"),
                documentNumber = documentNumber,
                title = "Existing document",
            )
            save(document)
        }
    }

    class RecordingStorage : StorageAdapter {
        val uploads = mutableListOf<StorageUploadRequest>()
        val contents = mutableListOf<ByteArray>()
        var failure: Exception? = null

        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
            failure?.let { throw it }
            uploads += request
            contents += content.readBytes()
            return StoredObject(
                location = StorageObjectLocation("memory", "objects/${uploads.size}"),
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

    private class MemoryFileObjects : FileObjectRepository {
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? = null

        override fun save(fileObject: FileObject) = Unit
    }

    private class MemoryAssets : FileAssetRepository {
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? = null

        override fun save(fileAsset: FileAsset) = Unit
    }

    private class SequenceIdentifiers(values: List<String>) : IdentifierGenerator {
        private val remaining = ArrayDeque(values.map(::Identifier))

        constructor(vararg values: String) : this(values.toList())

        override fun nextId(): Identifier = remaining.removeFirst()
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private object CatalogProvider : DocumentCatalogProvider {
        override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = listOf(
            DocumentCatalogFolder("finance", null, "Finance"),
        )
    }

    companion object {
        fun traceProvider(traceId: String): TraceContextProvider = object : TraceContextProvider {
            override fun currentTraceContext(): TraceContext = TraceContext(Identifier(traceId))
        }
    }
}
