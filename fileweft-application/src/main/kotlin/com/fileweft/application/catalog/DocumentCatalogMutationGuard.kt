package com.fileweft.application.catalog

import com.fileweft.application.document.DocumentMutationGuard
import com.fileweft.application.document.DocumentMutationComponents
import com.fileweft.application.document.DocumentMutationPermit
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.document.DocumentSummaryView
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.file.FileAsset
import com.fileweft.domain.file.FileAssetMutationRepository
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.spi.catalog.DocumentCatalogBinding

/**
 * Catalog ACL decision followed by a local binding recheck under the document
 * mutation lock.
 *
 * Every writer of [DocumentCatalogBinding.METADATA_KEY] must acquire the same
 * document mutation lock and then the asset mutation lock before a read or
 * write. Host code must not update this reserved metadata directly. Owners
 * must invoke the surrounding service as a top-level boundary without a host
 * database transaction so the snapshot transaction closes before the catalog call.
 * The external ACL decision is necessarily a short-lived snapshot and cannot
 * be atomic with a host catalog permission change.
 */
internal class DocumentCatalogMutationGuard(
    private val catalogAccess: DocumentCatalogAccessService,
    components: DocumentMutationComponents,
) : DocumentMutationGuard {
    private val documents: DocumentRepository = components.documents
    private val assets: FileAssetRepository = components.assets
    private val transaction = components.transaction
    private val mutationAssets = components.assets as? FileAssetMutationRepository
        ?: throw IllegalArgumentException(
            "Catalog-safe document mutations require FileAssetMutationRepository.",
        )

    override fun prepare(tenantId: Identifier, documentId: Identifier): DocumentMutationPermit {
        val permit = transaction.execute {
            val document = documents.findById(tenantId, documentId)
                ?: throw DocumentNotFoundException(documentId)
            if (document.tenantId != tenantId || document.id != documentId) {
                throw DocumentNotFoundException(documentId)
            }
            val asset = assets.findById(tenantId, document.assetId)
                ?: throw missingAsset(document)
            if (asset.tenantId != tenantId || asset.id != document.assetId) {
                throw missingAsset(document)
            }
            val rawFolderId = asset.metadata[DocumentCatalogBinding.METADATA_KEY]
            DocumentCatalogMutationPermit(
                tenantId = tenantId,
                documentId = document.id,
                assetId = document.assetId,
                rawFolderId = rawFolderId,
                effectiveFolderId = rawFolderId?.takeIf { value -> value.isNotBlank() }
                    ?: DocumentSummaryView.DEFAULT_FOLDER_ID,
            )
        }
        // The host catalog may be remote. It must be called only after the
        // short persistence snapshot transaction has completed.
        catalogAccess.requireCurrentFolderForDocumentUpdate(permit.documentId, permit.effectiveFolderId)
        return permit
    }

    override fun revalidate(
        tenantId: Identifier,
        documentId: Identifier,
        permit: DocumentMutationPermit,
    ) {
        val catalogPermit = catalogPermit(permit)
        if (catalogPermit.tenantId != tenantId || catalogPermit.documentId != documentId) {
            throw DocumentCatalogBindingChangedException(documentId)
        }
        catalogAccess.requireCurrentFolderForDocumentUpdate(
            catalogPermit.documentId,
            catalogPermit.effectiveFolderId,
        )
    }

    override fun verifyLocked(
        tenantId: Identifier,
        document: Document,
        permit: DocumentMutationPermit,
    ) {
        verifyAndLoadAssetLocked(tenantId, document, permit)
    }

    /** Locks and returns the verified asset so a catalog move needs no second read. */
    fun verifyAndLoadAssetLocked(
        tenantId: Identifier,
        document: Document,
        permit: DocumentMutationPermit,
    ): FileAsset {
        val catalogPermit = catalogPermit(permit)
        if (
            catalogPermit.tenantId != tenantId ||
            document.tenantId != tenantId ||
            catalogPermit.documentId != document.id ||
            catalogPermit.assetId != document.assetId
        ) {
            throw DocumentCatalogBindingChangedException(document.id)
        }
        val asset = mutationAssets.findForMutation(tenantId, document.assetId)
            ?: throw missingAsset(document)
        if (asset.tenantId != tenantId || asset.id != document.assetId) {
            throw DocumentCatalogBindingChangedException(document.id)
        }
        if (asset.metadata[DocumentCatalogBinding.METADATA_KEY] != catalogPermit.rawFolderId) {
            throw DocumentCatalogBindingChangedException(document.id)
        }
        return asset
    }

    private fun catalogPermit(permit: DocumentMutationPermit): DocumentCatalogMutationPermit =
        permit as? DocumentCatalogMutationPermit
            ?: throw IllegalStateException("Document mutation permit does not belong to the catalog guard.")

    private fun missingAsset(document: Document): IllegalStateException =
        IllegalStateException("Document ${document.id.value} references a missing file asset.")
}

private class DocumentCatalogMutationPermit(
    val tenantId: Identifier,
    val documentId: Identifier,
    val assetId: Identifier,
    val rawFolderId: String?,
    val effectiveFolderId: String,
) : DocumentMutationPermit
