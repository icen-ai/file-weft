package com.fileweft.domain.workflow

import com.fileweft.core.id.Identifier
import java.util.Collections

class WorkflowInstance(
    val id: Identifier,
    val tenantId: Identifier,
    val documentId: Identifier,
    val workflowType: String,
    state: WorkflowState = WorkflowState.PENDING,
    tasks: List<WorkflowTask>,
) {
    private val mutableTasks = ArrayList<WorkflowTask>()

    var state: WorkflowState = state
        private set

    val tasks: List<WorkflowTask>
        get() = Collections.unmodifiableList(mutableTasks)

    init {
        require(workflowType.isNotBlank()) { "Workflow type must not be blank." }
        require(tasks.isNotEmpty()) { "Workflow instance must contain at least one task." }
        require(tasks.map { it.id }.distinct().size == tasks.size) { "Workflow task identifiers must be unique." }
        tasks.forEach {
            require(it.tenantId == tenantId) { "Workflow task tenant must match workflow tenant." }
            require(it.workflowId == id) { "Workflow task workflow id must match workflow instance." }
        }
        mutableTasks.addAll(tasks)
        requireStateAndTasksAreConsistent()
    }

    fun approve(taskId: Identifier, operatorId: Identifier, comment: String? = null) {
        require(state == WorkflowState.PENDING) { "Only pending workflows can be approved." }
        task(taskId).approve(operatorId, comment)
        if (mutableTasks.all { it.state == WorkflowTaskState.APPROVED }) {
            state = WorkflowState.APPROVED
        }
    }

    /**
     * Validates whether approving this task would finish the workflow without
     * mutating it. Application services use this to resolve remote delivery
     * policy before acquiring the database transaction for the final decision.
     */
    fun willCompleteAfterApproval(taskId: Identifier, operatorId: Identifier): Boolean {
        require(state == WorkflowState.PENDING) { "Only pending workflows can be approved." }
        val candidate = task(taskId)
        require(candidate.state == WorkflowTaskState.PENDING) { "Only pending workflow tasks can be decided." }
        require(candidate.assigneeId == null || candidate.assigneeId == operatorId) {
            "Workflow task is assigned to another operator."
        }
        return mutableTasks.all { task -> task.id == taskId || task.state == WorkflowTaskState.APPROVED }
    }

    fun reject(taskId: Identifier, operatorId: Identifier, comment: String? = null) {
        require(state == WorkflowState.PENDING) { "Only pending workflows can be rejected." }
        task(taskId).reject(operatorId, comment)
        state = WorkflowState.REJECTED
    }

    private fun task(taskId: Identifier): WorkflowTask =
        mutableTasks.firstOrNull { it.id == taskId }
            ?: throw NoSuchElementException("Workflow task ${taskId.value} does not belong to workflow ${id.value}.")

    private fun requireStateAndTasksAreConsistent() {
        when (state) {
            WorkflowState.PENDING -> require(
                mutableTasks.none { it.state == WorkflowTaskState.REJECTED } &&
                    mutableTasks.any { it.state == WorkflowTaskState.PENDING },
            ) {
                "Pending workflow must have pending tasks and no rejected task."
            }

            WorkflowState.APPROVED -> require(mutableTasks.all { it.state == WorkflowTaskState.APPROVED }) {
                "Approved workflow tasks must all be approved."
            }

            WorkflowState.REJECTED -> require(mutableTasks.any { it.state == WorkflowTaskState.REJECTED }) {
                "Rejected workflow must contain a rejected task."
            }
        }
    }
}
