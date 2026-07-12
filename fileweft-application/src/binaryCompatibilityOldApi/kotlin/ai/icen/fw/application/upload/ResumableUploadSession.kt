package ai.icen.fw.application.upload

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.StorageObjectLocation

/**
 * Frozen compile-time shape from the last API before upload-session owner binding.
 *
 * This source is compiled only with the compatibility consumer. Its classes are deliberately
 * excluded from the runtime fixture JAR, forcing that consumer to link to the current classes.
 */
@Suppress("unused")
class ResumableUploadSession @JvmOverloads constructor(
    val id: Identifier,
    val tenantId: Identifier,
    val idempotencyKey: String,
    val storageUploadId: Identifier,
    val storageLocation: StorageObjectLocation,
    val fileObjectId: Identifier,
    val fileAssetId: Identifier,
    val fileName: String,
    val contentLength: Long,
    val assetType: String,
    val contentType: String? = null,
    val expectedContentHash: String? = null,
    metadata: Map<String, String> = emptyMap(),
    val status: ResumableUploadSessionStatus = ResumableUploadSessionStatus.ACTIVE,
    val expiresAt: Long,
    val lastError: String? = null,
    val completedAt: Long? = null,
    val createdTime: Long,
    val updatedTime: Long,
) {
    val metadata: Map<String, String> = LinkedHashMap(metadata)
}

enum class ResumableUploadSessionStatus {
    ACTIVE,
    COMPLETING,
    COMPLETED,
    ABORTING,
    ABORTED,
    FAILED,
    EXPIRED,
}
