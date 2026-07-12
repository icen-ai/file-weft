package ai.icen.fw.application.workflow

import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

/** Security boundary for approval inbox and document workflow history. */
class WorkflowQueryService @JvmOverloads constructor(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val queries: WorkflowQueryRepository,
    private val transaction: ApplicationTransaction,
    private val folderReadAccess: DocumentFolderReadAccess? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun pendingTasks(request: WorkflowTaskPageRequest): WorkflowTaskPageResult {
        val tenant = tenantProvider.currentTenant()
        val operator = authorization.requireAction(
            tenant.tenantId,
            WORKFLOW_TASK_PAGE_RESOURCE_ID,
            WORKFLOW_TASK_PAGE_RESOURCE_TYPE,
            REVIEW_ACTION,
        )
        authorization.requireActionAs(
            tenant.tenantId,
            WORKFLOW_TASK_PAGE_RESOURCE_ID,
            WORKFLOW_TASK_PAGE_RESOURCE_TYPE,
            READ_ACTION,
            operator,
        )
        val folderScope = readableFolderScope()
        if (folderScope?.isEmpty == true) return WorkflowTaskPageResult(emptyList())
        return transaction.execute {
            queries.findPendingTaskPage(tenant.tenantId, operator.id, request, folderScope)
        }
    }

    fun documentHistory(
        documentId: Identifier,
        request: DocumentWorkflowPageRequest,
    ): DocumentWorkflowPageResult {
        val tenant = tenantProvider.currentTenant()
        authorization.requireDocumentAction(tenant.tenantId, documentId, READ_ACTION)
        val folderScope = readableFolderScope()
        if (folderScope?.isEmpty == true) throw DocumentNotFoundException(documentId)
        return transaction.execute {
            queries.findDocumentWorkflowPage(tenant.tenantId, documentId, request, folderScope)
                ?: throw DocumentNotFoundException(documentId)
        }
    }

    private fun readableFolderScope(): DocumentFolderReadScope? =
        folderReadAccess?.readableFolderIds()?.let(::DocumentFolderReadScope)

    companion object {
        const val REVIEW_ACTION: String = "document:audit"
        const val READ_ACTION: String = "document:read"
        const val WORKFLOW_TASK_PAGE_RESOURCE_TYPE: String = "WORKFLOW_TASK_PAGE"
        val WORKFLOW_TASK_PAGE_RESOURCE_ID: Identifier = Identifier("workflow-task-page")
    }
}
