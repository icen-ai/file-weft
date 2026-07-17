package ai.icen.fw.spi.storage

/**
 * Optional server-side multipart recovery capability.
 *
 * [StorageAdapter] deliberately remains unchanged for binary compatibility.
 * Implementations of this interface enumerate the provider's authoritative
 * multipart ledger so another process can recover after losing its local
 * checkpoint. Implementations must consume provider pagination internally and
 * return parts ordered by [MultipartPart.partNumber], with no duplicates.
 * A missing upload or an authorization failure must fail explicitly; it must
 * never be represented as an empty successful upload.
 * Both [MultipartUpload.uploadId] and its opaque tenant-scoped location are
 * authority and implementations must reject an inconsistent pair.
 */
interface ResumableMultipartStorageAdapter {
    fun listUploadedParts(upload: MultipartUpload): List<MultipartPart>
}
