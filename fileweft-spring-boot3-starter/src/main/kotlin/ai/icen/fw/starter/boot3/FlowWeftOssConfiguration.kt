package ai.icen.fw.starter.boot3

import ai.icen.fw.adapter.oss.OssCredentialsProvider
import ai.icen.fw.adapter.oss.OssStorageAdapter
import ai.icen.fw.adapter.oss.OssStorageClientPolicy
import ai.icen.fw.adapter.oss.OssStorageConfiguration
import ai.icen.fw.adapter.oss.OssStorageDoctorChecker
import ai.icen.fw.spi.doctor.DoctorChecker
import ai.icen.fw.spi.plugin.FileWeftPlugin
import ai.icen.fw.spi.storage.StorageAdapter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.time.Duration

/** Optional OSS plugin composition; credential values remain in a host-owned rotating provider bean. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(
    name = [
        "ai.icen.fw.adapter.oss.OssStorageAdapter",
        "com.aliyun.oss.OSS",
    ],
)
@ConditionalOnProperty(prefix = "fileweft.storage.oss", name = ["enabled"], havingValue = "true")
class FlowWeftOssConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["flowWeftOssPlugin"])
    fun flowWeftOssPlugin(
        properties: FileWeftProperties,
        credentialProviders: ObjectProvider<OssCredentialsProvider>,
    ): FileWeftPlugin {
        val credentials = credentialProviders.orderedStream().iterator().asSequence().toList()
        require(credentials.size == 1) {
            "fileweft.storage.oss.enabled requires exactly one host-owned OssCredentialsProvider bean; " +
                "found ${credentials.size}."
        }
        val oss = properties.storage.oss
        val configuration = OssStorageConfiguration(
            endpoint = parseOssEndpoint(oss.endpoint),
            region = oss.region,
            bucket = oss.bucket,
            credentialsProvider = credentials.single(),
            usePathStyle = oss.usePathStyle,
            useCName = oss.useCName,
        )
        val policy = OssStorageClientPolicy(
            connectionTimeout = Duration.ofMillis(oss.connectionTimeoutMillis),
            socketTimeout = Duration.ofMillis(oss.socketTimeoutMillis),
            requestTimeout = Duration.ofMillis(oss.requestTimeoutMillis),
            maxAttempts = oss.maxAttempts,
            credentialExpirySafetyWindow = Duration.ofMillis(oss.credentialExpirySafetyWindowMillis),
        )
        return FlowWeftOssPlugin(OssStorageAdapter(configuration, policy))
    }
}

private fun parseOssEndpoint(value: String): URI = try {
    URI.create(value)
} catch (_: IllegalArgumentException) {
    // URI.create includes the rejected value in its exception message. Do not
    // retain it because operators occasionally paste credential-bearing URLs.
    throw IllegalArgumentException("fileweft.storage.oss.endpoint is not a valid URI.")
}

private class FlowWeftOssPlugin(
    private val adapter: OssStorageAdapter,
) : FileWeftPlugin, AutoCloseable {
    private val storage: List<StorageAdapter> = listOf(adapter)
    private val doctors: List<DoctorChecker> = listOf(OssStorageDoctorChecker(adapter))

    override fun id(): String = PLUGIN_ID

    override fun storageAdapters(): List<StorageAdapter> = storage

    override fun doctorCheckers(): List<DoctorChecker> = doctors

    override fun close() = adapter.close()

    private companion object {
        const val PLUGIN_ID: String = "flowweft-storage-oss"
    }
}
