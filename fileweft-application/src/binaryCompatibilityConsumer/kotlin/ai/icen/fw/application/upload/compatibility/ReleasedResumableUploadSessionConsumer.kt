package ai.icen.fw.application.upload.compatibility

import ai.icen.fw.application.upload.ResumableUploadSession
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.StorageObjectLocation

/** Bytecode fixture: this source is compiled against the frozen pre-owner Kotlin API. */
object ReleasedResumableUploadSessionConsumer {
    @JvmStatic
    fun constructUsingReleasedDefaults(): ResumableUploadSession = ResumableUploadSession(
        id = Identifier("released-session"),
        tenantId = Identifier("released-tenant"),
        idempotencyKey = "released-key",
        storageUploadId = Identifier("released-storage-upload"),
        storageLocation = StorageObjectLocation("s3", "released/object"),
        fileObjectId = Identifier("released-file"),
        fileAssetId = Identifier("released-asset"),
        fileName = "released.pdf",
        contentLength = 7,
        assetType = "DOCUMENT",
        expiresAt = 1_000,
        createdTime = 100,
        updatedTime = 100,
    )
}
