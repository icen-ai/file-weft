package ai.icen.fw.domain.file

import ai.icen.fw.core.id.Identifier

class FileAsset(
    val id: Identifier,
    val tenantId: Identifier,
    val fileObjectId: Identifier,
    val assetType: String,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(assetType.isNotBlank()) { "Asset type must not be blank." }
    }
}
