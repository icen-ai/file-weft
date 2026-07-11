package com.fileweft.web.spring.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.document.DocumentQueryService
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentApiReadFacade
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.SpringFactoriesLoader
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileWeftWebBoot2AutoConfigurationTest {
    @Test
    fun `does not expose v1 MVC components without the secure document query service`() {
        contextRunner().run { context ->
            assertTrue(context.getBeansOfType(DocumentApiReadFacade::class.java).isEmpty())
            assertTrue(context.getBeansOfType(V1ApiResponseFactory::class.java).isEmpty())
            assertTrue(context.getBeansOfType(DocumentV1Controller::class.java).isEmpty())
        }
    }

    @Test
    fun `assembles v1 MVC components only after a host supplies the document query service`() {
        contextRunner()
            .withUserConfiguration(DocumentQueryServiceConfiguration::class.java)
            .run { context ->
                assertEquals(1, context.getBeansOfType(DocumentApiReadFacade::class.java).size)
                assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
                assertEquals(1, context.getBeansOfType(DocumentV1Controller::class.java).size)
                MockMvcBuilders.standaloneSetup(context.getBean(DocumentV1Controller::class.java))
                    .setMessageConverters(
                        MappingJackson2HttpMessageConverter(context.getBean(ObjectMapper::class.java)),
                    )
                    .build()
                    .perform(get("/fileweft/v1/documents/document-1"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.traceId").value("auto-config-trace"))
                    .andExpect(jsonPath("$.success").doesNotExist())
                    .andExpect(jsonPath("$.failure").doesNotExist())
            }
    }

    @Test
    fun `registers the boot 2 auto configuration through spring factories`() {
        val factories = SpringFactoriesLoader.loadFactoryNames(
            EnableAutoConfiguration::class.java,
            FileWeftWebBoot2AutoConfiguration::class.java.classLoader,
        )

        assertTrue(factories.contains(FileWeftWebBoot2AutoConfiguration::class.java.name))
    }

    private fun contextRunner(): WebApplicationContextRunner = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                FileWeftWebBoot2AutoConfiguration::class.java,
            ),
        )

    @Configuration(proxyBeanMethods = false)
    class DocumentQueryServiceConfiguration {
        @Bean
        fun documentQueryService(): DocumentQueryService = DocumentV1ControllerTestFixture().service()

        @Bean
        fun traceContextProvider(): TraceContextProvider = DocumentV1ControllerTestFixture.traceProvider("auto-config-trace")
    }
}
