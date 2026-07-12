package ai.icen.fw.domain.workflow

import ai.icen.fw.core.id.Identifier

/**
 * Base type for an otherwise valid workflow command that conflicts with the
 * workflow's current state.
 *
 * Keeping this separate from request validation lets outer adapters map a
 * decision race to HTTP 409 without inspecting an exception message.
 */
open class WorkflowConflictException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** A workflow or task was no longer pending when a reviewer tried to decide it. */
class WorkflowDecisionConflictException @JvmOverloads constructor(
    message: String = DEFAULT_MESSAGE,
    cause: Throwable? = null,
) : WorkflowConflictException(message, cause) {
    companion object {
        const val DEFAULT_MESSAGE: String = "Workflow decision conflicts with the current workflow state."
    }
}

/** The requested task belongs to another reviewer. */
class WorkflowTaskAssignmentDeniedException @JvmOverloads constructor(
    val taskId: Identifier,
    message: String = "Workflow task ${taskId.value} is assigned to another operator.",
    cause: Throwable? = null,
) : SecurityException(message, cause)

/** The task identifier is not part of the addressed workflow. */
class WorkflowTaskNotFoundException(
    val workflowId: Identifier,
    val taskId: Identifier,
) : NoSuchElementException(
    "Workflow task ${taskId.value} does not belong to workflow ${workflowId.value}.",
)
