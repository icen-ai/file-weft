package ai.icen.fw.application.workflow

import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.core.id.Identifier

/** Additive privileged query port; existing workflow read implementations remain compatible. */
interface WorkflowDecisionEvidenceQueryRepository {
    /** Null means absent, cross-tenant, or outside the supplied catalog scope. */
    fun findDocumentWorkflowDecisionEvidencePage(
        tenantId: Identifier,
        documentId: Identifier,
        request: DocumentWorkflowPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentWorkflowDecisionEvidencePageResult?
}
