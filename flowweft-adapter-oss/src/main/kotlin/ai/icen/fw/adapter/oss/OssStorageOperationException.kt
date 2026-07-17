package ai.icen.fw.adapter.oss

import com.aliyun.oss.ClientException
import com.aliyun.oss.ServiceException
import com.aliyun.oss.common.auth.InvalidCredentialsException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/** Stable operation names that never expose an Alibaba Cloud SDK request type. */
enum class OssStorageOperation {
    INITIALIZE,
    UPLOAD,
    DOWNLOAD,
    DOWNLOAD_RANGE,
    DELETE,
    EXISTS,
    METADATA,
    PRESIGN_DOWNLOAD,
    PRESIGN_UPLOAD,
    REISSUE_PRESIGNED_UPLOAD,
    FINALIZE_PRESIGNED_UPLOAD,
    CLEANUP_PRESIGNED_UPLOAD,
    BEGIN_MULTIPART_UPLOAD,
    UPLOAD_PART,
    LIST_UPLOADED_PARTS,
    COMPLETE_MULTIPART_UPLOAD,
    ABORT_MULTIPART_UPLOAD,
    VERIFY_COMPLETED_OBJECT,
    CHECKSUM_COMPLETED_OBJECT,
    CHECK_BUCKET,
    CLOSE,
}

/** Actionable provider-neutral failure categories for Alibaba Cloud OSS. */
enum class OssStorageFailureCategory {
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

/**
 * Sanitized OSS adapter failure.
 *
 * The message contains no endpoint, bucket, key, upload id, credential,
 * provider response body, request id or signed URL. Provider exceptions are
 * deliberately not retained as [cause]: ordinary JVM logging renders nested
 * causes, and SDK/HTTP exception messages can contain Authorization headers or
 * a complete presigned capability URL.
 */
class OssStorageOperationException internal constructor(
    val operation: OssStorageOperation,
    val category: OssStorageFailureCategory,
    val retryable: Boolean,
    @Suppress("UNUSED_PARAMETER") cause: Throwable,
) : RuntimeException(
    "OSS storage operation ${operation.name} failed as ${category.name} (retryable=$retryable).",
)

internal fun ossStorageFailure(
    operation: OssStorageOperation,
    failure: Throwable,
): OssStorageOperationException {
    if (failure is OssStorageOperationException) return failure
    val category = classifyOssStorageFailure(failure)
    return OssStorageOperationException(
        operation = operation,
        category = category,
        retryable = category in RETRYABLE_FAILURE_CATEGORIES,
        cause = failure,
    )
}

internal fun ossClassifiedFailure(
    operation: OssStorageOperation,
    category: OssStorageFailureCategory,
    retryable: Boolean = false,
    detail: String,
): OssStorageOperationException = OssStorageOperationException(
    operation = operation,
    category = category,
    retryable = retryable,
    cause = IllegalStateException(detail),
)

internal fun classifyOssStorageFailure(failure: Throwable): OssStorageFailureCategory {
    if (
        failure.hasCause<SocketTimeoutException>() ||
        failure.hasCause<InterruptedIOException>()
    ) {
        return OssStorageFailureCategory.TIMEOUT
    }
    if (failure.hasCause<SSLException>()) return OssStorageFailureCategory.CONFIGURATION
    if (
        failure.hasCause<UnknownHostException>() ||
        failure.hasCause<ConnectException>() ||
        failure.hasCause<SocketException>()
    ) {
        return OssStorageFailureCategory.UNAVAILABLE
    }
    if (failure.hasCause<InvalidCredentialsException>()) return OssStorageFailureCategory.AUTHENTICATION

    val serviceFailure = failure.findCause<ServiceException>()
    if (serviceFailure != null) {
        val code = serviceFailure.errorCode.orEmpty().uppercase(Locale.ROOT)
        when (code) {
            "INVALIDACCESSKEYID",
            "SIGNATUREDOESNOTMATCH",
            "INVALIDSECURITYTOKEN",
            "SECURITYTOKENEXPIRED",
            "TOKENREFRESHREQUIRED",
            -> return OssStorageFailureCategory.AUTHENTICATION

            "ACCESSDENIED",
            "ALLACCESSDISABLED",
            -> return OssStorageFailureCategory.AUTHORIZATION

            "NOSUCHBUCKET",
            "NOSUCHKEY",
            "NOSUCHVERSION",
            "NOSUCHUPLOAD",
            "NOTFOUND",
            -> return OssStorageFailureCategory.NOT_FOUND

            "BUCKETALREADYEXISTS",
            "BUCKETALREADYOWNEDBYYOU",
            "FILEALREADYEXISTS",
            "OBJECTALREADYEXISTS",
            "PRECONDITIONFAILED",
            "OPERATIONABORTED",
            -> return OssStorageFailureCategory.CONFLICT

            "SLOWDOWN",
            "TOOMANYREQUESTS",
            "THROTTLING",
            -> return OssStorageFailureCategory.THROTTLED

            "REQUESTTIMEOUT",
            "REQUESTTIMEOUTEXCEPTION",
            -> return OssStorageFailureCategory.TIMEOUT

            "INTERNALERROR",
            "SERVICEUNAVAILABLE",
            -> return OssStorageFailureCategory.UNAVAILABLE

            "REQUESTTIMETOOSKEWED",
            "INVALIDREGION",
            "SECONDLEVELDOMAINFORBIDDEN",
            -> return OssStorageFailureCategory.CONFIGURATION

            "INVALIDARGUMENT",
            "INVALIDDIGEST",
            "INVALIDPART",
            "INVALIDPARTORDER",
            "ENTITYTOOSMALL",
            "INVALIDRANGE",
            "MALFORMEDXML",
            -> return OssStorageFailureCategory.INVALID_REQUEST

            "INVALIDRESPONSE",
            "CRCINCONSISTENTERROR",
            -> return OssStorageFailureCategory.INTEGRITY
        }
    }
    val clientFailure = failure.findCause<ClientException>()
    if (clientFailure != null) {
        return when (clientFailure.errorCode.orEmpty().uppercase(Locale.ROOT)) {
            "CONNECTIONTIMEOUT",
            "SOCKETTIMEOUT",
            "REQUESTTIMEOUT",
            -> OssStorageFailureCategory.TIMEOUT
            "CONNECTIONREFUSED",
            "UNKNOWNHOST",
            "SOCKETEXCEPTION",
            -> OssStorageFailureCategory.UNAVAILABLE
            "SSLEXCEPTION" -> OssStorageFailureCategory.CONFIGURATION
            "INVALIDCREDENTIALS" -> OssStorageFailureCategory.AUTHENTICATION
            "INVALIDARGUMENT" -> OssStorageFailureCategory.INVALID_REQUEST
            "INVALIDRESPONSE",
            "CRCINCONSISTENTERROR",
            -> OssStorageFailureCategory.INTEGRITY
            else -> OssStorageFailureCategory.UNKNOWN
        }
    }
    if (failure.hasCause<java.io.IOException>()) return OssStorageFailureCategory.UNAVAILABLE
    if (failure.hasCause<IllegalArgumentException>()) return OssStorageFailureCategory.CONFIGURATION
    return OssStorageFailureCategory.UNKNOWN
}

internal fun ossServiceErrorCode(failure: Throwable): String? =
    failure.findCause<ServiceException>()?.errorCode?.uppercase(Locale.ROOT)

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    return findCause<T>() != null
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is T) return current
        current = current.cause
        depth += 1
    }
    return null
}

private const val MAX_CAUSE_DEPTH = 16
private val RETRYABLE_FAILURE_CATEGORIES: Set<OssStorageFailureCategory> = setOf(
    OssStorageFailureCategory.THROTTLED,
    OssStorageFailureCategory.TIMEOUT,
    OssStorageFailureCategory.UNAVAILABLE,
)
