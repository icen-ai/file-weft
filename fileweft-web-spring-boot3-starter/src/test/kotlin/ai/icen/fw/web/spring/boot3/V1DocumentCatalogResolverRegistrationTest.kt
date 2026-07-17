package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.catalog.DocumentCatalogApiFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentCatalogController
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentCatalogRequestFailureHandler
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockServletContext
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

class V1DocumentCatalogResolverRegistrationTest {
    @Test
    fun `catalog WebMvcConfigurer installs the safe request failure resolver on Boot 3`() {
        val context = AnnotationConfigWebApplicationContext().apply {
            servletContext = MockServletContext()
            register(TestConfiguration::class.java)
            refresh()
        }
        try {
            val mvc = MockMvcBuilders.webAppContextSetup(context).build()

            mvc.perform(
                put("/fileweft/v1/documents/document-a/catalog-folder")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{"),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        } finally {
            context.close()
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    class TestConfiguration {
        @Bean
        fun responses(): V1ApiResponseFactory = V1ApiResponseFactory()

        @Bean
        fun catalogAccess(): DocumentCatalogAccessService = catalogAccessFixture()

        @Bean
        fun catalogFacade(catalogAccess: DocumentCatalogAccessService): DocumentCatalogApiFacade =
            DocumentCatalogApiFacade(catalogAccess)

        @Bean
        fun catalogController(
            facade: DocumentCatalogApiFacade,
            responses: V1ApiResponseFactory,
        ): V1DocumentCatalogController = V1DocumentCatalogController(facade, responses, null)

        @Bean
        fun catalogFailureHandler(responses: V1ApiResponseFactory): V1DocumentCatalogRequestFailureHandler =
            V1DocumentCatalogRequestFailureHandler(responses, null)
    }
}

private fun catalogAccessFixture(): DocumentCatalogAccessService = DocumentCatalogAccessService(
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
        override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
            listOf(DocumentCatalogFolder("root", null, "Root"))
    },
)
