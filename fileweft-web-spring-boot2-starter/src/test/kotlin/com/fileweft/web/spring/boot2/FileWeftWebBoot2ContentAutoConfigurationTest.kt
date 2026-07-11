package com.fileweft.web.spring.boot2

import com.fileweft.application.document.DocumentDownloadService
import com.fileweft.application.document.DocumentQueryService
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentApiDownloadFacade
import com.fileweft.web.runtime.v1.document.DocumentApiReadFacade
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.SpringFactoriesLoader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileWeftWebBoot2ContentAutoConfigurationTest {
    @Test
    fun `does not expose content MVC components without a trusted download service`() {
        contextRunner().run { context ->
            assertTrue(context.getBeansOfType(DocumentApiDownloadFacade::class.java).isEmpty())
            assertTrue(context.getBeansOfType(V1ApiResponseFactory::class.java).isEmpty())
            assertTrue(context.getBeansOfType(DocumentV1ContentController::class.java).isEmpty())
            assertTrue(context.getBeansOfType(DocumentV1ContentControllerAdvice::class.java).isEmpty())
        }
    }

    @Test
    fun `assembles independent content facade controller and failure advice from a download service`() {
        contextRunner()
            .withUserConfiguration(DownloadServiceConfiguration::class.java)
            .run { context ->
                assertEquals(1, context.getBeansOfType(DocumentApiDownloadFacade::class.java).size)
                assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
                assertEquals(1, context.getBeansOfType(DocumentV1ContentController::class.java).size)
                assertEquals(1, context.getBeansOfType(DocumentV1ContentControllerAdvice::class.java).size)
            }
    }

    @Test
    fun `shares one response factory with the independent read MVC auto configuration`() {
        WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JacksonAutoConfiguration::class.java,
                    FileWeftWebBoot2AutoConfiguration::class.java,
                    FileWeftWebBoot2ContentAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(ReadAndDownloadServiceConfiguration::class.java)
            .run { context ->
                assertEquals(1, context.getBeansOfType(DocumentApiReadFacade::class.java).size)
                assertEquals(1, context.getBeansOfType(DocumentApiDownloadFacade::class.java).size)
                assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
                assertEquals(1, context.getBeansOfType(DocumentV1Controller::class.java).size)
                assertEquals(1, context.getBeansOfType(DocumentV1ContentController::class.java).size)
            }
    }

    @Test
    fun `fails closed when the host provides multiple download services`() {
        contextRunner()
            .withUserConfiguration(MultipleDownloadServicesConfiguration::class.java)
            .run { context ->
                assertTrue(requireNotNull(context.startupFailure).hasCause(NoUniqueBeanDefinitionException::class.java))
            }
    }

    @Test
    fun `fails closed when the host provides multiple download facades`() {
        contextRunner()
            .withUserConfiguration(MultipleDownloadFacadesConfiguration::class.java)
            .run { context ->
                assertTrue(requireNotNull(context.startupFailure).hasCause(NoUniqueBeanDefinitionException::class.java))
            }
    }

    @Test
    fun `registers the independent content auto configuration through spring factories`() {
        val factories = SpringFactoriesLoader.loadFactoryNames(
            EnableAutoConfiguration::class.java,
            FileWeftWebBoot2ContentAutoConfiguration::class.java.classLoader,
        )

        assertTrue(factories.contains(FileWeftWebBoot2AutoConfiguration::class.java.name))
        assertTrue(factories.contains(FileWeftWebBoot2WriteAutoConfiguration::class.java.name))
        assertTrue(factories.contains(FileWeftWebBoot2ContentAutoConfiguration::class.java.name))
    }

    private fun contextRunner(): WebApplicationContextRunner = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                FileWeftWebBoot2ContentAutoConfiguration::class.java,
            ),
        )

    private fun Throwable.hasCause(type: Class<out Throwable>): Boolean =
        generateSequence(this) { throwable -> throwable.cause }.any { throwable -> type.isInstance(throwable) }

    @Configuration(proxyBeanMethods = false)
    class DownloadServiceConfiguration {
        @Bean
        fun documentDownloadService(): DocumentDownloadService = DocumentV1ContentControllerTestFixture().service()
    }

    @Configuration(proxyBeanMethods = false)
    class ReadAndDownloadServiceConfiguration {
        @Bean
        fun documentDownloadService(): DocumentDownloadService = DocumentV1ContentControllerTestFixture().service()

        @Bean
        fun documentQueryService(): DocumentQueryService = DocumentV1ControllerTestFixture().service()
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleDownloadServicesConfiguration {
        @Bean
        fun firstDocumentDownloadService(): DocumentDownloadService = DocumentV1ContentControllerTestFixture().service()

        @Bean
        fun secondDocumentDownloadService(): DocumentDownloadService = DocumentV1ContentControllerTestFixture().service()
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleDownloadFacadesConfiguration {
        @Bean
        fun documentDownloadService(): DocumentDownloadService = DocumentV1ContentControllerTestFixture().service()

        @Bean
        fun firstDocumentApiDownloadFacade(downloads: DocumentDownloadService): DocumentApiDownloadFacade =
            DocumentApiDownloadFacade(downloads)

        @Bean
        fun secondDocumentApiDownloadFacade(downloads: DocumentDownloadService): DocumentApiDownloadFacade =
            DocumentApiDownloadFacade(downloads)
    }
}
