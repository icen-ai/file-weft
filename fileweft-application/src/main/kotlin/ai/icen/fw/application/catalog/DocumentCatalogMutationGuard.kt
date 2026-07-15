package ai.icen.fw.application.catalog

import ai.icen.fw.application.document.DocumentMutationGuard
import ai.icen.fw.application.document.DocumentMutationComponents
import ai.icen.fw.application.document.DocumentMutationPermit
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.document.DocumentSummaryView
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetMutationRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.spi.catalog.DocumentCatalogBinding
import ai.icen.fw.spi.identity.UserIdentity

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

    fun prepareAs(
        tenantId: Identifier,
        operator: UserIdentity,
        documentId: Identifier,
    ): DocumentMutationPermit {
        val permit = snapshot(
            tenantId,
            documentId,
            DocumentCatalogMutationPurpose.DRAFT_EDIT,
            operator = operator,
        )
        catalogAccess.requireCurrentFolderForDocumentUpdate(
            tenantId,
            operator,
            permit.documentId,
            permit.effectiveFolderId,
        )
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

    fun revalidateAs(
        tenantId: Identifier,
        operator: UserIdentity,
        documentId: Identifier,
        permit: DocumentMutationPermit,
    ) {
        val catalogPermit = catalogPermit(permit)
        if (
            catalogPermit.tenantId != tenantId ||
            catalogPermit.operator != operator ||
            catalogPermit.documentId != documentId
        ) {
            throw DocumentCatalogBindingChangedException(documentId)
        }
        catalogAccess.requireCurrentFolderForDocumentUpdate(
            tenantId,
            operator,
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
