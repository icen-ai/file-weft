package ai.icen.fw.starter.boot2

import ai.icen.fw.adapter.authorization.DefaultAuthorizationProvider
import ai.icen.fw.adapter.id.UuidIdentifierGenerator
import ai.icen.fw.adapter.micrometer.MicrometerFileWeftGauges
import ai.icen.fw.adapter.micrometer.MicrometerFileWeftMetrics
import ai.icen.fw.adapter.observability.NoOpFileWeftMetrics
import ai.icen.fw.adapter.observability.NoOpTraceContextProvider
import ai.icen.fw.adapter.identity.DefaultUserRealmProvider
import ai.icen.fw.adapter.storage.LocalStorageAdapter
import ai.icen.fw.adapter.tenant.FixedTenantProvider
import ai.icen.fw.application.plugin.PluginCapabilityType
import ai.icen.fw.application.plugin.PluginInventoryProvider
import ai.icen.fw.application.plugin.PluginInventoryQueryService
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
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.AllNestedConditions
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase
import org.springframework.core.env.Environment
import java.nio.file.Paths
import java.time.Clock
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry

private const val LEGACY_AGENT_AUTOCONFIGURATION_PROPERTY =
    "fileweft.compatibility.legacy-agent-autoconfiguration-enabled"

@AutoConfiguration(after = [DataSourceAutoConfiguration::class, FlywayAutoConfiguration::class, JacksonAutoConfiguration::class])
@EnableConfigurationProperties(FileWeftProperties::class)
@Import(
    FlowWeftOssConfiguration::class,
    FileWeftMigrationConfiguration::class,
    FileWeftRuntimeConfiguration::class,
    FileWeftWorkerSchedulingConfiguration::class,
)
class FileWeftAutoConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "fileweft.storage.oss", name = ["enabled"], havingValue = "true")
    fun flowWeftOssSelectionValidator(
        plugins: FileWeftPluginRegistry,
        storageCandidates: ObjectProvider<StorageAdapter>,
    ): SmartInitializingSingleton {
        val matchingPlugins = plugins.inventory().filter { plugin -> plugin.id == FLOWWEFT_OSS_PLUGIN_ID }
        require(matchingPlugins.size == 1) {
            "fileweft.storage.oss.enabled requires the flowweft-adapter-oss artifact and exactly one OSS plugin."
        }
        val storageCapability = matchingPlugins.single().capabilities.singleOrNull { capability ->
            capability.type == PluginCapabilityType.STORAGE_ADAPTER
        }
        require(storageCapability?.count == 1) {
            "The FlowWeft OSS plugin must contribute exactly one StorageAdapter."
        }
        val contributed = plugins.storageAdapters()
        require(contributed.size == 1) {
            "fileweft.storage.oss.enabled requires the OSS plugin to be the only plugin StorageAdapter."
        }
        val expected = contributed.single()
        return SmartInitializingSingleton {
            val selected = storageCandidates.orderedStream().iterator().asSequence().toList()
            require(selected.size == 1 && selected.single() === expected) {
                "fileweft.storage.oss.enabled requires the OSS adapter to be the one selected StorageAdapter."
            }
        }
    }

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
    fun fileWeftPluginRegistry(
        plugins: List<FileWeftPlugin>,
        environment: Environment,
    ): FileWeftPluginRegistry = if (
        environment.getProperty(LEGACY_AGENT_AUTOCONFIGURATION_PROPERTY, Boolean::class.java, false)
    ) {
        FileWeftPluginRegistry.withLegacyAgentCompatibility(plugins)
    } else {
        FileWeftPluginRegistry(plugins)
    }

    @Bean
    @ConditionalOnMissingBean(PluginInventoryQueryService::class)
    @Conditional(TrustedPluginInventoryBoundariesCondition::class)
    fun fileWeftPluginInventoryQueryService(
        tenant: TenantProvider,
        user: UserRealmProvider,
        authorization: AuthorizationProvider,
        providers: ObjectProvider<PluginInventoryProvider>,
    ): PluginInventoryQueryService = PluginInventoryQueryService(
        tenant,
        user,
        authorization,
        requiredPluginInventoryProvider(providers),
    )

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

    private fun requiredPluginInventoryProvider(
        providers: ObjectProvider<PluginInventoryProvider>,
    ): PluginInventoryProvider {
        val candidates = providers.orderedStream().iterator().asSequence().toList()
        val explicit = candidates.filterNot { candidate -> candidate is FileWeftPluginRegistry }
        val registries = candidates.filterIsInstance<FileWeftPluginRegistry>()
        if (explicit.size > 1) {
            throw NoUniqueBeanDefinitionException(
                PluginInventoryProvider::class.java,
                explicit.size,
                "Plugin inventory queries require at most one explicit PluginInventoryProvider.",
            )
        }
        if (registries.size > 1) {
            throw NoUniqueBeanDefinitionException(
                FileWeftPluginRegistry::class.java,
                registries.size,
                "Plugin inventory queries require exactly one default FileWeftPluginRegistry.",
            )
        }
        return explicit.singleOrNull() ?: registries.singleOrNull()
            ?: throw NoSuchBeanDefinitionException(PluginInventoryProvider::class.java)
    }

    private class TrustedPluginInventoryBoundariesCondition : AllNestedConditions(ConfigurationPhase.REGISTER_BEAN) {
        @ConditionalOnSingleCandidate(TenantProvider::class)
        class Tenant

        @ConditionalOnSingleCandidate(UserRealmProvider::class)
        class User

        @ConditionalOnSingleCandidate(AuthorizationProvider::class)
        class Authorization
    }

    private companion object {
        const val FLOWWEFT_OSS_PLUGIN_ID: String = "flowweft-storage-oss"
    }
}
