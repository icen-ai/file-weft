package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.workflow.DocumentWorkflowDecisionEvidencePageResult
import ai.icen.fw.application.workflow.DocumentWorkflowPageRequest
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryRepository
import ai.icen.fw.core.id.Identifier

/**
 * Dedicated Spring candidate for the additive privileged query port. Keeping
 * the delegate behind this single-interface adapter prevents it from becoming
 * a second WorkflowQueryRepository candidate in existing host contexts.
 */
class JdbcWorkflowDecisionEvidenceQueryRepository(
    private val delegate: JdbcWorkflowQueryRepository = JdbcWorkflowQueryRepository(),
) : WorkflowDecisionEvidenceQueryRepository {
    override fun findDocumentWorkflowDecisionEvidencePage(
        tenantId: Identifier,
        documentId: Identifier,
        request: DocumentWorkflowPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentWorkflowDecisionEvidencePageResult? = delegate.findDocumentWorkflowDecisionEvidencePage(
        tenantId,
        documentId,
        request,
        folderReadScope,
    )
}
