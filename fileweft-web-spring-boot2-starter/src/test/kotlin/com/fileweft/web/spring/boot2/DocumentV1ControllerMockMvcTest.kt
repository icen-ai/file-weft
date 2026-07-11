package com.fileweft.web.spring.boot2

import com.fileweft.application.document.DocumentDetailView
import com.fileweft.application.document.DocumentFolderReadAccess
import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.application.document.DocumentPageCursor
import com.fileweft.application.document.DocumentPageRequest
import com.fileweft.application.document.DocumentPageResult
import com.fileweft.application.document.DocumentQueryRepository
import com.fileweft.application.document.DocumentQueryService
import com.fileweft.application.document.DocumentSummaryView
import com.fileweft.application.document.DocumentVersionView
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.context.TraceContext
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.LifecycleState
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentApiReadFacade
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals

class DocumentV1ControllerMockMvcTest {
    @Test
    fun `returns a safe detail envelope with the optional trace id`() {
        val fixture = DocumentV1ControllerTestFixture()

        mvc(fixture, "trace-detail-1")
            .perform(get("/fileweft/v1/documents/document-1"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").value("trace-detail-1"))
            .andExpect(jsonPath("$.data.document.id").value("document-1"))
            .andExpect(jsonPath("$.data.document.lifecycleState").value("DRAFT"))
            .andExpect(jsonPath("$.data.versions[0].id").value("version-1"))
            .andExpect(content().string(not(containsString("contentHash"))))
            .andExpect(content().string(not(containsString("fileObjectId"))))
            .andExpect(content().string(not(containsString("storagePath"))))
            .andExpect(content().string(not(containsString("tenantId"))))
            .andExpect(content().string(not(containsString("operatorId"))))
    }

    @Test
    fun `parses only string page parameters while keeping tenant and user context server derived`() {
        val fixture = DocumentV1ControllerTestFixture()

        mvc(fixture)
            .perform(
                get("/fileweft/v1/documents")
                    .param("limit", "2")
                    .param("lifecycleState", "DRAFT")
                    .param("folderId", "finance")
                    .param("tenantId", "attacker-tenant")
                    .param("userId", "attacker-user"),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.items[0].id").value("document-1"))
            .andExpect(jsonPath("$.data.nextCursor").isString)

        assertEquals("tenant-a", fixture.lastTenantId?.value)
        assertEquals(2, fixture.lastPageRequest?.limit)
        assertEquals(LifecycleState.DRAFT, fixture.lastPageRequest?.lifecycleState)
        assertEquals("finance", fixture.lastPageRequest?.folderId)
        assertEquals("finance", fixture.lastRequiredFolderId)
    }

    @Test
    fun `maps a missing trusted user to a safe 401 response before querying`() {
        val fixture = DocumentV1ControllerTestFixture().apply { currentUser = null }

        mvc(fixture)
            .perform(get("/fileweft/v1/documents/document-1"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.message").value("Authentication is required."))
            .andExpect(content().string(not(containsString("current user"))))

        assertEquals(0, fixture.detailCalls)
    }

    @Test
    fun `maps authorization denial to a safe 403 response before querying`() {
        val fixture = DocumentV1ControllerTestFixture().apply {
            authorizationDecision = AuthorizationDecision(false, "host-policy=restricted-finance")
        }

        mvc(fixture)
            .perform(get("/fileweft/v1/documents/document-1"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("Access denied."))
            .andExpect(content().string(not(containsString("host-policy"))))

        assertEquals(0, fixture.detailCalls)
    }

    @Test
    fun `maps an absent authorized document to a safe 404 response`() {
        val fixture = DocumentV1ControllerTestFixture().apply { detail = null }

        mvc(fixture)
            .perform(get("/fileweft/v1/documents/private-document"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resource was not found."))
            .andExpect(content().string(not(containsString("private-document"))))
    }

    @Test
    fun `rejects malformed string parameters without exposing their value`() {
        val fixture = DocumentV1ControllerTestFixture()
        val mockMvc = mvc(fixture)

        mockMvc.perform(get("/fileweft/v1/documents").param("limit", "not-a-number"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Request is invalid."))
            .andExpect(content().string(not(containsString("not-a-number"))))

        mockMvc.perform(get("/fileweft/v1/documents").param("cursor", "***"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(content().string(not(containsString("***"))))

        mockMvc.perform(get("/fileweft/v1/documents").param("limit", "001"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(content().string(not(containsString("001"))))

        assertEquals(0, fixture.pageCalls)
    }

    @Test
    fun `maps an unavailable folder access feature to a safe 503 response before querying`() {
        val fixture = DocumentV1ControllerTestFixture().apply { folderReadAccessEnabled = false }

        mvc(fixture)
            .perform(get("/fileweft/v1/documents").param("folderId", "finance"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("The requested feature is unavailable."))
            .andExpect(content().string(not(containsString("Document folder filtering is unavailable."))))
            .andExpect(content().string(not(containsString("finance"))))

        assertEquals(0, fixture.pageCalls)
    }

    @Test
    fun `maps unexpected query failures to a safe 500 response locally`() {
        val fixture = DocumentV1ControllerTestFixture().apply {
            detailFailure = Exception("jdbc://internal-db/password=secret")
        }

        mvc(fixture)
            .perform(get("/fileweft/v1/documents/document-1"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
            .andExpect(content().string(not(containsString("jdbc://"))))
            .andExpect(content().string(not(containsString("password=secret"))))
    }

    private fun mvc(fixture: DocumentV1ControllerTestFixture, traceId: String? = null): MockMvc =
        MockMvcBuilders.standaloneSetup(
            DocumentV1Controller(
                documents = DocumentApiReadFacade(fixture.service()),
                responses = V1ApiResponseFactory(),
                traceContextProvider = traceId?.let(DocumentV1ControllerTestFixture::traceProvider),
            ),
        ).build()
}

internal class DocumentV1ControllerTestFixture {
    var currentUser: UserIdentity? = UserIdentity(Identifier("user-a"), "User A")
    var authorizationDecision: AuthorizationDecision = AuthorizationDecision(true)
    var detail: DocumentDetailView? = detailView()
    var page: DocumentPageResult = DocumentPageResult(
        items = listOf(summary()),
        nextCursor = DocumentPageCursor(300, Identifier("document-next")),
    )
    var detailFailure: Exception? = null
    var pageFailure: Exception? = null
    var detailCalls: Int = 0
    var pageCalls: Int = 0
    var lastTenantId: Identifier? = null
    var lastPageRequest: DocumentPageRequest? = null
    var lastRequiredFolderId: String? = null
    var folderReadAccessEnabled: Boolean = true

    fun service(): DocumentQueryService = DocumentQueryService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
        },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity? = this@DocumentV1ControllerTestFixture.currentUser

            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = authorizationDecision
        },
        queries = object : DocumentQueryRepository {
            override fun findDetail(
                tenantId: Identifier,
                documentId: Identifier,
                folderReadScope: DocumentFolderReadScope?,
            ): DocumentDetailView? {
                detailCalls++
                lastTenantId = tenantId
                detailFailure?.let { failure -> throw failure }
                return detail
            }

            override fun findPage(
                tenantId: Identifier,
                request: DocumentPageRequest,
                folderReadScope: DocumentFolderReadScope?,
            ): DocumentPageResult {
                pageCalls++
                lastTenantId = tenantId
                lastPageRequest = request
                pageFailure?.let { failure -> throw failure }
                return page
            }
        },
        transaction = DirectTransaction,
        folderReadAccess = if (folderReadAccessEnabled) {
            object : DocumentFolderReadAccess {
                override fun requireFolderForDocumentRead(folderId: String) {
                    lastRequiredFolderId = folderId
                }

                override fun readableFolderIds(): Set<String> = setOf("finance")
            }
        } else {
            null
        },
    )

    companion object {
        fun traceProvider(traceId: String): TraceContextProvider = object : TraceContextProvider {
            override fun currentTraceContext(): TraceContext = TraceContext(Identifier(traceId))
        }

        private fun detailView(): DocumentDetailView = DocumentDetailView(
            document = summary(),
            versions = listOf(
                DocumentVersionView(
                    id = Identifier("version-1"),
                    versionNumber = "1.0",
                    fileName = "budget.pdf",
                    contentLength = 256,
                    createdTime = 100,
                    updatedTime = 200,
                    contentType = "application/pdf",
                ),
            ),
        )

        private fun summary(): DocumentSummaryView = DocumentSummaryView(
            id = Identifier("document-1"),
            documentNumber = "DOC-001",
            title = "Budget forecast",
            lifecycleState = LifecycleState.DRAFT,
            createdTime = 100,
            updatedTime = 200,
            currentVersionId = Identifier("version-1"),
            folderId = "finance",
        )

        private object DirectTransaction : ApplicationTransaction {
            override fun <T> execute(action: () -> T): T = action()
        }
    }
}
