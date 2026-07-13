package ai.icen.fw.sample.host

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.MultipartCompletionRejectedException
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private fun newObjectId(): String = UUID.randomUUID().toString()

/**
 * In-memory sample storage adapter used to demonstrate the StorageAdapter SPI
 * contract without external infrastructure. Tenant isolation is enforced by
 * prefixing the object path with the tenant id.
 */
class SampleStorageAdapter : StorageAdapter {

    private val objects = ConcurrentHashMap<StorageObjectLocation, ByteArray>()
    private val multipartParts = ConcurrentHashMap<Identifier, MutableMap<Int, SampleMultipartPart>>()

    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
        val bytes = readExact(content, request.contentLength.toInt())
        val location = locationFor(request.tenantId, request.objectName, newObjectId())
        objects[location] = bytes
        return StoredObject(
            location = location,
            contentLength = bytes.size.toLong(),
            contentType = request.contentType,
        )
    }

    override fun download(location: StorageObjectLocation): StorageDownload {
        val bytes = objects[location]
            ?: throw IllegalArgumentException("Object not found: ${location.path}")
        return StorageDownload(
            content = ByteArrayInputStream(bytes),
            contentLength = bytes.size.toLong(),
            contentType = null,
        )
    }

    override fun delete(location: StorageObjectLocation) {
        objects.remove(location)
    }

    override fun exists(location: StorageObjectLocation): Boolean = objects.containsKey(location)

    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI {
        return URI("file://sample${location.path}?expiresIn=${expiresIn.toMillis()}")
    }

    override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload {
        val uploadId = Identifier(UUID.randomUUID().toString())
        multipartParts[uploadId] = ConcurrentHashMap()
        return MultipartUpload(
            uploadId = uploadId,
            location = locationFor(request.tenantId, request.objectName, newObjectId()),
        )
    }

    override fun uploadPart(
        upload: MultipartUpload,
        partNumber: Int,
        content: InputStream,
        contentLength: Long,
    ): MultipartPart {
        val parts = multipartParts[upload.uploadId]
            ?: throw IllegalArgumentException("Unknown multipart upload: ${upload.uploadId}")
        val bytes = readExact(content, contentLength.toInt())
        val acknowledgement = MultipartPart(partNumber = partNumber, eTag = "etag-$partNumber-${UUID.randomUUID()}")
        synchronized(parts) {
            require(multipartParts[upload.uploadId] === parts) {
                "Unknown multipart upload: ${upload.uploadId}"
            }
            parts[partNumber] = SampleMultipartPart(bytes, acknowledgement.eTag)
        }
        return acknowledgement
    }

    override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject {
        val partMap = multipartParts[upload.uploadId]
            ?: throw IllegalArgumentException("Unknown multipart upload: ${upload.uploadId}")
        return synchronized(partMap) {
            require(multipartParts[upload.uploadId] === partMap) {
                "Unknown multipart upload: ${upload.uploadId}"
            }
            val sorted = parts.sortedBy { it.partNumber }
            if (sorted.isEmpty()) {
                throw MultipartCompletionRejectedException("Multipart completion requires at least one part.")
            }
            if (sorted.map { it.partNumber }.distinct().size != sorted.size) {
                throw MultipartCompletionRejectedException("Multipart completion must not contain duplicate part numbers.")
            }
            val combined = sorted.flatMap { acknowledgement ->
                val stored = partMap[acknowledgement.partNumber]
                    ?: throw MultipartCompletionRejectedException(
                        "Unknown multipart part: ${acknowledgement.partNumber}",
                    )
                if (stored.eTag != acknowledgement.eTag) {
                    throw MultipartCompletionRejectedException(
                        "Multipart part acknowledgement is stale: ${acknowledgement.partNumber}",
                    )
                }
                stored.content.toList()
            }.toByteArray()
            check(multipartParts.remove(upload.uploadId, partMap)) {
                "Multipart upload changed during completion: ${upload.uploadId}"
            }
            objects[upload.location] = combined
            StoredObject(
                location = upload.location,
                contentLength = combined.size.toLong(),
            )
        }
    }

    override fun abortMultipartUpload(upload: MultipartUpload) {
        multipartParts.remove(upload.uploadId)
        objects.remove(upload.location)
    }

    private fun locationFor(tenantId: Identifier, objectName: String, objectId: String): StorageObjectLocation {
        return StorageObjectLocation(
            storageType = "sample-memory",
            path = "/tenants/${tenantId.value}/objects/$objectName--$objectId",
        )
    }

    private fun readExact(content: InputStream, length: Int): ByteArray {
        require(length >= 0) { "Content length must not be negative." }
        if (length == 0) return ByteArray(0)
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = content.read(buffer, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        require(offset == length) { "Expected $length bytes but read $offset." }
        return buffer
    }

    private data class SampleMultipartPart(
        val content: ByteArray,
        val eTag: String,
    )
}
