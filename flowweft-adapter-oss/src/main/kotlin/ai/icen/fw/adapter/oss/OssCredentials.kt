package ai.icen.fw.adapter.oss

/**
 * Provider-neutral OSS credentials resolved at the moment an SDK request is signed.
 *
 * The values are deliberately absent from [toString]. Hosts that rotate STS or
 * RAM-role credentials can implement [OssCredentialsProvider] without exposing
 * an Alibaba Cloud SDK type to FlowWeft callers.
 */
class OssCredentials @JvmOverloads constructor(
    val accessKeyId: String,
    val accessKeySecret: String,
    val securityToken: String? = null,
    /** Absolute epoch milliseconds at which temporary credentials expire. */
    val expiresAt: Long? = null,
) {
    init {
        validateCredentialText(accessKeyId, "OSS access key id", MAX_ACCESS_KEY_ID_LENGTH)
        validateCredentialText(accessKeySecret, "OSS access key secret", MAX_ACCESS_KEY_SECRET_LENGTH)
        securityToken?.let { token ->
            validateCredentialText(token, "OSS security token", MAX_SECURITY_TOKEN_LENGTH)
        }
        require(expiresAt == null || expiresAt > 0) { "OSS credential expiration must be positive." }
    }

    override fun toString(): String = "OssCredentials(redacted)"

    private companion object {
        const val MAX_ACCESS_KEY_ID_LENGTH = 256
        const val MAX_ACCESS_KEY_SECRET_LENGTH = 1_024
        const val MAX_SECURITY_TOKEN_LENGTH = 8_192
    }
}

/**
 * Resolves current OSS credentials. Implementations must be thread-safe and
 * must never log the result.
 */
interface OssCredentialsProvider {
    fun resolve(): OssCredentials
}

/** Static credentials for simple hosts and the isolated real-OSS test lane. */
class StaticOssCredentialsProvider @JvmOverloads constructor(
    accessKeyId: String,
    accessKeySecret: String,
    securityToken: String? = null,
    expiresAt: Long? = null,
) : OssCredentialsProvider {
    private val credentials = OssCredentials(accessKeyId, accessKeySecret, securityToken, expiresAt)

    override fun resolve(): OssCredentials = credentials

    override fun toString(): String = "StaticOssCredentialsProvider(redacted)"
}

private fun validateCredentialText(value: String, label: String, maxLength: Int) {
    require(value.isNotBlank()) { "$label must not be blank." }
    require(value.length <= maxLength) { "$label must not exceed $maxLength characters." }
    require(value.none(::isUnsafeCredentialCharacter)) { "$label must not contain unsafe characters." }
}

private fun isUnsafeCredentialCharacter(character: Char): Boolean =
    Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
