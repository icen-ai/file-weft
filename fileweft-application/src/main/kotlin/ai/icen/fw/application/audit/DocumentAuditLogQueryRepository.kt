package ai.icen.fw.application.audit

import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.core.id.Identifier

/**
 * Tenant-filtered persistence port for the public-safe document audit view.
 *
 * Null means the document is absent, cross-tenant, or outside the supplied
 * folder scope. Implementations must never select raw audit or operation
 * details into the returned projection.
 */
interface DocumentAuditLogQueryRepository {
    fun findPage(
        tenantId: Identifier,
        documentId: Identifier,
        request: DocumentAuditLogPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentAuditLogPageResult?
}
