package ai.icen.fw.application.catalog

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.document.DocumentMutationComponents
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.security.ApplicationUnauthenticatedException
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.spi.catalog.DocumentCatalogBinding
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

/**
 * Moves a document between host-owned folders without changing the document
 * lifecycle, object storage location, or any already published downstream
 * representation. The only persisted change is the opaque asset metadata.
 *
 * This service is a top-level application boundary and must not be wrapped in
 * a host-owned database transaction because catalog authorization is external.
 * All catalog binding writers must acquire the document mutation lock and then
 * the asset mutation lock as done here; hosts must never edit
 * [DocumentCatalogBinding.METADATA_KEY] directly.
 * Catalog authorization is a short-lived snapshot and cannot be atomic with
 * permission changes made by the host catalog during this call.
 */
class DocumentCatalogBindingService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    private val catalogAccess: DocumentCatalogAccessService,
    private val documents: DocumentRepository,
    private val assets: FileAssetRepository,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
) : DocumentCatalogBindingCommand {
    private val mutationGuard = DocumentCatalogMutationGuard(
        catalogAccess,
        DocumentMutationComponents(documents, assets, transaction),
    )

    override fun move(documentId: Identifier, folderId: String): Document {
        val tenant = tenantProvider.currentTenant()
        val currentUser = userRealmProvider.currentUser()
            ?: throw ApplicationUnauthenticatedException()
        // Capture and validate one identity before any persistence or catalog
        // access; every later authorization and the audit use this snapshot.
        val operator = catalogAccess.requireDocumentUpdateAuthorizationAs(
            tenant.tenantId,
            documentId,
            currentUser,
        )
        val sourcePermit = mutationGuard.prepareAs(tenant.tenantId, operator, documentId)
        // Source visibility is established by prepare; only then may the
        // requested target folder be evaluated.
        val folder = catalogAccess.requireFolderForDocumentUpdateAs(
            tenant.tenantId,
            operator,
            documentId,
            folderId,
        )
        // A target provider may be remote. Revalidate the source decision after
        // that call so a revocation cannot slip into the final mutation window.
        mutationGuard.revalidateAs(tenant.tenantId, operator, documentId, sourcePermit)
        return transaction.execute {
            val document = documents.findForMutation(tenant.tenantId, documentId)
                ?: throw DocumentNotFoundException(documentId)
            val asset = mutationGuard.verifyAndLoadAssetLocked(tenant.tenantId, document, sourcePermit)
            val previousFolderId = asset.metadata[DocumentCatalogBinding.METADATA_KEY]
            if (previousFolderId != folder.id) {
                assets.save(
                    FileAsset(
                        id = asset.id,
                        tenantId = asset.tenantId,
                        fileObjectId = asset.fileObjectId,
                        assetType = asset.assetType,
                        metadata = asset.metadata + (DocumentCatalogBinding.METADATA_KEY to folder.id),
                    ),
                )
                auditTrail?.record(
                    tenantId = tenant.tenantId,
                    resourceType = DOCUMENT_RESOURCE_TYPE,
                    resourceId = document.id,
                    action = MOVE_ACTION,
                    operatorId = operator.id,
                    operatorName = operator.displayName,
                    details = linkedMapOf<String, String>().apply {
                        put("folderId", folder.id)
                        previousFolderId?.let { put("previousFolderId", it) }
                    },
                )
            }
            document
        }
    }

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val MOVE_ACTION = "document:catalog:move"
    }
}
