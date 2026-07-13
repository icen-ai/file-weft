package ai.icen.fw.web.api.v1.workflow

import ai.icen.fw.web.api.immutableList
import ai.icen.fw.web.api.optionalText
import ai.icen.fw.web.api.requiredText

/** Privileged task decision projection; assignment and comments remain redacted. */
class WorkflowDecisionTaskEvidenceDto @JvmOverloads constructor(
    id: String,
    state: String,
    val createdTime: Long,
    val updatedTime: Long,
    decisionOperatorId: String? = null,
    decisionOperatorName: String? = null,
    val decidedTime: Long? = null,
) {
    val id: String = requiredText(id, "Workflow decision task id", 64)
    val state: String = requiredText(state, "Workflow decision task state", 64)
    val decisionOperatorId: String? = optionalText(
        decisionOperatorId,
        "Workflow decision operator id",
        256,
    )
    val decisionOperatorName: String? = optionalText(
        decisionOperatorName,
        "Workflow decision operator name",
        256,
    )
    val decisionEvidenceRecorded: Boolean = this.decisionOperatorId != null

    init {
        require(createdTime >= 0) { "Workflow decision task creation time must not be negative." }
        require(updatedTime >= createdTime) { "Workflow decision task update time must not precede creation time." }
        require(this.decisionOperatorName == null || this.decisionOperatorId != null) {
            "Workflow decision operator name requires an operator id."
        }
        require((this.decisionOperatorId == null) == (decidedTime == null)) {
            "Workflow decision operator id and decision time must be present together."
        }
        require(state != "PENDING" || this.decisionOperatorId == null) {
            "A pending workflow task cannot contain decision evidence."
        }
        require(this.decisionOperatorId == null || state == "APPROVED" || state == "REJECTED") {
            "Workflow decision evidence requires an approved or rejected task."
        }
        decidedTime?.let { time ->
            require(time in createdTime..updatedTime) {
                "Workflow decision time must fall within the task lifetime."
            }
        }
    }
}

class DocumentWorkflowDecisionEvidenceDto(
    id: String,
    documentId: String,
    workflowType: String,
    state: String,
    val createdTime: Long,
    val updatedTime: Long,
    tasks: List<WorkflowDecisionTaskEvidenceDto>,
) {
    val id: String = requiredText(id, "Workflow decision workflow id", 64)
    val documentId: String = requiredText(documentId, "Workflow decision document id", 128)
    val workflowType: String = requiredText(workflowType, "Workflow decision type", 64)
    val state: String = requiredText(state, "Workflow decision workflow state", 64)
    val tasks: List<WorkflowDecisionTaskEvidenceDto> = immutableList(tasks)

    init {
        require(createdTime >= 0) { "Workflow decision workflow creation time must not be negative." }
        require(updatedTime >= createdTime) { "Workflow decision workflow update time must not precede creation time." }
        require(this.tasks.isNotEmpty()) { "Workflow decision evidence must contain at least one task." }
        require(this.tasks.map { task -> task.id }.distinct().size == this.tasks.size) {
            "Workflow decision evidence task identifiers must be unique."
        }
    }
}

class DocumentWorkflowDecisionEvidencePageQuery @JvmOverloads constructor(
    cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    val cursor: String? = optionalText(cursor, "Workflow decision evidence cursor", 512)

    init {
        require(limit in 1..MAX_LIMIT) {
            "Workflow decision evidence page limit must be between 1 and $MAX_LIMIT."
        }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 100
    }
}
