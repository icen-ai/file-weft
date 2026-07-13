package ai.icen.fw.web.api

import ai.icen.fw.web.api.v1.upload.ResumableUploadCompletionDto
import ai.icen.fw.web.api.v1.upload.ResumableUploadDto
import ai.icen.fw.web.api.v1.upload.ResumableUploadPartDto
import ai.icen.fw.web.api.v1.upload.ResumableUploadStatuses
import ai.icen.fw.web.api.v1.upload.StartResumableUploadCommand
import ai.icen.fw.web.api.v1.upload.StartResumableUploadRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ResumableUploadApiContractTest {
    @Test
    fun `start request is a minimal mutable transport bean`() {
        val request = StartResumableUploadRequest().apply {
            fileName = "合同.pdf"
            contentLength = 8_192L
            contentType = "application/pdf"
            contentHash = "sha256:${"a".repeat(64)}"
        }

        assertEquals("合同.pdf", request.fileName)
        assertEquals(8_192L, request.contentLength)
        assertEquals("application/pdf", request.contentType)
        assertEquals("sha256:${"a".repeat(64)}", request.contentHash)
        assertEquals(
            setOf("fileName", "contentLength", "contentType", "contentHash"),
            StartResumableUploadRequest::class.java.declaredFields
                .filterNot { field -> field.isSynthetic }
                .map { field -> field.name }
                .toSet(),
        )
    }

    @Test
    fun `validated start command rejects paths and non-positive lengths`() {
        assertFailsWith<IllegalArgumentException> {
            StartResumableUploadCommand("../合同.pdf", 1)
        }
        assertFailsWith<IllegalArgumentException> {
            StartResumableUploadCommand("合同.pdf", 0)
        }
        assertFailsWith<IllegalArgumentException> {
            StartResumableUploadCommand("合同.pdf", 1, contentType = " ")
        }
        listOf("sha256:abc", "SHA256:${"a".repeat(64)}", "sha256:${"A".repeat(64)}").forEach { hash ->
            assertFailsWith<IllegalArgumentException> {
                StartResumableUploadCommand("合同.pdf", 1, contentHash = hash)
            }
        }
    }

    @Test
    fun `upload snapshot is immutable and contains only public state`() {
        val callerParts = mutableListOf(ResumableUploadPartDto("upload-1", 1, 4, 20))
        val upload = ResumableUploadDto(
            uploadId = "upload-1",
            fileName = "合同.pdf",
            contentLength = 4,
            status = ResumableUploadStatuses.UPLOADING,
            expiresAt = 100,
            createdTime = 10,
            updatedTime = 20,
            uploadedParts = callerParts,
            contentType = "application/pdf",
            contentHash = null,
        )
        callerParts.clear()

        assertEquals(1, upload.uploadedParts.size)
        assertNull(upload.completion)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (upload.uploadedParts as MutableList<ResumableUploadPartDto>).clear()
        }
        assertEquals(
            setOf(
                "uploadId",
                "fileName",
                "contentLength",
                "status",
                "expiresAt",
                "createdTime",
                "updatedTime",
                "uploadedParts",
                "contentType",
                "contentHash",
                "completion",
            ),
            ResumableUploadDto::class.java.declaredFields
                .filterNot { field -> field.isSynthetic }
                .map { field -> field.name }
                .toSet(),
        )
    }

    @Test
    fun `completed snapshot requires a matching opaque receipt`() {
        val completion = ResumableUploadCompletionDto("upload-1", "object-1", "asset-1", 30)
        val upload = ResumableUploadDto(
            uploadId = "upload-1",
            fileName = "合同.pdf",
            contentLength = 4,
            status = ResumableUploadStatuses.COMPLETED,
            expiresAt = 100,
            createdTime = 10,
            updatedTime = 30,
            uploadedParts = listOf(ResumableUploadPartDto("upload-1", 1, 4, 20)),
            completion = completion,
        )

        assertEquals("object-1", upload.completion?.fileObjectId)
        assertFailsWith<IllegalArgumentException> {
            ResumableUploadDto(
                uploadId = "upload-1",
                fileName = "合同.pdf",
                contentLength = 4,
                status = ResumableUploadStatuses.COMPLETED,
                expiresAt = 100,
                createdTime = 10,
                updatedTime = 30,
                uploadedParts = emptyList(),
            )
        }
    }
}
