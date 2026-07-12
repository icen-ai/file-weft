package ai.icen.fw.web.spring.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.plugin.PluginCapabilityDescriptor
import ai.icen.fw.application.plugin.PluginCapabilityType
import ai.icen.fw.application.plugin.PluginInventoryDescriptor
import ai.icen.fw.application.plugin.PluginInventoryProvider
import ai.icen.fw.application.plugin.PluginInventoryQueryService
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.health.HealthApiFacade
import ai.icen.fw.web.runtime.v1.plugin.PluginInventoryApiFacade
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class SystemProjectionV1ControllerMockMvcTest {
    @Test
    fun `serves paged plugin inventory and public health through both formal paths`() {
        val mapper = ObjectMapper()
        val provider = RecordingProvider(
            listOf(
                descriptor("plugin-b"),
                descriptor("plugin-a", PluginCapabilityType.CONNECTOR, 2),
            ),
        )
        val mvc = mvc(pluginFacade(provider))

        val first = mvc.perform(get(PLUGIN_V1_PATH).param("limit", "1"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
            .andExpect(header().string(X_CONTENT_TYPE_OPTIONS, NOSNIFF))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID.value))
            .andExpect(jsonPath("$.data.items[0].id").value("plugin-a"))
            .andExpect(jsonPath("$.data.items[0].capabilities[0].type").value("CONNECTOR"))
            .andExpect(jsonPath("$.data.items[0].capabilities[0].count").value(2))
            .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
            .andExpect(jsonPath("$.data.tenantId").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].className").doesNotExist())
            .andReturn()
        val cursor = mapper.readTree(first.response.contentAsString).path("data").path("nextCursor").asText()

        mvc.perform(get(PLUGIN_COMPATIBILITY_PATH).param("limit", "1").param("cursor", cursor))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
            .andExpect(jsonPath("$.data.items[0].id").value("plugin-b"))
            .andExpect(jsonPath("$.data.nextCursor").doesNotExist())

        listOf(HEALTH_V1_PATH, HEALTH_COMPATIBILITY_PATH).forEach { path ->
            mvc.perform(get(path))
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(header().string(X_CONTENT_TYPE_OPTIONS, NOSNIFF))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.application").doesNotExist())
                .andExpect(jsonPath("$.data.tenantId").doesNotExist())
        }
    }

    @Test
    fun `maps invalid denied and unavailable plugin inventory requests without leaking details`() {
        val deniedProvider = RecordingProvider(listOf(descriptor("plugin-a")))
        val mvc = mvc(pluginFacade(deniedProvider, allowed = false, denialReason = "policy-secret=password"))

        mvc.perform(get(PLUGIN_V1_PATH))
            .andExpect(status().isForbidden)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
            .andExpect(header().string(X_CONTENT_TYPE_OPTIONS, NOSNIFF))
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("Access denied."))
            .andExpect(content().string(not(containsString("policy-secret"))))

        mvc.perform(get(PLUGIN_V1_PATH).param("limit", "101"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        mvc.perform(get(PLUGIN_V1_PATH).param("cursor", "not-a-valid-cursor"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Request is invalid."))

        val unavailable = mvc(PluginInventoryApiFacade(emptyList<PluginInventoryQueryService>()))
        unavailable.perform(get(PLUGIN_COMPATIBILITY_PATH))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
            .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))

        kotlin.test.assertEquals(0, deniedProvider.calls)
    }

    @Test
    fun `rejects explicit HEAD for every alias without reading plugin inventory`() {
        val provider = RecordingProvider(listOf(descriptor("plugin-a")))
        val mvc = mvc(pluginFacade(provider))

        listOf(PLUGIN_V1_PATH, PLUGIN_COMPATIBILITY_PATH).forEach { path ->
            mvc.perform(head(path))
                .andExpect(status().isMethodNotAllowed)
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
                .andExpect(header().string(X_CONTENT_TYPE_OPTIONS, NOSNIFF))
        }
        listOf(HEALTH_V1_PATH, HEALTH_COMPATIBILITY_PATH).forEach { path ->
            mvc.perform(head(path))
                .andExpect(status().isMethodNotAllowed)
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(header().string(X_CONTENT_TYPE_OPTIONS, NOSNIFF))
        }

        kotlin.test.assertEquals(0, provider.calls)
    }

    private fun mvc(plugins: PluginInventoryApiFacade): MockMvc {
        val responses = V1ApiResponseFactory()
        val traces = object : TraceContextProvider {
            override fun currentTraceContext(): TraceContext = TraceContext(TRACE_ID)
        }
        return MockMvcBuilders.standaloneSetup(
            PluginInventoryV1Controller(plugins, responses, traces),
            HealthV1Controller(HealthApiFacade(), responses, traces),
        ).setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper())).build()
    }

    private fun pluginFacade(
        provider: PluginInventoryProvider,
        allowed: Boolean = true,
        denialReason: String? = null,
    ): PluginInventoryApiFacade = PluginInventoryApiFacade(
        PluginInventoryQueryService(
            tenants = object : TenantProvider {
                override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
            },
            users = object : UserRealmProvider {
                override fun currentUser(): UserIdentity = UserIdentity(Identifier("operator-a"), "Operator A")
                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest): AuthorizationDecision =
                    AuthorizationDecision(allowed, denialReason)
            },
            provider = provider,
        ),
    )

    private fun descriptor(
        id: String,
        type: PluginCapabilityType? = null,
        count: Int = 1,
    ): PluginInventoryDescriptor = PluginInventoryDescriptor(
        id,
        type?.let { capability -> listOf(PluginCapabilityDescriptor(capability, count)) } ?: emptyList(),
    )

    private class RecordingProvider(private val values: List<PluginInventoryDescriptor>) : PluginInventoryProvider {
        var calls: Int = 0

        override fun inventory(): List<PluginInventoryDescriptor> {
            calls += 1
            return values
        }
    }

    private companion object {
        const val PLUGIN_V1_PATH = "/fileweft/v1/plugins"
        const val PLUGIN_COMPATIBILITY_PATH = "/fileweft/plugins"
        const val HEALTH_V1_PATH = "/fileweft/v1/health"
        const val HEALTH_COMPATIBILITY_PATH = "/fileweft/health"
        const val PRIVATE_NO_STORE = "private, no-store"
        const val NO_STORE = "no-store"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
        val TRACE_ID: Identifier = Identifier("trace-system-projection-1")
    }
}
