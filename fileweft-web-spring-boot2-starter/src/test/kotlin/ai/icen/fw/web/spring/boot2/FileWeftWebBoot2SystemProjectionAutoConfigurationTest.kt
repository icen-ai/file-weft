package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.plugin.PluginInventoryQueryService
import ai.icen.fw.web.api.v1.plugin.PluginPageQuery
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.health.HealthApiFacade
import ai.icen.fw.web.runtime.v1.plugin.PluginInventoryApiFacade
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.io.support.SpringFactoriesLoader
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileWeftWebBoot2SystemProjectionAutoConfigurationTest {
    private val contextRunner = WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(FileWeftWebBoot2SystemProjectionAutoConfiguration::class.java),
    )

    @Test
    fun `registers system projection auto configuration through spring factories`() {
        val factories = SpringFactoriesLoader.loadFactoryNames(
            EnableAutoConfiguration::class.java,
            FileWeftWebBoot2SystemProjectionAutoConfiguration::class.java.classLoader,
        )

        assertTrue(factories.contains(FileWeftWebBoot2SystemProjectionAutoConfiguration::class.java.name))
        assertTrue(factories.contains(FileWeftWebBoot2AuditLogAutoConfiguration::class.java.name))
    }

    @Test
    fun `always installs health and a fail closed plugin route`() {
        contextRunner.run { context ->
            assertEquals(1, context.getBeansOfType(HealthApiFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(HealthV1Controller::class.java).size)
            assertEquals(1, context.getBeansOfType(PluginInventoryApiFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(PluginInventoryV1Controller::class.java).size)
            assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)

            assertThrows<IllegalStateException> {
                context.getBean(PluginInventoryApiFacade::class.java).page(PluginPageQuery())
            }
            assertEquals("UP", context.getBean(HealthApiFacade::class.java).inspect().status)
        }
    }

    @Test
    fun `uses one application query service and backs off from host facades`() {
        val query = Mockito.mock(PluginInventoryQueryService::class.java)
        contextRunner.withBean(PluginInventoryQueryService::class.java, Supplier { query }).run { context ->
            assertEquals(1, context.getBeansOfType(PluginInventoryApiFacade::class.java).size)
            assertNotNull(context.getBeanProvider(PluginInventoryV1Controller::class.java).getIfAvailable())
        }

        val hostPlugins = PluginInventoryApiFacade(emptyList<PluginInventoryQueryService>())
        val hostHealth = HealthApiFacade()
        contextRunner
            .withBean(PluginInventoryApiFacade::class.java, Supplier { hostPlugins })
            .withBean(HealthApiFacade::class.java, Supplier { hostHealth })
            .run { context ->
                assertTrue(context.getBean(PluginInventoryApiFacade::class.java) === hostPlugins)
                assertTrue(context.getBean(HealthApiFacade::class.java) === hostHealth)
                assertEquals(1, context.getBeansOfType(PluginInventoryV1Controller::class.java).size)
                assertEquals(1, context.getBeansOfType(HealthV1Controller::class.java).size)
            }
    }

    @Test
    fun `fails startup for multiple plugin query services even when one is primary`() {
        contextRunner.withUserConfiguration(MultiplePluginQueries::class.java).run { context ->
            val failure = assertNotNull(context.startupFailure)
            assertTrue(failure.causeChain().any { cause ->
                cause.message?.contains("multiple query-service candidates") == true
            })
        }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { failure -> failure.cause }

    @Configuration(proxyBeanMethods = false)
    internal class MultiplePluginQueries {
        @Bean
        @Primary
        fun primaryQuery(): PluginInventoryQueryService = Mockito.mock(PluginInventoryQueryService::class.java)

        @Bean
        fun secondaryQuery(): PluginInventoryQueryService = Mockito.mock(PluginInventoryQueryService::class.java)
    }
}
