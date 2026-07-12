package com.fileweft.application.workflow

import com.fileweft.application.delivery.DocumentDeliveryPreparation
import com.fileweft.application.lifecycle.DocumentLifecycleMutationContext
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.Document
import com.fileweft.domain.workflow.WorkflowInstance
import com.fileweft.spi.workflow.DocumentReviewRouteRequest

/** Immutable route snapshot resolved before the final submit transaction. */
internal class DocumentReviewSubmitPreparation(
    val lifecycle: DocumentLifecycleMutationContext,
    val reviewerId: Identifier?,
    val routeRequest: DocumentReviewRouteRequest,
    val resolvedRoute: ResolvedDocumentReviewRoute,
)

/** Authorization, catalog evidence and workflow snapshot for one review decision. */
internal class DocumentReviewDecisionContext(
    val lifecycle: DocumentLifecycleMutationContext,
    val workflowId: Identifier,
    val taskId: Identifier,
    val approved: Boolean,
    val workflowSnapshot: WorkflowInstance,
)

/** Local result returned before the enclosing idempotency record is completed. */
internal class DocumentReviewMutationResult(
    val document: Document,
    val workflow: WorkflowInstance,
)

/**
 * Signals that a concurrent approval became the final vote only after locks
 * were acquired. Throwing rolls back the idempotency claim and all local work;
 * the caller then prepares delivery outside the transaction and retries.
 */
internal class DocumentReviewDeliveryPreparationRequiredException : RuntimeException(
    "The completing review decision requires delivery preparation.",
    null,
    false,
    false,
)

/** Optional delivery snapshot paired with the decision that requested it. */
internal class DocumentReviewDecisionDelivery(
    val decision: DocumentReviewDecisionContext,
    val preparation: DocumentDeliveryPreparation,
)
