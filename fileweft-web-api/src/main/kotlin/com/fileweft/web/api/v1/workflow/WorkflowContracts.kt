package com.fileweft.web.api.v1.workflow

import com.fileweft.web.api.optionalText
import com.fileweft.web.api.requiredText

/**
 * Immutable public representation of a single approval task.
 *
 * It intentionally does not disclose an assignee's host-user identifier or
 * review comments. The caller-scoped boolean is enough for a UI to identify
 * its own actionable task without exposing another user's identity.
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

/**
 * Starts document review through a configured route. Reviewer resolution is an
 * authorization/workflow concern and is deliberately not accepted from clients.
 */
class SubmitDocumentReviewCommand @JvmOverloads constructor(reviewRouteId: String? = null) {
    val reviewRouteId: String? = optionalText(reviewRouteId, "Document review route id", 256)
}

class ApproveWorkflowTaskCommand @JvmOverloads constructor(
    comment: String? = null,
    deliveryProfileId: String? = null,
) {
    val comment: String? = optionalText(comment, "Workflow approval comment", 1_000)
    val deliveryProfileId: String? = optionalText(deliveryProfileId, "Document delivery profile id", 256)
}

class RejectWorkflowTaskCommand @JvmOverloads constructor(comment: String? = null) {
    val comment: String? = optionalText(comment, "Workflow rejection comment", 1_000)
}
