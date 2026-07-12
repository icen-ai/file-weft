package com.fileweft.application.workflow

import com.fileweft.application.document.DocumentFolderReadAccess
import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.document.DocumentSummaryView
import com.fileweft.application.security.ApplicationForbiddenException
import com.fileweft.application.security.ApplicationUnauthenticatedException
import com.fileweft.application.transaction.ApplicationTransaction
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkflowQueryServiceTest {
    @Test
    fun `authorizes review then read with one trusted identity before querying the current tenant inbox`() {
        val authorization = RecordingAuthorization()
        val users = RecordingUsers(UserIdentity(Identifier("reviewer-a"), "Reviewer A", mapOf("role" to "reviewer")))
        val transaction = RecordingTransaction()
        val queries = RecordingQueries(pendingResult = pendingPage())
        val service = service(users, authorization, queries, transaction)
        val request = WorkflowTaskPageRequest(WorkflowTaskPageCursor(120, Identifier("task-next")), 17)

        val result = service.pendingTasks(request)

        assertSame(queries.pendingResult, result)
        assertEquals(1, users.currentUserCalls)
        assertEquals(1, queries.pendingCalls)
        assertEquals(Identifier("tenant-a"), queries.pendingTenantId)
        assertEquals(Identifier("reviewer-a"), queries.pendingUserId)
        assertSame(request, queries.pendingRequest)
        assertNull(queries.pendingFolderScope)
        assertTrue(queries.pendingCalledInTransaction)
        assertEquals(1, transaction.executions)
        assertEquals(
            listOf(WorkflowQueryService.REVIEW_ACTION, WorkflowQueryService.READ_ACTION),
            authorization.requests.map { request -> request.action.name },
        )
        authorization.requests.forEach { authorizationRequest ->
            assertEquals(Identifier("reviewer-a"), authorizationRequest.subject.id)
            assertEquals(mapOf("role" to "reviewer"), authorizationRequest.subject.attributes)
            assertEquals(WorkflowQueryService.WORKFLOW_TASK_PAGE_RESOURCE_ID, authorizationRequest.resource.id)
            assertEquals(WorkflowQueryService.WORKFLOW_TASK_PAGE_RESOURCE_TYPE, authorizationRequest.resource.type)
            assertEquals(Identifier("tenant-a"), authorizationRequest.resource.tenantId)
        }
    }

    @Test
    fun `requires authentication before either inbox authorization decision or repository access`() {
        val users = RecordingUsers(null)
        val authorization = RecordingAuthorization()
        val queries = RecordingQueries()
        val transaction = RecordingTransaction()

        assertThrows<ApplicationUnauthenticatedException> {
            service(users, authorization, queries, transaction).pendingTasks(WorkflowTaskPageRequest())
        }

        assertEquals(1, users.currentUserCalls)
        assertTrue(authorization.requests.isEmpty())
        assertEquals(0, queries.pendingCalls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `stops after a denied review permission without checking read or folder scope`() {
        val authorization = RecordingAuthorization(deniedAction = WorkflowQueryService.REVIEW_ACTION)
        val folderAccess = RecordingFolderAccess(setOf("finance"))
        val queries = RecordingQueries()
        val transaction = RecordingTransaction()

        assertThrows<ApplicationForbiddenException> {
            service(
                users = RecordingUsers(user()),
                authorization = authorization,
                queries = queries,
                transaction = transaction,
                folderReadAccess = folderAccess,
            ).pendingTasks(WorkflowTaskPageRequest())
        }

        assertEquals(listOf(WorkflowQueryService.REVIEW_ACTION), authorization.requests.map { it.action.name })
        assertEquals(0, folderAccess.calls)
        assertEquals(0, queries.pendingCalls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `stops after review succeeds but read permission is denied`() {
        val authorization = RecordingAuthorization(deniedAction = WorkflowQueryService.READ_ACTION)
        val folderAccess = RecordingFolderAccess(setOf("finance"))
        val queries = RecordingQueries()
        val transaction = RecordingTransaction()

        assertThrows<ApplicationForbiddenException> {
            service(
                users = RecordingUsers(user()),
                authorization = authorization,
                queries = queries,
                transaction = transaction,
                folderReadAccess = folderAccess,
            ).pendingTasks(WorkflowTaskPageRequest())
        }

        assertEquals(
            listOf(WorkflowQueryService.REVIEW_ACTION, WorkflowQueryService.READ_ACTION),
            authorization.requests.map { it.action.name },
        )
        assertEquals(0, folderAccess.calls)
        assertEquals(0, queries.pendingCalls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `derives a trusted folder scope outside the transaction and passes it unchanged to inbox persistence`() {
        val transaction = RecordingTransaction()
        val folderAccess = RecordingFolderAccess(linkedSetOf("finance", "contracts"), transaction)
        val queries = RecordingQueries(pendingResult = pendingPage())
        val service = service(
            users = RecordingUsers(user()),
            authorization = RecordingAuthorization(),
            queries = queries,
            transaction = transaction,
            folderReadAccess = folderAccess,
        )

        service.pendingTasks(WorkflowTaskPageRequest())

        assertEquals(1, folderAccess.calls)
        assertFalse(folderAccess.calledInTransaction)
        assertEquals(listOf("finance", "contracts"), queries.pendingFolderScope?.folderIds)
        assertTrue(queries.pendingCalledInTransaction)
    }

    @Test
    fun `returns an empty inbox without opening a transaction when catalog scope is empty`() {
        val transaction = RecordingTransaction()
        val queries = RecordingQueries(pendingResult = pendingPage())
        val folderAccess = RecordingFolderAccess(emptySet(), transaction)

        val result = service(
            users = RecordingUsers(user()),
            authorization = RecordingAuthorization(),
            queries = queries,
            transaction = transaction,
            folderReadAccess = folderAccess,
        ).pendingTasks(WorkflowTaskPageRequest())

        assertTrue(result.items.isEmpty())
        assertNull(result.nextCursor)
        assertEquals(1, folderAccess.calls)
        assertFalse(folderAccess.calledInTransaction)
        assertEquals(0, queries.pendingCalls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `authorizes document history and queries a trusted tenant folder scope inside one transaction`() {
        val authorization = RecordingAuthorization()
        val transaction = RecordingTransaction()
        val folderAccess = RecordingFolderAccess(setOf("legal"), transaction)
        val history = historyPage()
        val queries = RecordingQueries(historyResult = history)
        val users = RecordingUsers(user())
        val request = DocumentWorkflowPageRequest(DocumentWorkflowPageCursor(90, Identifier("workflow-old")), 12)

        val result = service(users, authorization, queries, transaction, folderAccess)
            .documentHistory(Identifier("document-a"), request)

        assertSame(history, result)
        assertEquals(1, users.currentUserCalls)
        assertEquals(listOf(WorkflowQueryService.READ_ACTION), authorization.requests.map { it.action.name })
        val authorizationRequest = authorization.requests.single()
        assertEquals(Identifier("document-a"), authorizationRequest.resource.id)
        assertEquals("DOCUMENT", authorizationRequest.resource.type)
        assertEquals(Identifier("tenant-a"), authorizationRequest.resource.tenantId)
        assertEquals(1, folderAccess.calls)
        assertFalse(folderAccess.calledInTransaction)
        assertEquals(1, queries.historyCalls)
        assertEquals(Identifier("tenant-a"), queries.historyTenantId)
        assertEquals(Identifier("document-a"), queries.historyDocumentId)
        assertSame(request, queries.historyRequest)
        assertEquals(listOf("legal"), queries.historyFolderScope?.folderIds)
        assertTrue(queries.historyCalledInTransaction)
        assertEquals(1, transaction.executions)
    }

    @Test
    fun `rejects unauthenticated and denied document history before catalog and persistence access`() {
        val unauthenticatedQueries = RecordingQueries()
        val unauthenticatedTransaction = RecordingTransaction()
        val unauthenticatedAuthorization = RecordingAuthorization()
        assertThrows<ApplicationUnauthenticatedException> {
            service(
                RecordingUsers(null),
                unauthenticatedAuthorization,
                unauthenticatedQueries,
                unauthenticatedTransaction,
            ).documentHistory(Identifier("document-a"), DocumentWorkflowPageRequest())
        }
        assertTrue(unauthenticatedAuthorization.requests.isEmpty())
        assertEquals(0, unauthenticatedQueries.historyCalls)
        assertEquals(0, unauthenticatedTransaction.executions)

        val deniedQueries = RecordingQueries()
        val deniedTransaction = RecordingTransaction()
        val deniedFolderAccess = RecordingFolderAccess(setOf("finance"), deniedTransaction)
        val deniedAuthorization = RecordingAuthorization(deniedAction = WorkflowQueryService.READ_ACTION)
        assertThrows<ApplicationForbiddenException> {
            service(
                RecordingUsers(user()),
                deniedAuthorization,
                deniedQueries,
                deniedTransaction,
                deniedFolderAccess,
            ).documentHistory(Identifier("document-a"), DocumentWorkflowPageRequest())
        }
        assertEquals(listOf(WorkflowQueryService.READ_ACTION), deniedAuthorization.requests.map { it.action.name })
        assertEquals(0, deniedFolderAccess.calls)
        assertEquals(0, deniedQueries.historyCalls)
        assertEquals(0, deniedTransaction.executions)
    }

    @Test
    fun `returns not found for history when trusted catalog grants no folders without querying persistence`() {
        val transaction = RecordingTransaction()
        val queries = RecordingQueries(historyResult = historyPage())
        val documentId = Identifier("document-hidden")

        assertThrows<DocumentNotFoundException> {
            service(
                users = RecordingUsers(user()),
                authorization = RecordingAuthorization(),
                queries = queries,
                transaction = transaction,
                folderReadAccess = RecordingFolderAccess(emptySet(), transaction),
            ).documentHistory(documentId, DocumentWorkflowPageRequest())
        }

        assertEquals(0, queries.historyCalls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `maps a missing cross tenant or folder hidden history repository result to the same not found failure`() {
        val transaction = RecordingTransaction()
        val queries = RecordingQueries(historyResult = null)
        val documentId = Identifier("document-invisible")

        assertThrows<DocumentNotFoundException> {
            service(RecordingUsers(user()), RecordingAuthorization(), queries, transaction)
                .documentHistory(documentId, DocumentWorkflowPageRequest())
        }

        assertEquals(1, queries.historyCalls)
        assertTrue(queries.historyCalledInTransaction)
        assertEquals(1, transaction.executions)
        assertFalse(transaction.active)
    }

    @Test
    fun `preserves a visible empty document history as a successful empty page`() {
        val emptyHistory = DocumentWorkflowPageResult(emptyList())
        val queries = RecordingQueries(historyResult = emptyHistory)

        val result = service(
            RecordingUsers(user()),
            RecordingAuthorization(),
            queries,
            RecordingTransaction(),
        ).documentHistory(Identifier("document-without-workflows"), DocumentWorkflowPageRequest())

        assertSame(emptyHistory, result)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `flat mode passes a null folder scope to both query methods`() {
        val queries = RecordingQueries(pendingResult = pendingPage(), historyResult = historyPage())
        val service = service(RecordingUsers(user()), RecordingAuthorization(), queries, RecordingTransaction())

        service.pendingTasks(WorkflowTaskPageRequest())
        service.documentHistory(Identifier("document-a"), DocumentWorkflowPageRequest())

        assertNull(queries.pendingFolderScope)
        assertNull(queries.historyFolderScope)
    }

    @Test
    fun `does not swallow repository failures and always leaves the transaction boundary`() {
        val failure = IllegalStateException("query unavailable")
        val transaction = RecordingTransaction()
        val queries = RecordingQueries(pendingFailure = failure)
        val service = service(RecordingUsers(user()), RecordingAuthorization(), queries, transaction)

        val thrown = assertThrows<IllegalStateException> {
            service.pendingTasks(WorkflowTaskPageRequest())
        }

        assertSame(failure, thrown)
        assertEquals(1, queries.pendingCalls)
        assertEquals(1, transaction.executions)
        assertFalse(transaction.active)
    }

    @Test
    fun `propagates document history repository failures and always closes the transaction boundary`() {
        val failure = IllegalStateException("history query unavailable")
        val transaction = RecordingTransaction()
        val queries = RecordingQueries(historyFailure = failure)
        val service = service(RecordingUsers(user()), RecordingAuthorization(), queries, transaction)

        val thrown = assertThrows<IllegalStateException> {
            service.documentHistory(Identifier("document-a"), DocumentWorkflowPageRequest())
        }

        assertSame(failure, thrown)
        assertEquals(1, queries.historyCalls)
        assertEquals(1, transaction.executions)
        assertFalse(transaction.active)
    }

    private fun service(
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: DocumentFolderReadAccess? = null,
    ): WorkflowQueryService {
        if (queries is RecordingQueries) queries.transaction = transaction as? RecordingTransaction
        return WorkflowQueryService(
            tenantProvider = object : TenantProvider {
                override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
            },
            userRealmProvider = users,
            authorizationProvider = authorization,
            queries = queries,
            transaction = transaction,
            folderReadAccess = folderReadAccess,
        )
    }

    private fun user(): UserIdentity = UserIdentity(Identifier("reviewer-a"), "Reviewer A")

    private fun pendingPage(): WorkflowTaskPageResult = WorkflowTaskPageResult(
        listOf(
            WorkflowTaskInboxItemView(
                task = WorkflowTaskView(
                    Identifier("task-a"), Identifier("workflow-a"), WorkflowTaskState.PENDING,
                    100, 110, assignedToCurrentUser = true,
                ),
                document = documentSummary(),
                workflowType = "DOCUMENT_REVIEW",
                workflowState = WorkflowState.PENDING,
            ),
        ),
        WorkflowTaskPageCursor(100, Identifier("task-a")),
    )

    private fun historyPage(): DocumentWorkflowPageResult = DocumentWorkflowPageResult(
        listOf(
            WorkflowView(
                id = Identifier("workflow-a"),
                documentId = Identifier("document-a"),
                workflowType = "DOCUMENT_REVIEW",
                state = WorkflowState.APPROVED,
                createdTime = 50,
                updatedTime = 100,
                tasks = listOf(
                    WorkflowHistoryTaskView(Identifier("task-a"), WorkflowTaskState.APPROVED, 50, 100),
                ),
            ),
        ),
    )

    private fun documentSummary(): DocumentSummaryView = DocumentSummaryView(
        id = Identifier("document-a"),
        documentNumber = "DOC-A",
        title = "Contract A",
        lifecycleState = LifecycleState.PENDING_REVIEW,
        createdTime = 10,
        updatedTime = 100,
        currentVersionId = Identifier("version-a"),
        folderId = "finance",
    )

    private class RecordingUsers(private val user: UserIdentity?) : UserRealmProvider {
        var currentUserCalls: Int = 0

        override fun currentUser(): UserIdentity? {
            currentUserCalls++
            return user
        }

        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private class RecordingAuthorization(
        private val deniedAction: String? = null,
    ) : AuthorizationProvider {
        val requests = ArrayList<AuthorizationRequest>()

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            requests += request
            return if (request.action.name == deniedAction) {
                AuthorizationDecision(false, "host policy detail must not affect classification")
            } else {
                AuthorizationDecision(true)
            }
        }
    }

    private class RecordingFolderAccess(
        private val folders: Set<String>,
        private val transaction: RecordingTransaction? = null,
    ) : DocumentFolderReadAccess {
        var calls: Int = 0
        var calledInTransaction: Boolean = false

        override fun requireFolderForDocumentRead(folderId: String) = Unit

        override fun readableFolderIds(): Set<String> {
            calls++
            calledInTransaction = transaction?.active == true
            return folders
        }
    }

    private class RecordingQueries(
        val pendingResult: WorkflowTaskPageResult = WorkflowTaskPageResult(emptyList()),
        private val historyResult: DocumentWorkflowPageResult? = DocumentWorkflowPageResult(emptyList()),
        private val pendingFailure: RuntimeException? = null,
        private val historyFailure: RuntimeException? = null,
    ) : WorkflowQueryRepository {
        var pendingCalls: Int = 0
        var historyCalls: Int = 0
        var pendingTenantId: Identifier? = null
        var pendingUserId: Identifier? = null
        var pendingRequest: WorkflowTaskPageRequest? = null
        var pendingFolderScope: DocumentFolderReadScope? = null
        var pendingCalledInTransaction: Boolean = false
        var historyTenantId: Identifier? = null
        var historyDocumentId: Identifier? = null
        var historyRequest: DocumentWorkflowPageRequest? = null
        var historyFolderScope: DocumentFolderReadScope? = null
        var historyCalledInTransaction: Boolean = false
        var transaction: RecordingTransaction? = null

        override fun findPendingTaskPage(
            tenantId: Identifier,
            currentUserId: Identifier,
            request: WorkflowTaskPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): WorkflowTaskPageResult {
            pendingCalls++
            pendingTenantId = tenantId
            pendingUserId = currentUserId
            pendingRequest = request
            pendingFolderScope = folderReadScope
            pendingCalledInTransaction = transaction?.active == true
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
            historyTenantId = tenantId
            historyDocumentId = documentId
            historyRequest = request
            historyFolderScope = folderReadScope
            historyCalledInTransaction = transaction?.active == true
            historyFailure?.let { throw it }
            return historyResult
        }
    }

    private class RecordingTransaction : ApplicationTransaction {
        var executions: Int = 0
        var active: Boolean = false

        override fun <T> execute(action: () -> T): T {
            executions++
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }
}
