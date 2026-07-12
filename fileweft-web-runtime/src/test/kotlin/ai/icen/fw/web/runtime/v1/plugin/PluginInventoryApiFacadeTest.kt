package ai.icen.fw.web.runtime.v1.plugin

import ai.icen.fw.application.plugin.PluginCapabilityDescriptor
import ai.icen.fw.application.plugin.PluginCapabilityType
import ai.icen.fw.application.plugin.PluginInventoryDescriptor
import ai.icen.fw.application.plugin.PluginInventoryProvider
import ai.icen.fw.application.plugin.PluginInventoryQueryService
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.api.v1.plugin.PluginPageQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ai.icen.fw.web.runtime.v1.V1FeatureUnavailableException
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginInventoryApiFacadeTest {
    @Test
    fun `maps safe capabilities and round trips an opaque kind-specific cursor`() {
        val facade = facade(
            listOf(
                descriptor("plugin-c"),
                descriptor("插件-a", PluginCapabilityType.CONNECTOR, 2),
                descriptor("plugin-b"),
            ),
        )

        val first = facade.page(PluginPageQuery(limit = 2))
        val second = facade.page(PluginPageQuery(first.nextCursor, 2))

        assertEquals(listOf("plugin-b", "plugin-c"), first.items.map { plugin -> plugin.id })
        assertEquals(listOf("插件-a"), second.items.map { plugin -> plugin.id })
        assertEquals("CONNECTOR", second.items.single().capabilities.single().type)
        assertEquals(2, second.items.single().capabilities.single().count)
        assertNull(second.nextCursor)
    }

    @Test
    fun `rejects malformed and wrong-kind cursors before inventory access`() {
        val facade = facade(listOf(descriptor("plugin-a")))
        val wrongKind = "AQIAAgBh"

        assertThrows<IllegalArgumentException> { facade.page(PluginPageQuery("not-a-valid-cursor", 20)) }
        assertThrows<IllegalArgumentException> { facade.page(PluginPageQuery(wrongKind, 20)) }
    }

    @Test
    fun `fails closed when inventory capability is absent or ambiguous`() {
        assertThrows<V1FeatureUnavailableException> {
            PluginInventoryApiFacade(emptyList()).page(PluginPageQuery())
        }
        val query = queryService(listOf(descriptor("plugin-a")))
        assertThrows<IllegalArgumentException> {
            PluginInventoryApiFacade(listOf(query, query))
        }
    }

    private fun facade(values: List<PluginInventoryDescriptor>): PluginInventoryApiFacade =
        PluginInventoryApiFacade(queryService(values))

    private fun queryService(values: List<PluginInventoryDescriptor>): PluginInventoryQueryService =
        PluginInventoryQueryService(
                tenants = object : TenantProvider {
                    override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
                },
                users = object : UserRealmProvider {
                    override fun currentUser(): UserIdentity = UserIdentity(Identifier("operator-a"), "Operator A")
                    override fun findUser(userId: Identifier): UserIdentity? = null
                },
                authorizationProvider = object : AuthorizationProvider {
                    override fun authorize(request: ai.icen.fw.spi.authorization.AuthorizationRequest): AuthorizationDecision =
                        AuthorizationDecision(true)
                },
                provider = object : PluginInventoryProvider {
                    override fun inventory(): List<PluginInventoryDescriptor> = values
                },
        )

    private fun descriptor(
        id: String,
        type: PluginCapabilityType? = null,
        count: Int = 1,
    ): PluginInventoryDescriptor = PluginInventoryDescriptor(
        id,
        type?.let { capability -> listOf(PluginCapabilityDescriptor(capability, count)) } ?: emptyList(),
    )
}
