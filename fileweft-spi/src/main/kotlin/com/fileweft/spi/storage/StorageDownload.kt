package com.fileweft.spi.storage

import java.io.InputStream

class StorageDownload(
    val content: InputStream,
    val contentLength: Long? = null,
    val contentType: String? = null,
) {
    init {
        require(contentLength == null || contentLength >= 0) {
            "Content length must not be negative."
        }
    }
}
