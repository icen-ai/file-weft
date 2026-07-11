package com.fileweft.application.catalog

import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.catalog.DocumentCatalogAccessRequest
import com.fileweft.spi.catalog.DocumentCatalogFolder
import com.fileweft.spi.catalog.DocumentCatalogOperation
import com.fileweft.spi.catalog.DocumentCatalogProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider

/**
 * Applies FileWeft's trusted tenant and user context before delegating folder
 * visibility and folder-specific ACL decisions to the host-owned catalog.
 */
class DocumentCatalogAccessService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val catalog: DocumentCatalogProvider,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun listAccessibleFolders(): List<DocumentCatalogFolder> = access(DocumentCatalogOperation.BROWSE) { request ->
        catalog.listFolders(request)
    }

    fun requireFolderForDocumentCreation(folderId: String): DocumentCatalogFolder {
        val normalizedFolderId = folderId.trim()
        require(normalizedFolderId.isNotEmpty()) { "Document catalog folder id must not be blank." }
        return access(DocumentCatalogOperation.BIND_DOCUMENT) { request ->
            catalog.findFolder(request, normalizedFolderId)
                ?: throw IllegalArgumentException("Folder '$normalizedFolderId' is not available to the current user in this tenant catalog.")
        }
    }

    private fun <T> access(
        operation: DocumentCatalogOperation,
        action: (DocumentCatalogAccessRequest) -> T,
    ): T {
        val tenant = tenantProvider.currentTenant()
        val user = userRealmProvider.currentUser()
            ?: throw SecurityException("A current user is required to access the document catalog.")
        authorization.requireAction(
            tenant.tenantId,
            CATALOG_RESOURCE_ID,
            CATALOG_RESOURCE_TYPE,
            if (operation == DocumentCatalogOperation.BROWSE) DOCUMENT_READ_ACTION else DOCUMENT_CREATE_ACTION,
        )
        return action(DocumentCatalogAccessRequest(tenant.tenantId, user.id, operation))
    }

    private companion object {
        val CATALOG_RESOURCE_ID = Identifier("document-catalog")
        const val CATALOG_RESOURCE_TYPE = "DOCUMENT_CATALOG"
        const val DOCUMENT_READ_ACTION = "document:read"
        const val DOCUMENT_CREATE_ACTION = "document:create"
    }
}
