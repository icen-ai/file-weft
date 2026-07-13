package ai.icen.fw.sample.host

import ai.icen.fw.testkit.tenant.TenantProviderContractTest

class SampleTenantProviderContractTest : TenantProviderContractTest() {

    override val tenantProvider = SampleTenantProvider()
}
