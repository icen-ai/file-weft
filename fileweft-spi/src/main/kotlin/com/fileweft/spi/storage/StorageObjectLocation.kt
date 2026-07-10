package com.fileweft.spi.storage

data class StorageObjectLocation(
    val storageType: String,
    val path: String,
) {
    init {
        require(storageType.isNotBlank()) { "Storage type must not be blank." }
        require(path.isNotBlank()) { "Storage path must not be blank." }
    }
}
