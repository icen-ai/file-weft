package ai.icen.fw.adapter.s3

import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.InterruptedIOException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/** Stable operation names exposed without leaking an AWS SDK request type. */
enum class S3StorageOperation {
    UPLOAD,
    DOWNLOAD,
    DOWNLOAD_RANGE,
    READ_METADATA,
    DELETE,
    EXISTS,
    PRESIGN_DOWNLOAD,
    BEGIN_MULTIPART_UPLOAD,
    UPLOAD_PART,
    LIST_MULTIPART_PARTS,
    COMPLETE_MULTIPART_UPLOAD,
    ABORT_MULTIPART_UPLOAD,
    VERIFY_COMPLETED_OBJECT,
    CHECKSUM_COMPLETED_OBJECT,
    CHECK_BUCKET,
    CREATE_BUCKET,
    CLOSE,
}

/** Actionable, provider-neutral failure classes for S3-compatible services. */
enum class S3StorageFailureCategory {
    AUTHENTICATION,
    AUTHORIZATION,
    NOT_FOUND,
    CONFLICT,
    THROTTLED,
    TIMEOUT,
    UNAVAILABLE,
    CONFIGURATION,
    INVALID_REQUEST,
    INTEGRITY,
    UNKNOWN,
}

/** Exact not-found authority retained after the provider exception is removed. */
enum class S3StorageMissingResource {
    OBJECT,
    BUCKET,
    MULTIPART_UPLOAD,
    AMBIGUOUS,
}

/**
 * Sanitized adapter failure.
 *
 * The message intentionally contains no endpoint, bucket, object key,
 * credential, signed URL, provider response body, or SDK exception message.
 * Operators may use [operation], [category], [retryable], and
 * [missingResource] as bounded diagnostic evidence. Provider failures are
 * deliberately not retained as a
 * cause: ordinary JVM logging renders nested causes, and SDK/HTTP exception
 * messages can contain credentials, request headers, endpoint query strings,
 * object keys, upload ids, or complete presigned capability URLs.
 */
class S3StorageOperationException private constructor(
    val operation: S3StorageOperation,
    val category: S3StorageFailureCategory,
    val retryable: Boolean,
    val missingResource: S3StorageMissingResource?,
    @Suppress("UNUSED_PARAMETER") cause: Throwable,
) : RuntimeException(
    "S3-compatible storage operation ${operation.name} failed as ${category.name} (retryable=$retryable).",
) {
    internal companion object {
        fun sanitized(
            operation: S3StorageOperation,
            category: S3StorageFailureCategory,
            retryable: Boolean,
            missingResource: S3StorageMissingResource?,
            cause: Throwable,
        ): S3StorageOperationException =
            S3StorageOperationException(operation, category, retryable, missingResource, cause)
    }
}

internal fun s3StorageFailure(
    operation: S3StorageOperation,
    failure: Throwable,
): S3StorageOperationException {
    if (failure is S3StorageOperationException) return failure
    val category = classifyS3StorageFailure(failure)
    val retryable = category in RETRYABLE_FAILURE_CATEGORIES
    return S3StorageOperationException.sanitized(
        operation = operation,
        category = category,
        retryable = retryable,
        missingResource = (failure as? S3Exception)?.let { providerFailure ->
            classifyS3MissingResource(
                providerFailure.statusCode(),
                providerFailure.awsErrorDetails()?.errorCode(),
            )
        },
        cause = failure,
    )
}

internal fun s3ClassifiedFailure(
    operation: S3StorageOperation,
    category: S3StorageFailureCategory,
    retryable: Boolean = false,
    detail: String,
): S3StorageOperationException = S3StorageOperationException.sanitized(
    operation = operation,
    category = category,
    retryable = retryable,
    missingResource = null,
    cause = IllegalStateException(detail),
)

internal fun classifyS3MissingResource(
    statusCode: Int,
    errorCode: String?,
): S3StorageMissingResource? {
    val code = errorCode.orEmpty().uppercase(Locale.ROOT)
    return when (code) {
        "NOSUCHKEY" -> S3StorageMissingResource.OBJECT
        "NOSUCHBUCKET" -> S3StorageMissingResource.BUCKET
        "NOSUCHUPLOAD" -> S3StorageMissingResource.MULTIPART_UPLOAD
        "NOTFOUND" -> S3StorageMissingResource.AMBIGUOUS
        else -> if (statusCode == 404) S3StorageMissingResource.AMBIGUOUS else null
    }
}

internal fun classifyS3StorageFailure(failure: Throwable): S3StorageFailureCategory {
    if (
        failure is ApiCallTimeoutException ||
        failure is ApiCallAttemptTimeoutException ||
        failure.hasCause<SocketTimeoutException>() ||
        failure.hasCause<InterruptedIOException>()
    ) {
        return S3StorageFailureCategory.TIMEOUT
    }
    if (failure.hasCause<SSLException>()) return S3StorageFailureCategory.CONFIGURATION
    if (
        failure.hasCause<UnknownHostException>() ||
        failure.hasCause<ConnectException>() ||
        failure.hasCause<SocketException>()
    ) {
        return S3StorageFailureCategory.UNAVAILABLE
    }

    if (failure is S3Exception) {
        val code = failure.awsErrorDetails()?.errorCode().orEmpty().uppercase(Locale.ROOT)
        when (code) {
            "INVALIDACCESSKEYID",
            "SIGNATUREDOESNOTMATCH",
            "EXPIREDTOKEN",
            "INVALIDTOKEN",
            "INVALIDSECURITYTOKEN",
            "TOKENREFRESHREQUIRED",
            ->
                return S3StorageFailureCategory.AUTHENTICATION
            "ACCESSDENIED", "ALLACCESSDISABLED" -> return S3StorageFailureCategory.AUTHORIZATION
            "NOSUCHBUCKET", "NOSUCHKEY", "NOSUCHUPLOAD", "NOTFOUND" ->
                return S3StorageFailureCategory.NOT_FOUND
            "BUCKETALREADYEXISTS", "BUCKETALREADYOWNEDBYYOU", "OPERATIONABORTED" ->
                return S3StorageFailureCategory.CONFLICT
            "SLOWDOWN", "THROTTLING", "THROTTLINGEXCEPTION" -> return S3StorageFailureCategory.THROTTLED
            "REQUESTTIMEOUT", "REQUESTTIMEOUTEXCEPTION" -> return S3StorageFailureCategory.TIMEOUT
            "INTERNALERROR", "SERVICEUNAVAILABLE" -> return S3StorageFailureCategory.UNAVAILABLE
            "PERMANENTREDIRECT", "AUTHORIZATIONHEADERMALFORMED", "REQUESTTIMETOOSKEWED" ->
                return S3StorageFailureCategory.CONFIGURATION
            "INVALIDARGUMENT", "INVALIDPART", "INVALIDPARTORDER", "ENTITYTOOSMALL", "INVALIDRANGE" ->
                return S3StorageFailureCategory.INVALID_REQUEST
        }
        return when (failure.statusCode()) {
            301, 307 -> S3StorageFailureCategory.CONFIGURATION
            400, 405, 411, 413, 416, 422 -> S3StorageFailureCategory.INVALID_REQUEST
            401 -> S3StorageFailureCategory.AUTHENTICATION
            403 -> S3StorageFailureCategory.AUTHORIZATION
            404 -> S3StorageFailureCategory.NOT_FOUND
            408, 504 -> S3StorageFailureCategory.TIMEOUT
            409, 412 -> S3StorageFailureCategory.CONFLICT
            429 -> S3StorageFailureCategory.THROTTLED
            in 500..599 -> S3StorageFailureCategory.UNAVAILABLE
            else -> S3StorageFailureCategory.UNKNOWN
        }
    }
    if (failure is SdkClientException) return S3StorageFailureCategory.UNAVAILABLE
    if (failure.hasCause<IOException>()) return S3StorageFailureCategory.UNAVAILABLE
    return S3StorageFailureCategory.UNKNOWN
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is T) return true
        current = current.cause
        depth += 1
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 16
private val RETRYABLE_FAILURE_CATEGORIES: Set<S3StorageFailureCategory> = setOf(
    S3StorageFailureCategory.THROTTLED,
    S3StorageFailureCategory.TIMEOUT,
    S3StorageFailureCategory.UNAVAILABLE,
)
