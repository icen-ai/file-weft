package ai.icen.fw.spi.tenant

import ai.icen.fw.core.context.TenantContext

interface TenantProvider {
    fun currentTenant(): TenantContext
}
