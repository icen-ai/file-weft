package com.fileweft.application.catalog

import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.security.ApplicationUnauthenticatedException
import com.fileweft.application.document.DocumentFolderDownloadAccess
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.catalog.DocumentCatalogAccessRequest
import com.fileweft.spi.catalog.DocumentCatalogFolder
import com.fileweft.spi.catalog.DocumentCatalogOperation
import com.fileweft.spi.catalog.DocumentCatalogProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import java.util.Collections
import java.util.LinkedHashSet

/**
 * Applies FileWeft's trusted tenant and user context before delegating folder
 * visibility and folder-specific ACL decisions to the host-owned catalog.
 */
class DocumentCatalogAccessService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val catalog: DocumentCatalogProvider,
) : DocumentFolderDownloadAccess {
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
        requireFolder(
            folderId,
            documentId,
            DOCUMENT_RESOURCE_TYPE,
            DOCUMENT_EDIT_ACTION,
            DocumentCatalogOperation.BIND_DOCUMENT,
        )

    /** Performs base document authorization without invoking the host catalog or a repository. */
    internal fun requireDocumentUpdateAuthorization(documentId: Identifier) {
        requireDocumentActionAuthorization(documentId, DOCUMENT_EDIT_ACTION)
    }

    /** Performs one action-specific base authorization without consulting the catalog. */
    internal fun requireDocumentActionAuthorization(documentId: Identifier, actionName: String) {
        requireValidDocumentAction(actionName)
        val tenant = tenantProvider.currentTenant()
        authorization.requireDocumentAction(tenant.tenantId, documentId, actionName)
    }

    /**
     * Authorizes a binding read from trusted persistence. An unavailable source
     * folder is hidden as a missing document so callers cannot enumerate a
     * document that sits outside their catalog view.
     */
    internal fun requireCurrentFolderForDocumentUpdate(
        documentId: Identifier,
        folderId: String,
    ): DocumentCatalogFolder = requireCurrentFolderForDocumentAction(
        documentId,
        folderId,
        DOCUMENT_EDIT_ACTION,
        DocumentCatalogOperation.BIND_DOCUMENT,
    )

    /**
     * Applies an action-specific document authorization and checks that the
     * persisted source folder remains browseable. Lifecycle operations never
     * request permission to rebind the document merely to inspect its source.
     */
    internal fun requireCurrentFolderForDocumentLifecycle(
        documentId: Identifier,
        folderId: String,
        actionName: String,
    ): DocumentCatalogFolder = requireCurrentFolderForDocumentAction(
        documentId,
        folderId,
        actionName,
        DocumentCatalogOperation.BROWSE,
    )

    private fun requireCurrentFolderForDocumentAction(
        documentId: Identifier,
        folderId: String,
        actionName: String,
        operation: DocumentCatalogOperation,
    ): DocumentCatalogFolder = try {
        requireValidDocumentAction(actionName)
        require(folderId == folderId.trim()) {
            "Persisted document catalog binding must be a canonical folder id."
        }
        requireFolder(
            folderId,
            documentId,
            DOCUMENT_RESOURCE_TYPE,
            actionName,
            operation,
        )
    } catch (_: DocumentCatalogFolderUnavailableException) {
        throw DocumentNotFoundException(documentId)
    } catch (failure: IllegalArgumentException) {
        throw IllegalStateException("Persisted document catalog binding is invalid.", failure)
    }

    private fun requireValidDocumentAction(actionName: String) {
        require(actionName.startsWith(DOCUMENT_ACTION_PREFIX) && actionName.length > DOCUMENT_ACTION_PREFIX.length) {
            "Document action must use the document namespace."
        }
        require(actionName.none { character -> Character.isISOControl(character) }) {
            "Document action must not contain control characters."
        }
    }

    override fun requireFolderForDocumentRead(folderId: String) {
        requireFolder(
            folderId,
            CATALOG_RESOURCE_ID,
            CATALOG_RESOURCE_TYPE,
            DOCUMENT_READ_ACTION,
            DocumentCatalogOperation.BROWSE,
        )
    }

    override fun readableFolderIds(): Set<String> = immutableFolderIds(listAccessibleFolders())

    override fun readableFolderIdsForDocumentDownload(documentId: Identifier): Set<String> = access(
        DocumentCatalogOperation.BROWSE,
        documentId,
        DOCUMENT_RESOURCE_TYPE,
        DOCUMENT_DOWNLOAD_ACTION,
    ) { request ->
        immutableFolderIds(catalog.listFolders(request))
    }

    private fun requireFolder(
        folderId: String,
        resourceId: Identifier,
        resourceType: String,
        actionName: String,
        operation: DocumentCatalogOperation = DocumentCatalogOperation.BIND_DOCUMENT,
    ): DocumentCatalogFolder {
        val normalizedFolderId = folderId.trim()
        require(normalizedFolderId.isNotEmpty()) { "Document catalog folder id must not be blank." }
        require(normalizedFolderId.length <= MAX_FOLDER_ID_LENGTH) {
            "Document catalog folder id must not exceed $MAX_FOLDER_ID_LENGTH characters."
        }
        require(normalizedFolderId.none { character -> Character.isISOControl(character) }) {
            "Document catalog folder id must not contain control characters."
        }
        return access(operation, resourceId, resourceType, actionName) { request ->
            val folder = catalog.findFolder(request, normalizedFolderId)
                ?: throw DocumentCatalogFolderUnavailableException()
            requireValidCanonicalFolder(folder)
            folder
        }
    }

    /** Provider output is an integration boundary, not caller input. */
    private fun requireValidCanonicalFolder(folder: DocumentCatalogFolder) {
        if (
            folder.id.isBlank() ||
            folder.id != folder.id.trim() ||
            folder.id.length > MAX_FOLDER_ID_LENGTH ||
            folder.id.any { character -> Character.isISOControl(character) }
        ) {
            throw IllegalStateException("Document catalog provider returned an invalid canonical folder id.")
        }
    }

    private fun immutableFolderIds(folders: List<DocumentCatalogFolder>): Set<String> {
        folders.forEach(::requireValidCanonicalFolder)
        return Collections.unmodifiableSet(LinkedHashSet(folders.map { folder -> folder.id }))
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
            ?: throw ApplicationUnauthenticatedException()
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
        const val DOCUMENT_DOWNLOAD_ACTION = "document:download"
        const val DOCUMENT_CREATE_ACTION = "document:create"
        const val DOCUMENT_EDIT_ACTION = "document:edit"
        const val DOCUMENT_ACTION_PREFIX = "document:"
        const val MAX_FOLDER_ID_LENGTH = 256
    }
}

/** Missing and user-invisible target folders retain the existing invalid-input contract. */
internal class DocumentCatalogFolderUnavailableException :
    IllegalArgumentException("Document catalog folder is unavailable.")
