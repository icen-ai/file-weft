package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.catalog.DocumentCatalogDraftService
import ai.icen.fw.application.catalog.DocumentCatalogMutationService
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.catalog.DocumentCatalogBinding
import ai.icen.fw.web.api.v1.document.AddDocumentVersionCommand
import ai.icen.fw.web.api.v1.document.CreateDocumentDraftCommand
import ai.icen.fw.web.api.v1.document.RenameDocumentCommand
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentApiWriteFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentWriteController
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            listOf("ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"),
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
    fun `keeps catalog mutations fail closed when only the draft capability is available`() {
        val fixture = V1DocumentWriteTestFixture()
        val drafts = fixture.drafts
        val catalogDrafts = fixture.catalogDraftService()

        contextRunner
            .withBean(DocumentDraftService::class.java, Supplier { drafts })
            .withBean(DocumentCatalogDraftService::class.java, Supplier { catalogDrafts })
            .run { context ->
                val facade = context.getBean(DocumentApiWriteFacade::class.java)
                ByteArrayInputStream(byteArrayOf(1, 2, 3)).use { content ->
                    facade.create(
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
                assertFailsWith<IllegalStateException> {
                    ByteArrayInputStream(byteArrayOf(4, 5, 6)).use { content ->
                        facade.addVersion(
                            "document-1",
                            AddDocumentVersionCommand("2.0", "revision.pdf", 3, "application/pdf"),
                            content,
                        )
                    }
                }
                assertFailsWith<IllegalStateException> {
                    facade.rename("document-1", RenameDocumentCommand("Renamed"))
                }
            }

        assertEquals("finance", fixture.storage.uploads.single().metadata[DocumentCatalogBinding.METADATA_KEY])
        assertEquals("Tax certificate", fixture.documents.values.getValue(Identifier("document-1")).title)
    }

    @Test
    fun `passes complete catalog draft and mutation capabilities into the write facade`() {
        val fixture = V1DocumentWriteTestFixture(
            listOf("document-1", "file-1", "asset-1", "version-1", "file-2", "version-2"),
        )
        val drafts = fixture.drafts
        val catalogDrafts = fixture.catalogDraftService()
        val catalogMutations = fixture.catalogMutationService()

        contextRunner
            .withBean(DocumentDraftService::class.java, Supplier { drafts })
            .withBean(DocumentCatalogDraftService::class.java, Supplier { catalogDrafts })
            .withBean(DocumentCatalogMutationService::class.java, Supplier { catalogMutations })
            .run { context ->
                val facade = context.getBean(DocumentApiWriteFacade::class.java)
                ByteArrayInputStream(byteArrayOf(1, 2, 3)).use { content ->
                    facade.create(
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
                ByteArrayInputStream(byteArrayOf(4, 5, 6)).use { content ->
                    val added = facade.addVersion(
                        "document-1",
                        AddDocumentVersionCommand("2.0", "revision.pdf", 3, "application/pdf"),
                        content,
                    )
                    assertEquals("version-2", added.versionId)
                }
                val renamed = facade.rename("document-1", RenameDocumentCommand("Renamed"))
                assertEquals("document-1", renamed.documentId)
            }

        assertEquals(2, fixture.storage.uploads.size)
        assertEquals("Renamed", fixture.documents.values.getValue(Identifier("document-1")).title)
    }

    @Test
    fun `fails startup when the host provides multiple catalog draft services`() {
        contextRunner
            .withUserConfiguration(MultipleCatalogDraftServicesConfiguration::class.java)
            .run { context ->
                assertTrue(context.startupFailure != null)
            }
    }

    @Test
    fun `fails startup when the host provides multiple catalog mutation services`() {
        contextRunner
            .withUserConfiguration(MultipleCatalogMutationServicesConfiguration::class.java)
            .run { context ->
                assertTrue(context.startupFailure != null)
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleCatalogDraftServicesConfiguration {
        @Bean
        fun fixture(): V1DocumentWriteTestFixture = V1DocumentWriteTestFixture()

        @Bean
        fun documentDraftService(fixture: V1DocumentWriteTestFixture): DocumentDraftService = fixture.drafts

        @Bean
        fun firstCatalogDraftService(fixture: V1DocumentWriteTestFixture): DocumentCatalogDraftService =
            fixture.catalogDraftService()

        @Bean
        fun secondCatalogDraftService(fixture: V1DocumentWriteTestFixture): DocumentCatalogDraftService =
            fixture.catalogDraftService()
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleCatalogMutationServicesConfiguration {
        @Bean
        fun fixture(): V1DocumentWriteTestFixture = V1DocumentWriteTestFixture()

        @Bean
        fun documentDraftService(fixture: V1DocumentWriteTestFixture): DocumentDraftService = fixture.drafts

        @Bean
        fun firstCatalogMutationService(fixture: V1DocumentWriteTestFixture): DocumentCatalogMutationService =
            fixture.catalogMutationService()

        @Bean
        fun secondCatalogMutationService(fixture: V1DocumentWriteTestFixture): DocumentCatalogMutationService =
            fixture.catalogMutationService()
    }
}
