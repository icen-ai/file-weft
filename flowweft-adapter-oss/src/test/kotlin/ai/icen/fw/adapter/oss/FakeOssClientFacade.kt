package ai.icen.fw.adapter.oss

import com.aliyun.oss.ServiceException
import ai.icen.fw.spi.storage.PresignedUploadGrant
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap

internal class FakeOssClientFacade : OssClientFacade {
    private data class FakeObject(
        val content: ByteArray,
        val contentType: String?,
        val metadata: Map<String, String>,
        val eTag: String,
        val versionId: String,
        val contentMd5: String,
        val lastModifiedTime: Long,
    )

    private data class FakeMultipartUpload(
        val key: String,
        val contentType: String?,
        val metadata: Map<String, String>,
        val parts: MutableMap<Int, Pair<String, ByteArray>> = LinkedHashMap(),
    )

    private val objects = LinkedHashMap<String, LinkedHashMap<String, FakeObject>>()
    private val uploads = LinkedHashMap<String, FakeMultipartUpload>()
    private var objectSequence = 0
    private var uploadSequence = 0
    private var partSequence = 0

    var forcePartPagination: Boolean = false
    var rangeStatusOverride: Int? = null
    var rangeHeaderOverride: String? = null
    var bucketFailure: Throwable? = null
    var putFailureBeforeStore: Throwable? = null
    var putFailureAfterStore: Throwable? = null
    var completeFailureBeforeStore: Throwable? = null
    var completeFailureAfterStore: Throwable? = null
    var advanceCurrentVersionAfterComplete: Boolean = false
    var omitWriteVersionId: Boolean = false
    var omitWriteETag: Boolean = false
    var deleteFailure: Throwable? = null
    var headContentLengthDelta: Long = 0
    var omitHeadContentMd5: Boolean = false
    var omitHeadCrc64: Boolean = false
    var omitHeadVersionId: Boolean = false
    var headVersionIdOverride: String? = null
    var omitHeadStrongRevision: Boolean = false
    var omitHeadMetadata: Boolean = false
    var replaceBeforeConditionalGet: Boolean = false
    var lastRevisionCondition: OssRevisionCondition? = null
        private set
    var completedUploadCount: Int = 0
        private set
    var abortedResponseCount: Int = 0
        private set
    var closedResponseCount: Int = 0
        private set
    var lastDeletedVersionId: String? = null
        private set
    var deleteCount: Int = 0
        private set
    var lastPresignedPut: OssPresignedPutCapture? = null
        private set
    var lastPresignedGet: OssPresignedGetCapture? = null
        private set
    var presignedHost: String = "flowweft-oss-test.oss-cn-hangzhou.aliyuncs.com"
    var presignedPathPrefix: String = ""
    var presignedExpiresSeconds: Long = 1
    var overridePresignedGetVersion: Boolean = false
    var presignedGetVersionOverride: String? = null

    val objectKeys: Set<String>
        get() = objects.filterValues { it.isNotEmpty() }.keys.toSet()

    override fun putObject(
        key: String,
        contentLength: Long,
        contentType: String?,
        metadata: Map<String, String>,
        content: InputStream,
    ): OssWriteResult {
        putFailureBeforeStore?.let { throw it }
        if (currentObject(key) != null) throw serviceFailure("ObjectAlreadyExists")
        val stored = storeObject(key, content.readBytes(), contentType, metadata)
        putFailureAfterStore?.let { throw it }
        return writeResult(stored)
    }

    override fun getObject(
        key: String,
        rangeStart: Long?,
        rangeEnd: Long?,
        condition: OssRevisionCondition?,
    ): OssObjectResponse {
        lastRevisionCondition = condition
        if (replaceBeforeConditionalGet) {
            replaceBeforeConditionalGet = false
            val previous = currentObject(key) ?: throw serviceFailure("NoSuchKey")
            storeObject(key, previous.content, previous.contentType, previous.metadata, previous.contentMd5)
        }
        val stored = when (condition?.kind) {
            OssRevisionKind.VERSION_ID -> versionObject(key, condition.value)
                ?: throw serviceFailure("NoSuchVersion")
            OssRevisionKind.ETAG -> currentObject(key)?.also { current ->
                if (current.eTag != condition.value) throw serviceFailure("PreconditionFailed")
            } ?: throw serviceFailure("NoSuchKey")
            null -> currentObject(key) ?: throw serviceFailure("NoSuchKey")
        }

        val ranged = rangeStart != null || rangeEnd != null
        val responseContent: ByteArray
        val contentRange: String?
        if (ranged) {
            require(rangeStart != null && rangeEnd != null)
            if (rangeStart >= stored.content.size) throw serviceFailure("InvalidRange")
            val actualEnd = minOf(rangeEnd, stored.content.lastIndex.toLong())
            responseContent = stored.content.copyOfRange(rangeStart.toInt(), actualEnd.toInt() + 1)
            contentRange = rangeHeaderOverride ?: "bytes $rangeStart-$actualEnd/${stored.content.size}"
        } else {
            responseContent = stored.content.copyOf()
            contentRange = null
        }

        return OssObjectResponse(
            statusCode = rangeStatusOverride ?: if (ranged) 206 else 200,
            contentLength = responseContent.size.toLong(),
            contentType = stored.contentType,
            contentRange = contentRange,
            eTag = stored.eTag,
            versionId = stored.versionId,
            body = ByteArrayInputStream(responseContent),
            closeAction = { closedResponseCount += 1 },
            abortAction = { abortedResponseCount += 1 },
        )
    }

    override fun deleteObject(key: String, versionId: String?) {
        deleteFailure?.let { throw it }
        val versions = objects[key] ?: throw serviceFailure("NoSuchKey")
        val exactVersionId = versionId ?: versions.values.lastOrNull()?.versionId
            ?: throw serviceFailure("NoSuchKey")
        if (versions.remove(exactVersionId) == null) throw serviceFailure("NoSuchVersion")
        lastDeletedVersionId = exactVersionId
        if (versions.isEmpty()) objects.remove(key)
        deleteCount += 1
    }

    override fun headObject(key: String, versionId: String?): OssHeadObjectResponse {
        val stored = if (versionId == null) {
            currentObject(key) ?: throw serviceFailure("NoSuchKey")
        } else {
            versionObject(key, versionId) ?: throw serviceFailure("NoSuchVersion")
        }
        return OssHeadObjectResponse(
            contentLength = Math.addExact(stored.content.size.toLong(), headContentLengthDelta),
            contentType = stored.contentType,
            eTag = stored.eTag.takeUnless { omitHeadStrongRevision },
            versionId = headVersionIdOverride
                ?: stored.versionId.takeUnless { omitHeadVersionId || omitHeadStrongRevision },
            contentMd5 = stored.contentMd5.takeUnless { omitHeadContentMd5 },
            crc64ecma = 1L.takeUnless { omitHeadCrc64 },
            lastModifiedTime = stored.lastModifiedTime,
            metadata = if (omitHeadMetadata) emptyMap() else stored.metadata,
        )
    }

    override fun presignGetObject(
        key: String,
        versionId: String?,
        expiresAt: Long,
        signingCredentials: OssCredentials,
    ): URI {
        lastPresignedGet = OssPresignedGetCapture(key, versionId, expiresAt, signingCredentials)
        val returnedVersion = if (overridePresignedGetVersion) presignedGetVersionOverride else versionId
        return signedUri(key, signingCredentials.securityToken != null, returnedVersion)
    }

    override fun presignPutObject(
        key: String,
        expiresAt: Long,
        contentType: String,
        contentMd5: String,
        metadata: Map<String, String>,
        signingCredentials: OssCredentials,
    ): URI {
        lastPresignedPut = OssPresignedPutCapture(
            key,
            expiresAt,
            contentType,
            contentMd5,
            LinkedHashMap(metadata),
            signingCredentials,
        )
        return signedUri(key, signingCredentials.securityToken != null)
    }

    fun uploadUsingGrant(
        grant: PresignedUploadGrant,
        content: ByteArray,
        simulateProviderAcceptedMd5Collision: Boolean = false,
    ) {
        val contentMd5 = requireNotNull(grant.requiredHeaders["Content-MD5"])
        require(simulateProviderAcceptedMd5Collision || contentMd5 == md5(content)) {
            "Fake direct upload rejected Content-MD5."
        }
        val metadata = grant.requiredHeaders.entries
            .filter { it.key.startsWith("x-oss-meta-") }
            .associate { it.key.removePrefix("x-oss-meta-") to it.value }
        require(grant.requiredHeaders["x-oss-forbid-overwrite"] == "true")
        storeObject(
            grant.location.path,
            content,
            requireNotNull(grant.requiredHeaders["Content-Type"]),
            metadata,
            contentMd5,
        )
    }

    fun replaceObjectForTest(
        key: String,
        content: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        contentMd5: String = md5(content),
    ) {
        storeObject(key, content, contentType, metadata, contentMd5)
    }

    override fun initiateMultipartUpload(
        key: String,
        contentType: String?,
        metadata: Map<String, String>,
    ): String {
        val uploadId = "upload-${++uploadSequence}"
        uploads[uploadId] = FakeMultipartUpload(key, contentType, LinkedHashMap(metadata))
        return uploadId
    }

    override fun uploadPart(
        key: String,
        uploadId: String,
        partNumber: Int,
        contentLength: Long,
        content: InputStream,
    ): String {
        val upload = uploads[uploadId] ?: throw serviceFailure("NoSuchUpload")
        if (upload.key != key) throw serviceFailure("InvalidArgument")
        val eTag = "part-$partNumber-${++partSequence}"
        upload.parts[partNumber] = eTag to content.readBytes()
        return eTag
    }

    override fun listParts(
        key: String,
        uploadId: String,
        partNumberMarker: Int?,
        maxParts: Int,
    ): OssListPartsPage {
        val upload = uploads[uploadId] ?: throw serviceFailure("NoSuchUpload")
        if (upload.key != key) throw serviceFailure("InvalidArgument")
        val all = upload.parts.entries.sortedBy { it.key }
        if (forcePartPagination && all.size >= 2 && partNumberMarker == null) {
            val first = all.first()
            return OssListPartsPage(
                listOf(OssListedPart(first.key, first.value.first)),
                truncated = true,
                nextPartNumberMarker = first.key,
            )
        }
        val remaining = all.filter { partNumberMarker == null || it.key > partNumberMarker }
            .take(maxParts)
        return OssListPartsPage(
            remaining.map { OssListedPart(it.key, it.value.first) },
            truncated = false,
            nextPartNumberMarker = null,
        )
    }

    override fun completeMultipartUpload(
        key: String,
        uploadId: String,
        parts: List<OssCompletedPart>,
    ): OssWriteResult {
        completeFailureBeforeStore?.let { throw it }
        val upload = uploads[uploadId] ?: throw serviceFailure("NoSuchUpload")
        if (upload.key != key) throw serviceFailure("InvalidArgument")
        val content = parts.sortedBy(OssCompletedPart::partNumber).flatMap { completed ->
            val authoritative = upload.parts[completed.partNumber] ?: throw serviceFailure("InvalidPart")
            if (authoritative.first != completed.eTag) throw serviceFailure("InvalidPart")
            authoritative.second.asIterable()
        }.toByteArray()
        uploads.remove(uploadId)
        val stored = storeObject(key, content, upload.contentType, upload.metadata)
        completedUploadCount += 1
        if (advanceCurrentVersionAfterComplete) {
            storeObject(key, content, upload.contentType, upload.metadata)
        }
        completeFailureAfterStore?.let { throw it }
        return writeResult(stored)
    }

    override fun abortMultipartUpload(key: String, uploadId: String) {
        val upload = uploads[uploadId] ?: throw serviceFailure("NoSuchUpload")
        if (upload.key != key) throw serviceFailure("InvalidArgument")
        uploads.remove(uploadId)
    }

    override fun checkBucketAccess() {
        bucketFailure?.let { throw it }
    }

    override fun close() = Unit

    private fun storeObject(
        key: String,
        content: ByteArray,
        contentType: String?,
        metadata: Map<String, String>,
        contentMd5: String = md5(content),
    ): FakeObject {
        val revision = ++objectSequence
        val stored = FakeObject(
            content.copyOf(),
            contentType,
            LinkedHashMap(metadata),
            "etag-$revision",
            "version-$revision",
            contentMd5,
            revision.toLong(),
        )
        objects.getOrPut(key) { LinkedHashMap() }[stored.versionId] = stored
        return stored
    }

    private fun writeResult(stored: FakeObject): OssWriteResult = OssWriteResult(
        versionId = stored.versionId.takeUnless { omitWriteVersionId },
        eTag = stored.eTag.takeUnless { omitWriteETag },
    )

    fun versionCount(key: String): Int = objects[key]?.size ?: 0

    private fun currentObject(key: String): FakeObject? = objects[key]?.values?.lastOrNull()

    private fun versionObject(key: String, versionId: String): FakeObject? = objects[key]?.get(versionId)

    private fun signedUri(key: String, securityToken: Boolean, versionId: String? = null): URI = URI.create(
        "https://$presignedHost$presignedPathPrefix/$key" +
            "?x-oss-expires=$presignedExpiresSeconds&x-oss-credential=test" +
            "&x-oss-signature=${"a".repeat(64)}&x-oss-signature-version=OSS4-HMAC-SHA256" +
            "&x-oss-date=20260715T000000Z" +
            (if (securityToken) "&x-oss-security-token=test" else "") +
            (if (versionId == null) "" else "&versionId=${encodeQueryValue(versionId)}"),
    )

    private fun encodeQueryValue(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")
}

internal data class OssPresignedPutCapture(
    val key: String,
    val expiresAt: Long,
    val contentType: String,
    val contentMd5: String,
    val metadata: Map<String, String>,
    val credentials: OssCredentials,
)

internal data class OssPresignedGetCapture(
    val key: String,
    val versionId: String?,
    val expiresAt: Long,
    val credentials: OssCredentials,
)

internal fun md5(content: ByteArray): String = Base64.getEncoder().encodeToString(
    MessageDigest.getInstance("MD5").digest(content),
)

internal fun serviceFailure(errorCode: String, secretDetail: String = "provider detail"): ServiceException =
    ServiceException(secretDetail, errorCode, "request-id", "host-id")
