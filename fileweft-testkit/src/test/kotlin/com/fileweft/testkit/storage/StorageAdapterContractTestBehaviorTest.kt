package com.fileweft.testkit.storage

import com.fileweft.core.id.Identifier
import com.fileweft.spi.storage.MultipartPart
import com.fileweft.spi.storage.MultipartUpload
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageDownload
import com.fileweft.spi.storage.StorageObjectLocation
import com.fileweft.spi.storage.StorageUploadRequest
import com.fileweft.spi.storage.StoredObject
import java.io.InputStream
import java.net.URI
import java.time.Duration
import java.util.LinkedHashMap

/** Exercises every inherited storage contract assertion without depending on an adapter module. */
class StorageAdapterContractTestBehaviorTest : StorageAdapterContractTest() {
    override val storageAdapter: StorageAdapter = InMemoryStorageAdapter()

    override fun uploadRequest(): StorageUploadRequest = StorageUploadRequest(
        tenantId = Identifier("tenant-contract"),
        objectName = "contract.txt",
        contentLength = content().size.toLong(),
        contentType = "text/plain",
    )

    private class InMemoryStorageAdapter : StorageAdapter {
        private val objects = linkedMapOf<StorageObjectLocation, ObjectRecord>()
        private val uploads = linkedMapOf<String, UploadSession>()
        private var sequence = 0

        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
            val bytes = content.readBytes()
            require(bytes.size.toLong() == request.contentLength) { "Content length mismatch." }
            val location = nextLocation(request.tenantId)
            objects[location] = ObjectRecord(bytes, request.contentType)
            return StoredObject(location, bytes.size.toLong(), request.contentType)
        }

        override fun download(location: StorageObjectLocation): StorageDownload {
            val stored = requireNotNull(objects[location]) { "Object does not exist." }
            // The SPI allows stream-only adapters that cannot obtain metadata
            // without a separate provider call. The reusable contract must
            // accept that valid shape while still verifying bytes.
            return StorageDownload(stored.content.inputStream())
        }

        override fun delete(location: StorageObjectLocation) {
            objects.remove(location)
        }

        override fun exists(location: StorageObjectLocation): Boolean = objects.containsKey(location)

        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI {
            require(!expiresIn.isNegative && !expiresIn.isZero) { "Expiration must be positive." }
            require(exists(location)) { "Object does not exist." }
            return URI("memory://fileweft/${location.path}")
        }

        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload {
            val uploadId = "upload-${++sequence}"
            val location = nextLocation(request.tenantId)
            uploads[uploadId] = UploadSession(request, location)
            return MultipartUpload(Identifier(uploadId), location)
        }

        override fun uploadPart(
            upload: MultipartUpload,
            partNumber: Int,
            content: InputStream,
            contentLength: Long,
        ): MultipartPart {
            require(partNumber > 0) { "Part number must be positive." }
            val session = session(upload)
            val bytes = content.readBytes()
            require(bytes.size.toLong() == contentLength) { "Part length mismatch." }
            session.parts[partNumber] = bytes
            return MultipartPart(partNumber, eTag(partNumber, bytes))
        }

        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject {
            val session = session(upload)
            require(parts.isNotEmpty()) { "Parts are required." }
            require(parts.map { it.partNumber }.distinct().size == parts.size) { "Parts must be unique." }
            require(parts.map { it.partNumber }.toSet() == session.parts.keys) { "Parts do not match uploaded content." }
            parts.forEach { part ->
                val content = requireNotNull(session.parts[part.partNumber])
                require(part.eTag == eTag(part.partNumber, content)) { "Part eTag mismatch." }
            }
            val content = concatenate(parts.sortedBy { it.partNumber }.map { session.parts.getValue(it.partNumber) })
            require(content.size.toLong() == session.request.contentLength) { "Multipart content length mismatch." }
            objects[session.location] = ObjectRecord(content, session.request.contentType)
            uploads.remove(upload.uploadId.value)
            return StoredObject(session.location, content.size.toLong(), session.request.contentType)
        }

        override fun abortMultipartUpload(upload: MultipartUpload) {
            uploads.remove(upload.uploadId.value)
        }

        private fun session(upload: MultipartUpload): UploadSession {
            val session = requireNotNull(uploads[upload.uploadId.value]) { "Upload does not exist." }
            require(session.location == upload.location) { "Upload location mismatch." }
            return session
        }

        private fun nextLocation(tenantId: Identifier): StorageObjectLocation =
            StorageObjectLocation("memory", "objects/${tenantId.value}/${++sequence}")

        private fun eTag(partNumber: Int, content: ByteArray): String = "etag-$partNumber-${content.contentHashCode()}"

        private fun concatenate(parts: List<ByteArray>): ByteArray {
            val size = parts.fold(0) { total, part -> Math.addExact(total, part.size) }
            val content = ByteArray(size)
            var offset = 0
            parts.forEach { part ->
                part.copyInto(content, destinationOffset = offset)
                offset += part.size
            }
            return content
        }

        private data class ObjectRecord(val content: ByteArray, val contentType: String?)

        private class UploadSession(
            val request: StorageUploadRequest,
            val location: StorageObjectLocation,
            val parts: MutableMap<Int, ByteArray> = LinkedHashMap(),
        )
    }
}
