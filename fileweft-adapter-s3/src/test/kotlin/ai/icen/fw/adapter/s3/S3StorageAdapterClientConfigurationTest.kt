package ai.icen.fw.adapter.s3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.time.Duration

/**
 * Asserts on the built S3 client so the timeout configuration cannot silently
 * stop reaching the SDK. Building the client performs no network access.
 */
class S3StorageAdapterClientConfigurationTest {
    @Test
    fun `applies the configured call budgets and the standard retry mode to the client`() {
        val adapter = S3StorageAdapter(
            configuration(
                apiCallTimeout = Duration.ofSeconds(95),
                apiCallAttemptTimeout = Duration.ofSeconds(17),
            ),
        )
        try {
            val overrides = clientOverrides(adapter)
            assertEquals(Duration.ofSeconds(95), overrides.apiCallTimeout().get())
            assertEquals(Duration.ofSeconds(17), overrides.apiCallAttemptTimeout().get())
            assertTrue(overrides.retryStrategy().isPresent)
            assertEquals(3, overrides.retryStrategy().get().maxAttempts())
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `applies the documented default call budgets to the client`() {
        val adapter = S3StorageAdapter(configuration())
        try {
            val overrides = clientOverrides(adapter)
            assertEquals(S3StorageConfiguration.DEFAULT_API_CALL_TIMEOUT, overrides.apiCallTimeout().get())
            assertEquals(S3StorageConfiguration.DEFAULT_API_CALL_ATTEMPT_TIMEOUT, overrides.apiCallAttemptTimeout().get())
            assertTrue(overrides.retryStrategy().isPresent)
            assertEquals(3, overrides.retryStrategy().get().maxAttempts())
        } finally {
            adapter.close()
        }
    }

    private fun clientOverrides(adapter: S3StorageAdapter): ClientOverrideConfiguration {
        val field = S3StorageAdapter::class.java.getDeclaredField("client")
        field.isAccessible = true
        val client = field.get(adapter) as S3Client
        return client.serviceClientConfiguration().overrideConfiguration()
    }

    private fun configuration(
        apiCallTimeout: Duration = S3StorageConfiguration.DEFAULT_API_CALL_TIMEOUT,
        apiCallAttemptTimeout: Duration = S3StorageConfiguration.DEFAULT_API_CALL_ATTEMPT_TIMEOUT,
    ) = S3StorageConfiguration(
        endpoint = URI("http://127.0.0.1:9000"),
        region = "us-east-1",
        accessKey = "access",
        secretKey = "secret",
        bucket = "fileweft-unit",
        apiCallTimeout = apiCallTimeout,
        apiCallAttemptTimeout = apiCallAttemptTimeout,
    )
}
