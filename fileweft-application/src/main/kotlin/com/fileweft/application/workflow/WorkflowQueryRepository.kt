package com.fileweft.application.workflow

import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.core.id.Identifier

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
