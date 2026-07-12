package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentLifecycleApiFacade
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.io.support.SpringFactoriesLoader
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileWeftWebBoot2LifecycleAutoConfigurationTest {
    @Test
    fun `always installs the lifecycle transport and keeps absent application capabilities fail closed`() {
        contextRunner().run { context ->
            assertEquals(1, context.getBeansOfType(DocumentLifecycleApiFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(DocumentV1LifecycleController::class.java).size)
            assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
        }
    }

    @Test
    fun `fails startup for multiple catalog access candidates even when one is primary`() {
        contextRunner()
            .withUserConfiguration(MultipleCatalogAccessConfiguration::class.java)
            .run { context ->
                val failure = assertNotNull(context.startupFailure)
                assertTrue(
                    generateSequence(failure) { throwable -> throwable.cause }
                        .any { throwable ->
                            throwable is IllegalArgumentException &&
                                throwable.message == "Formal lifecycle API requires at most one catalog access boundary."
                        },
                )
            }
    }

    @Test
    fun `registers lifecycle auto configuration through spring factories`() {
        val factories = SpringFactoriesLoader.loadFactoryNames(
            EnableAutoConfiguration::class.java,
            FileWeftWebBoot2LifecycleAutoConfiguration::class.java.classLoader,
        )

        assertTrue(factories.contains(FileWeftWebBoot2LifecycleAutoConfiguration::class.java.name))
    }

    private fun contextRunner(): WebApplicationContextRunner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileWeftWebBoot2LifecycleAutoConfiguration::class.java))

    @Configuration(proxyBeanMethods = false)
    internal class MultipleCatalogAccessConfiguration {
        @Bean
        @Primary
        fun primaryCatalogAccess(): DocumentCatalogAccessService = catalogAccess()

        @Bean
        fun secondaryCatalogAccess(): DocumentCatalogAccessService = catalogAccess()

        private fun catalogAccess(): DocumentCatalogAccessService = DocumentCatalogAccessService(
            tenantProvider = object : TenantProvider {
                override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
            },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-1"), "User One")

                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(
                    request: ai.icen.fw.spi.authorization.AuthorizationRequest,
                ): AuthorizationDecision = AuthorizationDecision(true)
            },
            catalog = object : DocumentCatalogProvider {
                override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = emptyList()
            },
        )
    }
}
