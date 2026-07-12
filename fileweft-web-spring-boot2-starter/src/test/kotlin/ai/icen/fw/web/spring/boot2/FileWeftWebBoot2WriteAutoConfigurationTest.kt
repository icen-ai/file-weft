package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.catalog.DocumentCatalogDraftService
import ai.icen.fw.application.catalog.DocumentCatalogMutationService
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.spi.catalog.DocumentCatalogBinding
import ai.icen.fw.web.api.v1.document.AddDocumentVersionCommand
import ai.icen.fw.web.api.v1.document.CreateDocumentDraftCommand
import ai.icen.fw.web.api.v1.document.RenameDocumentCommand
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentApiReadFacade
import ai.icen.fw.web.runtime.v1.document.DocumentApiWriteFacade
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.SpringFactoriesLoader
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `keeps catalog mutations fail closed when only catalog draft service is available`() {
        contextRunner()
            .withUserConfiguration(CatalogDraftOnlyConfiguration::class.java)
            .run { context ->
                val fixture = context.getBean(DocumentV1WriteControllerTestFixture::class.java)
                val facade = context.getBean(DocumentApiWriteFacade::class.java)

                ByteArrayInputStream(byteArrayOf(1, 2, 3)).use { content ->
                    facade.create(
                        CreateDocumentDraftCommand(
                            documentNumber = "DOC-1",
                            title = "Catalog draft",
                            fileName = "draft.txt",
                            contentLength = 3,
                            contentType = "text/plain",
                            folderId = "finance",
                        ),
                        content,
                    )
                }
                val uploadCountBeforeMutation = fixture.storage.uploads.size

                assertFailsWith<IllegalStateException> {
                    ByteArrayInputStream(byteArrayOf(4, 5, 6)).use { content ->
                        facade.addVersion(
                            "document-1",
                            AddDocumentVersionCommand("2.0", "revision.txt", 3, "text/plain"),
                            content,
                        )
                    }
                }
                assertFailsWith<IllegalStateException> {
                    facade.rename("document-1", RenameDocumentCommand("Renamed catalog draft"))
                }

                assertEquals(uploadCountBeforeMutation, fixture.storage.uploads.size)
                assertEquals(
                    "finance",
                    fixture.storage.uploads.single().metadata[DocumentCatalogBinding.METADATA_KEY],
                )
                assertEquals("Catalog draft", fixture.documents.values.values.single().title)
            }
    }

    @Test
    fun `passes complete catalog draft and mutation services into the write facade`() {
        contextRunner()
            .withUserConfiguration(CompleteCatalogConfiguration::class.java)
            .run { context ->
                val fixture = context.getBean(DocumentV1WriteControllerTestFixture::class.java)
                val facade = context.getBean(DocumentApiWriteFacade::class.java)

                ByteArrayInputStream(byteArrayOf(1, 2, 3)).use { content ->
                    val created = facade.create(
                        CreateDocumentDraftCommand(
                            documentNumber = "DOC-1",
                            title = "Catalog draft",
                            fileName = "draft.txt",
                            contentLength = 3,
                            contentType = "text/plain",
                            folderId = "finance",
                        ),
                        content,
                    )
                    assertEquals("document-1", created.documentId)
                }
                ByteArrayInputStream(byteArrayOf(4, 5, 6)).use { content ->
                    val added = facade.addVersion(
                        "document-1",
                        AddDocumentVersionCommand("2.0", "revision.txt", 3, "text/plain"),
                        content,
                    )
                    assertEquals("version-2", added.versionId)
                }
                val renamed = facade.rename("document-1", RenameDocumentCommand("Renamed catalog draft"))

                assertEquals("document-1", renamed.documentId)
                assertEquals(2, fixture.storage.uploads.size)
                assertEquals(
                    "finance",
                    fixture.storage.uploads.first().metadata[DocumentCatalogBinding.METADATA_KEY],
                )
                assertEquals("Renamed catalog draft", fixture.documents.values.values.single().title)
            }
    }

    @Test
    fun `fails startup when the host provides multiple catalog draft services`() {
        contextRunner()
            .withUserConfiguration(MultipleCatalogDraftServicesConfiguration::class.java)
            .run { context ->
                assertTrue(requireNotNull(context.startupFailure).hasCause(NoUniqueBeanDefinitionException::class.java))
            }
    }

    @Test
    fun `fails startup when the host provides multiple catalog mutation services`() {
        contextRunner()
            .withUserConfiguration(MultipleCatalogMutationServicesConfiguration::class.java)
            .run { context ->
                assertTrue(requireNotNull(context.startupFailure).hasCause(NoUniqueBeanDefinitionException::class.java))
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

    private fun Throwable.hasCause(type: Class<out Throwable>): Boolean =
        generateSequence(this) { throwable -> throwable.cause }.any { throwable -> type.isInstance(throwable) }

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

    @Configuration(proxyBeanMethods = false)
    internal class CatalogDraftOnlyConfiguration {
        @Bean
        fun documentV1WriteControllerTestFixture(): DocumentV1WriteControllerTestFixture =
            DocumentV1WriteControllerTestFixture()

        @Bean
        fun documentDraftService(fixture: DocumentV1WriteControllerTestFixture): DocumentDraftService = fixture.drafts

        @Bean
        fun documentCatalogDraftService(fixture: DocumentV1WriteControllerTestFixture): DocumentCatalogDraftService =
            fixture.catalogDraftService()
    }

    @Configuration(proxyBeanMethods = false)
    internal class CompleteCatalogConfiguration {
        @Bean
        fun documentV1WriteControllerTestFixture(): DocumentV1WriteControllerTestFixture =
            DocumentV1WriteControllerTestFixture()

        @Bean
        fun documentDraftService(fixture: DocumentV1WriteControllerTestFixture): DocumentDraftService = fixture.drafts

        @Bean
        fun documentCatalogDraftService(fixture: DocumentV1WriteControllerTestFixture): DocumentCatalogDraftService =
            fixture.catalogDraftService()

        @Bean
        fun documentCatalogMutationService(
            fixture: DocumentV1WriteControllerTestFixture,
        ): DocumentCatalogMutationService = fixture.catalogMutationService()
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleCatalogDraftServicesConfiguration {
        @Bean
        fun documentV1WriteControllerTestFixture(): DocumentV1WriteControllerTestFixture =
            DocumentV1WriteControllerTestFixture()

        @Bean
        fun documentDraftService(fixture: DocumentV1WriteControllerTestFixture): DocumentDraftService = fixture.drafts

        @Bean
        fun firstCatalogDraftService(fixture: DocumentV1WriteControllerTestFixture): DocumentCatalogDraftService =
            fixture.catalogDraftService()

        @Bean
        fun secondCatalogDraftService(fixture: DocumentV1WriteControllerTestFixture): DocumentCatalogDraftService =
            fixture.catalogDraftService()
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleCatalogMutationServicesConfiguration {
        @Bean
        fun documentV1WriteControllerTestFixture(): DocumentV1WriteControllerTestFixture =
            DocumentV1WriteControllerTestFixture()

        @Bean
        fun documentDraftService(fixture: DocumentV1WriteControllerTestFixture): DocumentDraftService = fixture.drafts

        @Bean
        fun firstCatalogMutationService(
            fixture: DocumentV1WriteControllerTestFixture,
        ): DocumentCatalogMutationService = fixture.catalogMutationService()

        @Bean
        fun secondCatalogMutationService(
            fixture: DocumentV1WriteControllerTestFixture,
        ): DocumentCatalogMutationService = fixture.catalogMutationService()
    }
}
