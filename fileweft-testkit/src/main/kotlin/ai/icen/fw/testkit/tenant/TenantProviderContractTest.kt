package ai.icen.fw.testkit.tenant

import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

abstract class TenantProviderContractTest {
    protected abstract val tenantProvider: TenantProvider

    @Test
    fun `returns a non-null tenant context`() {
        val context = tenantProvider.currentTenant()

        assertNotNull(context, "Tenant provider must return a tenant context.")
        assertNotNull(context.tenantId, "Tenant context must carry a tenant identifier.")
    }
}
