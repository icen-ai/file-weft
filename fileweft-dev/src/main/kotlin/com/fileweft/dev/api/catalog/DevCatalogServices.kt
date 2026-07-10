package com.fileweft.dev.api.catalog

import com.fileweft.application.document.CreateDocumentDraftCommand
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.core.id.Identifier
import com.fileweft.dev.api.service.DevAccessService
import com.fileweft.domain.document.Document
import com.fileweft.spi.catalog.DocumentCatalogBinding
import com.fileweft.spi.catalog.DocumentCatalogProvider
import com.fileweft.spi.tenant.TenantProvider
import java.io.InputStream

data class DevCatalogFolderView(
    val id: String,
    val parentFolderId: String?,
    val displayName: String,
)

/** Applies read authorization before exposing the host system's folder topology. */
class DevCatalogQueryService(
    private val catalog: DocumentCatalogProvider,
    private val access: DevAccessService,
    private val tenants: TenantProvider,
) {
    fun folders(): List<DevCatalogFolderView> {
        access.requireAction(Identifier("document-catalog"), "DOCUMENT_CATALOG", "document:read")
        return catalog.listFolders(tenants.currentTenant().tenantId).map { folder ->
            DevCatalogFolderView(folder.id, folder.parentFolderId, folder.displayName)
        }
    }
}

/**
 * Binds an uploaded FileWeft document to an externally owned folder reference.
 * Only the opaque folder ID is stored on the asset; folder contents remain in
 * the [DocumentCatalogProvider] implementation.
 */
class DevCatalogDocumentService(
    private val drafts: DocumentDraftService,
    private val catalog: DocumentCatalogProvider,
    private val tenants: TenantProvider,
) {
    fun create(command: CreateDocumentDraftCommand, folderId: String?, content: InputStream): Document {
        val tenantId = tenants.currentTenant().tenantId
        val resolvedFolderId = folderId?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_FOLDER_ID
        require(catalog.findFolder(tenantId, resolvedFolderId) != null) {
            "Folder '$resolvedFolderId' does not exist in the current tenant catalog."
        }
        return drafts.create(
            command.copy(metadata = command.metadata + (DocumentCatalogBinding.METADATA_KEY to resolvedFolderId)),
            content,
        )
    }

    private companion object {
        const val DEFAULT_FOLDER_ID = "inbox"
    }
}
