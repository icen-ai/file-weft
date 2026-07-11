package com.fileweft.application.upload

import com.fileweft.spi.storage.StorageUploadRequest
import com.fileweft.spi.storage.StoredObject

/**
 * Rejects a storage acknowledgement that does not describe the bytes the caller authorized.
 * This check is deliberately kept above every persistence transaction so an untrusted adapter
 * response can never create a durable FileObject record.
 */
object StoredObjectIntegrity {
    @JvmStatic
    fun requireMatches(request: StorageUploadRequest, stored: StoredObject) {
        require(stored.contentLength == request.contentLength) {
            "Storage acknowledged ${stored.contentLength} bytes but ${request.contentLength} bytes were declared."
        }
        require(request.contentHash == null || request.contentHash == stored.contentHash) {
            "Storage content hash does not match the declared content hash."
        }
    }
}
