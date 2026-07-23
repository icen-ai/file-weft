package ai.icen.fw.adapter.s3

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.MultipartCompletionRejectedException
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryMode
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.util.UUID

/**
 * S3-compatible [StorageAdapter] with opaque tenant-scoped keys.
 *
 * It deliberately calculates SHA-256 values from the streamed content instead
 * of returning an S3 ETag, because an ETag is not a portable content digest for
 * multipart objects. No AWS SDK type appears in this adapter's public API.
 */
class S3StorageAdapter(
    private val configuration: S3StorageConfiguration,
) : StorageAdapter, AutoCloseable {
    private val credentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(configuration.accessKey, configuration.secretKey),
    )
    private val client: S3Client = S3Client.builder()
        .endpointOverride(configuration.endpoint)
        .region(Region.of(configuration.region))
        .credentialsProvider(credentials)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(configuration.forcePathStyle).build())
        .overrideConfiguration(
            // Bound every storage call so a wedged connection can never block a
            // storage operation forever; retries use the SDK standard mode.
            ClientOverrideConfiguration.builder()
                .apiCallTimeout(configuration.apiCallTimeout)
                .apiCallAttemptTimeout(configuration.apiCallAttemptTimeout)
                .retryStrategy(RetryMode.STANDARD)
                .build(),
        )
        .build()
    private val presigner: S3Presigner = S3Presigner.builder()
        .endpointOverride(configuration.endpoint)
        .region(Region.of(configuration.region))
        .credentialsProvider(credentials)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(configuration.forcePathStyle).build())
        .build()

    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
        val location = newObjectLocation(request.tenantId)
        val measured = DigestingInputStream(content)
        try {
            client.putObject(
                PutObjectRequest.builder()
                    .bucket(configuration.bucket)
                    .key(location.path)
                    .contentType(request.contentType)
                    .metadata(userMetadata(request.metadata))
                    .build(),
                RequestBody.fromInputStream(measured, request.contentLength),
            )
            require(measured.contentLength == request.contentLength) {
                "Uploaded content length does not match the declared content length."
            }
            return StoredObject(location, measured.contentLength, request.contentType, measured.contentHash())
        } catch (failure: Throwable) {
            try {
                client.deleteObject(DeleteObjectRequest.builder().bucket(configuration.bucket).key(location.path).build())
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    override fun download(location: StorageObjectLocation): StorageDownload {
        val response = client.getObject(
            GetObjectRequest.builder().bucket(configuration.bucket).key(validatedKey(location)).build(),
        )
        return StorageDownload(
            content = response,
            contentLength = response.response().contentLength(),
            contentType = response.response().contentType(),
        )
    }

    override fun delete(location: StorageObjectLocation) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(configuration.bucket).key(validatedKey(location)).build())
    }

    override fun exists(location: StorageObjectLocation): Boolean = try {
        client.headObject(HeadObjectRequest.builder().bucket(configuration.bucket).key(validatedKey(location)).build())
        true
    } catch (failure: S3Exception) {
        if (failure.statusCode() == 404) false else throw failure
    }

    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI {
        require(!expiresIn.isNegative && !expiresIn.isZero) { "Access URL expiration must be positive." }
        val request = GetObjectPresignRequest.builder()
            .signatureDuration(expiresIn)
            .getObjectRequest(GetObjectRequest.builder().bucket(configuration.bucket).key(validatedKey(location)).build())
            .build()
        return presigner.presignGetObject(request).url().toURI()
    }

    override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload {
        val location = newObjectLocation(request.tenantId)
        val response = client.createMultipartUpload(
            CreateMultipartUploadRequest.builder()
                .bucket(configuration.bucket)
                .key(location.path)
                .contentType(request.contentType)
                .metadata(userMetadata(request.metadata))
                .build(),
        )
        val uploadId = response.uploadId().takeIf { !it.isNullOrBlank() }
            ?: throw IllegalStateException("S3 service did not return a multipart upload id.")
        return MultipartUpload(Identifier(uploadId), location)
    }

    override fun uploadPart(
        upload: MultipartUpload,
        partNumber: Int,
        content: InputStream,
        contentLength: Long,
    ): MultipartPart {
        require(partNumber > 0) { "Part number must be positive." }
        require(contentLength >= 0) { "Part content length must not be negative." }
        val measured = DigestingInputStream(content)
        val response = client.uploadPart(
            UploadPartRequest.builder()
                .bucket(configuration.bucket)
                .key(validatedKey(upload.location))
                .uploadId(upload.uploadId.value)
                .partNumber(partNumber)
                .build(),
            RequestBody.fromInputStream(measured, contentLength),
        )
        require(measured.contentLength == contentLength) {
            "Part content length does not match the declared content length."
        }
        val eTag = response.eTag().takeIf { !it.isNullOrBlank() }
            ?: throw IllegalStateException("S3 service did not return a multipart part ETag.")
        return MultipartPart(partNumber, eTag)
    }

    override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject {
        require(parts.isNotEmpty()) { "At least one multipart upload part is required." }
        require(parts.all { it.partNumber > 0 && it.eTag.isNotBlank() }) { "Multipart upload parts are invalid." }
        require(parts.map { it.partNumber }.distinct().size == parts.size) {
            "Multipart upload parts must not contain duplicates."
        }
        val key = validatedKey(upload.location)
        try {
            client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                    .bucket(configuration.bucket)
                    .key(key)
                    .uploadId(upload.uploadId.value)
                    .multipartUpload(
                        CompletedMultipartUpload.builder()
                            .parts(parts.sortedBy { it.partNumber }.map { part ->
                                CompletedPart.builder().partNumber(part.partNumber).eTag(part.eTag).build()
                            })
                            .build(),
                    )
                    .build(),
            )
        } catch (failure: S3Exception) {
            if (isDefinitiveMultipartCompletionRejection(failure)) {
                throw MultipartCompletionRejectedException(cause = failure)
            }
            throw failure
        }
        val head = client.headObject(HeadObjectRequest.builder().bucket(configuration.bucket).key(key).build())
        val contentLength = head.contentLength()
        require(contentLength >= 0) { "S3 service returned an invalid object length." }
        return StoredObject(
            location = upload.location,
            contentLength = contentLength,
            contentType = head.contentType(),
            contentHash = calculateObjectHash(key),
        )
    }

    override fun abortMultipartUpload(upload: MultipartUpload) {
        val key = validatedKey(upload.location)
        try {
            client.abortMultipartUpload(
                AbortMultipartUploadRequest.builder()
                    .bucket(configuration.bucket)
                    .key(key)
                    .uploadId(upload.uploadId.value)
                    .build(),
            )
        } catch (failure: S3Exception) {
            if (failure.statusCode() != 404) throw failure
        }
    }

    /** Creates the configured bucket when it is absent. Intended for development bootstrap only. */
    fun ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(configuration.bucket).build())
            return
        } catch (failure: S3Exception) {
            if (failure.statusCode() != 404) throw failure
        }
        try {
            client.createBucket(CreateBucketRequest.builder().bucket(configuration.bucket).build())
        } catch (failure: S3Exception) {
            if (failure.statusCode() != 409) throw failure
        }
    }

    override fun close() {
        presigner.close()
        client.close()
    }

    private fun calculateObjectHash(key: String): String {
        val response: ResponseInputStream<*> = client.getObject(
            GetObjectRequest.builder().bucket(configuration.bucket).key(key).build(),
        )
        response.use { content ->
            val measured = DigestingInputStream(content)
            val buffer = ByteArray(BUFFER_SIZE)
            while (measured.read(buffer) >= 0) {
                // The digest stream performs the work; no buffering of the object occurs.
            }
            return measured.contentHash()
        }
    }

    private fun newObjectLocation(tenantId: Identifier): StorageObjectLocation = StorageObjectLocation(
        configuration.storageType,
        "objects/${sha256(tenantId.value.toByteArray(Charsets.UTF_8))}/${UUID.randomUUID().toString().replace("-", "")}",
    )

    private fun validatedKey(location: StorageObjectLocation): String {
        require(location.storageType == configuration.storageType) { "Storage location does not belong to this adapter." }
        require(OBJECT_KEY_PATTERN.matches(location.path)) { "Storage location path is invalid." }
        return location.path
    }

    private fun userMetadata(metadata: Map<String, String>): Map<String, String> {
        require(metadata.keys.none { it.startsWith(RESERVED_METADATA_PREFIX) }) {
            "Storage metadata keys starting with $RESERVED_METADATA_PREFIX are reserved."
        }
        require(metadata.all { (key, value) -> key.isNotBlank() && value.isNotBlank() }) {
            "Storage metadata keys and values must not be blank."
        }
        return LinkedHashMap(metadata)
    }

    private class DigestingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        private val digest: MessageDigest = newSha256()
        var contentLength: Long = 0
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                digest.update(value.toByte())
                contentLength = Math.addExact(contentLength, 1)
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                digest.update(buffer, offset, read)
                contentLength = Math.addExact(contentLength, read.toLong())
            }
            return read
        }

        fun contentHash(): String = "sha256:" + digest.digest().toHex()
    }

    companion object {
        const val STORAGE_TYPE = "s3"
        private const val BUFFER_SIZE = 8 * 1024
        private const val RESERVED_METADATA_PREFIX = "fileweft-"
        private val OBJECT_KEY_PATTERN = Regex("objects/[0-9a-f]{64}/[0-9a-f]{32}")

        private fun newSha256(): MessageDigest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (failure: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is unavailable in this JVM.", failure)
        }

        private fun sha256(value: ByteArray): String = newSha256().digest(value).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

internal fun isDefinitiveMultipartCompletionRejection(failure: S3Exception): Boolean =
    failure.awsErrorDetails()?.errorCode() in DEFINITIVE_MULTIPART_COMPLETION_REJECTION_CODES

private val DEFINITIVE_MULTIPART_COMPLETION_REJECTION_CODES: Set<String> = setOf(
    "EntityTooSmall",
    "InvalidPart",
    "InvalidPartOrder",
)
