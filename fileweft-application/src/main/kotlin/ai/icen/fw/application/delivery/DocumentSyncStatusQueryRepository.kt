package ai.icen.fw.application.delivery

import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.core.id.Identifier

/**
 * Tenant-filtered persistence port for the public-safe synchronization view.
 *
 * Implementations must return targets only from the document's current
 * publication generation. A retryable flag may be true only when the matching
 * target state is FAILED and the target's current fenced event, in the same
 * tenant and operation, is durably FAILED. Null means that the document is
 * absent, cross-tenant, or outside [folderReadScope].
 *
 * A persisted internal diagnostic may reach the view only as the fixed
 * [DocumentDeliveryErrorCategory] produced by [DocumentDeliveryErrorCategory.classify];
 * the raw error text must never be exposed through this port.
 */
interface DocumentSyncStatusQueryRepository {
    fun findByDocument(
        tenantId: Identifier,
        documentId: Identifier,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentSyncStatusView?
}
