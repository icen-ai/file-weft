package com.fileweft.application.upload

data class UploadFileCommand(
    val fileName: String,
    val contentLength: Long,
    val assetType: String,
    val contentType: String? = null,
    val contentHash: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(fileName.isNotBlank()) { "File name must not be blank." }
        require(contentLength >= 0) { "Content length must not be negative." }
        require(assetType.isNotBlank()) { "Asset type must not be blank." }
    }
}
