package ai.icen.fw.application.workflow

import ai.icen.fw.application.delivery.DocumentDeliveryPreparation
import ai.icen.fw.application.lifecycle.DocumentLifecycleMutationContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.workflow.WorkflowInstance
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest

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

/** Authorization, catalog evidence and immutable workflow identity for one withdrawal. */
internal class DocumentReviewWithdrawalContext(
    val lifecycle: DocumentLifecycleMutationContext,
    val workflowId: Identifier,
    val submittedBySnapshot: Identifier?,
    val authorizedAsSubmitter: Boolean,
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
