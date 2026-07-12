package ai.icen.fw.spi.connector

import java.time.Duration

data class ConnectorInvocation(
    val idempotencyKey: String,
    val timeout: Duration,
    val attempt: Int = 1,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "Idempotency key must not be blank." }
        require(!timeout.isNegative && !timeout.isZero) { "Timeout must be positive." }
        require(attempt > 0) { "Attempt must be positive." }
    }
}
