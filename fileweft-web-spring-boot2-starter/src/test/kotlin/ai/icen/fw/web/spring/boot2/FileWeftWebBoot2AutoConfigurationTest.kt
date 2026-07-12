package ai.icen.fw.web.spring.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryService
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentApiReadFacade
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.SpringFactoriesLoader
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.function.Supplier

class FileWeftWebBoot2AutoConfigurationTest {
    @Test
    fun `does not expose document read routes without the secure document query service`() {
        contextRunner().run { context ->
            assertTrue(context.getBeansOfType(DocumentApiReadFacade::class.java).isEmpty())
            assertTrue(context.getBeansOfType(V1ApiResponseFactory::class.java).isEmpty())
            assertTrue(context.getBeansOfType(DocumentV1Controller::class.java).isEmpty())
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

    @Configuration(proxyBeanMethods = false)
    class DocumentQueryServiceConfiguration {
        @Bean
        fun documentQueryService(): DocumentQueryService = DocumentV1ControllerTestFixture().service()

        @Bean
        fun traceContextProvider(): TraceContextProvider = DocumentV1ControllerTestFixture.traceProvider("auto-config-trace")
    }
}
