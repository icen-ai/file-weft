package ai.icen.fw.web.api.v1.workflow

import ai.icen.fw.web.api.immutableList
import ai.icen.fw.web.api.optionalText
import ai.icen.fw.web.api.requiredText
import ai.icen.fw.web.api.v1.document.DocumentDto

/**
 * Immutable public representation of a single approval task.
 *
 * It intentionally does not disclose an assignee's host-user identifier or
 * review comments. `assignedToCurrentUser=false` can also mean that the task
 * is deliberately unassigned; the inbox item contract declares actionability.
 */
class WorkflowTaskDto @JvmOverloads constructor(
    id: String,
    workflowId: String,
    state: String,
    val createdTime: Long,
    val updatedTime: Long,
    val assignedToCurrentUser: Boolean = false,
) {
    val id: String = requiredText(id, "Workflow task id", 128)
    val workflowId: String = requiredText(workflowId, "Workflow id", 128)
    val state: String = requiredText(state, "Workflow task state", 64)

    init {
        require(createdTime >= 0) { "Workflow task creation time must not be negative." }
        require(updatedTime >= createdTime) { "Workflow task update time must not precede creation time." }
    }
}

/** One actionable task plus the minimum safe document/workflow context needed by an inbox UI. */
class WorkflowTaskInboxItemDto(
    val task: WorkflowTaskDto,
    val document: DocumentDto,
    workflowType: String,
    workflowState: String,
) {
    val workflowType: String = requiredText(workflowType, "Workflow type", 64)
    val workflowState: String = requiredText(workflowState, "Workflow state", 64)
    val actionableByCurrentUser: Boolean = true
}

/** A history task deliberately omits assignment, decision comments and operator identity. */
class WorkflowHistoryTaskDto(
    id: String,
    state: String,
    val createdTime: Long,
    val updatedTime: Long,
) {
    val id: String = requiredText(id, "Workflow history task id", 128)
    val state: String = requiredText(state, "Workflow history task state", 64)

    init {
        require(createdTime >= 0) { "Workflow history task creation time must not be negative." }
        require(updatedTime >= createdTime) { "Workflow history task update time must not precede creation time." }
    }
}

class DocumentWorkflowDto(
    id: String,
    documentId: String,
    workflowType: String,
    state: String,
    val createdTime: Long,
    val updatedTime: Long,
    tasks: List<WorkflowHistoryTaskDto>,
) {
    val id: String = requiredText(id, "Workflow id", 128)
    val documentId: String = requiredText(documentId, "Workflow document id", 128)
    val workflowType: String = requiredText(workflowType, "Workflow type", 64)
    val state: String = requiredText(state, "Workflow state", 64)
    val tasks: List<WorkflowHistoryTaskDto> = immutableList(tasks)

    init {
        require(createdTime >= 0) { "Workflow creation time must not be negative." }
        require(updatedTime >= createdTime) { "Workflow update time must not precede creation time." }
        require(this.tasks.isNotEmpty()) { "Workflow history must contain at least one task." }
        require(this.tasks.map { task -> task.id }.distinct().size == this.tasks.size) {
            "Workflow history task identifiers must be unique."
        }
    }
}

class WorkflowTaskPageQuery @JvmOverloads constructor(
    cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    val cursor: String? = optionalText(cursor, "Workflow task page cursor", 512)

    init {
        require(limit in 1..MAX_LIMIT) { "Workflow task page limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 100
    }
}

class DocumentWorkflowPageQuery @JvmOverloads constructor(
    cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    val cursor: String? = optionalText(cursor, "Document workflow page cursor", 512)

    init {
        require(limit in 1..MAX_LIMIT) { "Document workflow page limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 100
    }
}

/**
 * Starts document review through a configured route. Reviewer resolution is an
 * authorization/workflow concern and is deliberately not accepted from clients.
 */
class SubmitDocumentReviewCommand @JvmOverloads constructor(reviewRouteId: String? = null) {
    val reviewRouteId: String? = optionalText(reviewRouteId, "Document review route id", 256)
}

/** Mutable JSON bean; controllers must convert it to [SubmitDocumentReviewCommand]. */
class SubmitDocumentReviewRequest {
    var reviewRouteId: String? = null
}

class ApproveWorkflowTaskCommand @JvmOverloads constructor(
    comment: String? = null,
    deliveryProfileId: String? = null,
) {
    val comment: String? = optionalText(comment, "Workflow approval comment", 1_000)
    val deliveryProfileId: String? = optionalText(deliveryProfileId, "Document delivery profile id", 256)
}

/** Mutable JSON bean; controllers must convert it to [ApproveWorkflowTaskCommand]. */
class ApproveWorkflowTaskRequest {
    var comment: String? = null
    var deliveryProfileId: String? = null
}

class RejectWorkflowTaskCommand @JvmOverloads constructor(comment: String? = null) {
    val comment: String? = optionalText(comment, "Workflow rejection comment", 1_000)
}

/** Mutable JSON bean; controllers must convert it to [RejectWorkflowTaskCommand]. */
class RejectWorkflowTaskRequest {
    var comment: String? = null
}
