package com.fileweft.web.spring.boot2

import com.fileweft.application.workflow.WorkflowQueryService
import com.fileweft.core.context.TraceContext
import com.fileweft.core.id.Identifier
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.workflow.WorkflowApiReadFacade
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.io.support.SpringFactoriesLoader
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileWeftWebBoot2WorkflowAutoConfigurationTest {
    @Test
    fun `does not register workflow web capability without a secure query service`() {
        contextRunner().run { context ->
            assertTrue(context.getBeansOfType(WorkflowApiReadFacade::class.java).isEmpty())
            assertTrue(context.getBeansOfType(WorkflowV1Controller::class.java).isEmpty())
            assertTrue(context.getBeansOfType(V1ApiResponseFactory::class.java).isEmpty())
        }
    }

    @Test
    fun `registers one facade response factory and controller for one query service`() {
        contextRunner().withUserConfiguration(SingleServiceConfiguration::class.java).run { context ->
            assertEquals(1, context.getBeansOfType(WorkflowQueryService::class.java).size)
            assertEquals(1, context.getBeansOfType(WorkflowApiReadFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(WorkflowV1Controller::class.java).size)
            assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
        }
    }

    @Test
    fun `fails startup for multiple query services even when one is primary`() {
        assertStartupFailure(
            MultipleServicesConfiguration::class.java,
            "Formal workflow API requires exactly one workflow query service.",
        )
    }

    @Test
    fun `fails startup for multiple workflow facades even when one is primary`() {
        assertStartupFailure(
            MultipleFacadesConfiguration::class.java,
            "Formal workflow API requires exactly one workflow API facade.",
        )
    }

    @Test
    fun `fails startup for multiple trace providers even when one is primary`() {
        assertStartupFailure(
            MultipleTracesConfiguration::class.java,
            "Formal workflow API requires at most one trace context provider.",
        )
    }

    @Test
    fun `backs off to customer facade and controller`() {
        contextRunner().withUserConfiguration(CustomerWebConfiguration::class.java).run { context ->
            val facade = context.getBean("customerWorkflowFacade")
            val controller = context.getBean("customerWorkflowController")
            assertSame(facade, context.getBean(WorkflowApiReadFacade::class.java))
            assertSame(controller, context.getBean(WorkflowV1Controller::class.java))
            assertEquals(1, context.getBeansOfType(WorkflowApiReadFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(WorkflowV1Controller::class.java).size)
            assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
        }
    }

    @Test
    fun `registers workflow auto configuration through spring factories`() {
        val factories = SpringFactoriesLoader.loadFactoryNames(
            EnableAutoConfiguration::class.java,
            FileWeftWebBoot2WorkflowAutoConfiguration::class.java.classLoader,
        )

        assertTrue(factories.contains(FileWeftWebBoot2WorkflowAutoConfiguration::class.java.name))
    }

    private fun assertStartupFailure(configuration: Class<*>, expectedMessage: String) {
        contextRunner().withUserConfiguration(configuration).run { context ->
            val failure = assertNotNull(context.startupFailure)
            assertTrue(
                generateSequence(failure) { throwable -> throwable.cause }
                    .any { throwable -> throwable is IllegalStateException && throwable.message == expectedMessage },
                "Expected startup failure '$expectedMessage' but was: ${failure.message}",
            )
        }
    }

    private fun contextRunner(): WebApplicationContextRunner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileWeftWebBoot2WorkflowAutoConfiguration::class.java))

    @Configuration(proxyBeanMethods = false)
    internal class SingleServiceConfiguration {
        @Bean
        fun workflowQueryService(): WorkflowQueryService = WorkflowV1ControllerFixture().service()
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleServicesConfiguration {
        @Bean
        @Primary
        fun primaryWorkflowQueryService(): WorkflowQueryService = WorkflowV1ControllerFixture().service()

        @Bean
        fun secondaryWorkflowQueryService(): WorkflowQueryService = WorkflowV1ControllerFixture().service()
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleFacadesConfiguration {
        @Bean
        fun workflowQueryService(): WorkflowQueryService = WorkflowV1ControllerFixture().service()

        @Bean
        @Primary
        fun primaryWorkflowFacade(service: WorkflowQueryService): WorkflowApiReadFacade = WorkflowApiReadFacade(service)

        @Bean
        fun secondaryWorkflowFacade(service: WorkflowQueryService): WorkflowApiReadFacade = WorkflowApiReadFacade(service)
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleTracesConfiguration {
        @Bean
        fun workflowQueryService(): WorkflowQueryService = WorkflowV1ControllerFixture().service()

        @Bean
        @Primary
        fun primaryTraceContextProvider(): TraceContextProvider = trace("trace-primary")

        @Bean
        fun secondaryTraceContextProvider(): TraceContextProvider = trace("trace-secondary")
    }

    @Configuration(proxyBeanMethods = false)
    internal class CustomerWebConfiguration {
        @Bean
        fun workflowQueryService(): WorkflowQueryService = WorkflowV1ControllerFixture().service()

        @Bean
        fun customerWorkflowFacade(service: WorkflowQueryService): WorkflowApiReadFacade = WorkflowApiReadFacade(service)

        @Bean
        fun customerWorkflowController(facade: WorkflowApiReadFacade): WorkflowV1Controller =
            WorkflowV1Controller(facade, V1ApiResponseFactory(), null)
    }

    companion object {
        private fun trace(traceId: String): TraceContextProvider = object : TraceContextProvider {
            override fun currentTraceContext(): TraceContext = TraceContext(Identifier(traceId))
        }
    }
}
