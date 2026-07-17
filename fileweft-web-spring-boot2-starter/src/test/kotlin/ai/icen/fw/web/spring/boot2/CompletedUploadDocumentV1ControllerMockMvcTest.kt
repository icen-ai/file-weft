package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimConflictException
import ai.icen.fw.web.api.v1.document.AddDocumentVersionFromCompletedUploadRequest
import ai.icen.fw.web.api.v1.document.CreateDocumentFromCompletedUploadRequest
import ai.icen.fw.web.api.v1.document.DocumentCommandResultDto
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.CompletedUploadDocumentApiFacade
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals

class CompletedUploadDocumentV1ControllerMockMvcTest {
    @Test
    fun `creates a document from a completed upload without accepting identity or storage bindings`() {
        val recording = RecordingFacade()

        mvc(recording.facade).perform(
            post("/fileweft/v1/documents")
                .queryParam("uploadId", "upload-1")
                .header("Idempotency-Key", "claim-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"documentNumber":"DOC-100","title":"供水合同"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(header().string("Location", "/fileweft/v1/documents/document-1"))
            .andExpect(jsonPath("$.data.documentId").value("document-1"))
            .andExpect(jsonPath("$.data.versionId").value("version-1"))

        assertEquals("upload-1", recording.uploadId)
        assertEquals(listOf("claim-key-1"), recording.idempotencyKeys)
        assertEquals("DOC-100", recording.createRequest?.documentNumber)
        assertEquals("供水合同", recording.createRequest?.title)
    }

    @Test
    fun `adds a document version from a completed upload`() {
        val recording = RecordingFacade()

        mvc(recording.facade).perform(
            post("/fileweft/v1/documents/document-1/versions")
                .queryParam("uploadId", "upload-2")
                .header("Idempotency-Key", "claim-key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"versionNumber":"2.0"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.documentId").value("document-1"))
            .andExpect(jsonPath("$.data.versionId").value("version-2"))

        assertEquals("document-1", recording.documentId)
        assertEquals("upload-2", recording.uploadId)
        assertEquals("2.0", recording.versionRequest?.versionNumber)
    }

    @Test
    fun `rejects ambiguous upload parameters and maps claim conflicts without leaking details`() {
        val recording = RecordingFacade()
        mvc(recording.facade).perform(
            post("/fileweft/v1/documents")
                .queryParam("uploadId", "upload-1", "upload-2")
                .header("Idempotency-Key", "claim-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"documentNumber":"DOC-100","title":"Title"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))

        recording.failure = CompletedResumableUploadAssetClaimConflictException()
        mvc(recording.facade).perform(
            post("/fileweft/v1/documents")
                .queryParam("uploadId", "upload-1")
                .header("Idempotency-Key", "claim-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"documentNumber":"DOC-100","title":"Title"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("CONFLICT"))
            .andExpect(jsonPath("$.message").value("Request conflicts with the current resource state."))
    }

    private fun mvc(facade: CompletedUploadDocumentApiFacade): MockMvc = MockMvcBuilders
        .standaloneSetup(CompletedUploadDocumentV1Controller(facade, V1ApiResponseFactory()))
        .build()

    private class RecordingFacade {
        var uploadId: String? = null
        var documentId: String? = null
        var idempotencyKeys: List<String>? = null
        var createRequest: CreateDocumentFromCompletedUploadRequest? = null
        var versionRequest: AddDocumentVersionFromCompletedUploadRequest? = null
        var failure: RuntimeException? = null

        val facade: CompletedUploadDocumentApiFacade = Mockito.mock(
            CompletedUploadDocumentApiFacade::class.java,
        ) { invocation ->
            failure?.let { throw it }
            when (invocation.method.name) {
                "createDocument" -> {
                    uploadId = invocation.arguments[0] as String
                    @Suppress("UNCHECKED_CAST")
                    idempotencyKeys = invocation.arguments[1] as List<String>?
                    createRequest = invocation.arguments[2] as CreateDocumentFromCompletedUploadRequest
                    DocumentCommandResultDto("document-1", "version-1")
                }
                "addDocumentVersion" -> {
                    documentId = invocation.arguments[0] as String
                    uploadId = invocation.arguments[1] as String
                    @Suppress("UNCHECKED_CAST")
                    idempotencyKeys = invocation.arguments[2] as List<String>?
                    versionRequest = invocation.arguments[3] as AddDocumentVersionFromCompletedUploadRequest
                    DocumentCommandResultDto(documentId!!, "version-2")
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
    }
}
