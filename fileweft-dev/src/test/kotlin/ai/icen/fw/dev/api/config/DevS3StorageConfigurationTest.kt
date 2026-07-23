package ai.icen.fw.dev.api.config

import ai.icen.fw.adapter.s3.S3StorageAdapter
import ai.icen.fw.adapter.s3.S3StorageConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.mock.env.MockEnvironment
import java.time.Duration

class DevS3StorageConfigurationTest {
    @Test
    fun `storage timeout properties default to the adapter budgets`() {
        val properties = FileWeftDevProperties()

        assertEquals(S3StorageConfiguration.DEFAULT_API_CALL_TIMEOUT.toMillis(), properties.storage.apiCallTimeoutMillis)
        assertEquals(S3StorageConfiguration.DEFAULT_API_CALL_ATTEMPT_TIMEOUT.toMillis(), properties.storage.apiCallAttemptTimeoutMillis)
    }

    @Test
    fun `binds storage timeout properties from the environment`() {
        val environment = MockEnvironment()
            .withProperty("fileweft.dev.storage.api-call-timeout-millis", "45000")
            .withProperty("fileweft.dev.storage.api-call-attempt-timeout-millis", "15000")

        val properties = Binder.get(environment).bind("fileweft.dev", FileWeftDevProperties::class.java).get()

        assertEquals(45_000, properties.storage.apiCallTimeoutMillis)
        assertEquals(15_000, properties.storage.apiCallAttemptTimeoutMillis)
    }

    @Test
    fun `passes the storage timeout properties into the S3 adapter configuration`() {
        val properties = FileWeftDevProperties()
        properties.storage.apiCallTimeoutMillis = 45_000
        properties.storage.apiCallAttemptTimeoutMillis = 15_000

        val adapter = DevApiConfiguration().devS3StorageAdapter(properties)
        try {
            val configuration = adapterConfiguration(adapter)
            assertEquals(Duration.ofMillis(45_000), configuration.apiCallTimeout)
            assertEquals(Duration.ofMillis(15_000), configuration.apiCallAttemptTimeout)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `keeps the adapter default budgets when the properties are not overridden`() {
        val adapter = DevApiConfiguration().devS3StorageAdapter(FileWeftDevProperties())
        try {
            val configuration = adapterConfiguration(adapter)
            assertEquals(S3StorageConfiguration.DEFAULT_API_CALL_TIMEOUT, configuration.apiCallTimeout)
            assertEquals(S3StorageConfiguration.DEFAULT_API_CALL_ATTEMPT_TIMEOUT, configuration.apiCallAttemptTimeout)
        } finally {
            adapter.close()
        }
    }

    private fun adapterConfiguration(adapter: S3StorageAdapter): S3StorageConfiguration {
        val field = S3StorageAdapter::class.java.getDeclaredField("configuration")
        field.isAccessible = true
        return field.get(adapter) as S3StorageConfiguration
    }
}
