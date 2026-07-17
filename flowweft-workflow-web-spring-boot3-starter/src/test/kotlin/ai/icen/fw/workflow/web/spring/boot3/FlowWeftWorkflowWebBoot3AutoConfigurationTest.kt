package ai.icen.fw.workflow.web.spring.boot3

import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContext
import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContextProvider
import ai.icen.fw.workflow.web.runtime.WorkflowWebControllerRuntime
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlowWeftWorkflowWebBoot3AutoConfigurationTest {
    @Test
    fun `does not expose routes without a trusted context provider`() {
        runner().withUserConfiguration(MapperOnlyConfiguration::class.java).run { context ->
            assertTrue(context.getBeansOfType(FlowWeftWorkflowWebBoot3Controller::class.java).isEmpty())
            assertTrue(context.getBeansOfType(WorkflowWebControllerRuntime::class.java).isEmpty())
        }
    }

    @Test
    fun `registers transport with one trusted provider and backs off host codec`() {
        runner().withUserConfiguration(SecureHostConfiguration::class.java).run { context ->
            assertEquals(1, context.getBeansOfType(WorkflowWebTrustedContextProvider::class.java).size)
            assertEquals(1, context.getBeansOfType(WorkflowWebControllerRuntime::class.java).size)
            assertEquals(1, context.getBeansOfType(FlowWeftWorkflowWebBoot3ApplicationPorts::class.java).size)
            assertEquals(1, context.getBeansOfType(FlowWeftWorkflowWebBoot3Controller::class.java).size)
            assertSame(
                context.getBean("customerWorkflowJsonCodec"),
                context.getBean(FlowWeftWorkflowWebBoot3JsonCodec::class.java),
            )
        }
    }

    @Test
    fun `fails startup for ambiguous trusted providers even when host marks one primary`() {
        runner().withUserConfiguration(AmbiguousIdentityConfiguration::class.java).run { context ->
            val failure = assertNotNull(context.startupFailure)
            assertTrue(generateSequence(failure) { it.cause }.any {
                it is IllegalStateException &&
                    it.message == "FlowWeft Workflow Web requires exactly one trusted context provider."
            })
        }
    }

    @Test
    fun `registers through Boot 3 auto configuration imports`() {
        val resource = assertNotNull(
            javaClass.classLoader.getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            ),
        )
        val registrations = resource.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        assertTrue(registrations.lineSequence().any {
            it.trim() == FlowWeftWorkflowWebBoot3AutoConfiguration::class.java.name
        })
    }

    private fun runner(): WebApplicationContextRunner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FlowWeftWorkflowWebBoot3AutoConfiguration::class.java))

    @Configuration(proxyBeanMethods = false)
    internal class MapperOnlyConfiguration {
        @Bean fun objectMapper(): ObjectMapper = ObjectMapper()
    }

    @Configuration(proxyBeanMethods = false)
    internal class SecureHostConfiguration {
        @Bean fun objectMapper(): ObjectMapper = ObjectMapper()
        @Bean fun trustedContextProvider(): WorkflowWebTrustedContextProvider = trusted("provider-1")
        @Bean fun customerWorkflowJsonCodec(mapper: ObjectMapper): FlowWeftWorkflowWebBoot3JsonCodec =
            FlowWeftWorkflowWebBoot3JsonCodec(mapper)
    }

    @Configuration(proxyBeanMethods = false)
    internal class AmbiguousIdentityConfiguration {
        @Bean fun objectMapper(): ObjectMapper = ObjectMapper()
        @Bean @Primary fun firstProvider(): WorkflowWebTrustedContextProvider = trusted("provider-1")
        @Bean fun secondProvider(): WorkflowWebTrustedContextProvider = trusted("provider-2")
    }

    companion object {
        private fun trusted(authenticationId: String): WorkflowWebTrustedContextProvider =
            WorkflowWebTrustedContextProvider {
                WorkflowWebTrustedContext.authenticated(
                    "tenant-1", "USER", "user-1", authenticationId, "0".repeat(64),
                )
            }
    }
}
