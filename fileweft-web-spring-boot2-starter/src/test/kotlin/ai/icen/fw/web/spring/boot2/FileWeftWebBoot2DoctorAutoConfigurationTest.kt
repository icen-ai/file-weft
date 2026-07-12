package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.doctor.DocumentDoctorQueryService
import ai.icen.fw.application.doctor.DocumentDoctorTaskReceipt
import ai.icen.fw.application.doctor.IdempotentScheduleDocumentCatalogDoctorService
import ai.icen.fw.application.doctor.IdempotentScheduleDocumentDoctorService
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.doctor.DoctorApiFacade
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.io.support.SpringFactoriesLoader
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileWeftWebBoot2DoctorAutoConfigurationTest {
    private val contextRunner = WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(FileWeftWebBoot2DoctorAutoConfiguration::class.java),
    )

    @Test
    fun `registers Doctor auto configuration through spring factories`() {
        val factories = SpringFactoriesLoader.loadFactoryNames(
            EnableAutoConfiguration::class.java,
            FileWeftWebBoot2DoctorAutoConfiguration::class.java.classLoader,
        )

        assertTrue(factories.contains(FileWeftWebBoot2DoctorAutoConfiguration::class.java.name))
    }

    @Test
    fun `always installs fail closed Doctor facade controllers and response factory`() {
        contextRunner.run { context ->
            assertEquals(1, context.getBeansOfType(DoctorApiFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(DocumentV1DoctorController::class.java).size)
            assertEquals(1, context.getBeansOfType(SystemV1DoctorController::class.java).size)
            assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
        }
    }

    @Test
    fun `backs off from a host Doctor facade while retaining both MVC edges`() {
        val host = DoctorApiFacade(0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        contextRunner.withBean(DoctorApiFacade::class.java, Supplier { host }).run { context ->
            assertTrue(context.getBean(DoctorApiFacade::class.java) === host)
            assertEquals(1, context.getBeansOfType(DoctorApiFacade::class.java).size)
            assertNotNull(context.getBeanProvider(DocumentV1DoctorController::class.java).getIfAvailable())
            assertNotNull(context.getBeanProvider(SystemV1DoctorController::class.java).getIfAvailable())
        }
    }

    @Test
    fun `backs off independently from host Doctor controllers`() {
        val hostDocument = Mockito.mock(DocumentV1DoctorController::class.java)
        val hostSystem = Mockito.mock(SystemV1DoctorController::class.java)

        contextRunner
            .withBean(DocumentV1DoctorController::class.java, Supplier { hostDocument })
            .withBean(SystemV1DoctorController::class.java, Supplier { hostSystem })
            .run { context ->
                assertTrue(context.getBean(DocumentV1DoctorController::class.java) === hostDocument)
                assertTrue(context.getBean(SystemV1DoctorController::class.java) === hostSystem)
                assertEquals(1, context.getBeansOfType(DocumentV1DoctorController::class.java).size)
                assertEquals(1, context.getBeansOfType(SystemV1DoctorController::class.java).size)
            }
    }

    @Test
    fun `enumerates every Doctor candidate even when one is primary`() {
        contextRunner.withUserConfiguration(MultipleDocumentDoctors::class.java).run { context ->
            val failure = assertNotNull(context.startupFailure)
            assertTrue(failure.causeChain().any { cause ->
                cause.message?.contains("multiple document Doctor candidates") == true
            })
        }
    }

    @Test
    fun `counts every catalog access candidate instead of selecting a primary bean`() {
        contextRunner.withUserConfiguration(MultipleCatalogAccesses::class.java).run { context ->
            val failure = assertNotNull(context.startupFailure)
            assertTrue(failure.causeChain().any { cause ->
                cause.message?.contains("at most one catalog access boundary") == true
            })
        }
    }

    @Test
    fun `selects only the scheduler matching the installed catalog boundary`() {
        val flat = Mockito.mock(IdempotentScheduleDocumentDoctorService::class.java)
        Mockito.`when`(flat.schedule(DOCUMENT_ID, "doctor-key-1"))
            .thenReturn(DocumentDoctorTaskReceipt(TASK_ID, DOCUMENT_ID))
        contextRunner.withBean(IdempotentScheduleDocumentDoctorService::class.java, Supplier { flat }).run { context ->
            assertEquals(TASK_ID.value, context.getBean(DoctorApiFacade::class.java)
                .scheduleDocument(DOCUMENT_ID.value, "doctor-key-1").taskId)
        }

        val catalogAccess = Mockito.mock(DocumentCatalogAccessService::class.java)
        val catalog = Mockito.mock(IdempotentScheduleDocumentCatalogDoctorService::class.java)
        Mockito.`when`(catalog.schedule(DOCUMENT_ID, "doctor-key-2"))
            .thenReturn(DocumentDoctorTaskReceipt(TASK_ID, DOCUMENT_ID))
        contextRunner
            .withBean(DocumentCatalogAccessService::class.java, Supplier { catalogAccess })
            .withBean(IdempotentScheduleDocumentCatalogDoctorService::class.java, Supplier { catalog })
            .run { context ->
                assertEquals(TASK_ID.value, context.getBean(DoctorApiFacade::class.java)
                    .scheduleDocument(DOCUMENT_ID.value, "doctor-key-2").taskId)
            }

        contextRunner
            .withBean(DocumentCatalogAccessService::class.java, Supplier { catalogAccess })
            .withBean(IdempotentScheduleDocumentDoctorService::class.java, Supplier { flat })
            .run { context ->
                assertThrows<IllegalStateException> {
                    context.getBean(DoctorApiFacade::class.java)
                        .scheduleDocument(DOCUMENT_ID.value, "doctor-key-1")
                }
            }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { failure -> failure.cause }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleDocumentDoctors {
        @Bean
        @Primary
        fun primaryDoctor(): DocumentDoctorQueryService = Mockito.mock(DocumentDoctorQueryService::class.java)

        @Bean
        fun secondaryDoctor(): DocumentDoctorQueryService = Mockito.mock(DocumentDoctorQueryService::class.java)
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleCatalogAccesses {
        @Bean
        @Primary
        fun primaryAccess(): DocumentCatalogAccessService = Mockito.mock(DocumentCatalogAccessService::class.java)

        @Bean
        fun secondaryAccess(): DocumentCatalogAccessService = Mockito.mock(DocumentCatalogAccessService::class.java)
    }

    private companion object {
        val DOCUMENT_ID = Identifier("document-1")
        val TASK_ID = Identifier("task-1")
    }
}
