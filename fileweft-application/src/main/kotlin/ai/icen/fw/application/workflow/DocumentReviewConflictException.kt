package ai.icen.fw.application.workflow

import ai.icen.fw.domain.workflow.WorkflowConflictException

/**
 * Submission could not create or reuse a review workflow because the document
 * or its active review changed while the route was being resolved. An already
 * active pending review is not a conflict: submission returns that existing
 * workflow as its idempotent result. Retrying after this exception resolves
 * the race.
 */
class DocumentReviewConflictException @JvmOverloads constructor(
    message: String = DEFAULT_MESSAGE,
    cause: Throwable? = null,
) : WorkflowConflictException(message, cause) {
    companion object {
        const val DEFAULT_MESSAGE: String = "Document review conflicts with the current workflow state."
    }
}
