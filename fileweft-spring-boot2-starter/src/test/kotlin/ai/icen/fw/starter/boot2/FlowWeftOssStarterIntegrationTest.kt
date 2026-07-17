package ai.icen.fw.starter.boot2

import ai.icen.fw.adapter.oss.OssCredentialsProvider
import ai.icen.fw.adapter.oss.OssStorageAdapter
import ai.icen.fw.adapter.oss.OssStorageDoctorChecker
import ai.icen.fw.adapter.oss.StaticOssCredentialsProvider
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.runtime.plugin.FileWeftPluginRegistry
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageUploadRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class FlowWeftOssStarterIntegrationTest {
    @Test
    fun `Boot 2 context composes the real OSS adapter for a host credential provider`() {
        check(System.getenv("FLOWWEFT_RUN_OSS_TESTS") == "true") {
            "OSS Starter integration tests must run only through the fail-closed Gradle task."
        }
        assertEquals("21", System.getProperty("java.specification.version"))

        val credentials = StaticOssCredentialsProvider(
            requiredEnvironment("FLOWWEFT_OSS_ACCESS_KEY_ID"),
            requiredEnvironment("FLOWWEFT_OSS_ACCESS_KEY_SECRET"),
            requiredEnvironment("FLOWWEFT_OSS_SECURITY_TOKEN"),
            requiredCredentialExpiration(),
        )
        ApplicationContextRunner()
            .withUserConfiguration(FileWeftAutoConfiguration::class.java)
            .withBean("hostOssCredentialsProvider", OssCredentialsProvider::class.java, { credentials })
            .withPropertyValues(
                "fileweft.default-tenant-enabled=true",
                "fileweft.default-tenant-id=oss-starter-boot2",
                "fileweft.storage.oss.enabled=true",
                "fileweft.storage.oss.endpoint=${requiredEnvironment("FLOWWEFT_OSS_ENDPOINT")}",
                "fileweft.storage.oss.region=${requiredEnvironment("FLOWWEFT_OSS_REGION")}",
                "fileweft.storage.oss.bucket=${requiredEnvironment("FLOWWEFT_OSS_BUCKET")}",
            )
            .run { context ->
                check(context.startupFailure == null) {
                    "FlowWeft OSS Starter context failed; sensitive configuration is intentionally omitted."
                }
                val adapter = context.getBean(StorageAdapter::class.java)
                assertTrue(adapter is OssStorageAdapter)
                val plugins = context.getBean(FileWeftPluginRegistry::class.java)
                assertTrue(plugins.doctorCheckers().any { checker -> checker is OssStorageDoctorChecker })
                assertRoundTrip(adapter)
            }
        // ApplicationContextRunner closes the context and the OSS plugin's destroy method closes the adapter.
    }

    private fun assertRoundTrip(adapter: StorageAdapter) {
        val content = "flowweft-boot2-oss-starter-${UUID.randomUUID()}".toByteArray(StandardCharsets.UTF_8)
        val stored = adapter.upload(
            StorageUploadRequest(
                tenantId = Identifier("oss-starter-boot2-${UUID.randomUUID()}"),
                objectName = "starter-context-smoke.txt",
                contentLength = content.size.toLong(),
                contentType = "text/plain",
                metadata = mapOf("suite" to "boot2-starter-context"),
            ),
            ByteArrayInputStream(content),
        )
        try {
            assertTrue(adapter.exists(stored.location))
            val downloaded = adapter.download(stored.location)
            assertEquals(content.size.toLong(), downloaded.contentLength)
            downloaded.content.use { input -> assertArrayEquals(content, input.readBytes()) }
        } finally {
            adapter.delete(stored.location)
        }
        assertFalse(adapter.exists(stored.location))
    }

    private fun requiredEnvironment(name: String): String =
        requireNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) {
            "$name is required for the opt-in OSS Starter integration lane."
        }

    private fun requiredCredentialExpiration(): Long {
        val expiresAt = try {
            Instant.parse(requiredEnvironment("FLOWWEFT_OSS_CREDENTIAL_EXPIRES_AT")).toEpochMilli()
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("FLOWWEFT_OSS_CREDENTIAL_EXPIRES_AT must be an ISO-8601 instant.")
        }
        require(expiresAt > System.currentTimeMillis()) {
            "FLOWWEFT_OSS_CREDENTIAL_EXPIRES_AT must be in the future."
        }
        return expiresAt
    }
}
