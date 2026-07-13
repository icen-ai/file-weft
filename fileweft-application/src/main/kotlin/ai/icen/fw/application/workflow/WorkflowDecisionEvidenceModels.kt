package ai.icen.fw.application.workflow

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.workflow.WorkflowState
import ai.icen.fw.domain.workflow.WorkflowTaskState
import java.util.ArrayList
import java.util.Collections

/** Privileged task-level decision evidence; assignment and comments stay redacted. */
class WorkflowDecisionTaskEvidenceView @JvmOverloads constructor(
    val id: Identifier,
    val state: WorkflowTaskState,
    val createdTime: Long,
    val updatedTime: Long,
    val decisionOperatorId: Identifier? = null,
    decisionOperatorName: String? = null,
    val decidedTime: Long? = null,
) {
    val decisionOperatorName: String? = decisionOperatorName?.let { name ->
        safeEvidenceText(name, "Workflow decision operator name", MAX_OPERATOR_NAME_LENGTH)
    }

    init {
        requireSafeEvidenceIdentifier(id, "Workflow decision task id", MAX_TASK_ID_LENGTH)
        decisionOperatorId?.let { operatorId ->
            requireSafeEvidenceIdentifier(operatorId, "Workflow decision operator id", MAX_OPERATOR_ID_LENGTH)
        }
        require(createdTime >= 0) { "Workflow decision task creation time must not be negative." }
        require(updatedTime >= createdTime) { "Workflow decision task update time must not precede creation time." }
        require(this.decisionOperatorName == null || decisionOperatorId != null) {
            "Workflow decision operator name requires an operator id."
        }
        require((decisionOperatorId == null) == (decidedTime == null)) {
            "Workflow decision operator id and decision time must be present together."
        }
        require(state != WorkflowTaskState.PENDING || decisionOperatorId == null) {
            "A pending workflow task cannot contain decision evidence."
        }
        require(decisionOperatorId == null || state in DECIDED_STATES) {
            "Workflow decision evidence requires an approved or rejected task."
        }
        decidedTime?.let { time ->
            require(time in createdTime..updatedTime) {
                "Workflow decision time must fall within the task lifetime."
            }
        }
    }
}

class WorkflowDecisionEvidenceView(
    val id: Identifier,
    val documentId: Identifier,
    workflowType: String,
    val state: WorkflowState,
    val createdTime: Long,
    val updatedTime: Long,
    tasks: List<WorkflowDecisionTaskEvidenceView>,
) {
    val workflowType: String = safeEvidenceText(workflowType, "Workflow decision type", MAX_WORKFLOW_TYPE_LENGTH)
    val tasks: List<WorkflowDecisionTaskEvidenceView> = Collections.unmodifiableList(ArrayList(tasks))

    init {
        requireSafeEvidenceIdentifier(id, "Workflow decision workflow id", MAX_WORKFLOW_ID_LENGTH)
        requireSafeEvidenceIdentifier(documentId, "Workflow decision document id", MAX_DOCUMENT_ID_LENGTH)
        require(createdTime >= 0) { "Workflow decision workflow creation time must not be negative." }
        require(updatedTime >= createdTime) { "Workflow decision workflow update time must not precede creation time." }
        require(this.tasks.isNotEmpty()) { "Workflow decision evidence must contain at least one task." }
        require(this.tasks.map { task -> task.id }.distinct().size == this.tasks.size) {
            "Workflow decision evidence task identifiers must be unique."
        }
    }
}

class DocumentWorkflowDecisionEvidencePageResult @JvmOverloads constructor(
    val documentId: Identifier,
    items: List<WorkflowDecisionEvidenceView>,
    val nextCursor: DocumentWorkflowPageCursor? = null,
) {
    val items: List<WorkflowDecisionEvidenceView> = Collections.unmodifiableList(ArrayList(items))

    init {
        requireSafeEvidenceIdentifier(documentId, "Workflow decision evidence document id", MAX_DOCUMENT_ID_LENGTH)
        require(this.items.size <= DocumentWorkflowPageRequest.MAX_LIMIT) {
            "Workflow decision evidence page must not exceed ${DocumentWorkflowPageRequest.MAX_LIMIT} items."
        }
        require(this.items.all { workflow -> workflow.documentId == documentId }) {
            "Workflow decision evidence must belong to the requested document."
        }
        require(this.items.map { workflow -> workflow.id }.distinct().size == this.items.size) {
            "Workflow decision evidence workflow identifiers must be unique."
        }
    }
}

private fun safeEvidenceText(value: String, label: String, maximumLength: Int): String {
    require(value.isNotBlank()) { "$label must not be blank." }
    require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters." }
    require(value.none(::isUnsafeEvidenceCharacter)) { "$label must not contain unsafe characters." }
    return value
}

private fun requireSafeEvidenceIdentifier(identifier: Identifier, label: String, maximumLength: Int) {
    val value = identifier.value
    require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters." }
    require(value.none(::isUnsafeEvidenceCharacter)) { "$label must not contain unsafe characters." }
}

private fun isUnsafeEvidenceCharacter(character: Char): Boolean =
    Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()

private val DECIDED_STATES = setOf(WorkflowTaskState.APPROVED, WorkflowTaskState.REJECTED)
private const val MAX_TASK_ID_LENGTH = 64
private const val MAX_WORKFLOW_ID_LENGTH = 64
private const val MAX_DOCUMENT_ID_LENGTH = 128
private const val MAX_OPERATOR_ID_LENGTH = 256
private const val MAX_OPERATOR_NAME_LENGTH = 256
private const val MAX_WORKFLOW_TYPE_LENGTH = 64
