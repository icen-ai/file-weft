package com.fileweft.web.runtime.v1.document

import com.fileweft.application.lifecycle.DocumentLifecycleReceipt
import com.fileweft.application.lifecycle.IdempotentDocumentCatalogLifecycleService
import com.fileweft.application.lifecycle.IdempotentDocumentLifecycleService
import com.fileweft.application.workflow.IdempotentDocumentCatalogReviewWorkflowService
import com.fileweft.application.workflow.IdempotentDocumentReviewWorkflowService
import com.fileweft.core.id.Identifier
import com.fileweft.web.api.v1.document.DocumentLifecycleCommandResultDto
import com.fileweft.web.api.v1.document.PublishDocumentCommand
import com.fileweft.web.api.v1.workflow.ApproveWorkflowTaskCommand
import com.fileweft.web.api.v1.workflow.RejectWorkflowTaskCommand
import com.fileweft.web.api.v1.workflow.SubmitDocumentReviewCommand
import com.fileweft.web.runtime.v1.IdempotencyKeyParser
import com.fileweft.web.runtime.v1.V1FeatureUnavailableException

/**
 * Transport-neutral formal-v1 lifecycle boundary.
 *
 * Candidate lists are resolved here instead of through Spring's `@Primary`
 * semantics. A catalog host can never fall back to an unscoped flat service;
 * missing or mixed capabilities remain callable only as a safe 503.
 */
class DocumentLifecycleApiFacade internal constructor(
    private val lifecycles: LifecycleCommands?,
    private val reviews: ReviewCommands?,
) {
    constructor(
        catalogAccessCount: Int,
        flatLifecycles: List<IdempotentDocumentLifecycleService>,
        catalogLifecycles: List<IdempotentDocumentCatalogLifecycleService>,
        flatReviews: List<IdempotentDocumentReviewWorkflowService>,
        catalogReviews: List<IdempotentDocumentCatalogReviewWorkflowService>,
    ) : this(
        lifecycles = resolveLifecycles(catalogAccessCount, flatLifecycles, catalogLifecycles),
        reviews = resolveReviews(catalogAccessCount, flatReviews, catalogReviews),
    )

    private companion object {
        fun resolveLifecycles(
            catalogAccessCount: Int,
            flatLifecycles: List<IdempotentDocumentLifecycleService>,
            catalogLifecycles: List<IdempotentDocumentCatalogLifecycleService>,
        ): LifecycleCommands? {
            requireCatalogCount(catalogAccessCount)
            requireSingleCandidate(flatLifecycles, "flat lifecycle")
            requireSingleCandidate(catalogLifecycles, "catalog lifecycle")
            return when {
            catalogAccessCount == 0 && flatLifecycles.size == 1 && catalogLifecycles.isEmpty() ->
                FlatLifecycleCommands(flatLifecycles.single())
            catalogAccessCount == 1 && flatLifecycles.isEmpty() && catalogLifecycles.size == 1 ->
                CatalogLifecycleCommands(catalogLifecycles.single())
            else -> null
        }
        }

        fun resolveReviews(
            catalogAccessCount: Int,
            flatReviews: List<IdempotentDocumentReviewWorkflowService>,
            catalogReviews: List<IdempotentDocumentCatalogReviewWorkflowService>,
        ): ReviewCommands? {
            requireCatalogCount(catalogAccessCount)
            requireSingleCandidate(flatReviews, "flat review")
            requireSingleCandidate(catalogReviews, "catalog review")
            return when {
            catalogAccessCount == 0 && flatReviews.size == 1 && catalogReviews.isEmpty() ->
                FlatReviewCommands(flatReviews.single())
            catalogAccessCount == 1 && flatReviews.isEmpty() && catalogReviews.size == 1 ->
                CatalogReviewCommands(catalogReviews.single())
            else -> null
        }
        }

        fun requireCatalogCount(catalogAccessCount: Int) {
            require(catalogAccessCount >= 0) { "Catalog access candidate count must not be negative." }
            require(catalogAccessCount <= 1) { "Formal lifecycle API requires at most one catalog access boundary." }
        }

        fun requireSingleCandidate(values: List<*>, label: String) {
            require(values.size <= 1) { "Formal lifecycle API has multiple $label candidates." }
        }
    }

    fun revise(documentId: String, idempotencyKey: String): DocumentLifecycleCommandResultDto =
        lifecycle().revise(documentId(documentId), key(idempotencyKey)).toDto()

    fun publish(
        documentId: String,
        command: PublishDocumentCommand,
        idempotencyKey: String,
    ): DocumentLifecycleCommandResultDto = lifecycle().publish(
        documentId(documentId),
        command.deliveryProfileId,
        key(idempotencyKey),
    ).toDto()

    fun offline(documentId: String, idempotencyKey: String): DocumentLifecycleCommandResultDto =
        lifecycle().offline(documentId(documentId), key(idempotencyKey)).toDto()

    fun restore(documentId: String, idempotencyKey: String): DocumentLifecycleCommandResultDto =
        lifecycle().restore(documentId(documentId), key(idempotencyKey)).toDto()

    fun archive(documentId: String, idempotencyKey: String): DocumentLifecycleCommandResultDto =
        lifecycle().archive(documentId(documentId), key(idempotencyKey)).toDto()

    fun submitForReview(
        documentId: String,
        command: SubmitDocumentReviewCommand,
        idempotencyKey: String,
    ): DocumentLifecycleCommandResultDto = reviews().submitForReview(
        documentId(documentId),
        command.reviewRouteId,
        key(idempotencyKey),
    ).toDto()

    fun approve(
        workflowId: String,
        taskId: String,
        command: ApproveWorkflowTaskCommand,
        idempotencyKey: String,
    ): DocumentLifecycleCommandResultDto = reviews().approve(
        DocumentApiInputs.workflowId(workflowId),
        DocumentApiInputs.taskId(taskId),
        command.comment,
        command.deliveryProfileId,
        key(idempotencyKey),
    ).toDto()

    fun reject(
        workflowId: String,
        taskId: String,
        command: RejectWorkflowTaskCommand,
        idempotencyKey: String,
    ): DocumentLifecycleCommandResultDto = reviews().reject(
        DocumentApiInputs.workflowId(workflowId),
        DocumentApiInputs.taskId(taskId),
        command.comment,
        key(idempotencyKey),
    ).toDto()

    private fun documentId(value: String): Identifier = DocumentApiInputs.documentId(value)

    private fun key(value: String): String = IdempotencyKeyParser.parse(listOf(value))

    private fun lifecycle(): LifecycleCommands = lifecycles ?: throw V1FeatureUnavailableException()

    private fun reviews(): ReviewCommands = reviews ?: throw V1FeatureUnavailableException()

    private fun DocumentLifecycleReceipt.toDto(): DocumentLifecycleCommandResultDto =
        DocumentLifecycleCommandResultDto(
            documentId = documentId.value,
            workflowId = workflowId?.value,
            taskId = taskId?.value,
        )

    internal interface LifecycleCommands {
        fun revise(documentId: Identifier, key: String): DocumentLifecycleReceipt
        fun publish(documentId: Identifier, profileId: String?, key: String): DocumentLifecycleReceipt
        fun offline(documentId: Identifier, key: String): DocumentLifecycleReceipt
        fun restore(documentId: Identifier, key: String): DocumentLifecycleReceipt
        fun archive(documentId: Identifier, key: String): DocumentLifecycleReceipt
    }

    internal interface ReviewCommands {
        fun submitForReview(documentId: Identifier, routeId: String?, key: String): DocumentLifecycleReceipt
        fun approve(
            workflowId: Identifier,
            taskId: Identifier,
            comment: String?,
            profileId: String?,
            key: String,
        ): DocumentLifecycleReceipt
        fun reject(
            workflowId: Identifier,
            taskId: Identifier,
            comment: String?,
            key: String,
        ): DocumentLifecycleReceipt
    }

    private class FlatLifecycleCommands(
        private val service: IdempotentDocumentLifecycleService,
    ) : LifecycleCommands {
        override fun revise(documentId: Identifier, key: String) = service.revise(documentId, key)
        override fun publish(documentId: Identifier, profileId: String?, key: String) =
            service.publish(documentId, profileId, key)
        override fun offline(documentId: Identifier, key: String) = service.offline(documentId, key)
        override fun restore(documentId: Identifier, key: String) = service.restore(documentId, key)
        override fun archive(documentId: Identifier, key: String) = service.archive(documentId, key)
    }

    private class CatalogLifecycleCommands(
        private val service: IdempotentDocumentCatalogLifecycleService,
    ) : LifecycleCommands {
        override fun revise(documentId: Identifier, key: String) = service.revise(documentId, key)
        override fun publish(documentId: Identifier, profileId: String?, key: String) =
            service.publish(documentId, profileId, key)
        override fun offline(documentId: Identifier, key: String) = service.offline(documentId, key)
        override fun restore(documentId: Identifier, key: String) = service.restore(documentId, key)
        override fun archive(documentId: Identifier, key: String) = service.archive(documentId, key)
    }

    private class FlatReviewCommands(
        private val service: IdempotentDocumentReviewWorkflowService,
    ) : ReviewCommands {
        override fun submitForReview(documentId: Identifier, routeId: String?, key: String) =
            service.submitForReview(documentId, routeId, key)
        override fun approve(
            workflowId: Identifier,
            taskId: Identifier,
            comment: String?,
            profileId: String?,
            key: String,
        ) = service.approve(workflowId, taskId, comment, profileId, key)
        override fun reject(workflowId: Identifier, taskId: Identifier, comment: String?, key: String) =
            service.reject(workflowId, taskId, comment, key)
    }

    private class CatalogReviewCommands(
        private val service: IdempotentDocumentCatalogReviewWorkflowService,
    ) : ReviewCommands {
        override fun submitForReview(documentId: Identifier, routeId: String?, key: String) =
            service.submitForReview(documentId, routeId, key)
        override fun approve(
            workflowId: Identifier,
            taskId: Identifier,
            comment: String?,
            profileId: String?,
            key: String,
        ) = service.approve(workflowId, taskId, comment, profileId, key)
        override fun reject(workflowId: Identifier, taskId: Identifier, comment: String?, key: String) =
            service.reject(workflowId, taskId, comment, key)
    }
}
