package ai.icen.fw.web.runtime.v1.workflow

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
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.domain.workflow.WorkflowState
import ai.icen.fw.domain.workflow.WorkflowTaskState
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

class JavaWorkflowRuntimeFixtures private constructor() {
    companion object {
        @JvmStatic
        fun facade(): WorkflowApiReadFacade = WorkflowApiReadFacade(
            WorkflowQueryService(
                tenantProvider = object : TenantProvider {
                    override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-java"))
                },
                userRealmProvider = object : UserRealmProvider {
                    override fun currentUser(): UserIdentity = UserIdentity(Identifier("reviewer-java"), "Java Reviewer")
                    override fun findUser(userId: Identifier): UserIdentity? = null
                },
                authorizationProvider = object : AuthorizationProvider {
                    override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
                },
                queries = JavaWorkflowQueries,
                transaction = object : ApplicationTransaction {
                    override fun <T> execute(action: () -> T): T = action()
                },
            ),
        )
    }

    private object JavaWorkflowQueries : WorkflowQueryRepository {
        override fun findPendingTaskPage(
            tenantId: Identifier,
            currentUserId: Identifier,
            request: WorkflowTaskPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): WorkflowTaskPageResult = WorkflowTaskPageResult(
            listOf(
                WorkflowTaskInboxItemView(
                    task = WorkflowTaskView(
                        Identifier("task-java"), Identifier("workflow-java"), WorkflowTaskState.PENDING,
                        1, 2, true,
                    ),
                    document = document(),
                    workflowType = "SINGLE_REVIEW",
                    workflowState = WorkflowState.PENDING,
                ),
            ),
            WorkflowTaskPageCursor(2, Identifier("task-java")),
        )

        override fun findDocumentWorkflowPage(
            tenantId: Identifier,
            documentId: Identifier,
            request: DocumentWorkflowPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentWorkflowPageResult = DocumentWorkflowPageResult(
            listOf(
                WorkflowView(
                    Identifier("workflow-java"), documentId, "SINGLE_REVIEW", WorkflowState.APPROVED,
                    1, 2, listOf(WorkflowHistoryTaskView(Identifier("task-java"), WorkflowTaskState.APPROVED, 1, 2)),
                ),
            ),
            DocumentWorkflowPageCursor(2, Identifier("workflow-java")),
        )

        private fun document(): DocumentSummaryView = DocumentSummaryView(
            Identifier("document-java"), "DOC-JAVA", "Java document", LifecycleState.PENDING_REVIEW,
            1, 2, Identifier("version-java"), "inbox",
        )
    }
}
