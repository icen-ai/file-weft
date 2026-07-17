package ai.icen.fw.adapter.oss

import java.time.Duration

/** Bounded OSS SDK connection, streaming and retry policy. */
class OssStorageClientPolicy @JvmOverloads constructor(
    val connectionTimeout: Duration = Duration.ofSeconds(5),
    val socketTimeout: Duration = Duration.ofMinutes(2),
    val requestTimeout: Duration = Duration.ofMinutes(15),
    val maxAttempts: Int = 3,
    val credentialExpirySafetyWindow: Duration = Duration.ofSeconds(30),
) {
    init {
        requirePositiveMillis(connectionTimeout, "OSS connection timeout")
        requirePositiveMillis(socketTimeout, "OSS socket timeout")
        requirePositiveMillis(requestTimeout, "OSS request timeout")
        requirePositiveMillis(credentialExpirySafetyWindow, "OSS credential expiry safety window")
        require(connectionTimeout <= MAX_TIMEOUT) { "OSS connection timeout is too large." }
        require(socketTimeout <= MAX_TIMEOUT) { "OSS socket timeout is too large." }
        require(requestTimeout <= MAX_TIMEOUT) { "OSS request timeout is too large." }
        require(credentialExpirySafetyWindow <= MAX_TIMEOUT) {
            "OSS credential expiry safety window is too large."
        }
        require(maxAttempts in 1..MAX_ATTEMPTS) {
            "OSS maximum attempts must be between 1 and $MAX_ATTEMPTS."
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 10
        val MAX_TIMEOUT: Duration = Duration.ofHours(24)

        fun requirePositiveMillis(value: Duration, label: String) {
            require(!value.isNegative && !value.isZero && value.toMillis() > 0) {
                "$label must be at least one millisecond."
            }
        }
    }
}
