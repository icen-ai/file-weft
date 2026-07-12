package ai.icen.fw.starter.boot3

import ai.icen.fw.adapter.authorization.DefaultAuthorizationProvider
import ai.icen.fw.adapter.id.UuidIdentifierGenerator
import ai.icen.fw.adapter.micrometer.MicrometerFileWeftGauges
import ai.icen.fw.adapter.micrometer.MicrometerFileWeftMetrics
import ai.icen.fw.adapter.observability.NoOpFileWeftMetrics
import ai.icen.fw.adapter.observability.NoOpTraceContextProvider
import ai.icen.fw.adapter.identity.DefaultUserRealmProvider
import ai.icen.fw.adapter.storage.LocalStorageAdapter
import ai.icen.fw.adapter.tenant.FixedTenantProvider
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.runtime.plugin.FileWeftPluginRegistry
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftGaugeRecorder
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.spi.plugin.FileWeftPlugin
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.tenant.TenantProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.nio.file.Paths
import java.time.Clock
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry

@AutoConfiguration(after = [DataSourceAutoConfiguration::class, JacksonAutoConfiguration::class])
@EnableConfigurationProperties(FileWeftProperties::class)
@Import(
    FileWeftRuntimeConfiguration::class,
    FileWeftDoctorConfiguration::class,
    FileWeftWorkerSchedulingConfiguration::class,
)
class FileWeftAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(TenantProvider::class)
    fun fileWeftTenantProvider(properties: FileWeftProperties): TenantProvider {
        require(properties.defaultTenantEnabled) {
            "No TenantProvider is configured. Register a trusted TenantProvider bean or explicitly set " +
                "fileweft.default-tenant-enabled=true for a fixed single-tenant deployment."
        }
        require(properties.defaultTenantId.isNotBlank()) {
            "fileweft.default-tenant-id must not be blank when fileweft.default-tenant-enabled=true."
        }
        return FixedTenantProvider(properties.defaultTenantId)
    }

    @Bean
    @ConditionalOnMissingBean(UserRealmProvider::class)
    fun fileWeftUserRealmProvider(): UserRealmProvider = DefaultUserRealmProvider()

    @Bean
    @ConditionalOnMissingBean(AuthorizationProvider::class)
    fun fileWeftAuthorizationProvider(): AuthorizationProvider = DefaultAuthorizationProvider()

    @Bean
    @ConditionalOnMissingBean(StorageAdapter::class)
    fun fileWeftStorageAdapter(properties: FileWeftProperties, plugins: FileWeftPluginRegistry): StorageAdapter {
        val pluginAdapters = plugins.storageAdapters()
        require(pluginAdapters.size <= 1) {
            "More than one plugin StorageAdapter is available; register the intended adapter as a customer Spring bean."
        }
        pluginAdapters.singleOrNull()?.let { return it }
        require(properties.storage.localEnabled) {
            "No StorageAdapter is configured. Register a StorageAdapter bean, install exactly one storage plugin, " +
                "or explicitly set fileweft.storage.local-enabled=true to use local filesystem storage."
        }
        require(properties.storage.localRoot.isNotBlank()) { "fileweft.storage.local-root must not be blank." }
        return LocalStorageAdapter(Paths.get(properties.storage.localRoot))
    }

    @Bean
    @ConditionalOnMissingBean(FileWeftPluginRegistry::class)
    fun fileWeftPluginRegistry(plugins: List<FileWeftPlugin>): FileWeftPluginRegistry = FileWeftPluginRegistry(plugins)

    @Bean
    @ConditionalOnMissingBean(IdentifierGenerator::class)
    fun fileWeftIdentifierGenerator(): IdentifierGenerator = UuidIdentifierGenerator()

    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun fileWeftClock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnMissingBean(ObjectMapper::class)
    fun fileWeftObjectMapper(): ObjectMapper = ObjectMapper()

    @Bean
    @ConditionalOnMissingBean(FileWeftMetrics::class)
    @ConditionalOnBean(MeterRegistry::class)
    fun fileWeftMicrometerMetrics(meterRegistry: MeterRegistry): FileWeftMetrics = MicrometerFileWeftMetrics(meterRegistry)

    @Bean
    @ConditionalOnMissingBean(FileWeftGaugeRecorder::class)
    @ConditionalOnBean(MeterRegistry::class)
    fun fileWeftMicrometerGauges(meterRegistry: MeterRegistry): FileWeftGaugeRecorder = MicrometerFileWeftGauges(meterRegistry)

    @Bean
    @ConditionalOnMissingBean(value = [FileWeftMetrics::class, MeterRegistry::class])
    fun fileWeftMetrics(): FileWeftMetrics = NoOpFileWeftMetrics()

    @Bean
    @ConditionalOnMissingBean(TraceContextProvider::class)
    fun fileWeftTraceContextProvider(): NoOpTraceContextProvider = NoOpTraceContextProvider()
}
