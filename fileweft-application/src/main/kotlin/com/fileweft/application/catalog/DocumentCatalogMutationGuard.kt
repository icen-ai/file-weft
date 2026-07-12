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
import com.fileweft.spi.identity.UserIdentity

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
) : DocumentMutationGuard, DocumentLifecycleMutationGuard {
    private val documents: DocumentRepository = components.documents
    private val assets: FileAssetRepository = components.assets
    private val transaction = components.transaction
    private val mutationAssets = components.assets as? FileAssetMutationRepository
        ?: throw IllegalArgumentException(
            "Catalog-safe document mutations require FileAssetMutationRepository.",
        )

    override fun prepare(tenantId: Identifier, documentId: Identifier): DocumentMutationPermit {
        val permit = snapshot(tenantId, documentId, DocumentCatalogMutationPurpose.DRAFT_EDIT)
        // The host catalog may be remote. It must be called only after the
        // short persistence snapshot transaction has completed.
        catalogAccess.requireCurrentFolderForDocumentUpdate(permit.documentId, permit.effectiveFolderId)
        return permit
    }

    override fun prepareLifecycle(
        tenantId: Identifier,
        operator: UserIdentity,
        documentId: Identifier,
        actionName: String,
    ): DocumentLifecycleMutationPermit {
        val permit = snapshot(
            tenantId,
            documentId,
            DocumentCatalogMutationPurpose.LIFECYCLE,
            actionName,
            operator,
        )
        // Base authorization and the host-owned folder ACL are intentionally
        // outside FileWeft's short snapshot transaction.
        catalogAccess.requireCurrentFolderForDocumentLifecycle(
            permit.tenantId,
            operator,
            permit.documentId,
            permit.effectiveFolderId,
            permit.actionName,
        )
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

    override fun revalidateLifecycle(
        tenantId: Identifier,
        operator: UserIdentity,
        documentId: Identifier,
        permit: DocumentLifecycleMutationPermit,
    ) {
        val catalogPermit = lifecyclePermit(permit)
        if (
            catalogPermit.tenantId != tenantId ||
            catalogPermit.operator != operator ||
            catalogPermit.documentId != documentId
        ) {
            throw DocumentCatalogBindingChangedException(documentId)
        }
        catalogAccess.requireCurrentFolderForDocumentLifecycle(
            catalogPermit.tenantId,
            operator,
            catalogPermit.documentId,
            catalogPermit.effectiveFolderId,
            catalogPermit.actionName,
        )
    }

    override fun verifyLocked(
        tenantId: Identifier,
        document: Document,
        permit: DocumentMutationPermit,
    ) {
        verifyAndLoadAssetLocked(tenantId, document, permit)
    }

    override fun verifyLifecycleLocked(
        tenantId: Identifier,
        document: Document,
        permit: DocumentLifecycleMutationPermit,
    ) {
        verifyAndLoadAssetLocked(tenantId, document, lifecyclePermit(permit))
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

    private fun snapshot(
        tenantId: Identifier,
        documentId: Identifier,
        purpose: DocumentCatalogMutationPurpose,
        actionName: String = DRAFT_EDIT_ACTION,
        operator: UserIdentity? = null,
    ): DocumentCatalogMutationPermit = transaction.execute {
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
            purpose = purpose,
            actionName = actionName,
            operator = operator,
        )
    }

    private fun catalogPermit(permit: DocumentMutationPermit): DocumentCatalogMutationPermit =
        permit as? DocumentCatalogMutationPermit
            ?: throw IllegalStateException("Document mutation permit does not belong to the catalog guard.")

    private fun lifecyclePermit(permit: DocumentLifecycleMutationPermit): DocumentCatalogMutationPermit {
        val catalogPermit = permit as? DocumentCatalogMutationPermit
            ?: throw IllegalStateException("Document lifecycle permit does not belong to the catalog guard.")
        if (
            catalogPermit.purpose != DocumentCatalogMutationPurpose.LIFECYCLE ||
            catalogPermit.operator == null
        ) {
            throw IllegalStateException("Document lifecycle permit was created for a different mutation purpose.")
        }
        return catalogPermit
    }

    private fun missingAsset(document: Document): IllegalStateException =
        IllegalStateException("Document ${document.id.value} references a missing file asset.")

    private companion object {
        const val DRAFT_EDIT_ACTION = "document:edit"
    }
}

private class DocumentCatalogMutationPermit(
    val tenantId: Identifier,
    val documentId: Identifier,
    val assetId: Identifier,
    val rawFolderId: String?,
    val effectiveFolderId: String,
    val purpose: DocumentCatalogMutationPurpose,
    val actionName: String,
    val operator: UserIdentity?,
) : DocumentMutationPermit, DocumentLifecycleMutationPermit

private enum class DocumentCatalogMutationPurpose {
    DRAFT_EDIT,
    LIFECYCLE,
}

/**
 * Two-phase policy boundary for catalog-aware lifecycle and workflow changes.
 * Implementations must keep external authorization and catalog calls outside
 * the final mutation transaction.
 */
internal interface DocumentLifecycleMutationGuard {
    fun prepareLifecycle(
        tenantId: Identifier,
        operator: UserIdentity,
        documentId: Identifier,
        actionName: String,
    ): DocumentLifecycleMutationPermit

    fun revalidateLifecycle(
        tenantId: Identifier,
        operator: UserIdentity,
        documentId: Identifier,
        permit: DocumentLifecycleMutationPermit,
    )

    /** Called only after the caller has acquired the document mutation lock. */
    fun verifyLifecycleLocked(
        tenantId: Identifier,
        document: Document,
        permit: DocumentLifecycleMutationPermit,
    )
}

/** Invocation-local evidence for one action-specific catalog decision. */
internal interface DocumentLifecycleMutationPermit
