package ai.icen.fw.adapter.s3

import java.net.URI
import java.time.Duration

/**
 * Vendor-neutral configuration for an S3-compatible storage service.
 *
 * The access key and secret are intentionally supplied by the host application;
 * this library does not read environment variables or configuration files.
 *
 * [apiCallAttemptTimeout] bounds every single HTTP exchange against the service
 * and [apiCallTimeout] bounds one SDK call including its retries, so a wedged
 * connection can never block a storage operation forever. Multipart completion
 * on the supported S3-compatible services is a fast server-side operation;
 * hosts whose backend assembles very large objects slowly can raise both
 * budgets. Response content streams after a call returns, so downloads are not
 * cut off by these budgets; streamed request bodies (upload, upload part) are,
 * which is why the defaults stay generous and configurable.
 */
data class S3StorageConfiguration(
    val endpoint: URI,
    val region: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    val forcePathStyle: Boolean = true,
    val storageType: String = S3StorageAdapter.STORAGE_TYPE,
    val apiCallTimeout: Duration = DEFAULT_API_CALL_TIMEOUT,
    val apiCallAttemptTimeout: Duration = DEFAULT_API_CALL_ATTEMPT_TIMEOUT,
) {
    init {
        require(endpoint.isAbsolute) { "S3 endpoint must be an absolute URI." }
        require(endpoint.scheme == "http" || endpoint.scheme == "https") {
            "S3 endpoint must use HTTP or HTTPS."
        }
        require(region.isNotBlank()) { "S3 region must not be blank." }
        require(accessKey.isNotBlank()) { "S3 access key must not be blank." }
        require(secretKey.isNotBlank()) { "S3 secret key must not be blank." }
        require(BUCKET_NAME_PATTERN.matches(bucket)) { "S3 bucket name is invalid." }
        require(storageType.isNotBlank()) { "Storage type must not be blank." }
        require(apiCallTimeout.isPositive()) { "S3 API call timeout must be positive." }
        require(apiCallAttemptTimeout.isPositive()) { "S3 API call attempt timeout must be positive." }
        require(apiCallAttemptTimeout <= apiCallTimeout) {
            "S3 API call attempt timeout must not exceed the overall API call timeout."
        }
    }

    companion object {
        /** Default overall budget for one S3 API call including retries. */
        val DEFAULT_API_CALL_TIMEOUT: Duration = Duration.ofMinutes(2)

        /** Default budget for a single S3 API call attempt. */
        val DEFAULT_API_CALL_ATTEMPT_TIMEOUT: Duration = Duration.ofSeconds(30)

        private val BUCKET_NAME_PATTERN = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
    }

    private fun Duration.isPositive(): Boolean = !isZero && !isNegative
}

