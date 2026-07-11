package com.fileweft.application.workflow

import com.fileweft.domain.workflow.WorkflowConflictException

/**
 * Submission could not create a review workflow because the document or its
 * active review changed while the route was being resolved.
 */
class DocumentReviewConflictException @JvmOverloads constructor(
    message: String = DEFAULT_MESSAGE,
    cause: Throwable? = null,
) : WorkflowConflictException(message, cause) {
    companion object {
        const val DEFAULT_MESSAGE: String = "Document review conflicts with the current workflow state."
    }
}
