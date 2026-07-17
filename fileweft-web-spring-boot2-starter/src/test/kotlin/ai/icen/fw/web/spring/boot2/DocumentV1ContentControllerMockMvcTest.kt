package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.document.DocumentDownloadService
import ai.icen.fw.application.retention.DeletionVisibilityFence
import ai.icen.fw.application.retention.DeletionVisibilityQuery
import ai.icen.fw.application.retention.DeletionVisibilityQuerySource
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
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
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentApiDownload
import ai.icen.fw.web.runtime.v1.document.DocumentApiDownloadFacade
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.URI
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentV1ContentControllerMockMvcTest {
    @Test
    fun `uses Spring MVC asynchronous streaming for successful binary content`() {
        val fixture = DocumentV1ContentControllerTestFixture()
        val mockMvc = mvc(fixture)

        val pending = mockMvc.perform(get("/fileweft/v1/documents/document-1/content"))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(pending))
            .andExpect(status().isOk)
            .andExpect(content().bytes(fixture.payload))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().doesNotExist(HttpHeaders.ETAG))
            .andExpect(header().doesNotExist("Accept-Ranges"))

        assertEquals(1, fixture.storage.streams.single().closeCount)
    }

    @Test
    fun `streams current and selected versions with safe headers and unicode disposition`() {
        val fixture = DocumentV1ContentControllerTestFixture()
        val controller = controller(fixture)

        val current = controller.currentContent("document-1", null)
        assertSafeContentHeaders(current, fixture.payload.size.toLong())
        val currentOutput = ByteArrayOutputStream()
        // Writing through the body is intentionally explicit: this is the
        // caller-owned stream lifecycle that MVC executes asynchronously.
        stream(current).writeTo(currentOutput)
        assertEquals(fixture.payload.toList(), currentOutput.toByteArray().toList())

        val selectedOutput = ByteArrayOutputStream()
        val selected = controller.versionContent("document-1", "version-1", null)
        stream(selected).writeTo(selectedOutput)
        assertEquals(fixture.payload.toList(), selectedOutput.toByteArray().toList())

        assertEquals(
            listOf(
                StorageObjectLocation("memory", "tenant-1/file-2"),
                StorageObjectLocation("memory", "tenant-1/file-1"),
            ),
            fixture.storage.requested,
        )
        assertEquals(listOf(1, 1), fixture.storage.streams.map { it.closeCount })
    }

    @Test
    fun `omits Content-Length when storage did not verify it and falls back for unsafe media types`() {
        val unverified = DocumentV1ContentControllerTestFixture(reportedContentLength = null)
        val response = controller(unverified).currentContent("document-1", null)

        assertNull(response.headers.getFirst(HttpHeaders.CONTENT_LENGTH))
        assertEquals(MediaType.APPLICATION_PDF, response.headers.contentType)
        stream(response).writeTo(ByteArrayOutputStream())

        val unsafeType = DocumentV1ContentControllerTestFixture(storageContentType = "text/html")
        val safeResponse = controller(unsafeType).currentContent("document-1", null)

        assertEquals(MediaType.APPLICATION_OCTET_STREAM, safeResponse.headers.contentType)
        stream(safeResponse).writeTo(ByteArrayOutputStream())
    }

    @Test
    fun `maps authentication authorization missing and unavailable downloads to fixed protected JSON`() {
        val unauthenticated = DocumentV1ContentControllerTestFixture(authenticated = false)
        mvc(unauthenticated, "trace-content")
            .perform(get("/fileweft/v1/documents/document-1/content"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.message").value("Authentication is required."))
            .andExpect(jsonPath("$.traceId").value("trace-content"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        assertTrue(unauthenticated.storage.requested.isEmpty())

        val forbidden = DocumentV1ContentControllerTestFixture(authorized = false)
        mvc(forbidden)
            .perform(get("/fileweft/v1/documents/document-1/content"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(content().string(not(containsString("private host policy"))))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        assertTrue(forbidden.storage.requested.isEmpty())

        val missing = DocumentV1ContentControllerTestFixture(documentAvailable = false)
        mvc(missing)
            .perform(get("/fileweft/v1/documents/private-document/content"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(content().string(not(containsString("private-document"))))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        assertTrue(missing.storage.requested.isEmpty())

        val unavailable = DocumentV1ContentControllerTestFixture(
            storageFailure = IllegalStateException("s3://private-bucket/tenant-1/file-2"),
        )
        mvc(unavailable)
            .perform(get("/fileweft/v1/documents/document-1/content"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("CONTENT_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("Document content is unavailable."))
            .andExpect(content().string(not(containsString("s3://"))))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        assertEquals(1, unavailable.storage.requested.size)
    }

    @Test
    fun `rejects every Range header before application download and makes both HEAD routes fixed 405`() {
        val fixture = DocumentV1ContentControllerTestFixture()
        val mockMvc = mvc(fixture)

        mockMvc.perform(
            get("/fileweft/v1/documents/document-1/content")
                .header(HttpHeaders.RANGE, "bytes=0-3"),
        )
            .andExpect(status().isRequestedRangeNotSatisfiable)
            .andExpect(jsonPath("$.code").value("RANGE_NOT_SUPPORTED"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))

        mockMvc.perform(
            get("/fileweft/v1/documents/document-1/versions/version-1/content")
                .header(HttpHeaders.RANGE, ""),
        )
            .andExpect(status().isRequestedRangeNotSatisfiable)
            .andExpect(jsonPath("$.code").value("RANGE_NOT_SUPPORTED"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))

        mockMvc.perform(head("/fileweft/v1/documents/document-1/content"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))

        mockMvc.perform(head("/fileweft/v1/documents/document-1/versions/version-1/content"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))

        assertTrue(fixture.storage.requested.isEmpty())
        assertTrue(fixture.storage.streams.isEmpty())
    }

    @Test
    fun `closes the handle when response construction fails before streaming`() {
        val controller = controller(DocumentV1ContentControllerTestFixture())
        val malicious = TestDocumentApiDownload(
            contentType = "application/pdf",
            verifiedContentLength = -1,
        )
        val method = DocumentV1ContentController::class.java.declaredMethods.single { candidate ->
            candidate.name == "download" && candidate.parameterCount == 2
        }.apply { isAccessible = true }
        val opener: () -> DocumentApiDownload = { malicious }

        val thrown = assertFailsWith<InvocationTargetException> {
            method.invoke(controller, null, opener)
        }
        val transport = assertNotNull(thrown.cause as? DocumentV1ContentTransportFailure)
        val failure = DocumentV1ContentControllerAdvice().contentFailure(transport)

        assertEquals(1, malicious.closeCount)
        assertEquals(400, failure.statusCodeValue)
        assertEquals("INVALID_REQUEST", (failure.body as ApiResponse<*>).code)
        assertEquals("private, no-store", failure.headers.getFirst(HttpHeaders.CACHE_CONTROL))
    }

    @Test
    fun `propagates stream output failures while closing exactly once without a JSON rewrite`() {
        val fixture = DocumentV1ContentControllerTestFixture()
        val response = controller(fixture).currentContent("document-1", null)

        assertFailsWith<IOException> {
            stream(response).writeTo(FailingOutputStream())
        }

        assertEquals(1, fixture.storage.streams.single().closeCount)
        assertEquals(MediaType.APPLICATION_PDF, response.headers.contentType)
        assertNull(response.headers.getFirst(HttpHeaders.ETAG))
        assertNull(response.headers.getFirst("Accept-Ranges"))
    }

    @Test
    fun `propagates source copy failures while closing the owned handle exactly once`() {
        val controller = controller(DocumentV1ContentControllerTestFixture())
        val download = TestDocumentApiDownload(content = FailingInputStream())
        val method = DocumentV1ContentController::class.java.declaredMethods.single { candidate ->
            candidate.name == "stream" && candidate.parameterCount == 2
        }.apply { isAccessible = true }

        val thrown = assertFailsWith<InvocationTargetException> {
            method.invoke(controller, download, ByteArrayOutputStream())
        }

        assertTrue(thrown.cause is IOException)
        assertEquals(1, download.closeCount)
    }

    @Test
    fun `content handlers accept no tenant user or reviewer inputs`() {
        val handlerMethods = DocumentV1ContentController::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }

        assertEquals(
            setOf("currentContent", "versionContent", "currentContentHead", "versionContentHead"),
            handlerMethods.map { method -> method.name }.toSet(),
        )
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

    private fun controller(
        fixture: DocumentV1ContentControllerTestFixture,
        traceId: String? = null,
    ): DocumentV1ContentController =
        DocumentV1ContentController(
            documents = fixture.facade,
            responses = V1ApiResponseFactory(),
            traceContextProvider = traceId?.let(DocumentV1ControllerTestFixture::traceProvider),
        )

    private fun mvc(fixture: DocumentV1ContentControllerTestFixture, traceId: String? = null): MockMvc =
        MockMvcBuilders.standaloneSetup(controller(fixture, traceId))
            .setControllerAdvice(DocumentV1ContentControllerAdvice())
            .build()

    private fun stream(response: ResponseEntity<StreamingResponseBody>): StreamingResponseBody =
        assertNotNull(response.body)

    private fun assertSafeContentHeaders(response: ResponseEntity<StreamingResponseBody>, expectedLength: Long) {
        val headers = response.headers
        assertEquals("private, no-store", headers.getFirst(HttpHeaders.CACHE_CONTROL))
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"))
        assertEquals(MediaType.APPLICATION_PDF, headers.contentType)
        assertEquals(expectedLength.toString(), headers.getFirst(HttpHeaders.CONTENT_LENGTH))
        assertTrue(requireNotNull(headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("filename=\"download.pdf\""))
        assertTrue(requireNotNull(headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("%E6%B8%85%E7%A8%8E"))
        assertNull(headers.getFirst(HttpHeaders.ETAG))
        assertNull(headers.getFirst("Accept-Ranges"))
        assertFalse(headers.keys.any { key ->
            key.contains("hash", ignoreCase = true) || key.contains("storage", ignoreCase = true)
        })
    }

    private class FailingOutputStream : OutputStream() {
        override fun write(value: Int) {
            throw IOException("client disconnected")
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            throw IOException("client disconnected")
        }
    }

    private class TestDocumentApiDownload(
        override val content: InputStream = ByteArrayInputStream(byteArrayOf(1)),
        override val contentDisposition: String = "attachment; filename=\"download\"",
        override val contentType: String = "application/octet-stream",
        override val verifiedContentLength: Long? = null,
    ) : DocumentApiDownload {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount++
            content.close()
        }
    }

    private class FailingInputStream : InputStream() {
        override fun read(): Int = throw IOException("storage stream failed")
    }
}

internal class DocumentV1ContentControllerTestFixture(
    private val fileName: String = "C:\\legacy\\清税证明.pdf",
    private val persistedContentType: String? = "application/pdf",
    private val storageContentType: String? = null,
    private val reportedContentLength: Long? = PAYLOAD.size.toLong(),
    private val authenticated: Boolean = true,
    private val authorized: Boolean = true,
    private val documentAvailable: Boolean = true,
    private val storageFailure: Exception? = null,
) {
    val storage = RecordingStorage(storageContentType, reportedContentLength, storageFailure)
    val facade = DocumentApiDownloadFacade(service())
    val payload: ByteArray
        get() = PAYLOAD.copyOf()

    fun service(): DocumentDownloadService = DocumentDownloadService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(TENANT_ID)
        },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity? = if (authenticated) {
                UserIdentity(Identifier("user-1"), "下载用户")
            } else {
                null
            }

            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision =
                AuthorizationDecision(authorized, "private host policy")
        },
        documentRepository = object : DocumentRepository, DeletionVisibilityQuerySource {
            override fun findById(tenantId: Identifier, documentId: Identifier): Document? = document().takeIf { candidate ->
                documentAvailable && candidate.tenantId == tenantId && candidate.id == documentId
            }

            override fun save(document: Document) = Unit

            override fun deletionVisibilityQuery(): DeletionVisibilityQuery = object : DeletionVisibilityQuery {
                override fun findFence(
                    tenantId: Identifier,
                    resourceType: String,
                    resourceId: Identifier,
                ): DeletionVisibilityFence? = null
            }
        },
        fileObjectRepository = object : FileObjectRepository {
            override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? =
                FileObject(
                    id = fileObjectId,
                    tenantId = tenantId,
                    fileName = fileName,
                    contentLength = PAYLOAD.size.toLong(),
                    storageType = "memory",
                    storagePath = "tenant-1/${fileObjectId.value}",
                    contentType = persistedContentType,
                    contentHash = "sha256:must-not-leak",
                )

            override fun save(fileObject: FileObject) = Unit
        },
        storageAdapter = storage,
        transaction = DirectTransaction,
    )

    private fun document(): Document = Document(
        id = Identifier("document-1"),
        tenantId = TENANT_ID,
        assetId = Identifier("asset-1"),
        documentNumber = "DOC-001",
        title = "下载文档",
        versions = listOf(
            DocumentVersion(Identifier("version-1"), TENANT_ID, Identifier("document-1"), "1.0", Identifier("file-1")),
            DocumentVersion(Identifier("version-2"), TENANT_ID, Identifier("document-1"), "2.0", Identifier("file-2")),
        ),
        currentVersionId = Identifier("version-2"),
    )

    class RecordingStorage(
        private val contentType: String?,
        private val contentLength: Long?,
        private val failure: Exception?,
    ) : StorageAdapter {
        val requested = mutableListOf<StorageObjectLocation>()
        val streams = mutableListOf<CloseTrackingInputStream>()

        override fun download(location: StorageObjectLocation): StorageDownload {
            requested += location
            failure?.let { throw it }
            val stream = CloseTrackingInputStream(PAYLOAD)
            streams += stream
            return StorageDownload(stream, contentLength, contentType)
        }

        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject =
            throw UnsupportedOperationException()
        override fun delete(location: StorageObjectLocation) = Unit
        override fun exists(location: StorageObjectLocation): Boolean = true
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = URI("http://localhost/file")
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

    class CloseTrackingInputStream(content: ByteArray) : ByteArrayInputStream(content) {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount++
            super.close()
        }
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val PAYLOAD: ByteArray = "document-content".toByteArray()
    }
}
