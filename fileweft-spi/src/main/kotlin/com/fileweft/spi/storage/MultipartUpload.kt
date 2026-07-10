package com.fileweft.spi.storage

import com.fileweft.core.id.Identifier

data class MultipartUpload(
    val uploadId: Identifier,
    val location: StorageObjectLocation,
)
