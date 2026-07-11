package com.fileweft.application.catalog

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.file.FileAsset
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.spi.catalog.DocumentCatalogBinding
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider

/**
 * Moves a document between host-owned folders without changing the document
 * lifecycle, object storage location, or any already published downstream
 * representation. The only persisted change is the opaque asset metadata.
 */
class DocumentCatalogBindingService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    private val catalogAccess: DocumentCatalogAccessService,
    private val documents: DocumentRepository,
    private val assets: FileAssetRepository,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
) {
    fun move(documentId: Identifier, folderId: String): Document {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        val folder = catalogAccess.requireFolderForDocumentUpdate(documentId, folderId)
        return transaction.execute {
            val document = documents.findById(tenant.tenantId, documentId)
                ?: throw DocumentNotFoundException(documentId)
            val asset = assets.findById(tenant.tenantId, document.assetId)
                ?: throw IllegalStateException("Document ${document.id.value} references a missing file asset.")
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
                    operatorId = operator?.id,
                    operatorName = operator?.displayName,
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
