package ai.icen.fw.dev.api.security

import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

class DevTenantProvider : TenantProvider {
    override fun currentTenant(): TenantContext = DevRequestIdentityContext.current()
        ?.let { principal -> TenantContext(principal.tenantId) }
        ?: throw SecurityException("An authenticated development user is required.")
}

class DevUserRealmProvider(
    private val users: DevUserDirectory,
) : UserRealmProvider {
    override fun currentUser(): UserIdentity? = DevRequestIdentityContext.current()?.toUserIdentity()

    override fun findUser(userId: ai.icen.fw.core.id.Identifier): UserIdentity? = users.findById(userId)?.toUserIdentity()
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
