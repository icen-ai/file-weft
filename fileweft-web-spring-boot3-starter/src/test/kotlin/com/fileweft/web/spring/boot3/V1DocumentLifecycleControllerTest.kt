package com.fileweft.web.spring.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.core.context.TraceContext
import com.fileweft.core.id.Identifier
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.api.v1.document.DocumentLifecycleCommandResultDto
import com.fileweft.web.api.v1.document.PublishDocumentCommand
import com.fileweft.web.api.v1.workflow.ApproveWorkflowTaskCommand
import com.fileweft.web.api.v1.workflow.RejectWorkflowTaskCommand
import com.fileweft.web.api.v1.workflow.SubmitDocumentReviewCommand
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentLifecycleApiFacade
import com.fileweft.web.spring.boot3.v1.document.V1DocumentLifecycleController
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V1DocumentLifecycleControllerTest {
    @Test
    fun `exposes all eight lifecycle routes and nullable bodies select immutable command defaults`() {
        val calls = mutableListOf<LifecycleCall>()
        val mvc = mockMvc(recordingFacade(calls))

        mvc.perform(post("$DOCUMENTS/document-1/revise").header(IDEMPOTENCY_KEY, "revise-1"))
            .andExpectLifecycleSuccess("document-1")
        mvc.perform(post("$DOCUMENTS/document-1/publish").header(IDEMPOTENCY_KEY, "publish-1"))
            .andExpectLifecycleSuccess("document-1")
        mvc.perform(post("$DOCUMENTS/document-1/offline").header(IDEMPOTENCY_KEY, "offline-1"))
            .andExpectLifecycleSuccess("document-1")
        mvc.perform(post("$DOCUMENTS/document-1/restore").header(IDEMPOTENCY_KEY, "restore-1"))
            .andExpectLifecycleSuccess("document-1")
        mvc.perform(post("$DOCUMENTS/document-1/archive").header(IDEMPOTENCY_KEY, "archive-1"))
            .andExpectLifecycleSuccess("document-1")
        mvc.perform(post("$DOCUMENTS/document-1/submit").header(IDEMPOTENCY_KEY, "submit-1"))
            .andExpectLifecycleSuccess("document-1")
        mvc.perform(post("$WORKFLOWS/workflow-1/tasks/task-1/approve").header(IDEMPOTENCY_KEY, "approve-1"))
            .andExpectLifecycleSuccess("document-1")
        mvc.perform(post("$WORKFLOWS/workflow-1/tasks/task-1/reject").header(IDEMPOTENCY_KEY, "reject-1"))
            .andExpectLifecycleSuccess("document-1")

        assertEquals(
            listOf("revise", "publish", "offline", "restore", "archive", "submitForReview", "approve", "reject"),
            calls.map { call -> call.method },
        )
        assertNull(assertIs<PublishDocumentCommand>(calls[1].arguments[1]).deliveryProfileId)
        assertNull(assertIs<SubmitDocumentReviewCommand>(calls[5].arguments[1]).reviewRouteId)
        assertNull(assertIs<ApproveWorkflowTaskCommand>(calls[6].arguments[2]).comment)
        assertNull(assertIs<ApproveWorkflowTaskCommand>(calls[6].arguments[2]).deliveryProfileId)
        assertNull(assertIs<RejectWorkflowTaskCommand>(calls[7].arguments[2]).comment)
        assertEquals(
            listOf("revise-1", "publish-1", "offline-1", "restore-1", "archive-1", "submit-1", "approve-1", "reject-1"),
            calls.map { call -> call.arguments.last() },
        )
    }

    @Test
    fun `immediately converts mutable request beans to populated immutable commands`() {
        val calls = mutableListOf<LifecycleCall>()
        val mvc = mockMvc(recordingFacade(calls))

        mvc.perform(
            post("$DOCUMENTS/document-1/publish")
                .header(IDEMPOTENCY_KEY, "publish-json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"deliveryProfileId":"regulated"}"""),
        ).andExpectLifecycleSuccess("document-1")
        mvc.perform(
            post("$DOCUMENTS/document-1/submit")
                .header(IDEMPOTENCY_KEY, "submit-json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reviewRouteId":"dual-control"}"""),
        ).andExpectLifecycleSuccess("document-1")
        mvc.perform(
            post("$WORKFLOWS/workflow-1/tasks/task-1/approve")
                .header(IDEMPOTENCY_KEY, "approve-json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"comment":"approved","deliveryProfileId":"regulated"}"""),
        ).andExpectLifecycleSuccess("document-1")
        mvc.perform(
            post("$WORKFLOWS/workflow-1/tasks/task-1/reject")
                .header(IDEMPOTENCY_KEY, "reject-json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"comment":"needs revision"}"""),
        ).andExpectLifecycleSuccess("document-1")

        assertEquals("regulated", assertIs<PublishDocumentCommand>(calls[0].arguments[1]).deliveryProfileId)
        assertEquals("dual-control", assertIs<SubmitDocumentReviewCommand>(calls[1].arguments[1]).reviewRouteId)
        val approval = assertIs<ApproveWorkflowTaskCommand>(calls[2].arguments[2])
        assertEquals("approved", approval.comment)
        assertEquals("regulated", approval.deliveryProfileId)
        assertEquals(
            "needs revision",
            assertIs<RejectWorkflowTaskCommand>(calls[3].arguments[2]).comment,
        )
    }

    @Test
    fun `rejects unsafe idempotency headers before the facade without leaking values`() {
        val calls = mutableListOf<LifecycleCall>()
        val mvc = mockMvc(recordingFacade(calls))

        val missing = mvc.perform(post("$DOCUMENTS/document-1/revise"))
            .andExpectInvalidRequest()
            .andReturn().response.contentAsString
        val duplicate = mvc.perform(
            post("$DOCUMENTS/document-1/revise")
                .header(IDEMPOTENCY_KEY, "private-key-one", "private-key-two"),
        ).andExpectInvalidRequest().andReturn().response.contentAsString
        val invalid = mvc.perform(
            post("$DOCUMENTS/document-1/revise")
                .header(IDEMPOTENCY_KEY, "private key/with/path"),
        ).andExpectInvalidRequest().andReturn().response.contentAsString

        assertTrue(calls.isEmpty())
        listOf(missing, duplicate, invalid).forEach { body ->
            assertFalse(body.contains("private-key"))
            assertFalse(body.contains("private key"))
            assertFalse(body.contains("with/path"))
        }
    }

    @Test
    fun `maps missing lifecycle capability to a fixed traced 503 response`() {
        val unavailable = DocumentLifecycleApiFacade(
            catalogAccessCount = 0,
            flatLifecycles = emptyList(),
            catalogLifecycles = emptyList(),
            flatReviews = emptyList(),
            catalogReviews = emptyList(),
        )

        mockMvc(unavailable)
            .perform(post("$DOCUMENTS/document-1/revise").header(IDEMPOTENCY_KEY, "revise-unavailable"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("The requested feature is unavailable."))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))
    }

    @Test
    fun `maps unexpected facade failures without exposing internal details`() {
        val failure = IllegalStateException("jdbc://internal-host/private-password")
        val facade = Mockito.mock(DocumentLifecycleApiFacade::class.java, Answer<Any?> { invocation ->
            if (invocation.method.returnType == DocumentLifecycleCommandResultDto::class.java) throw failure
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        })

        val body = mockMvc(facade)
            .perform(post("$DOCUMENTS/document-1/archive").header(IDEMPOTENCY_KEY, "archive-safe"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
            .andReturn().response.contentAsString

        assertFalse(body.contains("internal-host"))
        assertFalse(body.contains("private-password"))
    }

    private fun recordingFacade(calls: MutableList<LifecycleCall>): DocumentLifecycleApiFacade =
        Mockito.mock(DocumentLifecycleApiFacade::class.java, Answer<Any?> { invocation ->
            if (invocation.method.returnType == DocumentLifecycleCommandResultDto::class.java) {
                calls += LifecycleCall(invocation.method.name, invocation.arguments.toList())
                DocumentLifecycleCommandResultDto(
                    documentId = "document-1",
                    workflowId = if (invocation.method.name in setOf("submitForReview", "approve", "reject")) {
                        "workflow-1"
                    } else {
                        null
                    },
                    taskId = if (invocation.method.name in setOf("approve", "reject")) "task-1" else null,
                )
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })

    private fun mockMvc(facade: DocumentLifecycleApiFacade): MockMvc {
        val controller = V1DocumentLifecycleController(
            documents = facade,
            responses = V1ApiResponseFactory(),
            traceContextProvider = object : TraceContextProvider {
                override fun currentTraceContext(): TraceContext = TraceContext(Identifier(TRACE_ID))
            },
        )
        return MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper()))
            .build()
    }

    private fun ResultActions.andExpectLifecycleSuccess(documentId: String): ResultActions =
        andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))
            .andExpect(jsonPath("$.data.documentId").value(documentId))
            .andExpect(jsonPath("$.data.tenantId").doesNotExist())

    private fun ResultActions.andExpectInvalidRequest(): ResultActions =
        andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Request is invalid."))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))

    private data class LifecycleCall(
        val method: String,
        val arguments: List<Any?>,
    )

    private companion object {
        const val DOCUMENTS: String = "/fileweft/v1/documents"
        const val WORKFLOWS: String = "/fileweft/v1/workflows"
        const val IDEMPOTENCY_KEY: String = "Idempotency-Key"
        const val TRACE_ID: String = "trace-lifecycle-3"
    }
}
