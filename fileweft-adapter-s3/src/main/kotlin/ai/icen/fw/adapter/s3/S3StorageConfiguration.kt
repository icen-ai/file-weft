package ai.icen.fw.adapter.s3

import java.net.URI

/**
 * Vendor-neutral configuration for an S3-compatible storage service.
 *
 * The access key and secret are intentionally supplied by the host application;
 * this library does not read environment variables or configuration files.
 */
data class S3StorageConfiguration(
    val endpoint: URI,
    val region: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    val forcePathStyle: Boolean = true,
    val storageType: String = S3StorageAdapter.STORAGE_TYPE,
) {
    init {
        require(endpoint.isAbsolute) { "S3 endpoint must be an absolute URI." }
        require(endpoint.scheme == "http" || endpoint.scheme == "https") {
            "S3 endpoint must use HTTP or HTTPS."
        }
        require(!endpoint.host.isNullOrBlank()) { "S3 endpoint must contain a host." }
        require(endpoint.userInfo == null) { "S3 endpoint must not contain user information." }
        require(endpoint.query == null) { "S3 endpoint must not contain a query." }
        require(endpoint.fragment == null) { "S3 endpoint must not contain a fragment." }
        require(
            region.isNotBlank() &&
                region.none { character -> Character.isISOControl(character) },
        ) {
            "S3 region is invalid."
        }
        require(
            accessKey.isNotBlank() &&
                accessKey.length <= MAX_ACCESS_KEY_LENGTH &&
                accessKey.none { character -> Character.isISOControl(character) },
        ) {
            "S3 access key is invalid."
        }
        require(
            secretKey.isNotBlank() &&
                secretKey.length <= MAX_SECRET_KEY_LENGTH &&
                secretKey.none { character -> Character.isISOControl(character) },
        ) {
            "S3 secret key is invalid."
        }
        require(BUCKET_NAME_PATTERN.matches(bucket)) { "S3 bucket name is invalid." }
        require(
            storageType.isNotBlank() &&
                storageType.none { character -> Character.isISOControl(character) },
        ) {
            "Storage type is invalid."
        }
    }

    /**
     * Do not let configuration interpolation or ordinary logger arguments
     * expose credentials, the bucket, or an endpoint path/query capability.
     */
    override fun toString(): String =
        "S3StorageConfiguration(" +
            "endpointScheme=${endpoint.scheme}, " +
            "region=$region, " +
            "credentials=<redacted>, " +
            "bucket=<redacted>, " +
            "forcePathStyle=$forcePathStyle, " +
            "storageType=$storageType)"

    private companion object {
        val BUCKET_NAME_PATTERN = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
        const val MAX_ACCESS_KEY_LENGTH = 256
        const val MAX_SECRET_KEY_LENGTH = 2_048
    }
}
