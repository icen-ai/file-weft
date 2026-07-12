package com.fileweft.application.catalog

import com.fileweft.application.archive.ArchiveDocumentService
import com.fileweft.application.document.DocumentCommandService
import com.fileweft.application.document.DocumentMutationComponents
import com.fileweft.application.offline.OfflineDocumentService
import com.fileweft.application.offline.RestoreOfflineDocumentService
import com.fileweft.application.publish.PublishDocumentService
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.application.idempotency.RequestIdempotencyService
import com.fileweft.application.lifecycle.IdempotentDocumentLifecycleDelegate
import com.fileweft.application.workflow.DocumentReviewWorkflowService
import com.fileweft.application.workflow.IdempotentDocumentReviewWorkflowDelegate
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.domain.workflow.WorkflowInstance

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
