package ai.icen.fw.web.runtime.v1.workflow

import ai.icen.fw.application.workflow.DocumentWorkflowPageCursor
import ai.icen.fw.application.workflow.DocumentWorkflowPageRequest
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryService
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceView
import ai.icen.fw.web.api.ApiPage
import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowDecisionEvidenceDto
import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowDecisionEvidencePageQuery
import ai.icen.fw.web.api.v1.workflow.WorkflowDecisionTaskEvidenceDto
import ai.icen.fw.web.runtime.v1.document.DocumentApiInputs

/** Pure-JVM mapping boundary for privileged workflow decision evidence. */
class WorkflowDecisionEvidenceApiReadFacade(
    private val evidence: WorkflowDecisionEvidenceQueryService,
) {
    private val cursors = WorkflowPageCursorCodec(WorkflowPageCursorCodec.EVIDENCE_KIND)

    fun documentEvidence(
        documentId: String,
        query: DocumentWorkflowDecisionEvidencePageQuery,
    ): ApiPage<DocumentWorkflowDecisionEvidenceDto> {
        val result = evidence.documentEvidence(
            DocumentApiInputs.documentId(documentId),
            DocumentWorkflowPageRequest(
                cursor = query.cursor?.let(cursors::decode)?.let { cursor ->
                    DocumentWorkflowPageCursor(cursor.createdTime, cursor.id)
                },
                limit = query.limit,
            ),
        )
        return ApiPage(
            items = result.items.map { workflow -> workflow.toDto() },
            nextCursor = result.nextCursor?.let { cursor -> cursors.encode(cursor.createdTime, cursor.id) },
        )
    }

    private fun WorkflowDecisionEvidenceView.toDto(): DocumentWorkflowDecisionEvidenceDto =
        DocumentWorkflowDecisionEvidenceDto(
            id = id.value,
            documentId = documentId.value,
            workflowType = workflowType,
            state = state.name,
            createdTime = createdTime,
            updatedTime = updatedTime,
            tasks = tasks.map { task ->
                WorkflowDecisionTaskEvidenceDto(
                    id = task.id.value,
                    state = task.state.name,
                    createdTime = task.createdTime,
                    updatedTime = task.updatedTime,
                    decisionOperatorId = task.decisionOperatorId?.value,
                    decisionOperatorName = task.decisionOperatorName,
                    decidedTime = task.decidedTime,
                )
            },
        )
}
