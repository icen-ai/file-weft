package ai.icen.fw.adapter.oss

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.ConditionalRangedStorageAdapter
import ai.icen.fw.spi.storage.MultipartCompletionRejectedException
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.PresignedUploadFinalization
import ai.icen.fw.spi.storage.PresignedUploadFinalizeRequest
import ai.icen.fw.spi.storage.PresignedUploadCleanupRequest
import ai.icen.fw.spi.storage.PresignedUploadGrant
import ai.icen.fw.spi.storage.PresignedUploadGrantRequest
import ai.icen.fw.spi.storage.PresignedUploadReissueRequest
import ai.icen.fw.spi.storage.PresignedUploadStorageAdapter
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
import ai.icen.fw.spi.storage.StorageContentChecksum
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

/** Production Alibaba Cloud OSS reference adapter backed by the stable Java SDK V1. */
class OssStorageAdapter private constructor(
    private val configuration: OssStorageConfiguration,
    private val clientPolicy: OssStorageClientPolicy,
    private val client: OssClientFacade,
    private val clock: Clock,
) : StorageAdapter,
    RangedStorageAdapter,
    ConditionalRangedStorageAdapter,
    StorageMetadataAdapter,
    ResumableMultipartStorageAdapter,
    PresignedUploadStorageAdapter,
    AutoCloseable {

    constructor(configuration: OssStorageConfiguration) :
        this(configuration, OssStorageClientPolicy())

    constructor(configuration: OssStorageConfiguration, clientPolicy: OssStorageClientPolicy) :
        this(configuration, clientPolicy, AlibabaOssV1Client(configuration, clientPolicy), Clock.systemUTC())

    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
        val expectedHash = validatedExpectedHash(request.contentHash)
        val providerMetadata = providerMetadata(request.metadata, expectedHash, request.contentLength)
        val contentType = validatedContentType(request.contentType)
        val location = newObjectLocation(request.tenantId)
        val measured = DigestingInputStream(content)
        var providerStored = false
        var storedVersionId: String? = null
        try {
            val result = execute(OssStorageOperation.UPLOAD) {
                client.putObject(
                    location.path,
                    request.contentLength,
                    contentType,
                    providerMetadata,
                    measured,
                )
            }
            providerStored = true
            val evidence = requiredWriteEvidence(result, OssStorageOperation.UPLOAD)
            storedVersionId = evidence.versionId
            verifyExactLength(measured, request.contentLength, OssStorageOperation.UPLOAD)
            val actualHash = measured.contentHash()
            if (expectedHash != null && expectedHash != actualHash) {
                throw integrityFailure(OssStorageOperation.UPLOAD, "Uploaded content hash did not match its declaration.")
            }
            return StoredObject(
                boundLocation(location.path, evidence.versionId),
                request.contentLength,
                contentType,
                actualHash,
            )
        } catch (failure: Throwable) {
            // A transport/service failure can mean OSS committed the PUT even
            // though this client never observed the response. Deleting in that
            // outcome-unknown window could race the PUT or remove an object
            // whose overwrite guard correctly rejected this request.
            if (providerStored) {
                try {
                    deleteReference(
                        OssObjectReference(location.path, storedVersionId),
                        OssStorageOperation.DELETE,
                    )
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    override fun download(location: StorageObjectLocation): StorageDownload {
        val reference = validatedReference(location)
        val condition = reference.versionId?.let { OssRevisionCondition(OssRevisionKind.VERSION_ID, it) }
        val response = execute(OssStorageOperation.DOWNLOAD) {
            client.getObject(reference.key, condition = condition)
        }
        try {
            if (
                response.statusCode != HTTP_OK ||
                response.contentLength < 0 ||
                (condition != null && !responseRevisionMatches(response, condition))
            ) {
                throw integrityFailure(OssStorageOperation.DOWNLOAD, "OSS returned invalid full-object response metadata.")
            }
            return StorageDownload(
                TranslatingDownloadInputStream(response, OssStorageOperation.DOWNLOAD),
                response.contentLength,
                response.contentType?.takeIf(String::isNotBlank),
            )
        } catch (failure: Throwable) {
            abortResponse(response, OssStorageOperation.DOWNLOAD, failure)
            throw failure
        }
    }

    override fun downloadRange(location: StorageObjectLocation, offset: Long, length: Long): StorageDownload {
        require(offset >= 0) { "Storage range offset must not be negative." }
        require(length > 0) { "Storage range length must be positive." }
        val revision = requireNotNull(metadata(location).revision) { "OSS object revision is unavailable." }
        return downloadRange(StorageRangeRequest(location, offset, length, revision))
    }

    override fun downloadRange(request: StorageRangeRequest): StorageDownload {
        val requestedEnd = Math.addExact(request.offset, request.length - 1)
        val reference = validatedReference(request.location)
        val requestedRevision = decodeRevision(request.expectedRevision)
        val expected = reference.versionId?.let { boundVersion ->
            require(
                requestedRevision.kind == OssRevisionKind.VERSION_ID && requestedRevision.value == boundVersion,
            ) { "Expected storage revision does not match the bound OSS object version." }
            OssRevisionCondition(OssRevisionKind.VERSION_ID, boundVersion)
        } ?: requestedRevision
        val response = execute(OssStorageOperation.DOWNLOAD_RANGE) {
            client.getObject(reference.key, request.offset, requestedEnd, expected)
        }
        try {
            if (response.statusCode != HTTP_PARTIAL_CONTENT) {
                throw integrityFailure(OssStorageOperation.DOWNLOAD_RANGE, "OSS did not return a partial response.")
            }
            val actualLength = validatedRangeLength(response, request.offset, requestedEnd)
            if (!responseRevisionMatches(response, expected)) {
                throw integrityFailure(OssStorageOperation.DOWNLOAD_RANGE, "OSS returned a different object revision.")
            }
            return StorageDownload(
                TranslatingDownloadInputStream(response, OssStorageOperation.DOWNLOAD_RANGE),
                actualLength,
                response.contentType?.takeIf(String::isNotBlank),
            )
        } catch (failure: Throwable) {
            abortResponse(response, OssStorageOperation.DOWNLOAD_RANGE, failure)
            throw failure
        }
    }

    override fun metadata(location: StorageObjectLocation): StorageObjectMetadata {
        val reference = validatedReference(location)
        val canonical = canonicalLocation(reference)
        val head = execute(OssStorageOperation.METADATA) {
            client.headObject(reference.key, reference.versionId)
        }
        if (head.contentLength < 0) {
            throw integrityFailure(OssStorageOperation.METADATA, "OSS returned a negative object length.")
        }
        if (reference.versionId != null && head.versionId != reference.versionId) {
            throw integrityFailure(OssStorageOperation.METADATA, "OSS returned a different bound object version.")
        }
        val revision = head.versionId?.takeIf(String::isNotBlank)
            ?.let { encodeRevision(OssRevisionKind.VERSION_ID, it) }
            ?: head.eTag?.takeIf(String::isNotBlank)?.let { encodeRevision(OssRevisionKind.ETAG, it) }
            ?: throw integrityFailure(OssStorageOperation.METADATA, "OSS returned no strong object revision.")
        val canonicalMetadata = canonicalProviderMetadata(head.metadata)
        return StorageObjectMetadata(
            location = canonical,
            contentLength = head.contentLength,
            contentType = head.contentType?.takeIf(String::isNotBlank),
            contentHash = canonicalMetadata[CONTENT_HASH_METADATA_KEY],
            revision = revision,
            metadata = canonicalMetadata.filterKeys { !it.startsWith(RESERVED_METADATA_PREFIX) },
            lastModifiedTime = head.lastModifiedTime,
        )
    }

    override fun delete(location: StorageObjectLocation) {
        deleteReference(validatedReference(location), OssStorageOperation.DELETE)
    }

    override fun exists(location: StorageObjectLocation): Boolean = try {
        val reference = validatedReference(location)
        val head = client.headObject(reference.key, reference.versionId)
        if (reference.versionId != null && head.versionId != reference.versionId) {
            throw integrityFailure(OssStorageOperation.EXISTS, "OSS returned a different bound object version.")
        }
        true
    } catch (failure: Exception) {
        if (ossServiceErrorCode(failure) in IDEMPOTENT_DELETE_MISSING_CODES) false
        else throw ossStorageFailure(OssStorageOperation.EXISTS, failure)
    }

    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI {
        require(expiresIn >= MINIMUM_PRESIGN_DURATION && expiresIn <= MAXIMUM_PRESIGN_DURATION) {
            "Access URL expiration must be between two seconds and seven days."
        }
        val now = clock.millis()
        val expiresAt = Math.addExact(now, expiresIn.toMillis())
        val credentials = signingCredentials(now, expiresAt, OssStorageOperation.PRESIGN_DOWNLOAD)
        val reference = validatedReference(location)
        val url = execute(OssStorageOperation.PRESIGN_DOWNLOAD) {
            client.presignGetObject(reference.key, reference.versionId, expiresAt, credentials)
        }
        return validatedPresignedUrl(
            url,
            reference.key,
            credentials.securityToken != null,
            expiresIn,
            reference.versionId,
        )
    }

    override fun createUploadGrant(request: PresignedUploadGrantRequest): PresignedUploadGrant {
        require(
            request.expiresIn >= MINIMUM_PRESIGN_DURATION &&
                request.expiresIn <= MAXIMUM_DIRECT_UPLOAD_PRESIGN_DURATION,
        ) { "Direct upload expiration must be between two seconds and fifteen minutes." }
        require(request.contentLength <= MAXIMUM_DIRECT_UPLOAD_LENGTH) {
            "OSS direct PUT content length must not exceed 5 GiB."
        }
        val contentType = requireNotNull(validatedContentType(request.contentType))
        val contentHash = requireNotNull(validatedExpectedHash(request.contentHash))
        val contentMd5 = validatedContentMd5(request.checksum)
        val location = newObjectLocation(request.tenantId)
        val providerMetadata = providerMetadata(
            userMetadata = request.metadata,
            contentHash = contentHash,
            contentLength = request.contentLength,
            tenantId = request.tenantId,
            bindingId = request.bindingId,
        )
        val now = clock.millis()
        val expiresAt = Math.addExact(now, request.expiresIn.toMillis())
        val credentials = signingCredentials(now, expiresAt, OssStorageOperation.PRESIGN_UPLOAD)
        val url = execute(OssStorageOperation.PRESIGN_UPLOAD) {
            client.presignPutObject(
                key = location.path,
                expiresAt = expiresAt,
                contentType = contentType,
                contentMd5 = contentMd5,
                metadata = providerMetadata,
                signingCredentials = credentials,
            )
        }
        val requiredHeaders = directUploadRequiredHeaders(contentType, contentMd5, providerMetadata)
        return PresignedUploadGrant(
            location = location,
            uploadUri = validatedPresignedUrl(
                url,
                location.path,
                credentials.securityToken != null,
                request.expiresIn,
            ),
            requiredHeaders = requiredHeaders,
            expiresAt = expiresAt,
        )
    }

    override fun reissueUploadGrant(request: PresignedUploadReissueRequest): PresignedUploadGrant {
        val key = validatedTenantKey(request.location, request.tenantId)
        require(request.contentLength <= MAXIMUM_DIRECT_UPLOAD_LENGTH) {
            "OSS direct PUT content length must not exceed 5 GiB."
        }
        val contentType = requireNotNull(validatedContentType(request.contentType))
        val contentHash = requireNotNull(validatedExpectedHash(request.contentHash))
        val contentMd5 = validatedContentMd5(request.checksum)
        val providerMetadata = providerMetadata(
            userMetadata = request.metadata,
            contentHash = contentHash,
            contentLength = request.contentLength,
            tenantId = request.tenantId,
            bindingId = request.bindingId,
        )
        val requiredHeaders = directUploadRequiredHeaders(contentType, contentMd5, providerMetadata)
        require(requiredHeaders == request.requiredHeaders) {
            "OSS cannot reissue a direct upload whose durable signed headers changed."
        }
        val now = clock.millis()
        val remaining = Duration.ofMillis(Math.subtractExact(request.expiresAt, now))
        require(
            remaining >= MINIMUM_PRESIGN_DURATION &&
                remaining <= MAXIMUM_DIRECT_UPLOAD_PRESIGN_DURATION,
        ) { "Direct upload reissue must preserve a deadline between two seconds and fifteen minutes." }
        val credentials = signingCredentials(
            now,
            request.expiresAt,
            OssStorageOperation.REISSUE_PRESIGNED_UPLOAD,
        )
        val url = execute(OssStorageOperation.REISSUE_PRESIGNED_UPLOAD) {
            client.presignPutObject(
                key = key,
                expiresAt = request.expiresAt,
                contentType = contentType,
                contentMd5 = contentMd5,
                metadata = providerMetadata,
                signingCredentials = credentials,
            )
        }
        return PresignedUploadGrant(
            location = StorageObjectLocation(configuration.storageType, key),
            uploadUri = validatedPresignedUrl(
                url,
                key,
                credentials.securityToken != null,
                remaining,
            ),
            requiredHeaders = requiredHeaders,
            expiresAt = request.expiresAt,
        )
    }

    override fun finalizeUpload(request: PresignedUploadFinalizeRequest): PresignedUploadFinalization {
        val key = validatedTenantKey(request.location, request.tenantId)
        val sourceLocation = StorageObjectLocation(configuration.storageType, key)
        val expectedContentType = requireNotNull(validatedContentType(request.contentType))
        val expectedContentHash = requireNotNull(validatedExpectedHash(request.contentHash))
        val expectedContentMd5 = validatedContentMd5(request.checksum)
        val expectedMetadata = providerMetadata(
            userMetadata = request.metadata,
            contentHash = expectedContentHash,
            contentLength = request.contentLength,
            tenantId = request.tenantId,
            bindingId = request.bindingId,
        )
        val head = execute(OssStorageOperation.FINALIZE_PRESIGNED_UPLOAD) { client.headObject(key) }
        val actualMetadata = canonicalProviderMetadata(head.metadata)
        if (
            head.contentLength != request.contentLength ||
            head.contentType != expectedContentType ||
            head.contentMd5 != expectedContentMd5 ||
            head.crc64ecma == null ||
            actualMetadata != expectedMetadata
        ) {
            throw integrityFailure(
                OssStorageOperation.FINALIZE_PRESIGNED_UPLOAD,
                "OSS direct-upload HEAD evidence did not match its durable grant.",
            )
        }
        val versionId = head.versionId?.takeIf(String::isNotBlank)
            ?.let(::validatedRevisionValue)
            ?: throw integrityFailure(
                OssStorageOperation.FINALIZE_PRESIGNED_UPLOAD,
                "OSS direct upload requires an immutable version id; enable bucket versioning.",
            )
        val revision = encodeRevision(OssRevisionKind.VERSION_ID, versionId)
        val actualContentHash = calculateObjectHash(key, head)
        if (actualContentHash != expectedContentHash) {
            throw integrityFailure(
                OssStorageOperation.FINALIZE_PRESIGNED_UPLOAD,
                "OSS direct-upload content did not match its declared SHA-256.",
            )
        }
        return PresignedUploadFinalization(
            tenantId = request.tenantId,
            bindingId = request.bindingId,
            sourceLocation = sourceLocation,
            storedObject = StoredObject(
                location = boundLocation(key, versionId),
                contentLength = head.contentLength,
                contentType = expectedContentType,
                contentHash = actualContentHash,
            ),
            revision = revision,
            checksum = StorageContentChecksum(CHECKSUM_ALGORITHM_MD5, expectedContentMd5),
            // HEAD was compared with the canonical provider representation
            // above. Return the exact application declaration rather than the
            // provider's lower-cased header keys.
            metadata = request.metadata,
        )
    }

    override fun cleanupUpload(request: PresignedUploadCleanupRequest) {
        // validatedTenantKey rejects a revision-bound final location. The
        // application additionally selects only sessions with no finalization
        // evidence and waits for the original signed PUT deadline.
        val key = validatedTenantKey(request.location, request.tenantId)
        val expectedTenantDigest = sha256(request.tenantId.value.toByteArray(StandardCharsets.UTF_8))
        val expectedBindingDigest = sha256(request.bindingId.value.toByteArray(StandardCharsets.UTF_8))
        repeat(MAX_CLEANUP_VERSIONS_PER_CALL) {
            val head = try {
                client.headObject(key)
            } catch (failure: Exception) {
                if (ossServiceErrorCode(failure) in IDEMPOTENT_DELETE_MISSING_CODES) return
                throw ossStorageFailure(OssStorageOperation.CLEANUP_PRESIGNED_UPLOAD, failure)
            }
            val metadata = canonicalProviderMetadata(head.metadata)
            if (
                metadata[TENANT_DIGEST_METADATA_KEY] != expectedTenantDigest ||
                metadata[UPLOAD_BINDING_METADATA_KEY] != expectedBindingDigest
            ) {
                throw integrityFailure(
                    OssStorageOperation.CLEANUP_PRESIGNED_UPLOAD,
                    "OSS staging cleanup authority did not match its durable tenant and binding.",
                )
            }
            val exactVersion = head.versionId?.takeIf(String::isNotBlank)?.let(::validatedRevisionValue)
            try {
                client.deleteObject(key, exactVersion)
            } catch (failure: Exception) {
                if (ossServiceErrorCode(failure) !in IDEMPOTENT_DELETE_MISSING_CODES) {
                    throw ossStorageFailure(OssStorageOperation.CLEANUP_PRESIGNED_UPLOAD, failure)
                }
            }
        }
        try {
            client.headObject(key)
        } catch (failure: Exception) {
            if (ossServiceErrorCode(failure) in IDEMPOTENT_DELETE_MISSING_CODES) return
            throw ossStorageFailure(OssStorageOperation.CLEANUP_PRESIGNED_UPLOAD, failure)
        }
        throw ossClassifiedFailure(
            OssStorageOperation.CLEANUP_PRESIGNED_UPLOAD,
            OssStorageFailureCategory.UNAVAILABLE,
            retryable = true,
            detail = "OSS staging cleanup exceeded its bounded version batch.",
        )
    }

    override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload {
        val contentType = validatedContentType(request.contentType)
        val metadata = providerMetadata(
            request.metadata,
            validatedExpectedHash(request.contentHash),
            request.contentLength,
        )
        val location = newObjectLocation(request.tenantId)
        val uploadId = execute(OssStorageOperation.BEGIN_MULTIPART_UPLOAD) {
            client.initiateMultipartUpload(location.path, contentType, metadata)
        }
        return MultipartUpload(Identifier(validatedUploadId(uploadId)), location)
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
        require(contentLength > 0) { "Part content length must be positive." }
        val measured = DigestingInputStream(content)
        val eTag = execute(OssStorageOperation.UPLOAD_PART) {
            client.uploadPart(
                validatedUnboundKey(upload.location),
                validatedUploadId(upload.uploadId.value),
                partNumber,
                contentLength,
                measured,
            )
        }
        verifyExactLength(measured, contentLength, OssStorageOperation.UPLOAD_PART)
        return MultipartPart(partNumber, validatedETag(eTag))
    }

    override fun listUploadedParts(upload: MultipartUpload): List<MultipartPart> {
        val key = validatedUnboundKey(upload.location)
        val uploadId = validatedUploadId(upload.uploadId.value)
        val byNumber = LinkedHashMap<Int, MultipartPart>()
        var marker: Int? = null
        var pageCount = 0
        do {
            if (++pageCount > MAX_LIST_PART_PAGES) {
                throw integrityFailure(OssStorageOperation.LIST_UPLOADED_PARTS, "OSS multipart pagination exceeded its bound.")
            }
            val page = execute(OssStorageOperation.LIST_UPLOADED_PARTS) {
                client.listParts(key, uploadId, marker, LIST_PART_PAGE_SIZE)
            }
            page.parts.forEach { listed ->
                if (listed.partNumber !in 1..MAX_MULTIPART_PART_NUMBER) {
                    throw integrityFailure(OssStorageOperation.LIST_UPLOADED_PARTS, "OSS returned an invalid part number.")
                }
                val part = MultipartPart(listed.partNumber, validatedETag(listed.eTag))
                if (byNumber.put(part.partNumber, part) != null) {
                    throw integrityFailure(OssStorageOperation.LIST_UPLOADED_PARTS, "OSS returned duplicate multipart parts.")
                }
            }
            if (!page.truncated) break
            val next = page.nextPartNumberMarker
                ?: throw integrityFailure(OssStorageOperation.LIST_UPLOADED_PARTS, "OSS omitted a multipart page marker.")
            if (marker != null && next <= marker!!) {
                throw integrityFailure(OssStorageOperation.LIST_UPLOADED_PARTS, "OSS multipart pagination did not advance.")
            }
            marker = next
        } while (true)
        return byNumber.values.sortedBy(MultipartPart::partNumber)
    }

    override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject {
        val requested = validatedCompletionParts(parts)
        val key = validatedUnboundKey(upload.location)
        val uploadId = validatedUploadId(upload.uploadId.value)
        val authoritative = try {
            listUploadedParts(upload)
        } catch (failure: OssStorageOperationException) {
            if (failure.category == OssStorageFailureCategory.NOT_FOUND) {
                reconcileCompletedMultipartUpload(key, failure)?.let { return it }
            }
            throw failure
        }
        if (requested != authoritative) {
            throw MultipartCompletionRejectedException(
                cause = integrityFailure(
                    OssStorageOperation.COMPLETE_MULTIPART_UPLOAD,
                    "Multipart acknowledgements are no longer authoritative.",
                ),
            )
        }
        val result = try {
            client.completeMultipartUpload(
                key,
                uploadId,
                requested.map { OssCompletedPart(it.partNumber, it.eTag) },
            )
        } catch (failure: Exception) {
            val translated = ossStorageFailure(OssStorageOperation.COMPLETE_MULTIPART_UPLOAD, failure)
            if (ossServiceErrorCode(failure) in DEFINITIVE_COMPLETION_REJECTIONS) {
                throw MultipartCompletionRejectedException(cause = translated)
            }
            if (
                translated.retryable ||
                ossServiceErrorCode(failure) == NO_SUCH_UPLOAD
            ) {
                reconcileCompletedMultipartUpload(key, translated)?.let { return it }
            }
            throw translated
        }
        val evidence = requiredWriteEvidence(result, OssStorageOperation.COMPLETE_MULTIPART_UPLOAD)
        return verifiedCompletedMultipartObject(key, evidence.versionId, evidence.eTag)
    }

    private fun verifiedCompletedMultipartObject(
        key: String,
        expectedVersionId: String?,
        expectedETag: String? = null,
    ): StoredObject {
        val head = execute(OssStorageOperation.VERIFY_COMPLETED_OBJECT) {
            client.headObject(key, expectedVersionId)
        }
        val actualVersionId = head.versionId?.takeIf(String::isNotBlank)?.let(::validatedRevisionValue)
            ?: throw integrityFailure(
                OssStorageOperation.VERIFY_COMPLETED_OBJECT,
                "Completed OSS object has no immutable version id; enable bucket versioning.",
            )
        if (expectedVersionId != null && actualVersionId != expectedVersionId) {
            throw integrityFailure(
                OssStorageOperation.VERIFY_COMPLETED_OBJECT,
                "Completed OSS object did not match the completion response version.",
            )
        }
        if (expectedETag != null && head.eTag != expectedETag) {
            throw integrityFailure(
                OssStorageOperation.VERIFY_COMPLETED_OBJECT,
                "Completed OSS object did not match the completion response ETag.",
            )
        }
        val metadata = canonicalProviderMetadata(head.metadata)
        val declaredLength = metadata[CONTENT_LENGTH_METADATA_KEY]?.toLongOrNull()
        if (head.contentLength < 0 || declaredLength == null || declaredLength != head.contentLength) {
            throw integrityFailure(
                OssStorageOperation.VERIFY_COMPLETED_OBJECT,
                "Completed OSS object did not match its declared length.",
            )
        }
        val actualHash = calculateObjectHash(key, head)
        val expectedHash = metadata[CONTENT_HASH_METADATA_KEY]
        if (expectedHash != null && expectedHash != actualHash) {
            throw integrityFailure(
                OssStorageOperation.VERIFY_COMPLETED_OBJECT,
                "Completed OSS object did not match its declared SHA-256.",
            )
        }
        return StoredObject(
            boundLocation(key, actualVersionId),
            head.contentLength,
            head.contentType?.takeIf(String::isNotBlank),
            actualHash,
        )
    }

    /**
     * A retryable completion failure can mean OSS committed the object but the
     * response (including its version id) was lost. The opaque random key is
     * unique to this upload, and the final HEAD metadata plus a conditional
     * full-object hash must both validate before recovery is accepted.
     */
    private fun reconcileCompletedMultipartUpload(
        key: String,
        original: OssStorageOperationException,
    ): StoredObject? = try {
        verifiedCompletedMultipartObject(key, null)
    } catch (reconciliationFailure: Exception) {
        original.addSuppressed(
            if (reconciliationFailure is OssStorageOperationException) {
                reconciliationFailure
            } else {
                ossStorageFailure(OssStorageOperation.VERIFY_COMPLETED_OBJECT, reconciliationFailure)
            },
        )
        null
    }

    override fun abortMultipartUpload(upload: MultipartUpload) {
        try {
            client.abortMultipartUpload(
                validatedUnboundKey(upload.location),
                validatedUploadId(upload.uploadId.value),
            )
        } catch (failure: Exception) {
            if (ossServiceErrorCode(failure) != NO_SUCH_UPLOAD) {
                throw ossStorageFailure(OssStorageOperation.ABORT_MULTIPART_UPLOAD, failure)
            }
        }
    }

    @JvmSynthetic
    internal fun checkBucketAccess() {
        execute(OssStorageOperation.CHECK_BUCKET, client::checkBucketAccess)
    }

    @JvmSynthetic
    internal fun diagnosticEvidence(): Map<String, String> = linkedMapOf(
        "storageType" to configuration.storageType,
        "endpointScheme" to configuration.endpoint.scheme.lowercase(Locale.ROOT),
        "region" to configuration.region,
        "pathStyle" to configuration.usePathStyle.toString(),
        "cname" to configuration.useCName.toString(),
        "bucketFingerprint" to safeFingerprint(configuration.bucket),
        "signatureVersion" to "v4",
        "sdkFamily" to "aliyun-oss-java-v1",
        "connectionTimeoutMillis" to clientPolicy.connectionTimeout.toMillis().toString(),
        "socketTimeoutMillis" to clientPolicy.socketTimeout.toMillis().toString(),
        "requestTimeoutMillis" to clientPolicy.requestTimeout.toMillis().toString(),
        "credentialExpirySafetyWindowMillis" to
            clientPolicy.credentialExpirySafetyWindow.toMillis().toString(),
        "maxAttempts" to clientPolicy.maxAttempts.toString(),
    )

    override fun close() {
        try {
            client.close()
        } catch (failure: Exception) {
            throw ossStorageFailure(OssStorageOperation.CLOSE, failure)
        }
    }

    private fun calculateObjectHash(key: String, head: OssHeadObjectResponse): String {
        val revision = head.versionId?.takeIf(String::isNotBlank)
            ?.let { OssRevisionCondition(OssRevisionKind.VERSION_ID, it) }
            ?: head.eTag?.takeIf(String::isNotBlank)?.let { OssRevisionCondition(OssRevisionKind.ETAG, it) }
            ?: throw integrityFailure(OssStorageOperation.CHECKSUM_COMPLETED_OBJECT, "OSS returned no strong revision.")
        val response = execute(OssStorageOperation.CHECKSUM_COMPLETED_OBJECT) {
            client.getObject(key, condition = revision)
        }
        var translatedFailure: Throwable? = null
        try {
            if (
                response.statusCode != HTTP_OK ||
                response.contentLength != head.contentLength ||
                !responseRevisionMatches(response, revision)
            ) {
                throw integrityFailure(
                    OssStorageOperation.CHECKSUM_COMPLETED_OBJECT,
                    "OSS changed the object during checksum verification.",
                )
            }
            val measured = DigestingInputStream(response.body)
            val buffer = ByteArray(BUFFER_SIZE)
            while (measured.read(buffer) != -1) {
                // DigestingInputStream performs the bounded streaming work.
            }
            if (measured.contentLength != head.contentLength) {
                throw integrityFailure(
                    OssStorageOperation.CHECKSUM_COMPLETED_OBJECT,
                    "OSS checksum stream length was inconsistent.",
                )
            }
            return measured.contentHash()
        } catch (failure: Exception) {
            val translated = ossStorageFailure(OssStorageOperation.CHECKSUM_COMPLETED_OBJECT, failure)
            translatedFailure = translated
            throw translated
        } finally {
            try {
                response.close()
            } catch (closeFailure: Exception) {
                val translatedClose = ossStorageFailure(
                    OssStorageOperation.CHECKSUM_COMPLETED_OBJECT,
                    closeFailure,
                )
                if (translatedFailure == null) throw translatedClose
                translatedFailure.addSuppressed(translatedClose)
            }
        }
    }

    private fun validatedCompletionParts(parts: List<MultipartPart>): List<MultipartPart> {
        require(parts.isNotEmpty()) { "At least one multipart upload part is required." }
        require(parts.size <= MAX_MULTIPART_PART_NUMBER) { "Multipart upload contains too many parts." }
        val sorted = parts.map { part -> MultipartPart(part.partNumber, validatedETag(part.eTag)) }
            .sortedBy(MultipartPart::partNumber)
        require(sorted.map(MultipartPart::partNumber).distinct().size == sorted.size) {
            "Multipart upload parts must not contain duplicates."
        }
        return sorted
    }

    private fun validatedRangeLength(response: OssObjectResponse, requestedStart: Long, requestedEnd: Long): Long {
        val match = response.contentRange?.let(CONTENT_RANGE_PATTERN::matchEntire)
            ?: throw integrityFailure(OssStorageOperation.DOWNLOAD_RANGE, "OSS omitted Content-Range.")
        val actualStart = match.groupValues[1].toLong()
        val actualEnd = match.groupValues[2].toLong()
        if (actualStart != requestedStart || actualEnd !in actualStart..requestedEnd) {
            throw integrityFailure(OssStorageOperation.DOWNLOAD_RANGE, "OSS returned bytes outside the requested range.")
        }
        val total = match.groupValues[3].takeIf { it != "*" }?.toLong()
        if (total != null && (total <= actualEnd || requestedStart >= total)) {
            throw integrityFailure(OssStorageOperation.DOWNLOAD_RANGE, "OSS returned an invalid range total.")
        }
        val actualLength = Math.addExact(Math.subtractExact(actualEnd, actualStart), 1)
        if (response.contentLength != actualLength) {
            throw integrityFailure(OssStorageOperation.DOWNLOAD_RANGE, "OSS returned inconsistent range length.")
        }
        return actualLength
    }

    private fun responseRevisionMatches(response: OssObjectResponse, expected: OssRevisionCondition): Boolean =
        when (expected.kind) {
            OssRevisionKind.VERSION_ID -> response.versionId == expected.value
            OssRevisionKind.ETAG -> response.eTag == expected.value
        }

    private fun requiredWriteEvidence(
        result: OssWriteResult,
        operation: OssStorageOperation,
    ): OssWriteEvidence {
        val versionId = result.versionId?.takeIf(String::isNotBlank)?.let(::validatedRevisionValue)
            ?: throw integrityFailure(
                operation,
                "OSS write response has no immutable version id; enable bucket versioning.",
            )
        val eTag = result.eTag?.let(::validatedETag)
            ?: throw integrityFailure(operation, "OSS write response omitted its ETag.")
        return OssWriteEvidence(versionId, eTag)
    }

    private data class OssWriteEvidence(
        val versionId: String,
        val eTag: String,
    )

    private fun encodeRevision(kind: OssRevisionKind, value: String): String {
        val validated = validatedRevisionValue(value)
        val prefix = if (kind == OssRevisionKind.VERSION_ID) VERSION_REVISION_PREFIX else ETAG_REVISION_PREFIX
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(validated.toByteArray(StandardCharsets.UTF_8))
        require(payload.length <= MAX_ENCODED_REVISION_LENGTH) {
            "OSS revision cannot be represented canonically."
        }
        return prefix + payload
    }

    private fun decodeRevision(encoded: String): OssRevisionCondition = try {
        val kind: OssRevisionKind
        val prefix: String
        when {
            encoded.startsWith(VERSION_REVISION_PREFIX) -> {
                kind = OssRevisionKind.VERSION_ID
                prefix = VERSION_REVISION_PREFIX
            }
            encoded.startsWith(ETAG_REVISION_PREFIX) -> {
                kind = OssRevisionKind.ETAG
                prefix = ETAG_REVISION_PREFIX
            }
            else -> throw IllegalArgumentException()
        }
        val payload = encoded.substring(prefix.length)
        require(payload.isNotEmpty() && payload.length <= MAX_ENCODED_REVISION_LENGTH)
        val value = validatedRevisionValue(
            String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8),
        )
        require(encodeRevision(kind, value) == encoded)
        OssRevisionCondition(kind, value)
    } catch (_: RuntimeException) {
        throw IllegalArgumentException("Expected storage revision is invalid.")
    }

    private fun validatedRevisionValue(value: String): String = value.also {
        require(it.isNotBlank() && it.length <= MAX_PROVIDER_TOKEN_LENGTH) { "OSS revision is invalid." }
        require(it.all(::isVisibleAscii)) { "OSS revision is invalid." }
    }

    private fun validatedUploadId(value: String): String = value.also {
        require(it.isNotBlank() && it.length <= MAX_PROVIDER_TOKEN_LENGTH) { "OSS upload id is invalid." }
        require(it.all(::isVisibleAscii)) { "OSS upload id is invalid." }
    }

    private fun validatedETag(value: String): String = value.also {
        require(it.isNotBlank() && it.length <= MAX_PROVIDER_TOKEN_LENGTH) { "OSS part ETag is invalid." }
        require(it.all(::isVisibleAscii)) { "OSS part ETag is invalid." }
    }

    private fun validatedExpectedHash(value: String?): String? = value?.also {
        require(CONTENT_HASH_PATTERN.matches(it)) { "OSS content hash must be a lowercase SHA-256 value." }
    }

    private fun validatedContentMd5(checksum: StorageContentChecksum): String {
        require(checksum.algorithm == CHECKSUM_ALGORITHM_MD5) {
            "OSS direct upload requires an MD5 checksum that the provider can validate."
        }
        val decoded = try {
            Base64.getDecoder().decode(checksum.value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("OSS Content-MD5 must be canonical Base64.")
        }
        require(decoded.size == MD5_DIGEST_LENGTH && Base64.getEncoder().encodeToString(decoded) == checksum.value) {
            "OSS Content-MD5 must be canonical Base64 for exactly 128 bits."
        }
        return checksum.value
    }

    private fun validatedContentType(value: String?): String? = value?.also {
        require(it.isNotBlank() && it.length <= MAX_CONTENT_TYPE_LENGTH) { "OSS content type is invalid." }
        require(it.all(::isVisibleAscii)) { "OSS content type contains unsafe characters." }
    }

    private fun providerMetadata(
        userMetadata: Map<String, String>,
        contentHash: String?,
        contentLength: Long,
        tenantId: Identifier? = null,
        bindingId: Identifier? = null,
    ): Map<String, String> {
        require((tenantId == null) == (bindingId == null)) {
            "OSS direct-upload tenant and binding identifiers must be provided together."
        }
        val result = canonicalProviderMetadata(userMetadata, rejectReserved = true).toMutableMap()
        result[CONTENT_LENGTH_METADATA_KEY] = contentLength.toString()
        contentHash?.let { result[CONTENT_HASH_METADATA_KEY] = it }
        tenantId?.let {
            result[TENANT_DIGEST_METADATA_KEY] = sha256(it.value.toByteArray(StandardCharsets.UTF_8))
        }
        bindingId?.let {
            result[UPLOAD_BINDING_METADATA_KEY] = sha256(it.value.toByteArray(StandardCharsets.UTF_8))
        }
        validateMetadataSize(result)
        return result
    }

    private fun directUploadRequiredHeaders(
        contentType: String,
        contentMd5: String,
        providerMetadata: Map<String, String>,
    ): Map<String, String> = LinkedHashMap<String, String>(providerMetadata.size + 3).apply {
        put(CONTENT_TYPE_HEADER, contentType)
        put(CONTENT_MD5_HEADER, contentMd5)
        put(FORBID_OVERWRITE_HEADER, "true")
        providerMetadata.forEach { (key, value) -> put(USER_METADATA_HEADER_PREFIX + key, value) }
    }

    private fun canonicalProviderMetadata(
        metadata: Map<String, String>,
        rejectReserved: Boolean = false,
    ): Map<String, String> {
        require(metadata.size <= MAX_METADATA_ENTRIES) { "OSS metadata contains too many entries." }
        val result = LinkedHashMap<String, String>(metadata.size)
        metadata.forEach { (rawKey, value) ->
            val key = rawKey.lowercase(Locale.ROOT)
            require(METADATA_KEY_PATTERN.matches(key)) { "OSS metadata key is invalid." }
            require(!rejectReserved || !key.startsWith(RESERVED_METADATA_PREFIX)) {
                "OSS metadata keys starting with $RESERVED_METADATA_PREFIX are reserved."
            }
            require(value.isNotBlank() && value.length <= MAX_METADATA_VALUE_LENGTH && value.all(::isVisibleAscii)) {
                "OSS metadata value is invalid."
            }
            require(result.put(key, value) == null) { "OSS metadata keys must be case-insensitively unique." }
        }
        validateMetadataSize(result)
        return result
    }

    private fun validateMetadataSize(metadata: Map<String, String>) {
        var bytes = 0L
        metadata.forEach { (key, value) ->
            bytes = Math.addExact(
                bytes,
                (USER_METADATA_HEADER_PREFIX.length + key.length + value.length).toLong(),
            )
        }
        require(bytes < MAX_METADATA_BYTES) { "OSS user metadata must be smaller than 8 KiB." }
    }

    private fun newObjectLocation(tenantId: Identifier): StorageObjectLocation = StorageObjectLocation(
        configuration.storageType,
        "objects/${sha256(tenantId.value.toByteArray(StandardCharsets.UTF_8))}/" +
            UUID.randomUUID().toString().replace("-", ""),
    )

    private fun validatedReference(location: StorageObjectLocation): OssObjectReference {
        require(location.storageType == configuration.storageType) { "Storage location does not belong to this adapter." }
        if (OBJECT_KEY_PATTERN.matches(location.path)) return OssObjectReference(location.path, null)
        require(location.path.length <= MAX_BOUND_LOCATION_LENGTH && location.path.startsWith(BOUND_LOCATION_PREFIX)) {
            "Storage location path is invalid."
        }
        val payload = location.path.substring(BOUND_LOCATION_PREFIX.length)
        val separator = payload.indexOf('/')
        require(separator > 0 && separator == payload.lastIndexOf('/') && separator < payload.lastIndex) {
            "Bound OSS storage location is malformed."
        }
        val key = decodeLocationComponent(payload.substring(0, separator), "object key")
        val versionId = validatedRevisionValue(
            decodeLocationComponent(payload.substring(separator + 1), "version id"),
        )
        require(OBJECT_KEY_PATTERN.matches(key) && boundLocation(key, versionId).path == location.path) {
            "Bound OSS storage location is not canonical."
        }
        return OssObjectReference(key, versionId)
    }

    private fun validatedUnboundKey(location: StorageObjectLocation): String {
        val reference = validatedReference(location)
        require(reference.versionId == null) { "This OSS operation requires an unbound staging location." }
        return reference.key
    }

    private fun validatedTenantKey(location: StorageObjectLocation, tenantId: Identifier): String {
        val key = validatedUnboundKey(location)
        val tenantPrefix = "objects/${sha256(tenantId.value.toByteArray(StandardCharsets.UTF_8))}/"
        require(key.startsWith(tenantPrefix)) { "Storage location does not belong to the requested tenant." }
        return key
    }

    private fun canonicalLocation(reference: OssObjectReference): StorageObjectLocation =
        reference.versionId?.let { boundLocation(reference.key, it) }
            ?: StorageObjectLocation(configuration.storageType, reference.key)

    private fun boundLocation(key: String, versionId: String): StorageObjectLocation {
        require(OBJECT_KEY_PATTERN.matches(key)) { "OSS object key is invalid." }
        val validatedVersion = validatedRevisionValue(versionId)
        val encodedKey = encodeLocationComponent(key)
        val encodedVersion = encodeLocationComponent(validatedVersion)
        require(encodedKey.length <= MAX_BOUND_COMPONENT_LENGTH && encodedVersion.length <= MAX_BOUND_COMPONENT_LENGTH) {
            "Bound OSS storage location component is too large."
        }
        val path = BOUND_LOCATION_PREFIX + encodedKey + "/" + encodedVersion
        require(path.length <= MAX_BOUND_LOCATION_LENGTH) { "Bound OSS storage location is too large." }
        return StorageObjectLocation(
            configuration.storageType,
            path,
        )
    }

    private fun encodeLocationComponent(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeLocationComponent(encoded: String, label: String): String = try {
        require(encoded.isNotEmpty() && encoded.length <= MAX_BOUND_COMPONENT_LENGTH) {
            "Bound OSS $label is invalid."
        }
        val decoded = String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        require(encodeLocationComponent(decoded) == encoded) { "Bound OSS $label is not canonical." }
        decoded
    } catch (_: RuntimeException) {
        throw IllegalArgumentException("Bound OSS $label is invalid.")
    }

    private fun signingCredentials(
        now: Long,
        grantExpiresAt: Long,
        operation: OssStorageOperation,
    ): OssCredentials {
        require(grantExpiresAt > now) { "OSS presigned URL expiration must be in the future." }
        val credentials = try {
            requireNotNull(configuration.credentialsProvider.resolve()) {
                "OSS credentials provider returned null."
            }
        } catch (failure: Exception) {
            throw ossStorageFailure(operation, failure)
        }
        if (credentials.securityToken != null) {
            require(credentials.expiresAt != null) {
                "Temporary OSS credentials must declare their expiration before presigning."
            }
        }
        credentials.expiresAt?.let { credentialExpiresAt ->
            val requestSafetyBoundary = Math.addExact(
                Math.addExact(now, clientPolicy.requestTimeout.toMillis()),
                clientPolicy.credentialExpirySafetyWindow.toMillis(),
            )
            require(requestSafetyBoundary < credentialExpiresAt) {
                "OSS credentials expire before the request timeout and safety window."
            }
            val latestGrantExpiration = Math.subtractExact(
                credentialExpiresAt,
                clientPolicy.credentialExpirySafetyWindow.toMillis(),
            )
            require(grantExpiresAt <= latestGrantExpiration) {
                "OSS presigned URL expiration exceeds the credential lifetime."
            }
        }
        return credentials
    }

    private fun validatedPresignedUrl(
        url: URI,
        key: String,
        requiresSecurityToken: Boolean,
        maximumLifetime: Duration,
        expectedVersionId: String? = null,
    ): URI {
        require(url.isAbsolute && url.scheme.equals("https", true) && !url.host.isNullOrBlank()) {
            "OSS returned an invalid presigned URL."
        }
        require(url.userInfo == null && url.fragment == null) { "OSS returned an unsafe presigned URL." }
        val endpointHost = configuration.endpoint.host.lowercase(Locale.ROOT)
        val expectedHost = when {
            configuration.useCName || configuration.usePathStyle -> endpointHost
            else -> "${configuration.bucket}.$endpointHost"
        }
        require(url.host.lowercase(Locale.ROOT) == expectedHost) {
            "OSS returned a presigned URL for an unexpected host."
        }
        require(effectiveHttpsPort(url) == effectiveHttpsPort(configuration.endpoint)) {
            "OSS returned a presigned URL for an unexpected port."
        }
        val expectedPath = if (configuration.usePathStyle) {
            "/${configuration.bucket}/$key"
        } else {
            "/$key"
        }
        require(url.rawPath == expectedPath) { "OSS returned a presigned URL for an unexpected object path." }
        val rawQuery = requireNotNull(url.rawQuery) { "OSS returned a presigned URL without a signature." }
        val queryParameters = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { pair ->
            val separator = pair.indexOf('=')
            require(separator > 0 && separator < pair.lastIndex) {
                "OSS returned a malformed presigned URL query."
            }
            val name = pair.substring(0, separator).lowercase(Locale.ROOT)
            require(queryParameters.put(name, pair.substring(separator + 1)) == null) {
                "OSS returned duplicate presigned URL query parameters."
            }
        }
        require(
            queryParameters["x-oss-signature"]?.matches(V4_SIGNATURE_PATTERN) == true &&
                queryParameters["x-oss-signature-version"] == "OSS4-HMAC-SHA256" &&
                queryParameters["x-oss-date"]?.matches(V4_DATE_PATTERN) == true &&
                queryParameters.containsKey("x-oss-expires") &&
                queryParameters.containsKey("x-oss-credential") &&
                (queryParameters.containsKey("x-oss-security-token") == requiresSecurityToken),
        ) { "OSS returned an incomplete V4 presigned URL." }
        val returnedVersionId = queryParameters[VERSION_ID_QUERY_LOWERCASE]?.let { rawVersion ->
            try {
                URLDecoder.decode(rawVersion, StandardCharsets.UTF_8.name())
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("OSS returned a malformed version-bound presigned URL.")
            }
        }
        require(returnedVersionId == expectedVersionId) {
            "OSS returned a presigned URL for an unexpected object version."
        }
        val signedLifetimeSeconds = queryParameters.getValue("x-oss-expires").toLongOrNull()
        val maximumLifetimeSeconds = Math.addExact(maximumLifetime.toMillis(), 999L) / 1_000L
        require(signedLifetimeSeconds != null && signedLifetimeSeconds in 1..maximumLifetimeSeconds) {
            "OSS returned a presigned URL outside the requested lifetime."
        }
        return url
    }

    private fun effectiveHttpsPort(uri: URI): Int = if (uri.port == -1) HTTPS_DEFAULT_PORT else uri.port

    private fun deleteKey(key: String, operation: OssStorageOperation) {
        deleteReference(OssObjectReference(key, null), operation)
    }

    private fun deleteReference(reference: OssObjectReference, operation: OssStorageOperation) {
        val versionId = reference.versionId ?: try {
            client.headObject(reference.key).versionId?.also {
                if (it.isBlank()) throw integrityFailure(operation, "OSS returned an invalid object version.")
            }
        } catch (failure: Exception) {
            if (ossServiceErrorCode(failure) == NO_SUCH_KEY) return
            throw ossStorageFailure(operation, failure)
        }
        try {
            // Delete the exact current version where OSS supplies one. This
            // keeps repeated deletes idempotent instead of stacking delete
            // markers in a versioning-enabled bucket.
            client.deleteObject(reference.key, versionId)
        } catch (failure: Exception) {
            if (ossServiceErrorCode(failure) !in IDEMPOTENT_DELETE_MISSING_CODES) {
                throw ossStorageFailure(operation, failure)
            }
        }
    }

    private data class OssObjectReference(
        val key: String,
        val versionId: String?,
    )

    private fun verifyExactLength(stream: DigestingInputStream, expected: Long, operation: OssStorageOperation) {
        try {
            if (stream.contentLength != expected || stream.read() != -1) {
                throw integrityFailure(operation, "OSS request content length did not match its declaration.")
            }
        } catch (failure: IOException) {
            throw ossStorageFailure(operation, failure)
        }
    }

    private inline fun <T> execute(operation: OssStorageOperation, action: () -> T): T = try {
        action()
    } catch (failure: Exception) {
        throw ossStorageFailure(operation, failure)
    }

    private fun integrityFailure(operation: OssStorageOperation, detail: String): OssStorageOperationException =
        ossClassifiedFailure(operation, OssStorageFailureCategory.INTEGRITY, detail = detail)

    private fun abortResponse(
        response: OssObjectResponse,
        operation: OssStorageOperation,
        original: Throwable,
    ) {
        try {
            response.abort()
        } catch (closeFailure: Throwable) {
            original.addSuppressed(
                if (closeFailure is Exception) ossStorageFailure(operation, closeFailure)
                else IllegalStateException("OSS response abort failed without provider diagnostics."),
            )
        }
    }

    private class TranslatingDownloadInputStream(
        private val response: OssObjectResponse,
        private val operation: OssStorageOperation,
    ) : FilterInputStream(response.body) {
        override fun read(): Int = translate { super.read() }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            translate { super.read(buffer, offset, length) }
        override fun skip(count: Long): Long = translate { super.skip(count) }
        override fun available(): Int = translate { super.available() }
        override fun close() = translate { response.close() }

        private inline fun <T> translate(action: () -> T): T = try {
            action()
        } catch (failure: Exception) {
            val translated = ossStorageFailure(operation, failure)
            try {
                response.abort()
            } catch (closeFailure: Throwable) {
                translated.addSuppressed(
                    if (closeFailure is Exception) ossStorageFailure(operation, closeFailure)
                    else IllegalStateException("OSS response abort failed without provider diagnostics."),
                )
            }
            throw translated
        }
    }

    private class DigestingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        private val digest = newSha256()
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
        const val STORAGE_TYPE = "oss"

        @JvmSynthetic
        internal fun testInstance(
            configuration: OssStorageConfiguration,
            clientPolicy: OssStorageClientPolicy,
            client: OssClientFacade,
            clock: Clock = Clock.systemUTC(),
        ): OssStorageAdapter = OssStorageAdapter(configuration, clientPolicy, client, clock)

        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val BUFFER_SIZE = 8 * 1024
        private const val MAX_MULTIPART_PART_NUMBER = 10_000
        private const val MAX_CLEANUP_VERSIONS_PER_CALL = 100
        private const val LIST_PART_PAGE_SIZE = 1_000
        private const val MAX_LIST_PART_PAGES = 11
        private const val MAX_PROVIDER_TOKEN_LENGTH = 1_024
        private const val MAX_ENCODED_REVISION_LENGTH = 1_500
        private const val MAX_BOUND_COMPONENT_LENGTH = 2_048
        private const val MAX_BOUND_LOCATION_LENGTH = 4_096
        private const val MAX_CONTENT_TYPE_LENGTH = 256
        private const val MAX_METADATA_ENTRIES = 128
        private const val MAX_METADATA_VALUE_LENGTH = 4_096
        private const val MAX_METADATA_BYTES = 8_192L
        private const val USER_METADATA_HEADER_PREFIX = "x-oss-meta-"
        private const val CONTENT_TYPE_HEADER = "Content-Type"
        private const val CONTENT_MD5_HEADER = "Content-MD5"
        private const val FORBID_OVERWRITE_HEADER = "x-oss-forbid-overwrite"
        private const val RESERVED_METADATA_PREFIX = "flowweft-"
        private const val CONTENT_HASH_METADATA_KEY = "flowweft-content-sha256"
        private const val CONTENT_LENGTH_METADATA_KEY = "flowweft-content-length"
        private const val TENANT_DIGEST_METADATA_KEY = "flowweft-tenant-sha256"
        private const val UPLOAD_BINDING_METADATA_KEY = "flowweft-upload-binding-sha256"
        private const val CHECKSUM_ALGORITHM_MD5 = "md5"
        private const val MD5_DIGEST_LENGTH = 16
        private const val HTTPS_DEFAULT_PORT = 443
        private const val MAXIMUM_DIRECT_UPLOAD_LENGTH = 5L * 1024L * 1024L * 1024L
        private const val VERSION_REVISION_PREFIX = "oss-v1-version:"
        private const val ETAG_REVISION_PREFIX = "oss-v1-etag:"
        private const val BOUND_LOCATION_PREFIX = "oss-bound-v1/"
        private const val VERSION_ID_QUERY_LOWERCASE = "versionid"
        private const val NO_SUCH_KEY = "NOSUCHKEY"
        private const val NO_SUCH_VERSION = "NOSUCHVERSION"
        private const val NO_SUCH_UPLOAD = "NOSUCHUPLOAD"
        // SDK V1 floors V4 lifetime to whole seconds after it captures its own
        // signing time. A one-second absolute deadline can therefore become a
        // zero-second URL while the request is being assembled.
        private val MINIMUM_PRESIGN_DURATION = Duration.ofSeconds(2)
        private val MAXIMUM_PRESIGN_DURATION = Duration.ofDays(7)
        private val MAXIMUM_DIRECT_UPLOAD_PRESIGN_DURATION = Duration.ofMinutes(15)
        private val OBJECT_KEY_PATTERN = Regex("objects/[0-9a-f]{64}/[0-9a-f]{32}")
        private val CONTENT_HASH_PATTERN = Regex("sha256:[0-9a-f]{64}")
        private val METADATA_KEY_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,255}")
        private val CONTENT_RANGE_PATTERN = Regex("bytes ([0-9]+)-([0-9]+)/(\\*|[0-9]+)")
        private val V4_SIGNATURE_PATTERN = Regex("[0-9a-f]{64}")
        private val V4_DATE_PATTERN = Regex("[0-9]{8}T[0-9]{6}Z")
        private val DEFINITIVE_COMPLETION_REJECTIONS = setOf("ENTITYTOOSMALL", "INVALIDPART", "INVALIDPARTORDER")
        private val IDEMPOTENT_DELETE_MISSING_CODES = setOf(NO_SUCH_KEY, NO_SUCH_VERSION)

        private fun isVisibleAscii(character: Char): Boolean = character.code in 0x20..0x7e
    }
}
