package ai.icen.fw.application.workflow

import ai.icen.fw.application.catalog.DocumentCatalogLifecycleService
import ai.icen.fw.application.catalog.DocumentLifecycleMutationGuard
import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.IdempotencyStoreException
import ai.icen.fw.application.idempotency.IdempotentCommand
import ai.icen.fw.application.idempotency.IdempotentCommandResult
import ai.icen.fw.application.idempotency.IdempotencyReplayMapper
import ai.icen.fw.application.idempotency.RequestFingerprint
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.lifecycle.DocumentLifecycleMutationTransaction
import ai.icen.fw.application.lifecycle.DocumentLifecycleReceipt
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.workflow.WorkflowInstance
import ai.icen.fw.domain.workflow.WorkflowTaskState

/** Flat review boundary for hosts that do not install a document catalog. */
class IdempotentDocumentReviewWorkflowService(
    reviews: DocumentReviewWorkflowService,
    idempotency: RequestIdempotencyService,
) {
    private val delegate = IdempotentDocumentReviewWorkflowDelegate(reviews, idempotency, null)

    fun submitForReview(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt =
        delegate.submitForReview(documentId, null, null, idempotencyKey)

    fun submitForReview(
        documentId: Identifier,
        reviewRouteId: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.submitForReview(documentId, null, reviewRouteId, idempotencyKey)

    fun submitForReview(
        documentId: Identifier,
        reviewerId: Identifier?,
        reviewRouteId: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.submitForReview(
        documentId,
        reviewerId,
        reviewRouteId,
        idempotencyKey,
    )

    fun approve(
        workflowId: Identifier,
        taskId: Identifier,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.approve(workflowId, taskId, null, null, idempotencyKey)

    fun approve(
        workflowId: Identifier,
        taskId: Identifier,
        comment: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.approve(workflowId, taskId, comment, null, idempotencyKey)

    fun approve(
        workflowId: Identifier,
        taskId: Identifier,
        comment: String?,
        deliveryProfileId: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.approve(
        workflowId,
        taskId,
        comment,
        deliveryProfileId,
        idempotencyKey,
    )

    fun reject(
        workflowId: Identifier,
        taskId: Identifier,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.reject(workflowId, taskId, null, idempotencyKey)

    fun reject(
        workflowId: Identifier,
        taskId: Identifier,
        comment: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.reject(workflowId, taskId, comment, idempotencyKey)

    fun withdrawReview(
        workflowId: Identifier,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.withdrawReview(workflowId, idempotencyKey)
}

/** Catalog-aware review boundary; every replay still checks the current source-folder ACL. */
class IdempotentDocumentCatalogReviewWorkflowService(
    catalogLifecycle: DocumentCatalogLifecycleService,
    idempotency: RequestIdempotencyService,
) {
    private val delegate = catalogLifecycle.createIdempotentReviewDelegate(idempotency)

    fun submitForReview(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt =
        delegate.submitForReview(documentId, null, null, idempotencyKey)

    fun submitForReview(
        documentId: Identifier,
        reviewRouteId: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.submitForReview(documentId, null, reviewRouteId, idempotencyKey)

    fun submitForReview(
        documentId: Identifier,
        reviewerId: Identifier?,
        reviewRouteId: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.submitForReview(
        documentId,
        reviewerId,
        reviewRouteId,
        idempotencyKey,
    )

    fun approve(
        workflowId: Identifier,
        taskId: Identifier,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.approve(workflowId, taskId, null, null, idempotencyKey)

    fun approve(
        workflowId: Identifier,
        taskId: Identifier,
        comment: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.approve(workflowId, taskId, comment, null, idempotencyKey)

    fun approve(
        workflowId: Identifier,
        taskId: Identifier,
        comment: String?,
        deliveryProfileId: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.approve(
        workflowId,
        taskId,
        comment,
        deliveryProfileId,
        idempotencyKey,
    )

    fun reject(
        workflowId: Identifier,
        taskId: Identifier,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.reject(workflowId, taskId, null, idempotencyKey)

    fun reject(
        workflowId: Identifier,
        taskId: Identifier,
        comment: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.reject(workflowId, taskId, comment, idempotencyKey)

    fun withdrawReview(
        workflowId: Identifier,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.withdrawReview(workflowId, idempotencyKey)
}

internal class IdempotentDocumentReviewWorkflowDelegate(
    private val reviews: DocumentReviewWorkflowService,
    private val idempotency: RequestIdempotencyService,
    private val guard: DocumentLifecycleMutationGuard?,
) {
    @JvmSynthetic
    fun submitForReview(
        documentId: Identifier,
        reviewerId: Identifier?,
        reviewRouteId: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt {
        val normalizedReviewer = reviewerId?.also(::requireBoundaryIdentifier)
        val normalizedRoute = optionalText(reviewRouteId, "Review route id", MAX_SELECTOR_LENGTH, trim = true)
        val context = reviews.prepareSubmitForReview(documentId, guard)
        val request = RequestIdempotency.create(
            tenantId = context.tenantId,
            operatorId = context.operator.id,
            idempotencyKey = idempotencyKey,
            action = SUBMIT_ACTION,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = context.documentId,
            requestFingerprint = RequestFingerprint.sha256(
                SUBMIT_FINGERPRINT_VERSION,
                normalizedReviewer?.value,
                normalizedRoute,
            ),
        )
        idempotency.findCompleted(request)?.let { result ->
            return replay(context.documentId, null, result)
        }
        val preparation = reviews.prepareSubmitForReviewRoute(context, normalizedReviewer, normalizedRoute)
        val validated = context.revalidate()
        return idempotency.execute(
            request,
            IdempotencyReplayMapper { result -> replay(context.documentId, null, result) },
            IdempotentCommand {
                DocumentLifecycleMutationTransaction.execute {
                    val result = reviews.submitForReviewInCurrentTransaction(validated, preparation)
                    fresh(
                        result = result,
                        expectedDocumentId = context.documentId,
                        expectedWorkflowId = null,
                        taskId = firstPendingTaskId(result.workflow),
                    )
                }
            },
        ).value
    }

    @JvmSynthetic
    fun approve(
        workflowId: Identifier,
        taskId: Identifier,
        comment: String?,
        deliveryProfileId: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = decide(
        workflowId = workflowId,
        taskId = taskId,
        comment = optionalText(comment, "Workflow approval comment", MAX_COMMENT_LENGTH, trim = false),
        deliveryProfileId = optionalText(
            deliveryProfileId,
            "Delivery profile id",
            MAX_SELECTOR_LENGTH,
            trim = true,
        ),
        approved = true,
        idempotencyKey = idempotencyKey,
    )

    @JvmSynthetic
    fun reject(
        workflowId: Identifier,
        taskId: Identifier,
        comment: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = decide(
        workflowId = workflowId,
        taskId = taskId,
        comment = optionalText(comment, "Workflow rejection comment", MAX_COMMENT_LENGTH, trim = false),
        deliveryProfileId = null,
        approved = false,
        idempotencyKey = idempotencyKey,
    )

    @JvmSynthetic
    fun withdrawReview(
        workflowId: Identifier,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt {
        requireBoundaryIdentifier(workflowId)
        val withdrawal = reviews.prepareReviewWithdrawal(workflowId, guard)
        val context = withdrawal.lifecycle
        val request = RequestIdempotency.create(
            tenantId = context.tenantId,
            operatorId = context.operator.id,
            idempotencyKey = idempotencyKey,
            action = WITHDRAW_ACTION,
            resourceType = WORKFLOW_RESOURCE_TYPE,
            resourceId = workflowId,
            requestFingerprint = RequestFingerprint.sha256(WITHDRAW_FINGERPRINT_VERSION),
        )
        idempotency.findCompleted(request)?.let { result ->
            return replay(context.documentId, workflowId, result)
        }
        val validated = reviews.revalidateReviewWithdrawal(withdrawal)
        return idempotency.execute(
            request,
            IdempotencyReplayMapper { result -> replay(context.documentId, workflowId, result) },
            IdempotentCommand {
                DocumentLifecycleMutationTransaction.execute {
                    fresh(
                        result = reviews.withdrawSafelyInCurrentTransaction(validated, withdrawal),
                        expectedDocumentId = context.documentId,
                        expectedWorkflowId = workflowId,
                        taskId = null,
                    )
                }
            },
        ).value
    }

    private fun decide(
        workflowId: Identifier,
        taskId: Identifier,
        comment: String?,
        deliveryProfileId: String?,
        approved: Boolean,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt {
        requireBoundaryIdentifier(workflowId)
        requireBoundaryIdentifier(taskId)
        val decision = reviews.prepareReviewDecision(workflowId, taskId, approved, guard)
        val context = decision.lifecycle
        val action = if (approved) APPROVE_ACTION else REJECT_ACTION
        val request = RequestIdempotency.create(
            tenantId = context.tenantId,
            operatorId = context.operator.id,
            idempotencyKey = idempotencyKey,
            action = action,
            resourceType = WORKFLOW_RESOURCE_TYPE,
            resourceId = workflowId,
            subresourceId = taskId,
            requestFingerprint = RequestFingerprint.sha256(
                if (approved) APPROVE_FINGERPRINT_VERSION else REJECT_FINGERPRINT_VERSION,
                taskId.value,
                comment,
                deliveryProfileId,
            ),
        )
        idempotency.findCompleted(request)?.let { result ->
            return replay(context.documentId, workflowId, result, taskId)
        }
        var delivery = reviews.prepareInitialReviewDelivery(decision, deliveryProfileId)
        while (true) {
            val validated = reviews.revalidateReviewDecision(decision)
            try {
                return idempotency.execute(
                    request,
                    IdempotencyReplayMapper { result -> replay(context.documentId, workflowId, result, taskId) },
                    IdempotentCommand {
                        DocumentLifecycleMutationTransaction.execute {
                            fresh(
                                result = reviews.decideSafelyInCurrentTransaction(
                                    validated,
                                    decision,
                                    comment,
                                    delivery,
                                ),
                                expectedDocumentId = context.documentId,
                                expectedWorkflowId = workflowId,
                                taskId = taskId,
                            )
                        }
                    },
                ).value
            } catch (_: DocumentReviewDeliveryPreparationRequiredException) {
                delivery = reviews.prepareCompletingReviewDelivery(decision, deliveryProfileId)
            }
        }
    }

    /**
     * Task exposed on a fresh submit receipt. Routes with several parallel
     * tasks still yield a single receipt identifier: the first pending task.
     * An abnormal workflow without any pending task degrades to a null task
     * id instead of failing the submission.
     */
    private fun firstPendingTaskId(workflow: WorkflowInstance): Identifier? =
        workflow.tasks.firstOrNull { task -> task.state == WorkflowTaskState.PENDING }?.id

    private fun fresh(
        result: DocumentReviewMutationResult,
        expectedDocumentId: Identifier,
        expectedWorkflowId: Identifier?,
        taskId: Identifier?,
    ): IdempotentCommandResult<DocumentLifecycleReceipt> {
        val document = result.document
        val workflow = result.workflow
        if (
            document.tenantId != workflow.tenantId ||
            document.id != workflow.documentId ||
            document.id != expectedDocumentId ||
            (expectedWorkflowId != null && workflow.id != expectedWorkflowId) ||
            (taskId != null && workflow.tasks.none { task -> task.id == taskId })
        ) {
            throw IdempotencyStoreException("Review mutation returned an inconsistent receipt.")
        }
        return IdempotentCommandResult(
            DocumentLifecycleReceipt(document.id, workflow.id, taskId),
            IdempotencyResult(
                DOCUMENT_RESOURCE_TYPE,
                document.id,
                WORKFLOW_RESOURCE_TYPE,
                workflow.id,
            ),
        )
    }

    private fun replay(
        documentId: Identifier,
        workflowId: Identifier?,
        result: IdempotencyResult,
        taskId: Identifier? = null,
    ): DocumentLifecycleReceipt {
        if (
            result.resourceType != DOCUMENT_RESOURCE_TYPE ||
            result.resourceId != documentId ||
            result.relatedResourceType != WORKFLOW_RESOURCE_TYPE ||
            result.relatedResourceId == null ||
            (workflowId != null && result.relatedResourceId != workflowId)
        ) {
            throw IdempotencyStoreException("Stored review receipt does not match the requested operation.")
        }
        return DocumentLifecycleReceipt(documentId, result.relatedResourceId, taskId)
    }

    private fun requireBoundaryIdentifier(identifier: Identifier) {
        val value = identifier.value
        require(value.length <= MAX_IDENTIFIER_LENGTH) { "Identifier is too long." }
        require(value.first().isNotWhitespace() && value.last().isNotWhitespace()) {
            "Identifier has invalid boundary whitespace."
        }
        require(value.none(::isUnsafeCharacter)) { "Identifier contains unsafe characters." }
        // Reuse the canonical digest encoder to reject malformed surrogate pairs.
        RequestFingerprint.sha256(IDENTIFIER_VALIDATION_VERSION, value)
    }

    private fun optionalText(
        value: String?,
        label: String,
        maximumLength: Int,
        trim: Boolean,
    ): String? {
        if (value == null) return null
        val normalized = if (trim) value.trim() else value
        require(normalized.isNotBlank()) { "$label must not be blank when provided." }
        require(normalized.length <= maximumLength) { "$label is too long." }
        require(normalized.none(::isUnsafeCharacter)) { "$label contains unsafe characters." }
        RequestFingerprint.sha256(TEXT_VALIDATION_VERSION, normalized)
        return normalized
    }

    private fun Char.isNotWhitespace(): Boolean = !isWhitespace() && !Character.isSpaceChar(this)

    private fun isUnsafeCharacter(character: Char): Boolean =
        Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val WORKFLOW_RESOURCE_TYPE = "WORKFLOW"
        const val SUBMIT_ACTION = "document:review:submit"
        const val APPROVE_ACTION = "document:review:approve"
        const val REJECT_ACTION = "document:review:reject"
        const val WITHDRAW_ACTION = "document:review:withdraw"
        const val MAX_IDENTIFIER_LENGTH = 256
        const val MAX_SELECTOR_LENGTH = 256
        const val MAX_COMMENT_LENGTH = 1_000
        const val SUBMIT_FINGERPRINT_VERSION = "fileweft:workflow:submit:v1"
        const val APPROVE_FINGERPRINT_VERSION = "fileweft:workflow:approve:v1"
        const val REJECT_FINGERPRINT_VERSION = "fileweft:workflow:reject:v1"
        const val WITHDRAW_FINGERPRINT_VERSION = "fileweft:workflow:withdraw:v1"
        const val IDENTIFIER_VALIDATION_VERSION = "fileweft:workflow:identifier:v1"
        const val TEXT_VALIDATION_VERSION = "fileweft:workflow:text:v1"
    }
}
