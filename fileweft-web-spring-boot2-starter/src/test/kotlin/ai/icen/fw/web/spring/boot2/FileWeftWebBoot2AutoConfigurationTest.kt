package ai.icen.fw.web.spring.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogBindingCommand
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryService
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.catalog.DocumentCatalogApiFacade
import ai.icen.fw.web.runtime.v1.document.DocumentApiReadFacade
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.io.support.SpringFactoriesLoader
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.function.Supplier

private fun catalogBindingFixture(): DocumentCatalogBindingCommand = object : DocumentCatalogBindingCommand {
    override fun move(documentId: Identifier, folderId: String): Document = Document(
        documentId,
        Identifier("tenant-a"),
        Identifier("asset-a"),
        "DOC-1",
        "Document",
    )
}

class FileWeftWebBoot2AutoConfigurationTest {
    @Test
    fun `does not expose document read routes without the secure document query service`() {
        contextRunner().run { context ->
            assertTrue(context.getBeansOfType(DocumentApiReadFacade::class.java).isEmpty())
            assertTrue(context.getBeansOfType(V1ApiResponseFactory::class.java).isEmpty())
            assertTrue(context.getBeansOfType(DocumentV1Controller::class.java).isEmpty())
            assertTrue(context.getBeansOfType(DocumentCatalogApiFacade::class.java).isEmpty())
            assertTrue(context.getBeansOfType(DocumentCatalogV1Controller::class.java).isEmpty())
            assertTrue(context.getBeansOfType(DocumentCatalogV1RequestFailureHandler::class.java).isEmpty())
        }
    }

    @Test
    fun `assembles a read-only catalog capability and fails closed for move without one command`() {
        contextRunner()
            .withBean(DocumentCatalogAccessService::class.java, Supplier { catalogAccess() })
            .run { context ->
                assertEquals(1, context.getBeansOfType(DocumentCatalogApiFacade::class.java).size)
                assertEquals(1, context.getBeansOfType(DocumentCatalogV1Controller::class.java).size)
                assertEquals(1, context.getBeansOfType(DocumentCatalogV1RequestFailureHandler::class.java).size)
                assertFailsWith<IllegalStateException> {
                    context.getBean(DocumentCatalogApiFacade::class.java).move("document-a", "child")
                }
            }
    }

    @Test
    fun `uses one catalog binding command and rejects ambiguous candidates`() {
        contextRunner()
            .withBean(DocumentCatalogAccessService::class.java, Supplier { catalogAccess() })
            .withUserConfiguration(UniqueCatalogBindingConfiguration::class.java)
            .run { context ->
                val result = context.getBean(DocumentCatalogApiFacade::class.java).move("document-a", "child")
                assertEquals("document-a", result.documentId)
            }

        contextRunner()
            .withBean(DocumentCatalogAccessService::class.java, Supplier { catalogAccess() })
            .withUserConfiguration(AmbiguousCatalogBindingConfiguration::class.java)
            .run { context ->
                assertFailsWith<IllegalStateException> {
                    context.getBean(DocumentCatalogApiFacade::class.java).move("document-a", "child")
                }
            }
    }

    @Test
    fun `exposes sync status independently from the general document query service`() {
        contextRunner()
            .withBean(
                DocumentSyncStatusQueryService::class.java,
                Supplier { syncStatusQueryService() },
            )
            .run { context ->
                assertTrue(context.getBeansOfType(DocumentV1Controller::class.java).isEmpty())
                assertNotNull(context.getBeanProvider(DocumentV1SyncStatusController::class.java).getIfAvailable())
                assertNotNull(
                    context.getBeanProvider(
                        ai.icen.fw.web.runtime.v1.document.DocumentSyncStatusApiFacade::class.java,
                    ).getIfAvailable(),
                )
            }
    }

    @Test
    fun `assembles v1 MVC components only after a host supplies the document query service`() {
        contextRunner()
            .withUserConfiguration(DocumentQueryServiceConfiguration::class.java)
            .run { context ->
                assertEquals(1, context.getBeansOfType(DocumentApiReadFacade::class.java).size)
                assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
                assertEquals(1, context.getBeansOfType(DocumentV1Controller::class.java).size)
                MockMvcBuilders.standaloneSetup(context.getBean(DocumentV1Controller::class.java))
                    .setMessageConverters(
                        MappingJackson2HttpMessageConverter(context.getBean(ObjectMapper::class.java)),
                    )
                    .build()
                    .perform(get("/fileweft/v1/documents/document-1"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.traceId").value("auto-config-trace"))
                    .andExpect(jsonPath("$.success").doesNotExist())
                    .andExpect(jsonPath("$.failure").doesNotExist())
            }
    }

    @Test
    fun `registers the boot 2 auto configuration through spring factories`() {
        val factories = SpringFactoriesLoader.loadFactoryNames(
            EnableAutoConfiguration::class.java,
            FileWeftWebBoot2AutoConfiguration::class.java.classLoader,
        )

        assertTrue(factories.contains(FileWeftWebBoot2AutoConfiguration::class.java.name))
    }

    private fun contextRunner(): WebApplicationContextRunner = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                FileWeftWebBoot2AutoConfiguration::class.java,
            ),
        )

    private fun syncStatusQueryService(): DocumentSyncStatusQueryService = DocumentSyncStatusQueryService(
        Mockito.mock(TenantProvider::class.java),
        Mockito.mock(UserRealmProvider::class.java),
        Mockito.mock(AuthorizationProvider::class.java),
        Mockito.mock(DocumentSyncStatusQueryRepository::class.java),
        Mockito.mock(ApplicationTransaction::class.java),
    )

    private fun catalogAccess(): DocumentCatalogAccessService = DocumentCatalogAccessService(
        object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
        },
        object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-a"), "User A")
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
        },
        object : DocumentCatalogProvider {
            override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = listOf(
                DocumentCatalogFolder("root", null, "Root"),
                DocumentCatalogFolder("child", "root", "Child"),
            )
        },
    )

    @Configuration(proxyBeanMethods = false)
    class UniqueCatalogBindingConfiguration {
        @Bean
        fun catalogBinding(): DocumentCatalogBindingCommand = catalogBindingFixture()
    }

    @Configuration(proxyBeanMethods = false)
    class AmbiguousCatalogBindingConfiguration {
        @Bean
        @Primary
        fun firstCatalogBinding(): DocumentCatalogBindingCommand = catalogBindingFixture()

        @Bean
        fun secondCatalogBinding(): DocumentCatalogBindingCommand = catalogBindingFixture()
    }

    @Configuration(proxyBeanMethods = false)
    class DocumentQueryServiceConfiguration {
        @Bean
        fun documentQueryService(): DocumentQueryService = DocumentV1ControllerTestFixture().service()

        @Bean
        fun traceContextProvider(): TraceContextProvider = DocumentV1ControllerTestFixture.traceProvider("auto-config-trace")
    }
}
