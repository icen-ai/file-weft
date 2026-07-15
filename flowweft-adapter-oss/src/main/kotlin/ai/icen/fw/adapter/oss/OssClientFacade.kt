package ai.icen.fw.adapter.oss

import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Internal SDK boundary; no Alibaba Cloud type crosses the adapter's public API. */
internal interface OssClientFacade : AutoCloseable {
    fun putObject(
        key: String,
        contentLength: Long,
        contentType: String?,
        metadata: Map<String, String>,
        content: InputStream,
    ): OssWriteResult

    fun getObject(
        key: String,
        rangeStart: Long? = null,
        rangeEnd: Long? = null,
        condition: OssRevisionCondition? = null,
    ): OssObjectResponse

    fun deleteObject(key: String, versionId: String?)

    fun headObject(key: String, versionId: String? = null): OssHeadObjectResponse

    fun presignGetObject(
        key: String,
        versionId: String?,
        expiresAt: Long,
        signingCredentials: OssCredentials,
    ): URI

    fun presignPutObject(
        key: String,
        expiresAt: Long,
        contentType: String,
        contentMd5: String,
        metadata: Map<String, String>,
        signingCredentials: OssCredentials,
    ): URI

    fun initiateMultipartUpload(
        key: String,
        contentType: String?,
        metadata: Map<String, String>,
    ): String

    fun uploadPart(
        key: String,
        uploadId: String,
        partNumber: Int,
        contentLength: Long,
        content: InputStream,
    ): String

    fun listParts(
        key: String,
        uploadId: String,
        partNumberMarker: Int?,
        maxParts: Int,
    ): OssListPartsPage

    fun completeMultipartUpload(
        key: String,
        uploadId: String,
        parts: List<OssCompletedPart>,
    ): OssWriteResult

    fun abortMultipartUpload(key: String, uploadId: String)

    fun checkBucketAccess()
}

internal enum class OssRevisionKind {
    VERSION_ID,
    ETAG,
}

internal data class OssRevisionCondition(
    val kind: OssRevisionKind,
    val value: String,
)

internal data class OssHeadObjectResponse(
    val contentLength: Long,
    val contentType: String?,
    val eTag: String?,
    val versionId: String?,
    val contentMd5: String?,
    val crc64ecma: Long?,
    val lastModifiedTime: Long?,
    val metadata: Map<String, String>,
)

internal class OssObjectResponse(
    val statusCode: Int,
    val contentLength: Long,
    val contentType: String?,
    val contentRange: String?,
    val eTag: String?,
    val versionId: String?,
    val body: InputStream,
    private val closeAction: () -> Unit,
    private val abortAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun abort() {
        if (closed.compareAndSet(false, true)) abortAction()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) closeAction()
    }
}

internal data class OssListedPart(
    val partNumber: Int,
    val eTag: String,
)

internal data class OssListPartsPage(
    val parts: List<OssListedPart>,
    val truncated: Boolean,
    val nextPartNumberMarker: Int?,
)

internal data class OssCompletedPart(
    val partNumber: Int,
    val eTag: String,
)

/** Strong provider response captured before another writer can advance the key. */
internal data class OssWriteResult(
    val versionId: String?,
    val eTag: String?,
)
