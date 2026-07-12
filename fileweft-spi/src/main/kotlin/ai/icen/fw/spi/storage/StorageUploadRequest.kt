package ai.icen.fw.spi.storage

import ai.icen.fw.core.id.Identifier

data class StorageUploadRequest(
    val tenantId: Identifier,
    val objectName: String,
    val contentLength: Long,
    val contentType: String? = null,
    val contentHash: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(objectName.isNotBlank()) { "Object name must not be blank." }
        require(contentLength >= 0) { "Content length must not be negative." }
    }
}
