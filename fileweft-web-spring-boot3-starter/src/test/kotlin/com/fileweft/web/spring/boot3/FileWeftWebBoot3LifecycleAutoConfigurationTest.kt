package com.fileweft.web.spring.boot3

import com.fileweft.application.catalog.DocumentCatalogAccessService
import com.fileweft.application.lifecycle.IdempotentDocumentLifecycleService
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentLifecycleApiFacade
import com.fileweft.web.spring.boot3.v1.document.V1DocumentLifecycleController
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.nio.charset.StandardCharsets
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileWeftWebBoot3LifecycleAutoConfigurationTest {
    private val contextRunner = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                FileWeftWebBoot3AutoConfiguration::class.java,
                FileWeftWebBoot3LifecycleAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `registers lifecycle auto configuration after the matching core starter`() {
        val resource = requireNotNull(
            javaClass.classLoader.getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            ),
        )
        val registrations = resource.use { input -> String(input.readBytes(), StandardCharsets.UTF_8) }
        val annotation = FileWeftWebBoot3LifecycleAutoConfiguration::class.java
            .getAnnotation(AutoConfiguration::class.java)

        assertTrue(registrations.lineSequence().any { line ->
            line.trim() == FileWeftWebBoot3LifecycleAutoConfiguration::class.java.name
        })
        assertEquals(
            listOf("com.fileweft.starter.boot3.FileWeftAutoConfiguration"),
            annotation.afterName.toList(),
        )
    }

    @Test
    fun `always registers facade controller and response factory without lifecycle capabilities`() {
        contextRunner.run { context ->
            assertNotNull(context.getBeanProvider(DocumentLifecycleApiFacade::class.java).getIfAvailable())
            assertNotNull(context.getBeanProvider(V1DocumentLifecycleController::class.java).getIfAvailable())
            assertNotNull(context.getBeanProvider(V1ApiResponseFactory::class.java).getIfAvailable())
            assertEquals(1, context.getBeansOfType(DocumentLifecycleApiFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(V1DocumentLifecycleController::class.java).size)
        }
    }

    @Test
    fun `enumerates every flat lifecycle candidate even when one is primary`() {
        contextRunner
            .withUserConfiguration(MultipleFlatLifecycleServicesConfiguration::class.java)
            .run { context ->
                val failure = assertNotNull(context.startupFailure)
                assertTrue(failure.causeChain().any { cause ->
                    cause.message?.contains("multiple flat lifecycle candidates") == true
                })
            }
    }

    @Test
    fun `counts every catalog access candidate instead of selecting a primary bean`() {
        contextRunner
            .withUserConfiguration(MultipleCatalogAccessServicesConfiguration::class.java)
            .run { context ->
                val failure = assertNotNull(context.startupFailure)
                assertTrue(failure.causeChain().any { cause ->
                    cause.message?.contains("at most one catalog access boundary") == true
                })
            }
    }

    @Test
    fun `backs off from host lifecycle facade and still installs its mvc edge`() {
        val hostFacade = DocumentLifecycleApiFacade(
            catalogAccessCount = 0,
            flatLifecycles = emptyList(),
            catalogLifecycles = emptyList(),
            flatReviews = emptyList(),
            catalogReviews = emptyList(),
        )

        contextRunner
            .withBean(DocumentLifecycleApiFacade::class.java, Supplier { hostFacade })
            .run { context ->
                assertTrue(context.getBean(DocumentLifecycleApiFacade::class.java) === hostFacade)
                assertEquals(1, context.getBeansOfType(DocumentLifecycleApiFacade::class.java).size)
                assertNotNull(context.getBeanProvider(V1DocumentLifecycleController::class.java).getIfAvailable())
            }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { failure -> failure.cause }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleFlatLifecycleServicesConfiguration {
        @Bean
        @Primary
        fun primaryFlatLifecycle(): IdempotentDocumentLifecycleService =
            Mockito.mock(IdempotentDocumentLifecycleService::class.java)

        @Bean
        fun secondaryFlatLifecycle(): IdempotentDocumentLifecycleService =
            Mockito.mock(IdempotentDocumentLifecycleService::class.java)
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleCatalogAccessServicesConfiguration {
        @Bean
        @Primary
        fun primaryCatalogAccess(): DocumentCatalogAccessService =
            Mockito.mock(DocumentCatalogAccessService::class.java)

        @Bean
        fun secondaryCatalogAccess(): DocumentCatalogAccessService =
            Mockito.mock(DocumentCatalogAccessService::class.java)
    }
}
