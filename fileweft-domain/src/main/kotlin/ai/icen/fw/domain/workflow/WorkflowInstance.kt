package ai.icen.fw.domain.workflow

import ai.icen.fw.core.id.Identifier
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
    private var mutableSubmittedBy: Identifier? = null

    /**
     * Additive rehydration constructor. The original six-argument JVM
     * constructor remains unchanged for existing Java and Kotlin users. A null
     * submitter represents a legacy workflow and must never be guessed.
     */
    constructor(
        id: Identifier,
        tenantId: Identifier,
        documentId: Identifier,
        workflowType: String,
        state: WorkflowState,
        tasks: List<WorkflowTask>,
        submittedBy: Identifier?,
    ) : this(id, tenantId, documentId, workflowType, state, tasks) {
        mutableSubmittedBy = submittedBy
    }

    var state: WorkflowState = state
        private set

    val tasks: List<WorkflowTask>
        get() = Collections.unmodifiableList(mutableTasks)

    /** Trusted submitter identity when the workflow was created with submitter persistence. */
    val submittedBy: Identifier?
        get() = mutableSubmittedBy

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
        approve(taskId, operatorId, null, comment)
    }

    fun approve(taskId: Identifier, operatorId: Identifier, operatorName: String?, comment: String?) {
        val candidate = task(taskId)
        candidate.requireAssignedTo(operatorId)
        requirePendingWorkflow("approved")
        candidate.approve(operatorId, operatorName, comment)
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
        val candidate = task(taskId)
        candidate.requireAssignedTo(operatorId)
        requirePendingWorkflow("approved")
        candidate.requirePendingDecision()
        return mutableTasks.all { task -> task.id == taskId || task.state == WorkflowTaskState.APPROVED }
    }

    fun reject(taskId: Identifier, operatorId: Identifier, comment: String? = null) {
        reject(taskId, operatorId, null, comment)
    }

    fun reject(taskId: Identifier, operatorId: Identifier, operatorName: String?, comment: String?) {
        val candidate = task(taskId)
        candidate.requireAssignedTo(operatorId)
        requirePendingWorkflow("rejected")
        candidate.reject(operatorId, operatorName, comment)
        state = WorkflowState.REJECTED
    }

    /** Withdraws an unfinished review while retaining task decisions as historical evidence. */
    fun withdraw() {
        if (state != WorkflowState.PENDING) {
            throw WorkflowWithdrawalConflictException(
                "Only pending workflows can be withdrawn; workflow ${id.value} is ${state.name}.",
            )
        }
        state = WorkflowState.WITHDRAWN
    }

    private fun task(taskId: Identifier): WorkflowTask =
        mutableTasks.firstOrNull { it.id == taskId }
            ?: throw WorkflowTaskNotFoundException(id, taskId)

    private fun requirePendingWorkflow(operation: String) {
        if (state != WorkflowState.PENDING) {
            throw WorkflowDecisionConflictException(
                "Only pending workflows can be $operation; workflow ${id.value} is ${state.name}.",
            )
        }
    }

    private fun requireStateAndTasksAreConsistent() {
        when (state) {
            WorkflowState.PENDING -> require(
                mutableTasks.none { it.state == WorkflowTaskState.REJECTED } &&
                    mutableTasks.any { it.state == WorkflowTaskState.PENDING },
            ) {
                "Pending workflow must have pending tasks and no rejected task."
            }

            WorkflowState.WITHDRAWN -> require(
                mutableTasks.none { it.state == WorkflowTaskState.REJECTED } &&
                    mutableTasks.any { it.state == WorkflowTaskState.PENDING },
            ) {
                "Withdrawn workflow must retain unfinished tasks and no rejected task."
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
