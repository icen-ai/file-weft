package com.fileweft.web.spring.boot2

import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.document.DocumentQueryService
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentApiReadFacade
import com.fileweft.web.runtime.v1.document.DocumentApiWriteFacade
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.SpringFactoriesLoader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileWeftWebBoot2WriteAutoConfigurationTest {
    @Test
    fun `does not expose a write controller when the host only supplies the read service`() {
        contextRunner()
            .withUserConfiguration(ReadOnlyConfiguration::class.java)
            .run { context ->
                assertEquals(1, context.getBeansOfType(DocumentApiReadFacade::class.java).size)
                assertTrue(context.getBeansOfType(DocumentApiWriteFacade::class.java).isEmpty())
                assertTrue(context.getBeansOfType(DocumentV1WriteController::class.java).isEmpty())
            }
    }

    @Test
    fun `assembles write components only after a host supplies the draft service`() {
        contextRunner()
            .withUserConfiguration(DraftServiceConfiguration::class.java)
            .run { context ->
                assertEquals(1, context.getBeansOfType(DocumentApiWriteFacade::class.java).size)
                assertEquals(1, context.getBeansOfType(DocumentV1WriteController::class.java).size)
                assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
            }
    }

    @Test
    fun `registers both boot 2 web auto configurations through spring factories`() {
        val factories = SpringFactoriesLoader.loadFactoryNames(
            EnableAutoConfiguration::class.java,
            FileWeftWebBoot2WriteAutoConfiguration::class.java.classLoader,
        )

        assertTrue(factories.contains(FileWeftWebBoot2AutoConfiguration::class.java.name))
        assertTrue(factories.contains(FileWeftWebBoot2WriteAutoConfiguration::class.java.name))
    }

    private fun contextRunner(): WebApplicationContextRunner = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                FileWeftWebBoot2AutoConfiguration::class.java,
                FileWeftWebBoot2WriteAutoConfiguration::class.java,
            ),
        )

    @Configuration(proxyBeanMethods = false)
    class ReadOnlyConfiguration {
        @Bean
        fun documentQueryService(): DocumentQueryService = DocumentV1ControllerTestFixture().service()
    }

    @Configuration(proxyBeanMethods = false)
    class DraftServiceConfiguration {
        @Bean
        fun documentDraftService(): DocumentDraftService = DocumentV1WriteControllerTestFixture().drafts
    }
}
