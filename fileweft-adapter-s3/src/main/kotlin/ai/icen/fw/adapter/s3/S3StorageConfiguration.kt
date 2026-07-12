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
        require(region.isNotBlank()) { "S3 region must not be blank." }
        require(accessKey.isNotBlank()) { "S3 access key must not be blank." }
        require(secretKey.isNotBlank()) { "S3 secret key must not be blank." }
        require(BUCKET_NAME_PATTERN.matches(bucket)) { "S3 bucket name is invalid." }
        require(storageType.isNotBlank()) { "Storage type must not be blank." }
    }

    private companion object {
        val BUCKET_NAME_PATTERN = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
    }
}
