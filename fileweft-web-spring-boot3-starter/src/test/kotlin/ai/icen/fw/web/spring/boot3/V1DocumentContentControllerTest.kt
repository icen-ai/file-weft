package ai.icen.fw.web.spring.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.document.DocumentDownloadService
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentMutationRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentApiDownload
import ai.icen.fw.web.runtime.v1.document.DocumentApiDownloadFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentContentController
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentContentFailureHandler
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.URI
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class V1DocumentContentControllerTest {
    @Test
    fun `streams current binary content with safe unicode headers and no internal metadata`() {
        val fixture = V1DocumentContentTestFixture()
        val mvc = mockMvc(fixture)

        val pending = mvc.perform(get(CURRENT_CONTENT_PATH))
            .andExpect(request().asyncStarted())
            .andReturn()
        val completed = mvc.perform(asyncDispatch(pending))
            .andExpect(status().isOk)
            .andExpect(content().bytes(CURRENT_BYTES))
            .andExpect(content().contentType("application/pdf"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CURRENT_BYTES.size.toLong()))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("filename=\"download.pdf\"")))
            .andExpect(
                header().string(
                    HttpHeaders.CONTENT_DISPOSITION,
                    containsString("filename*=UTF-8''%E6%B8%85%E7%A8%8E%E8%AF%81%E6%98%8E.pdf"),
                ),
            )
            .andExpect(header().doesNotExist(HttpHeaders.ETAG))
            .andExpect(header().doesNotExist("Accept-Ranges"))
            .andExpect(header().doesNotExist("Content-Range"))
            .andReturn()

        val headerNames = completed.response.headerNames.map { name -> name.lowercase() }
        assertTrue(headerNames.none { name ->
            name.contains("hash") || name.contains("storage") || name.contains("location")
        })
        assertTrue(!completed.response.getHeader(HttpHeaders.CONTENT_DISPOSITION).orEmpty().contains("legacy"))
        assertEquals(listOf(CURRENT_STORAGE_PATH), fixture.storage.requested.map { it.path })
        assertEquals(1, fixture.storage.opened.single().closeCount)
    }

    @Test
    fun `streams an explicitly selected historical version`() {
        val fixture = V1DocumentContentTestFixture()
        val mvc = mockMvc(fixture)

        val pending = mvc.perform(get(HISTORICAL_CONTENT_PATH))
            .andExpect(request().asyncStarted())
            .andReturn()
        mvc.perform(asyncDispatch(pending))
            .andExpect(status().isOk)
            .andExpect(content().bytes(HISTORICAL_BYTES))
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("archive.txt")))

        assertEquals(listOf(HISTORICAL_STORAGE_PATH), fixture.storage.requested.map { it.path })
        assertEquals(1, fixture.storage.opened.single().closeCount)
    }

    @Test
    fun `omits unverified length and falls back from an unsafe mime type`() {
        val fixture = V1DocumentContentTestFixture()
        fixture.storage.spec(CURRENT_STORAGE_PATH).apply {
            reportedContentLength = null
            contentType = "text/html; charset=UTF-8"
        }
        val mvc = mockMvc(fixture)

        val pending = mvc.perform(get(CURRENT_CONTENT_PATH))
            .andExpect(request().asyncStarted())
            .andReturn()
        mvc.perform(asyncDispatch(pending))
            .andExpect(status().isOk)
            .andExpect(content().bytes(CURRENT_BYTES))
            .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
            .andExpect(header().doesNotExist(HttpHeaders.CONTENT_LENGTH))
            .andExpect(header().doesNotExist("Accept-Ranges"))
    }

    @Test
    fun `rejects every range before authorization repositories or storage`() {
        listOf(
            CURRENT_CONTENT_PATH to "bytes=0-1",
            HISTORICAL_CONTENT_PATH to "items=0-1",
            CURRENT_CONTENT_PATH to "",
        ).forEach { (path, range) ->
            val fixture = V1DocumentContentTestFixture()
            val mvc = mockMvc(fixture)

            mvc.perform(get(path).header(HttpHeaders.RANGE, range))
                .andExpect(status().isRequestedRangeNotSatisfiable)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RANGE_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.message").value("Range requests are not supported."))
                .andExpect(jsonPath("$.traceId").value("trace-content"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Accept-Ranges"))

            assertTrue(fixture.authorizationRequests.isEmpty())
            assertTrue(fixture.documents.reads.isEmpty())
            assertTrue(fixture.storage.requested.isEmpty())
        }
    }

    @Test
    fun `rejects explicit head routes with allow get and never opens a download`() {
        listOf(CURRENT_CONTENT_PATH, HISTORICAL_CONTENT_PATH).forEach { path ->
            val fixture = V1DocumentContentTestFixture()
            val mvc = mockMvc(fixture)

            mvc.perform(head(path))
                .andExpect(status().isMethodNotAllowed)
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Method is not allowed."))

            assertTrue(fixture.authorizationRequests.isEmpty())
            assertTrue(fixture.documents.reads.isEmpty())
            assertTrue(fixture.storage.requested.isEmpty())
        }
    }

    @Test
    fun `maps authentication authorization not found and storage failures without leaking details`() {
        val unauthenticated = V1DocumentContentTestFixture().apply { currentUser = null }
        mockMvc(unauthenticated).perform(get(CURRENT_CONTENT_PATH))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.message").value("Authentication is required."))
            .andExpect(jsonPath("$.traceId").value("trace-content"))
        assertTrue(unauthenticated.storage.requested.isEmpty())

        val forbidden = V1DocumentContentTestFixture().apply { authorized = false }
        mockMvc(forbidden).perform(get(CURRENT_CONTENT_PATH))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("Access denied."))
        assertTrue(forbidden.storage.requested.isEmpty())

        val missing = V1DocumentContentTestFixture()
        mockMvc(missing).perform(get("/fileweft/v1/documents/missing-document/content"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resource was not found."))
        assertTrue(missing.storage.requested.isEmpty())

        val unavailable = V1DocumentContentTestFixture().apply {
            storage.openFailure = IllegalStateException("s3://secret-bucket/tenant-1/current is unavailable")
        }
        val result = mockMvc(unavailable).perform(get(CURRENT_CONTENT_PATH))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("CONTENT_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("Document content is unavailable."))
            .andReturn()
        assertTrue(!result.response.contentAsString.contains("secret-bucket"))
    }

    @Test
    fun `propagates stream failures after closing exactly once without invoking the json failure path`() {
        val fixture = V1DocumentContentTestFixture()
        fixture.storage.spec(CURRENT_STORAGE_PATH).failAfterBytes = 2
        val mvc = mockMvc(fixture)

        val pending = mvc.perform(get(CURRENT_CONTENT_PATH))
            .andExpect(request().asyncStarted())
            .andReturn()
        val failure = pending.getAsyncResult(2_000)

        assertIs<IOException>(failure)
        assertEquals(1, fixture.storage.opened.single().closeCount)
        assertEquals(0, fixture.traceCalls)
        assertContentEquals(CURRENT_BYTES.copyOf(2), pending.response.contentAsByteArray)
        assertTrue(!pending.response.contentAsString.contains("\"code\""))
    }

    @Test
    fun `closes an owned handle when response preparation fails`() {
        val fixture = V1DocumentContentTestFixture()
        val handle = RecordingApiDownload(verifiedContentLength = -1L)
        val method = V1DocumentContentController::class.java.declaredMethods.single { candidate ->
            candidate.name == "streamingResponse" && candidate.parameterCount == 1
        }.apply { isAccessible = true }

        val thrown = assertFailsWith<InvocationTargetException> {
            method.invoke(fixture.controller, handle)
        }

        assertIs<IllegalArgumentException>(thrown.cause)
        assertEquals(1, handle.closeCount)
    }

    @Test
    fun `propagates client output failures after closing exactly once`() {
        val fixture = V1DocumentContentTestFixture()
        val response = fixture.controller.current("document-1", null)
        val body = assertNotNull(response.body)

        assertFailsWith<IOException> {
            body.writeTo(
                object : OutputStream() {
                    override fun write(value: Int) {
                        throw IOException("simulated client disconnect")
                    }

                    override fun write(value: ByteArray, offset: Int, length: Int) {
                        throw IOException("simulated client disconnect")
                    }
                },
            )
        }

        assertEquals(1, fixture.storage.opened.single().closeCount)
        assertEquals(0, fixture.traceCalls)
    }

    @Test
    fun `content handlers accept no tenant user role or authorization inputs`() {
        val handlers = V1DocumentContentController::class.java.declaredMethods.filter { method ->
            Modifier.isPublic(method.modifiers) &&
                (method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping::class.java) ||
                    method.isAnnotationPresent(org.springframework.web.bind.annotation.RequestMapping::class.java))
        }

        assertEquals(
            setOf("current", "version", "currentHead", "versionHead"),
            handlers.map { method -> method.name }.toSet(),
        )
        assertTrue(handlers.flatMap { method -> method.parameterTypes.toList() }.none { type ->
            val name = type.name.lowercase()
            name.contains("tenant") || name.contains("userrealm") || name.contains("authorization") ||
                type == Identifier::class.java
        })
    }

    private fun mockMvc(fixture: V1DocumentContentTestFixture): MockMvc = MockMvcBuilders
        .standaloneSetup(fixture.controller)
        .setControllerAdvice(V1DocumentContentFailureHandler())
        .setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper()))
        .build()

    private class RecordingApiDownload(
        override val verifiedContentLength: Long?,
    ) : DocumentApiDownload {
        override val content: InputStream = ByteArrayInputStream(byteArrayOf(1))
        override val contentDisposition: String = "attachment; filename=\"safe.bin\""
        override val contentType: String = "application/octet-stream"
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount++
            content.close()
        }
    }

    private companion object {
        const val CURRENT_CONTENT_PATH = "/fileweft/v1/documents/document-1/content"
        const val HISTORICAL_CONTENT_PATH =
            "/fileweft/v1/documents/document-1/versions/version-1/content"
    }
}

internal class V1DocumentContentTestFixture {
    var currentUser: UserIdentity? = UserIdentity(Identifier("user-1"), "User One")
    var authorized: Boolean = true
    var traceCalls: Int = 0
        private set
    val authorizationRequests = mutableListOf<AuthorizationRequest>()
    val documents = MemoryDocuments()
    val files = MemoryFiles()
    val storage = RecordingDownloadStorage()

    val downloadService = DocumentDownloadService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(TENANT_ID)
        },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity? = this@V1DocumentContentTestFixture.currentUser
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
                authorizationRequests += request
                return AuthorizationDecision(authorized)
            }
        },
        documentRepository = documents,
        fileObjectRepository = files,
        storageAdapter = storage,
        transaction = DirectTransaction,
    )
    val facade = DocumentApiDownloadFacade(downloadService)
    val controller = V1DocumentContentController(
        documents = facade,
        responses = V1ApiResponseFactory(),
        traceContextProvider = object : TraceContextProvider {
            override fun currentTraceContext(): TraceContext {
                traceCalls++
                return TraceContext(Identifier("trace-content"))
            }
        },
    )

    init {
        val historicalVersion = DocumentVersion(
            Identifier("version-1"), TENANT_ID, DOCUMENT_ID, "1.0", Identifier("file-1"),
        )
        val currentVersion = DocumentVersion(
            Identifier("version-2"), TENANT_ID, DOCUMENT_ID, "2.0", Identifier("file-2"),
        )
        documents.values[DOCUMENT_ID] = Document(
            id = DOCUMENT_ID,
            tenantId = TENANT_ID,
            assetId = Identifier("asset-1"),
            documentNumber = "DOC-1",
            title = "Tax certificate",
            versions = listOf(historicalVersion, currentVersion),
            currentVersionId = currentVersion.id,
        )
        files.values[historicalVersion.fileObjectId] = FileObject(
            historicalVersion.fileObjectId,
            TENANT_ID,
            "archive.txt",
            HISTORICAL_BYTES.size.toLong(),
            "memory",
            HISTORICAL_STORAGE_PATH,
            "text/plain",
            "sha256:must-not-leak",
        )
        files.values[currentVersion.fileObjectId] = FileObject(
            currentVersion.fileObjectId,
            TENANT_ID,
            "C:\\legacy\\清税证明.pdf",
            CURRENT_BYTES.size.toLong(),
            "memory",
            CURRENT_STORAGE_PATH,
            "application/pdf",
            "sha256:must-not-leak",
        )
        storage.register(HISTORICAL_STORAGE_PATH, HISTORICAL_BYTES, "text/plain")
        storage.register(CURRENT_STORAGE_PATH, CURRENT_BYTES, "application/pdf")
    }

    internal class MemoryDocuments : DocumentMutationRepository {
        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? = findById(tenantId, documentId)
        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? = null

        val values = linkedMapOf<Identifier, Document>()
        val reads = mutableListOf<Identifier>()

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? {
            reads += documentId
            return values[documentId]?.takeIf { document -> document.tenantId == tenantId }
        }

        override fun save(document: Document) = Unit
    }

    internal class MemoryFiles : FileObjectRepository {
        val values = linkedMapOf<Identifier, FileObject>()

        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? =
            values[fileObjectId]?.takeIf { file -> file.tenantId == tenantId }

        override fun save(fileObject: FileObject) = Unit
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val DOCUMENT_ID = Identifier("document-1")
    }
}

internal class RecordingDownloadStorage : StorageAdapter {
    data class ContentSpec(
        val bytes: ByteArray,
        var reportedContentLength: Long?,
        var contentType: String?,
        var failAfterBytes: Int? = null,
    )

    private val contents = linkedMapOf<String, ContentSpec>()
    val requested = mutableListOf<StorageObjectLocation>()
    val opened = mutableListOf<CloseTrackingInputStream>()
    var openFailure: Exception? = null

    fun register(path: String, bytes: ByteArray, contentType: String?) {
        contents[path] = ContentSpec(bytes, bytes.size.toLong(), contentType)
    }

    fun spec(path: String): ContentSpec = contents.getValue(path)

    override fun download(location: StorageObjectLocation): StorageDownload {
        requested += location
        openFailure?.let { throw it }
        val spec = contents.getValue(location.path)
        val stream = CloseTrackingInputStream(spec.bytes, spec.failAfterBytes)
        opened += stream
        return StorageDownload(stream, spec.reportedContentLength, spec.contentType)
    }

    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject =
        throw UnsupportedOperationException()

    override fun delete(location: StorageObjectLocation) = Unit
    override fun exists(location: StorageObjectLocation): Boolean = true
    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = URI.create("memory://content")
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

internal class CloseTrackingInputStream(
    content: ByteArray,
    private val failAfterBytes: Int? = null,
) : ByteArrayInputStream(content) {
    var closeCount: Int = 0
        private set
    private var emitted: Int = 0

    override fun read(): Int {
        failIfRequired()
        return super.read().also { value -> if (value >= 0) emitted++ }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        failIfRequired()
        val permitted = failAfterBytes?.let { limit -> minOf(length, limit - emitted) } ?: length
        return super.read(target, offset, permitted).also { count -> if (count > 0) emitted += count }
    }

    override fun close() {
        closeCount++
        super.close()
    }

    private fun failIfRequired() {
        if (failAfterBytes != null && emitted >= failAfterBytes) {
            throw IOException("simulated content stream failure")
        }
    }
}

private val CURRENT_BYTES = byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte())
private val HISTORICAL_BYTES = "historical-content".toByteArray()
private const val CURRENT_STORAGE_PATH = "tenant-1/internal/current-object"
private const val HISTORICAL_STORAGE_PATH = "tenant-1/internal/historical-object"
