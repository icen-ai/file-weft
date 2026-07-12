package ai.icen.fw.application.upload

import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject

/**
 * Rejects a storage acknowledgement that does not describe the bytes the caller authorized.
 * This check is deliberately kept above every persistence transaction so an untrusted adapter
 * response can never create a durable FileObject record.
 */
object StoredObjectIntegrity {
    @JvmStatic
    fun requireMatches(request: StorageUploadRequest, stored: StoredObject) {
        if (stored.contentLength != request.contentLength) {
            throw StoredObjectIntegrityException(
                "Storage acknowledged ${stored.contentLength} bytes but ${request.contentLength} bytes were declared.",
            )
        }
        if (request.contentHash != null && request.contentHash != stored.contentHash) {
            throw StoredObjectIntegrityException("Storage content hash does not match the declared content hash.")
        }
    }
}
