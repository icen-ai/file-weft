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

/** Security boundary for task-level approval identity snapshots. */
class WorkflowDecisionEvidenceQueryService @JvmOverloads constructor(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val queries: WorkflowDecisionEvidenceQueryRepository,
    private val transaction: ApplicationTransaction,
    private val folderReadAccess: DocumentFolderReadAccess? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun documentEvidence(
        documentId: Identifier,
        request: DocumentWorkflowPageRequest,
    ): DocumentWorkflowDecisionEvidencePageResult {
        val tenant = tenantProvider.currentTenant()
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, AUDIT_ACTION)
        authorization.requireDocumentActionAs(tenant.tenantId, documentId, READ_ACTION, operator)
        val folderScope = readableFolderScope()
        if (folderScope?.isEmpty == true) throw DocumentNotFoundException(documentId)
        val result = transaction.execute {
            queries.findDocumentWorkflowDecisionEvidencePage(tenant.tenantId, documentId, request, folderScope)
                ?: throw DocumentNotFoundException(documentId)
        }
        check(result.documentId == documentId) {
            "Workflow decision query returned evidence outside the requested document."
        }
        return result
    }

    private fun readableFolderScope(): DocumentFolderReadScope? =
        folderReadAccess?.readableFolderIds()?.let(::DocumentFolderReadScope)

    companion object {
        const val AUDIT_ACTION: String = "document:audit"
        const val READ_ACTION: String = "document:read"
    }
}
