package com.fileweft.spi.storage

data class StoredObject(
    val location: StorageObjectLocation,
    val contentLength: Long,
    val contentType: String? = null,
    val contentHash: String? = null,
) {
    init {
        require(contentLength >= 0) { "Content length must not be negative." }
    }
}
