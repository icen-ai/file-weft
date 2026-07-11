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
        requireAssignedTo(operatorId)
        requirePendingDecision()
        validateComment(decisionComment)
        state = WorkflowTaskState.APPROVED
        comment = decisionComment
    }

    fun reject(operatorId: Identifier, decisionComment: String? = null) {
        requireAssignedTo(operatorId)
        requirePendingDecision()
        validateComment(decisionComment)
        state = WorkflowTaskState.REJECTED
        comment = decisionComment
    }

    internal fun requirePendingDecision() {
        if (state != WorkflowTaskState.PENDING) {
            throw WorkflowDecisionConflictException(
                "Workflow task ${id.value} has already been decided.",
            )
        }
    }

    internal fun requireAssignedTo(operatorId: Identifier) {
        if (assigneeId != null && assigneeId != operatorId) {
            throw WorkflowTaskAssignmentDeniedException(id)
        }
    }

    private fun validateComment(decisionComment: String?) {
        require(decisionComment == null || decisionComment.isNotBlank()) {
            "Workflow decision comment must not be blank when provided."
        }
    }
}
