package ai.icen.fw.adapter.oss

import com.aliyun.oss.ClientConfiguration
import com.aliyun.oss.HttpMethod
import com.aliyun.oss.OSS
import com.aliyun.oss.OSSClientBuilder
import com.aliyun.oss.common.auth.Credentials
import com.aliyun.oss.common.auth.CredentialsProvider
import com.aliyun.oss.common.auth.DefaultCredentials
import com.aliyun.oss.common.comm.Protocol
import com.aliyun.oss.common.comm.SignVersion
import com.aliyun.oss.model.AbortMultipartUploadRequest
import com.aliyun.oss.model.CompleteMultipartUploadRequest
import com.aliyun.oss.model.GeneratePresignedUrlRequest
import com.aliyun.oss.model.GetObjectRequest
import com.aliyun.oss.model.HeadObjectRequest
import com.aliyun.oss.model.InitiateMultipartUploadRequest
import com.aliyun.oss.model.ListPartsRequest
import com.aliyun.oss.model.ObjectMetadata
import com.aliyun.oss.model.PartETag
import com.aliyun.oss.model.PutObjectRequest
import com.aliyun.oss.model.UploadPartRequest
import java.io.InputStream
import java.net.URI
import java.time.Clock
import java.util.Collections
import java.util.Date

/** Stable Alibaba Cloud OSS Java SDK V1 implementation of the internal boundary. */
internal class AlibabaOssV1Client(
    private val configuration: OssStorageConfiguration,
    private val credentialsProvider: FlowWeftCredentialsProvider,
    private val client: OSS,
) : OssClientFacade {
    internal constructor(
        configuration: OssStorageConfiguration,
        clientPolicy: OssStorageClientPolicy,
    ) : this(
        configuration,
        FlowWeftCredentialsProvider(
            configuration.credentialsProvider,
            Math.addExact(
                clientPolicy.requestTimeout.toMillis(),
                clientPolicy.credentialExpirySafetyWindow.toMillis(),
            ),
        ),
        clientPolicy,
    )

    private constructor(
        configuration: OssStorageConfiguration,
        credentialsProvider: FlowWeftCredentialsProvider,
        clientPolicy: OssStorageClientPolicy,
    ) : this(
        configuration,
        credentialsProvider,
        OSSClientBuilder.create()
            .endpoint(configuration.endpoint.toASCIIString().removeSuffix("/"))
            .credentialsProvider(credentialsProvider)
            .clientConfiguration(
                ClientConfiguration().apply {
                    setProtocol(Protocol.HTTPS)
                    setSignatureVersion(SignVersion.V4)
                    setSupportCname(configuration.useCName)
                    setSLDEnabled(configuration.usePathStyle)
                    setConnectionTimeout(clientPolicy.connectionTimeout.toMillis().toInt())
                    setConnectionRequestTimeout(clientPolicy.connectionTimeout.toMillis().toInt())
                    setSocketTimeout(clientPolicy.socketTimeout.toMillis().toInt())
                    setRequestTimeout(clientPolicy.requestTimeout.toMillis().toInt())
                    setRequestTimeoutEnabled(true)
                    setMaxErrorRetry(clientPolicy.maxAttempts - 1)
                    setCrcCheckEnabled(true)
                    setVerifyObjectStrictEnable(true)
                    setRedirectEnable(false)
                    setVerifySSLEnable(true)
                    setExtractSettingFromEndpoint(false)
                    userAgent = "flowweft-adapter-oss"
                },
            )
            .region(configuration.region)
            .build(),
    )

    override fun putObject(
        key: String,
        contentLength: Long,
        contentType: String?,
        metadata: Map<String, String>,
        content: InputStream,
    ): OssWriteResult {
        val objectMetadata = requestMetadata(contentType, metadata).apply {
            setContentLength(contentLength)
            setHeader(FORBID_OVERWRITE_HEADER, "true")
        }
        val result = client.putObject(PutObjectRequest(configuration.bucket, key, content, objectMetadata))
        return OssWriteResult(result.versionId, result.eTag)
    }

    override fun getObject(
        key: String,
        rangeStart: Long?,
        rangeEnd: Long?,
        condition: OssRevisionCondition?,
    ): OssObjectResponse {
        val request = GetObjectRequest(configuration.bucket, key)
        if (rangeStart != null || rangeEnd != null) {
            require(rangeStart != null && rangeEnd != null) { "OSS range bounds must be provided together." }
            request.setRange(rangeStart, rangeEnd)
            // The operation-specific marshaller does not copy this header,
            // but OSS SDK V1 merges WebServiceRequest headers immediately
            // before signing and sending. AlibabaOssV1HeaderMarshallingTest
            // verifies the header on the actual HTTP request produced by the
            // pinned SDK version so an SDK upgrade cannot silently remove it.
            request.addHeader(RANGE_BEHAVIOR_HEADER, RANGE_BEHAVIOR_STANDARD)
        }
        condition?.let { revision ->
            when (revision.kind) {
                OssRevisionKind.VERSION_ID -> request.versionId = revision.value
                OssRevisionKind.ETAG -> request.setMatchingETagConstraints(Collections.singletonList(revision.value))
            }
        }
        val result = client.getObject(request)
        val metadata = result.objectMetadata
        return OssObjectResponse(
            statusCode = result.response.statusCode,
            contentLength = metadata.contentLength,
            contentType = metadata.contentType,
            contentRange = result.response.headers[CONTENT_RANGE_HEADER],
            eTag = metadata.eTag,
            versionId = metadata.versionId,
            body = result.objectContent,
            closeAction = { result.close() },
            abortAction = { result.forcedClose() },
        )
    }

    override fun deleteObject(key: String, versionId: String?) {
        if (versionId == null) {
            client.deleteObject(configuration.bucket, key)
        } else {
            // OSS SDK V1's deleteObject(GenericRequest) does not marshal the
            // GenericRequest versionId. deleteVersion is the supported path
            // that actually sends the versionId query parameter.
            client.deleteVersion(configuration.bucket, key, versionId)
        }
    }

    override fun headObject(key: String, versionId: String?): OssHeadObjectResponse {
        val request = HeadObjectRequest(configuration.bucket, key).apply {
            versionId?.let(::setVersionId)
        }
        val metadata = client.headObject(request)
        return OssHeadObjectResponse(
            contentLength = metadata.contentLength,
            contentType = metadata.contentType,
            eTag = metadata.eTag,
            versionId = metadata.versionId,
            contentMd5 = metadata.contentMD5,
            crc64ecma = metadata.serverCRC,
            lastModifiedTime = metadata.lastModified?.time,
            metadata = LinkedHashMap(metadata.userMetadata),
        )
    }

    override fun presignGetObject(
        key: String,
        versionId: String?,
        expiresAt: Long,
        signingCredentials: OssCredentials,
    ): URI {
        val request = GeneratePresignedUrlRequest(configuration.bucket, key, HttpMethod.GET).apply {
            setExpiration(Date(expiresAt))
            versionId?.let { addQueryParameter(VERSION_ID_QUERY, it) }
        }
        return credentialsProvider.withSnapshot(signingCredentials) {
            client.generatePresignedUrl(request).toURI()
        }
    }

    override fun presignPutObject(
        key: String,
        expiresAt: Long,
        contentType: String,
        contentMd5: String,
        metadata: Map<String, String>,
        signingCredentials: OssCredentials,
    ): URI {
        val request = GeneratePresignedUrlRequest(configuration.bucket, key, HttpMethod.PUT).apply {
            setExpiration(Date(expiresAt))
            setContentType(contentType)
            setContentMD5(contentMd5)
            setUserMetadata(LinkedHashMap(metadata))
            addHeader(FORBID_OVERWRITE_HEADER, "true")
        }
        return credentialsProvider.withSnapshot(signingCredentials) {
            client.generatePresignedUrl(request).toURI()
        }
    }

    override fun initiateMultipartUpload(
        key: String,
        contentType: String?,
        metadata: Map<String, String>,
    ): String {
        val objectMetadata = requestMetadata(contentType, metadata).apply {
            setHeader(FORBID_OVERWRITE_HEADER, "true")
        }
        return client.initiateMultipartUpload(
            InitiateMultipartUploadRequest(configuration.bucket, key, objectMetadata),
        ).uploadId
    }

    override fun uploadPart(
        key: String,
        uploadId: String,
        partNumber: Int,
        contentLength: Long,
        content: InputStream,
    ): String = client.uploadPart(
        UploadPartRequest(configuration.bucket, key, uploadId, partNumber, content, contentLength),
    ).eTag

    override fun listParts(
        key: String,
        uploadId: String,
        partNumberMarker: Int?,
        maxParts: Int,
    ): OssListPartsPage {
        val request = ListPartsRequest(configuration.bucket, key, uploadId).apply {
            setMaxParts(maxParts)
            partNumberMarker?.let(::setPartNumberMarker)
        }
        val result = client.listParts(request)
        return OssListPartsPage(
            parts = result.parts.map { part -> OssListedPart(part.partNumber, part.eTag) },
            truncated = result.isTruncated,
            nextPartNumberMarker = result.nextPartNumberMarker,
        )
    }

    override fun completeMultipartUpload(
        key: String,
        uploadId: String,
        parts: List<OssCompletedPart>,
    ): OssWriteResult {
        val request = CompleteMultipartUploadRequest(
            configuration.bucket,
            key,
            uploadId,
            parts.map { part -> PartETag(part.partNumber, part.eTag) },
        ).apply {
            // See the ranged GET note above. The generic OSS operation layer
            // merges this request header after multipart XML marshalling and
            // before V4 signing. The wire-level regression test covers it.
            addHeader(FORBID_OVERWRITE_HEADER, "true")
        }
        val result = client.completeMultipartUpload(request)
        return OssWriteResult(result.versionId, result.eTag)
    }

    override fun abortMultipartUpload(key: String, uploadId: String) {
        client.abortMultipartUpload(AbortMultipartUploadRequest(configuration.bucket, key, uploadId))
    }

    override fun checkBucketAccess() {
        client.getBucketInfo(configuration.bucket)
    }

    override fun close() {
        client.shutdown()
    }

    private fun requestMetadata(contentType: String?, metadata: Map<String, String>): ObjectMetadata =
        ObjectMetadata().apply {
            contentType?.let(::setContentType)
            setUserMetadata(metadata)
        }

    private companion object {
        const val FORBID_OVERWRITE_HEADER = "x-oss-forbid-overwrite"
        const val RANGE_BEHAVIOR_HEADER = "x-oss-range-behavior"
        const val RANGE_BEHAVIOR_STANDARD = "standard"
        const val CONTENT_RANGE_HEADER = "Content-Range"
        const val VERSION_ID_QUERY = "versionId"
    }
}

/**
 * Resolves credentials for every SDK request so RAM role and STS rotation stays live.
 *
 * The wrapped OSS client is private to the adapter, therefore its imperative
 * `switchCredentials` hook is intentionally ignored instead of permanently
 * shadowing the authoritative FlowWeft provider.
 */
internal class FlowWeftCredentialsProvider(
    private val delegate: OssCredentialsProvider,
    private val minimumValidityMillis: Long = 0,
    private val clock: Clock = Clock.systemUTC(),
) : CredentialsProvider {
    private val signingSnapshot = ThreadLocal<OssCredentials?>()

    init {
        require(minimumValidityMillis >= 0) { "OSS credential minimum validity must not be negative." }
    }

    override fun getCredentials(): Credentials {
        val resolved = signingSnapshot.get()
            ?: requireNotNull(delegate.resolve()) { "OSS credentials provider returned null." }
        if (resolved.securityToken != null && resolved.expiresAt == null) {
            throw com.aliyun.oss.common.auth.InvalidCredentialsException(
                "Temporary OSS credentials must declare their expiration.",
            )
        }
        resolved.expiresAt?.let { expiresAt ->
            val minimumExpiration = Math.addExact(clock.millis(), minimumValidityMillis)
            if (expiresAt <= minimumExpiration) {
                throw com.aliyun.oss.common.auth.InvalidCredentialsException(
                    "OSS credentials do not cover the configured request timeout and safety window.",
                )
            }
        }
        return if (resolved.securityToken == null) {
            DefaultCredentials(resolved.accessKeyId, resolved.accessKeySecret)
        } else {
            DefaultCredentials(resolved.accessKeyId, resolved.accessKeySecret, resolved.securityToken)
        }
    }

    override fun setCredentials(creds: Credentials) {
        requireNotNull(creds) { "OSS SDK credentials must not be null." }
    }

    internal fun <T> withSnapshot(credentials: OssCredentials, action: () -> T): T {
        val previous = signingSnapshot.get()
        signingSnapshot.set(credentials)
        return try {
            action()
        } finally {
            if (previous == null) signingSnapshot.remove() else signingSnapshot.set(previous)
        }
    }
}
