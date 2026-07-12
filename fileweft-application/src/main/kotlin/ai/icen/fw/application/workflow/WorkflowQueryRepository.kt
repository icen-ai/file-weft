package ai.icen.fw.application.workflow

import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.core.id.Identifier

/** Public-safe workflow read model; implementations must filter every query by tenant. */
interface WorkflowQueryRepository {
    fun findPendingTaskPage(
        tenantId: Identifier,
        currentUserId: Identifier,
        request: WorkflowTaskPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): WorkflowTaskPageResult

    /** Null means the document is absent, cross-tenant, or outside the supplied folder scope. */
    fun findDocumentWorkflowPage(
        tenantId: Identifier,
        documentId: Identifier,
        request: DocumentWorkflowPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentWorkflowPageResult?
}
