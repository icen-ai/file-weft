package com.fileweft.web.spring.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.application.document.DocumentSummaryView
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.application.workflow.DocumentWorkflowPageRequest
import com.fileweft.application.workflow.DocumentWorkflowPageResult
import com.fileweft.application.workflow.DocumentWorkflowPageCursor
import com.fileweft.application.workflow.WorkflowHistoryTaskView
import com.fileweft.application.workflow.WorkflowQueryRepository
import com.fileweft.application.workflow.WorkflowQueryService
import com.fileweft.application.workflow.WorkflowTaskInboxItemView
import com.fileweft.application.workflow.WorkflowTaskPageCursor
import com.fileweft.application.workflow.WorkflowTaskPageRequest
import com.fileweft.application.workflow.WorkflowTaskPageResult
import com.fileweft.application.workflow.WorkflowTaskView
import com.fileweft.application.workflow.WorkflowView
import com.fileweft.core.context.TenantContext
import com.fileweft.core.context.TraceContext
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.LifecycleState
import com.fileweft.domain.workflow.WorkflowState
import com.fileweft.domain.workflow.WorkflowTaskState
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.workflow.WorkflowApiReadFacade
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
import kotlin.test.assertNull

class WorkflowV1ControllerMockMvcTest {
    @Test
    fun `returns the approval inbox envelope opaque cursor bounded limit and trace`() {
        val fixture = WorkflowV1ControllerFixture()
        val mvc = mvc(fixture, traceProvider("trace-workflow-1"))

        val first = mvc.perform(get("/fileweft/v1/workflows/tasks").param("limit", "2"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.traceId").value("trace-workflow-1"))
            .andExpect(jsonPath("$.data.items[0].task.id").value("task-1"))
            .andExpect(jsonPath("$.data.items[0].task.assignedToCurrentUser").value(true))
            .andExpect(jsonPath("$.data.items[0].actionableByCurrentUser").value(true))
            .andExpect(jsonPath("$.data.items[0].document.id").value("document-1"))
            .andExpect(jsonPath("$.data.items[0].workflowType").value("DOCUMENT_REVIEW"))
            .andExpect(jsonPath("$.data.nextCursor").isString)
            .andExpect(content().string(not(containsString("assigneeId"))))
            .andExpect(content().string(not(containsString("comment"))))
            .andExpect(content().string(not(containsString("tenantId"))))
            .andReturn()

        assertEquals(2, fixture.lastPendingRequest?.limit)
        assertEquals(Identifier("tenant-1"), fixture.lastPendingTenant)
        assertEquals(Identifier("reviewer-1"), fixture.lastPendingUser)
        val cursor = ObjectMapper().readTree(first.response.contentAsString).path("data").path("nextCursor").asText()

        mvc.perform(get("/fileweft/v1/workflows/tasks").param("cursor", cursor).param("limit", "1"))
            .andExpect(status().isOk)

        assertEquals(90, fixture.lastPendingRequest?.cursor?.createdTime)
        assertEquals("task-next", fixture.lastPendingRequest?.cursor?.id?.value)
        assertEquals(1, fixture.lastPendingRequest?.limit)
    }

    @Test
    fun `returns document workflow history with an opaque history cursor and trace`() {
        val fixture = WorkflowV1ControllerFixture()
        val mvc = mvc(fixture, traceProvider("trace-history-1"))

        val first = mvc.perform(
            get("/fileweft/v1/documents/document-1/workflows").param("limit", "3"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").value("trace-history-1"))
            .andExpect(jsonPath("$.data.items[0].id").value("workflow-1"))
            .andExpect(jsonPath("$.data.items[0].documentId").value("document-1"))
            .andExpect(jsonPath("$.data.items[0].tasks[0].id").value("task-1"))
            .andExpect(jsonPath("$.data.items[0].tasks[0].state").value("APPROVED"))
            .andExpect(jsonPath("$.data.nextCursor").isString)
            .andExpect(content().string(not(containsString("assigneeId"))))
            .andExpect(content().string(not(containsString("operatorId"))))
            .andExpect(content().string(not(containsString("comment"))))
            .andReturn()

        assertEquals(Identifier("document-1"), fixture.lastHistoryDocument)
        assertEquals(3, fixture.lastHistoryRequest?.limit)
        val cursor = ObjectMapper().readTree(first.response.contentAsString).path("data").path("nextCursor").asText()

        mvc.perform(
            get("/fileweft/v1/documents/document-1/workflows").param("cursor", cursor),
        ).andExpect(status().isOk)

        assertEquals(80, fixture.lastHistoryRequest?.cursor?.createdTime)
        assertEquals("workflow-next", fixture.lastHistoryRequest?.cursor?.id?.value)
    }

    @Test
    fun `uses the same default page size for both workflow routes`() {
        val fixture = WorkflowV1ControllerFixture()
        val mvc = mvc(fixture)

        mvc.perform(get("/fileweft/v1/workflows/tasks")).andExpect(status().isOk)
        assertEquals(20, fixture.lastPendingRequest?.limit)
        assertNull(fixture.lastPendingRequest?.cursor)

        mvc.perform(get("/fileweft/v1/documents/document-1/workflows")).andExpect(status().isOk)
        assertEquals(20, fixture.lastHistoryRequest?.limit)
        assertNull(fixture.lastHistoryRequest?.cursor)
    }

    @Test
    fun `rejects malformed limits and cursors with fixed 400 responses and no value echo`() {
        val fixture = WorkflowV1ControllerFixture()
        val mvc = mvc(fixture)

        listOf("0", "101", "001", "12e3").forEach { invalidLimit ->
            mvc.perform(get("/fileweft/v1/workflows/tasks").param("limit", invalidLimit))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request is invalid."))
                .andExpect(content().string(not(containsString(invalidLimit))))
        }
        mvc.perform(get("/fileweft/v1/workflows/tasks").param("cursor", "***private-cursor***"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(content().string(not(containsString("private-cursor"))))
        mvc.perform(get("/fileweft/v1/documents/document-1/workflows").param("limit", "-1"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        assertEquals(0, fixture.pendingCalls)
        assertEquals(0, fixture.historyCalls)
    }

    @Test
    fun `rejects a task cursor on the document history route`() {
        val fixture = WorkflowV1ControllerFixture()
        val mvc = mvc(fixture)
        val body = mvc.perform(get("/fileweft/v1/workflows/tasks"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val taskCursor = ObjectMapper().readTree(body).path("data").path("nextCursor").asText()
        fixture.historyCalls = 0

        mvc.perform(
            get("/fileweft/v1/documents/document-1/workflows").param("cursor", taskCursor),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        assertEquals(0, fixture.historyCalls)
    }

    @Test
    fun `maps authentication authorization missing history and unexpected failures safely`() {
        val unauthenticated = WorkflowV1ControllerFixture().apply { currentUser = null }
        mvc(unauthenticated).perform(get("/fileweft/v1/workflows/tasks"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.message").value("Authentication is required."))
            .andExpect(content().string(not(containsString("current user"))))
        assertEquals(0, unauthenticated.pendingCalls)

        val denied = WorkflowV1ControllerFixture().apply {
            authorizationDecision = AuthorizationDecision(false, "host-policy=restricted-secret")
        }
        mvc(denied).perform(get("/fileweft/v1/workflows/tasks"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("Access denied."))
            .andExpect(content().string(not(containsString("host-policy"))))
            .andExpect(content().string(not(containsString("restricted-secret"))))
        assertEquals(0, denied.pendingCalls)

        val missing = WorkflowV1ControllerFixture().apply { historyResult = null }
        mvc(missing).perform(get("/fileweft/v1/documents/private-document/workflows"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resource was not found."))
            .andExpect(content().string(not(containsString("private-document"))))

        val failed = WorkflowV1ControllerFixture().apply {
            pendingFailure = IllegalStateException("jdbc://private-db/password=secret")
        }
        mvc(failed).perform(get("/fileweft/v1/workflows/tasks"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
            .andExpect(content().string(not(containsString("jdbc://"))))
            .andExpect(content().string(not(containsString("password=secret"))))
    }

    @Test
    fun `ignores trace provider failures without failing a successful workflow read`() {
        val fixture = WorkflowV1ControllerFixture()
        val brokenTrace = object : TraceContextProvider {
            override fun currentTraceContext(): TraceContext = throw IllegalStateException("trace backend secret")
        }

        mvc(fixture, brokenTrace).perform(get("/fileweft/v1/workflows/tasks"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").isEmpty())
            .andExpect(content().string(not(containsString("trace backend"))))

        assertEquals(1, fixture.pendingCalls)
    }

    private fun mvc(
        fixture: WorkflowV1ControllerFixture,
        traces: TraceContextProvider? = null,
    ): MockMvc = MockMvcBuilders.standaloneSetup(
        WorkflowV1Controller(
            workflows = WorkflowApiReadFacade(fixture.service()),
            responses = V1ApiResponseFactory(),
            traceContextProvider = traces,
        ),
    ).build()

    private fun traceProvider(traceId: String): TraceContextProvider = object : TraceContextProvider {
        override fun currentTraceContext(): TraceContext = TraceContext(Identifier(traceId))
    }
}

internal class WorkflowV1ControllerFixture {
    var currentUser: UserIdentity? = UserIdentity(Identifier("reviewer-1"), "Reviewer One")
    var authorizationDecision: AuthorizationDecision = AuthorizationDecision(true)
    var pendingResult: WorkflowTaskPageResult = pendingPage()
    var historyResult: DocumentWorkflowPageResult? = historyPage()
    var pendingFailure: RuntimeException? = null
    var historyFailure: RuntimeException? = null
    var pendingCalls: Int = 0
    var historyCalls: Int = 0
    var lastPendingTenant: Identifier? = null
    var lastPendingUser: Identifier? = null
    var lastPendingRequest: WorkflowTaskPageRequest? = null
    var lastHistoryTenant: Identifier? = null
    var lastHistoryDocument: Identifier? = null
    var lastHistoryRequest: DocumentWorkflowPageRequest? = null

    fun service(): WorkflowQueryService = WorkflowQueryService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
        },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity? = this@WorkflowV1ControllerFixture.currentUser
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = authorizationDecision
        },
        queries = object : WorkflowQueryRepository {
            override fun findPendingTaskPage(
                tenantId: Identifier,
                currentUserId: Identifier,
                request: WorkflowTaskPageRequest,
                folderReadScope: DocumentFolderReadScope?,
            ): WorkflowTaskPageResult {
                pendingCalls++
                lastPendingTenant = tenantId
                lastPendingUser = currentUserId
                lastPendingRequest = request
                pendingFailure?.let { throw it }
                return pendingResult
            }

            override fun findDocumentWorkflowPage(
                tenantId: Identifier,
                documentId: Identifier,
                request: DocumentWorkflowPageRequest,
                folderReadScope: DocumentFolderReadScope?,
            ): DocumentWorkflowPageResult? {
                historyCalls++
                lastHistoryTenant = tenantId
                lastHistoryDocument = documentId
                lastHistoryRequest = request
                historyFailure?.let { throw it }
                return historyResult
            }
        },
        transaction = object : ApplicationTransaction {
            override fun <T> execute(action: () -> T): T = action()
        },
    )

    companion object {
        private fun pendingPage(): WorkflowTaskPageResult = WorkflowTaskPageResult(
            listOf(
                WorkflowTaskInboxItemView(
                    WorkflowTaskView(
                        Identifier("task-1"), Identifier("workflow-1"), WorkflowTaskState.PENDING,
                        100, 110, assignedToCurrentUser = true,
                    ),
                    summary(),
                    "DOCUMENT_REVIEW",
                    WorkflowState.PENDING,
                ),
            ),
            WorkflowTaskPageCursor(90, Identifier("task-next")),
        )

        private fun historyPage(): DocumentWorkflowPageResult = DocumentWorkflowPageResult(
            listOf(
                WorkflowView(
                    Identifier("workflow-1"), Identifier("document-1"), "DOCUMENT_REVIEW", WorkflowState.APPROVED,
                    100, 120,
                    listOf(WorkflowHistoryTaskView(Identifier("task-1"), WorkflowTaskState.APPROVED, 100, 120)),
                ),
            ),
            DocumentWorkflowPageCursor(80, Identifier("workflow-next")),
        )

        private fun summary(): DocumentSummaryView = DocumentSummaryView(
            Identifier("document-1"), "DOC-001", "Approval contract", LifecycleState.PENDING_REVIEW,
            90, 110, Identifier("version-1"), "finance",
        )
    }
}
