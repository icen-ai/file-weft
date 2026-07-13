package ai.icen.fw.web.runtime.v1.upload

import ai.icen.fw.application.upload.ResumableUploadNotFoundException
import ai.icen.fw.application.upload.ResumableUploadCompletionResult
import ai.icen.fw.application.upload.ResumableUploadPart
import ai.icen.fw.application.upload.ResumableUploadSession
import ai.icen.fw.application.upload.ResumableUploadSessionStatus
import ai.icen.fw.application.upload.ResumableUploadSessionView
import ai.icen.fw.application.upload.StartResumableUploadCommand
import ai.icen.fw.application.upload.UploadFileResult
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.web.api.v1.upload.ResumableUploadStatuses
import ai.icen.fw.web.api.v1.upload.StartResumableUploadRequest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumableUploadApiFacadeTest {
    @Test
    fun `start validates the caller key and delegates a fixed asset command with replay checkpoints`() {
        val operations = FakeOperations().apply {
            startView = view(
                session(),
                listOf(part(2, 3, "private-etag-2"), part(1, 4, "private-etag-1")),
            )
        }
        val facade = facade(operations)
        val request = StartResumableUploadRequest().apply {
            fileName = "合同.pdf"
            contentLength = 7
            contentType = "application/pdf"
            contentHash = "sha256:${"a".repeat(64)}"
        }

        val response = facade.start(listOf("browser-retry-key"), request)
        val command = requireNotNull(operations.started)

        assertEquals("DOCUMENT", command.assetType)
        assertEquals(emptyMap(), command.metadata)
        assertEquals("browser-retry-key", command.idempotencyKey)
        assertEquals(listOf(1, 2), response.uploadedParts.map { uploaded -> uploaded.partNumber })
        assertEquals(listOf(4L, 3L), response.uploadedParts.map { uploaded -> uploaded.contentLength })
        assertEquals(ResumableUploadStatuses.UPLOADING, response.status)
        assertEquals("sha256:${"a".repeat(64)}", response.contentHash)

    }

    @Test
    fun `start validates the complete idempotency header set before application work`() {
        val operations = FakeOperations()
        val facade = facade(operations)
        val request = StartResumableUploadRequest().apply {
            fileName = "合同.pdf"
            contentLength = 7
        }

        assertFailsWith<IllegalArgumentException> { facade.start(null, request) }
        assertFailsWith<IllegalArgumentException> { facade.start(emptyList(), request) }
        assertFailsWith<IllegalArgumentException> { facade.start(listOf("a", "b"), request) }
        assertFailsWith<IllegalArgumentException> { facade.start(listOf("secret key"), request) }
        assertNull(operations.started)
    }

    @Test
    fun `inspect redacts storage owner etag and diagnostic details`() {
        val operations = FakeOperations().apply {
            inspectedView = view(
                session(status = ResumableUploadSessionStatus.FAILED, lastError = "jdbc private detail"),
                listOf(part(1, 7, "storage-secret-etag")),
            )
        }

        val response = facade(operations).inspect("upload-1")

        assertEquals(ResumableUploadStatuses.FAILED, response.status)
        assertEquals(1, response.uploadedParts.single().partNumber)
        val publicFields = response.javaClass.declaredFields.filterNot { field -> field.isSynthetic }.map { field -> field.name }
        assertFalse(publicFields.any { name -> name in setOf("tenantId", "ownerId", "storageUploadId", "storageLocation", "lastError") })
        val partFields = response.uploadedParts.single().javaClass.declaredFields
            .filterNot { field -> field.isSynthetic }
            .map { field -> field.name }
        assertFalse("eTag" in partFields)
    }

    @Test
    fun `upload part validates public bounds and returns a redacted acknowledgement`() {
        val operations = FakeOperations()
        val facade = facade(operations)
        val bytes = "payload".toByteArray()

        val response = facade.uploadPart("upload-1", 1, bytes.size.toLong(), ByteArrayInputStream(bytes))

        assertEquals("upload-1", operations.partUploadId?.value)
        assertEquals(1, operations.partNumber)
        assertEquals("payload", operations.partContent?.toString(Charsets.UTF_8))
        assertEquals(1, response.partNumber)
        assertEquals(7L, response.contentLength)
        assertFailsWith<IllegalArgumentException> {
            facade.uploadPart("upload-1", 0, 1, ByteArrayInputStream(byteArrayOf(1)))
        }
        assertFailsWith<IllegalArgumentException> {
            facade.uploadPart("upload-1", 1, 0, ByteArrayInputStream(byteArrayOf()))
        }
    }

    @Test
    fun `complete returns opaque committed identifiers and best-effort completion time`() {
        val operations = FakeOperations().apply {
            inspectedView = view(session(status = ResumableUploadSessionStatus.COMPLETED, completedAt = 30), listOf(part(1, 7)))
        }

        val response = facade(operations).complete("upload-1")

        assertEquals("upload-1", response.uploadId)
        assertEquals("object-1", response.fileObjectId)
        assertEquals("asset-1", response.fileAssetId)
        assertEquals(30L, response.completedAt)
    }

    @Test
    fun `abort maps only the safe terminal state`() {
        val operations = FakeOperations().apply {
            abortedSession = session(status = ResumableUploadSessionStatus.ABORTED)
            abortedParts = listOf(part(1, 7))
        }

        val response = facade(operations).abort("upload-1")

        assertEquals(ResumableUploadStatuses.ABORTED, response.status)
        assertEquals(listOf(1), response.uploadedParts.map { part -> part.partNumber })
    }

    @Test
    fun `internal quarantine and staging states fail closed as not found`() {
        listOf(ResumableUploadSessionStatus.ABORTING, ResumableUploadSessionStatus.QUARANTINED).forEach { status ->
            val operations = FakeOperations().apply { inspectedView = view(session(status = status), emptyList()) }
            assertFailsWith<ResumableUploadNotFoundException>(status.name) {
                facade(operations).inspect("upload-1")
            }
        }
    }

    @Test
    fun `location helper emits safe relative upload resources and rejects invalid internal ids`() {
        assertEquals(
            "/fileweft/v1/uploads/upload-1",
            ResumableUploadApiLocations.inspect("upload-1").toString(),
        )
        assertEquals(
            "/host/gateway/fileweft/v1/uploads/upload-1",
            ResumableUploadApiLocations.inspect(
                URI.create("/host/gateway/fileweft/v1/uploads"),
                "upload-1",
            ).toString(),
        )
        listOf("unsafe/path", "空白", "x".repeat(129)).forEach { uploadId ->
            assertFailsWith<IllegalStateException> { ResumableUploadApiLocations.inspect(uploadId) }
        }
    }

    private fun facade(operations: FakeOperations): ResumableUploadApiFacade =
        ResumableUploadApiFacade.forTesting(operations)

    private fun session(
        status: ResumableUploadSessionStatus = ResumableUploadSessionStatus.ACTIVE,
        lastError: String? = null,
        completedAt: Long? = null,
    ): ResumableUploadSession = ResumableUploadSession(
        id = Identifier("upload-1"),
        tenantId = Identifier("tenant-1"),
        idempotencyKey = "v1:sha256:${"b".repeat(64)}",
        storageUploadId = Identifier("private-storage-upload"),
        storageLocation = StorageObjectLocation("private-storage", "private/path"),
        fileObjectId = Identifier("object-1"),
        fileAssetId = Identifier("asset-1"),
        fileName = "合同.pdf",
        contentLength = 7,
        assetType = "DOCUMENT",
        contentType = "application/pdf",
        expectedContentHash = "sha256:${"a".repeat(64)}",
        status = status,
        expiresAt = 1_000,
        lastError = lastError,
        completedAt = completedAt,
        createdTime = 10,
        updatedTime = completedAt ?: 20,
        ownerId = "private-owner",
    )

    private fun part(number: Int, length: Long, eTag: String = "private-etag-$number"): ResumableUploadPart =
        ResumableUploadPart(
            id = Identifier("part-$number"),
            tenantId = Identifier("tenant-1"),
            sessionId = Identifier("upload-1"),
            partNumber = number,
            eTag = eTag,
            contentLength = length,
            createdTime = 15,
            updatedTime = 20,
        )

    private fun view(session: ResumableUploadSession, parts: List<ResumableUploadPart>): ResumableUploadSessionView =
        ResumableUploadSessionView(session, parts)

    private class FakeOperations : ResumableUploadFacadeOperations {
        var started: StartResumableUploadCommand? = null
        var startView: ResumableUploadSessionView? = null
        var inspectedView: ResumableUploadSessionView? = null
        var partUploadId: Identifier? = null
        var partNumber: Int? = null
        var partContent: ByteArray? = null
        var abortedSession: ResumableUploadSession? = null
        var abortedParts: List<ResumableUploadPart> = emptyList()

        override fun startAndInspectWithCallerKey(
            command: StartResumableUploadCommand,
        ): ResumableUploadSessionView {
            started = command
            return requireNotNull(startView)
        }

        override fun inspect(uploadId: Identifier): ResumableUploadSessionView = requireNotNull(inspectedView)

        override fun uploadPart(
            uploadId: Identifier,
            partNumber: Int,
            contentLength: Long,
            content: InputStream,
        ): ResumableUploadPart {
            partUploadId = uploadId
            this.partNumber = partNumber
            partContent = content.readBytes()
            return ResumableUploadPart(
                id = Identifier("part-$partNumber"),
                tenantId = Identifier("tenant-1"),
                sessionId = uploadId,
                partNumber = partNumber,
                eTag = "private-etag",
                contentLength = contentLength,
                createdTime = 20,
                updatedTime = 20,
            )
        }

        override fun completeAndInspect(uploadId: Identifier): ResumableUploadCompletionResult =
            ResumableUploadCompletionResult(
                result = UploadFileResult(
                    FileObject(
                        id = Identifier("object-1"),
                        tenantId = Identifier("tenant-1"),
                        fileName = "合同.pdf",
                        contentLength = 7,
                        storageType = "private-storage",
                        storagePath = "private/path",
                    ),
                    FileAsset(
                        id = Identifier("asset-1"),
                        tenantId = Identifier("tenant-1"),
                        fileObjectId = Identifier("object-1"),
                        assetType = "DOCUMENT",
                    ),
                ),
                completedAt = inspectedView?.session?.completedAt,
            )

        override fun abortAndInspect(uploadId: Identifier): ResumableUploadSessionView =
            ResumableUploadSessionView(requireNotNull(abortedSession), abortedParts)
    }
}
