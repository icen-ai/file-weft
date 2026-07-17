package ai.icen.fw.adapter.s3

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.ConditionalRangedStorageAdapter
import ai.icen.fw.spi.storage.MultipartCompletionRejectedException
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.RangedStorageAdapter
import ai.icen.fw.spi.storage.ResumableMultipartStorageAdapter
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageMetadataAdapter
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageObjectMetadata
import ai.icen.fw.spi.storage.StorageRangeRequest
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListPartsRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * S3-compatible [StorageAdapter] with opaque tenant-scoped keys.
 *
 * It deliberately calculates SHA-256 values from the streamed content instead
 * of returning an S3 ETag, because an ETag is not a portable content digest for
 * multipart objects. No AWS SDK type appears in this adapter's public API.
 */
class S3StorageAdapter :
    StorageAdapter,
    RangedStorageAdapter,
    ConditionalRangedStorageAdapter,
    StorageMetadataAdapter,
    ResumableMultipartStorageAdapter,
    AutoCloseable {
    private val configuration: S3StorageConfiguration
    private val clientPolicy: S3StorageClientPolicy
    private val retryingClient: S3Client
    private val singleAttemptClient: S3Client
    private val presigner: S3Presigner

    /** Retains the released constructor and applies a bounded production default policy. */
    constructor(configuration: S3StorageConfiguration) : this(configuration, S3StorageClientPolicy())

    /** Additive constructor for hosts that need explicit network and retry budgets. */
    constructor(configuration: S3StorageConfiguration, clientPolicy: S3StorageClientPolicy) {
        this.configuration = configuration
        this.clientPolicy = clientPolicy
        val credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(configuration.accessKey, configuration.secretKey),
        )
        val serviceConfiguration = S3Configuration.builder()
            .pathStyleAccessEnabled(configuration.forcePathStyle)
            .build()
        fun newClient(maxAttempts: Int): S3Client {
            val overrideConfiguration = ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(clientPolicy.apiCallAttemptTimeout)
                .apiCallTimeout(clientPolicy.apiCallTimeout)
                .retryStrategy(
                    StandardRetryStrategy.builder()
                        .maxAttempts(maxAttempts)
                        .build(),
                )
                .build()
            return S3Client.builder()
                .endpointOverride(configuration.endpoint)
                .region(Region.of(configuration.region))
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfiguration)
                .overrideConfiguration(overrideConfiguration)
                .httpClientBuilder(
                    UrlConnectionHttpClient.builder()
                        .connectionTimeout(clientPolicy.connectionTimeout)
                        .socketTimeout(clientPolicy.socketTimeout),
                )
                .build()
        }
        retryingClient = newClient(clientPolicy.maxAttempts)
        singleAttemptClient = newClient(SINGLE_ATTEMPT)
        presigner = S3Presigner.builder()
            .endpointOverride(configuration.endpoint)
            .region(Region.of(configuration.region))
            .credentialsProvider(credentials)
            .serviceConfiguration(serviceConfiguration)
            .build()
    }

    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
        val location = newObjectLocation(request.tenantId)
        val expectedHash = validatedExpectedHash(request.contentHash)
        val contentType = validatedContentType(request.contentType)
        val measured = DigestingInputStream(content)
        try {
            executeWithClient(S3StorageOperation.UPLOAD) { client ->
                client.putObject(
                    PutObjectRequest.builder()
                        .bucket(configuration.bucket)
                        .key(location.path)
                        .contentType(contentType)
                        .metadata(providerMetadata(request, expectedHash, multipart = false))
                        .build(),
                    RequestBody.fromInputStream(measured, request.contentLength),
                )
            }
            require(measured.contentLength == request.contentLength) {
                "Uploaded content length does not match the declared content length."
            }
            val contentHash = measured.contentHash()
            require(expectedHash == null || expectedHash == contentHash) {
                "Uploaded content hash does not match the expected content hash."
            }
            return StoredObject(location, measured.contentLength, contentType, contentHash)
        } catch (failure: Throwable) {
            try {
                deleteKey(location.path, S3StorageOperation.DELETE)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(s3StorageFailure(S3StorageOperation.DELETE, cleanupFailure))
            }
            throw failure
        }
    }

    override fun download(location: StorageObjectLocation): StorageDownload {
        val response = executeWithClient(S3StorageOperation.DOWNLOAD) { client ->
            client.getObject(
                GetObjectRequest.builder().bucket(configuration.bucket).key(validatedKey(location)).build(),
            )
        }
        val contentLength = try {
            validatedContentLength(response.response().contentLength(), S3StorageOperation.DOWNLOAD)
        } catch (failure: Throwable) {
            try {
                response.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(s3StorageFailure(S3StorageOperation.DOWNLOAD, closeFailure))
            }
            throw failure
        }
        return StorageDownload(
            content = TranslatingDownloadInputStream(response, S3StorageOperation.DOWNLOAD),
            contentLength = contentLength,
            contentType = response.response().contentType(),
        )
    }

    /**
     * Opens one HTTP byte range without reading or exposing bytes outside it.
     *
     * This additive optional capability leaves the released [StorageAdapter]
     * ABI unchanged. A range extending beyond the object end
     * returns the provider's valid clipped suffix; an offset beyond the end is
     * classified as [S3StorageFailureCategory.INVALID_REQUEST].
     */
    override fun downloadRange(location: StorageObjectLocation, offset: Long, length: Long): StorageDownload {
        require(offset >= 0) { "Storage range offset must not be negative." }
        require(length > 0) { "Storage range length must be positive." }
        val requestedEnd = Math.addExact(offset, length - 1)
        val response = executeWithClient(S3StorageOperation.DOWNLOAD_RANGE) { client ->
            client.getObject(
                GetObjectRequest.builder()
                    .bucket(configuration.bucket)
                    .key(validatedKey(location))
                    .range("bytes=$offset-$requestedEnd")
                    .build(),
            )
        }
        val actualLength = try {
            validatedRangeLength(response, offset, requestedEnd)
        } catch (failure: Throwable) {
            try {
                response.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(s3StorageFailure(S3StorageOperation.DOWNLOAD_RANGE, closeFailure))
            }
            throw failure
        }
        return StorageDownload(
            content = TranslatingDownloadInputStream(response, S3StorageOperation.DOWNLOAD_RANGE),
            contentLength = actualLength,
            contentType = response.response().contentType(),
        )
    }

    override fun metadata(location: StorageObjectLocation): StorageObjectMetadata {
        val canonicalLocation = StorageObjectLocation(configuration.storageType, validatedKey(location))
        val response = executeWithClient(S3StorageOperation.READ_METADATA) { client ->
            client.headObject(
                HeadObjectRequest.builder()
                    .bucket(configuration.bucket)
                    .key(canonicalLocation.path)
                    .build(),
            )
        }
        val contentLength = validatedContentLength(response.contentLength(), S3StorageOperation.READ_METADATA)
        val revision = response.versionId()?.takeIf { versionId -> versionId.isNotBlank() }
            ?.let { versionId -> encodeRevision(VERSION_REVISION_KIND, versionId) }
            ?: response.eTag()?.takeIf { eTag -> eTag.isNotBlank() }
                ?.let { eTag -> encodeRevision(ETAG_REVISION_KIND, eTag) }
            ?: throw IllegalStateException("S3-compatible service did not return an object revision.")
        return StorageObjectMetadata(
            location = canonicalLocation,
            contentLength = contentLength,
            contentType = response.contentType(),
            revision = revision,
            metadata = userVisibleMetadata(response.metadata()),
            lastModifiedTime = response.lastModified()?.toEpochMilli(),
        )
    }

    override fun downloadRange(request: StorageRangeRequest): StorageDownload {
        val requestedEnd = Math.addExact(request.offset, request.length - 1)
        val revision = decodeRevision(request.expectedRevision)
        val builder = GetObjectRequest.builder()
            .bucket(configuration.bucket)
            .key(validatedKey(request.location))
            .range("bytes=${request.offset}-$requestedEnd")
        when (revision.kind) {
            VERSION_REVISION_KIND -> builder.versionId(revision.value)
            ETAG_REVISION_KIND -> builder.ifMatch(revision.value)
            else -> throw IllegalArgumentException("Expected storage revision is invalid.")
        }
        val response = executeWithClient(S3StorageOperation.DOWNLOAD_RANGE) { client -> client.getObject(builder.build()) }
        val actualLength = try {
            val rangeLength = validatedRangeLength(response, request.offset, requestedEnd)
            if (!responseRevisionMatches(response.response(), revision)) {
                throw integrityFailure(
                    S3StorageOperation.DOWNLOAD_RANGE,
                    "S3-compatible service returned a different object revision.",
                )
            }
            rangeLength
        } catch (failure: Throwable) {
            try {
                response.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(s3StorageFailure(S3StorageOperation.DOWNLOAD_RANGE, closeFailure))
            }
            throw failure
        }
        return StorageDownload(
            content = TranslatingDownloadInputStream(response, S3StorageOperation.DOWNLOAD_RANGE),
            contentLength = actualLength,
            contentType = response.response().contentType(),
        )
    }

    override fun delete(location: StorageObjectLocation) {
        deleteKey(validatedKey(location), S3StorageOperation.DELETE)
    }

    override fun exists(location: StorageObjectLocation): Boolean {
        val key = validatedKey(location)
        return try {
            clientFor(S3StorageOperation.EXISTS).headObject(
                HeadObjectRequest.builder().bucket(configuration.bucket).key(key).build(),
            )
            true
        } catch (failure: S3Exception) {
            when {
                isNoSuchKey(failure) -> false
                isAmbiguousObjectNotFound(failure) -> {
                    // A body-less HEAD 404 cannot distinguish a missing object
                    // from a missing/inaccessible bucket. Prove bucket access
                    // before reporting a negative object lookup.
                    checkBucketAccess()
                    false
                }
                else -> throw s3StorageFailure(S3StorageOperation.EXISTS, failure)
            }
        } catch (failure: SdkException) {
            throw s3StorageFailure(S3StorageOperation.EXISTS, failure)
        }
    }

    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI {
        require(expiresIn >= MINIMUM_PRESIGN_DURATION && expiresIn <= MAXIMUM_PRESIGN_DURATION) {
            "Access URL expiration must be between one second and seven days."
        }
        val request = GetObjectPresignRequest.builder()
            .signatureDuration(expiresIn)
            .getObjectRequest(GetObjectRequest.builder().bucket(configuration.bucket).key(validatedKey(location)).build())
            .build()
        return executeWithoutClient(S3StorageOperation.PRESIGN_DOWNLOAD) {
            presigner.presignGetObject(request).url().toURI()
        }
    }

    override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload {
        val location = newObjectLocation(request.tenantId)
        val expectedHash = validatedExpectedHash(request.contentHash)
        val contentType = validatedContentType(request.contentType)
        val response = executeWithClient(S3StorageOperation.BEGIN_MULTIPART_UPLOAD) { client ->
            client.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                    .bucket(configuration.bucket)
                    .key(location.path)
                    .contentType(contentType)
                    .metadata(providerMetadata(request, expectedHash, multipart = true))
                    .build(),
            )
        }
        val uploadId = response.uploadId()?.takeIf(String::isNotBlank)
            ?: throw integrityFailure(
                S3StorageOperation.BEGIN_MULTIPART_UPLOAD,
                "S3-compatible service did not return a multipart upload id.",
            )
        val providerUploadId = try {
            validatedProviderUploadId(uploadId)
        } catch (_: IllegalArgumentException) {
            throw integrityFailure(
                S3StorageOperation.BEGIN_MULTIPART_UPLOAD,
                "S3-compatible service returned an invalid multipart upload id.",
            )
        }
        val opaqueUploadId = try {
            encodeUploadId(providerUploadId, location.path, request.contentLength, expectedHash)
        } catch (_: IllegalArgumentException) {
            val failure = integrityFailure(
                S3StorageOperation.BEGIN_MULTIPART_UPLOAD,
                "S3-compatible service returned an upload id that cannot be persisted safely.",
            )
            try {
                clientFor(S3StorageOperation.ABORT_MULTIPART_UPLOAD).abortMultipartUpload(
                    AbortMultipartUploadRequest.builder()
                        .bucket(configuration.bucket)
                        .key(location.path)
                        .uploadId(providerUploadId)
                        .build(),
                )
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(
                    if (cleanupFailure is S3StorageOperationException) cleanupFailure
                    else s3StorageFailure(S3StorageOperation.ABORT_MULTIPART_UPLOAD, cleanupFailure),
                )
            }
            throw failure
        }
        return MultipartUpload(Identifier(opaqueUploadId), location)
    }

    override fun uploadPart(
        upload: MultipartUpload,
        partNumber: Int,
        content: InputStream,
        contentLength: Long,
    ): MultipartPart {
        require(partNumber in 1..MAX_MULTIPART_PART_NUMBER) {
            "Part number must be between 1 and $MAX_MULTIPART_PART_NUMBER."
        }
        require(contentLength >= 0) { "Part content length must not be negative." }
        val multipart = validatedMultipartUpload(upload)
        val measured = DigestingInputStream(content)
        val response = executeWithClient(S3StorageOperation.UPLOAD_PART) { client ->
            client.uploadPart(
                UploadPartRequest.builder()
                    .bucket(configuration.bucket)
                    .key(multipart.key)
                    .uploadId(multipart.declaration.providerValue)
                    .partNumber(partNumber)
                    .build(),
                RequestBody.fromInputStream(measured, contentLength),
            )
        }
        require(measured.contentLength == contentLength) {
            "Part content length does not match the declared content length."
        }
        val eTag = response.eTag()?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("S3 service did not return a multipart part ETag.")
        return MultipartPart(partNumber, validatedETag(eTag))
    }

    override fun listUploadedParts(upload: MultipartUpload): List<MultipartPart> {
        return listUploadedPartRecords(upload).map { record -> record.part }
    }

    private fun listUploadedPartRecords(upload: MultipartUpload): List<ListedMultipartPart> {
        val multipart = validatedMultipartUpload(upload)
        val parts: List<ListedMultipartPart> = executeWithClient(S3StorageOperation.LIST_MULTIPART_PARTS) { client ->
            client.listPartsPaginator(
                ListPartsRequest.builder()
                    .bucket(configuration.bucket)
                    .key(multipart.key)
                    .uploadId(multipart.declaration.providerValue)
                    .build(),
            ).parts().map { part ->
                val partNumber = part.partNumber()?.takeIf { number -> number in 1..MAX_MULTIPART_PART_NUMBER }
                    ?: throw integrityFailure(
                        S3StorageOperation.LIST_MULTIPART_PARTS,
                        "S3-compatible service returned an invalid multipart part number.",
                    )
                val eTag = part.eTag()?.takeIf(String::isNotBlank)
                    ?: throw integrityFailure(
                        S3StorageOperation.LIST_MULTIPART_PARTS,
                        "S3-compatible service returned a multipart part without an ETag.",
                    )
                val size = part.size()?.takeIf { contentLength -> contentLength >= 0 }
                    ?: throw integrityFailure(
                        S3StorageOperation.LIST_MULTIPART_PARTS,
                        "S3-compatible service returned a multipart part with an invalid size.",
                    )
                ListedMultipartPart(MultipartPart(partNumber, validatedETag(eTag)), size)
            }.toList()
        }
        require(parts.map { record -> record.part.partNumber }.distinct().size == parts.size) {
            "S3-compatible service returned duplicate multipart part numbers."
        }
        return parts.sortedBy { record -> record.part.partNumber }
    }

    override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject {
        val requestedParts = validatedCompletionParts(parts)
        val multipart = validatedMultipartUpload(upload)
        val key = multipart.key
        val decodedUploadId = multipart.declaration
        val authoritativeParts = try {
            listUploadedPartRecords(upload)
        } catch (failure: S3StorageOperationException) {
            if (
                !decodedUploadId.legacy &&
                failure.missingResource == S3StorageMissingResource.MULTIPART_UPLOAD
            ) {
                reconcileCompletedMultipartUpload(key, upload.location, decodedUploadId, failure)?.let { return it }
            }
            throw failure
        }
        if (requestedParts != authoritativeParts.map { record -> record.part }) {
            throw MultipartCompletionRejectedException(
                cause = integrityFailure(
                    S3StorageOperation.COMPLETE_MULTIPART_UPLOAD,
                    "Multipart acknowledgements are no longer authoritative.",
                ),
            )
        }
        if (!decodedUploadId.legacy) {
            val uploadedLength = try {
                authoritativeParts.fold(0L) { total, record -> Math.addExact(total, record.contentLength) }
            } catch (_: ArithmeticException) {
                throw MultipartCompletionRejectedException(
                    cause = integrityFailure(
                        S3StorageOperation.COMPLETE_MULTIPART_UPLOAD,
                        "Multipart content length overflowed its declaration.",
                    ),
                )
            }
            if (uploadedLength != decodedUploadId.expectedLength) {
                throw MultipartCompletionRejectedException(
                    cause = integrityFailure(
                        S3StorageOperation.COMPLETE_MULTIPART_UPLOAD,
                        "Multipart content length does not match its declaration.",
                    ),
                )
            }
        }
        val response: CompleteMultipartUploadResponse = try {
            clientFor(S3StorageOperation.COMPLETE_MULTIPART_UPLOAD).completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                    .bucket(configuration.bucket)
                    .key(key)
                    .uploadId(decodedUploadId.providerValue)
                    .multipartUpload(
                        CompletedMultipartUpload.builder()
                            .parts(requestedParts.map { part ->
                                CompletedPart.builder().partNumber(part.partNumber).eTag(part.eTag).build()
                            })
                            .build(),
                    )
                    .build(),
            )
        } catch (failure: S3Exception) {
            val translated = s3StorageFailure(S3StorageOperation.COMPLETE_MULTIPART_UPLOAD, failure)
            if (isDefinitiveMultipartCompletionRejection(failure)) {
                throw MultipartCompletionRejectedException(cause = translated)
            }
            if (
                !decodedUploadId.legacy &&
                (translated.retryable || isNoSuchUpload(failure))
            ) {
                reconcileCompletedMultipartUpload(key, upload.location, decodedUploadId, translated)?.let { return it }
            }
            throw translated
        } catch (failure: SdkException) {
            val translated = s3StorageFailure(S3StorageOperation.COMPLETE_MULTIPART_UPLOAD, failure)
            if (!decodedUploadId.legacy && translated.retryable) {
                reconcileCompletedMultipartUpload(key, upload.location, decodedUploadId, translated)?.let { return it }
            }
            throw translated
        }
        return verifiedCompletedMultipartObject(
            key = key,
            location = upload.location,
            declaration = decodedUploadId.takeUnless { decoded -> decoded.legacy },
            expectedVersionId = response.versionId()?.takeIf(String::isNotBlank),
            expectedETag = response.eTag()?.takeIf(String::isNotBlank),
        )
    }

    override fun abortMultipartUpload(upload: MultipartUpload) {
        val multipart = validatedMultipartUpload(upload)
        try {
            clientFor(S3StorageOperation.ABORT_MULTIPART_UPLOAD).abortMultipartUpload(
                AbortMultipartUploadRequest.builder()
                    .bucket(configuration.bucket)
                    .key(multipart.key)
                    .uploadId(multipart.declaration.providerValue)
                    .build(),
            )
        } catch (failure: S3Exception) {
            if (!isNoSuchUpload(failure)) {
                throw s3StorageFailure(S3StorageOperation.ABORT_MULTIPART_UPLOAD, failure)
            }
        } catch (failure: SdkException) {
            throw s3StorageFailure(S3StorageOperation.ABORT_MULTIPART_UPLOAD, failure)
        }
    }

    /** Creates the configured bucket when it is absent. Intended for development bootstrap only. */
    fun ensureBucket() {
        if (bucketExists()) return
        try {
            clientFor(S3StorageOperation.CREATE_BUCKET).createBucket(
                CreateBucketRequest.builder().bucket(configuration.bucket).build(),
            )
        } catch (failure: S3Exception) {
            if (failure.statusCode() != 409) {
                throw s3StorageFailure(S3StorageOperation.CREATE_BUCKET, failure)
            }
            // A concurrent creator is acceptable only when the configured
            // credentials can now access the bucket. Never swallow a conflict
            // for a bucket owned by another account.
            checkBucketAccess()
        } catch (failure: SdkException) {
            throw s3StorageFailure(S3StorageOperation.CREATE_BUCKET, failure)
        }
    }

    /** Side-effect-free bucket reachability probe used by the adapter Doctor. */
    internal fun checkBucketAccess() {
        executeWithClient(S3StorageOperation.CHECK_BUCKET) { client ->
            client.headBucket(HeadBucketRequest.builder().bucket(configuration.bucket).build())
        }
    }

    /** Bounded evidence that intentionally omits host, bucket and credentials. */
    internal fun diagnosticEvidence(): Map<String, String> = linkedMapOf(
        "storageType" to configuration.storageType,
        "endpointScheme" to configuration.endpoint.scheme,
        "region" to configuration.region,
        "pathStyle" to configuration.forcePathStyle.toString(),
        "connectionTimeoutMillis" to clientPolicy.connectionTimeout.toMillis().toString(),
        "socketTimeoutMillis" to clientPolicy.socketTimeout.toMillis().toString(),
        "apiCallAttemptTimeoutMillis" to clientPolicy.apiCallAttemptTimeout.toMillis().toString(),
        "apiCallTimeoutMillis" to clientPolicy.apiCallTimeout.toMillis().toString(),
        "readProbeMaxAttempts" to clientPolicy.maxAttempts.toString(),
        "mutationMaxAttempts" to SINGLE_ATTEMPT.toString(),
    )

    override fun close() {
        var firstFailure: S3StorageOperationException? = null
        fun closeResource(action: () -> Unit) {
            try {
                action()
            } catch (failure: Exception) {
                val translated = s3StorageFailure(S3StorageOperation.CLOSE, failure)
                if (firstFailure == null) {
                    firstFailure = translated
                } else {
                    firstFailure!!.addSuppressed(translated)
                }
            }
        }
        closeResource(presigner::close)
        closeResource(singleAttemptClient::close)
        closeResource(retryingClient::close)
        firstFailure?.let { throw it }
    }

    /**
     * CompleteMultipartUpload can commit remotely and still lose its response.
     * A new FlowWeft upload is recoverable only when the random key resolves to
     * an object carrying the exact declaration persisted by multipart begin,
     * and a conditional full-object read proves the declared SHA-256 (when
     * supplied). Legacy upload ids remain completable after an acknowledged
     * success, but are never guessed successful after an ambiguous outcome.
     */
    private fun reconcileCompletedMultipartUpload(
        key: String,
        location: StorageObjectLocation,
        declaration: DecodedUploadId,
        original: S3StorageOperationException,
    ): StoredObject? = try {
        verifiedCompletedMultipartObject(
            key = key,
            location = location,
            declaration = declaration,
            expectedVersionId = null,
            expectedETag = null,
        )
    } catch (reconciliationFailure: Exception) {
        original.addSuppressed(
            if (reconciliationFailure is S3StorageOperationException) {
                reconciliationFailure
            } else {
                integrityFailure(
                    S3StorageOperation.VERIFY_COMPLETED_OBJECT,
                    "Completed multipart outcome could not be reconciled.",
                )
            },
        )
        null
    }

    private fun verifiedCompletedMultipartObject(
        key: String,
        location: StorageObjectLocation,
        declaration: DecodedUploadId?,
        expectedVersionId: String?,
        expectedETag: String?,
    ): StoredObject {
        val head = executeWithClient(S3StorageOperation.VERIFY_COMPLETED_OBJECT) { client ->
            client.headObject(HeadObjectRequest.builder().bucket(configuration.bucket).key(key).build())
        }
        val contentLength = validatedContentLength(
            head.contentLength(),
            S3StorageOperation.VERIFY_COMPLETED_OBJECT,
        )
        if (
            expectedVersionId != null &&
            head.versionId() != validatedProviderRevision(expectedVersionId)
        ) {
            throw integrityFailure(
                S3StorageOperation.VERIFY_COMPLETED_OBJECT,
                "Completed object did not match the completion response version.",
            )
        }
        if (expectedETag != null && head.eTag() != validatedETag(expectedETag)) {
            throw integrityFailure(
                S3StorageOperation.VERIFY_COMPLETED_OBJECT,
                "Completed object did not match the completion response ETag.",
            )
        }

        val metadata = canonicalProviderMetadata(head.metadata(), S3StorageOperation.VERIFY_COMPLETED_OBJECT)
        val declarationVersion = metadata[DECLARATION_VERSION_METADATA_KEY]
        if (declaration != null && declarationVersion != MULTIPART_DECLARATION_VERSION) {
            throw integrityFailure(
                S3StorageOperation.VERIFY_COMPLETED_OBJECT,
                "Completed object has no authoritative multipart declaration.",
            )
        }
        val declaredHash: String? = if (declarationVersion == MULTIPART_DECLARATION_VERSION) {
            val declaredLength = metadata[CONTENT_LENGTH_METADATA_KEY]?.toLongOrNull()
            if (
                declaredLength == null ||
                declaredLength != contentLength ||
                (declaration != null && declaredLength != declaration.expectedLength)
            ) {
                throw integrityFailure(
                    S3StorageOperation.VERIFY_COMPLETED_OBJECT,
                    "Completed object did not match its declared content length.",
                )
            }
            val metadataHash = metadata[CONTENT_HASH_METADATA_KEY]?.let { hash ->
                try {
                    validatedExpectedHash(hash)
                } catch (_: IllegalArgumentException) {
                    throw integrityFailure(
                        S3StorageOperation.VERIFY_COMPLETED_OBJECT,
                        "Completed object contained an invalid declared SHA-256.",
                    )
                }
            }
            if (declaration != null && metadataHash != declaration.expectedHash) {
                throw integrityFailure(
                    S3StorageOperation.VERIFY_COMPLETED_OBJECT,
                    "Completed object did not match its durable SHA-256 declaration.",
                )
            }
            metadataHash
        } else {
            // An acknowledged completion from a pre-1.0 session has no
            // declaration metadata. It still receives a revision-bound full
            // hash, but it is deliberately ineligible for outcome recovery.
            null
        }

        val actualHash = calculateObjectHash(key, head, contentLength)
        if (declaredHash != null && declaredHash != actualHash) {
            throw integrityFailure(
                S3StorageOperation.VERIFY_COMPLETED_OBJECT,
                "Completed object did not match its declared SHA-256.",
            )
        }
        return StoredObject(
            location = location,
            contentLength = contentLength,
            contentType = head.contentType()?.takeIf(String::isNotBlank),
            contentHash = actualHash,
        )
    }

    private fun calculateObjectHash(
        key: String,
        head: HeadObjectResponse,
        expectedContentLength: Long,
    ): String {
        val revision = strongRevision(head)
        val request = GetObjectRequest.builder().bucket(configuration.bucket).key(key)
        when (revision.kind) {
            VERSION_REVISION_KIND -> request.versionId(revision.value)
            ETAG_REVISION_KIND -> request.ifMatch(revision.value)
            else -> throw integrityFailure(
                S3StorageOperation.CHECKSUM_COMPLETED_OBJECT,
                "Completed object revision kind is unsupported.",
            )
        }
        val response: ResponseInputStream<GetObjectResponse> =
            executeWithClient(S3StorageOperation.CHECKSUM_COMPLETED_OBJECT) { client ->
                client.getObject(request.build())
            }
        var primaryFailure: Throwable? = null
        try {
            requireResponseRevision(response.response(), revision)
            require(response.response().contentLength() == expectedContentLength) {
                "S3-compatible service returned a different object length during checksum verification."
            }
            val measured = DigestingInputStream(response)
            val buffer = ByteArray(BUFFER_SIZE)
            while (measured.read(buffer) != -1) {
                // The digest stream performs the work; no buffering of the object occurs.
            }
            if (measured.contentLength != expectedContentLength) {
                throw integrityFailure(
                    S3StorageOperation.CHECKSUM_COMPLETED_OBJECT,
                    "Completed object bytes did not match the verified object length.",
                )
            }
            return measured.contentHash()
        } catch (failure: S3StorageOperationException) {
            primaryFailure = failure
            throw failure
        } catch (failure: IOException) {
            val translated = s3StorageFailure(S3StorageOperation.CHECKSUM_COMPLETED_OBJECT, failure)
            primaryFailure = translated
            throw translated
        } catch (failure: IllegalArgumentException) {
            val translated = integrityFailure(
                S3StorageOperation.CHECKSUM_COMPLETED_OBJECT,
                failure.message.orEmpty(),
            )
            primaryFailure = translated
            throw translated
        } finally {
            try {
                response.close()
            } catch (closeFailure: Throwable) {
                val translated = s3StorageFailure(S3StorageOperation.CHECKSUM_COMPLETED_OBJECT, closeFailure)
                if (primaryFailure == null) {
                    throw translated
                } else {
                    primaryFailure!!.addSuppressed(translated)
                }
            }
        }
    }

    private fun bucketExists(): Boolean = try {
        clientFor(S3StorageOperation.CHECK_BUCKET).headBucket(
            HeadBucketRequest.builder().bucket(configuration.bucket).build(),
        )
        true
    } catch (failure: S3Exception) {
        if (isNoSuchBucket(failure)) false else throw s3StorageFailure(S3StorageOperation.CHECK_BUCKET, failure)
    } catch (failure: SdkException) {
        throw s3StorageFailure(S3StorageOperation.CHECK_BUCKET, failure)
    }

    private fun deleteKey(key: String, operation: S3StorageOperation) {
        try {
            clientFor(operation).deleteObject(
                DeleteObjectRequest.builder().bucket(configuration.bucket).key(key).build(),
            )
        } catch (failure: S3Exception) {
            if (!isNoSuchKey(failure)) throw s3StorageFailure(operation, failure)
        } catch (failure: SdkException) {
            throw s3StorageFailure(operation, failure)
        }
    }

    private fun clientFor(operation: S3StorageOperation): S3Client = when (s3ClientMode(operation)) {
        S3StorageClientMode.RETRYING -> retryingClient
        S3StorageClientMode.SINGLE_ATTEMPT -> singleAttemptClient
        S3StorageClientMode.NONE -> throw IllegalArgumentException(
            "S3 storage operation ${operation.name} does not use an S3 client.",
        )
    }

    private inline fun <T> executeWithClient(
        operation: S3StorageOperation,
        action: (S3Client) -> T,
    ): T = try {
        action(clientFor(operation))
    } catch (failure: SdkException) {
        throw s3StorageFailure(operation, failure)
    }

    private inline fun <T> executeWithoutClient(operation: S3StorageOperation, action: () -> T): T = try {
        action()
    } catch (failure: Exception) {
        throw s3StorageFailure(operation, failure)
    }

    private fun validatedContentLength(value: Long?, operation: S3StorageOperation): Long =
        value?.takeIf { contentLength -> contentLength >= 0 }
            ?: throw integrityFailure(
                operation,
                "S3-compatible service returned an invalid content length.",
            )

    private fun validatedRangeLength(
        response: ResponseInputStream<GetObjectResponse>,
        requestedStart: Long,
        requestedEnd: Long,
    ): Long {
        val match = response.response().contentRange()?.let(CONTENT_RANGE_PATTERN::matchEntire)
            ?: throw integrityFailure(
                S3StorageOperation.DOWNLOAD_RANGE,
                "S3-compatible service did not honor the requested byte range.",
            )
        val actualStart = match.groupValues[1].toLongOrNull()
            ?: throw integrityFailure(
                S3StorageOperation.DOWNLOAD_RANGE,
                "S3-compatible service returned an invalid byte-range start.",
            )
        val actualEnd = match.groupValues[2].toLongOrNull()
            ?: throw integrityFailure(
                S3StorageOperation.DOWNLOAD_RANGE,
                "S3-compatible service returned an invalid byte-range end.",
            )
        if (actualStart != requestedStart || actualEnd !in actualStart..requestedEnd) {
            throw integrityFailure(
                S3StorageOperation.DOWNLOAD_RANGE,
                "S3-compatible service returned a byte range outside the request.",
            )
        }
        val actualLength = Math.addExact(Math.subtractExact(actualEnd, actualStart), 1)
        if (response.response().contentLength() != actualLength) {
            throw integrityFailure(
                S3StorageOperation.DOWNLOAD_RANGE,
                "S3-compatible service returned inconsistent byte-range metadata.",
            )
        }
        return actualLength
    }

    private fun responseRevisionMatches(response: GetObjectResponse, expected: RevisionCondition): Boolean =
        when (expected.kind) {
            VERSION_REVISION_KIND -> response.versionId() == expected.value
            ETAG_REVISION_KIND -> response.eTag() == expected.value
            else -> false
        }

    private fun requireResponseRevision(response: GetObjectResponse, expected: RevisionCondition) {
        if (!responseRevisionMatches(response, expected)) {
            throw integrityFailure(
                S3StorageOperation.CHECKSUM_COMPLETED_OBJECT,
                "S3-compatible service returned a different object revision.",
            )
        }
    }

    private fun strongRevision(head: HeadObjectResponse): RevisionCondition =
        head.versionId()?.takeIf(String::isNotBlank)
            ?.let { versionId -> RevisionCondition(VERSION_REVISION_KIND, validatedProviderRevision(versionId)) }
            ?: head.eTag()?.takeIf(String::isNotBlank)
                ?.let { eTag -> RevisionCondition(ETAG_REVISION_KIND, validatedETag(eTag)) }
            ?: throw integrityFailure(
                S3StorageOperation.VERIFY_COMPLETED_OBJECT,
                "S3-compatible service did not return an object revision.",
            )

    private fun encodeRevision(kind: String, value: String): String {
        val revision = validatedProviderRevision(value)
        return kind + Base64.getUrlEncoder().withoutPadding().encodeToString(revision.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeRevision(encoded: String): RevisionCondition = try {
        val kind = when {
            encoded.startsWith(VERSION_REVISION_KIND) -> VERSION_REVISION_KIND
            encoded.startsWith(ETAG_REVISION_KIND) -> ETAG_REVISION_KIND
            else -> throw IllegalArgumentException()
        }
        val payload = encoded.substring(kind.length)
        require(payload.isNotEmpty() && payload.length <= MAX_ENCODED_REVISION_LENGTH)
        val value = String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
        validatedProviderRevision(value)
        require(encodeRevision(kind, value) == encoded)
        RevisionCondition(kind, value)
    } catch (_: RuntimeException) {
        throw IllegalArgumentException("Expected storage revision is invalid.")
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

    /**
     * Validates the durable upload id and its canonical destination as one
     * unit before an operation is allowed to select an HTTP client. Raw ids
     * from released sessions remain location-unbound for compatibility; every
     * upload id created by this adapter is bound to storage type, bucket and
     * key with a full SHA-256 digest.
     */
    private fun validatedMultipartUpload(upload: MultipartUpload): ValidatedMultipartUpload {
        val key = validatedKey(upload.location)
        val declaration = decodeUploadId(upload.uploadId.value)
        val expectedLocationDigest = declaration.locationDigest
        if (
            expectedLocationDigest != null &&
            !MessageDigest.isEqual(expectedLocationDigest, multipartLocationDigest(key))
        ) {
            throw IllegalArgumentException("Multipart upload id does not belong to this storage location.")
        }
        return ValidatedMultipartUpload(key, declaration)
    }

    private fun providerMetadata(
        request: StorageUploadRequest,
        expectedHash: String?,
        multipart: Boolean,
    ): Map<String, String> = LinkedHashMap(userMetadata(request.metadata)).apply {
        put(
            DECLARATION_VERSION_METADATA_KEY,
            if (multipart) MULTIPART_DECLARATION_VERSION else STREAM_DECLARATION_VERSION,
        )
        put(CONTENT_LENGTH_METADATA_KEY, request.contentLength.toString())
        expectedHash?.let { hash -> put(CONTENT_HASH_METADATA_KEY, hash) }
    }

    private fun userVisibleMetadata(metadata: Map<String, String>): Map<String, String> =
        canonicalProviderMetadata(metadata, S3StorageOperation.READ_METADATA)
            .filterKeys { key -> !key.startsWith(RESERVED_METADATA_PREFIX) }

    private fun canonicalProviderMetadata(
        metadata: Map<String, String>,
        operation: S3StorageOperation,
    ): Map<String, String> {
        val canonical = LinkedHashMap<String, String>()
        metadata.forEach { (key, value) ->
            val normalizedKey = key.lowercase(Locale.ROOT)
            if (canonical.put(normalizedKey, value) != null) {
                throw integrityFailure(
                    operation,
                    "S3-compatible service returned duplicate metadata keys.",
                )
            }
        }
        return canonical
    }

    private fun userMetadata(metadata: Map<String, String>): Map<String, String> {
        val normalizedKeys = HashSet<String>()
        metadata.forEach { (key, value) ->
            require(USER_METADATA_KEY_PATTERN.matches(key)) { "Storage metadata key is invalid." }
            val normalizedKey = key.lowercase(Locale.ROOT)
            require(!normalizedKey.startsWith(RESERVED_METADATA_PREFIX)) {
                "Storage metadata key uses a reserved prefix."
            }
            require(normalizedKeys.add(normalizedKey)) { "Storage metadata keys must be unique ignoring case." }
            require(
                value.isNotBlank() &&
                    value.length <= MAX_USER_METADATA_VALUE_LENGTH &&
                    value.none { character -> Character.isISOControl(character) },
            ) {
                "Storage metadata value is invalid."
            }
        }
        return LinkedHashMap(metadata)
    }

    private fun validatedExpectedHash(value: String?): String? {
        if (value == null) return null
        require(SHA256_HASH_PATTERN.matches(value)) {
            "Expected content hash must be a canonical SHA-256 value."
        }
        return value.lowercase(Locale.ROOT)
    }

    private fun validatedContentType(value: String?): String? {
        if (value == null) return null
        require(
            value.isNotBlank() &&
                value.length <= MAX_CONTENT_TYPE_LENGTH &&
                value.none { character -> Character.isISOControl(character) },
        ) {
            "Storage content type is invalid."
        }
        return value
    }

    private fun validatedCompletionParts(parts: List<MultipartPart>): List<MultipartPart> {
        require(parts.isNotEmpty()) { "At least one multipart upload part is required." }
        require(parts.size <= MAX_MULTIPART_PART_NUMBER) { "Multipart upload contains too many parts." }
        val result = parts.map { part ->
            require(part.partNumber in 1..MAX_MULTIPART_PART_NUMBER) { "Multipart upload part number is invalid." }
            MultipartPart(part.partNumber, validatedETag(part.eTag))
        }.sortedBy(MultipartPart::partNumber)
        require(result.map(MultipartPart::partNumber).distinct().size == result.size) {
            "Multipart upload parts must not contain duplicates."
        }
        return result
    }

    private fun validatedETag(value: String): String {
        require(
            value.isNotBlank() &&
                value.length <= MAX_ETAG_LENGTH &&
                value.none { character -> Character.isISOControl(character) },
        ) {
            "S3-compatible service returned an invalid ETag."
        }
        return value
    }

    private fun validatedProviderRevision(value: String): String {
        require(
            value.isNotBlank() &&
                value.length <= MAX_PROVIDER_REVISION_LENGTH &&
                value.none { character -> Character.isISOControl(character) },
        ) {
            "S3-compatible service returned an invalid object revision."
        }
        return value
    }

    private fun encodeUploadId(
        providerValue: String,
        key: String,
        expectedLength: Long,
        expectedHash: String?,
    ): String {
        require(expectedLength >= 0) { "Multipart expected content length is invalid." }
        val providerBytes = providerValue.toByteArray(StandardCharsets.UTF_8)
        require(providerBytes.size <= MAX_ENVELOPED_PROVIDER_UPLOAD_ID_BYTES) {
            "S3-compatible service returned an upload id that exceeds the durable session limit."
        }
        val canonicalHash = expectedHash?.let(::validatedExpectedHash)
        val flags = if (canonicalHash == null) 0 else MULTIPART_ENVELOPE_HASH_FLAG
        val fixedLength = MULTIPART_ENVELOPE_FIXED_LENGTH_WITHOUT_HASH +
            if (canonicalHash == null) 0 else SHA256_BYTE_LENGTH
        val declaration = ByteBuffer.allocate(fixedLength + providerBytes.size)
            .put(flags.toByte())
            .putLong(expectedLength)
            .put(multipartLocationDigest(key))
            .apply {
                canonicalHash?.let { hash -> put(decodeCanonicalSha256(hash)) }
            }
            .put(providerBytes)
            .array()
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(declaration)
        val encoded = MULTIPART_UPLOAD_ID_PREFIX + payload
        require(encoded.length <= MAX_OPAQUE_UPLOAD_ID_LENGTH) {
            "S3-compatible service returned an upload id that exceeds the durable session limit."
        }
        return encoded
    }

    private fun decodeUploadId(opaqueValue: String): DecodedUploadId {
        if (!opaqueValue.startsWith(MULTIPART_UPLOAD_ID_PREFIX)) {
            return DecodedUploadId(
                providerValue = validatedProviderUploadId(opaqueValue),
                expectedLength = null,
                expectedHash = null,
                locationDigest = null,
            )
        }
        return try {
            require(opaqueValue.length <= MAX_OPAQUE_UPLOAD_ID_LENGTH)
            val payload = opaqueValue.substring(MULTIPART_UPLOAD_ID_PREFIX.length)
            require(payload.isNotEmpty())
            val declaration = Base64.getUrlDecoder().decode(payload)
            require(declaration.size >= MULTIPART_ENVELOPE_FIXED_LENGTH_WITHOUT_HASH + 1)
            val buffer = ByteBuffer.wrap(declaration)
            val flags = buffer.get().toInt()
            require(flags == 0 || flags == MULTIPART_ENVELOPE_HASH_FLAG)
            val expectedLength = buffer.long
            require(expectedLength >= 0)
            val locationDigest = ByteArray(SHA256_BYTE_LENGTH).also { digest -> buffer.get(digest) }
            val expectedHash = if (flags == MULTIPART_ENVELOPE_HASH_FLAG) {
                require(buffer.remaining() >= SHA256_BYTE_LENGTH + 1)
                ByteArray(SHA256_BYTE_LENGTH).also { hash -> buffer.get(hash) }
                    .let { hash -> SHA256_PREFIX + hash.toHex() }
            } else {
                null
            }
            val providerBytes = ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
            require(providerBytes.size in 1..MAX_ENVELOPED_PROVIDER_UPLOAD_ID_BYTES)
            val providerValue = String(providerBytes, StandardCharsets.UTF_8)
            require(providerValue.toByteArray(StandardCharsets.UTF_8).contentEquals(providerBytes))
            validatedProviderUploadId(providerValue)
            require(encodeUploadId(providerValue, locationDigest, expectedLength, expectedHash) == opaqueValue)
            DecodedUploadId(providerValue, expectedLength, expectedHash, locationDigest)
        } catch (_: RuntimeException) {
            // Released sessions persisted the provider id verbatim. A provider
            // was therefore free to issue an id that happens to begin with the
            // later v2 marker. Invalid v2 bytes must retain that legacy path;
            // legacy ids never participate in completed-object reconciliation.
            DecodedUploadId(
                providerValue = validatedProviderUploadId(opaqueValue),
                expectedLength = null,
                expectedHash = null,
                locationDigest = null,
            )
        }
    }

    /** Canonical re-encoding used while decoding, before the key binding is checked. */
    private fun encodeUploadId(
        providerValue: String,
        keyForDigestValidationOnly: ByteArray,
        expectedLength: Long,
        expectedHash: String?,
    ): String {
        require(keyForDigestValidationOnly.size == SHA256_BYTE_LENGTH)
        val providerBytes = providerValue.toByteArray(StandardCharsets.UTF_8)
        require(providerBytes.size in 1..MAX_ENVELOPED_PROVIDER_UPLOAD_ID_BYTES)
        val canonicalHash = expectedHash?.let(::validatedExpectedHash)
        val fixedLength = MULTIPART_ENVELOPE_FIXED_LENGTH_WITHOUT_HASH +
            if (canonicalHash == null) 0 else SHA256_BYTE_LENGTH
        val declaration = ByteBuffer.allocate(fixedLength + providerBytes.size)
            .put((if (canonicalHash == null) 0 else MULTIPART_ENVELOPE_HASH_FLAG).toByte())
            .putLong(expectedLength)
            .put(keyForDigestValidationOnly)
            .apply { canonicalHash?.let { hash -> put(decodeCanonicalSha256(hash)) } }
            .put(providerBytes)
            .array()
        return MULTIPART_UPLOAD_ID_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(declaration)
    }

    private fun multipartLocationDigest(key: String): ByteArray = newSha256().digest(
        buildString {
            append(configuration.storageType)
            append('\u0000')
            append(configuration.bucket)
            append('\u0000')
            append(key)
        }.toByteArray(StandardCharsets.UTF_8),
    )

    private fun decodeCanonicalSha256(value: String): ByteArray {
        val canonical = checkNotNull(validatedExpectedHash(value))
        val hex = canonical.substring(SHA256_PREFIX.length)
        return ByteArray(SHA256_BYTE_LENGTH) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun validatedProviderUploadId(value: String): String {
        require(
            value.isNotBlank() &&
                value.length <= MAX_PROVIDER_UPLOAD_ID_LENGTH &&
                value.none { character -> Character.isISOControl(character) },
        ) {
            "S3-compatible service returned an invalid multipart upload id."
        }
        return value
    }

    private fun integrityFailure(operation: S3StorageOperation, detail: String): S3StorageOperationException =
        s3ClassifiedFailure(
            operation = operation,
            category = S3StorageFailureCategory.INTEGRITY,
            retryable = false,
            detail = detail,
        )

    private class TranslatingDownloadInputStream(
        delegate: InputStream,
        private val operation: S3StorageOperation,
    ) : FilterInputStream(delegate) {
        override fun read(): Int = translate { super.read() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            translate { super.read(buffer, offset, length) }

        override fun skip(count: Long): Long = translate { super.skip(count) }

        override fun available(): Int = translate { super.available() }

        override fun close() {
            translate { super.close() }
        }

        private inline fun <T> translate(action: () -> T): T = try {
            action()
        } catch (failure: IOException) {
            throw s3StorageFailure(operation, failure)
        }
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
        private const val SINGLE_ATTEMPT = 1
        private const val BUFFER_SIZE = 8 * 1024
        private const val MAX_MULTIPART_PART_NUMBER = 10_000
        private const val RESERVED_METADATA_PREFIX = "fileweft-"
        private const val DECLARATION_VERSION_METADATA_KEY = "fileweft-declaration-version"
        private const val CONTENT_LENGTH_METADATA_KEY = "fileweft-content-length"
        private const val CONTENT_HASH_METADATA_KEY = "fileweft-content-sha256"
        private const val STREAM_DECLARATION_VERSION = "stream-v1"
        private const val MULTIPART_DECLARATION_VERSION = "multipart-v1"
        private const val MULTIPART_UPLOAD_ID_PREFIX = "fw-s3-v2."
        private const val MAX_OPAQUE_UPLOAD_ID_LENGTH = 512
        private const val MULTIPART_ENVELOPE_HASH_FLAG = 1
        private const val SHA256_BYTE_LENGTH = 32
        private const val MULTIPART_ENVELOPE_FIXED_LENGTH_WITHOUT_HASH = 1 + 8 + SHA256_BYTE_LENGTH
        // V013 persists 512 ASCII characters. In the worst case the v2
        // envelope uses a 9-character prefix plus Base64URL without padding
        // for flags (1), length (8), location SHA-256 (32), content SHA-256
        // (32), and 304 UTF-8 provider-id bytes: exactly 512 characters.
        private const val MAX_ENVELOPED_PROVIDER_UPLOAD_ID_BYTES = 304
        private const val MAX_PROVIDER_UPLOAD_ID_LENGTH = 2_048
        private const val MAX_ETAG_LENGTH = 1_024
        private const val MAX_CONTENT_TYPE_LENGTH = 255
        private const val MAX_USER_METADATA_VALUE_LENGTH = 2_048
        private const val VERSION_REVISION_KIND = "v1-version:"
        private const val ETAG_REVISION_KIND = "v1-etag:"
        private const val MAX_PROVIDER_REVISION_LENGTH = 1_024
        private const val MAX_ENCODED_REVISION_LENGTH = 1_500
        private val MINIMUM_PRESIGN_DURATION: Duration = Duration.ofSeconds(1)
        private val MAXIMUM_PRESIGN_DURATION: Duration = Duration.ofDays(7)
        private val OBJECT_KEY_PATTERN = Regex("objects/[0-9a-f]{64}/[0-9a-f]{32}")
        private val CONTENT_RANGE_PATTERN = Regex("bytes ([0-9]+)-([0-9]+)/(?:[0-9]+|\\*)")
        private val USER_METADATA_KEY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private const val SHA256_PREFIX = "sha256:"
        private val SHA256_HASH_PATTERN = Regex("sha256:[0-9a-fA-F]{64}")

        private fun newSha256(): MessageDigest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (failure: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is unavailable in this JVM.", failure)
        }

        private fun sha256(value: ByteArray): String = newSha256().digest(value).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

private class RevisionCondition(
    val kind: String,
    val value: String,
)

private class DecodedUploadId(
    val providerValue: String,
    val expectedLength: Long?,
    val expectedHash: String?,
    val locationDigest: ByteArray?,
) {
    val legacy: Boolean = expectedLength == null
}

private class ValidatedMultipartUpload(
    val key: String,
    val declaration: DecodedUploadId,
)

private class ListedMultipartPart(
    val part: MultipartPart,
    val contentLength: Long,
)

internal enum class S3StorageClientMode {
    RETRYING,
    SINGLE_ATTEMPT,
    NONE,
}

/**
 * Only side-effect-free reads and probes receive transparent SDK retries.
 * Every mutation is single-attempt so a consumed InputStream, duplicate
 * multipart initiation, repeated completion, or extra version delete marker
 * can never be hidden inside the SDK.
 */
internal fun s3ClientMode(operation: S3StorageOperation): S3StorageClientMode = when (operation) {
    S3StorageOperation.DOWNLOAD,
    S3StorageOperation.DOWNLOAD_RANGE,
    S3StorageOperation.READ_METADATA,
    S3StorageOperation.EXISTS,
    S3StorageOperation.LIST_MULTIPART_PARTS,
    S3StorageOperation.VERIFY_COMPLETED_OBJECT,
    S3StorageOperation.CHECKSUM_COMPLETED_OBJECT,
    S3StorageOperation.CHECK_BUCKET,
    -> S3StorageClientMode.RETRYING

    S3StorageOperation.UPLOAD,
    S3StorageOperation.DELETE,
    S3StorageOperation.BEGIN_MULTIPART_UPLOAD,
    S3StorageOperation.UPLOAD_PART,
    S3StorageOperation.COMPLETE_MULTIPART_UPLOAD,
    S3StorageOperation.ABORT_MULTIPART_UPLOAD,
    S3StorageOperation.CREATE_BUCKET,
    -> S3StorageClientMode.SINGLE_ATTEMPT

    S3StorageOperation.PRESIGN_DOWNLOAD,
    S3StorageOperation.CLOSE,
    -> S3StorageClientMode.NONE
}

private fun isNoSuchKey(failure: S3Exception): Boolean =
    s3ErrorCode(failure) == "NOSUCHKEY"

private fun isNoSuchBucket(failure: S3Exception): Boolean =
    s3ErrorCode(failure) == "NOSUCHBUCKET"

private fun isNoSuchUpload(failure: S3Exception): Boolean =
    s3ErrorCode(failure) == "NOSUCHUPLOAD"

private fun isAmbiguousObjectNotFound(failure: S3Exception): Boolean {
    val code = s3ErrorCode(failure)
    return failure.statusCode() == 404 && (code.isEmpty() || code == "NOTFOUND")
}

private fun s3ErrorCode(failure: S3Exception): String =
    failure.awsErrorDetails()?.errorCode().orEmpty().uppercase(Locale.ROOT)

internal fun isDefinitiveMultipartCompletionRejection(failure: S3Exception): Boolean =
    isDefinitiveMultipartCompletionRejection(s3ErrorCode(failure))

// S3 explicitly permits CompleteMultipartUpload to return HTTP 200 and place
// the real failure in an XML Error body. The exact provider code, rather than
// the transport status, is therefore the authoritative rejection signal.
internal fun isDefinitiveMultipartCompletionRejection(
    errorCode: String?,
): Boolean = errorCode.orEmpty().uppercase(Locale.ROOT) in
    DEFINITIVE_MULTIPART_COMPLETION_REJECTION_CODES

private val DEFINITIVE_MULTIPART_COMPLETION_REJECTION_CODES: Set<String> = setOf(
    "ENTITYTOOSMALL",
    "INVALIDPART",
    "INVALIDPARTORDER",
)
