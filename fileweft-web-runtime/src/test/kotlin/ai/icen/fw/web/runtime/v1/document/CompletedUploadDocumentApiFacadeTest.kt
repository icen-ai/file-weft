package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.upload.AddDocumentVersionFromCompletedUploadCommand
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimResult
import ai.icen.fw.application.upload.CreateDocumentFromCompletedUploadCommand
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.web.api.v1.document.AddDocumentVersionFromCompletedUploadRequest
import ai.icen.fw.web.api.v1.document.CreateDocumentFromCompletedUploadRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CompletedUploadDocumentApiFacadeTest {
    @Test
    fun `creates a document using only bounded public metadata and one caller key`() {
        val operations = RecordingOperations()
        val request = CreateDocumentFromCompletedUploadRequest().apply {
            documentNumber = "DOC-100"
            title = "供水合同"
        }

        val result = CompletedUploadDocumentApiFacade.forTesting(operations).createDocument(
            "upload-1",
            listOf("claim-key-1"),
            request,
        )

        assertEquals("document-1", result.documentId)
        assertEquals("version-1", result.versionId)
        assertEquals("upload-1", operations.created?.uploadId?.value)
        assertEquals("DOC-100", operations.created?.documentNumber)
        assertEquals("供水合同", operations.created?.title)
        assertEquals("claim-key-1", operations.created?.idempotencyKey)
        assertNull(operations.added)
    }

    @Test
    fun `adds a version with exact document upload and caller key bindings`() {
        val operations = RecordingOperations()
        val request = AddDocumentVersionFromCompletedUploadRequest().apply {
            versionNumber = "2.0"
        }

        val result = CompletedUploadDocumentApiFacade.forTesting(operations).addDocumentVersion(
            "document-1",
            "upload-2",
            listOf("claim-key-2"),
            request,
        )

        assertEquals("document-1", result.documentId)
        assertEquals("version-1", result.versionId)
        assertEquals("document-1", operations.added?.documentId?.value)
        assertEquals("upload-2", operations.added?.uploadId?.value)
        assertEquals("2.0", operations.added?.versionNumber)
        assertEquals("claim-key-2", operations.added?.idempotencyKey)
        assertNull(operations.created)
    }

    @Test
    fun `rejects ambiguous idempotency and missing request values before invoking the application`() {
        val operations = RecordingOperations()
        val facade = CompletedUploadDocumentApiFacade.forTesting(operations)

        assertFailsWith<IllegalArgumentException> {
            facade.createDocument(
                "upload-1",
                listOf("first-key", "second-key"),
                CreateDocumentFromCompletedUploadRequest().apply {
                    documentNumber = "DOC-100"
                    title = "Title"
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            facade.addDocumentVersion(
                "document-1",
                "upload-1",
                listOf("claim-key"),
                AddDocumentVersionFromCompletedUploadRequest(),
            )
        }

        assertNull(operations.created)
        assertNull(operations.added)
    }

    private class RecordingOperations : CompletedUploadDocumentFacadeOperations {
        var created: CreateDocumentFromCompletedUploadCommand? = null
        var added: AddDocumentVersionFromCompletedUploadCommand? = null

        override fun createDocument(
            command: CreateDocumentFromCompletedUploadCommand,
        ): CompletedResumableUploadAssetClaimResult {
            created = command
            return result(command.uploadId, Identifier("document-1"))
        }

        override fun addDocumentVersion(
            command: AddDocumentVersionFromCompletedUploadCommand,
        ): CompletedResumableUploadAssetClaimResult {
            added = command
            return result(command.uploadId, command.documentId)
        }

        private fun result(
            uploadId: Identifier,
            documentId: Identifier,
        ): CompletedResumableUploadAssetClaimResult = CompletedResumableUploadAssetClaimResult(
            uploadId = uploadId,
            fileObjectId = Identifier("file-1"),
            fileAssetId = Identifier("asset-1"),
            documentId = documentId,
            versionId = Identifier("version-1"),
            replayed = false,
        )
    }
}
