package com.fileweft.web.spring.boot3

import com.fileweft.application.document.DocumentDownloadService
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentApiDownloadFacade
import com.fileweft.web.spring.boot3.v1.document.V1DocumentContentController
import com.fileweft.web.spring.boot3.v1.document.V1DocumentContentFailureHandler
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.charset.StandardCharsets
import java.util.function.Supplier
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileWeftWebBoot3ContentAutoConfigurationTest {
    private val contextRunner = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                FileWeftWebBoot3ContentAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `registers the independent content auto configuration after the core starter`() {
        val resource = requireNotNull(
            javaClass.classLoader.getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            ),
        )
        val registrations = resource.use { input -> String(input.readBytes(), StandardCharsets.UTF_8) }
        val annotation = FileWeftWebBoot3ContentAutoConfiguration::class.java.getAnnotation(AutoConfiguration::class.java)

        assertTrue(registrations.lineSequence().any { line ->
            line.trim() == FileWeftWebBoot3ContentAutoConfiguration::class.java.name
        })
        assertTrue(annotation.afterName.contains("com.fileweft.starter.boot3.FileWeftAutoConfiguration"))
    }

    @Test
    fun `does not expose content components without DocumentDownloadService`() {
        contextRunner.run { context ->
            assertNull(context.getBeanProvider(DocumentApiDownloadFacade::class.java).getIfAvailable())
            assertNull(context.getBeanProvider(V1DocumentContentController::class.java).getIfAvailable())
            assertNull(context.getBeanProvider(V1DocumentContentFailureHandler::class.java).getIfAvailable())
            assertNull(context.getBeanProvider(V1ApiResponseFactory::class.java).getIfAvailable())
        }
    }

    @Test
    fun `registers one facade controller advice and response factory for one download service`() {
        val service = V1DocumentContentTestFixture().downloadService

        contextRunner
            .withBean(DocumentDownloadService::class.java, Supplier { service })
            .run { context ->
                assertNotNull(context.getBeanProvider(DocumentApiDownloadFacade::class.java).getIfAvailable())
                assertNotNull(context.getBeanProvider(V1DocumentContentController::class.java).getIfAvailable())
                assertNotNull(context.getBeanProvider(V1DocumentContentFailureHandler::class.java).getIfAvailable())
                assertNotNull(context.getBeanProvider(V1ApiResponseFactory::class.java).getIfAvailable())
            }
    }

    @Test
    fun `keeps unique host facade advice and response factory replacements`() {
        val fixture = V1DocumentContentTestFixture()
        val facade = fixture.facade
        val advice = V1DocumentContentFailureHandler()
        val responses = V1ApiResponseFactory()

        contextRunner
            .withBean(DocumentDownloadService::class.java, Supplier { fixture.downloadService })
            .withBean(DocumentApiDownloadFacade::class.java, Supplier { facade })
            .withBean(V1DocumentContentFailureHandler::class.java, Supplier { advice })
            .withBean(V1ApiResponseFactory::class.java, Supplier { responses })
            .run { context ->
                assertSame(facade, context.getBean(DocumentApiDownloadFacade::class.java))
                assertSame(advice, context.getBean(V1DocumentContentFailureHandler::class.java))
                assertSame(responses, context.getBean(V1ApiResponseFactory::class.java))
                assertNotNull(context.getBeanProvider(V1DocumentContentController::class.java).getIfAvailable())
            }
    }

    @Test
    fun `fails startup when download services are ambiguous`() {
        contextRunner
            .withUserConfiguration(MultipleDownloadServicesConfiguration::class.java)
            .run { context ->
                assertTrue(context.startupFailure != null)
            }
    }

    @Test
    fun `fails startup when download facades are ambiguous`() {
        contextRunner
            .withUserConfiguration(MultipleDownloadFacadesConfiguration::class.java)
            .run { context ->
                assertTrue(context.startupFailure != null)
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleDownloadServicesConfiguration {
        @Bean
        fun firstDocumentDownloadService(): DocumentDownloadService =
            V1DocumentContentTestFixture().downloadService

        @Bean
        fun secondDocumentDownloadService(): DocumentDownloadService =
            V1DocumentContentTestFixture().downloadService
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleDownloadFacadesConfiguration {
        @Bean
        fun documentDownloadService(): DocumentDownloadService =
            V1DocumentContentTestFixture().downloadService

        @Bean
        fun firstDocumentApiDownloadFacade(service: DocumentDownloadService): DocumentApiDownloadFacade =
            DocumentApiDownloadFacade(service)

        @Bean
        fun secondDocumentApiDownloadFacade(service: DocumentDownloadService): DocumentApiDownloadFacade =
            DocumentApiDownloadFacade(service)
    }
}
