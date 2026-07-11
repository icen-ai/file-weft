package com.fileweft.web.spring.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.catalog.DocumentCatalogAccessService
import com.fileweft.application.catalog.DocumentCatalogDraftService
import com.fileweft.application.catalog.DocumentCatalogMutationService
import com.fileweft.application.document.DocumentDetailView
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.application.document.DocumentPageRequest
import com.fileweft.application.document.DocumentPageResult
import com.fileweft.application.document.DocumentQueryRepository
import com.fileweft.application.document.DocumentQueryService
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.context.TraceContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.file.FileAsset
import com.fileweft.domain.file.FileAssetMutationRepository
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
import com.fileweft.web.spring.boot3.v1.document.V1DocumentWriteController
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.ArrayDeque
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class V1DocumentWriteControllerTest {
    @Test
    fun `creates a draft from exact multipart values and returns a minimal traced result with Location`() {
        val fixture = V1DocumentWriteTestFixture()
        val payload = "公开证明内容".toByteArray(StandardCharsets.UTF_8)
        val file = TrackingMultipartFile("file", "证明.pdf", "application/pdf", payload)

        mockMvc(fixture)
            .perform(
                multipart(DOCUMENTS_PATH)
                    .file(file)
                    .param("documentNumber", "DOC-1")
                    .param("title", "清税证明")
                    .param("fileName", "spoofed.exe")
                    .param("contentType", "text/html")
                    .param("contentLength", "999999"),
            )
            .andExpect(status().isCreated)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.LOCATION, "/fileweft/v1/documents/document-1"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").value("trace-write-3"))
            .andExpect(jsonPath("$.data.documentId").value("document-1"))
            .andExpect(jsonPath("$.data.versionId").value("version-1"))
            .andExpect(jsonPath("$.data.documentNumber").doesNotExist())
            .andExpect(jsonPath("$.data.title").doesNotExist())
            .andExpect(jsonPath("$.data.lifecycleState").doesNotExist())
            .andExpect(jsonPath("$.data.tenantId").doesNotExist())
            .andExpect(jsonPath("$.data.fileObjectId").doesNotExist())
            .andExpect(jsonPath("$.data.storagePath").doesNotExist())
            .andExpect(jsonPath("$.data.contentHash").doesNotExist())
            .andExpect(jsonPath("$.success").doesNotExist())
            .andExpect(jsonPath("$.failure").doesNotExist())

        val upload = fixture.storage.uploads.single()
        assertEquals("DOC-1", fixture.documents.values.getValue(Identifier("document-1")).documentNumber)
        assertEquals("清税证明", fixture.documents.values.getValue(Identifier("document-1")).title)
        assertEquals("证明.pdf", upload.objectName)
        assertEquals(payload.size.toLong(), upload.contentLength)
        assertEquals("application/pdf", upload.contentType)
        assertContentEquals(payload, fixture.storage.contents.single())
        assertEquals(1, file.openCount)
        assertTrue(file.openedStreams.single().closed)
    }

    @Test
    fun `adds a version from its file part and returns the document Location`() {
        val fixture = V1DocumentWriteTestFixture(listOf("file-2", "version-2"))
        fixture.documents.seed()
        val payload = byteArrayOf(1, 2, 3, 4)
        val file = TrackingMultipartFile("file", "revision.docx", WORD_CONTENT_TYPE, payload)

        mockMvc(fixture)
            .perform(
                multipart("$DOCUMENTS_PATH/document-1/versions")
                    .file(file)
                    .param("versionNumber", "2.0")
                    .param("fileName", "ignored.txt"),
            )
            .andExpect(status().isCreated)
            .andExpect(header().string(HttpHeaders.LOCATION, "/fileweft/v1/documents/document-1"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.documentId").value("document-1"))
            .andExpect(jsonPath("$.data.versionId").value("version-2"))

        val upload = fixture.storage.uploads.single()
        assertEquals("revision.docx", upload.objectName)
        assertEquals(payload.size.toLong(), upload.contentLength)
        assertEquals(WORD_CONTENT_TYPE, upload.contentType)
        assertEquals("version-2", fixture.documents.values.getValue(Identifier("document-1")).currentVersionId?.value)
        assertContentEquals(payload, fixture.storage.contents.single())
        assertEquals(1, file.openCount)
        assertTrue(file.openedStreams.single().closed)
    }

    @Test
    fun `keeps a committed creation successful when its server id is not safe for a Location`() {
        val fixture = V1DocumentWriteTestFixture(
            listOf("document/unsafe", "file-1", "asset-1", "version-1"),
        )
        val file = trackingFile("proof.pdf")

        mockMvc(fixture)
            .perform(
                multipart(DOCUMENTS_PATH)
                    .file(file)
                    .param("documentNumber", "DOC-1")
                    .param("title", "Proof"),
            )
            .andExpect(status().isCreated)
            .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.documentId").value("document/unsafe"))

        assertTrue(fixture.documents.values.containsKey(Identifier("document/unsafe")))
        assertTrue(file.openedStreams.single().closed)
    }

    @Test
    fun `converts the nullable rename transport bean into a validated command`() {
        val fixture = V1DocumentWriteTestFixture(emptyList())
        fixture.documents.seed(title = "Original")

        mockMvc(fixture)
            .perform(
                patch("$DOCUMENTS_PATH/document-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"已重命名\"}"),
            )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").value("trace-write-3"))
            .andExpect(jsonPath("$.data.documentId").value("document-1"))
            .andExpect(jsonPath("$.data.versionId").isEmpty())

        assertEquals("已重命名", fixture.documents.values.getValue(Identifier("document-1")).title)
    }

    @Test
    fun `rejects missing duplicate and path-shaped multipart values before opening a file`() {
        val fixture = V1DocumentWriteTestFixture()
        val mvc = mockMvc(fixture)

        val missingTextFile = trackingFile("proof.pdf")
        mvc.perform(
            multipart(DOCUMENTS_PATH)
                .file(missingTextFile)
                .param("title", "Proof"),
        ).andExpectInvalidRequest()
        assertEquals(0, missingTextFile.openCount)

        val duplicateTextFile = trackingFile("proof.pdf")
        mvc.perform(
            multipart(DOCUMENTS_PATH)
                .file(duplicateTextFile)
                .param("documentNumber", "DOC-1", "DOC-2")
                .param("title", "Proof"),
        ).andExpectInvalidRequest()
        assertEquals(0, duplicateTextFile.openCount)

        val duplicateFolderFile = trackingFile("proof.pdf")
        mvc.perform(
            multipart(DOCUMENTS_PATH)
                .file(duplicateFolderFile)
                .param("documentNumber", "DOC-1")
                .param("title", "Proof")
                .param("folderId", "finance", "legal"),
        ).andExpectInvalidRequest()
        assertEquals(0, duplicateFolderFile.openCount)

        val firstFile = trackingFile("first.pdf")
        val secondFile = trackingFile("second.pdf")
        mvc.perform(
            multipart(DOCUMENTS_PATH)
                .file(firstFile)
                .file(secondFile)
                .param("documentNumber", "DOC-1")
                .param("title", "Proof"),
        ).andExpectInvalidRequest()
        assertEquals(0, firstFile.openCount)
        assertEquals(0, secondFile.openCount)

        val duplicateVersionFile = trackingFile("revision.pdf")
        mvc.perform(
            multipart("$DOCUMENTS_PATH/document-1/versions")
                .file(duplicateVersionFile)
                .param("versionNumber", "2.0", "3.0"),
        ).andExpectInvalidRequest()
        assertEquals(0, duplicateVersionFile.openCount)

        val pathFile = trackingFile("../private/proof.pdf")
        mvc.perform(
            multipart(DOCUMENTS_PATH)
                .file(pathFile)
                .param("documentNumber", "DOC-1")
                .param("title", "Proof"),
        ).andExpectInvalidRequest()
        assertEquals(0, pathFile.openCount)

        mvc.perform(
            multipart(DOCUMENTS_PATH)
                .param("documentNumber", "DOC-1")
                .param("title", "Proof"),
        ).andExpectInvalidRequest()

        assertTrue(fixture.storage.uploads.isEmpty())
        assertTrue(fixture.documents.values.isEmpty())
    }

    @Test
    fun `maps absent catalog support to a fixed 503 without invoking draft creation`() {
        val fixture = V1DocumentWriteTestFixture()
        val file = trackingFile("proof.pdf")

        val body = mockMvc(fixture)
            .perform(
                multipart(DOCUMENTS_PATH)
                    .file(file)
                    .param("documentNumber", "DOC-1")
                    .param("title", "Proof")
                    .param("folderId", "finance"),
            )
            .andExpect(status().isServiceUnavailable)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("The requested feature is unavailable."))
            .andReturn()
            .response
            .contentAsString

        assertFalse(body.contains("finance"))
        assertEquals(1, file.openCount)
        assertTrue(file.openedStreams.single().closed)
        assertTrue(fixture.storage.uploads.isEmpty())
        assertTrue(fixture.documents.values.isEmpty())
    }

    @Test
    fun `creates versions and renames when both catalog mutation capabilities are installed`() {
        val fixture = V1DocumentWriteTestFixture(
            listOf("document-1", "file-1", "asset-1", "version-1", "file-2", "version-2"),
        )
        val mvc = mockMvc(
            fixture,
            fixture.catalogDraftService(),
            fixture.catalogMutationService(),
        )

        mvc.perform(
            multipart(DOCUMENTS_PATH)
                .file(trackingFile("proof.pdf"))
                .param("documentNumber", "DOC-1")
                .param("title", "Original")
                .param("folderId", "finance"),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.versionId").value("version-1"))

        mvc.perform(
            multipart("$DOCUMENTS_PATH/document-1/versions")
                .file(trackingFile("revision.pdf"))
                .param("versionNumber", "2.0"),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.versionId").value("version-2"))

        mvc.perform(
            patch("$DOCUMENTS_PATH/document-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Renamed\"}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.documentId").value("document-1"))

        assertEquals(2, fixture.storage.uploads.size)
        assertEquals("version-2", fixture.documents.values.getValue(Identifier("document-1")).currentVersionId?.value)
        assertEquals("Renamed", fixture.documents.values.getValue(Identifier("document-1")).title)
    }

    @Test
    fun `fails closed without extra uploads when catalog mutation capability is incomplete`() {
        val fixture = V1DocumentWriteTestFixture()
        val file = trackingFile("revision.pdf")
        val mvc = mockMvc(fixture, fixture.catalogDraftService())

        mvc.perform(
            multipart(DOCUMENTS_PATH)
                .file(trackingFile("proof.pdf"))
                .param("documentNumber", "DOC-1")
                .param("title", "Original")
                .param("folderId", "finance"),
        ).andExpect(status().isCreated)

        mvc.perform(
            multipart("$DOCUMENTS_PATH/document-1/versions")
                .file(file)
                .param("versionNumber", "2.0"),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))

        mvc.perform(
            patch("$DOCUMENTS_PATH/document-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Renamed\"}"),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))

        assertEquals(1, file.openCount)
        assertTrue(file.openedStreams.single().closed)
        assertEquals(1, fixture.storage.uploads.size)
        assertEquals("Original", fixture.documents.values.getValue(Identifier("document-1")).title)
    }

    @Test
    fun `maps authentication authorization not-found conflict and unexpected failures to safe envelopes`() {
        val unauthenticated = V1DocumentWriteTestFixture().apply { currentUser = null }
        mockMvc(unauthenticated)
            .perform(createRequest())
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.traceId").value("trace-write-3"))
        assertTrue(unauthenticated.storage.uploads.isEmpty())

        val forbidden = V1DocumentWriteTestFixture().apply {
            authorizationDecision = AuthorizationDecision(false, "internal-leak-policy")
        }
        val forbiddenBody = mockMvc(forbidden)
            .perform(createRequest())
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andReturn()
            .response
            .contentAsString
        assertFalse(forbiddenBody.contains("internal-leak"))
        assertTrue(forbidden.storage.uploads.isEmpty())

        val missing = V1DocumentWriteTestFixture(listOf("file-2", "version-2"))
        val missingBody = mockMvc(missing)
            .perform(
                multipart("$DOCUMENTS_PATH/internal-leak-document/versions")
                    .file(trackingFile("revision.pdf"))
                    .param("versionNumber", "2.0"),
            )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andReturn()
            .response
            .contentAsString
        assertFalse(missingBody.contains("internal-leak"))
        assertEquals(1, missing.storage.deletions.size)

        val conflict = V1DocumentWriteTestFixture()
        conflict.documents.seed(documentNumber = "DOC-1")
        mockMvc(conflict)
            .perform(createRequest())
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
            .andExpect(jsonPath("$.message").value("Request conflicts with the current resource state."))
        assertTrue(conflict.storage.uploads.isEmpty())

        val failedStorage = V1DocumentWriteTestFixture().apply {
            storage.failure = IllegalStateException("jdbc://internal-leak-storage/password")
        }
        val failedBody = mockMvc(failedStorage)
            .perform(createRequest())
            .andExpect(status().isInternalServerError)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
            .andReturn()
            .response
            .contentAsString
        assertFalse(failedBody.contains("jdbc://"))
        assertFalse(failedBody.contains("password"))
    }

    @Test
    fun `maps an absent rename body or title through the fixed 400 envelope`() {
        val fixture = V1DocumentWriteTestFixture(emptyList())
        fixture.documents.seed()
        val mvc = mockMvc(fixture)

        mvc.perform(
            patch("$DOCUMENTS_PATH/document-1")
                .contentType(MediaType.APPLICATION_JSON),
        ).andExpectInvalidRequest()

        mvc.perform(
            patch("$DOCUMENTS_PATH/document-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        ).andExpectInvalidRequest()

        assertEquals("Original", fixture.documents.values.getValue(Identifier("document-1")).title)
    }

    @Test
    fun `public write handlers accept no tenant user role or reviewer input`() {
        val endpointMethods = V1DocumentWriteController::class.java.declaredMethods.filter { method ->
            method.isAnnotationPresent(PostMapping::class.java) || method.isAnnotationPresent(PatchMapping::class.java)
        }
        val requestParameterNames = endpointMethods
            .flatMap { method -> method.parameters.toList() }
            .flatMap { parameter -> parameter.annotations.toList() }
            .filterIsInstance<RequestParam>()
            .map { annotation -> annotation.name.lowercase() }

        assertTrue(endpointMethods.isNotEmpty())
        assertTrue(requestParameterNames.none { name ->
            name.contains("tenant") || name.contains("user") || name.contains("role") || name.contains("reviewer")
        })
        assertTrue(endpointMethods.flatMap { it.parameterTypes.toList() }.none { type ->
            val name = type.name.lowercase()
            name.contains("tenantprovider") || name.contains("userrealm") || name.contains("authorizationprovider")
        })
    }

    private fun mockMvc(
        fixture: V1DocumentWriteTestFixture,
        catalogDrafts: DocumentCatalogDraftService? = null,
        catalogMutations: DocumentCatalogMutationService? = null,
    ): MockMvc = MockMvcBuilders.standaloneSetup(
        V1DocumentWriteController(
            documents = DocumentApiWriteFacade(fixture.drafts, catalogDrafts, catalogMutations),
            responses = V1ApiResponseFactory(),
            traceContextProvider = TRACE_CONTEXT_PROVIDER,
        ),
    ).setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper())).build()

    private fun createRequest() = multipart(DOCUMENTS_PATH)
        .file(trackingFile("proof.pdf"))
        .param("documentNumber", "DOC-1")
        .param("title", "Proof")

    private fun trackingFile(originalFilename: String): TrackingMultipartFile =
        TrackingMultipartFile("file", originalFilename, "application/pdf", byteArrayOf(1, 2, 3))

    private fun ResultActions.andExpectInvalidRequest() {
        andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Request is invalid."))
    }

    private class TrackingMultipartFile(
        name: String,
        originalFilename: String,
        contentType: String,
        private val payload: ByteArray,
    ) : MockMultipartFile(name, originalFilename, contentType, payload) {
        val openedStreams = mutableListOf<CloseTrackingInputStream>()
        var openCount: Int = 0
            private set

        override fun getInputStream(): InputStream {
            openCount++
            return CloseTrackingInputStream(payload).also(openedStreams::add)
        }
    }

    private class CloseTrackingInputStream(content: ByteArray) : ByteArrayInputStream(content) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        const val DOCUMENTS_PATH = "/fileweft/v1/documents"
        const val WORD_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        val TRACE_CONTEXT_PROVIDER = object : TraceContextProvider {
            override fun currentTraceContext(): TraceContext = TraceContext(Identifier("trace-write-3"))
        }
    }
}

internal class V1DocumentWriteTestFixture(
    identifierValues: List<String> = listOf("document-1", "file-1", "asset-1", "version-1"),
) {
    var currentUser: UserIdentity? = UserIdentity(Identifier("user-1"), "User One")
    var authorizationDecision: AuthorizationDecision = AuthorizationDecision(true)
    val documents = MemoryDocuments()
    val storage = RecordingStorage()
    private val assets = MemoryAssets()

    private val tenantProvider = object : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
    }
    private val userRealmProvider = object : UserRealmProvider {
        override fun currentUser(): UserIdentity? = this@V1DocumentWriteTestFixture.currentUser
        override fun findUser(userId: Identifier): UserIdentity? = null
    }
    private val authorizationProvider = object : AuthorizationProvider {
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision = authorizationDecision
    }
    private val catalogProvider = object : DocumentCatalogProvider {
        override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
            listOf(DocumentCatalogFolder("finance", null, "Finance"))
    }
    private val catalogAccess = DocumentCatalogAccessService(
        tenantProvider = tenantProvider,
        userRealmProvider = userRealmProvider,
        authorizationProvider = authorizationProvider,
        catalog = catalogProvider,
    )

    val drafts = DocumentDraftService(
        tenantProvider = tenantProvider,
        userRealmProvider = userRealmProvider,
        authorizationProvider = authorizationProvider,
        storageAdapter = storage,
        documentRepository = documents,
        fileObjectRepository = MemoryFileObjects(),
        fileAssetRepository = assets,
        identifierGenerator = SequenceIdentifiers(identifierValues),
        transaction = DirectTransaction,
    )

    fun catalogDraftService(): DocumentCatalogDraftService = DocumentCatalogDraftService(drafts, catalogAccess)

    fun catalogMutationService(): DocumentCatalogMutationService = DocumentCatalogMutationService(
        drafts = drafts,
        catalogAccess = catalogAccess,
    )

    fun queryService(): DocumentQueryService = DocumentQueryService(
        tenantProvider = tenantProvider,
        userRealmProvider = userRealmProvider,
        authorizationProvider = authorizationProvider,
        queries = object : DocumentQueryRepository {
            override fun findDetail(
                tenantId: Identifier,
                documentId: Identifier,
                folderReadScope: DocumentFolderReadScope?,
            ): DocumentDetailView? = null

            override fun findPage(
                tenantId: Identifier,
                request: DocumentPageRequest,
                folderReadScope: DocumentFolderReadScope?,
            ): DocumentPageResult = DocumentPageResult(emptyList())
        },
        transaction = DirectTransaction,
    )

    class MemoryDocuments : DocumentRepository {
        val values = linkedMapOf<Identifier, Document>()

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            values[documentId]?.takeIf { document -> document.tenantId == tenantId }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? =
            findById(tenantId, documentId)

        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? =
            values.values.firstOrNull { document ->
                document.tenantId == tenantId && document.documentNumber == documentNumber
            }

        override fun save(document: Document) {
            values[document.id] = document
        }

        fun seed(
            documentId: String = "document-1",
            documentNumber: String = "DOC-SEED",
            title: String = "Original",
        ) {
            val id = Identifier(documentId)
            save(
                Document(
                    id = id,
                    tenantId = Identifier("tenant-1"),
                    assetId = Identifier("seed-asset"),
                    documentNumber = documentNumber,
                    title = title,
                    versions = listOf(
                        DocumentVersion(
                            id = Identifier("version-1"),
                            tenantId = Identifier("tenant-1"),
                            documentId = id,
                            versionNumber = "1.0",
                            fileObjectId = Identifier("seed-file"),
                        ),
                    ),
                    currentVersionId = Identifier("version-1"),
                ),
            )
        }
    }

    class RecordingStorage : StorageAdapter {
        val uploads = mutableListOf<StorageUploadRequest>()
        val contents = mutableListOf<ByteArray>()
        val deletions = mutableListOf<StorageObjectLocation>()
        var failure: Exception? = null

        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
            failure?.let { throw it }
            val bytes = content.readBytes()
            uploads += request
            contents += bytes
            return StoredObject(
                location = StorageObjectLocation("memory", "objects/${uploads.size}"),
                contentLength = bytes.size.toLong(),
                contentType = request.contentType,
            )
        }

        override fun download(location: StorageObjectLocation): StorageDownload = throw UnsupportedOperationException()

        override fun delete(location: StorageObjectLocation) {
            deletions += location
        }

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

    private class MemoryAssets : FileAssetMutationRepository {
        private val values = linkedMapOf<Identifier, FileAsset>()

        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
            values[fileAssetId]?.takeIf { asset -> asset.tenantId == tenantId }

        override fun findForMutation(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
            findById(tenantId, fileAssetId)

        override fun save(fileAsset: FileAsset) {
            values[fileAsset.id] = fileAsset
        }
    }

    private class SequenceIdentifiers(values: List<String>) : IdentifierGenerator {
        private val remaining = ArrayDeque(values.map(::Identifier))

        override fun nextId(): Identifier {
            check(remaining.isNotEmpty()) { "The write test fixture exhausted its identifier sequence." }
            return remaining.removeFirst()
        }
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
