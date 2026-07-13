package ai.icen.fw.sample.host

import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.tenant.TenantProvider

/**
 * Sample host tenant provider that returns a fixed tenant context for
 * contract tests.
 */
class SampleTenantProvider(
    private val tenantId: Identifier = Identifier("sample-tenant"),
) : TenantProvider {

    override fun currentTenant(): TenantContext = TenantContext(tenantId = tenantId)
}
