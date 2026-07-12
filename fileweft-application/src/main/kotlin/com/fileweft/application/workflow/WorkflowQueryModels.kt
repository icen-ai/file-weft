package com.fileweft.application.workflow

import com.fileweft.application.document.DocumentSummaryView
import com.fileweft.core.id.Identifier
import com.fileweft.domain.workflow.WorkflowState
import com.fileweft.domain.workflow.WorkflowTaskState
import java.util.ArrayList
import java.util.Collections

class WorkflowTaskPageCursor(
    val createdTime: Long,
    val id: Identifier,
) {
    init {
        require(createdTime >= 0) { "Workflow task page cursor creation time must not be negative." }
    }
}

class WorkflowTaskPageRequest @JvmOverloads constructor(
    val cursor: WorkflowTaskPageCursor? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "Workflow task page limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 100
    }
}

/** One pending task that the current trusted reviewer can decide. */
class WorkflowTaskView(
    val id: Identifier,
    val workflowId: Identifier,
    val state: WorkflowTaskState,
    val createdTime: Long,
    val updatedTime: Long,
    val assignedToCurrentUser: Boolean,
) {
    val actionableByCurrentUser: Boolean = true

    init {
        require(state == WorkflowTaskState.PENDING) { "Workflow inbox task must be pending." }
        require(createdTime >= 0) { "Workflow task creation time must not be negative." }
        require(updatedTime >= createdTime) { "Workflow task update time must not precede creation time." }
    }
}

class WorkflowTaskInboxItemView(
    val task: WorkflowTaskView,
    val document: DocumentSummaryView,
    workflowType: String,
    val workflowState: WorkflowState,
) {
    val workflowType: String = safeRequiredText(workflowType, "Workflow type", 64)

    init {
        require(task.workflowId.value.isNotBlank()) { "Workflow task workflow id must not be blank." }
        require(workflowState == WorkflowState.PENDING) { "Workflow inbox workflow must be pending." }
    }
}

class WorkflowTaskPageResult @JvmOverloads constructor(
    items: List<WorkflowTaskInboxItemView>,
    val nextCursor: WorkflowTaskPageCursor? = null,
) {
    val items: List<WorkflowTaskInboxItemView> = immutableList(items)

    init {
        require(this.items.size <= WorkflowTaskPageRequest.MAX_LIMIT) {
            "Workflow task page must not contain more than ${WorkflowTaskPageRequest.MAX_LIMIT} items."
        }
    }
}

class DocumentWorkflowPageCursor(
    val createdTime: Long,
    val id: Identifier,
) {
    init {
        require(createdTime >= 0) { "Document workflow cursor creation time must not be negative." }
    }
}

class DocumentWorkflowPageRequest @JvmOverloads constructor(
    val cursor: DocumentWorkflowPageCursor? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "Document workflow page limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 100
    }
}

/** Assignment and comments remain hidden in document history. */
class WorkflowHistoryTaskView(
    val id: Identifier,
    val state: WorkflowTaskState,
    val createdTime: Long,
    val updatedTime: Long,
) {
    init {
        require(createdTime >= 0) { "Workflow history task creation time must not be negative." }
        require(updatedTime >= createdTime) { "Workflow history task update time must not precede creation time." }
    }
}

class WorkflowView(
    val id: Identifier,
    val documentId: Identifier,
    workflowType: String,
    val state: WorkflowState,
    val createdTime: Long,
    val updatedTime: Long,
    tasks: List<WorkflowHistoryTaskView>,
) {
    val workflowType: String = safeRequiredText(workflowType, "Workflow type", 64)
    val tasks: List<WorkflowHistoryTaskView> = immutableList(tasks)

    init {
        require(createdTime >= 0) { "Workflow creation time must not be negative." }
        require(updatedTime >= createdTime) { "Workflow update time must not precede creation time." }
        require(this.tasks.isNotEmpty()) { "Workflow history must contain at least one task." }
        require(this.tasks.map { task -> task.id }.distinct().size == this.tasks.size) {
            "Workflow history task identifiers must be unique."
        }
    }
}

class DocumentWorkflowPageResult @JvmOverloads constructor(
    items: List<WorkflowView>,
    val nextCursor: DocumentWorkflowPageCursor? = null,
) {
    val items: List<WorkflowView> = immutableList(items)

    init {
        require(this.items.size <= DocumentWorkflowPageRequest.MAX_LIMIT) {
            "Document workflow page must not contain more than ${DocumentWorkflowPageRequest.MAX_LIMIT} items."
        }
    }
}

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun safeRequiredText(value: String, label: String, maxLength: Int): String {
    require(value.isNotBlank()) { "$label must not be blank." }
    require(value.length <= maxLength) { "$label must not exceed $maxLength characters." }
    require(value.none { character -> Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt() }) {
        "$label must not contain unsafe characters."
    }
    return value
}
