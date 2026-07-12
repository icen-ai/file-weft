package ai.icen.fw.domain.file

import ai.icen.fw.core.id.Identifier

interface FileAssetRepository {
    fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset?

    fun save(fileAsset: FileAsset)
}
