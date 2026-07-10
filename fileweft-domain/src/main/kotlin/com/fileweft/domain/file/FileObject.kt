package com.fileweft.domain.file

import com.fileweft.core.id.Identifier

class FileObject(
    val id: Identifier,
    val tenantId: Identifier,
    val fileName: String,
    val contentLength: Long,
    val storageType: String,
    val storagePath: String,
    val contentType: String? = null,
    val contentHash: String? = null,
) {
    init {
        require(fileName.isNotBlank()) { "File name must not be blank." }
        require(contentLength >= 0) { "Content length must not be negative." }
        require(storageType.isNotBlank()) { "Storage type must not be blank." }
        require(storagePath.isNotBlank()) { "Storage path must not be blank." }
    }
}
