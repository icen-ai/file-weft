package com.fileweft.web.spring.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.delivery.DocumentDeliveryRecoveryConflictException
import com.fileweft.application.delivery.DocumentDeliveryRecoveryOperation
import com.fileweft.application.delivery.DocumentDeliveryRecoveryReceipt
import com.fileweft.application.delivery.DocumentDeliveryRemovalStatus
import com.fileweft.application.delivery.DocumentDeliveryStatus
import com.fileweft.application.delivery.DocumentDeliveryStatusView
import com.fileweft.application.delivery.DocumentSyncStatusQueryRepository
import com.fileweft.application.delivery.DocumentSyncStatusQueryService
import com.fileweft.application.delivery.DocumentSyncStatusView
import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.context.TraceContext
import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentDeliveryRecoveryApiFacade
import com.fileweft.web.runtime.v1.document.DocumentSyncStatusApiFacade
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentV1SyncRecoveryControllerMockMvcTest {
    @Test
    fun `exposes redacted sync status and both recovery routes`() {
        val calls = mutableListOf<RecoveryCall>()
        val mvc = mvc(syncFacade(syncStatus()), recordingRecovery(calls))

        mvc.perform(get("$DOCUMENTS/document-1/sync-status"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))
            .andExpect(jsonPath("$.data.documentId").value("document-1"))
            .andExpect(jsonPath("$.data.deliveryTargets[0].deliveryId").value("delivery-1"))
            .andExpect(jsonPath("$.data.deliveryTargets[0].deliveryStatus").value("FAILED"))
            .andExpect(jsonPath("$.data.deliveryTargets[0].deliveryRetryable").value(true))
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
    fun `rejects missing and repeated idempotency keys before recovery without echoing them`() {
        val calls = mutableListOf<RecoveryCall>()
        val mvc = mvc(syncFacade(syncStatus()), recordingRecovery(calls))

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
        mvc(syncFacade(null), recordingRecovery(mutableListOf()))
            .perform(get("$DOCUMENTS/private-document/sync-status"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resource was not found."))
            .andExpect(content().string(not(containsString("private-document"))))

        mvc(
            syncFacade(syncStatus()),
            recordingRecovery(mutableListOf(), DocumentDeliveryRecoveryConflictException("private fence details")),
        ).perform(post("$DOCUMENTS/document-1/deliveries/delivery-1/retry").header(KEY, "conflict-key"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
            .andExpect(jsonPath("$.message").value("Request conflicts with the current resource state."))
            .andExpect(content().string(not(containsString("private fence"))))

        mvc(syncFacade(syncStatus()), DocumentDeliveryRecoveryApiFacade(0, emptyList(), emptyList()))
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
        val mvc = mvc(syncFacade(syncStatus()), recordingRecovery(mutableListOf()), brokenTrace)

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
        DocumentV1SyncStatusController(synchronization, V1ApiResponseFactory(), traces),
        DocumentV1DeliveryRecoveryController(recoveries, V1ApiResponseFactory(), traces),
    ).setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper())).build()

    private fun syncFacade(result: DocumentSyncStatusView?): DocumentSyncStatusApiFacade =
        DocumentSyncStatusApiFacade(
            DocumentSyncStatusQueryService(
                object : TenantProvider {
                    override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-private"))
                },
                object : UserRealmProvider {
                    override fun currentUser(): UserIdentity = UserIdentity(Identifier("reader-1"), "Reader")
                    override fun findUser(userId: Identifier): UserIdentity? = null
                },
                object : AuthorizationProvider {
                    override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
                },
                object : DocumentSyncStatusQueryRepository {
                    override fun findByDocument(
                        tenantId: Identifier,
                        documentId: Identifier,
                        folderReadScope: DocumentFolderReadScope?,
                    ): DocumentSyncStatusView? = result
                },
                DirectTransaction,
            ),
        )

    private fun recordingRecovery(
        calls: MutableList<RecoveryCall>,
        failure: RuntimeException? = null,
    ): DocumentDeliveryRecoveryApiFacade {
        val facade = DocumentDeliveryRecoveryApiFacade(0, emptyList(), emptyList())
        val field = DocumentDeliveryRecoveryApiFacade::class.java.getDeclaredField("commands").apply { isAccessible = true }
        val commands = Proxy.newProxyInstance(field.type.classLoader, arrayOf(field.type)) { _, method, arguments ->
            failure?.let { throw it }
            val documentId = arguments[0] as Identifier
            val deliveryId = arguments[1] as Identifier
            val key = arguments[2] as String
            calls += RecoveryCall(method.name, documentId.value, deliveryId.value, key)
            DocumentDeliveryRecoveryReceipt(
                documentId,
                deliveryId,
                if (method.name == "retryDelivery") {
                    DocumentDeliveryRecoveryOperation.DELIVERY
                } else {
                    DocumentDeliveryRecoveryOperation.REMOVAL
                },
            )
        }
        field.set(facade, commands)
        return facade
    }

    private fun syncStatus(): DocumentSyncStatusView = DocumentSyncStatusView(
        Identifier("document-1"),
        listOf(
            DocumentDeliveryStatusView(
                Identifier("delivery-1"), "archive", "Archive", DeliveryRequirement.REQUIRED,
                DocumentDeliveryStatus.FAILED, 2, DocumentDeliveryRemovalStatus.NOT_REQUESTED, 0,
                deliveryRetryable = true, removalRetryable = false, updatedTime = 100,
            ),
        ),
    )

    private fun traceProvider(): TraceContextProvider = object : TraceContextProvider {
        override fun currentTraceContext(): TraceContext = TraceContext(Identifier(TRACE_ID))
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private data class RecoveryCall(val method: String, val documentId: String, val deliveryId: String, val key: String)

    private companion object {
        const val DOCUMENTS = "/fileweft/v1/documents"
        const val KEY = "Idempotency-Key"
        const val TRACE_ID = "trace-sync-recovery-2"
    }
}
