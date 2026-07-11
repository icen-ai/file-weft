package com.fileweft.domain.file

import com.fileweft.core.id.Identifier

/**
 * Additive persistence capability for serializing an asset mutation.
 *
 * Callers that mutate a document-owned asset must acquire the document
 * mutation lock first and this asset lock second. Implementations must not
 * silently fall back to an ordinary read.
 */
interface FileAssetMutationRepository : FileAssetRepository {
    fun findForMutation(tenantId: Identifier, fileAssetId: Identifier): FileAsset?
}
