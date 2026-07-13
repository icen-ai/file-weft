package ai.icen.fw.starter.boot2

import ai.icen.fw.adapter.storage.LocalStorageAdapter
import ai.icen.fw.adapter.authorization.DefaultAuthorizationProvider
import ai.icen.fw.adapter.identity.DefaultUserRealmProvider
import ai.icen.fw.application.plugin.PluginCapabilityType
import ai.icen.fw.application.plugin.PluginInventoryDescriptor
import ai.icen.fw.application.plugin.PluginInventoryPageRequest
import ai.icen.fw.application.plugin.PluginInventoryProvider
import ai.icen.fw.application.plugin.PluginInventoryQueryService
import ai.icen.fw.application.security.ApplicationUnauthenticatedException
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.runtime.plugin.FileWeftPluginRegistry
import ai.icen.fw.spi.ai.AgentTaskTrigger
import ai.icen.fw.spi.ai.FileWeftAgent
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.plugin.FileWeftPlugin
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.lang.reflect.Proxy
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginInventoryAutoConfigurationTest {
    @Test
    fun `keeps the starter identity and authorization fallbacks fail closed`() {
        contextRunner()
            .withUserConfiguration(TenantOnlyConfiguration::class.java, CustomerStorageConfiguration::class.java)
            .run { context ->
                assertNull(context.startupFailure)
                assertTrue(context.getBean(UserRealmProvider::class.java) is DefaultUserRealmProvider)
                assertTrue(context.getBean(AuthorizationProvider::class.java) is DefaultAuthorizationProvider)
                assertFailsWith<ApplicationUnauthenticatedException> {
                    context.getBean(PluginInventoryQueryService::class.java).page(PluginInventoryPageRequest())
                }
            }
    }

    @Test
    fun `assembles authorized inventory queries from the default registry without a DataSource`() {
        contextRunner()
            .withUserConfiguration(
                TrustedBoundariesConfiguration::class.java,
                CustomerStorageConfiguration::class.java,
                RegistryPluginConfiguration::class.java,
            )
            .run { context ->
                assertNull(context.startupFailure)
                val page = context.getBean(PluginInventoryQueryService::class.java)
                    .page(PluginInventoryPageRequest())

                assertEquals(listOf("registry-plugin"), page.items.map { descriptor -> descriptor.id })
                val authorization = context.getBean(RecordingAuthorizationProvider::class.java)
                val request = authorization.requests.single()
                assertEquals(PluginInventoryQueryService.PLUGIN_INVENTORY_ACTION, request.action.name)
                assertEquals(Identifier("trusted-tenant"), request.resource.tenantId)
                assertEquals(Identifier("trusted-user"), request.subject.id)
            }
    }

    @Test
    fun `hides deferred Agent plugin capabilities unless the legacy compatibility property is enabled`() {
        contextRunner()
            .withUserConfiguration(
                TrustedBoundariesConfiguration::class.java,
                CustomerStorageConfiguration::class.java,
                LegacyAgentPluginConfiguration::class.java,
            )
            .run { context ->
                assertNull(context.startupFailure)
                val descriptor = context.getBean(PluginInventoryQueryService::class.java)
                    .page(PluginInventoryPageRequest())
                    .items
                    .single { item -> item.id == "legacy-agent-plugin" }

                assertTrue(descriptor.capabilities.isEmpty())
            }

        contextRunner()
            .withUserConfiguration(
                TrustedBoundariesConfiguration::class.java,
                CustomerStorageConfiguration::class.java,
                LegacyAgentPluginConfiguration::class.java,
            )
            .withPropertyValues("fileweft.compatibility.legacy-agent-autoconfiguration-enabled=true")
            .run { context ->
                assertNull(context.startupFailure)
                val descriptor = context.getBean(PluginInventoryQueryService::class.java)
                    .page(PluginInventoryPageRequest())
                    .items
                    .single { item -> item.id == "legacy-agent-plugin" }

                assertEquals(
                    listOf(PluginCapabilityType.AGENT, PluginCapabilityType.AGENT_TASK_TRIGGER),
                    descriptor.capabilities.map { capability -> capability.type },
                )
            }
    }

    @Test
    fun `prefers one explicit inventory provider over the default registry`() {
        contextRunner()
            .withUserConfiguration(
                TrustedBoundariesConfiguration::class.java,
                CustomerStorageConfiguration::class.java,
                RegistryPluginConfiguration::class.java,
                ExplicitProviderConfiguration::class.java,
            )
            .run { context ->
                assertNull(context.startupFailure)
                val page = context.getBean(PluginInventoryQueryService::class.java)
                    .page(PluginInventoryPageRequest())

                assertEquals(listOf("explicit-provider"), page.items.map { descriptor -> descriptor.id })
            }
    }

    @Test
    fun `backs off completely for a customer inventory query service`() {
        contextRunner()
            .withUserConfiguration(
                TrustedBoundariesConfiguration::class.java,
                CustomerStorageConfiguration::class.java,
                MultipleExplicitProvidersConfiguration::class.java,
                CustomerQueryServiceConfiguration::class.java,
            )
            .run { context ->
                assertNull(context.startupFailure)
                assertSame(
                    context.getBean("customerPluginInventoryQueryService"),
                    context.getBean(PluginInventoryQueryService::class.java),
                )
                val page = context.getBean(PluginInventoryQueryService::class.java)
                    .page(PluginInventoryPageRequest())
                assertEquals(listOf("customer-query-service"), page.items.map { descriptor -> descriptor.id })
            }
    }

    @Test
    fun `honors primary trusted boundaries when hosts expose multiple candidates`() {
        contextRunner()
            .withUserConfiguration(
                TrustedBoundariesConfiguration::class.java,
                CustomerStorageConfiguration::class.java,
                SecondTenantProviderConfiguration::class.java,
                SecondUserRealmProviderConfiguration::class.java,
                SecondAuthorizationProviderConfiguration::class.java,
            )
            .run { context ->
                assertNull(context.startupFailure)
                context.getBean(PluginInventoryQueryService::class.java).page(PluginInventoryPageRequest())

                val authorization = context.getBean("secondAuthorizationProvider") as RecordingAuthorizationProvider
                val request = authorization.requests.single()
                assertEquals(Identifier("second-tenant"), request.resource.tenantId)
                assertEquals(Identifier("second-user"), request.subject.id)
            }
    }

    @Test
    fun `leaves the optional inventory query unavailable for truly ambiguous trusted boundaries`() {
        listOf(
            AmbiguousTenantProviderConfiguration::class.java,
            AmbiguousUserRealmProviderConfiguration::class.java,
            AmbiguousAuthorizationProviderConfiguration::class.java,
        ).forEach { ambiguousConfiguration ->
            contextRunner()
                .withUserConfiguration(
                    TrustedBoundariesConfiguration::class.java,
                    CustomerStorageConfiguration::class.java,
                    ambiguousConfiguration,
                )
                .run { context ->
                    assertNull(context.startupFailure)
                    assertTrue(context.getBeansOfType(PluginInventoryQueryService::class.java).isEmpty())
                }
        }
    }

    @Test
    fun `fails closed for every ambiguous inventory provider`() {
        listOf(
            arrayOf(MultipleExplicitProvidersConfiguration::class.java),
            arrayOf(MultipleRegistriesConfiguration::class.java),
            arrayOf(ExplicitProviderConfiguration::class.java, MultipleRegistriesConfiguration::class.java),
        ).forEach { ambiguousConfigurations ->
            contextRunner()
                .withUserConfiguration(
                    TrustedBoundariesConfiguration::class.java,
                    CustomerStorageConfiguration::class.java,
                    *ambiguousConfigurations,
                )
                .run { context ->
                    assertTrue(
                        requireNotNull(context.startupFailure)
                            .hasCause(NoUniqueBeanDefinitionException::class.java),
                        "Expected ${ambiguousConfigurations.joinToString { it.simpleName }} to fail closed.",
                    )
                }
        }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileWeftAutoConfiguration::class.java))

    private fun Throwable.hasCause(type: Class<out Throwable>): Boolean =
        generateSequence(this) { failure -> failure.cause }.any(type::isInstance)

    @Configuration(proxyBeanMethods = false)
    class TrustedBoundariesConfiguration {
        @Bean
        fun trustedTenantProvider(): TenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("trusted-tenant"))
        }

        @Bean
        fun trustedUserRealmProvider(): UserRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("trusted-user"))

            override fun findUser(userId: Identifier): UserIdentity? = null
        }

        @Bean
        fun trustedAuthorizationProvider(): RecordingAuthorizationProvider = RecordingAuthorizationProvider()
    }

    @Configuration(proxyBeanMethods = false)
    class TenantOnlyConfiguration {
        @Bean
        fun trustedTenantProvider(): TenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("trusted-tenant"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class CustomerStorageConfiguration {
        @Bean
        fun customerStorageAdapter(): StorageAdapter = LocalStorageAdapter(
            Paths.get(System.getProperty("java.io.tmpdir"), "fileweft-boot2-plugin-inventory-test"),
        )
    }

    @Configuration(proxyBeanMethods = false)
    class RegistryPluginConfiguration {
        @Bean
        fun registryPlugin(): FileWeftPlugin = plugin("registry-plugin")
    }

    @Configuration(proxyBeanMethods = false)
    class LegacyAgentPluginConfiguration {
        @Bean
        fun legacyAgentPlugin(): FileWeftPlugin = agentPlugin()
    }

    @Configuration(proxyBeanMethods = false)
    class ExplicitProviderConfiguration {
        @Bean
        fun explicitPluginInventoryProvider(): PluginInventoryProvider = provider("explicit-provider")
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleExplicitProvidersConfiguration {
        @Bean
        @Primary
        fun primaryPluginInventoryProvider(): PluginInventoryProvider = provider("primary-provider")

        @Bean
        fun secondaryPluginInventoryProvider(): PluginInventoryProvider = provider("secondary-provider")
    }

    @Configuration(proxyBeanMethods = false)
    class CustomerQueryServiceConfiguration {
        @Bean
        fun customerPluginInventoryQueryService(
            tenants: TenantProvider,
            users: UserRealmProvider,
            authorization: AuthorizationProvider,
        ): PluginInventoryQueryService = PluginInventoryQueryService(
            tenants,
            users,
            authorization,
            provider("customer-query-service"),
        )
    }

    @Configuration(proxyBeanMethods = false)
    class SecondTenantProviderConfiguration {
        @Bean
        @Primary
        fun secondTenantProvider(): TenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("second-tenant"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class SecondUserRealmProviderConfiguration {
        @Bean
        @Primary
        fun secondUserRealmProvider(): UserRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("second-user"))

            override fun findUser(userId: Identifier): UserIdentity? = null
        }
    }

    @Configuration(proxyBeanMethods = false)
    class SecondAuthorizationProviderConfiguration {
        @Bean
        @Primary
        fun secondAuthorizationProvider(): AuthorizationProvider = RecordingAuthorizationProvider()
    }

    @Configuration(proxyBeanMethods = false)
    class AmbiguousTenantProviderConfiguration {
        @Bean
        fun ambiguousTenantProvider(): TenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("ambiguous-tenant"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class AmbiguousUserRealmProviderConfiguration {
        @Bean
        fun ambiguousUserRealmProvider(): UserRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("ambiguous-user"))

            override fun findUser(userId: Identifier): UserIdentity? = null
        }
    }

    @Configuration(proxyBeanMethods = false)
    class AmbiguousAuthorizationProviderConfiguration {
        @Bean
        fun ambiguousAuthorizationProvider(): AuthorizationProvider = RecordingAuthorizationProvider()
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleRegistriesConfiguration {
        @Bean
        @Primary
        fun primaryPluginRegistry(): FileWeftPluginRegistry = FileWeftPluginRegistry(emptyList())

        @Bean
        fun secondaryPluginRegistry(): FileWeftPluginRegistry = FileWeftPluginRegistry(emptyList())
    }

    class RecordingAuthorizationProvider : AuthorizationProvider {
        val requests = ArrayList<AuthorizationRequest>()

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            requests += request
            return AuthorizationDecision(true)
        }
    }

    private companion object {
        fun provider(id: String): PluginInventoryProvider = object : PluginInventoryProvider {
            override fun inventory(): List<PluginInventoryDescriptor> =
                listOf(PluginInventoryDescriptor(id, emptyList()))
        }

        fun plugin(id: String): FileWeftPlugin = object : FileWeftPlugin {
            override fun id(): String = id
        }

        fun agentPlugin(): FileWeftPlugin = object : FileWeftPlugin {
            override fun id(): String = "legacy-agent-plugin"

            override fun agents(): List<FileWeftAgent> = listOf(contribution(FileWeftAgent::class.java))

            override fun agentTaskTriggers(): List<AgentTaskTrigger> =
                listOf(contribution(AgentTaskTrigger::class.java))
        }

        private fun <T : Any> contribution(type: Class<T>): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                error("Deferred Agent contribution method ${method.name} must not be invoked by inventory discovery.")
            },
        )
    }
}
