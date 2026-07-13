package ai.icen.fw.application.workflow

import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.workflow.WorkflowState
import ai.icen.fw.domain.workflow.WorkflowTaskState
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkflowDecisionEvidenceQueryServiceTest {
    @Test
    fun `requires audit and read with one identity before querying tenant and catalog scoped evidence`() {
        val user = UserIdentity(Identifier("reviewer-a"), "审批人甲", mapOf("role" to "reviewer"))
        val users = RecordingUsers(user)
        val authorization = RecordingAuthorization()
        val transaction = RecordingTransaction()
        val expected = evidencePage()
        val queries = RecordingEvidenceQueries(expected, transaction)
        val folderAccess = RecordingFolderAccess(linkedSetOf("finance", "contracts"), transaction)
        val request = DocumentWorkflowPageRequest(DocumentWorkflowPageCursor(600, Identifier("workflow-old")), 17)
        val service = service(users, authorization, queries, transaction, folderAccess)

        val result = service.documentEvidence(Identifier("document-1"), request)

        assertSame(expected, result)
        assertEquals(1, users.calls)
        assertEquals(
            listOf(WorkflowDecisionEvidenceQueryService.AUDIT_ACTION, WorkflowDecisionEvidenceQueryService.READ_ACTION),
            authorization.requests.map { authorizationRequest -> authorizationRequest.action.name },
        )
        authorization.requests.forEach { authorizationRequest ->
            assertEquals(user.id, authorizationRequest.subject.id)
            assertEquals(user.attributes, authorizationRequest.subject.attributes)
            assertEquals(Identifier("tenant-a"), authorizationRequest.resource.tenantId)
            assertEquals(Identifier("document-1"), authorizationRequest.resource.id)
        }
        assertEquals(1, folderAccess.calls)
        assertFalse(folderAccess.calledInTransaction)
        assertEquals(1, queries.calls)
        assertEquals(Identifier("tenant-a"), queries.tenantId)
        assertEquals(Identifier("document-1"), queries.documentId)
        assertSame(request, queries.request)
        assertEquals(listOf("finance", "contracts"), queries.scope?.folderIds)
        assertTrue(queries.calledInTransaction)
    }

    @Test
    fun `denies either missing permission before catalog or repository access`() {
        listOf(
            WorkflowDecisionEvidenceQueryService.AUDIT_ACTION,
            WorkflowDecisionEvidenceQueryService.READ_ACTION,
        ).forEach { deniedAction ->
            val authorization = RecordingAuthorization(deniedAction)
            val transaction = RecordingTransaction()
            val queries = RecordingEvidenceQueries(evidencePage(), transaction)
            val folderAccess = RecordingFolderAccess(setOf("finance"), transaction)

            assertThrows<ApplicationForbiddenException>(deniedAction) {
                service(RecordingUsers(user()), authorization, queries, transaction, folderAccess)
                    .documentEvidence(Identifier("document-1"), DocumentWorkflowPageRequest())
            }

            assertEquals(0, folderAccess.calls)
            assertEquals(0, queries.calls)
            assertEquals(0, transaction.executions)
        }
    }

    @Test
    fun `empty folder scope and hidden documents fail closed without leaking repository state`() {
        val transaction = RecordingTransaction()
        val denyAll = RecordingEvidenceQueries(evidencePage(), transaction)
        assertThrows<DocumentNotFoundException> {
            service(
                RecordingUsers(user()), RecordingAuthorization(), denyAll, transaction,
                RecordingFolderAccess(emptySet(), transaction),
            ).documentEvidence(Identifier("document-1"), DocumentWorkflowPageRequest())
        }
        assertEquals(0, denyAll.calls)
        assertEquals(0, transaction.executions)

        val hiddenTransaction = RecordingTransaction()
        val hidden = RecordingEvidenceQueries(null, hiddenTransaction)
        assertThrows<DocumentNotFoundException> {
            service(RecordingUsers(user()), RecordingAuthorization(), hidden, hiddenTransaction)
                .documentEvidence(Identifier("document-1"), DocumentWorkflowPageRequest())
        }
        assertEquals(1, hidden.calls)
        assertEquals(1, hiddenTransaction.executions)
    }

    @Test
    fun `rejects a repository page for another document`() {
        val transaction = RecordingTransaction()
        val wrong = DocumentWorkflowDecisionEvidencePageResult(Identifier("document-other"), emptyList())

        assertThrows<IllegalStateException> {
            service(
                RecordingUsers(user()), RecordingAuthorization(), RecordingEvidenceQueries(wrong, transaction), transaction,
            ).documentEvidence(Identifier("document-1"), DocumentWorkflowPageRequest())
        }
    }

    @Test
    fun `evidence models preserve legacy unknown rows and reject contradictory identity or time`() {
        val legacy = WorkflowDecisionTaskEvidenceView(
            Identifier("task-legacy"), WorkflowTaskState.APPROVED, 10, 20,
        )
        assertNull(legacy.decisionOperatorId)
        assertNull(legacy.decisionOperatorName)
        assertNull(legacy.decidedTime)

        assertThrows<IllegalArgumentException> {
            WorkflowDecisionTaskEvidenceView(
                Identifier("task-1"), WorkflowTaskState.PENDING, 10, 20,
                Identifier("reviewer-a"), "审批人甲", 15,
            )
        }
        assertThrows<IllegalArgumentException> {
            WorkflowDecisionTaskEvidenceView(
                Identifier("task-1"), WorkflowTaskState.APPROVED, 10, 20,
                Identifier("reviewer-a"), "审批人甲", 21,
            )
        }
        assertThrows<IllegalArgumentException> {
            WorkflowDecisionTaskEvidenceView(
                Identifier("task-1"), WorkflowTaskState.APPROVED, 10, 20,
                null, "审批人甲", null,
            )
        }

        val source = mutableListOf(legacy)
        val workflow = WorkflowDecisionEvidenceView(
            Identifier("workflow-1"), Identifier("document-1"), "DOCUMENT_REVIEW", WorkflowState.APPROVED,
            10, 20, source,
        )
        source.clear()
        assertEquals(listOf("task-legacy"), workflow.tasks.map { task -> task.id.value })
        assertThrows<UnsupportedOperationException> {
            (workflow.tasks as MutableList<WorkflowDecisionTaskEvidenceView>).clear()
        }
    }

    private fun service(
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowDecisionEvidenceQueryRepository,
        transaction: ApplicationTransaction,
        folderAccess: DocumentFolderReadAccess? = null,
    ) = WorkflowDecisionEvidenceQueryService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
        },
        userRealmProvider = users,
        authorizationProvider = authorization,
        queries = queries,
        transaction = transaction,
        folderReadAccess = folderAccess,
    )

    private fun evidencePage() = DocumentWorkflowDecisionEvidencePageResult(
        Identifier("document-1"),
        listOf(
            WorkflowDecisionEvidenceView(
                Identifier("workflow-1"), Identifier("document-1"), "DOCUMENT_REVIEW", WorkflowState.APPROVED,
                10, 20,
                listOf(
                    WorkflowDecisionTaskEvidenceView(
                        Identifier("task-1"), WorkflowTaskState.APPROVED, 10, 20,
                        Identifier("reviewer-a"), "审批人甲", 20,
                    ),
                ),
            ),
        ),
    )

    private fun user() = UserIdentity(Identifier("reviewer-a"), "审批人甲")

    private class RecordingUsers(private val user: UserIdentity?) : UserRealmProvider {
        var calls = 0
        override fun currentUser(): UserIdentity? {
            calls++
            return user
        }
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private class RecordingAuthorization(private val deniedAction: String? = null) : AuthorizationProvider {
        val requests = mutableListOf<AuthorizationRequest>()
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            requests += request
            return AuthorizationDecision(request.action.name != deniedAction)
        }
    }

    private class RecordingTransaction : ApplicationTransaction {
        var active = false
            private set
        var executions = 0
            private set
        override fun <T> execute(action: () -> T): T {
            check(!active)
            executions++
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }

    private class RecordingEvidenceQueries(
        private val result: DocumentWorkflowDecisionEvidencePageResult?,
        private val transaction: RecordingTransaction,
    ) : WorkflowDecisionEvidenceQueryRepository {
        var calls = 0
        var tenantId: Identifier? = null
        var documentId: Identifier? = null
        var request: DocumentWorkflowPageRequest? = null
        var scope: DocumentFolderReadScope? = null
        var calledInTransaction = false
        override fun findDocumentWorkflowDecisionEvidencePage(
            tenantId: Identifier,
            documentId: Identifier,
            request: DocumentWorkflowPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentWorkflowDecisionEvidencePageResult? {
            calls++
            this.tenantId = tenantId
            this.documentId = documentId
            this.request = request
            scope = folderReadScope
            calledInTransaction = transaction.active
            return result
        }
    }

    private class RecordingFolderAccess(
        private val folders: Set<String>,
        private val transaction: RecordingTransaction,
    ) : DocumentFolderReadAccess {
        var calls = 0
        var calledInTransaction = false
        override fun requireFolderForDocumentRead(folderId: String) = Unit
        override fun readableFolderIds(): Set<String> {
            calls++
            calledInTransaction = transaction.active
            return folders
        }
    }
}
