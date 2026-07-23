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

/** A review was no longer pending when its submitter or an operator tried to withdraw it. */
class WorkflowWithdrawalConflictException @JvmOverloads constructor(
    message: String = DEFAULT_MESSAGE,
    cause: Throwable? = null,
) : WorkflowConflictException(message, cause) {
    companion object {
        const val DEFAULT_MESSAGE: String = "Workflow withdrawal conflicts with the current workflow state."
    }
}

/**
 * The requested task belongs to another reviewer.
 *
 * A business denial expressed with framework semantics so outer adapters can
 * map it to HTTP 403 without routing it through [SecurityException], which
 * security scanners, log alerting and framework fallbacks treat as a
 * platform-level signal.
 */
open class WorkflowTaskDeniedException @JvmOverloads constructor(
    val taskId: Identifier,
    message: String = "Workflow task ${taskId.value} is assigned to another operator.",
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * The task identifier is not part of the addressed workflow.
 *
 * A business lookup failure expressed with framework semantics so outer
 * adapters can map it to HTTP 404 without routing it through the generic
 * [NoSuchElementException] collection signal.
 */
open class WorkflowTaskMissingException(
    val workflowId: Identifier,
    val taskId: Identifier,
) : IllegalStateException(
    "Workflow task ${taskId.value} does not belong to workflow ${workflowId.value}.",
)

/** The requested task belongs to another reviewer. */
@Deprecated(
    message = "Misused JDK SecurityException semantics; use WorkflowTaskDeniedException instead. " +
        "Retained only for source and binary compatibility: the framework no longer throws this type, " +
        "so catch clauses for it never match new instances. It will be removed in a future major release.",
    level = DeprecationLevel.WARNING,
)
class WorkflowTaskAssignmentDeniedException @JvmOverloads constructor(
    taskId: Identifier,
    message: String = "Workflow task ${taskId.value} is assigned to another operator.",
    cause: Throwable? = null,
) : WorkflowTaskDeniedException(taskId, message, cause)

/** The task identifier is not part of the addressed workflow. */
@Deprecated(
    message = "Misused JDK NoSuchElementException semantics; use WorkflowTaskMissingException instead. " +
        "Retained only for source and binary compatibility: the framework no longer throws this type, " +
        "so catch clauses for it never match new instances. It will be removed in a future major release.",
    level = DeprecationLevel.WARNING,
)
class WorkflowTaskNotFoundException(
    workflowId: Identifier,
    taskId: Identifier,
) : WorkflowTaskMissingException(workflowId, taskId)
