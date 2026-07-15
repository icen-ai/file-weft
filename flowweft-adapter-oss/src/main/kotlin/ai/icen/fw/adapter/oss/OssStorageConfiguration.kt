package ai.icen.fw.adapter.oss

import java.net.URI

/**
 * Alibaba Cloud OSS endpoint and bucket configuration.
 *
 * HTTPS is mandatory and TLS verification is never disabled. Query strings,
 * fragments, endpoint credentials and non-root paths are forbidden so secrets
 * cannot be smuggled into SDK configuration.
 */
class OssStorageConfiguration @JvmOverloads constructor(
    val endpoint: URI,
    val region: String,
    val bucket: String,
    val credentialsProvider: OssCredentialsProvider,
    val usePathStyle: Boolean = false,
    val useCName: Boolean = false,
    val storageType: String = OssStorageAdapter.STORAGE_TYPE,
) {
    init {
        require(endpoint.isAbsolute) { "OSS endpoint must be an absolute URI." }
        require(endpoint.scheme.equals("https", ignoreCase = true)) { "OSS endpoint must use HTTPS." }
        require(!endpoint.host.isNullOrBlank()) { "OSS endpoint must contain a host." }
        require(endpoint.port == -1 || endpoint.port in 1..MAX_TCP_PORT) {
            "OSS endpoint port must be absent or between 1 and $MAX_TCP_PORT."
        }
        require(endpoint.userInfo == null) { "OSS endpoint must not contain user information." }
        require(endpoint.query == null) { "OSS endpoint must not contain a query." }
        require(endpoint.fragment == null) { "OSS endpoint must not contain a fragment." }
        require(endpoint.path.isNullOrEmpty() || endpoint.path == "/") {
            "OSS endpoint must not contain a non-root path."
        }
        require(REGION_PATTERN.matches(region)) { "OSS region is invalid." }
        require(BUCKET_NAME_PATTERN.matches(bucket)) { "OSS bucket name is invalid." }
        require(!(usePathStyle && useCName)) { "OSS path-style and CNAME modes cannot both be enabled." }
        require(storageType.isNotBlank()) { "Storage type must not be blank." }
        require(storageType.length <= MAX_STORAGE_TYPE_LENGTH) { "Storage type is too long." }
        require(storageType.none(::isUnsafeConfigurationCharacter)) {
            "Storage type must not contain unsafe characters."
        }
        // Validate static providers at construction while preserving support
        // for genuinely rotating providers whose first lookup may be remote.
        if (credentialsProvider is StaticOssCredentialsProvider) {
            credentialsProvider.resolve()
        }
    }

    override fun toString(): String =
        "OssStorageConfiguration(endpointScheme=${endpoint.scheme}, region=$region, " +
            "bucketFingerprint=${safeFingerprint(bucket)}, usePathStyle=$usePathStyle, " +
            "useCName=$useCName, storageType=$storageType, credentials=redacted)"

    private companion object {
        const val MAX_STORAGE_TYPE_LENGTH = 64
        const val MAX_TCP_PORT = 65_535
        val REGION_PATTERN = Regex("[a-z0-9][a-z0-9-]{1,63}")
        val BUCKET_NAME_PATTERN = Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
    }
}

private fun isUnsafeConfigurationCharacter(character: Char): Boolean =
    Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
