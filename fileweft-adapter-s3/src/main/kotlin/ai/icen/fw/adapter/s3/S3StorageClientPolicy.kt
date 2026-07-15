package ai.icen.fw.adapter.s3

import java.time.Duration

/**
 * Bounded network policy for an S3-compatible service.
 *
 * This is separate from [S3StorageConfiguration] so the already-published
 * configuration constructor remains binary compatible. Values describe one
 * SDK operation. [maxAttempts] applies only to side-effect-free reads and
 * probes; mutations are always issued through a single-attempt client.
 */
class S3StorageClientPolicy @JvmOverloads constructor(
    val connectionTimeout: Duration = Duration.ofSeconds(5),
    val socketTimeout: Duration = Duration.ofSeconds(30),
    val apiCallAttemptTimeout: Duration = Duration.ofMinutes(10),
    val apiCallTimeout: Duration = Duration.ofMinutes(30),
    val maxAttempts: Int = 3,
) {
    init {
        requirePositiveMillis(connectionTimeout, "S3 connection timeout")
        requirePositiveMillis(socketTimeout, "S3 socket timeout")
        requirePositiveMillis(apiCallAttemptTimeout, "S3 API call attempt timeout")
        requirePositiveMillis(apiCallTimeout, "S3 API call timeout")
        require(apiCallAttemptTimeout <= apiCallTimeout) {
            "S3 API call attempt timeout must not exceed the overall API call timeout."
        }
        require(maxAttempts in 1..MAX_ATTEMPTS) {
            "S3 maximum attempts must be between 1 and $MAX_ATTEMPTS."
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 10

        fun requirePositiveMillis(value: Duration, label: String) {
            require(!value.isNegative && !value.isZero && value.toMillis() > 0) {
                "$label must be at least one millisecond."
            }
        }
    }
}
