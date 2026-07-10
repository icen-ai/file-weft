package com.fileweft.domain.file

import com.fileweft.core.id.Identifier

interface FileAssetRepository {
    fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset?

    fun save(fileAsset: FileAsset)
}
