package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.document.DocumentContentUnavailableException
import ai.icen.fw.application.document.DocumentDownloadService
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
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
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.api.ApiErrorCodes
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentApiDownloadFacadeTest {
    @Test
    fun `downloads the current or explicitly selected version without transport context inputs`() {
        val fixture = Fixture()

        fixture.facade.download("document-1").use { current ->
            assertEquals("payload", current.content.readBytes().decodeToString())
        }
        fixture.facade.download("document-1", "version-1").use { selected ->
            assertEquals("payload", selected.content.readBytes().decodeToString())
        }

        assertEquals(
            listOf(
                StorageObjectLocation("memory", "tenant-1/file-2"),
                StorageObjectLocation("memory", "tenant-1/file-1"),
            ),
            fixture.storage.requested,
        )
    }

    @Test
    fun `validates both identifiers before authorization repositories or storage`() {
        listOf(" ", "document\u0000id", "d".repeat(129)).forEach { invalidDocumentId ->
            val fixture = Fixture()

            assertThrows<IllegalArgumentException> {
                fixture.facade.download(invalidDocumentId)
            }

            assertEquals(0, fixture.authorization.calls)
            assertEquals(0, fixture.documents.reads)
            assertTrue(fixture.storage.requested.isEmpty())
        }

        listOf(" ", "version\u0000id", "v".repeat(129)).forEach { invalidVersionId ->
            val fixture = Fixture()

            assertThrows<IllegalArgumentException> {
                fixture.facade.download("document-1", invalidVersionId)
            }

            assertEquals(0, fixture.authorization.calls)
            assertEquals(0, fixture.documents.reads)
            assertTrue(fixture.storage.requested.isEmpty())
        }
    }

    @Test
    fun `builds bounded RFC5987 dispositions from unicode and hostile legacy names`() {
        val cases = listOf(
            "C:\\legacy\\清税证明.pdf",
            "../../private/report.pdf",
            "safe\r\nX-Injected: yes\u202E.pdf",
            "bad\u0000name\uD800.txt",
            ".",
            "..",
            "legacy/path/",
            "税".repeat(1_000) + ".pdf",
            "evil\"; filename=owned.txt",
        )

        cases.forEach { fileName ->
            val fixture = Fixture(fileName = fileName)
            fixture.facade.download("document-1").use { download ->
                val header = download.contentDisposition

                assertTrue(header.startsWith("attachment; filename=\""))
                assertTrue(header.contains("; filename*=UTF-8''"))
                assertTrue(header.length < 800)
                assertTrue(header.all { character -> character.code in 0x20..0x7e })
                assertFalse(header.contains('\r'))
                assertFalse(header.contains('\n'))
                assertFalse(header.contains("X-Injected:"))
                assertFalse(header.contains("%E2%80%AE"))
                assertFalse(header.contains("legacy/path"))
                assertFalse(header.contains("C:%5C"))
            }
        }

        val unicodeHeader = DocumentDownloadResponsePolicy.contentDisposition("C:\\legacy\\清税证明.pdf")
        assertTrue(unicodeHeader.contains("filename=\"download.pdf\""))
        assertTrue(unicodeHeader.contains("filename*=UTF-8''%E6%B8%85%E7%A8%8E%E8%AF%81%E6%98%8E.pdf"))
        assertEquals(
            "attachment; filename=\"download\"; filename*=UTF-8''download",
            DocumentDownloadResponsePolicy.contentDisposition(".."),
        )
        assertTrue(DocumentDownloadResponsePolicy.contentDisposition("../../report.pdf").contains("filename=\"report.pdf\""))
    }

    @Test
    fun `allows only fixed non active media types`() {
        mapOf(
            "application/pdf" to "application/pdf",
            " Application/PDF; charset=binary " to "application/pdf",
            "text/plain; charset=UTF-8" to "text/plain",
            "text/csv" to "text/csv",
            "image/png" to "image/png",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ).forEach { (source, expected) ->
            assertEquals(expected, DocumentDownloadResponsePolicy.contentType(source))
        }

        listOf(
            null,
            "text/html",
            "application/xhtml+xml",
            "image/svg+xml",
            "application/xml",
            "text/xml",
            "application/javascript",
            "text/javascript",
            "application/ecmascript",
            "text/ecmascript",
            "application/json",
            "application/pdf\r\nX-Type: text/html",
            "not a media type",
            "x".repeat(257),
        ).forEach { source ->
            assertEquals("application/octet-stream", DocumentDownloadResponsePolicy.contentType(source))
        }

        Fixture(storageContentType = "text/html", persistedContentType = "application/pdf")
            .facade.download("document-1").use { download ->
                assertEquals("application/octet-stream", download.contentType)
            }
        Fixture(storageContentType = null, persistedContentType = null)
            .facade.download("document-1").use { download ->
                assertEquals("application/octet-stream", download.contentType)
            }
    }

    @Test
    fun `exposes only storage verified length and never persisted fallback`() {
        Fixture(reportedContentLength = PAYLOAD.size.toLong()).facade.download("document-1").use { verified ->
            assertEquals(PAYLOAD.size.toLong(), verified.verifiedContentLength)
        }
        Fixture(reportedContentLength = null).facade.download("document-1").use { unverified ->
            assertNull(unverified.verifiedContentLength)
        }
    }

    @Test
    fun `closes the application download before wrapping a header construction failure`() {
        val fixture = Fixture(
            fileName = "x".repeat(DocumentDownloadResponsePolicy.MAX_SOURCE_FILE_NAME_CHARACTERS + 1),
        )

        val failure = assertThrows<DocumentContentUnavailableException> {
            fixture.facade.download("document-1")
        }

        assertEquals(DocumentContentUnavailableException.DEFAULT_MESSAGE, failure.message)
        assertTrue(failure.cause is IllegalArgumentException)
        assertEquals(1, fixture.storage.streams.single().closeCount)
    }

    @Test
    fun `the facade handle owns and idempotently closes the application stream`() {
        val fixture = Fixture()
        val download = fixture.facade.download("document-1")
        val stream = fixture.storage.streams.single()

        assertEquals(0, stream.closeCount)
        download.close()
        download.close()

        assertEquals(1, stream.closeCount)

        val directFixture = Fixture()
        val direct = directFixture.facade.download("document-1")
        val directStream = directFixture.storage.streams.single()
        direct.content.close()
        direct.close()

        assertEquals(1, directStream.closeCount)
    }

    @Test
    fun `maps authorization not found and content failures to fixed public responses`() {
        val responses = V1ApiResponseFactory()
        val cases = listOf(
            Fixture(authenticated = false) to Triple(401, ApiErrorCodes.UNAUTHENTICATED, "Authentication is required."),
            Fixture(authorized = false) to Triple(403, ApiErrorCodes.FORBIDDEN, "Access denied."),
            Fixture(documentAvailable = false) to Triple(404, ApiErrorCodes.NOT_FOUND, "Resource was not found."),
            Fixture(storageFailure = IllegalStateException("s3://private-bucket/key")) to Triple(
                503,
                ApiErrorCodes.CONTENT_UNAVAILABLE,
                "Document content is unavailable.",
            ),
        )

        cases.forEach { (fixture, expected) ->
            val failure = assertThrows<RuntimeException> {
                fixture.facade.download("document-1")
            }
            val mapped = responses.failure(failure)

            assertEquals(expected.first, mapped.status.statusCode)
            assertEquals(expected.second, mapped.response.code)
            assertEquals(expected.third, mapped.response.message)
            assertFalse(mapped.response.message.contains("private host policy"))
            assertFalse(mapped.response.message.contains("document-1"))
            assertFalse(mapped.response.message.contains("s3://"))
        }
    }

    private class Fixture(
        fileName: String = "report.pdf",
        persistedContentType: String? = "application/pdf",
        storageContentType: String? = null,
        reportedContentLength: Long? = PAYLOAD.size.toLong(),
        authorized: Boolean = true,
        authenticated: Boolean = true,
        documentAvailable: Boolean = true,
        storageFailure: Exception? = null,
    ) {
        val authorization = RecordingAuthorization(authorized)
        val documents = RecordingDocuments(document(), documentAvailable)
        private val files = RecordingFiles(fileName, persistedContentType)
        val storage = RecordingStorage(storageContentType, reportedContentLength, storageFailure)
        private val users = object : UserRealmProvider {
            override fun currentUser(): UserIdentity? = if (authenticated) {
                UserIdentity(Identifier("user-1"), "下载用户")
            } else {
                null
            }

            override fun findUser(userId: Identifier): UserIdentity? = null
        }
        val facade = DocumentApiDownloadFacade(
            DocumentDownloadService(
                tenantProvider = object : TenantProvider {
                    override fun currentTenant() = TenantContext(TENANT_ID)
                },
                userRealmProvider = users,
                authorizationProvider = authorization,
                documentRepository = documents,
                fileObjectRepository = files,
                storageAdapter = storage,
                transaction = DirectTransaction,
            ),
        )
    }

    private class RecordingAuthorization(
        private val authorized: Boolean,
    ) : AuthorizationProvider {
        var calls: Int = 0
            private set

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            calls++
            return AuthorizationDecision(authorized, "private host policy")
        }
    }

    private class RecordingDocuments(
        private val document: Document,
        private val available: Boolean,
    ) : DocumentMutationRepository {
        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? = findById(tenantId, documentId)
        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? = null

        var reads: Int = 0
            private set

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? {
            reads++
            return document.takeIf { candidate ->
                available && candidate.tenantId == tenantId && candidate.id == documentId
            }
        }

        override fun save(document: Document) = Unit
    }

    private class RecordingFiles(
        private val fileName: String,
        private val contentType: String?,
    ) : FileObjectRepository {
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? =
            FileObject(
                id = fileObjectId,
                tenantId = tenantId,
                fileName = fileName,
                contentLength = PAYLOAD.size.toLong(),
                storageType = "memory",
                storagePath = "tenant-1/${fileObjectId.value}",
                contentType = contentType,
                contentHash = "sha256:must-not-leak",
            )

        override fun save(fileObject: FileObject) = Unit
    }

    private class RecordingStorage(
        private val contentType: String?,
        private val reportedContentLength: Long?,
        private val failure: Exception?,
    ) : StorageAdapter {
        val requested = mutableListOf<StorageObjectLocation>()
        val streams = mutableListOf<CloseTrackingInputStream>()

        override fun download(location: StorageObjectLocation): StorageDownload {
            requested += location
            failure?.let { throw it }
            val stream = CloseTrackingInputStream(PAYLOAD)
            streams += stream
            return StorageDownload(stream, reportedContentLength, contentType)
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

    private class CloseTrackingInputStream(content: ByteArray) : ByteArrayInputStream(content) {
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
        val PAYLOAD = "payload".toByteArray()

        fun document(): Document = Document(
            id = Identifier("document-1"),
            tenantId = TENANT_ID,
            assetId = Identifier("asset-1"),
            documentNumber = "DOC-001",
            title = "Download document",
            versions = listOf(
                DocumentVersion(
                    Identifier("version-1"),
                    TENANT_ID,
                    Identifier("document-1"),
                    "1.0",
                    Identifier("file-1"),
                ),
                DocumentVersion(
                    Identifier("version-2"),
                    TENANT_ID,
                    Identifier("document-1"),
                    "2.0",
                    Identifier("file-2"),
                ),
            ),
            currentVersionId = Identifier("version-2"),
        )
    }
}
