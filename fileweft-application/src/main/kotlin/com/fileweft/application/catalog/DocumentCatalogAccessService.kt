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

    fun listAccessibleFolders(): List<DocumentCatalogFolder> = access(
        DocumentCatalogOperation.BROWSE,
        CATALOG_RESOURCE_ID,
        CATALOG_RESOURCE_TYPE,
        DOCUMENT_READ_ACTION,
    ) { request ->
        catalog.listFolders(request)
    }

    fun requireFolderForDocumentCreation(folderId: String): DocumentCatalogFolder {
        return requireFolder(
            folderId,
            CATALOG_RESOURCE_ID,
            CATALOG_RESOURCE_TYPE,
            DOCUMENT_CREATE_ACTION,
        )
    }

    fun requireFolderForDocumentUpdate(documentId: Identifier, folderId: String): DocumentCatalogFolder =
        requireFolder(folderId, documentId, DOCUMENT_RESOURCE_TYPE, DOCUMENT_EDIT_ACTION)

    private fun requireFolder(
        folderId: String,
        resourceId: Identifier,
        resourceType: String,
        actionName: String,
    ): DocumentCatalogFolder {
        val normalizedFolderId = folderId.trim()
        require(normalizedFolderId.isNotEmpty()) { "Document catalog folder id must not be blank." }
        return access(DocumentCatalogOperation.BIND_DOCUMENT, resourceId, resourceType, actionName) { request ->
            catalog.findFolder(request, normalizedFolderId)
                ?: throw IllegalArgumentException("Folder '$normalizedFolderId' is not available to the current user in this tenant catalog.")
        }
    }

    private fun <T> access(
        operation: DocumentCatalogOperation,
        resourceId: Identifier,
        resourceType: String,
        actionName: String,
        action: (DocumentCatalogAccessRequest) -> T,
    ): T {
        val tenant = tenantProvider.currentTenant()
        val user = userRealmProvider.currentUser()
            ?: throw SecurityException("A current user is required to access the document catalog.")
        authorization.requireAction(
            tenant.tenantId,
            resourceId,
            resourceType,
            actionName,
        )
        return action(DocumentCatalogAccessRequest(tenant.tenantId, user.id, operation))
    }

    private companion object {
        val CATALOG_RESOURCE_ID = Identifier("document-catalog")
        const val CATALOG_RESOURCE_TYPE = "DOCUMENT_CATALOG"
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val DOCUMENT_READ_ACTION = "document:read"
        const val DOCUMENT_CREATE_ACTION = "document:create"
        const val DOCUMENT_EDIT_ACTION = "document:edit"
    }
}
