package com.fileweft.spi.tenant

import com.fileweft.core.context.TenantContext

interface TenantProvider {
    fun currentTenant(): TenantContext
}
