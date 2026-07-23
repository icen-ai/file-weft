package ai.icen.fw.web.spring.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.delivery.DocumentDeliveryRecoveryConflictException
import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.v1.document.DocumentDeliveryRecoveryResultDto
import ai.icen.fw.web.api.v1.document.DocumentDeliverySyncStatusDto
import ai.icen.fw.web.api.v1.document.DocumentSyncStatusDto
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentDeliveryRecoveryApiFacade
import ai.icen.fw.web.runtime.v1.document.DocumentSyncStatusApiFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentDeliveryRecoveryController
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentSyncStatusController
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V1DocumentSyncRecoveryControllerTest {
    @Test
    fun `exposes redacted sync status and both recovery routes`() {
        val calls = mutableListOf<RecoveryCall>()
        val mvc = mvc(sync(), recovery(calls))

        mvc.perform(get("$DOCUMENTS/document-1/sync-status"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))
            .andExpect(jsonPath("$.data.documentId").value("document-1"))
            .andExpect(jsonPath("$.data.deliveryTargets[0].deliveryId").value("delivery-1"))
            .andExpect(jsonPath("$.data.deliveryTargets[0].deliveryRetryable").value(true))
            .andExpect(jsonPath("$.data.deliveryTargets[0].lastErrorCategory").value("CONNECTOR_FAILURE"))
            .andExpect(jsonPath("$.data.deliveryTargets[0].connectorId").doesNotExist())
            .andExpect(jsonPath("$.data.deliveryTargets[0].externalId").doesNotExist())
            .andExpect(jsonPath("$.data.deliveryTargets[0].errorMessage").doesNotExist())

        mvc.perform(post("$DOCUMENTS/document-1/deliveries/delivery-1/retry").header(KEY, "delivery-key"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.operation").value("DELIVERY"))
        mvc.perform(post("$DOCUMENTS/document-1/deliveries/delivery-1/removal/retry").header(KEY, "removal-key"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.operation").value("REMOVAL"))

        assertEquals(
            listOf(
                RecoveryCall("retryDelivery", "document-1", "delivery-1", "delivery-key"),
                RecoveryCall("retryRemoval", "document-1", "delivery-1", "removal-key"),
            ),
            calls,
        )
    }

    @Test
    fun `rejects missing and repeated idempotency keys before recovery without leaking them`() {
        val calls = mutableListOf<RecoveryCall>()
        val mvc = mvc(sync(), recovery(calls))

        mvc.perform(post("$DOCUMENTS/document-1/deliveries/delivery-1/retry"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        mvc.perform(
            post("$DOCUMENTS/document-1/deliveries/delivery-1/removal/retry")
                .header(KEY, "private-one", "private-two"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Request is invalid."))
            .andExpect(content().string(not(containsString("private-one"))))
            .andExpect(content().string(not(containsString("private-two"))))

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `maps sync not found recovery conflict and unavailable recovery to fixed responses`() {
        mvc(sync(NoSuchElementException("private document")), recovery(mutableListOf()))
            .perform(get("$DOCUMENTS/private-document/sync-status"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resource was not found."))
            .andExpect(content().string(not(containsString("private-document"))))

        mvc(sync(), recovery(mutableListOf(), DocumentDeliveryRecoveryConflictException("private fence details")))
            .perform(post("$DOCUMENTS/document-1/deliveries/delivery-1/retry").header(KEY, "conflict-key"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
            .andExpect(jsonPath("$.message").value("Request conflicts with the current resource state."))
            .andExpect(content().string(not(containsString("private fence"))))

        mvc(sync(), DocumentDeliveryRecoveryApiFacade(0, emptyList(), emptyList()))
            .perform(post("$DOCUMENTS/document-1/deliveries/delivery-1/retry").header(KEY, "unavailable-key"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("The requested feature is unavailable."))
    }

    @Test
    fun `isolates trace provider failure from status and recovery success`() {
        val brokenTrace = object : TraceContextProvider {
            override fun currentTraceContext(): TraceContext = throw IllegalStateException("private trace backend")
        }
        val mvc = mvc(sync(), recovery(mutableListOf()), brokenTrace)

        listOf(
            mvc.perform(get("$DOCUMENTS/document-1/sync-status")),
            mvc.perform(post("$DOCUMENTS/document-1/deliveries/delivery-1/retry").header(KEY, "trace-key")),
        ).forEach { result ->
            result.andExpect(status().isOk)
                .andExpect(jsonPath("$.traceId").isEmpty())
                .andExpect(content().string(not(containsString("trace backend"))))
        }
    }

    private fun mvc(
        synchronization: DocumentSyncStatusApiFacade,
        recoveries: DocumentDeliveryRecoveryApiFacade,
        traces: TraceContextProvider = traceProvider(),
    ): MockMvc = MockMvcBuilders.standaloneSetup(
        V1DocumentSyncStatusController(synchronization, V1ApiResponseFactory(), traces),
        V1DocumentDeliveryRecoveryController(recoveries, V1ApiResponseFactory(), traces),
    ).setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper())).build()

    private fun sync(failure: RuntimeException? = null): DocumentSyncStatusApiFacade =
        Mockito.mock(DocumentSyncStatusApiFacade::class.java, Answer<Any?> { invocation ->
            if (invocation.method.name == "status") {
                failure?.let { throw it }
                DocumentSyncStatusDto(
                    "document-1",
                    listOf(
                        DocumentDeliverySyncStatusDto(
                            "delivery-1", "archive", "Archive", "REQUIRED", "FAILED", 2,
                            "NOT_REQUESTED", 0, true, false, 100,
                            lastErrorCategory = "CONNECTOR_FAILURE",
                        ),
                    ),
                )
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })

    private fun recovery(
        calls: MutableList<RecoveryCall>,
        failure: RuntimeException? = null,
    ): DocumentDeliveryRecoveryApiFacade =
        Mockito.mock(DocumentDeliveryRecoveryApiFacade::class.java, Answer<Any?> { invocation ->
            if (invocation.method.name == "retryDelivery" || invocation.method.name == "retryRemoval") {
                failure?.let { throw it }
                calls += RecoveryCall(
                    invocation.method.name,
                    invocation.arguments[0] as String,
                    invocation.arguments[1] as String,
                    invocation.arguments[2] as String,
                )
                DocumentDeliveryRecoveryResultDto(
                    invocation.arguments[0] as String,
                    invocation.arguments[1] as String,
                    if (invocation.method.name == "retryDelivery") "DELIVERY" else "REMOVAL",
                )
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })

    private fun traceProvider(): TraceContextProvider = object : TraceContextProvider {
        override fun currentTraceContext(): TraceContext = TraceContext(Identifier(TRACE_ID))
    }

    private data class RecoveryCall(val method: String, val documentId: String, val deliveryId: String, val key: String)

    private companion object {
        const val DOCUMENTS = "/fileweft/v1/documents"
        const val KEY = "Idempotency-Key"
        const val TRACE_ID = "trace-sync-recovery-3"
    }
}
