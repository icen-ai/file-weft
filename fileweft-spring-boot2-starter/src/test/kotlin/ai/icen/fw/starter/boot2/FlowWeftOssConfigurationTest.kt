package ai.icen.fw.starter.boot2

import ai.icen.fw.adapter.oss.OssCredentialsProvider
import ai.icen.fw.adapter.oss.OssStorageAdapter
import ai.icen.fw.adapter.oss.OssStorageDoctorChecker
import ai.icen.fw.adapter.oss.StaticOssCredentialsProvider
import ai.icen.fw.runtime.plugin.FileWeftPluginRegistry
import ai.icen.fw.spi.plugin.FileWeftPlugin
import ai.icen.fw.spi.storage.StorageAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class FlowWeftOssConfigurationTest {
    @Test
    fun `selects one explicitly enabled OSS plugin without configuring secrets as properties`() {
        runner()
            .withBean(OssCredentialsProvider::class.java, {
                StaticOssCredentialsProvider("test-access-key", "test-access-secret")
            })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(StorageAdapter::class.java)
                assertThat(context.getBean(StorageAdapter::class.java)).isInstanceOf(OssStorageAdapter::class.java)
                assertThat(context.environment.propertySources.toString()).doesNotContain("test-access-secret")
                val plugins = context.getBean(FileWeftPluginRegistry::class.java)
                assertThat(plugins.plugins().map { plugin -> plugin.id() }).contains("flowweft-storage-oss")
                assertThat(plugins.doctorCheckers()).anyMatch { checker -> checker is OssStorageDoctorChecker }
            }
    }

    @Test
    fun `stays disabled without credentials when the property is false`() {
        val customer = Mockito.mock(StorageAdapter::class.java)
        runner(enabled = false)
            .withBean("customerStorageAdapter", StorageAdapter::class.java, { customer })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("flowWeftOssPlugin")
                assertThat(context.getBean(StorageAdapter::class.java)).isSameAs(customer)
            }
    }

    @Test
    fun `backs off for a customer OSS plugin bean with the stable override name`() {
        val customer = Mockito.mock(StorageAdapter::class.java)
        val storageSnapshots = AtomicInteger()
        val customerPlugin = object : FileWeftPlugin {
            override fun id(): String = "flowweft-storage-oss"
            override fun storageAdapters(): List<StorageAdapter> {
                check(storageSnapshots.incrementAndGet() == 1) { "Plugin contributions must be snapshotted once." }
                return listOf(customer)
            }
        }
        runner()
            .withBean("flowWeftOssPlugin", FileWeftPlugin::class.java, { customerPlugin })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(StorageAdapter::class.java)
                assertThat(context.getBean(StorageAdapter::class.java)).isSameAs(customer)
                assertThat(context.getBean("flowWeftOssPlugin")).isSameAs(customerPlugin)
                assertThat(storageSnapshots.get()).isEqualTo(1)
            }
    }

    @Test
    fun `does not let an unrelated customer storage bean silently defeat explicit OSS selection`() {
        runner()
            .withBean(OssCredentialsProvider::class.java, {
                StaticOssCredentialsProvider("test-access-key", "test-access-secret")
            })
            .withBean("customerStorageAdapter", StorageAdapter::class.java, {
                Mockito.mock(StorageAdapter::class.java)
            })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining(
                    "fileweft.storage.oss.enabled requires the OSS adapter to be the one selected StorageAdapter",
                )
            }
    }

    @Test
    fun `fails closed when enabled without a credential provider`() {
        runner().run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining(
                "requires exactly one host-owned OssCredentialsProvider",
            )
        }
    }

    @Test
    fun `fails closed when rotating credential ownership is ambiguous`() {
        runner()
            .withBean("firstCredentials", OssCredentialsProvider::class.java, {
                StaticOssCredentialsProvider("first-key", "first-secret")
            })
            .withBean("secondCredentials", OssCredentialsProvider::class.java, {
                StaticOssCredentialsProvider("second-key", "second-secret")
            })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining(
                    "requires exactly one host-owned OssCredentialsProvider",
                )
            }
    }

    @Test
    fun `rejects unsafe endpoints and does not retain malformed endpoint text in diagnostics`() {
        runner()
            .withBean(OssCredentialsProvider::class.java, {
                StaticOssCredentialsProvider("test-access-key", "test-access-secret")
            })
            .withPropertyValues("fileweft.storage.oss.endpoint=http://oss-cn-hangzhou.aliyuncs.com")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("OSS endpoint must use HTTPS")
            }

        val sensitiveFragment = "should-not-appear-in-startup-diagnostics"
        runner()
            .withBean(OssCredentialsProvider::class.java, {
                StaticOssCredentialsProvider("test-access-key", "test-access-secret")
            })
            .withPropertyValues(
                "fileweft.storage.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com/%ZZ-$sensitiveFragment",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context.startupFailure))
                    .contains("fileweft.storage.oss.endpoint is not a valid URI")
                    .doesNotContain(sensitiveFragment)
            }
    }

    @Test
    fun `rejects conflicting addressing and unbounded client policy`() {
        invalidConfiguration(
            "fileweft.storage.oss.use-path-style=true",
            "fileweft.storage.oss.use-cname=true",
            expectedMessage = "path-style and CNAME modes cannot both be enabled",
        )
        invalidConfiguration(
            "fileweft.storage.oss.connection-timeout-millis=0",
            expectedMessage = "connection timeout must be at least one millisecond",
        )
        invalidConfiguration(
            "fileweft.storage.oss.max-attempts=11",
            expectedMessage = "maximum attempts must be between 1 and 10",
        )
    }

    @Test
    fun `fails closed when the optional OSS runtime is absent`() {
        runner()
            .withClassLoader(FilteredClassLoader("com.aliyun.oss"))
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining(
                    "requires the flowweft-adapter-oss artifact and exactly one OSS plugin",
                )
            }
    }

    @Test
    fun `publishes non-secret OSS configuration metadata with a fixed storage identity`() {
        val metadata = requireNotNull(
            javaClass.getResourceAsStream("/META-INF/additional-spring-configuration-metadata.json"),
        ).bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }

        assertThat(metadata)
            .contains("fileweft.storage.oss.endpoint")
            .contains("fileweft.storage.oss.credential-expiry-safety-window-millis")
            .doesNotContain("fileweft.storage.oss.access-key")
            .doesNotContain("fileweft.storage.oss.security-token")
            .doesNotContain("fileweft.storage.oss.storage-type")
    }

    private fun invalidConfiguration(vararg properties: String, expectedMessage: String) {
        runner()
            .withBean(OssCredentialsProvider::class.java, {
                StaticOssCredentialsProvider("test-access-key", "test-access-secret")
            })
            .withPropertyValues(*properties)
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining(expectedMessage)
            }
    }

    private fun failureMessages(failure: Throwable?): String =
        generateSequence(failure) { current -> current.cause }
            .mapNotNull { current -> current.message }
            .joinToString("\n")

    private fun runner(enabled: Boolean = true): ApplicationContextRunner = ApplicationContextRunner()
        .withUserConfiguration(FileWeftAutoConfiguration::class.java)
        .withPropertyValues(
            "fileweft.default-tenant-enabled=true",
            "fileweft.default-tenant-id=tenant-1",
            "fileweft.storage.oss.enabled=$enabled",
            "fileweft.storage.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
            "fileweft.storage.oss.region=cn-hangzhou",
            "fileweft.storage.oss.bucket=flowweft-test-bucket",
        )
}
