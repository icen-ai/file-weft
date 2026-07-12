package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.plugin.PluginInventoryQueryService
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.health.HealthApiFacade
import ai.icen.fw.web.runtime.v1.plugin.PluginInventoryApiFacade
import ai.icen.fw.web.spring.boot3.v1.system.V1HealthController
import ai.icen.fw.web.spring.boot3.v1.system.V1PluginInventoryController
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/** Optional Spring Boot 3 transport for safe plugin inventory and process liveness projections. */
@AutoConfiguration(afterName = ["ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"])
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FileWeftWebBoot3SystemProjectionAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(PluginInventoryApiFacade::class)
    fun fileWeftV1PluginInventoryApiFacade(
        plugins: ObjectProvider<PluginInventoryQueryService>,
    ): PluginInventoryApiFacade = PluginInventoryApiFacade(plugins.allCandidates())

    @Bean
    @ConditionalOnMissingBean(HealthApiFacade::class)
    fun fileWeftV1HealthApiFacade(): HealthApiFacade = HealthApiFacade()

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1SystemProjectionApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnMissingBean(V1PluginInventoryController::class)
    fun fileWeftV1PluginInventoryController(
        plugins: PluginInventoryApiFacade,
        responses: V1ApiResponseFactory,
        traces: ObjectProvider<TraceContextProvider>,
    ): V1PluginInventoryController = V1PluginInventoryController(plugins, responses, traces.getIfUnique())

    @Bean
    @ConditionalOnMissingBean(V1HealthController::class)
    fun fileWeftV1HealthController(
        health: HealthApiFacade,
        responses: V1ApiResponseFactory,
        traces: ObjectProvider<TraceContextProvider>,
    ): V1HealthController = V1HealthController(health, responses, traces.getIfUnique())

    private fun <T> ObjectProvider<T>.allCandidates(): List<T> =
        orderedStream().iterator().asSequence().toList()
}
