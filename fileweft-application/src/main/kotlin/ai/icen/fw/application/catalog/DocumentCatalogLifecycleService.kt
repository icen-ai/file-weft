package ai.icen.fw.application.catalog

import ai.icen.fw.application.archive.ArchiveDocumentService
import ai.icen.fw.application.document.DocumentCommandService
import ai.icen.fw.application.document.DocumentMutationComponents
import ai.icen.fw.application.offline.OfflineDocumentService
import ai.icen.fw.application.offline.RestoreOfflineDocumentService
import ai.icen.fw.application.publish.PublishDocumentService
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.lifecycle.IdempotentDocumentLifecycleDelegate
import ai.icen.fw.application.workflow.DocumentReviewWorkflowService
import ai.icen.fw.application.workflow.IdempotentDocumentReviewWorkflowDelegate
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.workflow.WorkflowInstance

/**
 * Catalog-aware boundary for every document lifecycle and review mutation.
 *
 * The wrapped application services retain their existing tenant-wide behavior
 * for hosts without a catalog. Hosts that install a catalog should expose this
 * service instead: the wrapped use case first enforces its base permission,
 * then snapshots the document-to-asset binding, repeats the action-specific
 * permission together with the source-folder BROWSE ACL outside a database
 * transaction, revalidates that decision, and finally compares the raw binding
 * under the shared document -> asset mutation lock order.
 *
 * Invoke this service as a top-level application boundary. A host transaction
 * must not wrap it because the catalog provider may be remote.
 */
class DocumentCatalogLifecycleService(
    private val commands: DocumentCommandService,
    private val reviews: DocumentReviewWorkflowService,
    private val publishService: PublishDocumentService,
    private val offlineService: OfflineDocumentService,
    private val restoreService: RestoreOfflineDocumentService,
    private val archiveService: ArchiveDocumentService,
    catalogAccess: DocumentCatalogAccessService,
    documents: DocumentRepository,
    assets: FileAssetRepository,
    transaction: ApplicationTransaction,
) {
    private val guard: DocumentLifecycleMutationGuard = DocumentCatalogMutationGuard(
        catalogAccess,
        DocumentMutationComponents(documents, assets, transaction),
    )

    fun submit(documentId: Identifier): Document = commands.submit(documentId, guard)

    fun reject(documentId: Identifier): Document = commands.reject(documentId, guard)

    fun revise(documentId: Identifier): Document = commands.revise(documentId, guard)

    fun submitForReview(documentId: Identifier): WorkflowInstance =
        reviews.submit(documentId, null, null, guard)

    fun submitForReview(documentId: Identifier, reviewerId: Identifier?): WorkflowInstance =
        reviews.submit(documentId, reviewerId, null, guard)

    fun submitForReview(
        documentId: Identifier,
        reviewerId: Identifier?,
        reviewRouteId: String?,
    ): WorkflowInstance = reviews.submit(documentId, reviewerId, reviewRouteId, guard)

    fun approve(workflowId: Identifier, taskId: Identifier): Document =
        reviews.approve(workflowId, taskId, null, null, guard)

    fun approve(workflowId: Identifier, taskId: Identifier, comment: String?): Document =
        reviews.approve(workflowId, taskId, comment, null, guard)

    fun approve(
        workflowId: Identifier,
        taskId: Identifier,
        comment: String?,
        deliveryProfileId: String?,
    ): Document = reviews.approve(workflowId, taskId, comment, deliveryProfileId, guard)

    fun rejectReview(workflowId: Identifier, taskId: Identifier): Document =
        reviews.reject(workflowId, taskId, null, guard)

    fun rejectReview(workflowId: Identifier, taskId: Identifier, comment: String?): Document =
        reviews.reject(workflowId, taskId, comment, guard)

    fun withdrawReview(workflowId: Identifier): Document = reviews.withdraw(workflowId, guard)

    fun publish(documentId: Identifier): Document = publishService.publish(documentId, null, guard)

    fun publish(documentId: Identifier, deliveryProfileId: String?): Document =
        publishService.publish(documentId, deliveryProfileId, guard)

    fun offline(documentId: Identifier): Document = offlineService.offline(documentId, guard)

    fun restore(documentId: Identifier): Document = restoreService.restore(documentId, guard)

    fun archive(documentId: Identifier): Document = archiveService.archive(documentId, guard)

    @JvmSynthetic
    internal fun createIdempotentDelegate(
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentLifecycleDelegate = IdempotentDocumentLifecycleDelegate(
        commands,
        publishService,
        offlineService,
        restoreService,
        archiveService,
        idempotency,
        guard,
    )

    @JvmSynthetic
    internal fun createIdempotentReviewDelegate(
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentReviewWorkflowDelegate = IdempotentDocumentReviewWorkflowDelegate(
        reviews,
        idempotency,
        guard,
    )
}
