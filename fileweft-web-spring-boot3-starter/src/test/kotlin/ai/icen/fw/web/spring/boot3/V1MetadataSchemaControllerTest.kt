package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.metadata.MetadataSchemaQueryService
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.metadata.api.MetadataField
import ai.icen.fw.metadata.api.MetadataFieldType
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.metadata.MetadataSchemaApiFacade
import ai.icen.fw.web.spring.boot3.v1.metadata.V1MetadataSchemaController
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class V1MetadataSchemaControllerTest {
    @Test
    fun `returns current schema fields and validation rules`() {
        val mvc = mvc { schema() }

        mvc.perform(get("/fileweft/v1/metadata/schemas/invoice"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.id").value("invoice"))
            .andExpect(jsonPath("$.data.version").value("2"))
            .andExpect(jsonPath("$.data.fields[0].name").value("department"))
            .andExpect(jsonPath("$.data.fields[0].type").value("ENUM"))
            .andExpect(jsonPath("$.data.fields[0].required").value(true))
            .andExpect(jsonPath("$.data.fields[0].allowedValues[0]").value("finance"))
            .andExpect(jsonPath("$.data.fields[0].maxLength").value(32))
            .andExpect(jsonPath("$.data.fields[0].format").value("[a-z]+"))
    }

    @Test
    fun `maps an unavailable schema to a fixed not found envelope`() {
        mvc { null }.perform(get("/fileweft/v1/metadata/schemas/private-schema"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resource was not found."))
    }

    @Test
    fun `maps a missing trusted user to a fixed envelope before resolver access`() {
        var resolverCalled = false
        val body = mvc(
            resolve = {
                resolverCalled = true
                schema()
            },
            currentUser = null,
        )
            .perform(get("/fileweft/v1/metadata/schemas/private-schema"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.message").value("Authentication is required."))
            .andReturn().response.contentAsString

        kotlin.test.assertFalse(resolverCalled)
        kotlin.test.assertFalse(body.contains("private-schema"))
    }

    @Test
    fun `maps a policy denial to a fixed envelope before resolver access`() {
        var resolverCalled = false
        val body = mvc(
            resolve = {
                resolverCalled = true
                schema()
            },
            authorizationDecision = AuthorizationDecision(false, "host policy detail"),
        )
            .perform(get("/fileweft/v1/metadata/schemas/private-schema"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("Access denied."))
            .andReturn().response.contentAsString

        kotlin.test.assertFalse(resolverCalled)
        kotlin.test.assertFalse(body.contains("private-schema"))
        kotlin.test.assertFalse(body.contains("host policy detail"))
    }

    private fun mvc(
        currentUser: UserIdentity? = UserIdentity(Identifier("user-a")),
        authorizationDecision: AuthorizationDecision = AuthorizationDecision(true),
        resolve: (MetadataSchemaContext) -> MetadataSchema?,
    ) = MockMvcBuilders.standaloneSetup(
        V1MetadataSchemaController(
            MetadataSchemaApiFacade(
                MetadataSchemaQueryService(
                    tenant(),
                    users(currentUser),
                    authorization(authorizationDecision),
                    resolver(resolve),
                ),
            ),
            V1ApiResponseFactory(),
            null as TraceContextProvider?,
        ),
    )
        .setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper()))
        .build()

    private fun resolver(resolve: (MetadataSchemaContext) -> MetadataSchema?) = object : MetadataSchemaResolver {
        override fun resolve(context: MetadataSchemaContext): MetadataSchema? = resolve(context)
    }

    private fun tenant() = object : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
    }

    private fun users(user: UserIdentity?) = object : UserRealmProvider {
        override fun currentUser(): UserIdentity? = user
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private fun authorization(decision: AuthorizationDecision) = object : AuthorizationProvider {
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision = decision
    }

    private fun schema(): MetadataSchema = MetadataSchema(
        "invoice",
        "2",
        listOf(
            MetadataField(
                "department",
                MetadataFieldType.ENUM,
                required = true,
                allowedValues = listOf("finance", "legal"),
                maxLength = 32,
                format = "[a-z]+",
            ),
        ),
    )
}
