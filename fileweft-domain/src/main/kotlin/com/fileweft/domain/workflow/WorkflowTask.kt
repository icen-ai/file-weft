package com.fileweft.domain.workflow

import com.fileweft.core.id.Identifier

class WorkflowTask(
    val id: Identifier,
    val tenantId: Identifier,
    val workflowId: Identifier,
    val assigneeId: Identifier? = null,
    state: WorkflowTaskState = WorkflowTaskState.PENDING,
    comment: String? = null,
) {
    var state: WorkflowTaskState = state
        private set

    var comment: String? = comment
        private set

    init {
        require(comment == null || comment.isNotBlank()) { "Workflow task comment must not be blank when provided." }
    }

    fun approve(operatorId: Identifier, decisionComment: String? = null) {
        requirePending()
        requireAssignedTo(operatorId)
        validateComment(decisionComment)
        state = WorkflowTaskState.APPROVED
        comment = decisionComment
    }

    fun reject(operatorId: Identifier, decisionComment: String? = null) {
        requirePending()
        requireAssignedTo(operatorId)
        validateComment(decisionComment)
        state = WorkflowTaskState.REJECTED
        comment = decisionComment
    }

    private fun requirePending() {
        require(state == WorkflowTaskState.PENDING) { "Only pending workflow tasks can be decided." }
    }

    private fun requireAssignedTo(operatorId: Identifier) {
        require(assigneeId == null || assigneeId == operatorId) { "Workflow task is assigned to another operator." }
    }

    private fun validateComment(decisionComment: String?) {
        require(decisionComment == null || decisionComment.isNotBlank()) {
            "Workflow decision comment must not be blank when provided."
        }
    }
}
