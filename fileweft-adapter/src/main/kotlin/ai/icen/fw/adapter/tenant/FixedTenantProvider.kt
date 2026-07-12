package ai.icen.fw.adapter.tenant

import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.tenant.TenantProvider

/**
 * Explicit single-tenant adapter for reviewed deployments that do not derive
 * tenant identity from each request. Multi-tenant hosts should provide their
 * own request-scoped [TenantProvider] instead.
 */
class FixedTenantProvider(tenantId: String) : TenantProvider {
    private val tenant = TenantContext(Identifier(tenantId))

    override fun currentTenant(): TenantContext = tenant
}
