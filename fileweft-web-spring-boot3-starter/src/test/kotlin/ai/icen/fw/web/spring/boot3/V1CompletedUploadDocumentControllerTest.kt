package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimUnavailableException
import ai.icen.fw.web.api.v1.document.AddDocumentVersionFromCompletedUploadRequest
import ai.icen.fw.web.api.v1.document.CreateDocumentFromCompletedUploadRequest
import ai.icen.fw.web.api.v1.document.DocumentCommandResultDto
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.CompletedUploadDocumentApiFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1CompletedUploadDocumentController
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

class V1CompletedUploadDocumentControllerTest {
    @Test
    fun `supports JSON create and version routes beside the existing multipart routes`() {
        val recording = RecordingFacade()
        val mvc = mvc(recording.facade)

        mvc.perform(
            post("/fileweft/v1/documents")
                .queryParam("uploadId", "upload-1")
                .header("Idempotency-Key", "claim-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"documentNumber":"DOC-100","title":"供水合同"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(jsonPath("$.data.documentId").value("document-1"))

        mvc.perform(
            post("/fileweft/v1/documents/document-1/versions")
                .queryParam("uploadId", "upload-2")
                .header("Idempotency-Key", "claim-key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"versionNumber":"2.0"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.versionId").value("version-2"))

        assertEquals("document-1", recording.documentId)
        assertEquals("upload-2", recording.uploadId)
        assertEquals("2.0", recording.versionRequest?.versionNumber)
    }

    @Test
    fun `maps an unavailable claim repository to a redacted 503`() {
        val recording = RecordingFacade().apply {
            failure = CompletedResumableUploadAssetClaimUnavailableException()
        }

        mvc(recording.facade).perform(
            post("/fileweft/v1/documents")
                .queryParam("uploadId", "upload-1")
                .header("Idempotency-Key", "claim-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"documentNumber":"DOC-100","title":"Title"}"""),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error.code").value("FEATURE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("The requested feature is unavailable."))
    }

    private fun mvc(facade: CompletedUploadDocumentApiFacade): MockMvc = MockMvcBuilders
        .standaloneSetup(V1CompletedUploadDocumentController(facade, V1ApiResponseFactory(), null))
        .build()

    private class RecordingFacade {
        var uploadId: String? = null
        var documentId: String? = null
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
                    createRequest = invocation.arguments[2] as CreateDocumentFromCompletedUploadRequest
                    DocumentCommandResultDto("document-1", "version-1")
                }
                "addDocumentVersion" -> {
                    documentId = invocation.arguments[0] as String
                    uploadId = invocation.arguments[1] as String
                    versionRequest = invocation.arguments[3] as AddDocumentVersionFromCompletedUploadRequest
                    DocumentCommandResultDto(documentId!!, "version-2")
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
    }
}
