package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.lifecycle.DocumentLifecycleReceipt
import ai.icen.fw.application.lifecycle.IdempotentDocumentCatalogLifecycleService
import ai.icen.fw.application.lifecycle.IdempotentDocumentLifecycleService
import ai.icen.fw.application.workflow.IdempotentDocumentCatalogReviewWorkflowService
import ai.icen.fw.application.workflow.IdempotentDocumentReviewWorkflowService
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.web.api.v1.document.DocumentLifecycleCommandResultDto
import ai.icen.fw.web.api.v1.document.PublishDocumentCommand
import ai.icen.fw.web.api.v1.workflow.ApproveWorkflowTaskCommand
import ai.icen.fw.web.api.v1.workflow.RejectWorkflowTaskCommand
import ai.icen.fw.web.api.v1.workflow.SubmitDocumentReviewCommand
import ai.icen.fw.web.runtime.v1.IdempotencyKeyParser
import ai.icen.fw.web.runtime.v1.V1FeatureUnavailableException

/**
 * Transport-neutral formal-v1 lifecycle boundary.
 *
 * Candidate lists are resolved here instead of through Spring's `@Primary`
 * semantics. A catalog host can never fall back to an unscoped flat service;
 * missing or mixed capabilities remain callable only as a safe 503.
 */
class DocumentLifecycleApiFacade private constructor(
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

    companion object {
        /** Kotlin-test seam; synthetic so it cannot become a Java host integration path. */
        @JvmSynthetic
        internal fun forTesting(
            revise: ((Identifier, String) -> DocumentLifecycleReceipt)?,
            publish: ((Identifier, String?, String) -> DocumentLifecycleReceipt)?,
            offline: ((Identifier, String) -> DocumentLifecycleReceipt)?,
            restore: ((Identifier, String) -> DocumentLifecycleReceipt)?,
            archive: ((Identifier, String) -> DocumentLifecycleReceipt)?,
            submitForReview: ((Identifier, String?, String) -> DocumentLifecycleReceipt)?,
            approve: ((Identifier, Identifier, String?, String?, String) -> DocumentLifecycleReceipt)?,
            reject: ((Identifier, Identifier, String?, String) -> DocumentLifecycleReceipt)?,
            withdrawReview: ((Identifier, String) -> DocumentLifecycleReceipt)? = null,
        ): DocumentLifecycleApiFacade {
            val lifecycleFunctions = listOf(revise, publish, offline, restore, archive)
            val reviewFunctions = listOf(submitForReview, approve, reject)
            require(lifecycleFunctions.all { it == null } || lifecycleFunctions.all { it != null }) {
                "Lifecycle test commands must be supplied as one complete capability."
            }
            require(reviewFunctions.all { it == null } || reviewFunctions.all { it != null }) {
                "Review test commands must be supplied as one complete capability."
            }
            return DocumentLifecycleApiFacade(
                lifecycles = revise?.let {
                    LambdaLifecycleCommands(
                        it,
                        requireNotNull(publish),
                        requireNotNull(offline),
                        requireNotNull(restore),
                        requireNotNull(archive),
                    )
                },
                reviews = submitForReview?.let {
                    LambdaReviewCommands(it, requireNotNull(approve), requireNotNull(reject), withdrawReview)
                },
            )
        }

        private fun resolveLifecycles(
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

        private fun resolveReviews(
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

        private fun requireCatalogCount(catalogAccessCount: Int) {
            require(catalogAccessCount >= 0) { "Catalog access candidate count must not be negative." }
            require(catalogAccessCount <= 1) { "Formal lifecycle API requires at most one catalog access boundary." }
        }

        private fun requireSingleCandidate(values: List<*>, label: String) {
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

    fun withdrawReview(
        workflowId: String,
        idempotencyKey: String,
    ): DocumentLifecycleCommandResultDto = reviews().withdrawReview(
        DocumentApiInputs.workflowId(workflowId),
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

    private interface LifecycleCommands {
        fun revise(documentId: Identifier, key: String): DocumentLifecycleReceipt
        fun publish(documentId: Identifier, profileId: String?, key: String): DocumentLifecycleReceipt
        fun offline(documentId: Identifier, key: String): DocumentLifecycleReceipt
        fun restore(documentId: Identifier, key: String): DocumentLifecycleReceipt
        fun archive(documentId: Identifier, key: String): DocumentLifecycleReceipt
    }

    private interface ReviewCommands {
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
        fun withdrawReview(workflowId: Identifier, key: String): DocumentLifecycleReceipt
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
        override fun withdrawReview(workflowId: Identifier, key: String) =
            service.withdrawReview(workflowId, key)
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
        override fun withdrawReview(workflowId: Identifier, key: String) =
            service.withdrawReview(workflowId, key)
    }

    private class LambdaLifecycleCommands(
        private val reviseCommand: (Identifier, String) -> DocumentLifecycleReceipt,
        private val publishCommand: (Identifier, String?, String) -> DocumentLifecycleReceipt,
        private val offlineCommand: (Identifier, String) -> DocumentLifecycleReceipt,
        private val restoreCommand: (Identifier, String) -> DocumentLifecycleReceipt,
        private val archiveCommand: (Identifier, String) -> DocumentLifecycleReceipt,
    ) : LifecycleCommands {
        override fun revise(documentId: Identifier, key: String) = reviseCommand(documentId, key)
        override fun publish(documentId: Identifier, profileId: String?, key: String) =
            publishCommand(documentId, profileId, key)
        override fun offline(documentId: Identifier, key: String) = offlineCommand(documentId, key)
        override fun restore(documentId: Identifier, key: String) = restoreCommand(documentId, key)
        override fun archive(documentId: Identifier, key: String) = archiveCommand(documentId, key)
    }

    private class LambdaReviewCommands(
        private val submitCommand: (Identifier, String?, String) -> DocumentLifecycleReceipt,
        private val approveCommand: (Identifier, Identifier, String?, String?, String) -> DocumentLifecycleReceipt,
        private val rejectCommand: (Identifier, Identifier, String?, String) -> DocumentLifecycleReceipt,
        private val withdrawCommand: ((Identifier, String) -> DocumentLifecycleReceipt)?,
    ) : ReviewCommands {
        override fun submitForReview(documentId: Identifier, routeId: String?, key: String) =
            submitCommand(documentId, routeId, key)
        override fun approve(
            workflowId: Identifier,
            taskId: Identifier,
            comment: String?,
            profileId: String?,
            key: String,
        ) = approveCommand(workflowId, taskId, comment, profileId, key)
        override fun reject(workflowId: Identifier, taskId: Identifier, comment: String?, key: String) =
            rejectCommand(workflowId, taskId, comment, key)
        override fun withdrawReview(workflowId: Identifier, key: String): DocumentLifecycleReceipt =
            withdrawCommand?.invoke(workflowId, key) ?: throw V1FeatureUnavailableException()
    }
}
