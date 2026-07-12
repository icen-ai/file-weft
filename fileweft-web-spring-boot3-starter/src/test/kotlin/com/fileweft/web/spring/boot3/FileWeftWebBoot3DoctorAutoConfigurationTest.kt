package com.fileweft.web.spring.boot3

import com.fileweft.application.catalog.DocumentCatalogAccessService
import com.fileweft.application.doctor.DocumentDoctorQueryService
import com.fileweft.application.doctor.DocumentDoctorTaskQueryService
import com.fileweft.application.doctor.DocumentDoctorTaskReceipt
import com.fileweft.application.doctor.IdempotentScheduleDocumentCatalogDoctorService
import com.fileweft.application.doctor.IdempotentScheduleDocumentDoctorService
import com.fileweft.application.doctor.SystemDoctorService
import com.fileweft.core.id.Identifier
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.doctor.DoctorApiFacade
import com.fileweft.web.spring.boot3.v1.doctor.V1DocumentDoctorController
import com.fileweft.web.spring.boot3.v1.doctor.V1SystemDoctorController
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

class FileWeftWebBoot3DoctorAutoConfigurationTest {
    private val contextRunner = WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(
            JacksonAutoConfiguration::class.java,
            FileWeftWebBoot3DoctorAutoConfiguration::class.java,
        ),
    )

    @Test
    fun `registers Doctor auto configuration after the matching runtime starter`() {
        val resource = requireNotNull(
            javaClass.classLoader.getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            ),
        )
        val registrations = resource.use { input -> String(input.readBytes(), StandardCharsets.UTF_8) }
        val annotation = FileWeftWebBoot3DoctorAutoConfiguration::class.java
            .getAnnotation(AutoConfiguration::class.java)

        assertTrue(registrations.lineSequence().any { line ->
            line.trim() == FileWeftWebBoot3DoctorAutoConfiguration::class.java.name
        })
        assertEquals(
            listOf("com.fileweft.starter.boot3.FileWeftAutoConfiguration"),
            annotation.afterName.toList(),
        )
    }

    @Test
    fun `always installs fail closed Doctor facade controllers and response factory`() {
        contextRunner.run { context ->
            assertNotNull(context.getBeanProvider(DoctorApiFacade::class.java).getIfAvailable())
            assertNotNull(context.getBeanProvider(V1DocumentDoctorController::class.java).getIfAvailable())
            assertNotNull(context.getBeanProvider(V1SystemDoctorController::class.java).getIfAvailable())
            assertNotNull(context.getBeanProvider(V1ApiResponseFactory::class.java).getIfAvailable())
            assertEquals(1, context.getBeansOfType(DoctorApiFacade::class.java).size)
        }
    }

    @Test
    fun `backs off from a host Doctor facade while retaining both MVC edges`() {
        val host = DoctorApiFacade(0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        contextRunner.withBean(DoctorApiFacade::class.java, Supplier { host }).run { context ->
            assertTrue(context.getBean(DoctorApiFacade::class.java) === host)
            assertEquals(1, context.getBeansOfType(DoctorApiFacade::class.java).size)
            assertNotNull(context.getBeanProvider(V1DocumentDoctorController::class.java).getIfAvailable())
            assertNotNull(context.getBeanProvider(V1SystemDoctorController::class.java).getIfAvailable())
        }
    }

    @Test
    fun `backs off independently from host Doctor controllers`() {
        val hostDocument = Mockito.mock(V1DocumentDoctorController::class.java)
        val hostSystem = Mockito.mock(V1SystemDoctorController::class.java)

        contextRunner
            .withBean(V1DocumentDoctorController::class.java, Supplier { hostDocument })
            .withBean(V1SystemDoctorController::class.java, Supplier { hostSystem })
            .run { context ->
                assertTrue(context.getBean(V1DocumentDoctorController::class.java) === hostDocument)
                assertTrue(context.getBean(V1SystemDoctorController::class.java) === hostSystem)
                assertEquals(1, context.getBeansOfType(V1DocumentDoctorController::class.java).size)
                assertEquals(1, context.getBeansOfType(V1SystemDoctorController::class.java).size)
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

    @Test
    fun `rejects every ambiguous formal Doctor capability`() {
        val document = Mockito.mock(DocumentDoctorQueryService::class.java)
        val task = Mockito.mock(DocumentDoctorTaskQueryService::class.java)
        val flat = Mockito.mock(IdempotentScheduleDocumentDoctorService::class.java)
        val catalog = Mockito.mock(IdempotentScheduleDocumentCatalogDoctorService::class.java)
        val system = Mockito.mock(SystemDoctorService::class.java)

        assertThrows<IllegalArgumentException> {
            DoctorApiFacade(0, listOf(document, document), emptyList(), emptyList(), emptyList(), emptyList())
        }
        assertThrows<IllegalArgumentException> {
            DoctorApiFacade(0, emptyList(), listOf(task, task), emptyList(), emptyList(), emptyList())
        }
        assertThrows<IllegalArgumentException> {
            DoctorApiFacade(0, emptyList(), emptyList(), listOf(flat, flat), emptyList(), emptyList())
        }
        assertThrows<IllegalArgumentException> {
            DoctorApiFacade(1, emptyList(), emptyList(), emptyList(), listOf(catalog, catalog), emptyList())
        }
        assertThrows<IllegalArgumentException> {
            DoctorApiFacade(0, emptyList(), emptyList(), emptyList(), emptyList(), listOf(system, system))
        }
        assertThrows<IllegalArgumentException> {
            DoctorApiFacade(2, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
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
