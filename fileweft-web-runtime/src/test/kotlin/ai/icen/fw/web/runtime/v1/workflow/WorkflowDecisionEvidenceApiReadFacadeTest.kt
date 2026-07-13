package ai.icen.fw.web.runtime.v1.workflow

import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.workflow.DocumentWorkflowDecisionEvidencePageResult
import ai.icen.fw.application.workflow.DocumentWorkflowPageCursor
import ai.icen.fw.application.workflow.DocumentWorkflowPageRequest
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryRepository
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryService
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceView
import ai.icen.fw.application.workflow.WorkflowDecisionTaskEvidenceView
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
import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowDecisionEvidencePageQuery
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowDecisionEvidenceApiReadFacadeTest {
    @Test
    fun `maps recorded and legacy actors with an evidence specific opaque cursor`() {
        val repository = RecordingEvidenceQueries(
            DocumentWorkflowDecisionEvidencePageResult(
                Identifier("document-1"),
                listOf(
                    WorkflowDecisionEvidenceView(
                        Identifier("workflow-1"), Identifier("document-1"), "DUAL_REVIEW", WorkflowState.APPROVED,
                        10, 30,
                        listOf(
                            WorkflowDecisionTaskEvidenceView(
                                Identifier("task-1"), WorkflowTaskState.APPROVED, 10, 20,
                                Identifier("reviewer-a"), "审批人甲", 20,
                            ),
                            WorkflowDecisionTaskEvidenceView(
                                Identifier("task-legacy"), WorkflowTaskState.APPROVED, 11, 30,
                            ),
                        ),
                    ),
                ),
                DocumentWorkflowPageCursor(10, Identifier("workflow-1")),
            ),
        )
        val facade = facade(repository)

        val first = facade.documentEvidence("document-1", DocumentWorkflowDecisionEvidencePageQuery(limit = 7))
        val workflow = first.items.single()
        val cursor = assertNotNull(first.nextCursor)
        facade.documentEvidence("document-1", DocumentWorkflowDecisionEvidencePageQuery(cursor, 8))

        assertEquals("workflow-1", workflow.id)
        assertEquals("reviewer-a", workflow.tasks[0].decisionOperatorId)
        assertEquals("审批人甲", workflow.tasks[0].decisionOperatorName)
        assertEquals(20, workflow.tasks[0].decidedTime)
        assertTrue(workflow.tasks[0].decisionEvidenceRecorded)
        assertNull(workflow.tasks[1].decisionOperatorId)
        assertFalse(workflow.tasks[1].decisionEvidenceRecorded)
        assertEquals(8, repository.lastRequest?.limit)
        assertEquals(10, repository.lastRequest?.cursor?.createdTime)
        assertEquals("workflow-1", repository.lastRequest?.cursor?.id?.value)
        assertTrue(cursor.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(
            listOf(
                WorkflowDecisionEvidenceQueryService.AUDIT_ACTION,
                WorkflowDecisionEvidenceQueryService.READ_ACTION,
                WorkflowDecisionEvidenceQueryService.AUDIT_ACTION,
                WorkflowDecisionEvidenceQueryService.READ_ACTION,
            ),
            repository.authorizationActions,
        )
    }

    @Test
    fun `rejects malformed ids and cursors from other workflow routes before persistence`() {
        val repository = RecordingEvidenceQueries(DocumentWorkflowDecisionEvidencePageResult(Identifier("document-1"), emptyList()))
        val facade = facade(repository)
        val historyCursor = WorkflowPageCursorCodec(WorkflowPageCursorCodec.HISTORY_KIND)
            .encode(1, Identifier("workflow-1"))
        val taskCursor = WorkflowPageCursorCodec(WorkflowPageCursorCodec.TASK_KIND)
            .encode(1, Identifier("task-1"))

        listOf(historyCursor, taskCursor, "***").forEach { cursor ->
            assertFailsWith<IllegalArgumentException> {
                facade.documentEvidence("document-1", DocumentWorkflowDecisionEvidencePageQuery(cursor))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            facade.documentEvidence(" ", DocumentWorkflowDecisionEvidencePageQuery())
        }
        assertEquals(0, repository.calls)
    }

    @Test
    fun `public evidence facade accepts no tenant user or domain identifier parameters`() {
        val constructor = WorkflowDecisionEvidenceApiReadFacade::class.java.constructors.single()
        val publicMethods = WorkflowDecisionEvidenceApiReadFacade::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }

        assertEquals(listOf(WorkflowDecisionEvidenceQueryService::class.java), constructor.parameterTypes.toList())
        assertEquals(setOf("documentEvidence"), publicMethods.map { method -> method.name }.toSet())
        assertTrue(publicMethods.none { method ->
            method.parameterTypes.any { type ->
                type == TenantProvider::class.java || type == UserRealmProvider::class.java || type == Identifier::class.java
            }
        })
    }

    private fun facade(repository: RecordingEvidenceQueries): WorkflowDecisionEvidenceApiReadFacade {
        val actions = repository.authorizationActions
        return WorkflowDecisionEvidenceApiReadFacade(
            WorkflowDecisionEvidenceQueryService(
                tenantProvider = object : TenantProvider {
                    override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
                },
                userRealmProvider = object : UserRealmProvider {
                    override fun currentUser(): UserIdentity = UserIdentity(Identifier("reviewer-a"), "审批人甲")
                    override fun findUser(userId: Identifier): UserIdentity? = null
                },
                authorizationProvider = object : AuthorizationProvider {
                    override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
                        actions += request.action.name
                        return AuthorizationDecision(true)
                    }
                },
                queries = repository,
                transaction = DirectTransaction,
            ),
        )
    }

    private class RecordingEvidenceQueries(
        private val result: DocumentWorkflowDecisionEvidencePageResult,
    ) : WorkflowDecisionEvidenceQueryRepository {
        var calls = 0
        var lastRequest: DocumentWorkflowPageRequest? = null
        val authorizationActions = mutableListOf<String>()
        override fun findDocumentWorkflowDecisionEvidencePage(
            tenantId: Identifier,
            documentId: Identifier,
            request: DocumentWorkflowPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentWorkflowDecisionEvidencePageResult {
            calls++
            lastRequest = request
            return result
        }
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
