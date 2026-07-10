package com.fileweft.dev.api.security

import com.fileweft.core.context.TenantContext
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider

class DevTenantProvider : TenantProvider {
    override fun currentTenant(): TenantContext = DevRequestIdentityContext.current()
        ?.let { principal -> TenantContext(principal.tenantId) }
        ?: throw SecurityException("An authenticated development user is required.")
}

class DevUserRealmProvider(
    private val users: DevUserDirectory,
) : UserRealmProvider {
    override fun currentUser(): UserIdentity? = DevRequestIdentityContext.current()?.toUserIdentity()

    override fun findUser(userId: com.fileweft.core.id.Identifier): UserIdentity? = users.findById(userId)?.toUserIdentity()
}

class DevAuthorizationProvider : AuthorizationProvider {
    override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
        val principal = DevRequestIdentityContext.current()
            ?: return AuthorizationDecision(false, "An authenticated development user is required.")
        if (request.subject.id != principal.id || request.resource.tenantId != principal.tenantId) {
            return AuthorizationDecision(false, "Cross-user or cross-tenant access is forbidden.")
        }
        return if (DevRolePolicy.allows(principal.role, request.action.name)) {
            AuthorizationDecision(true)
        } else {
            AuthorizationDecision(false, "Role ${principal.role.name} cannot perform ${request.action.name}.")
        }
    }
}
