package com.fileweft.web.runtime.v1.workflow

import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.application.document.DocumentSummaryView
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.application.workflow.DocumentWorkflowPageCursor
import com.fileweft.application.workflow.DocumentWorkflowPageRequest
import com.fileweft.application.workflow.DocumentWorkflowPageResult
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
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.LifecycleState
import com.fileweft.domain.workflow.WorkflowState
import com.fileweft.domain.workflow.WorkflowTaskState
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.web.api.v1.workflow.DocumentWorkflowDto
import com.fileweft.web.api.v1.workflow.DocumentWorkflowPageQuery
import com.fileweft.web.api.v1.workflow.WorkflowHistoryTaskDto
import com.fileweft.web.api.v1.workflow.WorkflowTaskDto
import com.fileweft.web.api.v1.workflow.WorkflowTaskInboxItemDto
import com.fileweft.web.api.v1.workflow.WorkflowTaskPageQuery
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowApiReadFacadeTest {
    @Test
    fun `maps an approval inbox without exposing assignees comments or tenant state`() {
        val repository = RecordingWorkflowQueries(
            pendingResult = WorkflowTaskPageResult(
                listOf(
                    WorkflowTaskInboxItemView(
                        task = WorkflowTaskView(
                            id = Identifier("task-1"),
                            workflowId = Identifier("workflow-1"),
                            state = WorkflowTaskState.PENDING,
                            createdTime = 10,
                            updatedTime = 20,
                            assignedToCurrentUser = false,
                        ),
                        document = summary("document-1"),
                        workflowType = "DUAL_REVIEW",
                        workflowState = WorkflowState.PENDING,
                    ),
                ),
            ),
        )
        val facade = facade(repository)

        val page = facade.pendingTasks(WorkflowTaskPageQuery())
        val item = page.items.single()

        assertEquals("task-1", item.task.id)
        assertEquals("workflow-1", item.task.workflowId)
        assertEquals("PENDING", item.task.state)
        assertFalse(item.task.assignedToCurrentUser)
        assertTrue(item.actionableByCurrentUser)
        assertEquals("document-1", item.document.id)
        assertEquals("DOC-document-1", item.document.documentNumber)
        assertEquals("PENDING_REVIEW", item.document.lifecycleState)
        assertEquals("finance", item.document.folderId)
        assertEquals("DUAL_REVIEW", item.workflowType)
        assertEquals("PENDING", item.workflowState)
        assertNull(page.nextCursor)
        assertEquals(Identifier("tenant-1"), repository.lastTenantId)
        assertEquals(Identifier("reviewer-1"), repository.lastCurrentUserId)

        val forbidden = setOf(
            "getTenantId", "getAssigneeId", "getAssignee", "getReviewerId", "getOperatorId",
            "getOperatorName", "getComment", "getCommentText", "getAttributes", "getStoragePath",
        )
        val getters = listOf(WorkflowTaskDto::class.java, WorkflowTaskInboxItemDto::class.java)
            .flatMap { type -> type.methods.filter { it.parameterCount == 0 }.map { it.name } }
            .toSet()
        assertTrue(getters.intersect(forbidden).isEmpty())
    }

    @Test
    fun `maps document workflow history while omitting task assignment and decision detail`() {
        val repository = RecordingWorkflowQueries(
            historyResult = DocumentWorkflowPageResult(
                listOf(
                    WorkflowView(
                        id = Identifier("workflow-1"),
                        documentId = Identifier("document-1"),
                        workflowType = "DUAL_REVIEW",
                        state = WorkflowState.APPROVED,
                        createdTime = 10,
                        updatedTime = 30,
                        tasks = listOf(
                            WorkflowHistoryTaskView(Identifier("task-1"), WorkflowTaskState.APPROVED, 10, 20),
                            WorkflowHistoryTaskView(Identifier("task-2"), WorkflowTaskState.APPROVED, 11, 30),
                        ),
                    ),
                ),
            ),
        )
        val facade = facade(repository)

        val page = facade.documentHistory("document-1", DocumentWorkflowPageQuery())
        val history = page.items.single()

        assertEquals("workflow-1", history.id)
        assertEquals("document-1", history.documentId)
        assertEquals("DUAL_REVIEW", history.workflowType)
        assertEquals("APPROVED", history.state)
        assertEquals(listOf("task-1", "task-2"), history.tasks.map { it.id })
        assertEquals(listOf("APPROVED", "APPROVED"), history.tasks.map { it.state })
        assertEquals(Identifier("document-1"), repository.lastHistoryDocumentId)

        val forbidden = setOf(
            "getTenantId", "getAssigneeId", "getAssignedToCurrentUser", "getReviewerId", "getOperatorId",
            "getOperatorName", "getComment", "getCommentText", "getAttributes", "getExternalId",
        )
        val getters = listOf(DocumentWorkflowDto::class.java, WorkflowHistoryTaskDto::class.java)
            .flatMap { type -> type.methods.filter { it.parameterCount == 0 }.map { it.name } }
            .toSet()
        assertTrue(getters.intersect(forbidden).isEmpty())
    }

    @Test
    fun `passes inbox and history limits and opaque sort positions to application requests`() {
        val repository = RecordingWorkflowQueries(
            pendingResult = WorkflowTaskPageResult(
                emptyList(),
                WorkflowTaskPageCursor(200, Identifier("任务-🚀")),
            ),
            historyResult = DocumentWorkflowPageResult(
                emptyList(),
                DocumentWorkflowPageCursor(300, Identifier("流程-🚀")),
            ),
        )
        val facade = facade(repository)

        val firstInbox = facade.pendingTasks(WorkflowTaskPageQuery(limit = 7))
        val inboxCursor = assertNotNull(firstInbox.nextCursor)
        facade.pendingTasks(WorkflowTaskPageQuery(cursor = inboxCursor, limit = 8))

        assertEquals(8, repository.lastPendingRequest?.limit)
        assertEquals(200, repository.lastPendingRequest?.cursor?.createdTime)
        assertEquals("任务-🚀", repository.lastPendingRequest?.cursor?.id?.value)

        val firstHistory = facade.documentHistory("document-1", DocumentWorkflowPageQuery(limit = 9))
        val historyCursor = assertNotNull(firstHistory.nextCursor)
        facade.documentHistory("document-1", DocumentWorkflowPageQuery(cursor = historyCursor, limit = 10))

        assertEquals(10, repository.lastHistoryRequest?.limit)
        assertEquals(300, repository.lastHistoryRequest?.cursor?.createdTime)
        assertEquals("流程-🚀", repository.lastHistoryRequest?.cursor?.id?.value)
        assertTrue(inboxCursor.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(historyCursor.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(inboxCursor == historyCursor)
    }

    @Test
    fun `rejects invalid document ids malformed cursors and cursor kind confusion before repository access`() {
        val repository = RecordingWorkflowQueries()
        val facade = facade(repository)
        val taskCursor = WorkflowPageCursorCodec(WorkflowPageCursorCodec.TASK_KIND)
            .encode(1, Identifier("task-1"))
        val historyCursor = WorkflowPageCursorCodec(WorkflowPageCursorCodec.HISTORY_KIND)
            .encode(1, Identifier("workflow-1"))

        assertFailsWith<IllegalArgumentException> {
            facade.documentHistory(" ", DocumentWorkflowPageQuery())
        }
        assertFailsWith<IllegalArgumentException> {
            facade.documentHistory("document\u0000-1", DocumentWorkflowPageQuery())
        }
        assertFailsWith<IllegalArgumentException> {
            facade.documentHistory("d".repeat(129), DocumentWorkflowPageQuery())
        }
        assertFailsWith<IllegalArgumentException> {
            facade.pendingTasks(WorkflowTaskPageQuery(cursor = "***"))
        }
        assertFailsWith<IllegalArgumentException> {
            facade.pendingTasks(WorkflowTaskPageQuery(cursor = historyCursor))
        }
        assertFailsWith<IllegalArgumentException> {
            facade.documentHistory("document-1", DocumentWorkflowPageQuery(cursor = taskCursor))
        }

        assertEquals(0, repository.pendingCalls)
        assertEquals(0, repository.historyCalls)
    }

    @Test
    fun `public facade accepts no tenant user or domain identifier parameters`() {
        val constructor = WorkflowApiReadFacade::class.java.constructors.single()
        val publicMethods = WorkflowApiReadFacade::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }

        assertEquals(listOf(WorkflowQueryService::class.java), constructor.parameterTypes.toList())
        assertEquals(setOf("pendingTasks", "documentHistory"), publicMethods.map { it.name }.toSet())
        assertTrue(publicMethods.none { method ->
            method.parameterTypes.any { type ->
                type == TenantProvider::class.java || type == UserRealmProvider::class.java || type == Identifier::class.java
            }
        })
    }

    private fun facade(repository: RecordingWorkflowQueries): WorkflowApiReadFacade = WorkflowApiReadFacade(
        WorkflowQueryService(
            tenantProvider = object : TenantProvider {
                override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
            },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser(): UserIdentity = UserIdentity(Identifier("reviewer-1"), "Reviewer One")
                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
            },
            queries = repository,
            transaction = DirectTransaction,
        ),
    )

    private fun summary(id: String): DocumentSummaryView = DocumentSummaryView(
        id = Identifier(id),
        documentNumber = "DOC-$id",
        title = "Document $id",
        lifecycleState = LifecycleState.PENDING_REVIEW,
        createdTime = 1,
        updatedTime = 2,
        currentVersionId = Identifier("version-1"),
        folderId = "finance",
    )

    private class RecordingWorkflowQueries(
        var pendingResult: WorkflowTaskPageResult = WorkflowTaskPageResult(emptyList()),
        var historyResult: DocumentWorkflowPageResult? = DocumentWorkflowPageResult(emptyList()),
    ) : WorkflowQueryRepository {
        var pendingCalls: Int = 0
        var historyCalls: Int = 0
        var lastTenantId: Identifier? = null
        var lastCurrentUserId: Identifier? = null
        var lastPendingRequest: WorkflowTaskPageRequest? = null
        var lastHistoryDocumentId: Identifier? = null
        var lastHistoryRequest: DocumentWorkflowPageRequest? = null

        override fun findPendingTaskPage(
            tenantId: Identifier,
            currentUserId: Identifier,
            request: WorkflowTaskPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): WorkflowTaskPageResult {
            pendingCalls++
            lastTenantId = tenantId
            lastCurrentUserId = currentUserId
            lastPendingRequest = request
            return pendingResult
        }

        override fun findDocumentWorkflowPage(
            tenantId: Identifier,
            documentId: Identifier,
            request: DocumentWorkflowPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentWorkflowPageResult? {
            historyCalls++
            lastTenantId = tenantId
            lastHistoryDocumentId = documentId
            lastHistoryRequest = request
            return historyResult
        }
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
