package ai.icen.fw.agent.web.spring.boot2

import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.agent.web.api.AgentWebTrustedContextProvider
import ai.icen.fw.core.id.Identifier
import com.fasterxml.jackson.databind.ObjectMapper
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
import kotlin.test.assertTrue

class FlowWeftAgentWebBoot2AutoConfigurationTest {
    @Test
    fun `does not expose routes without trusted identity or when disabled`() {
        runner().withUserConfiguration(MapperOnlyConfiguration::class.java).run { context ->
            assertTrue(context.getBeansOfType(FlowWeftAgentWebBoot2Controller::class.java).isEmpty())
        }
        runner()
            .withUserConfiguration(SecureHostConfiguration::class.java)
            .withPropertyValues("flowweft.agent.web.enabled=false")
            .run { context ->
                assertTrue(context.getBeansOfType(FlowWeftAgentWebBoot2Controller::class.java).isEmpty())
            }
    }

    @Test
    fun `registers all adapter beans for one trusted provider`() {
        runner().withUserConfiguration(SecureHostConfiguration::class.java).run { context ->
            assertEquals(1, context.getBeansOfType(FlowWeftAgentWebBoot2ApplicationPorts::class.java).size)
            assertEquals(1, context.getBeansOfType(FlowWeftAgentWebBoot2JsonCodec::class.java).size)
            assertEquals(1, context.getBeansOfType(FlowWeftAgentWebBoot2Controller::class.java).size)
        }
    }

    @Test
    fun `fails startup for ambiguous trusted providers`() {
        runner().withUserConfiguration(AmbiguousIdentityConfiguration::class.java).run { context ->
            val failure = assertNotNull(context.startupFailure)
            assertTrue(generateSequence(failure) { it.cause }.any {
                it is IllegalStateException &&
                    it.message == "FlowWeft Agent Web requires exactly one trusted context provider."
            })
        }
    }

    @Test
    fun `registers through Boot 2 spring factories`() {
        val factories = SpringFactoriesLoader.loadFactoryNames(
            EnableAutoConfiguration::class.java,
            javaClass.classLoader,
        )
        assertTrue(factories.contains(FlowWeftAgentWebBoot2AutoConfiguration::class.java.name))
    }

    private fun runner(): WebApplicationContextRunner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FlowWeftAgentWebBoot2AutoConfiguration::class.java))

    @Configuration(proxyBeanMethods = false)
    internal class MapperOnlyConfiguration {
        @Bean fun objectMapper(): ObjectMapper = ObjectMapper()
    }

    @Configuration(proxyBeanMethods = false)
    internal class SecureHostConfiguration {
        @Bean fun objectMapper(): ObjectMapper = ObjectMapper()
        @Bean fun trustedContextProvider(): AgentWebTrustedContextProvider = trusted("authentication-1")
    }

    @Configuration(proxyBeanMethods = false)
    internal class AmbiguousIdentityConfiguration {
        @Bean fun objectMapper(): ObjectMapper = ObjectMapper()
        @Bean @Primary fun firstProvider(): AgentWebTrustedContextProvider = trusted("authentication-1")
        @Bean fun secondProvider(): AgentWebTrustedContextProvider = trusted("authentication-2")
    }

    companion object {
        private fun trusted(authenticationId: String): AgentWebTrustedContextProvider = AgentWebTrustedContextProvider {
            val now = System.currentTimeMillis()
            AgentWebTrustedContext.authenticated(
                AgentRunContext(
                    Identifier("tenant-1"),
                    Identifier("user-1"),
                    "USER",
                    Identifier("request-1"),
                    now,
                ),
                Identifier(authenticationId),
                "revision-1",
                now + 60_000L,
                "0".repeat(64),
            )
        }
    }
}
