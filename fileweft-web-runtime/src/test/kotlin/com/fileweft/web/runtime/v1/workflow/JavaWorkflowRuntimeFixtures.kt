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
