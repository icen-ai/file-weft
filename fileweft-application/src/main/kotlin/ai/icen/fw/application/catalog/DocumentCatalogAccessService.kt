package ai.icen.fw.application.catalog

import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.security.ApplicationUnauthenticatedException
import ai.icen.fw.application.document.DocumentFolderDownloadAccess
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.catalog.DocumentCatalogAccessRequest
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogOperation
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Applies FlowWeft's trusted tenant and user context before delegating folder
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
        validatedVisibleTree(catalog.listFolders(request))
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

    /** Reuses one trusted identity snapshot captured by the outer command. */
    internal fun requireDocumentUpdateAuthorizationAs(
        tenantId: Identifier,
        documentId: Identifier,
        operator: UserIdentity,
    ): UserIdentity = authorization.requireDocumentActionAs(
        tenantId,
        documentId,
        DOCUMENT_EDIT_ACTION,
        operator,
    )

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

    internal fun requireCurrentFolderForDocumentUpdate(
        tenantId: Identifier,
        operator: UserIdentity,
        documentId: Identifier,
        folderId: String,
    ): DocumentCatalogFolder = requireCurrentFolderForDocumentActionAs(
        tenantId,
        operator,
        documentId,
        folderId,
        DOCUMENT_EDIT_ACTION,
        DocumentCatalogOperation.BIND_DOCUMENT,
    )

    internal fun requireFolderForDocumentUpdateAs(
        tenantId: Identifier,
        operator: UserIdentity,
        documentId: Identifier,
        folderId: String,
    ): DocumentCatalogFolder = requireFolderAs(
        tenantId,
        operator,
        folderId,
        documentId,
        DOCUMENT_RESOURCE_TYPE,
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

    /**
     * Lifecycle guards use the identity and tenant captured at the outer
     * application boundary. Re-reading ambient providers here could authorize
     * a different principal from the one persisted in audit/idempotency data.
     */
    internal fun requireCurrentFolderForDocumentLifecycle(
        tenantId: Identifier,
        operator: UserIdentity,
        documentId: Identifier,
        folderId: String,
        actionName: String,
    ): DocumentCatalogFolder = requireCurrentFolderForDocumentActionAs(
        tenantId,
        operator,
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

    private fun requireCurrentFolderForDocumentActionAs(
        tenantId: Identifier,
        operator: UserIdentity,
        documentId: Identifier,
        folderId: String,
        actionName: String,
        operation: DocumentCatalogOperation,
    ): DocumentCatalogFolder = try {
        requireValidDocumentAction(actionName)
        require(folderId == folderId.trim()) {
            "Persisted document catalog binding must be a canonical folder id."
        }
        requireFolderAs(
            tenantId,
            operator,
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
        val tenant = tenantProvider.currentTenant()
        val user = userRealmProvider.currentUser()
            ?: throw ApplicationUnauthenticatedException()
        return requireFolderAs(
            tenant.tenantId,
            user,
            folderId,
            resourceId,
            resourceType,
            actionName,
            operation,
        )
    }

    private fun requireFolderAs(
        tenantId: Identifier,
        operator: UserIdentity,
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
        return accessAs(tenantId, operator, operation, resourceId, resourceType, actionName) { request ->
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
            folder.id.any(::isUnsafeCatalogTextCharacter)
        ) {
            throw IllegalStateException("Document catalog provider returned an invalid canonical folder id.")
        }
        if (
            folder.parentFolderId?.let { parentId ->
                parentId.isBlank() ||
                    parentId != parentId.trim() ||
                    parentId.length > MAX_FOLDER_ID_LENGTH ||
                    parentId.any(::isUnsafeCatalogTextCharacter)
            } == true ||
            folder.displayName.isBlank() ||
            folder.displayName != folder.displayName.trim() ||
            folder.displayName.length > MAX_FOLDER_DISPLAY_NAME_LENGTH ||
            folder.displayName.any(::isUnsafeCatalogTextCharacter)
        ) {
            throw IllegalStateException("Document catalog provider returned an invalid canonical folder.")
        }
    }

    private fun immutableFolderIds(folders: List<DocumentCatalogFolder>): Set<String> {
        val validated = validatedVisibleTree(folders)
        return Collections.unmodifiableSet(LinkedHashSet(validated.map { folder -> folder.id }))
    }

    /**
     * Validates one complete user-visible tree before any node is returned.
     * A provider must re-root a visible child instead of returning the opaque
     * identifier of a hidden parent; otherwise the whole snapshot fails closed.
     */
    private fun validatedVisibleTree(folders: List<DocumentCatalogFolder>): List<DocumentCatalogFolder> {
        if (folders.size > MAX_VISIBLE_FOLDER_COUNT) {
            throw IllegalStateException("Document catalog provider returned too many visible folders.")
        }
        val byId = LinkedHashMap<String, DocumentCatalogFolder>(folders.size)
        folders.forEach { folder ->
            requireValidCanonicalFolder(folder)
            if (byId.put(folder.id, folder) != null) {
                throw IllegalStateException("Document catalog provider returned duplicate folder identifiers.")
            }
        }
        byId.values.forEach { folder ->
            val parentId = folder.parentFolderId ?: return@forEach
            if (!byId.containsKey(parentId)) {
                throw IllegalStateException("Document catalog provider returned a folder with a hidden or missing parent.")
            }
        }
        val complete = HashSet<String>(byId.size)
        byId.keys.forEach { startId ->
            if (startId in complete) return@forEach
            val path = LinkedHashSet<String>()
            var currentId: String? = startId
            while (currentId != null && currentId !in complete) {
                if (!path.add(currentId)) {
                    throw IllegalStateException("Document catalog provider returned a cyclic folder tree.")
                }
                currentId = byId.getValue(currentId).parentFolderId
            }
            complete.addAll(path)
        }
        return Collections.unmodifiableList(ArrayList(byId.values))
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
        return accessAs(
            tenant.tenantId,
            user,
            operation,
            resourceId,
            resourceType,
            actionName,
            action,
        )
    }

    private fun <T> accessAs(
        tenantId: Identifier,
        operator: UserIdentity,
        operation: DocumentCatalogOperation,
        resourceId: Identifier,
        resourceType: String,
        actionName: String,
        action: (DocumentCatalogAccessRequest) -> T,
    ): T {
        val authorizedOperator = authorization.requireActionAs(
            tenantId,
            resourceId,
            resourceType,
            actionName,
            operator,
        )
        return action(DocumentCatalogAccessRequest(tenantId, authorizedOperator.id, operation))
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
        const val MAX_FOLDER_DISPLAY_NAME_LENGTH = 512
        const val MAX_VISIBLE_FOLDER_COUNT = 10_000
    }
}

private fun isUnsafeCatalogTextCharacter(character: Char): Boolean =
    Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()

/** Missing and user-invisible target folders retain the existing invalid-input contract. */
internal class DocumentCatalogFolderUnavailableException :
    IllegalArgumentException("Document catalog folder is unavailable.")
