package ai.icen.fw.web.spring.boot3

import ai.icen.fw.web.api.v1.upload.PresignedUploadGrantDto
import ai.icen.fw.web.api.v1.upload.PresignedUploadFinalizationDto
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.upload.PresignedUploadApiFacade
import ai.icen.fw.web.spring.boot3.v1.upload.V1PresignedUploadController
import ai.icen.fw.web.spring.boot3.v1.upload.V1PresignedUploadRequestFailureHandler
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.net.URI

class V1PresignedUploadControllerTest {
    @Test
    fun `creates a no-store capability without internal storage authority`() {
        val facade = Mockito.mock(PresignedUploadApiFacade::class.java, Answer {
            PresignedUploadGrantDto(
                "upload-1",
                URI.create("https://uploads.example/object?signature=opaque"),
                mapOf("Content-Type" to "application/pdf"),
                2_000,
                true,
            )
        })
        val mvc = MockMvcBuilders.standaloneSetup(
            V1PresignedUploadController(facade, V1ApiResponseFactory(), null),
        ).build()
        val hash = "sha256:${"a".repeat(64)}"

        mvc.perform(
            post("/flowweft/v1/presigned-uploads")
                .header("Idempotency-Key", "request-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"fileName":"合同.pdf","contentLength":12,"contentType":"application/pdf","contentHash":"$hash","checksumAlgorithm":"md5","checksumValue":"CY9rzUYh03PK3k6DJie09g=="}""",
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(header().string("Location", "/flowweft/v1/presigned-uploads/upload-1"))
            .andExpect(jsonPath("$.data.uploadId").value("upload-1"))
            .andExpect(jsonPath("$.data.method").value("PUT"))
            .andExpect(jsonPath("$.data.storageLocation").doesNotExist())
            .andExpect(jsonPath("$.data.ownerId").doesNotExist())
            .andExpect(header().string("Location", not(containsString("uploads.example"))))
    }

    @Test
    fun `returns ok with the same resource location for an idempotent start replay`() {
        val facade = Mockito.mock(PresignedUploadApiFacade::class.java, Answer {
            PresignedUploadGrantDto(
                "upload-1",
                URI.create("https://uploads.example/object?signature=opaque"),
                mapOf("Content-Type" to "application/pdf"),
                2_000,
                false,
            )
        })
        val mvc = MockMvcBuilders.standaloneSetup(
            V1PresignedUploadController(facade, V1ApiResponseFactory(), null),
        ).build()

        mvc.perform(
            post("/flowweft/v1/presigned-uploads")
                .header("Idempotency-Key", "request-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validStartJson()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Location", "/flowweft/v1/presigned-uploads/upload-1"))
            .andExpect(jsonPath("$.data.created").value(false))
    }

    @Test
    fun `finalize returns the stable atomic asset claim with no-store`() {
        val facade = Mockito.mock(PresignedUploadApiFacade::class.java, Answer {
            PresignedUploadFinalizationDto("upload-1", "file-object-1", "file-asset-1", false)
        })
        val mvc = MockMvcBuilders.standaloneSetup(
            V1PresignedUploadController(facade, V1ApiResponseFactory(), null),
        ).build()

        mvc.perform(
            post("/flowweft/v1/presigned-uploads/upload-1/finalize")
                .header("Idempotency-Key", "finalize-1"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(jsonPath("$.data.fileObjectId").value("file-object-1"))
            .andExpect(jsonPath("$.data.fileAssetId").value("file-asset-1"))
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))

        Mockito.verify(facade).finalizeUpload("upload-1", listOf("finalize-1"))
        Mockito.verifyNoMoreInteractions(facade)
    }

    @Test
    fun `maps malformed JSON before controller invocation without leaking cacheable details`() {
        val facade = Mockito.mock(PresignedUploadApiFacade::class.java)
        val responses = V1ApiResponseFactory()
        val failureHandler = V1PresignedUploadRequestFailureHandler(responses, null)
        val mvc = MockMvcBuilders.standaloneSetup(
            V1PresignedUploadController(facade, responses, null),
        ).setHandlerExceptionResolvers(failureHandler.uploadExceptionResolver()).build()

        mvc.perform(
            post("/flowweft/v1/presigned-uploads")
                .header("Idempotency-Key", "request-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        Mockito.verifyNoInteractions(facade)
    }

    private fun validStartJson(): String =
        """{"fileName":"contract.pdf","contentLength":12,"contentType":"application/pdf","contentHash":"sha256:${"a".repeat(64)}","checksumAlgorithm":"md5","checksumValue":"CY9rzUYh03PK3k6DJie09g=="}"""
}
