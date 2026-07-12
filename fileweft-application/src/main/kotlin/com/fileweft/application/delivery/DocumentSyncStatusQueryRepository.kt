package com.fileweft.application.delivery

import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.core.id.Identifier

/**
 * Tenant-filtered persistence port for the public-safe synchronization view.
 *
 * Implementations must return targets only from the document's current
 * publication generation. A retryable flag may be true only when the matching
 * target state is FAILED and the target's current fenced event, in the same
 * tenant and operation, is durably FAILED. Null means that the document is
 * absent, cross-tenant, or outside [folderReadScope].
 */
interface DocumentSyncStatusQueryRepository {
    fun findByDocument(
        tenantId: Identifier,
        documentId: Identifier,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentSyncStatusView?
}
