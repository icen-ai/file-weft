package ai.icen.fw.sample.host

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.catalog.DocumentCatalogAccessRequest
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider

/**
 * Sample host catalog provider that returns a small static folder tree for any
 * tenant. The same tree is returned for the user-aware variant because the
 * sample host does not enforce per-user folder ACLs.
 */
class SampleDocumentCatalogProvider : DocumentCatalogProvider {

    override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = folders

    override fun listFolders(request: DocumentCatalogAccessRequest): List<DocumentCatalogFolder> = folders

    private companion object {
        val folders = listOf(
            DocumentCatalogFolder(id = "inbox", parentFolderId = null, displayName = "Inbox"),
            DocumentCatalogFolder(id = "archived", parentFolderId = null, displayName = "Archived"),
        )
    }
}
