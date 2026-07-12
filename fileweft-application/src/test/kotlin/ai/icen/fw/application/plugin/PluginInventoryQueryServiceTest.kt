package ai.icen.fw.application.plugin

import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PluginInventoryQueryServiceTest {
    @Test
    fun `authorizes a global inventory through the trusted tenant and returns deterministic pages`() {
        val authorization = RecordingAuthorization()
        val provider = RecordingProvider(
            listOf(
                descriptor("plugin-c", PluginCapabilityType.AGENT),
                descriptor("plugin-a", PluginCapabilityType.CONNECTOR),
                descriptor("plugin-b", PluginCapabilityType.DOCTOR_CHECKER),
            ),
        )
        val service = service(authorization, provider)

        val first = service.page(PluginInventoryPageRequest(limit = 2))
        val second = service.page(PluginInventoryPageRequest(first.nextCursor, limit = 2))

        assertEquals(listOf("plugin-a", "plugin-b"), first.items.map { item -> item.id })
        assertEquals("plugin-b", first.nextCursor?.pluginId)
        assertEquals(listOf("plugin-c"), second.items.map { item -> item.id })
        assertNull(second.nextCursor)
        assertEquals(2, provider.calls)
        assertEquals(PluginInventoryQueryService.PLUGIN_INVENTORY_ACTION, authorization.requests.first().action.name)
        assertEquals(PluginInventoryQueryService.SYSTEM_RESOURCE_ID, authorization.requests.first().resource.id)
        assertEquals(PluginInventoryQueryService.SYSTEM_RESOURCE_TYPE, authorization.requests.first().resource.type)
        assertEquals(Identifier("tenant-a"), authorization.requests.first().resource.tenantId)
        assertEquals(Identifier("operator-a"), authorization.requests.first().subject.id)
    }

    @Test
    fun `denial happens before the process inventory is read`() {
        val provider = RecordingProvider(emptyList())
        val service = service(RecordingAuthorization(allowed = false), provider)

        assertThrows<ApplicationForbiddenException> { service.page(PluginInventoryPageRequest()) }

        assertEquals(0, provider.calls)
    }

    @Test
    fun `an installed inventory capability may safely report no plugins`() {
        val service = service(RecordingAuthorization(), RecordingProvider(emptyList()))

        val page = service.page(PluginInventoryPageRequest())

        assertEquals(emptyList(), page.items)
        assertNull(page.nextCursor)
    }

    @Test
    fun `fails closed on duplicate or unsafe plugin ids`() {
        val duplicate = service(
            RecordingAuthorization(),
            RecordingProvider(listOf(descriptor("same"), descriptor("same"))),
        )
        val unsafe = service(
            RecordingAuthorization(),
            RecordingProvider(listOf(descriptor("unsafe\nplugin"))),
        )
        val disguised = service(
            RecordingAuthorization(),
            RecordingProvider(listOf(descriptor("unsafe\u200Bplugin"))),
        )
        val padded = service(
            RecordingAuthorization(),
            RecordingProvider(listOf(descriptor(" plugin-a"))),
        )
        val nonBreakingSpacePadded = service(
            RecordingAuthorization(),
            RecordingProvider(listOf(descriptor("\u00A0plugin-a"))),
        )
        val figureSpacePadded = service(
            RecordingAuthorization(),
            RecordingProvider(listOf(descriptor("plugin-a\u2007"))),
        )
        val narrowNoBreakSpacePadded = service(
            RecordingAuthorization(),
            RecordingProvider(listOf(descriptor("\u202Fplugin-a"))),
        )
        val bidirectionalOverride = service(
            RecordingAuthorization(),
            RecordingProvider(listOf(descriptor("unsafe\u202Eplugin"))),
        )

        assertFailsWith<IllegalStateException> { duplicate.page(PluginInventoryPageRequest()) }
        assertFailsWith<IllegalStateException> { unsafe.page(PluginInventoryPageRequest()) }
        assertFailsWith<IllegalStateException> { disguised.page(PluginInventoryPageRequest()) }
        assertFailsWith<IllegalStateException> { padded.page(PluginInventoryPageRequest()) }
        assertFailsWith<IllegalStateException> { nonBreakingSpacePadded.page(PluginInventoryPageRequest()) }
        assertFailsWith<IllegalStateException> { figureSpacePadded.page(PluginInventoryPageRequest()) }
        assertFailsWith<IllegalStateException> { narrowNoBreakSpacePadded.page(PluginInventoryPageRequest()) }
        assertFailsWith<IllegalStateException> { bidirectionalOverride.page(PluginInventoryPageRequest()) }
    }

    @Test
    fun `bounds requests and keeps returned collections immutable`() {
        val service = service(
            RecordingAuthorization(),
            RecordingProvider(listOf(descriptor("plugin-a", PluginCapabilityType.CONNECTOR))),
        )

        val page = service.page(PluginInventoryPageRequest())

        assertFailsWith<IllegalArgumentException> { PluginInventoryPageRequest(limit = 0) }
        assertFailsWith<UnsupportedOperationException> {
            (page.items as MutableList<PluginInventoryDescriptor>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (page.items.single().capabilities as MutableList<PluginCapabilityDescriptor>).clear()
        }
    }

    private fun service(
        authorization: AuthorizationProvider,
        provider: PluginInventoryProvider,
    ): PluginInventoryQueryService = PluginInventoryQueryService(
        tenants = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
        },
        users = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("operator-a"), "Operator A")
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = authorization,
        provider = provider,
    )

    private fun descriptor(
        id: String,
        capability: PluginCapabilityType? = null,
    ): PluginInventoryDescriptor = PluginInventoryDescriptor(
        id,
        capability?.let { type -> listOf(PluginCapabilityDescriptor(type, 1)) } ?: emptyList(),
    )

    private class RecordingProvider(private val values: List<PluginInventoryDescriptor>) : PluginInventoryProvider {
        var calls: Int = 0
        override fun inventory(): List<PluginInventoryDescriptor> {
            calls += 1
            return values
        }
    }

    private class RecordingAuthorization(private val allowed: Boolean = true) : AuthorizationProvider {
        val requests = mutableListOf<AuthorizationRequest>()
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            requests += request
            return AuthorizationDecision(allowed)
        }
    }
}
