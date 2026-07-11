package com.fileweft.web.spring.boot3

import com.fileweft.application.catalog.DocumentCatalogDraftService
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.document.DocumentQueryService
import com.fileweft.spi.catalog.DocumentCatalogBinding
import com.fileweft.web.api.v1.document.CreateDocumentDraftCommand
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentApiWriteFacade
import com.fileweft.web.spring.boot3.v1.document.V1DocumentWriteController
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileWeftWebBoot3WriteAutoConfigurationTest {
    private val contextRunner = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                FileWeftWebBoot3AutoConfiguration::class.java,
                FileWeftWebBoot3WriteAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `registers the write auto configuration after the matching core starter`() {
        val resource = requireNotNull(
            javaClass.classLoader.getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            ),
        )
        val registrations = resource.use { input -> String(input.readBytes(), StandardCharsets.UTF_8) }
        val annotation = FileWeftWebBoot3WriteAutoConfiguration::class.java.getAnnotation(AutoConfiguration::class.java)

        assertTrue(registrations.lineSequence().any { line ->
            line.trim() == FileWeftWebBoot3WriteAutoConfiguration::class.java.name
        })
        assertEquals(
            listOf("com.fileweft.starter.boot3.FileWeftAutoConfiguration"),
            annotation.afterName.toList(),
        )
    }

    @Test
    fun `does not expose write components without DocumentDraftService`() {
        contextRunner.run { context ->
            assertNull(context.getBeanProvider(DocumentApiWriteFacade::class.java).getIfAvailable())
            assertNull(context.getBeanProvider(V1DocumentWriteController::class.java).getIfAvailable())
            assertNull(context.getBeanProvider(V1ApiResponseFactory::class.java).getIfAvailable())
        }
    }

    @Test
    fun `registers write components when the host supplies DocumentDraftService`() {
        val drafts = V1DocumentWriteTestFixture().drafts

        contextRunner
            .withBean(DocumentDraftService::class.java, Supplier { drafts })
            .run { context ->
                assertNotNull(context.getBeanProvider(DocumentApiWriteFacade::class.java).getIfAvailable())
                assertNotNull(context.getBeanProvider(V1DocumentWriteController::class.java).getIfAvailable())
                assertNotNull(context.getBeanProvider(V1ApiResponseFactory::class.java).getIfAvailable())
            }
    }

    @Test
    fun `shares one response factory when read and write services are both available`() {
        val fixture = V1DocumentWriteTestFixture()
        val drafts = fixture.drafts
        val queries = fixture.queryService()

        contextRunner
            .withBean(DocumentDraftService::class.java, Supplier { drafts })
            .withBean(DocumentQueryService::class.java, Supplier { queries })
            .run { context ->
                assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
                assertNotNull(context.getBeanProvider(V1DocumentWriteController::class.java).getIfAvailable())
            }
    }

    @Test
    fun `passes an available catalog draft service into the write facade`() {
        val fixture = V1DocumentWriteTestFixture()
        val drafts = fixture.drafts
        val catalogDrafts = fixture.catalogDraftService()

        contextRunner
            .withBean(DocumentDraftService::class.java, Supplier { drafts })
            .withBean(DocumentCatalogDraftService::class.java, Supplier { catalogDrafts })
            .run { context ->
                ByteArrayInputStream(byteArrayOf(1, 2, 3)).use { content ->
                    context.getBean(DocumentApiWriteFacade::class.java).create(
                        CreateDocumentDraftCommand(
                            documentNumber = "DOC-1",
                            title = "Tax certificate",
                            fileName = "proof.pdf",
                            contentLength = 3,
                            contentType = "application/pdf",
                            folderId = "finance",
                        ),
                        content,
                    )
                }
            }

        assertEquals("finance", fixture.storage.uploads.single().metadata[DocumentCatalogBinding.METADATA_KEY])
    }
}
