package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimService
import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaimService
import ai.icen.fw.application.upload.PresignedUploadService
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.upload.ResumableUploadApiFacade
import ai.icen.fw.web.runtime.v1.upload.PresignedUploadApiFacade
import ai.icen.fw.web.runtime.v1.document.CompletedUploadDocumentApiFacade
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.core.io.support.SpringFactoriesLoader
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileWeftWebBoot2ResumableUploadAutoConfigurationTest {
    private val contextRunner = WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(
            FileWeftWebBoot2AutoConfiguration::class.java,
            FileWeftWebBoot2ResumableUploadAutoConfiguration::class.java,
        ),
    )

    @Test
    fun `does not expose uploads without the application service`() {
        contextRunner.run(::assertUploadBeansAbsent)
    }

    @Test
    fun `assembles upload facade controller and response factory when both capabilities exist`() {
        capableContext().run { context ->
            assertEquals(1, context.getBeansOfType(ResumableUploadApiFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(V1ResumableUploadController::class.java).size)
            assertEquals(1, context.getBeansOfType(V1ResumableUploadRequestFailureHandler::class.java).size)
            assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
        }
    }

    @Test
    fun `a host facade alone can reuse the default transport`() {
        val hostFacade = Mockito.mock(ResumableUploadApiFacade::class.java)

        contextRunner
            .withBean(ResumableUploadApiFacade::class.java, Supplier { hostFacade })
            .run { context ->
                assertSame(hostFacade, context.getBean(ResumableUploadApiFacade::class.java))
                assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
                assertEquals(1, context.getBeansOfType(V1ResumableUploadController::class.java).size)
                assertEquals(1, context.getBeansOfType(V1ResumableUploadRequestFailureHandler::class.java).size)
            }
    }

    @Test
    fun `assembles completed upload document transport only when the claim service exists`() {
        contextRunner
            .withBean(
                CompletedResumableUploadAssetClaimService::class.java,
                Supplier { Mockito.mock(CompletedResumableUploadAssetClaimService::class.java) },
            )
            .run { context ->
                assertEquals(1, context.getBeansOfType(CompletedUploadDocumentApiFacade::class.java).size)
                assertEquals(1, context.getBeansOfType(CompletedUploadDocumentV1Controller::class.java).size)
                assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
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
                assertTrue(context.getBeansOfType(PresignedUploadApiFacade::class.java).isEmpty())
                assertTrue(context.getBeansOfType(V1PresignedUploadController::class.java).isEmpty())
                assertTrue(context.getBeansOfType(V1PresignedUploadRequestFailureHandler::class.java).isEmpty())
            }

        contextRunner
            .withBean(
                CompletedPresignedUploadAssetClaimService::class.java,
                Supplier { Mockito.mock(CompletedPresignedUploadAssetClaimService::class.java) },
            )
            .run { context ->
                assertTrue(context.getBeansOfType(PresignedUploadApiFacade::class.java).isEmpty())
                assertTrue(context.getBeansOfType(V1PresignedUploadController::class.java).isEmpty())
                assertTrue(context.getBeansOfType(V1PresignedUploadRequestFailureHandler::class.java).isEmpty())
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
                assertEquals(1, context.getBeansOfType(PresignedUploadApiFacade::class.java).size)
                assertEquals(1, context.getBeansOfType(V1PresignedUploadController::class.java).size)
                assertEquals(1, context.getBeansOfType(V1PresignedUploadRequestFailureHandler::class.java).size)
            }
    }

    @Test
    fun `backs off from host facade response factory and controller`() {
        val hostFacade = Mockito.mock(ResumableUploadApiFacade::class.java)
        val hostResponses = V1ApiResponseFactory()
        val hostController = Mockito.mock(V1ResumableUploadController::class.java)
        val hostFailureHandler = V1ResumableUploadRequestFailureHandler(hostResponses)

        capableContext()
            .withBean(ResumableUploadApiFacade::class.java, Supplier { hostFacade })
            .withBean(V1ApiResponseFactory::class.java, Supplier { hostResponses })
            .withBean(V1ResumableUploadController::class.java, Supplier { hostController })
            .withBean(V1ResumableUploadRequestFailureHandler::class.java, Supplier { hostFailureHandler })
            .run { context ->
                assertSame(hostFacade, context.getBean(ResumableUploadApiFacade::class.java))
                assertSame(hostResponses, context.getBean(V1ApiResponseFactory::class.java))
                assertSame(hostController, context.getBean(V1ResumableUploadController::class.java))
                assertSame(
                    hostFailureHandler,
                    context.getBean(V1ResumableUploadRequestFailureHandler::class.java),
                )
                assertEquals(1, context.getBeansOfType(ResumableUploadApiFacade::class.java).size)
                assertEquals(1, context.getBeansOfType(V1ResumableUploadController::class.java).size)
                assertEquals(1, context.getBeansOfType(V1ResumableUploadRequestFailureHandler::class.java).size)
            }
    }

    @Test
    fun `registers upload auto configuration through spring factories`() {
        val factories = SpringFactoriesLoader.loadFactoryNames(
            EnableAutoConfiguration::class.java,
            FileWeftWebBoot2ResumableUploadAutoConfiguration::class.java.classLoader,
        )

        assertTrue(factories.contains(FileWeftWebBoot2ResumableUploadAutoConfiguration::class.java.name))
    }

    private fun capableContext(): WebApplicationContextRunner = contextRunner
        .withBean(
            ResumableUploadService::class.java,
            Supplier { Mockito.mock(ResumableUploadService::class.java) },
        )

    private fun assertUploadBeansAbsent(context: org.springframework.context.ApplicationContext) {
        assertTrue(context.getBeansOfType(ResumableUploadApiFacade::class.java).isEmpty())
        assertTrue(context.getBeansOfType(V1ResumableUploadController::class.java).isEmpty())
        assertTrue(context.getBeansOfType(V1ResumableUploadRequestFailureHandler::class.java).isEmpty())
        assertTrue(context.getBeansOfType(CompletedUploadDocumentApiFacade::class.java).isEmpty())
        assertTrue(context.getBeansOfType(CompletedUploadDocumentV1Controller::class.java).isEmpty())
        assertTrue(context.getBeansOfType(PresignedUploadApiFacade::class.java).isEmpty())
        assertTrue(context.getBeansOfType(V1PresignedUploadController::class.java).isEmpty())
        assertTrue(context.getBeansOfType(V1PresignedUploadRequestFailureHandler::class.java).isEmpty())
    }
}
