package ai.icen.fw.web.spring.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.document.DocumentSummaryView
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.workflow.DocumentWorkflowPageCursor
import ai.icen.fw.application.workflow.DocumentWorkflowPageRequest
import ai.icen.fw.application.workflow.DocumentWorkflowPageResult
import ai.icen.fw.application.workflow.WorkflowHistoryTaskView
import ai.icen.fw.application.workflow.WorkflowQueryRepository
import ai.icen.fw.application.workflow.WorkflowQueryService
import ai.icen.fw.application.workflow.WorkflowTaskInboxItemView
import ai.icen.fw.application.workflow.WorkflowTaskPageCursor
import ai.icen.fw.application.workflow.WorkflowTaskPageRequest
import ai.icen.fw.application.workflow.WorkflowTaskPageResult
import ai.icen.fw.application.workflow.WorkflowTaskView
import ai.icen.fw.application.workflow.WorkflowView
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.domain.workflow.WorkflowState
import ai.icen.fw.domain.workflow.WorkflowTaskState
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.workflow.WorkflowApiReadFacade
import ai.icen.fw.web.spring.boot3.v1.workflow.V1WorkflowReadController
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

class V1WorkflowReadControllerTest {
    @Test
    fun `returns both workflow read envelopes with cursors limits safe fields and trace`() {
        val fixture = Boot3WorkflowControllerFixture()
        val mvc = mvc(fixture, traceProvider("trace-workflow-3"))

        val inboxBody = mvc.perform(get("/fileweft/v1/workflows/tasks").param("limit", "2"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.traceId").value("trace-workflow-3"))
            .andExpect(jsonPath("$.data.items[0].task.id").value("task-1"))
            .andExpect(jsonPath("$.data.items[0].task.assignedToCurrentUser").value(true))
            .andExpect(jsonPath("$.data.items[0].actionableByCurrentUser").value(true))
            .andExpect(jsonPath("$.data.items[0].document.id").value("document-1"))
            .andExpect(jsonPath("$.data.nextCursor").isString)
            .andExpect(content().string(not(containsString("assigneeId"))))
            .andExpect(content().string(not(containsString("comment"))))
            .andExpect(content().string(not(containsString("tenantId"))))
            .andReturn().response.contentAsString

        assertEquals(2, fixture.lastPendingRequest?.limit)
        assertEquals(Identifier("tenant-1"), fixture.lastPendingTenant)
        assertEquals(Identifier("reviewer-1"), fixture.lastPendingUser)
        val taskCursor = ObjectMapper().readTree(inboxBody).path("data").path("nextCursor").asText()
        mvc.perform(get("/fileweft/v1/workflows/tasks").param("cursor", taskCursor).param("limit", "1"))
            .andExpect(status().isOk)
        assertEquals(90, fixture.lastPendingRequest?.cursor?.createdTime)
        assertEquals("task-next", fixture.lastPendingRequest?.cursor?.id?.value)

        val historyBody = mvc.perform(
            get("/fileweft/v1/documents/document-1/workflows").param("limit", "3"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").value("trace-workflow-3"))
            .andExpect(jsonPath("$.data.items[0].id").value("workflow-1"))
            .andExpect(jsonPath("$.data.items[0].documentId").value("document-1"))
            .andExpect(jsonPath("$.data.items[0].tasks[0].state").value("APPROVED"))
            .andExpect(jsonPath("$.data.nextCursor").isString)
            .andExpect(content().string(not(containsString("operatorId"))))
            .andExpect(content().string(not(containsString("comment"))))
            .andReturn().response.contentAsString

        assertEquals(Identifier("document-1"), fixture.lastHistoryDocument)
        assertEquals(3, fixture.lastHistoryRequest?.limit)
        val historyCursor = ObjectMapper().readTree(historyBody).path("data").path("nextCursor").asText()
        mvc.perform(get("/fileweft/v1/documents/document-1/workflows").param("cursor", historyCursor))
            .andExpect(status().isOk)
        assertEquals(80, fixture.lastHistoryRequest?.cursor?.createdTime)
        assertEquals("workflow-next", fixture.lastHistoryRequest?.cursor?.id?.value)
    }

    @Test
    fun `uses default limits and null cursors for both workflow routes`() {
        val fixture = Boot3WorkflowControllerFixture()
        val mvc = mvc(fixture)

        mvc.perform(get("/fileweft/v1/workflows/tasks")).andExpect(status().isOk)
        assertEquals(20, fixture.lastPendingRequest?.limit)
        assertNull(fixture.lastPendingRequest?.cursor)

        mvc.perform(get("/fileweft/v1/documents/document-1/workflows")).andExpect(status().isOk)
        assertEquals(20, fixture.lastHistoryRequest?.limit)
        assertNull(fixture.lastHistoryRequest?.cursor)
    }

    @Test
    fun `maps invalid limits damaged cursors and cross-kind cursors to fixed 400 responses`() {
        val fixture = Boot3WorkflowControllerFixture()
        val mvc = mvc(fixture)

        listOf("0", "101", "001", "12e3").forEach { invalidLimit ->
            mvc.perform(get("/fileweft/v1/workflows/tasks").param("limit", invalidLimit))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request is invalid."))
                .andExpect(content().string(not(containsString(invalidLimit))))
        }
        mvc.perform(get("/fileweft/v1/documents/document-1/workflows").param("cursor", "***private***"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(content().string(not(containsString("private"))))
        assertEquals(0, fixture.pendingCalls)
        assertEquals(0, fixture.historyCalls)

        val taskCursorBody = mvc.perform(get("/fileweft/v1/workflows/tasks"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val taskCursor = ObjectMapper().readTree(taskCursorBody).path("data").path("nextCursor").asText()
        mvc.perform(get("/fileweft/v1/documents/document-1/workflows").param("cursor", taskCursor))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        assertEquals(0, fixture.historyCalls)
    }

    @Test
    fun `maps 401 403 404 and 500 without exposing internal context`() {
        val unauthenticated = Boot3WorkflowControllerFixture().apply { currentUser = null }
        mvc(unauthenticated).perform(get("/fileweft/v1/workflows/tasks"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.message").value("Authentication is required."))
            .andExpect(content().string(not(containsString("current user"))))
        assertEquals(0, unauthenticated.pendingCalls)

        val denied = Boot3WorkflowControllerFixture().apply {
            authorizationDecision = AuthorizationDecision(false, "host-policy=secret")
        }
        mvc(denied).perform(get("/fileweft/v1/workflows/tasks"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("Access denied."))
            .andExpect(content().string(not(containsString("host-policy"))))
        assertEquals(0, denied.pendingCalls)

        val missing = Boot3WorkflowControllerFixture().apply { historyResult = null }
        mvc(missing).perform(get("/fileweft/v1/documents/private-document/workflows"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resource was not found."))
            .andExpect(content().string(not(containsString("private-document"))))

        val failed = Boot3WorkflowControllerFixture().apply {
            historyFailure = IllegalStateException("jdbc://hidden/password=secret")
        }
        mvc(failed).perform(get("/fileweft/v1/documents/document-1/workflows"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
            .andExpect(content().string(not(containsString("jdbc://"))))
            .andExpect(content().string(not(containsString("password=secret"))))
    }

    @Test
    fun `ignores trace provider failures for successful workflow queries`() {
        val fixture = Boot3WorkflowControllerFixture()
        val brokenTrace = object : TraceContextProvider {
            override fun currentTraceContext(): TraceContext = throw IllegalStateException("trace secret")
        }

        mvc(fixture, brokenTrace).perform(get("/fileweft/v1/workflows/tasks"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").isEmpty())
            .andExpect(content().string(not(containsString("trace secret"))))
        assertEquals(1, fixture.pendingCalls)
    }

    private fun mvc(
        fixture: Boot3WorkflowControllerFixture,
        traces: TraceContextProvider? = null,
    ): MockMvc = MockMvcBuilders.standaloneSetup(
        V1WorkflowReadController(
            WorkflowApiReadFacade(fixture.service()),
            V1ApiResponseFactory(),
            traces,
        ),
    ).build()

    private fun traceProvider(traceId: String): TraceContextProvider = object : TraceContextProvider {
        override fun currentTraceContext(): TraceContext = TraceContext(Identifier(traceId))
    }
}

internal class Boot3WorkflowControllerFixture {
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
    var lastHistoryDocument: Identifier? = null
    var lastHistoryRequest: DocumentWorkflowPageRequest? = null

    fun service(): WorkflowQueryService = WorkflowQueryService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
        },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity? = this@Boot3WorkflowControllerFixture.currentUser
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
                    summary(), "DOCUMENT_REVIEW", WorkflowState.PENDING,
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
