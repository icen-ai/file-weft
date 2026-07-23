package ai.icen.fw.domain.workflow

import ai.icen.fw.core.id.Identifier

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

    /** Immutable host-identity snapshot captured when this task is decided. */
    var decisionOperatorId: Identifier? = null
        private set

    /** Optional display-name snapshot; identity resolution is never repeated during reads. */
    var decisionOperatorName: String? = null
        private set

    init {
        require(comment == null || comment.isNotBlank()) { "Workflow task comment must not be blank when provided." }
    }

    /**
     * Reconstructs a persisted task without changing the released six-argument
     * constructor. Null decision identity is valid for rows completed before
     * decision evidence was introduced.
     */
    constructor(
        id: Identifier,
        tenantId: Identifier,
        workflowId: Identifier,
        assigneeId: Identifier?,
        state: WorkflowTaskState,
        comment: String?,
        decisionOperatorId: Identifier?,
        decisionOperatorName: String?,
    ) : this(id, tenantId, workflowId, assigneeId, state, comment) {
        restoreDecisionOperator(decisionOperatorId, decisionOperatorName)
    }

    fun approve(operatorId: Identifier, decisionComment: String? = null) {
        approve(operatorId, null, decisionComment)
    }

    fun approve(operatorId: Identifier, operatorName: String?, decisionComment: String?) {
        requireAssignedTo(operatorId)
        requirePendingDecision()
        validateComment(decisionComment)
        state = WorkflowTaskState.APPROVED
        comment = decisionComment
        captureDecisionOperator(operatorId, operatorName)
    }

    fun reject(operatorId: Identifier, decisionComment: String? = null) {
        reject(operatorId, null, decisionComment)
    }

    fun reject(operatorId: Identifier, operatorName: String?, decisionComment: String?) {
        requireAssignedTo(operatorId)
        requirePendingDecision()
        validateComment(decisionComment)
        state = WorkflowTaskState.REJECTED
        comment = decisionComment
        captureDecisionOperator(operatorId, operatorName)
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
            throw WorkflowTaskDeniedException(id)
        }
    }

    private fun validateComment(decisionComment: String?) {
        require(decisionComment == null || decisionComment.isNotBlank()) {
            "Workflow decision comment must not be blank when provided."
        }
    }

    private fun captureDecisionOperator(operatorId: Identifier, operatorName: String?) {
        decisionOperatorId = operatorId
        decisionOperatorName = validatedOperatorName(operatorName)
    }

    private fun restoreDecisionOperator(operatorId: Identifier?, operatorName: String?) {
        require(state != WorkflowTaskState.PENDING || (operatorId == null && operatorName == null)) {
            "A pending workflow task cannot contain decision operator evidence."
        }
        require(operatorName == null || operatorId != null) {
            "Workflow decision operator name requires an operator id."
        }
        decisionOperatorId = operatorId
        decisionOperatorName = validatedOperatorName(operatorName)
    }

    private fun validatedOperatorName(operatorName: String?): String? = operatorName
        ?.takeIf { name -> name.isNotBlank() }
        ?.also { name ->
            require(name.length <= MAX_OPERATOR_NAME_LENGTH) {
                "Workflow decision operator name must not exceed $MAX_OPERATOR_NAME_LENGTH characters."
            }
        }

    private companion object {
        const val MAX_OPERATOR_NAME_LENGTH = 256
    }
}
