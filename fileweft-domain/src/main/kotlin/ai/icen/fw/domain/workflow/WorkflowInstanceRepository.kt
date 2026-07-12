package ai.icen.fw.domain.workflow

import ai.icen.fw.core.id.Identifier

interface WorkflowInstanceRepository {
    fun findById(tenantId: Identifier, workflowId: Identifier): WorkflowInstance?

    /**
     * Loads a workflow for a state-changing decision inside the caller's
     * transaction. Implementations must serialize concurrent decisions for the
     * same workflow (for example with a row lock or compare-and-set); silently
     * falling back to an ordinary read can lose another reviewer's decision.
     */
    fun findForDecision(tenantId: Identifier, workflowId: Identifier): WorkflowInstance? =
        throw UnsupportedOperationException(
            "WorkflowInstanceRepository must implement findForDecision with concurrency-safe decision semantics.",
        )

    fun findActiveByDocument(tenantId: Identifier, documentId: Identifier): WorkflowInstance?

    fun save(workflow: WorkflowInstance)
}
