package ai.icen.fw.spi.storage

import ai.icen.fw.core.id.Identifier

data class MultipartUpload(
    val uploadId: Identifier,
    val location: StorageObjectLocation,
)
