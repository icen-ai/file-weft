package ai.icen.fw.web.spring.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.lifecycle.DocumentLifecycleReceipt
import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentLifecycleApiFacade
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
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentV1LifecycleControllerMockMvcTest {
    @Test
    fun `maps all nine routes to 200 stable envelopes and populated immutable commands`() {
        val recorder = RecordingLifecycleCommands()
        val mockMvc = mvc(recorder.facade())
        val expectedResults = listOf(
            StableResult("result-revise"),
            StableResult("result-publish"),
            StableResult("result-offline"),
            StableResult("result-restore"),
            StableResult("result-archive"),
            StableResult("result-submit-document", "result-submit-workflow"),
            StableResult("result-approve-document", "result-approve-workflow", "result-approve-task"),
            StableResult("result-withdraw-document", "result-withdraw-workflow"),
            StableResult("result-reject-document", "result-reject-workflow", "result-reject-task"),
        )

        lifecycleRequests().zip(expectedResults).forEachIndexed { index, (request, expected) ->
            val response = mockMvc.perform(request.header(IDEMPOTENCY_KEY_HEADER, "lifecycle-key-$index"))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.data.documentId").value(expected.documentId))
                .andExpect(jsonPath("$.data.tenantId").doesNotExist())
            if (expected.workflowId == null) {
                response.andExpect(jsonPath("$.data.workflowId").isEmpty())
            } else {
                response.andExpect(jsonPath("$.data.workflowId").value(expected.workflowId))
            }
            if (expected.taskId == null) {
                response.andExpect(jsonPath("$.data.taskId").isEmpty())
            } else {
                response.andExpect(jsonPath("$.data.taskId").value(expected.taskId))
            }
        }

        assertEquals(
            listOf("revise", "publish", "offline", "restore", "archive", "submit", "approve", "withdraw", "reject"),
            recorder.calls.map { call -> call.method },
        )
        assertEquals("primary", recorder.calls[1].arguments[1])
        assertEquals("four-eyes", recorder.calls[5].arguments[1])
        assertEquals(listOf("approved", "primary"), recorder.calls[6].arguments.slice(2..3))
        assertEquals("rejected", recorder.calls[8].arguments[2])
        assertEquals(
            (0..8).map { index -> "lifecycle-key-$index" },
            recorder.calls.map { call -> call.arguments.last() },
        )
    }

    @Test
    fun `maps all nine lifecycle routes and fails closed when their capabilities are absent`() {
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
        val recorder = RecordingLifecycleCommands()
        val mockMvc = mvc(recorder.facade())
        val optionalBodyRoutes = listOf(
            "/fileweft/v1/documents/document-1/publish",
            "/fileweft/v1/documents/document-1/submit",
            "/fileweft/v1/workflows/workflow-1/tasks/task-1/approve",
            "/fileweft/v1/workflows/workflow-1/tasks/task-1/reject",
        )

        optionalBodyRoutes.forEachIndexed { index, path ->
            mockMvc.perform(post(path).header(IDEMPOTENCY_KEY_HEADER, "default-key-$index"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value("OK"))
        }

        assertEquals(listOf("publish", "submit", "approve", "reject"), recorder.calls.map { it.method })
        assertNull(recorder.calls[0].arguments[1])
        assertNull(recorder.calls[1].arguments[1])
        assertNull(recorder.calls[2].arguments[2])
        assertNull(recorder.calls[2].arguments[3])
        assertNull(recorder.calls[3].arguments[2])
        assertEquals(
            (0..3).map { index -> "default-key-$index" },
            recorder.calls.map { call -> call.arguments.last() },
        )
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
        post("/fileweft/v1/workflows/workflow-1/withdraw"),
        post("/fileweft/v1/workflows/workflow-1/tasks/task-1/reject")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"comment\":\"rejected\"}"),
    )

    private fun mvc(
        facade: DocumentLifecycleApiFacade = unavailableFacade(),
    ): MockMvc = MockMvcBuilders.standaloneSetup(
        DocumentV1LifecycleController(
            documents = facade,
            responses = V1ApiResponseFactory(),
            traceContextProvider = object : TraceContextProvider {
                override fun currentTraceContext(): TraceContext =
                    TraceContext(Identifier(TRACE_ID))
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

    private class RecordingLifecycleCommands {
        val calls = mutableListOf<FacadeCall>()

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        fun facade(): DocumentLifecycleApiFacade = DocumentLifecycleApiFacade.forTesting(
            revise = { documentId, key ->
                record("revise", documentId.value, key)
                receipt("result-revise")
            },
            publish = { documentId, profileId, key ->
                record("publish", documentId.value, profileId, key)
                receipt("result-publish")
            },
            offline = { documentId, key ->
                record("offline", documentId.value, key)
                receipt("result-offline")
            },
            restore = { documentId, key ->
                record("restore", documentId.value, key)
                receipt("result-restore")
            },
            archive = { documentId, key ->
                record("archive", documentId.value, key)
                receipt("result-archive")
            },
            submitForReview = { documentId, routeId, key ->
                record("submit", documentId.value, routeId, key)
                receipt("result-submit-document", "result-submit-workflow")
            },
            approve = { workflowId, taskId, comment, profileId, key ->
                record("approve", workflowId.value, taskId.value, comment, profileId, key)
                receipt("result-approve-document", "result-approve-workflow", "result-approve-task")
            },
            withdrawReview = { workflowId, key ->
                record("withdraw", workflowId.value, key)
                receipt("result-withdraw-document", "result-withdraw-workflow")
            },
            reject = { workflowId, taskId, comment, key ->
                record("reject", workflowId.value, taskId.value, comment, key)
                receipt("result-reject-document", "result-reject-workflow", "result-reject-task")
            },
        )

        private fun record(method: String, vararg arguments: Any?) {
            calls += FacadeCall(method, arguments.toList())
        }

        private fun receipt(
            documentId: String,
            workflowId: String? = null,
            taskId: String? = null,
        ): DocumentLifecycleReceipt = DocumentLifecycleReceipt(
            documentId = Identifier(documentId),
            workflowId = workflowId?.let(::Identifier),
            taskId = taskId?.let(::Identifier),
        )
    }

    private data class FacadeCall(
        val method: String,
        val arguments: List<Any?>,
    )

    private data class StableResult(
        val documentId: String,
        val workflowId: String? = null,
        val taskId: String? = null,
    )

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        const val REVISE_PATH = "/fileweft/v1/documents/document-1/revise"
        const val TRACE_ID = "trace-lifecycle-2"
    }
}
