package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimService
import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaimService
import ai.icen.fw.application.upload.PresignedUploadService
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.upload.ResumableUploadApiFacade
import ai.icen.fw.web.runtime.v1.upload.PresignedUploadApiFacade
import ai.icen.fw.web.runtime.v1.document.CompletedUploadDocumentApiFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1CompletedUploadDocumentController
import ai.icen.fw.web.spring.boot3.v1.upload.V1ResumableUploadController
import ai.icen.fw.web.spring.boot3.v1.upload.V1ResumableUploadRequestFailureHandler
import ai.icen.fw.web.spring.boot3.v1.upload.V1PresignedUploadController
import ai.icen.fw.web.spring.boot3.v1.upload.V1PresignedUploadRequestFailureHandler
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import java.nio.charset.StandardCharsets
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileWeftWebBoot3ResumableUploadAutoConfigurationTest {
    private val contextRunner = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                FileWeftWebBoot3ResumableUploadAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `registers the independent upload auto configuration after the core starter`() {
        val resource = requireNotNull(
            javaClass.classLoader.getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            ),
        )
        val registrations = resource.use { input -> String(input.readBytes(), StandardCharsets.UTF_8) }
        val annotation = FileWeftWebBoot3ResumableUploadAutoConfiguration::class.java
            .getAnnotation(AutoConfiguration::class.java)

        assertTrue(registrations.lineSequence().any { line ->
            line.trim() == FileWeftWebBoot3ResumableUploadAutoConfiguration::class.java.name
        })
        assertEquals(
            listOf("ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"),
            annotation.afterName.toList(),
        )
    }

    @Test
    fun `does not expose upload components without the application service`() {
        contextRunner.run(::assertUploadComponentsAbsent)
    }

    @Test
    fun `registers facade response factory and controller when required host beans are available`() {
        contextRunner
            .withBean(ResumableUploadService::class.java, Supplier { uploadService() })
            .run { context ->
                assertNotNull(context.getBeanProvider(ResumableUploadApiFacade::class.java).getIfAvailable())
                assertNotNull(context.getBeanProvider(V1ApiResponseFactory::class.java).getIfAvailable())
                assertNotNull(context.getBeanProvider(V1ResumableUploadController::class.java).getIfAvailable())
                assertNotNull(
                    context.getBeanProvider(V1ResumableUploadRequestFailureHandler::class.java).getIfAvailable(),
                )
            }
    }

    @Test
    fun `a host facade alone can reuse the default transport`() {
        val hostFacade = ResumableUploadApiFacade(uploadService())

        contextRunner
            .withBean(ResumableUploadApiFacade::class.java, Supplier { hostFacade })
            .run { context ->
                assertSame(hostFacade, context.getBean(ResumableUploadApiFacade::class.java))
                assertNotNull(context.getBeanProvider(V1ApiResponseFactory::class.java).getIfAvailable())
                assertNotNull(context.getBeanProvider(V1ResumableUploadController::class.java).getIfAvailable())
                assertNotNull(
                    context.getBeanProvider(V1ResumableUploadRequestFailureHandler::class.java).getIfAvailable(),
                )
            }
    }

    @Test
    fun `registers completed upload document transport only with the claim capability`() {
        contextRunner
            .withBean(
                CompletedResumableUploadAssetClaimService::class.java,
                Supplier { Mockito.mock(CompletedResumableUploadAssetClaimService::class.java) },
            )
            .run { context ->
                assertNotNull(context.getBeanProvider(CompletedUploadDocumentApiFacade::class.java).getIfAvailable())
                assertNotNull(context.getBeanProvider(V1CompletedUploadDocumentController::class.java).getIfAvailable())
                assertNotNull(context.getBeanProvider(V1ApiResponseFactory::class.java).getIfAvailable())
            }
    }

    @Test
    fun `presigned transport requires both provider completion and atomic asset claim capabilities`() {
        contextRunner
            .withBean(
                PresignedUploadService::class.java,
                Supplier { Mockito.mock(PresignedUploadService::class.java) },
            )
            .run { context ->
                assertNull(context.getBeanProvider(PresignedUploadApiFacade::class.java).getIfAvailable())
                assertNull(context.getBeanProvider(V1PresignedUploadController::class.java).getIfAvailable())
                assertNull(
                    context.getBeanProvider(V1PresignedUploadRequestFailureHandler::class.java).getIfAvailable(),
                )
            }

        contextRunner
            .withBean(
                CompletedPresignedUploadAssetClaimService::class.java,
                Supplier { Mockito.mock(CompletedPresignedUploadAssetClaimService::class.java) },
            )
            .run { context ->
                assertNull(context.getBeanProvider(PresignedUploadApiFacade::class.java).getIfAvailable())
                assertNull(context.getBeanProvider(V1PresignedUploadController::class.java).getIfAvailable())
                assertNull(
                    context.getBeanProvider(V1PresignedUploadRequestFailureHandler::class.java).getIfAvailable(),
                )
            }

        contextRunner
            .withBean(
                PresignedUploadService::class.java,
                Supplier { Mockito.mock(PresignedUploadService::class.java) },
            )
            .withBean(
                CompletedPresignedUploadAssetClaimService::class.java,
                Supplier { Mockito.mock(CompletedPresignedUploadAssetClaimService::class.java) },
            )
            .run { context ->
                assertNotNull(context.getBeanProvider(PresignedUploadApiFacade::class.java).getIfAvailable())
                assertNotNull(context.getBeanProvider(V1PresignedUploadController::class.java).getIfAvailable())
                assertNotNull(
                    context.getBeanProvider(V1PresignedUploadRequestFailureHandler::class.java).getIfAvailable(),
                )
            }
    }

    @Test
    fun `keeps host facade response factory and controller overrides authoritative`() {
        val hostFacade = ResumableUploadApiFacade(uploadService())
        val hostResponses = V1ApiResponseFactory()
        val hostController = V1ResumableUploadController(hostFacade, hostResponses, null)
        val hostFailureHandler = V1ResumableUploadRequestFailureHandler(hostResponses, null)

        contextRunner
            .withBean(ResumableUploadService::class.java, Supplier { uploadService() })
            .withBean(ResumableUploadApiFacade::class.java, Supplier { hostFacade })
            .withBean(V1ApiResponseFactory::class.java, Supplier { hostResponses })
            .withBean(V1ResumableUploadController::class.java, Supplier { hostController })
            .withBean(V1ResumableUploadRequestFailureHandler::class.java, Supplier { hostFailureHandler })
            .run { context ->
                assertEquals(1, context.getBeansOfType(ResumableUploadApiFacade::class.java).size)
                assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
                assertEquals(1, context.getBeansOfType(V1ResumableUploadController::class.java).size)
                assertEquals(1, context.getBeansOfType(V1ResumableUploadRequestFailureHandler::class.java).size)
                assertSame(hostFacade, context.getBean(ResumableUploadApiFacade::class.java))
                assertSame(hostResponses, context.getBean(V1ApiResponseFactory::class.java))
                assertSame(hostController, context.getBean(V1ResumableUploadController::class.java))
                assertSame(
                    hostFailureHandler,
                    context.getBean(V1ResumableUploadRequestFailureHandler::class.java),
                )
            }
    }

    private fun assertUploadComponentsAbsent(context: org.springframework.context.ApplicationContext) {
        assertNull(context.getBeanProvider(ResumableUploadApiFacade::class.java).getIfAvailable())
        assertNotNull(context.getBeanProvider(V1ApiResponseFactory::class.java).getIfAvailable())
        assertNull(context.getBeanProvider(V1ResumableUploadController::class.java).getIfAvailable())
        assertNull(context.getBeanProvider(V1ResumableUploadRequestFailureHandler::class.java).getIfAvailable())
        assertNull(context.getBeanProvider(CompletedUploadDocumentApiFacade::class.java).getIfAvailable())
        assertNull(context.getBeanProvider(V1CompletedUploadDocumentController::class.java).getIfAvailable())
        assertNull(context.getBeanProvider(PresignedUploadApiFacade::class.java).getIfAvailable())
        assertNull(context.getBeanProvider(V1PresignedUploadController::class.java).getIfAvailable())
        assertNull(context.getBeanProvider(V1PresignedUploadRequestFailureHandler::class.java).getIfAvailable())
    }

    private fun uploadService(): ResumableUploadService = Mockito.mock(ResumableUploadService::class.java)

}
