package com.fileweft.web.spring.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.core.context.TraceContext
import com.fileweft.core.id.Identifier
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentLifecycleApiFacade
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class DocumentV1LifecycleControllerMockMvcTest {
    @Test
    fun `maps all eight lifecycle routes and fails closed when their capabilities are absent`() {
        val mockMvc = mvc()

        lifecycleRequests().forEachIndexed { index, request ->
            mockMvc.perform(request.header(IDEMPOTENCY_KEY_HEADER, "lifecycle-key-$index"))
                .andExpect(status().isServiceUnavailable)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The requested feature is unavailable."))
                .andExpect(jsonPath("$.traceId").value("trace-lifecycle-2"))
                .andExpect(content().string(not(containsString("lifecycle-key-$index"))))
        }
    }

    @Test
    fun `allows every JSON lifecycle command body to be omitted and applies its default command`() {
        val mockMvc = mvc()
        val optionalBodyRoutes = listOf(
            "/fileweft/v1/documents/document-1/publish",
            "/fileweft/v1/documents/document-1/submit",
            "/fileweft/v1/workflows/workflow-1/tasks/task-1/approve",
            "/fileweft/v1/workflows/workflow-1/tasks/task-1/reject",
        )

        optionalBodyRoutes.forEachIndexed { index, path ->
            mockMvc.perform(post(path).header(IDEMPOTENCY_KEY_HEADER, "default-key-$index"))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))
        }
    }

    @Test
    fun `rejects missing repeated and invalid idempotency headers before capability resolution without leaking them`() {
        val mockMvc = mvc()

        mockMvc.perform(post(REVISE_PATH))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Request is invalid."))

        mockMvc.perform(
            post(REVISE_PATH).header(
                IDEMPOTENCY_KEY_HEADER,
                "first-private-key",
                "second-private-key",
            ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(content().string(not(containsString("first-private-key"))))
            .andExpect(content().string(not(containsString("second-private-key"))))

        mockMvc.perform(post(REVISE_PATH).header(IDEMPOTENCY_KEY_HEADER, "private invalid key"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(content().string(not(containsString("private invalid key"))))
    }

    private fun lifecycleRequests(): List<MockHttpServletRequestBuilder> = listOf(
        post("/fileweft/v1/documents/document-1/revise"),
        post("/fileweft/v1/documents/document-1/publish")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deliveryProfileId\":\"primary\"}"),
        post("/fileweft/v1/documents/document-1/offline"),
        post("/fileweft/v1/documents/document-1/restore"),
        post("/fileweft/v1/documents/document-1/archive"),
        post("/fileweft/v1/documents/document-1/submit")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reviewRouteId\":\"four-eyes\"}"),
        post("/fileweft/v1/workflows/workflow-1/tasks/task-1/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"comment\":\"approved\",\"deliveryProfileId\":\"primary\"}"),
        post("/fileweft/v1/workflows/workflow-1/tasks/task-1/reject")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"comment\":\"rejected\"}"),
    )

    private fun mvc(): MockMvc = MockMvcBuilders.standaloneSetup(
        DocumentV1LifecycleController(
            documents = unavailableFacade(),
            responses = V1ApiResponseFactory(),
            traceContextProvider = object : TraceContextProvider {
                override fun currentTraceContext(): TraceContext =
                    TraceContext(Identifier("trace-lifecycle-2"))
            },
        ),
    )
        .setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper()))
        .build()

    private fun unavailableFacade(): DocumentLifecycleApiFacade = DocumentLifecycleApiFacade(
        catalogAccessCount = 0,
        flatLifecycles = emptyList(),
        catalogLifecycles = emptyList(),
        flatReviews = emptyList(),
        catalogReviews = emptyList(),
    )

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        const val REVISE_PATH = "/fileweft/v1/documents/document-1/revise"
    }
}
